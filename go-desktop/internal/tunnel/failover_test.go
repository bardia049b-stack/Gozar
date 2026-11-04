package tunnel

import (
	"crypto/tls"
	"io"
	"net"
	"net/http"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/net/http2"
)

// echoProxy is a real HTTP/2 CONNECT proxy that echoes five bytes. kill()
// closes the listener *and* every live connection, which is what a dead proxy
// looks like to a client that pools HTTP/2 sessions.
func echoProxy(t *testing.T, cert tls.Certificate, status int) (int, *atomic.Int64, func()) {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	var hits atomic.Int64
	var mu sync.Mutex
	live := make(map[net.Conn]struct{})
	closed := false
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		if r.Method != http.MethodConnect || r.Header.Get("Proxy-Authorization") != "Bearer fake-pass" {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		if status != 0 {
			w.WriteHeader(status)
			return
		}
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		data := make([]byte, 5)
		if _, err := io.ReadFull(r.Body, data); err == nil {
			_, _ = w.Write(data)
			w.(http.Flusher).Flush()
		}
	})
	go func() {
		for {
			raw, err := listener.Accept()
			if err != nil {
				return
			}
			mu.Lock()
			if closed {
				mu.Unlock()
				raw.Close()
				return
			}
			live[raw] = struct{}{}
			mu.Unlock()
			go func() {
				conn := tls.Server(raw, &tls.Config{Certificates: []tls.Certificate{cert}, NextProtos: []string{"h2"}})
				defer func() {
					conn.Close()
					mu.Lock()
					delete(live, raw)
					mu.Unlock()
				}()
				if conn.Handshake() == nil {
					(&http2.Server{}).ServeConn(conn, &http2.ServeConnOpts{Handler: handler})
				}
			}()
		}
	}()
	kill := func() {
		listener.Close()
		mu.Lock()
		closed = true
		peers := make([]net.Conn, 0, len(live))
		for conn := range live {
			peers = append(peers, conn)
		}
		live = make(map[net.Conn]struct{})
		mu.Unlock()
		for _, conn := range peers {
			conn.Close()
		}
	}
	return listener.Addr().(*net.TCPAddr).Port, &hits, kill
}

func roundTrip(t *testing.T, manager *Manager) {
	t.Helper()
	stream, err := manager.Open("example.org", 443, "fake-pass", 9*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer stream.Close()
	if _, err := stream.Write([]byte("hello")); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, 5)
	if _, err := io.ReadFull(stream, reply); err != nil {
		t.Fatal(err)
	}
	if string(reply) != "hello" {
		t.Fatalf("unexpected echo %q", reply)
	}
}

// Requirement: when the main proxy dies the session must continue on a backup,
// and it must stay there instead of flapping back to a dead primary.
func TestDeadPrimaryFailsOverToBackupAndStaysThere(t *testing.T) {
	cert, roots := localCertificate(t)
	primaryPort, primaryHits, stopPrimary := echoProxy(t, cert, 0)
	defer stopPrimary()
	backupPort, backupHits, stopBackup := echoProxy(t, cert, 0)
	defer stopBackup()
	manager := NewManager(Target{Host: "localhost", DialHost: "127.0.0.1", Port: primaryPort})
	manager.testTLS = &tls.Config{RootCAs: roots}
	defer manager.Close()
	manager.SetBackups([]Target{{Host: "localhost", DialHost: "127.0.0.1", Port: backupPort}})
	manager.success(false, 100*time.Millisecond)

	roundTrip(t, manager)
	if primaryHits.Load() != 1 || backupHits.Load() != 0 {
		t.Fatalf("the primary should have served the first stream: %d/%d", primaryHits.Load(), backupHits.Load())
	}
	if manager.ActiveEndpoint() != "localhost:"+strconv.Itoa(primaryPort) {
		t.Fatalf("unexpected active endpoint %s", manager.ActiveEndpoint())
	}

	// The primary dies.
	stopPrimary()
	roundTrip(t, manager)
	if backupHits.Load() != 1 {
		t.Fatalf("the backup did not take over: %d", backupHits.Load())
	}
	if manager.ActiveEndpoint() != "localhost:"+strconv.Itoa(backupPort) {
		t.Fatalf("the manager did not stick to the backup: %s", manager.ActiveEndpoint())
	}
	roundTrip(t, manager)
	if backupHits.Load() != 2 || primaryHits.Load() != 1 {
		t.Fatalf("the manager flapped back to the dead primary: %d/%d", primaryHits.Load(), backupHits.Load())
	}
}

// A credential or quota refusal must surface to the user, not be hidden by
// silently switching proxies.
func TestProxyRefusalIsNotHiddenByFailover(t *testing.T) {
	cert, roots := localCertificate(t)
	refusingPort, refusingHits, stopRefusing := echoProxy(t, cert, http.StatusProxyAuthRequired)
	defer stopRefusing()
	backupPort, backupHits, stopBackup := echoProxy(t, cert, 0)
	defer stopBackup()
	manager := NewManager(Target{Host: "localhost", DialHost: "127.0.0.1", Port: refusingPort})
	manager.testTLS = &tls.Config{RootCAs: roots}
	defer manager.Close()
	manager.SetBackups([]Target{{Host: "localhost", DialHost: "127.0.0.1", Port: backupPort}})
	manager.success(false, 100*time.Millisecond)
	if _, err := manager.Open("example.org", 443, "fake-pass", 9*time.Second); err == nil {
		t.Fatal("a refusal was reported as success")
	}
	if refusingHits.Load() == 0 {
		t.Fatal("the refusing proxy was never asked")
	}
	if backupHits.Load() != 0 {
		t.Fatal("failover hid an authentication refusal")
	}
}
