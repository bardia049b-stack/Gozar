package fxa

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"strings"
	"testing"
)

func TestMozillaKeyDerivationVectors(t *testing.T) {
	password := hex.EncodeToString(AuthPW("andré@example.org", "pässwörd"))
	if password != "247b675ffb4c46310bc87e26d712153abe5e1c90ef00a4784594f97ef54f2375" {
		t.Fatalf("incorrect password derivation: %s", password)
	}
	id, key := sessionKeys("a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf")
	if hex.EncodeToString(id) != "c0a29dcf46174973da1378696e4c82ae10f723cf4f4d9f75e39f4ae3851595ab" {
		t.Fatal("incorrect session key ID")
	}
	if hex.EncodeToString(key) != "9d8f22998ee7f5798b887042466b72d53e56ab0c094388bf65831f702d2febc0" {
		t.Fatal("incorrect session request key")
	}
	if hmac.Equal(AuthPW("User@example.org", "password"), AuthPW("user@example.org", "password")) {
		t.Fatal("email case must be preserved in the password derivation")
	}
}

func TestHawkSignatureUsesStandardBase64(t *testing.T) {
	token := "a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf"
	payload := `{"code":"123456"}`
	header := HawkHeader("POST", "https://API.ACCOUNTS.FIREFOX.COM/v1/session/verify_code?source=test", token, payload)
	parts := map[string]string{}
	for _, part := range strings.Split(strings.TrimPrefix(header, "Hawk "), ", ") {
		key, value, ok := strings.Cut(part, "=")
		if ok {
			parts[key] = strings.Trim(value, `"`)
		}
	}
	if parts["id"] != "c0a29dcf46174973da1378696e4c82ae10f723cf4f4d9f75e39f4ae3851595ab" {
		t.Fatal("incorrect header ID")
	}
	sum := sha256.Sum256([]byte("hawk.1.payload\napplication/json\n" + payload + "\n"))
	payloadHash := base64.StdEncoding.EncodeToString(sum[:])
	if parts["hash"] != payloadHash {
		t.Fatal("payload hash must be base64-encoded")
	}
	normalized := strings.Join([]string{
		"hawk.1.header", parts["ts"], parts["nonce"], "POST", "/v1/session/verify_code?source=test",
		"api.accounts.firefox.com", "443", payloadHash, "", "",
	}, "\n")
	key, _ := hex.DecodeString("9d8f22998ee7f5798b887042466b72d53e56ab0c094388bf65831f702d2febc0")
	mac := hmac.New(sha256.New, key)
	mac.Write([]byte(normalized))
	if parts["mac"] != base64.StdEncoding.EncodeToString(mac.Sum(nil)) {
		t.Fatal("request signature does not match the Hawk specification")
	}
	if HawkHeader("POST", "https://example.org", "invalid", payload) != "" {
		t.Fatal("invalid session tokens must not be signed")
	}
}
