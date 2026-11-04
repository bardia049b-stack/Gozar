package advanced_test

import (
	"bytes"
	"context"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"io"
	"math/big"
	"net"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/advanced"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/advancedtest"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/bypass"
)

func certificate(t *testing.T, names ...string) (tls.Certificate, *x509.CertPool) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := x509.Certificate{
		SerialNumber: big.NewInt(time.Now().UnixNano()),
		Subject:      pkix.Name{CommonName: names[0]},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(24 * time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		DNSNames:     names,
		IPAddresses:  []net.IP{net.ParseIP("127.0.0.1")},
		IsCA:         true,
		BasicConstraintsValid: true,
	}
	der, err := x509.CreateCertificate(rand.Reader, &template, &template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	pool := x509.NewCertPool()
	pool.AddCert(parsed)
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key, Leaf: parsed}, pool
}

func echoServer(t *testing.T) string {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { listener.Close() })
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				_, _ = io.Copy(c, c)
			}(conn)
		}
	}()
	return listener.Addr().String()
}

func TestAdvancedParamsValidation(t *testing.T) {
	if err := (advanced.Params{}).Validate(); err != nil {
		t.Fatal(err)
	}
	defaults := advanced.Params{}.WithDefaults()
	if defaults.DoQServer == "" || defaults.WSSPath != "/gozar-tunnel" {
		t.Fatalf("bad defaults: %+v", defaults)
	}
	for _, bad := range []advanced.Params{
		{DoQServer: "no-port"},
		{DoQServer: "host:0"},
		{FrontHost: "bad host"},
		{WSSPath: "relative"},
		{ECHConfigList: "not base64!!"},
		{ECHConfigList: base64.StdEncoding.EncodeToString([]byte{1, 2, 3})},
	} {
		if err := bad.Validate(); err == nil {
			t.Fatalf("invalid params accepted: %+v", bad)
		}
	}
}

func TestECHConfigListRoundTrip(t *testing.T) {
	key, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	list, err := advanced.BuildECHConfigList(advanced.ECHConfigSpec{
		ConfigID: 7, KEMID: 0x0020, PublicKey: key.PublicKey().Bytes(), PublicName: "cover.example",
	})
	if err != nil {
		t.Fatal(err)
	}
	configs, err := advanced.ParseECHConfigList(list)
	if err != nil || len(configs) != 1 {
		t.Fatalf("parse failed: %+v %v", configs, err)
	}
	if configs[0].ConfigID != 7 || configs[0].PublicName != "cover.example" || !bytes.Equal(configs[0].PublicKey, key.PublicKey().Bytes()) {
		t.Fatalf("bad config: %+v", configs[0])
	}
	params := advanced.Params{ECHConfigList: base64.StdEncoding.EncodeToString(list)}
	if err := params.Validate(); err != nil {
		t.Fatal(err)
	}
}

func TestPlainDialIsTheDefaultPath(t *testing.T) {
	address := echoServer(t)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, path, err := advanced.DialTCP(ctx, address, bypass.Default(), 3*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if path != advanced.PathTCP {
		t.Fatalf("default path = %s, want tcp", path)
	}
}

func TestTFOAndMPTCPConnectAndFallBack(t *testing.T) {
	address := echoServer(t)
	for _, options := range []bypass.Options{
		{TFO: true},
		{MPTCP: true},
		{TFO: true, MPTCP: true},
	} {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		conn, path, err := advanced.DialTCP(ctx, address, options, 3*time.Second)
		cancel()
		if err != nil {
			t.Fatalf("options %+v: %v", options, err)
		}
		payload := []byte("gozar-advanced")
		_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
		if _, err := conn.Write(payload); err != nil {
			conn.Close()
			t.Fatalf("options %+v write: %v", options, err)
		}
		got := make([]byte, len(payload))
		if _, err := io.ReadFull(conn, got); err != nil {
			conn.Close()
			t.Fatalf("options %+v read: %v", options, err)
		}
		conn.Close()
		if !bytes.Equal(got, payload) {
			t.Fatalf("options %+v corrupted data", options)
		}
		if path == "" {
			t.Fatal("no path reported")
		}
		t.Logf("options tfo=%v mptcp=%v used path %s (linux TFO support: %v)", options.TFO, options.MPTCP, path, advanced.FastOpenSupported())
	}
}

func TestMPTCPKernelSupportIsReportedHonestly(t *testing.T) {
	data, err := os.ReadFile("/proc/sys/net/mptcp/enabled")
	if err != nil {
		t.Skipf("this platform does not expose MPTCP: %v", err)
	}
	enabled := strings.TrimSpace(string(data))
	value, _ := strconv.Atoi(enabled)
	if value == 0 {
		t.Skip("the kernel has MPTCP disabled; the dialer falls back to TCP")
	}
	var config net.ListenConfig
	config.SetMultipathTCP(true)
	listener, err := config.Listen(context.Background(), "tcp", "127.0.0.1:0")
	if err != nil {
		t.Skipf("MPTCP listen unavailable: %v", err)
	}
	defer listener.Close()
	go func() {
		conn, err := listener.Accept()
		if err == nil {
			_, _ = io.Copy(conn, conn)
			conn.Close()
		}
	}()
	options := bypass.Options{MPTCP: true}
	conn, path, err := advanced.DialTCP(context.Background(), listener.Addr().String(), options, 3*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if path != advanced.PathMPTCP {
		t.Fatalf("path = %s, want mptcp", path)
	}
	_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := conn.Write([]byte("mptcp")); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, 5)
	if _, err := io.ReadFull(conn, reply); err != nil || string(reply) != "mptcp" {
		t.Fatalf("mptcp echo failed: %q %v", reply, err)
	}
}

func TestECHRealHandshakeAndAutomaticFallback(t *testing.T) {
	cert, roots := certificate(t, "real.example", "cover.example")
	key, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	list, err := advanced.BuildECHConfigList(advanced.ECHConfigSpec{
		ConfigID: 3, KEMID: 0x0020, PublicKey: key.PublicKey().Bytes(), PublicName: "cover.example",
	})
	if err != nil {
		t.Fatal(err)
	}
	serverConfig := &tls.Config{
		Certificates: []tls.Certificate{cert},
		MinVersion:   tls.VersionTLS13,
		EncryptedClientHelloKeys: []tls.EncryptedClientHelloKey{{
			Config: list[2:], PrivateKey: key.Bytes(), SendAsRetry: true,
		}},
	}
	listener, err := tls.Listen("tcp", "127.0.0.1:0", serverConfig)
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	accepted := make(chan bool, 4)
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				tlsConn := c.(*tls.Conn)
				if err := tlsConn.Handshake(); err != nil {
					accepted <- false
					return
				}
				accepted <- tlsConn.ConnectionState().ECHAccepted
				_, _ = io.Copy(c, c)
			}(conn)
		}
	}()
	params := advanced.Params{ECHConfigList: base64.StdEncoding.EncodeToString(list)}
	base := &tls.Config{ServerName: "real.example", RootCAs: roots, MinVersion: tls.VersionTLS13}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	conn, path, err := advanced.DialTLSWithFallback(ctx, listener.Addr().String(), base, bypass.Options{ECH: true}, params, 5*time.Second)
	if err != nil {
		t.Fatalf("ECH handshake failed: %v", err)
	}
	if path != advanced.PathECH {
		t.Fatalf("path = %s, want ech", path)
	}
	select {
	case ok := <-accepted:
		if !ok {
			t.Fatal("the server did not accept ECH")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("no handshake reached the server")
	}
	_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := conn.Write([]byte("ech-ok")); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, 6)
	if _, err := io.ReadFull(conn, reply); err != nil || string(reply) != "ech-ok" {
		t.Fatalf("ech echo failed: %q %v", reply, err)
	}
	conn.Close()

	// A server without ECH keys must not break the tunnel: expect fallback.
	plainListener, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{Certificates: []tls.Certificate{cert}})
	if err != nil {
		t.Fatal(err)
	}
	defer plainListener.Close()
	go func() {
		for {
			conn, err := plainListener.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) { defer c.Close(); _, _ = io.Copy(c, c) }(conn)
		}
	}()
	fallback, fallbackPath, err := advanced.DialTLSWithFallback(ctx, plainListener.Addr().String(), base, bypass.Options{ECH: true}, params, 5*time.Second)
	if err != nil {
		t.Fatalf("ECH fallback failed: %v", err)
	}
	defer fallback.Close()
	if !strings.Contains(fallbackPath, advanced.PathTLS) {
		t.Fatalf("fallback path = %s, want the standard TLS path", fallbackPath)
	}
	_ = fallback.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := fallback.Write([]byte("plain")); err != nil {
		t.Fatal(err)
	}
	reply = make([]byte, 5)
	if _, err := io.ReadFull(fallback, reply); err != nil || string(reply) != "plain" {
		t.Fatalf("fallback echo failed: %q %v", reply, err)
	}
}

func TestDomainFrontingSeparatesSNIFromTargetAndFallsBack(t *testing.T) {
	cert, roots := certificate(t, "real.example", "front.example")
	var sni string
	serverConfig := &tls.Config{
		Certificates: []tls.Certificate{cert},
		GetConfigForClient: func(info *tls.ClientHelloInfo) (*tls.Config, error) {
			sni = info.ServerName
			return nil, nil
		},
	}
	listener, err := tls.Listen("tcp", "127.0.0.1:0", serverConfig)
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
			go func(c net.Conn) { defer c.Close(); _, _ = io.Copy(c, c) }(conn)
		}
	}()
	base := &tls.Config{ServerName: "real.example", RootCAs: roots}
	options := bypass.Options{DomainFronting: true}
	params := advanced.Params{FrontHost: "front.example"}
	config, path, err := advanced.TLSConfig(base, options, params)
	if err != nil {
		t.Fatal(err)
	}
	if path != advanced.PathFront || config.ServerName != "front.example" || base.ServerName != "real.example" {
		t.Fatalf("fronting config wrong: path=%s sni=%s original=%s", path, config.ServerName, base.ServerName)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	conn, used, err := advanced.DialTLSWithFallback(ctx, listener.Addr().String(), base, options, params, 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	conn.Close()
	if used != advanced.PathFront {
		t.Fatalf("path = %s, want fronted", used)
	}
	if sni != "front.example" {
		t.Fatalf("the server saw SNI %q, want the front name", sni)
	}

	// A front name the certificate does not cover must fall back to the real SNI.
	badParams := advanced.Params{FrontHost: "unknown.example"}
	fallback, fallbackPath, err := advanced.DialTLSWithFallback(ctx, listener.Addr().String(), base, options, badParams, 5*time.Second)
	if err != nil {
		t.Fatalf("fronting fallback failed: %v", err)
	}
	defer fallback.Close()
	if !strings.Contains(fallbackPath, advanced.PathTLS) {
		t.Fatalf("fallback path = %s", fallbackPath)
	}
}

func TestWSSRealUpgradeCarriesTunnelBytes(t *testing.T) {
	target := echoServer(t)
	cert, roots := certificate(t, "proxy.example")
	server, err := advancedtest.StartWSS(&tls.Config{Certificates: []tls.Certificate{cert}}, target, "/gozar-tunnel", "pass-1")
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	conn, err := advanced.DialWSS(ctx, server.Addr, &tls.Config{RootCAs: roots, ServerName: "proxy.example"}, "ifconfig.me", 80, "pass-1", advanced.Params{}, 10*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	payload := bytes.Repeat([]byte{0x5a}, 256*1024)
	go func() {
		_, _ = conn.Write(payload)
		if half, ok := conn.(interface{ CloseWrite() error }); ok {
			_ = half.CloseWrite()
		}
	}()
	_ = conn.SetReadDeadline(time.Now().Add(15 * time.Second))
	got := make([]byte, len(payload))
	if _, err := io.ReadFull(conn, got); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatal("the WebSocket tunnel corrupted the payload")
	}
	upgrades, _, host, auth := server.Stats()
	if upgrades != 1 || host != "proxy.example" || auth != "Bearer pass-1" {
		t.Fatalf("server stats: upgrades=%d host=%q auth=%q", upgrades, host, auth)
	}
}

func TestWSSRejectsWrongCredentials(t *testing.T) {
	cert, roots := certificate(t, "proxy.example")
	server, err := advancedtest.StartWSS(&tls.Config{Certificates: []tls.Certificate{cert}}, echoServer(t), "/gozar-tunnel", "right")
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	if _, err := advanced.DialWSS(ctx, server.Addr, &tls.Config{RootCAs: roots, ServerName: "proxy.example"}, "example.com", 443, "wrong", advanced.Params{}, 5*time.Second); err == nil {
		t.Fatal("the WSS proxy accepted a wrong pass")
	}
}
