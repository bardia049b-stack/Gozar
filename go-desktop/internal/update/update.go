package update

import (
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

var ErrVerification = errors.New("update verification failed")

type Verifier struct {
	PublicKey ed25519.PublicKey
	Client    *http.Client
}

func NewVerifier(publicKey ed25519.PublicKey) *Verifier {
	return &Verifier{
		PublicKey: publicKey,
		Client:    &http.Client{Timeout: 60 * time.Second},
	}
}

func VerifyManifest(manifest, signature []byte, publicKey ed25519.PublicKey) error {
	if len(publicKey) != ed25519.PublicKeySize || len(signature) != ed25519.SignatureSize {
		return ErrVerification
	}
	if !ed25519.Verify(publicKey, manifest, signature) {
		return ErrVerification
	}
	return nil
}

func ParseManifest(manifest []byte) (map[string][32]byte, error) {
	out := make(map[string][32]byte)
	for _, line := range strings.Split(string(manifest), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) != 2 {
			return nil, ErrVerification
		}
		sum, err := hex.DecodeString(fields[0])
		if err != nil || len(sum) != sha256.Size {
			return nil, ErrVerification
		}
		name := filepath.Base(fields[1])
		if name == "." || name == string(filepath.Separator) || name != fields[1] || strings.ContainsAny(name, "/\\") {
			return nil, ErrVerification
		}
		var hash [32]byte
		copy(hash[:], sum)
		out[name] = hash
	}
	if len(out) == 0 {
		return nil, ErrVerification
	}
	return out, nil
}

func (v *Verifier) FetchManifest(baseURL string) ([]byte, map[string][32]byte, error) {
	manifest, err := v.fetch(baseURL + "/SHA256SUMS.txt")
	if err != nil {
		return nil, nil, err
	}
	signature, err := v.fetch(baseURL + "/SHA256SUMS.txt.sig")
	if err != nil {
		return nil, nil, err
	}
	if err := VerifyManifest(manifest, signature, v.PublicKey); err != nil {
		return nil, nil, err
	}
	hashes, err := ParseManifest(manifest)
	if err != nil {
		return nil, nil, err
	}
	return manifest, hashes, nil
}

func (v *Verifier) DownloadVerified(baseURL, name, targetDir string) (string, error) {
	if filepath.Base(name) != name || strings.ContainsAny(name, "/\\") {
		return "", ErrVerification
	}
	_, hashes, err := v.FetchManifest(baseURL)
	if err != nil {
		return "", err
	}
	want, ok := hashes[name]
	if !ok {
		return "", ErrVerification
	}
	data, err := v.fetch(baseURL + "/" + name)
	if err != nil {
		return "", err
	}
	if sha256.Sum256(data) != want {
		return "", ErrVerification
	}
	if err := os.MkdirAll(targetDir, 0o755); err != nil {
		return "", err
	}
	tmp, err := os.CreateTemp(targetDir, ".gozar-update-*")
	if err != nil {
		return "", err
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName)
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return "", err
	}
	if err := tmp.Chmod(0o755); err != nil {
		tmp.Close()
		return "", err
	}
	if err := tmp.Close(); err != nil {
		return "", err
	}
	final := filepath.Join(targetDir, name)
	if err := os.Rename(tmpName, final); err != nil {
		return "", err
	}
	return final, nil
}

func (v *Verifier) fetch(url string) ([]byte, error) {
	if !strings.HasPrefix(url, "https://") {
		return nil, ErrVerification
	}
	response, err := v.Client.Get(url)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("%w: HTTP %d", ErrVerification, response.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(response.Body, 64<<20))
	if err != nil {
		return nil, err
	}
	return data, nil
}
