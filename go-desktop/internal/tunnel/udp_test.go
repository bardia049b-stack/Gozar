package tunnel

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"math/big"
	"net"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/quic-go/quic-go"
	"github.com/quic-go/quic-go/http3"
)

func loopbackCertificate(t *testing.T) tls.Certificate {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := x509.Certificate{
		SerialNumber: big.NewInt(11),
		Subject:      pkix.Name{CommonName: "loopback"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
		IPAddresses:  []net.IP{net.ParseIP("127.0.0.1")},
		DNSNames:     []string{"localhost", "127.0.0.1"},
	}
	der, err := x509.CreateCertificate(rand.Reader, &template, &template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}
}

type echoProxy struct {
	server *http3.Server
	port   int
}

func startEchoProxy(t *testing.T) *echoProxy {
	t.Helper()
	socket, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	if err != nil {
		t.Fatal(err)
	}
	server := &http3.Server{
		TLSConfig:       &tls.Config{Certificates: []tls.Certificate{loopbackCertificate(t)}},
		QUICConfig:      &quic.Config{EnableDatagrams: true},
		EnableDatagrams: true,
	}
	proxy := &echoProxy{server: server, port: socket.LocalAddr().(*net.UDPAddr).Port}
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodConnect {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		w.WriteHeader(http.StatusOK)
		streamer, ok := w.(http3.HTTPStreamer)
		if !ok {
			return
		}
		stream := streamer.HTTPStream()
		for {
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			payload, err := stream.ReceiveDatagram(ctx)
			cancel()
			if err != nil {
				return
			}
			reply := payload
			if err := stream.SendDatagram(reply); err != nil {
				return
			}
		}
	})
	proxy.server.Handler = handler
	go func() { _ = proxy.server.Serve(socket) }()
	t.Cleanup(func() { _ = proxy.server.Close() })
	time.Sleep(150 * time.Millisecond)
	return proxy
}

func TestUDPTunnelCarriesDatagrams(t *testing.T) {
	proxy := startEchoProxy(t)
	target := Target{Host: "127.0.0.1", Port: proxy.port, DialHost: "127.0.0.1"}
	target.insecureForTests = true
	session, err := OpenUDP(target, "echo.example", 443, "test-pass", 6*time.Second)
	if err != nil {
		t.Fatalf("open udp tunnel: %v", err)
	}
	defer session.Close()
	for _, want := range []string{"ping-one", "ping-two"} {
		if err := session.Send([]byte(want)); err != nil {
			t.Fatalf("send %q: %v", want, err)
		}
		ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
		got, err := session.Receive(ctx)
		cancel()
		if err != nil {
			t.Fatalf("receive %q: %v", want, err)
		}
		if string(got) != want {
			t.Fatalf("payload mismatch: got %q want %q", got, want)
		}
	}
}

func TestUDPTunnelRejectsMissingPass(t *testing.T) {
	if _, err := OpenUDP(Target{Host: "127.0.0.1", Port: 1}, "x", 53, "", time.Second); err == nil {
		t.Fatal("an empty proxy pass must be rejected before any network access")
	} else if !strings.Contains(err.Error(), "proxy pass") {
		t.Fatalf("unexpected error: %v", err)
	}
}
