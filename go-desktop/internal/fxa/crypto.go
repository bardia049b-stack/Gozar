package fxa

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"net/url"
	"strconv"
	"strings"
	"time"
)

const prefix = "identity.mozilla.com/picl/v1/"

func hmacSHA256(key, data []byte) []byte {
	mac := hmac.New(sha256.New, key)
	mac.Write(data)
	return mac.Sum(nil)
}

func pbkdf2(password, salt []byte, rounds, length int) []byte {
	out := make([]byte, 0, length)
	for block := 1; len(out) < length; block++ {
		index := []byte{byte(block >> 24), byte(block >> 16), byte(block >> 8), byte(block)}
		u := hmacSHA256(password, append(append([]byte{}, salt...), index...))
		t := append([]byte{}, u...)
		for round := 1; round < rounds; round++ {
			u = hmacSHA256(password, u)
			for i := range t {
				t[i] ^= u[i]
			}
		}
		out = append(out, t...)
	}
	return out[:length]
}

func hkdf(ikm, salt, info []byte, length int) []byte {
	if salt == nil {
		salt = make([]byte, 32)
	}
	prk := hmacSHA256(salt, ikm)
	out := make([]byte, 0, length)
	var prev []byte
	for counter := 1; len(out) < length; counter++ {
		block := append(append(append([]byte{}, prev...), info...), byte(counter))
		prev = hmacSHA256(prk, block)
		out = append(out, prev...)
	}
	return out[:length]
}

func AuthPW(email, password string) []byte {
	salt := []byte(prefix + "quickStretch:" + email)
	stretched := pbkdf2([]byte(password), salt, 1000, 32)
	return hkdf(stretched, []byte{0}, []byte(prefix+"authPW"), 32)
}

func sessionKeys(tokenHex string) (id, key []byte) {
	raw, err := hex.DecodeString(strings.TrimSpace(tokenHex))
	if err != nil || len(raw) != 32 {
		return nil, nil
	}
	keys := hkdf(raw, nil, []byte(prefix+"sessionToken"), 96)
	return keys[:32], keys[32:64]
}

func HawkHeader(method, rawURL, tokenHex, payload string) string {
	id, key := sessionKeys(tokenHex)
	if id == nil {
		return ""
	}
	parsed, err := url.Parse(rawURL)
	if err != nil || parsed.Hostname() == "" {
		return ""
	}
	nonceBytes := make([]byte, 6)
	if _, err := rand.Read(nonceBytes); err != nil {
		return ""
	}
	nonce := hex.EncodeToString(nonceBytes)
	ts := strconv.FormatInt(time.Now().Unix(), 10)
	port := parsed.Port()
	if port == "" {
		if parsed.Scheme == "https" {
			port = "443"
		} else if parsed.Scheme == "http" {
			port = "80"
		} else {
			return ""
		}
	}
	path := parsed.EscapedPath()
	if path == "" {
		path = "/"
	}
	if parsed.RawQuery != "" {
		path += "?" + parsed.RawQuery
	}
	payloadHash := ""
	if payload != "" {
		sum := sha256.Sum256([]byte("hawk.1.payload\napplication/json\n" + payload + "\n"))
		payloadHash = base64.StdEncoding.EncodeToString(sum[:])
	}
	normalized := strings.Join([]string{
		"hawk.1.header",
		ts,
		nonce,
		strings.ToUpper(method),
		path,
		strings.ToLower(parsed.Hostname()),
		port,
		payloadHash,
		"",
		"",
	}, "\n")
	mac := base64.StdEncoding.EncodeToString(hmacSHA256(key, []byte(normalized)))
	header := `Hawk id="` + hex.EncodeToString(id) + `", ts="` + ts + `", nonce="` + nonce + `", mac="` + mac + `"`
	if payloadHash != "" {
		header += `, hash="` + payloadHash + `"`
	}
	return header
}
