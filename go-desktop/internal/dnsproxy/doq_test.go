package dnsproxy

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"math/big"
	"net"
	"net/http"
	"sync/atomic"
	"testing"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/advancedtest"
	"golang.org/x/net/dns/dnsmessage"
)

func doqCertificate(t *testing.T) (tls.Certificate, *x509.CertPool) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := x509.Certificate{
		SerialNumber: big.NewInt(9), Subject: pkix.Name{CommonName: "resolver.example"},
		DNSNames: []string{"resolver.example"}, IPAddresses: []net.IP{net.ParseIP("127.0.0.1")},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour),
		IsCA: true, BasicConstraintsValid: true,
		KeyUsage: x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
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
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}, pool
}

func TestDoQIsPreferredAndDoHTakesOverWhenItFails(t *testing.T) {
	var dohRequests atomic.Int32
	resolver, _, _ := setup(t, http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		body := make([]byte, req.ContentLength)
		_, _ = req.Body.Read(body)
		dohRequests.Add(1)
		w.Header().Set("Content-Type", "application/dns-message")
		_, _ = w.Write(answerFor(t, body))
	}))
	cert, roots := doqCertificate(t)
	server, err := advancedtest.StartDoQ(&tls.Config{Certificates: []tls.Certificate{cert}}, func(query []byte) ([]byte, error) {
		var message dnsmessage.Message
		if err := message.Unpack(query); err != nil {
			return nil, err
		}
		answer := dnsmessage.Message{
			Header:    dnsmessage.Header{Response: true, RecursionAvailable: true},
			Questions: message.Questions,
			Answers: []dnsmessage.Resource{{
				Header: dnsmessage.ResourceHeader{Name: message.Questions[0].Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 30},
				Body:   &dnsmessage.AResource{A: [4]byte{192, 0, 2, 53}},
			}},
		}
		return answer.Pack()
	})
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	resolver.EnableDoQ(server.Addr, &tls.Config{RootCAs: roots, ServerName: "resolver.example"})
	if !resolver.DoQEnabled() {
		t.Fatal("DoQ was not enabled")
	}
	first := query(t, 11, "doq-first.example.", dnsmessage.TypeA)
	answer, err := resolver.Resolve(first)
	if err != nil {
		t.Fatal(err)
	}
	question, _ := readQuery(first)
	if err := checkAnswer(question, answer); err != nil {
		t.Fatal(err)
	}
	if server.Queries() != 1 || dohRequests.Load() != 0 {
		t.Fatalf("DoQ should have served the query alone: doq=%d doh=%d", server.Queries(), dohRequests.Load())
	}

	// Kill the DoQ server: the resolver must fall back to DoH automatically.
	server.Close()
	second := query(t, 12, "doh-fallback.example.", dnsmessage.TypeA)
	answer, err = resolver.Resolve(second)
	if err != nil {
		t.Fatalf("DoH fallback failed: %v", err)
	}
	question, _ = readQuery(second)
	if err := checkAnswer(question, answer); err != nil {
		t.Fatal(err)
	}
	if dohRequests.Load() == 0 {
		t.Fatal("the DoH fallback was never used")
	}
	resolver.EnableDoQ("", nil)
	if resolver.DoQEnabled() {
		t.Fatal("DoQ could not be disabled")
	}
}
