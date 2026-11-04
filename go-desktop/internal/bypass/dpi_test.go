package bypass

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	utls "github.com/refraction-networking/utls"
	"golang.org/x/net/http2"
)

func dpiCertificate(t *testing.T) (tls.Certificate, *x509.CertPool) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "localhost"},
		DNSNames:              []string{"localhost"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(time.Hour),
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		IsCA:                  true,
		BasicConstraintsValid: true,
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

func TestTLSFragmentationAgainstDPI(t *testing.T) {
	cert, roots := dpiCertificate(t)
	listener, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{Certificates: []tls.Certificate{cert}})
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go func(conn net.Conn) {
				defer conn.Close()
				io.Copy(conn, conn)
			}(conn)
		}
	}()
	for _, chunk := range []int{100, 200, 500} {
		raw, err := net.DialTimeout("tcp", listener.Addr().String(), 3*time.Second)
		if err != nil {
			t.Fatal(err)
		}
		started := time.Now()
		conn := tls.Client(&FragmentConn{Conn: raw, Chunk: chunk}, &tls.Config{RootCAs: roots, ServerName: "localhost"})
		if err := conn.Handshake(); err != nil {
			t.Fatalf("chunk %d handshake failed: %v", chunk, err)
		}
		payload := bytes.Repeat([]byte{0x41}, 4096)
		if _, err := conn.Write(payload); err != nil {
			t.Fatal(err)
		}
		reply := make([]byte, len(payload))
		if _, err := io.ReadFull(conn, reply); err != nil {
			t.Fatal(err)
		}
		if !bytes.Equal(reply, payload) {
			t.Fatalf("chunk %d corrupted payload", chunk)
		}
		conn.Close()
		t.Logf("tls_fragment chunk=%d bytes=%d elapsed=%s", chunk, len(payload), time.Since(started))
	}
}

func TestUTLSFingerprintsAgainstDPI(t *testing.T) {
	cert, roots := dpiCertificate(t)
	listener, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{Certificates: []tls.Certificate{cert}, NextProtos: []string{"h2", "http/1.1"}})
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go func(conn net.Conn) {
				defer conn.Close()
				io.Copy(conn, conn)
			}(conn)
		}
	}()
	for _, hello := range []utls.ClientHelloID{utls.HelloChrome_Auto, utls.HelloFirefox_Auto, utls.HelloSafari_Auto} {
		raw, err := net.DialTimeout("tcp", listener.Addr().String(), 3*time.Second)
		if err != nil {
			t.Fatal(err)
		}
		conn := utls.UClient(raw, &utls.Config{RootCAs: roots, ServerName: "localhost"}, hello)
		if err := conn.Handshake(); err != nil {
			t.Fatalf("%s handshake failed: %v", hello.Str(), err)
		}
		if _, err := conn.Write([]byte("utls")); err != nil {
			t.Fatal(err)
		}
		reply := make([]byte, 4)
		if _, err := io.ReadFull(conn, reply); err != nil {
			t.Fatal(err)
		}
		conn.Close()
		t.Logf("utls fingerprint=%s ok", hello.Str())
	}
}

func TestHTTP2MultiplexAgainstDPI(t *testing.T) {
	cert, roots := dpiCertificate(t)
	listener, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{Certificates: []tls.Certificate{cert}, NextProtos: []string{"h2"}})
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		w.Write(body)
	})
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go (&http2.Server{}).ServeConn(conn, &http2.ServeConnOpts{Handler: handler})
		}
	}()
	transport := &http2.Transport{
		TLSClientConfig: &tls.Config{RootCAs: roots, ServerName: "localhost", NextProtos: []string{"h2"}},
	}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport}
	var wg sync.WaitGroup
	var failures atomic.Int32
	for i := 0; i < 16; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			payload := fmt.Sprintf("stream-%d", i)
			response, err := client.Post("https://localhost/echo", "text/plain", bytes.NewBufferString(payload))
			if err != nil {
				failures.Add(1)
				return
			}
			defer response.Body.Close()
			body, _ := io.ReadAll(response.Body)
			if string(body) != payload {
				failures.Add(1)
			}
		}(i)
	}
	wg.Wait()
	if failures.Load() != 0 {
		t.Fatalf("http2 multiplex failures: %d", failures.Load())
	}
}

func TestDoHFallbackAgainstDPI(t *testing.T) {
	var attempts atomic.Int32
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if attempts.Add(1) == 1 {
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "application/dns-message")
		w.Write([]byte{0x00, 0x00, 0x81, 0x80})
	}))
	defer server.Close()
	client := server.Client()
	var answer []byte
	var last error
	for i := 0; i < 2; i++ {
		response, err := client.Post(server.URL, "application/dns-message", bytes.NewReader([]byte{0x00, 0x00, 0x01, 0x00}))
		if err != nil {
			last = err
			continue
		}
		if response.StatusCode != http.StatusOK {
			response.Body.Close()
			last = fmt.Errorf("doh status %d", response.StatusCode)
			continue
		}
		answer, last = io.ReadAll(response.Body)
		response.Body.Close()
		if last == nil {
			break
		}
	}
	if last != nil || len(answer) == 0 {
		t.Fatalf("doh fallback failed: %v", last)
	}
}

func TestPaddingAndJitterBounds(t *testing.T) {
	payload := []byte("hello")
	padded := Pad(payload, 16)
	if len(padded)%16 != 0 {
		t.Fatalf("padding not aligned: %d", len(padded))
	}
	unpadded, ok := Unpad(padded)
	if !ok || !bytes.Equal(unpadded, payload) {
		t.Fatal("unpad failed")
	}
	for i := 0; i < 20; i++ {
		delay := Jitter(5 * time.Millisecond)
		if delay < 0 || delay > 5*time.Millisecond {
			t.Fatalf("jitter out of bounds: %s", delay)
		}
	}
}
