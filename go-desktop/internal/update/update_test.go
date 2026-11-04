package update

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestDownloadVerified(t *testing.T) {
	public, private, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	payload := []byte("gozar-binary")
	sum := sha256.Sum256(payload)
	manifest := []byte(hex.EncodeToString(sum[:]) + "  gozar-core\n")
	signature := ed25519.Sign(private, manifest)
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/SHA256SUMS.txt":
			w.Write(manifest)
		case "/SHA256SUMS.txt.sig":
			w.Write(signature)
		case "/gozar-core":
			w.Write(payload)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer server.Close()
	verifier := NewVerifier(public)
	verifier.Client = server.Client()
	dir := t.TempDir()
	path, err := verifier.DownloadVerified(server.URL, "gozar-core", dir)
	if err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(path)
	if err != nil || string(data) != string(payload) {
		t.Fatalf("bad downloaded file: %v %q", err, data)
	}
}

func TestRejectsTamperedManifest(t *testing.T) {
	public, private, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	manifest := []byte("0000000000000000000000000000000000000000000000000000000000000000  gozar-core\n")
	signature := ed25519.Sign(private, manifest)
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/SHA256SUMS.txt":
			w.Write([]byte("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff  gozar-core\n"))
		case "/SHA256SUMS.txt.sig":
			w.Write(signature)
		default:
			w.Write([]byte("evil"))
		}
	}))
	defer server.Close()
	verifier := NewVerifier(public)
	verifier.Client = server.Client()
	if _, err := verifier.DownloadVerified(server.URL, "gozar-core", t.TempDir()); err == nil {
		t.Fatal("tampered manifest was accepted")
	}
}

func TestRejectsBadBinaryHash(t *testing.T) {
	public, private, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	manifest := []byte("0000000000000000000000000000000000000000000000000000000000000000  gozar-core\n")
	signature := ed25519.Sign(private, manifest)
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/SHA256SUMS.txt":
			w.Write(manifest)
		case "/SHA256SUMS.txt.sig":
			w.Write(signature)
		default:
			w.Write([]byte("evil"))
		}
	}))
	defer server.Close()
	verifier := NewVerifier(public)
	verifier.Client = server.Client()
	dir := t.TempDir()
	if _, err := verifier.DownloadVerified(server.URL, "gozar-core", dir); err == nil {
		t.Fatal("bad binary hash was accepted")
	}
	entries, _ := filepath.Glob(filepath.Join(dir, "*"))
	if len(entries) != 0 {
		t.Fatalf("unverified file was installed: %v", entries)
	}
}
