package engine

import (
	"context"
	"crypto/x509"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"sync/atomic"
	"testing"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/tunnel"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
)

type outageListener struct {
	net.Listener
	down *atomic.Bool
}

func (l *outageListener) Accept() (net.Conn, error) {
	for {
		conn, err := l.Listener.Accept()
		if err != nil {
			return nil, err
		}
		if l.down.Load() {
			conn.Close()
			continue
		}
		return conn, nil
	}
}

func outageProxy(t *testing.T, size int) (*httptest.Server, *atomic.Bool) {
	t.Helper()
	var down atomic.Bool
	payload := make([]byte, 16*1024)
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodConnect {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		remaining := size
		for remaining > 0 {
			chunk := len(payload)
			if chunk > remaining {
				chunk = remaining
			}
			n, err := w.Write(payload[:chunk])
			if err != nil {
				return
			}
			remaining -= n
			w.(http.Flusher).Flush()
		}
	}))
	server.EnableHTTP2 = true
	server.Listener = &outageListener{Listener: server.Listener, down: &down}
	server.StartTLS()
	t.Cleanup(func() {
		server.CloseClientConnections()
		server.Listener.Close()
	})
	return server, &down
}

func TestTunnelRecoversAfterTheNetworkDrops(t *testing.T) {
	const size = 64 * 1024
	server, down := outageProxy(t, size)
	roots := x509.NewCertPool()
	roots.AddCert(server.Certificate())
	host, portText, err := net.SplitHostPort(server.Listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	port, _ := strconv.Atoi(portText)
	manager := tunnel.NewManager(tunnel.Target{Host: "example.com", DialHost: host, Port: port})
	defer manager.Close()
	manager.SetProxyRoots(roots)

	clientStack, engine, _ := virtualNetwork(t, &mockResolver{})
	engine.dial = func(destination string, destinationPort int) (io.ReadWriteCloser, error) {
		return manager.Open(destination, destinationPort, "recovery-pass", 5*time.Second)
	}

	download := func() error {
		remote := &tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{198, 18, 0, 77}), Port: 443}
		ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
		defer cancel()
		stream, err := gonet.DialContextTCP(ctx, clientStack, *remote, ipv4.ProtocolNumber)
		if err != nil {
			return err
		}
		defer stream.Close()
		_ = stream.SetReadDeadline(time.Now().Add(20 * time.Second))
		n, err := io.Copy(io.Discard, io.LimitReader(stream, size))
		if err != nil {
			return err
		}
		if n != size {
			return io.ErrUnexpectedEOF
		}
		return nil
	}

	if err := download(); err != nil {
		t.Fatalf("the first download failed before any outage: %v", err)
	}

	down.Store(true)
	manager.Close()
	replacement := tunnel.NewManager(tunnel.Target{Host: "example.com", DialHost: host, Port: port})
	defer replacement.Close()
	replacement.SetProxyRoots(roots)
	engine.dial = func(destination string, destinationPort int) (io.ReadWriteCloser, error) {
		return replacement.Open(destination, destinationPort, "recovery-pass", 3*time.Second)
	}
	if err := download(); err == nil {
		t.Fatal("the tunnel kept working while the path was down")
	} else {
		t.Logf("during the outage the tunnel failed as expected: %v", err)
	}

	down.Store(false)
	fresh := tunnel.NewManager(tunnel.Target{Host: "example.com", DialHost: host, Port: port})
	defer fresh.Close()
	fresh.SetProxyRoots(roots)
	engine.dial = func(destination string, destinationPort int) (io.ReadWriteCloser, error) {
		return fresh.Open(destination, destinationPort, "recovery-pass", 8*time.Second)
	}
	deadline := time.Now().Add(30 * time.Second)
	var last error
	for time.Now().Before(deadline) {
		if last = download(); last == nil {
			t.Log("the tunnel recovered after the path came back")
			return
		}
		time.Sleep(500 * time.Millisecond)
	}
	t.Fatalf("the tunnel never recovered: %v", last)
}
