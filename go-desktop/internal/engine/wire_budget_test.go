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

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/bypass"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/tunnel"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
)

type countedConn struct {
	net.Conn
	read    *atomic.Int64
	written *atomic.Int64
}

func (c *countedConn) Read(p []byte) (int, error) {
	n, err := c.Conn.Read(p)
	c.read.Add(int64(n))
	return n, err
}

func (c *countedConn) Write(p []byte) (int, error) {
	n, err := c.Conn.Write(p)
	c.written.Add(int64(n))
	return n, err
}

type countedListener struct {
	net.Listener
	read    *atomic.Int64
	written *atomic.Int64
}

func (l *countedListener) Accept() (net.Conn, error) {
	conn, err := l.Listener.Accept()
	if err != nil {
		return nil, err
	}
	return &countedConn{Conn: conn, read: l.read, written: l.written}, nil
}

func downloadProxy(t *testing.T, size int) (*httptest.Server, *atomic.Int64) {
	t.Helper()
	var wire atomic.Int64
	payload := make([]byte, 32*1024)
	for index := range payload {
		payload[index] = byte(index * 7)
	}
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
	server.Listener = &countedListener{Listener: server.Listener, read: &wire, written: &wire}
	server.StartTLS()
	t.Cleanup(func() {
		server.CloseClientConnections()
		server.Listener.Close()
	})
	return server, &wire
}

func runDownload(t *testing.T, style bypass.Options, size int) (int64, uint64, uint64) {
	t.Helper()
	server, wire := downloadProxy(t, size)
	roots := x509.NewCertPool()
	roots.AddCert(server.Certificate())
	host, portText, err := net.SplitHostPort(server.Listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	port, _ := strconv.Atoi(portText)
	target := tunnel.Target{Host: "example.com", DialHost: host, Port: port, Advanced: style}
	manager := tunnel.NewManager(target)
	defer manager.Close()
	manager.SetProxyRoots(roots)

	clientStack, engine, _ := virtualNetwork(t, &mockResolver{})
	engine.dial = func(destination string, destinationPort int) (io.ReadWriteCloser, error) {
		return manager.Open(destination, destinationPort, "budget-pass", 20*time.Second)
	}
	remote := &tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{198, 18, 0, 44}), Port: 443}
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	stream, err := gonet.DialContextTCP(ctx, clientStack, *remote, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer stream.Close()
	_ = stream.SetReadDeadline(time.Now().Add(90 * time.Second))
	received, err := io.Copy(io.Discard, io.LimitReader(stream, int64(size)))
	if err != nil {
		t.Fatalf("download stopped after %d bytes: %v", received, err)
	}
	if received != int64(size) {
		t.Fatalf("download delivered %d bytes, want %d", received, size)
	}
	retransmits, segments := engine.TCPStats()
	return wire.Load(), retransmits, segments
}

func TestTenMegabyteDownloadWithinWireBudget(t *testing.T) {
	if testing.Short() {
		t.Skip("the wire budget test moves 10 MB")
	}
	const size = 10 << 20
	wire, retransmits, segments := runDownload(t, bypass.Options{}, size)
	overhead := float64(wire) / float64(size)
	t.Logf("wire bytes=%d payload=%d overhead=%.3fx retransmits=%d segments=%d", wire, size, overhead, retransmits, segments)
	if wire > 12<<20 {
		t.Fatalf("a 10 MB download used %d bytes on the wire, the budget is 12 MB", wire)
	}
	if segments > 0 {
		rate := float64(retransmits) / float64(segments)
		if rate >= 0.05 {
			t.Fatalf("retransmission rate %.4f is above the 5%% ceiling (%d/%d)", rate, retransmits, segments)
		}
	}
}

func TestObfuscationStaysWithinWireBudget(t *testing.T) {
	if testing.Short() {
		t.Skip("the wire budget test moves 10 MB")
	}
	const size = 10 << 20
	style := bypass.Options{Padding: true, Jitter: true, TLSFragment: true}
	wire, retransmits, segments := runDownload(t, style, size)
	t.Logf("obfuscated wire bytes=%d overhead=%.3fx retransmits=%d segments=%d", wire, float64(wire)/float64(size), retransmits, segments)
	if wire > 12<<20 {
		t.Fatalf("obfuscation pushed a 10 MB download to %d bytes, the budget is 12 MB", wire)
	}
	if segments > 0 {
		rate := float64(retransmits) / float64(segments)
		if rate >= 0.05 {
			t.Fatalf("retransmission rate %.4f is above the 5%% ceiling (%d/%d)", rate, retransmits, segments)
		}
	}
}

func TestRepeatedSmallRequestsReuseOneConnection(t *testing.T) {
	server, wire := downloadProxy(t, 4096)
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
	for index := 0; index < 25; index++ {
		stream, err := manager.Open("example.com", 443, "budget-pass", 15*time.Second)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := io.CopyN(io.Discard, stream, 4096); err != nil && err != io.EOF {
			t.Fatalf("request %d: %v", index, err)
		}
		stream.Close()
	}
	total := wire.Load()
	t.Logf("25 requests of 4 KB used %d bytes on the wire", total)
	if total > 25*4096+256*1024 {
		t.Fatalf("keep-alive is not reusing the session: %d bytes for 100 KB of payload", total)
	}
}
