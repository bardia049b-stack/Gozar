package tunnel

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"io"
	"math/big"
	"net"
	"net/http"
	"sync"
	"testing"
	"time"

	"github.com/quic-go/quic-go/http3"
	"golang.org/x/net/http2"
)

func localCertificate(t *testing.T) (tls.Certificate, *x509.CertPool) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{
		SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "localhost"}, DNSNames: []string{"localhost"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour),
		IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature,
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	roots := x509.NewCertPool()
	roots.AddCert(parsed)
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}, roots
}

func TestHTTP3ConnectReusesSessionAndPreservesStream(t *testing.T) {
	cert, roots := localCertificate(t)
	packet, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	var mu sync.Mutex
	peers := make([]string, 0, 2)
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodConnect || r.Host != "example.org:443" || r.Header.Get("Proxy-Authorization") != "Bearer fake-pass" {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		mu.Lock()
		peers = append(peers, r.RemoteAddr)
		mu.Unlock()
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		data := make([]byte, 5)
		if _, err := io.ReadFull(r.Body, data); err == nil {
			_, _ = w.Write(data)
			w.(http.Flusher).Flush()
		}
	})
	server := &http3.Server{TLSConfig: &tls.Config{Certificates: []tls.Certificate{cert}}, Handler: handler}
	go func() { _ = server.Serve(packet) }()
	defer server.Close()
	manager := NewManager(Target{Host: "localhost", DialHost: "127.0.0.1", Port: packet.LocalAddr().(*net.UDPAddr).Port})
	manager.testTLS = &tls.Config{RootCAs: roots}
	defer manager.Close()
	for i := 0; i < 2; i++ {
		stream, err := manager.openH3("example.org", 443, "fake-pass", 4*time.Second)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := stream.Write([]byte("hello")); err != nil {
			t.Fatal(err)
		}
		reply := make([]byte, 5)
		if _, err := io.ReadFull(stream, reply); err != nil {
			t.Fatal(err)
		}
		if string(reply) != "hello" {
			t.Fatalf("bad reply %q", reply)
		}
		_ = stream.Close()
	}
	mu.Lock()
	defer mu.Unlock()
	if len(peers) != 2 || peers[0] != peers[1] {
		t.Fatalf("session was not reused: %v", peers)
	}
}

func TestHTTP3RejectsUntrustedProxyCertificate(t *testing.T) {
	cert, _ := localCertificate(t)
	packet, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	server := &http3.Server{TLSConfig: &tls.Config{Certificates: []tls.Certificate{cert}}, Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(200) })}
	go func() { _ = server.Serve(packet) }()
	defer server.Close()
	manager := NewManager(Target{Host: "localhost", DialHost: "127.0.0.1", Port: packet.LocalAddr().(*net.UDPAddr).Port})
	defer manager.Close()
	if stream, err := manager.openH3("example.org", 443, "fake-pass", 3*time.Second); err == nil {
		stream.Close()
		t.Fatal("untrusted proxy certificate was accepted")
	}
}

func TestSelectionRespectsChainAndLearnsWithoutDuplicatingRequests(t *testing.T) {
	chained := NewManager(Target{DialHost: "127.0.0.1", Chain: []Hop{{Scheme: "http", Host: "127.0.0.1", Port: 8080}}})
	defer chained.Close()
	if _, canH3 := chained.choice(); canH3 {
		t.Fatal("HTTP/3 must not bypass an existing TCP proxy chain")
	}
	manager := NewManager(Target{DialHost: "127.0.0.1"})
	defer manager.Close()
	manager.success(false, 200*time.Millisecond)
	manager.success(true, 20*time.Millisecond)
	if h3, _ := manager.choice(); !h3 {
		t.Fatal("the faster healthy transport should be preferred")
	}
	manager.failed(true)
	if h3, _ := manager.choice(); h3 {
		t.Fatal("failed path must cool down")
	}
	manager.Close()
	if _, err := manager.Open("example.org", 443, "fake-pass", time.Second); err == nil {
		t.Fatal("closed manager reopened")
	}
}

func TestBlockedHTTP3FallsBackToPooledHTTP2(t *testing.T) {
	cert, roots := localCertificate(t)
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	var mu sync.Mutex
	peers := make([]string, 0, 2)
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodConnect || r.Header.Get("Proxy-Authorization") != "Bearer fake-pass" {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		mu.Lock()
		peers = append(peers, r.RemoteAddr)
		mu.Unlock()
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
	manager.success(false, 200*time.Millisecond)
	manager.success(true, 20*time.Millisecond)
	for i := 0; i < 2; i++ {
		stream, err := manager.Open("example.org", 443, "fake-pass", time.Second)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := stream.Write([]byte("hello")); err != nil {
			t.Fatal(err)
		}
		reply := make([]byte, 5)
		if _, err := io.ReadFull(stream, reply); err != nil {
			t.Fatal(err)
		}
		if string(reply) != "hello" {
			t.Fatal(string(reply))
		}
		stream.Close()
	}
	mu.Lock()
	defer mu.Unlock()
	if len(peers) != 2 || peers[0] != peers[1] {
		t.Fatalf("HTTP/2 connection was not pooled: %v", peers)
	}
	manager.mu.Lock()
	failures := manager.h3Score.failures
	manager.mu.Unlock()
	if failures == 0 {
		t.Fatal("failed HTTP/3 was not placed in cooldown")
	}
}
