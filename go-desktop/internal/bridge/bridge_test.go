package bridge

import (
	"io"
	"net"
	"net/netip"
	"strings"
	"testing"
	"time"
)

func echoDialer(t *testing.T) (Dialer, func()) {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan struct{})
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				close(done)
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				_, _ = io.Copy(c, c)
			}(conn)
		}
	}()
	address := listener.Addr().String()
	return func(host string, port int) (io.ReadWriteCloser, error) {
		return net.DialTimeout("tcp", address, 2*time.Second)
	}, func() { listener.Close(); <-done }
}

func TestBridgeTunnelsConnectAndRejectsOutsiders(t *testing.T) {
	dial, stop := echoDialer(t)
	defer stop()
	server, err := New(Config{Address: "127.0.0.1", Port: 18080, Allow: []netip.Prefix{}, MaxClients: 4}, dial, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := server.Start(); err != nil {
		t.Fatal(err)
	}
	defer server.Stop()
	conn, err := net.Dial("tcp", "127.0.0.1:18080")
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := io.WriteString(conn, "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n"); err != nil {
		t.Fatal(err)
	}
	buffer := make([]byte, 128)
	count, err := conn.Read(buffer)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(buffer[:count]), "200") {
		t.Fatalf("proxy refused: %q", string(buffer[:count]))
	}
	if _, err := conn.Write([]byte("hello-console")); err != nil {
		t.Fatal(err)
	}
	count, err = io.ReadFull(conn, buffer[:13])
	if err != nil || string(buffer[:13]) != "hello-console" {
		t.Fatalf("echo=%q err=%v", string(buffer[:13]), err)
	}
	var stats Stats
	for deadline := time.Now().Add(2 * time.Second); time.Now().Before(deadline); time.Sleep(20 * time.Millisecond) {
		stats = server.Stats()
		if stats.Up > 0 && stats.Down > 0 {
			break
		}
	}
	if !stats.Running || stats.Total != 1 || stats.Up != int64(len("hello-console")) {
		t.Fatalf("stats=%+v", stats)
	}
	if !server.permitted(&net.TCPAddr{IP: net.IPv4(127, 0, 0, 2), Port: 1234}) {
		t.Fatal("loopback must always be allowed")
	}
	if server.permitted(&net.TCPAddr{IP: net.IPv4(192, 168, 1, 50), Port: 1234}) {
		t.Fatal("an unlisted lan address must be refused")
	}
	server.config.Allow = []netip.Prefix{netip.MustParsePrefix("192.168.1.0/24")}
	if !server.permitted(&net.TCPAddr{IP: net.IPv4(192, 168, 1, 50), Port: 1234}) {
		t.Fatal("the allowlist must admit the console subnet")
	}
}

func TestBridgeRequiresCredentialsWhenSet(t *testing.T) {
	dial, stop := echoDialer(t)
	defer stop()
	server, err := New(Config{Address: "127.0.0.1", Port: 18081, User: "console", Pass: "s3cret", MaxClients: 2}, dial, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := server.Start(); err != nil {
		t.Fatal(err)
	}
	defer server.Stop()
	conn, err := net.Dial("tcp", "127.0.0.1:18081")
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	_, _ = io.WriteString(conn, "CONNECT example.com:443 HTTP/1.1\r\n\r\n")
	buffer := make([]byte, 128)
	count, _ := conn.Read(buffer)
	if !strings.Contains(string(buffer[:count]), "407") {
		t.Fatalf("expected a challenge, got %q", string(buffer[:count]))
	}
	second, err := net.Dial("tcp", "127.0.0.1:18081")
	if err != nil {
		t.Fatal(err)
	}
	defer second.Close()
	_ = second.SetDeadline(time.Now().Add(5 * time.Second))
	_, _ = io.WriteString(second, "CONNECT example.com:443 HTTP/1.1\r\nProxy-Authorization: Basic Y29uc29sZTpzM2NyZXQ=\r\n\r\n")
	count, err = second.Read(buffer)
	if err != nil || !strings.Contains(string(buffer[:count]), "200") {
		t.Fatalf("authorized request refused: %q err=%v", string(buffer[:count]), err)
	}
}

func TestSplitTargetHandlesIPv6AndPorts(t *testing.T) {
	host, port, err := splitTarget("[2001:db8::1]:8443", 443)
	if err != nil || host != "2001:db8::1" || port != 8443 {
		t.Fatalf("host=%s port=%d err=%v", host, port, err)
	}
	host, port, err = splitTarget("example.com", 80)
	if err != nil || host != "example.com" || port != 80 {
		t.Fatalf("host=%s port=%d err=%v", host, port, err)
	}
	if _, _, err := splitTarget("bad host:80", 80); err == nil {
		t.Fatal("a host with spaces must be refused")
	}
}
