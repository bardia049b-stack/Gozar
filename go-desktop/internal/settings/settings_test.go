package settings

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestFetchSignedBundle(t *testing.T) {
	public, private, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	payload, _ := json.Marshal(Bundle{Version: 2, DNS: []string{"https://dns.google/dns-query"}, Fragment: true, WarpPorts: []int{443, 8443}})
	signature := ed25519.Sign(private, payload)
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]any{"payload": json.RawMessage(payload), "signature": signature})
	}))
	defer server.Close()
	fetcher := NewFetcher(public)
	fetcher.Client = server.Client()
	bundle, err := fetcher.Fetch(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	if bundle.Version != 2 || !bundle.Fragment || len(bundle.WarpPorts) != 2 {
		t.Fatalf("bad bundle: %+v", bundle)
	}
}

func TestRejectsUnsignedBundle(t *testing.T) {
	public, _, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{"payload":{"version":1},"signature":"AAAA"}`))
	}))
	defer server.Close()
	fetcher := NewFetcher(public)
	fetcher.Client = server.Client()
	if _, err := fetcher.Fetch(server.URL); err == nil {
		t.Fatal("unsigned bundle accepted")
	}
}
