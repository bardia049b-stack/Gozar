package bypass

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"os"
	"testing"
	"time"
)

// TestToxiproxyLatencyDoesNotBreakTLS is enabled only when
// GOZAR_TOXIPROXY_API points at a toxiproxy control API (see CI). It routes a
// TLS session through toxiproxy with latency/bandwidth toxics and verifies the
// application bytes still arrive intact.
func TestToxiproxyLatencyDoesNotBreakTLS(t *testing.T) {
	api := os.Getenv("GOZAR_TOXIPROXY_API")
	if api == "" {
		t.Skip("GOZAR_TOXIPROXY_API not set")
	}
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := x509.Certificate{
		SerialNumber: big.NewInt(time.Now().UnixNano()),
		Subject:      pkix.Name{CommonName: "toxiproxy-test"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		DNSNames:     []string{"localhost"},
		IPAddresses:  []net.IP{net.ParseIP("127.0.0.1")},
	}
	der, err := x509.CreateCertificate(rand.Reader, &template, &template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	cert := tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}
	upstream, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{Certificates: []tls.Certificate{cert}})
	if err != nil {
		t.Fatal(err)
	}
	defer upstream.Close()
	server := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("toxiproxy-ok"))
	})}
	go server.Serve(upstream)
	defer server.Close()

	listenPort := freePort(t)
	proxyName := fmt.Sprintf("gozar-bypass-%d", time.Now().UnixNano())
	create := map[string]any{
		"name":     proxyName,
		"listen":   fmt.Sprintf("127.0.0.1:%d", listenPort),
		"upstream": upstream.Addr().String(),
	}
	body, _ := json.Marshal(create)
	resp, err := http.Post(api+"/proxies", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	if resp.StatusCode != http.StatusCreated && resp.StatusCode != http.StatusOK {
		t.Fatalf("toxiproxy create status %d", resp.StatusCode)
	}
	defer func() {
		req, _ := http.NewRequest(http.MethodDelete, api+"/proxies/"+proxyName, nil)
		if resp, err := http.DefaultClient.Do(req); err == nil {
			io.Copy(io.Discard, resp.Body)
			resp.Body.Close()
		}
	}()
	toxic := map[string]any{
		"name":       "latency",
		"type":       "latency",
		"stream":     "downstream",
		"toxicity":   1.0,
		"attributes": map[string]any{"latency": 25, "jitter": 5},
	}
	toxicBody, _ := json.Marshal(toxic)
	resp, err = http.Post(api+"/proxies/"+proxyName+"/toxics", "application/json", bytes.NewReader(toxicBody))
	if err != nil {
		t.Fatal(err)
	}
	io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusCreated {
		t.Fatalf("toxiproxy toxic status %d", resp.StatusCode)
	}

	roots := x509.NewCertPool()
	roots.AddCert(templateCert(t, der))
	proxyAddr := fmt.Sprintf("127.0.0.1:%d", listenPort)
	client := &http.Client{
		Timeout: 15 * time.Second,
		Transport: &http.Transport{
			TLSClientConfig: &tls.Config{RootCAs: roots, ServerName: "localhost", MinVersion: tls.VersionTLS12},
			DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				var dialer net.Dialer
				return dialer.DialContext(ctx, network, proxyAddr)
			},
		},
	}
	defer client.CloseIdleConnections()
	response, err := client.Get("https://localhost/")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	got, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusOK || string(got) != "toxiproxy-ok" {
		t.Fatalf("bad response through toxiproxy: %d %q", response.StatusCode, got)
	}
}

func templateCert(t *testing.T, der []byte) *x509.Certificate {
	t.Helper()
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	return cert
}

func freePort(t *testing.T) int {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	listener.Close()
	return port
}
