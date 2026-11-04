package warp

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"math/big"
	"net"
	"strconv"
	"testing"
	"time"
)

func TestDefaultScannerIncludesWarpPort(t *testing.T) {
	scanner := DefaultScanner()
	if len(scanner.Endpoints) == 0 || len(scanner.Ports) == 0 {
		t.Fatal("scanner has no endpoints or ports")
	}
	found := false
	for _, port := range scanner.Ports {
		if port == DefaultPort {
			found = true
		}
	}
	if !found {
		t.Fatalf("scanner does not include default WARP port: %+v", scanner.Ports)
	}
}

func TestScannerRanksReachableEndpoint(t *testing.T) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := x509.Certificate{
		SerialNumber: big.NewInt(1),
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
		IPAddresses:  []net.IP{net.ParseIP("127.0.0.1")},
	}
	der, err := x509.CreateCertificate(rand.Reader, &template, &template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	listener, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{
		Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: key}},
		NextProtos:   []string{"h2"},
	})
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
			if tlsConn, ok := conn.(*tls.Conn); ok {
				_ = tlsConn.Handshake()
			}
			conn.Close()
		}
	}()
	host, portText, err := net.SplitHostPort(listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	port, err := strconv.Atoi(portText)
	if err != nil {
		t.Fatal(err)
	}
	scanner := &Scanner{Endpoints: []string{host}, Ports: []int{port}, Timeout: 2 * time.Second, Workers: 1}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	results := scanner.Scan(ctx)
	if len(results) != 1 || !results[0].OK {
		t.Fatalf("reachable endpoint was not ranked OK: %+v", results)
	}
	best, ok := scanner.Best(ctx)
	if !ok || best != listener.Addr().String() {
		t.Fatalf("bad best endpoint: %s %v", best, ok)
	}
}
