package warp

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestRegisterP256(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v0a4471/reg" || r.Method != http.MethodPost {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		var body registration
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		if body.KeyType != "secp256r1" || body.TunType != "masque" {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		der, err := base64.StdEncoding.DecodeString(body.Key)
		if err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		public, err := x509.ParsePKIXPublicKey(der)
		if err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		if _, ok := public.(*ecdsa.PublicKey); !ok {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		peer, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
		peerDer, _ := x509.MarshalPKIXPublicKey(&peer.PublicKey)
		json.NewEncoder(w).Encode(map[string]any{
			"id":    "device-1",
			"token": "token-1",
			"config": map[string]any{
				"interface": map[string]any{"addresses": map[string]string{"v4": "172.16.0.2", "v6": "2606:4700::2"}},
				"peers": []map[string]any{{
					"public_key": base64.StdEncoding.EncodeToString(peerDer),
					"endpoint":   map[string]string{"v4": "162.159.198.2:0", "v6": "[2606:4700::2]:0"},
				}},
			},
		})
	}))
	defer server.Close()
	old := APIBase
	APIBase = server.URL + "/v0a4471"
	defer func() { APIBase = old }()
	identity, err := Register(context.Background(), server.Client())
	if err != nil {
		t.Fatal(err)
	}
	if identity.ID != "device-1" || identity.Endpoint != "162.159.198.2" {
		t.Fatalf("bad identity: %+v", identity)
	}
	if _, err := identity.privateKey(); err != nil {
		t.Fatal(err)
	}
	if _, err := identity.tlsConfig([]string{"h2"}); err != nil {
		t.Fatal(err)
	}
}

func TestScannerCache(t *testing.T) {
	cache := NewCache(filepath.Join(t.TempDir(), "endpoint.json"))
	if _, ok := cache.Load(); ok {
		t.Fatal("empty cache returned endpoint")
	}
	if err := cache.Save("162.159.198.2:443"); err != nil {
		t.Fatal(err)
	}
	endpoint, ok := cache.Load()
	if !ok || endpoint != "162.159.198.2:443" {
		t.Fatalf("bad cache: %s %v", endpoint, ok)
	}
	cache.storedAt = time.Now().Add(-24 * time.Hour)
	if _, ok := cache.Load(); ok {
		t.Fatal("expired cache returned endpoint")
	}
	data, err := os.ReadFile(cache.Path)
	if err != nil || len(data) == 0 {
		t.Fatal("cache was not persisted")
	}
}

func TestParseEndpoint(t *testing.T) {
	host, port, err := ParseEndpoint("162.159.198.2:443")
	if err != nil || host != "162.159.198.2" || port != 443 {
		t.Fatalf("bad endpoint: %s %d %v", host, port, err)
	}
	if !ValidEndpoint("[2606:4700::2]:443") {
		t.Fatal("IPv6 endpoint rejected")
	}
	if ValidEndpoint("bad host:443") {
		t.Fatal("invalid endpoint accepted")
	}
}
