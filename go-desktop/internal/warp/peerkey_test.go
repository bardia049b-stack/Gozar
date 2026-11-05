package warp

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"encoding/base64"
	"encoding/pem"
	"testing"
)

func peerKeyFixture(t *testing.T) ([]byte, string, string) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	bare := base64.StdEncoding.EncodeToString(der)
	wrapped := string(pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: der}))
	return der, bare, wrapped
}

func TestPeerKeyAcceptsPEMFromTheRealAPI(t *testing.T) {
	der, _, wrapped := peerKeyFixture(t)
	identity := &Identity{PeerKey: wrapped}
	got, err := identity.peerKeyBytes()
	if err != nil {
		t.Fatalf("the PEM form returned by api.cloudflareclient.com was rejected: %v", err)
	}
	if string(got) != string(der) {
		t.Fatal("the PEM block did not decode to the original DER")
	}
	if _, err := identity.tlsConfig([]string{"h3"}); err == nil {
		t.Fatal("tlsConfig should still need a client certificate")
	}
}

func TestPeerKeyStillAcceptsBareBase64(t *testing.T) {
	der, bare, _ := peerKeyFixture(t)
	got, err := (&Identity{PeerKey: bare}).peerKeyBytes()
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != string(der) {
		t.Fatal("the base64 form regressed")
	}
}

func TestPeerKeyRejectsGarbage(t *testing.T) {
	for _, value := range []string{"", "   ", "not base64!!", "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n"} {
		if _, err := (&Identity{PeerKey: value}).peerKeyBytes(); err == nil {
			t.Fatalf("%q was accepted as a peer key", value)
		}
	}
}
