package tunnel

import (
	"crypto/tls"
	"io"
	"net"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/advanced"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/bypass"
	"golang.org/x/net/http2"
)

// dualProxy speaks HTTP/2 CONNECT and, on HTTP/1.1, a real WebSocket upgrade.
// It lets the test prove both that WSS is used when it works and that the
// standard HTTP/2 path takes over when the upgrade is refused.
func dualProxy(t *testing.T, wssPath string) (string, *tls.Config) {
	t.Helper()
	cert, roots := localCertificate(t)
	handler := http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.Method == http.MethodConnect {
			response.WriteHeader(http.StatusOK)
			response.(http.Flusher).Flush()
			buffer := make([]byte, 4096)
			for {
				count, err := request.Body.Read(buffer)
				if count > 0 {
					if _, writeErr := response.Write(buffer[:count]); writeErr != nil {
						return
					}
					response.(http.Flusher).Flush()
				}
				if err != nil {
					return
				}
			}
		}
		if strings.EqualFold(request.Header.Get("Upgrade"), "websocket") && request.URL.Path == wssPath {
			hijacker, ok := response.(http.Hijacker)
			if !ok {
				http.Error(response, "no hijack", http.StatusInternalServerError)
				return
			}
			conn, buffered, err := hijacker.Hijack()
			if err != nil {
				return
			}
			defer conn.Close()
			accept := advanced.AcceptKey(request.Header.Get("Sec-WebSocket-Key"))
			_, err = conn.Write([]byte("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n"))
			if err != nil {
				return
			}
			framed := advanced.NewWebSocketConn(conn, buffered, false)
			done := make(chan struct{}, 1)
			go func() {
				_, _ = io.Copy(framed, framed)
				done <- struct{}{}
			}()
			<-done
			return
		}
		http.Error(response, "not found", http.StatusNotFound)
	})
	server := &http.Server{
		Handler:   handler,
		TLSConfig: &tls.Config{Certificates: []tls.Certificate{cert}, NextProtos: []string{"h2", "http/1.1"}},
	}
	if err := http2.ConfigureServer(server, &http2.Server{}); err != nil {
		t.Fatal(err)
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { server.Close(); listener.Close() })
	go server.ServeTLS(listener, "", "")
	return listener.Addr().String(), &tls.Config{RootCAs: roots}
}

func TestManagerUsesWebSocketTransportWhenEnabled(t *testing.T) {
	address, clientTLS := dualProxy(t, "/gozar-tunnel")
	host, portText, err := net.SplitHostPort(address)
	if err != nil {
		t.Fatal(err)
	}
	port := 0
	for _, r := range portText {
		port = port*10 + int(r-'0')
	}
	manager := NewManager(Target{
		Host: "localhost", Port: port, DialHost: host,
		Advanced: bypass.Options{WSS: true},
		Params:   advanced.Params{WSSPath: "/gozar-tunnel"},
	})
	manager.testTLS = clientTLS
	defer manager.Close()
	stream, err := manager.Open("example.org", 443, "pass", 12*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer stream.Close()
	if manager.LastAdvancedPath() != advanced.PathWSS {
		t.Fatalf("path = %q, want wss", manager.LastAdvancedPath())
	}
	payload := []byte("websocket-tunnel")
	_ = stream.SetDeadline(time.Now().Add(8 * time.Second))
	if _, err := stream.Write(payload); err != nil {
		t.Fatal(err)
	}
	got := make([]byte, len(payload))
	if _, err := io.ReadFull(stream, got); err != nil {
		t.Fatal(err)
	}
	if string(got) != string(payload) {
		t.Fatalf("echo mismatch: %q", got)
	}
}

func TestWebSocketFailureFallsBackToHTTP2(t *testing.T) {
	// The proxy only upgrades /other, so the enabled WSS path must fail and the
	// standard HTTP/2 CONNECT path must serve the stream instead.
	address, clientTLS := dualProxy(t, "/other")
	host, portText, _ := net.SplitHostPort(address)
	port := 0
	for _, r := range portText {
		port = port*10 + int(r-'0')
	}
	manager := NewManager(Target{
		Host: "localhost", Port: port, DialHost: host,
		Advanced: bypass.Options{WSS: true},
		Params:   advanced.Params{WSSPath: "/gozar-tunnel"},
	})
	manager.testTLS = clientTLS
	defer manager.Close()
	stream, err := manager.Open("example.org", 443, "pass", 20*time.Second)
	if err != nil {
		t.Fatalf("fallback failed: %v", err)
	}
	defer stream.Close()
	if manager.LastAdvancedPath() != "" {
		t.Fatalf("expected the standard path, got %q", manager.LastAdvancedPath())
	}
	_ = stream.SetDeadline(time.Now().Add(8 * time.Second))
	if _, err := stream.Write([]byte("fallback")); err != nil {
		t.Fatal(err)
	}
	got := make([]byte, 8)
	if _, err := io.ReadFull(stream, got); err != nil {
		t.Fatal(err)
	}
	if string(got) != "fallback" {
		t.Fatalf("echo mismatch: %q", got)
	}
}

func TestAdvancedDisabledByDefaultKeepsStandardDial(t *testing.T) {
	manager := NewManager(Target{Host: "proxy.example", Port: 443, DialHost: "127.0.0.1"})
	defer manager.Close()
	if manager.advancedEnabled() {
		t.Fatal("an advanced transport is enabled by default")
	}
	if manager.LastAdvancedPath() != "" {
		t.Fatal("a default manager reports an advanced path")
	}
}
