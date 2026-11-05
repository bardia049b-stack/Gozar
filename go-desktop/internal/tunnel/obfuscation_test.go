package tunnel

import (
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"sync"
	"testing"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/bypass"
)

type paddingRecord struct {
	mu      sync.Mutex
	padding []string
	arrival []time.Time
}

func (r *paddingRecord) add(value string) {
	r.mu.Lock()
	r.padding = append(r.padding, value)
	r.arrival = append(r.arrival, time.Now())
	r.mu.Unlock()
}

func (r *paddingRecord) snapshot() ([]string, []time.Time) {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]string(nil), r.padding...), append([]time.Time(nil), r.arrival...)
}

func recordingProxy(t *testing.T, record *paddingRecord) (string, *x509.CertPool, func()) {
	t.Helper()
	cert, roots := localCertificate(t)
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
		if request.Method != http.MethodConnect {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		record.add(request.Header.Get("X-Padding"))
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		_, _ = io.Copy(w, request.Body)
	}))
	server.EnableHTTP2 = true
	server.TLS = &tls.Config{Certificates: []tls.Certificate{cert}}
	server.StartTLS()
	stop := func() {
		server.CloseClientConnections()
		server.Listener.Close()
	}
	return server.Listener.Addr().String(), roots, stop
}

func TestPaddingHeaderReachesTheProxy(t *testing.T) {
	record := &paddingRecord{}
	address, roots, stop := recordingProxy(t, record)
	defer stop()
	host, portText, err := net.SplitHostPort(address)
	if err != nil {
		t.Fatal(err)
	}
	port, _ := strconv.Atoi(portText)
	manager := NewManager(Target{Host: "localhost", DialHost: host, Port: port, Advanced: bypass.Options{Padding: true}})
	defer manager.Close()
	manager.testTLS = &tls.Config{RootCAs: roots}
	stream, err := manager.Open("example.com", 443, "pass", 8*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	stream.Close()
	values, _ := record.snapshot()
	if len(values) == 0 {
		t.Fatal("the proxy never saw a CONNECT")
	}
	if values[0] == "" {
		t.Fatal("padding was enabled but no X-Padding header was sent")
	}
	raw, err := base64.StdEncoding.DecodeString(values[0])
	if err != nil {
		t.Fatalf("the padding header is not base64: %v", err)
	}
	if len(raw) == 0 || len(raw)%128 != 0 {
		t.Fatalf("padding is %d bytes, expected a multiple of 128", len(raw))
	}
	plain, ok := bypass.Unpad(raw)
	if !ok || len(plain) != 0 {
		t.Fatal("the padding blob does not unpad cleanly")
	}
}

func TestNoPaddingHeaderWhenDisabled(t *testing.T) {
	record := &paddingRecord{}
	address, roots, stop := recordingProxy(t, record)
	defer stop()
	host, portText, _ := net.SplitHostPort(address)
	port, _ := strconv.Atoi(portText)
	manager := NewManager(Target{Host: "localhost", DialHost: host, Port: port})
	defer manager.Close()
	manager.testTLS = &tls.Config{RootCAs: roots}
	stream, err := manager.Open("example.com", 443, "pass", 8*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	stream.Close()
	values, _ := record.snapshot()
	if len(values) == 0 || values[0] != "" {
		t.Fatalf("padding leaked while disabled: %q", values)
	}
}

func TestJitterDelaysTheConnect(t *testing.T) {
	record := &paddingRecord{}
	address, roots, stop := recordingProxy(t, record)
	defer stop()
	host, portText, _ := net.SplitHostPort(address)
	port, _ := strconv.Atoi(portText)
	manager := NewManager(Target{Host: "localhost", DialHost: host, Port: port, Advanced: bypass.Options{Jitter: true}})
	defer manager.Close()
	manager.testTLS = &tls.Config{RootCAs: roots}
	start := time.Now()
	for index := 0; index < 8; index++ {
		stream, err := manager.Open("example.com", 443, "pass", 8*time.Second)
		if err != nil {
			t.Fatal(err)
		}
		stream.Close()
	}
	elapsed := time.Since(start)
	if elapsed < 80*time.Millisecond {
		t.Fatalf("eight jittered connects took only %s, the delay is not on the write path", elapsed)
	}
}

func clientTLS(roots *x509.CertPool) *tls.Config {
	return &tls.Config{RootCAs: roots, ServerName: "localhost"}
}

type countingConn struct {
	net.Conn
	mu    sync.Mutex
	sizes []int
}

func (c *countingConn) Write(p []byte) (int, error) {
	n, err := c.Conn.Write(p)
	if n > 0 {
		c.mu.Lock()
		c.sizes = append(c.sizes, n)
		c.mu.Unlock()
	}
	return n, err
}

func (c *countingConn) writeSizes() []int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return append([]int(nil), c.sizes...)
}

func TestFragmentConnSplitsTheClientHello(t *testing.T) {
	cert, roots := localCertificate(t)
	listener, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{Certificates: []tls.Certificate{cert}})
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	raw, err := net.Dial("tcp", listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	accepted := make(chan net.Conn, 1)
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			accepted <- nil
			return
		}
		secured := conn.(*tls.Conn)
		secured.SetDeadline(time.Now().Add(8 * time.Second))
		if err := secured.Handshake(); err != nil {
			accepted <- nil
			return
		}
		accepted <- secured
	}()
	fragmented := &bypass.FragmentConn{Conn: raw, Chunk: 96}
	client := tls.Client(fragmented, clientTLS(roots))
	client.SetDeadline(time.Now().Add(8 * time.Second))
	if err := client.Handshake(); err != nil {
		t.Fatalf("fragmenting the handshake broke tls: %v", err)
	}
	server := <-accepted
	if server == nil {
		t.Fatal("the proxy never accepted the connection")
	}
	defer server.Close()
	defer client.Close()
	if _, err := client.Write([]byte("ping")); err != nil {
		t.Fatal(err)
	}
	buffer := make([]byte, 4)
	if _, err := io.ReadFull(server, buffer); err != nil {
		t.Fatal(err)
	}
	if string(buffer) != "ping" {
		t.Fatalf("payload was corrupted: %q", buffer)
	}
}

func TestFragmentConnChunkingIsExact(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	counter := &countingConn{Conn: client}
	fragmented := &bypass.FragmentConn{Conn: counter, Chunk: 96}
	payload := make([]byte, 500)
	for index := range payload {
		payload[index] = byte(index)
	}
	done := make(chan error, 1)
	go func() {
		_, err := fragmented.Write(payload)
		done <- err
	}()
	received := make([]byte, 0, len(payload))
	buffer := make([]byte, 4096)
	for len(received) < len(payload) {
		n, err := server.Read(buffer)
		if err != nil {
			t.Fatal(err)
		}
		received = append(received, buffer[:n]...)
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if string(received) != string(payload) {
		t.Fatal("fragmentation reordered the payload")
	}
	sizes := counter.writeSizes()
	if len(sizes) < 5 {
		t.Fatalf("a 500 byte write produced %d writes, expected at least 5 chunks of 96", len(sizes))
	}
	for index, size := range sizes[:len(sizes)-1] {
		if size != 96 {
			t.Fatalf("chunk %d is %d bytes, expected 96", index, size)
		}
	}
}
