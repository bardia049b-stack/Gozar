package tunnel

import (
	"bytes"
	"crypto/sha256"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"os"
	"runtime"
	"sync"
	"testing"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/engine"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/wintun"
	"golang.org/x/net/http2"
)

func TestLiveKernelTunnel(t *testing.T) {
	if os.Getenv("GOZAR_LIVE_TUN_TEST") != "1" {
		t.Skip("requires an isolated, privileged kernel tunnel environment")
	}
	if err := wintun.Ensure(); err != nil {
		t.Fatal(err)
	}
	cert, roots := localCertificate(t)
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	var lock sync.Mutex
	requests := 0
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodConnect || r.Host != "198.18.0.80:443" || r.Header.Get("Proxy-Authorization") != "Bearer fake-pass" {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		lock.Lock()
		requests++
		lock.Unlock()
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		hash := sha256.New()
		if _, err := io.Copy(hash, r.Body); err == nil {
			_, _ = w.Write(hash.Sum(nil))
			w.(http.Flusher).Flush()
		}
	})
	go func() {
		for {
			raw, err := listener.Accept()
			if err != nil {
				return
			}
			go func() {
				conn := tls.Server(raw, &tls.Config{Certificates: []tls.Certificate{cert}, NextProtos: []string{"h2"}})
				defer conn.Close()
				if conn.Handshake() == nil {
					(&http2.Server{}).ServeConn(conn, &http2.ServeConnOpts{Handler: handler})
				}
			}()
		}
	}()
	manager := NewManager(Target{Host: "localhost", DialHost: "127.0.0.1", Port: listener.Addr().(*net.TCPAddr).Port})
	manager.testTLS = &tls.Config{RootCAs: roots}
	defer manager.Close()
	var keep []netip.Addr
	if raw := os.Getenv("GOZAR_PROTECT_TEST_IP"); raw != "" {
		address, err := netip.ParseAddr(raw)
		if err != nil {
			t.Fatal(err)
		}
		keep = append(keep, address)
	}
	device, err := engine.Start("localhost", func(host string, port int) (io.ReadWriteCloser, error) {
		return manager.Open(host, port, "fake-pass", 10*time.Second)
	}, keep)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(device.Stop)
	const count = 4
	payload := bytes.Repeat([]byte("Gozar-TUN-H2-CONNECT-"), 32768)
	digest := sha256.Sum256(payload)
	results := make(chan error, count)
	for i := 0; i < count; i++ {
		go func() {
			conn, err := net.DialTimeout("tcp4", "198.18.0.80:443", 10*time.Second)
			if err != nil {
				results <- err
				return
			}
			defer conn.Close()
			_ = conn.SetDeadline(time.Now().Add(20 * time.Second))
			if _, err := conn.Write(payload); err != nil {
				results <- err
				return
			}
			if err := conn.(*net.TCPConn).CloseWrite(); err != nil {
				results <- err
				return
			}
			result := make([]byte, len(digest))
			if _, err := io.ReadFull(conn, result); err != nil {
				results <- err
				return
			}
			if !bytes.Equal(result, digest[:]) {
				results <- fmt.Errorf("incorrect response digest")
				return
			}
			results <- nil
		}()
	}
	for i := 0; i < count; i++ {
		if err := <-results; err != nil {
			t.Fatal(err)
		}
	}
	lock.Lock()
	got := requests
	lock.Unlock()
	if got != count {
		t.Fatalf("proxy received %d connections, want %d", got, count)
	}
	if runtime.GOOS == "linux" && os.Getenv("GOZAR_LIVE_DNS_CHANGE_TEST") == "1" {
		if err := os.WriteFile("/etc/resolv.conf", []byte("nameserver 192.0.2.99\n"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	device.Stop()
	t.Logf("real %s packet path passed through a local TLS-verified HTTP/2 CONNECT proxy", device.Device())
}
