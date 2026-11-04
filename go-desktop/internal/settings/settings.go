package settings

import (
	"crypto/ed25519"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"
)

var ErrVerification = errors.New("settings verification failed")

type Bundle struct {
	Version    int      `json:"version"`
	DNS        []string `json:"dns,omitempty"`
	Fragment   bool     `json:"fragment,omitempty"`
	WarpPorts  []int    `json:"warp_ports,omitempty"`
	UserAgent  string   `json:"user_agent,omitempty"`
	UpdatedAt  string   `json:"updated_at,omitempty"`
}

type Fetcher struct {
	PublicKey ed25519.PublicKey
	Client    *http.Client
}

func NewFetcher(publicKey ed25519.PublicKey) *Fetcher {
	return &Fetcher{PublicKey: publicKey, Client: &http.Client{Timeout: 20 * time.Second}}
}

func (f *Fetcher) Fetch(url string) (*Bundle, error) {
	if !strings.HasPrefix(url, "https://") {
		return nil, ErrVerification
	}
	response, err := f.Client.Get(url)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, ErrVerification
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	if err != nil {
		return nil, err
	}
	var envelope struct {
		Payload   json.RawMessage `json:"payload"`
		Signature []byte          `json:"signature"`
	}
	if err := json.Unmarshal(body, &envelope); err != nil {
		return nil, ErrVerification
	}
	if len(f.PublicKey) != ed25519.PublicKeySize || len(envelope.Signature) != ed25519.SignatureSize ||
		!ed25519.Verify(f.PublicKey, envelope.Payload, envelope.Signature) {
		return nil, ErrVerification
	}
	var bundle Bundle
	if err := json.Unmarshal(envelope.Payload, &bundle); err != nil || bundle.Version < 1 {
		return nil, ErrVerification
	}
	for _, server := range bundle.DNS {
		if !strings.HasPrefix(server, "https://") {
			return nil, ErrVerification
		}
	}
	for _, port := range bundle.WarpPorts {
		if port < 1 || port > 65535 {
			return nil, ErrVerification
		}
	}
	return &bundle, nil
}
