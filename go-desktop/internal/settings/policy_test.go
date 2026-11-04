package settings

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func signedServer(t *testing.T, bundle Bundle) (*httptest.Server, ed25519.PublicKey) {
	t.Helper()
	public, private, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	payload, err := json.Marshal(bundle)
	if err != nil {
		t.Fatal(err)
	}
	signature := ed25519.Sign(private, payload)
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]any{"payload": json.RawMessage(payload), "signature": signature})
	}))
	t.Cleanup(server.Close)
	return server, public
}

func TestRejectsInvalidWarpPorts(t *testing.T) {
	server, public := signedServer(t, Bundle{Version: 1, WarpPorts: []int{0}})
	fetcher := NewFetcher(public)
	fetcher.Client = server.Client()
	if _, err := fetcher.Fetch(server.URL); err == nil {
		t.Fatal("invalid WARP port accepted")
	}
}

func TestRejectsPlainHTTPSettings(t *testing.T) {
	_, public := signedServer(t, Bundle{Version: 1})
	fetcher := NewFetcher(public)
	if _, err := fetcher.Fetch("http://example.test/settings.json"); err == nil {
		t.Fatal("plain HTTP settings accepted")
	}
}

func TestRejectsNonHTTPSDNS(t *testing.T) {
	server, public := signedServer(t, Bundle{Version: 1, DNS: []string{"udp://1.1.1.1"}})
	fetcher := NewFetcher(public)
	fetcher.Client = server.Client()
	if _, err := fetcher.Fetch(server.URL); err == nil {
		t.Fatal("non-HTTPS DNS endpoint accepted")
	}
}
