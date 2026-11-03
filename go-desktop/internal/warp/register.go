package warp

import (
	"bytes"
	"context"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"time"
)

const (
	apiBase       = "https://api.cloudflareclient.com"
	apiVersion    = "v0a4471"
	agentName     = "WARP for Android"
	clientVersion = "a-6.35-4471"
	defaultPeer   = "162.159.198.2:443"
	serverName    = "consumer-masque.cloudflareclient.com"
)

type Device struct {
	ID          string
	Token       string
	PrivateKey  string
	PeerKey     string
	IPv4        string
	IPv6        string
	Endpoint    string
	AccountType string
}

type registerBody struct {
	Key       string `json:"key"`
	KeyType   string `json:"key_type"`
	TunType   string `json:"tunnel_type"`
	InstallID string `json:"install_id"`
	FcmToken  string `json:"fcm_token"`
	Tos       string `json:"tos"`
	Model     string `json:"model"`
	Locale    string `json:"locale"`
}

type enrollBody struct {
	Key     string `json:"key"`
	KeyType string `json:"key_type"`
	TunType string `json:"tunnel_type"`
}

type apiPeer struct {
	PublicKey string `json:"public_key"`
	Endpoint  struct {
		V4 string `json:"v4"`
		V6 string `json:"v6"`
	} `json:"endpoint"`
}

type apiResult struct {
	ID      string `json:"id"`
	Token   string `json:"token"`
	KeyType string `json:"key_type"`
	TunType string `json:"tunnel_type"`
	Account struct {
		Type string `json:"account_type"`
	} `json:"account"`
	Config struct {
		Peers     []apiPeer `json:"peers"`
		Interface struct {
			Addresses struct {
				V4 string `json:"v4"`
				V6 string `json:"v6"`
			} `json:"addresses"`
		} `json:"interface"`
	} `json:"config"`
}

type apiEnvelope struct {
	Result apiResult `json:"result"`
}

func Register(ctx context.Context) (*Device, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}
	encoded, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return nil, err
	}
	public, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		return nil, err
	}
	device := &Device{PrivateKey: base64.StdEncoding.EncodeToString(encoded)}
	result, err := post(ctx, apiBase+"/"+apiVersion+"/reg", registerBody{
		Key:     base64.StdEncoding.EncodeToString(public),
		KeyType: "secp256r1",
		TunType: "masque",
		Tos:     time.Now().UTC().Format(time.RFC3339),
		Model:   "PC",
		Locale:  "en_US",
	}, "")
	if err != nil || result.ID == "" {
		fallback, enrollErr := registerLegacy(ctx, device, public)
		if enrollErr != nil {
			if err != nil {
				return nil, err
			}
			return nil, enrollErr
		}
		result = fallback
	}
	if result.Token == "" {
		return nil, errors.New("the registration did not return an access token")
	}
	device.ID = result.ID
	device.Token = result.Token
	device.AccountType = result.Account.Type
	device.IPv4 = result.Config.Interface.Addresses.V4
	device.IPv6 = result.Config.Interface.Addresses.V6
	if len(result.Config.Peers) == 0 || result.Config.Peers[0].PublicKey == "" {
		return nil, errors.New("the registration did not return a peer")
	}
	device.PeerKey = result.Config.Peers[0].PublicKey
	device.Endpoint = defaultPeer
	if endpoint := result.Config.Peers[0].Endpoint.V4; endpoint != "" {
		if host, _, splitErr := net.SplitHostPort(endpoint); splitErr == nil && host != "" {
			device.Endpoint = net.JoinHostPort(host, "443")
		}
	}
	if device.IPv4 == "" {
		return nil, errors.New("the registration did not return an address")
	}
	return device, nil
}

func registerLegacy(ctx context.Context, device *Device, public []byte) (apiResult, error) {
	var out apiResult
	secret, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return out, err
	}
	result, err := post(ctx, apiBase+"/"+apiVersion+"/reg", registerBody{
		Key:     base64.StdEncoding.EncodeToString(secret.PublicKey().Bytes()),
		KeyType: "curve25519",
		TunType: "wireguard",
		Tos:     time.Now().UTC().Format(time.RFC3339),
		Model:   "PC",
		Locale:  "en_US",
	}, "")
	if err != nil {
		return out, err
	}
	if result.ID == "" || result.Token == "" {
		return out, errors.New("the registration was rejected")
	}
	enrolled, err := patch(ctx, apiBase+"/"+apiVersion+"/reg/"+result.ID, enrollBody{
		Key:     base64.StdEncoding.EncodeToString(public),
		KeyType: "secp256r1",
		TunType: "masque",
	}, result.Token)
	if err != nil {
		return out, err
	}
	if enrolled.Config.Interface.Addresses.V4 == "" {
		enrolled.Config.Interface.Addresses.V4 = result.Config.Interface.Addresses.V4
	}
	if enrolled.Config.Interface.Addresses.V6 == "" {
		enrolled.Config.Interface.Addresses.V6 = result.Config.Interface.Addresses.V6
	}
	if len(enrolled.Config.Peers) == 0 {
		enrolled.Config.Peers = result.Config.Peers
	}
	enrolled.ID = result.ID
	enrolled.Token = result.Token
	return enrolled, nil
}

func post(ctx context.Context, address string, body any, token string) (apiResult, error) {
	return call(ctx, http.MethodPost, address, body, token)
}

func patch(ctx context.Context, address string, body any, token string) (apiResult, error) {
	return call(ctx, http.MethodPatch, address, body, token)
}

func call(ctx context.Context, method, address string, body any, token string) (apiResult, error) {
	var out apiResult
	payload, err := json.Marshal(body)
	if err != nil {
		return out, err
	}
	request, err := http.NewRequestWithContext(ctx, method, address, bytes.NewReader(payload))
	if err != nil {
		return out, err
	}
	request.Header.Set("Content-Type", "application/json; charset=UTF-8")
	request.Header.Set("User-Agent", agentName)
	request.Header.Set("CF-Client-Version", clientVersion)
	request.Header.Set("Accept", "application/json")
	if token != "" {
		request.Header.Set("Authorization", "Bearer "+token)
	}
	client := &http.Client{Timeout: 25 * time.Second}
	reply, err := client.Do(request)
	if err != nil {
		return out, err
	}
	defer reply.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(reply.Body, 1<<20))
	if err != nil {
		return out, err
	}
	if reply.StatusCode != http.StatusOK {
		return out, fmt.Errorf("the warp api answered %d: %s", reply.StatusCode, summarize(raw))
	}
	var envelope apiEnvelope
	if err := json.Unmarshal(raw, &envelope); err == nil && envelope.Result.ID != "" {
		return envelope.Result, nil
	}
	var direct apiResult
	if err := json.Unmarshal(raw, &direct); err != nil {
		return out, err
	}
	if direct.ID == "" {
		return out, errors.New("the registration reply carried no device")
	}
	return direct, nil
}

func summarize(raw []byte) string {
	text := strings.TrimSpace(string(raw))
	if len(text) > 160 {
		text = text[:160]
	}
	return strings.ReplaceAll(text, "\n", " ")
}

func (d *Device) Private() (*ecdsa.PrivateKey, error) {
	decoded, err := base64.StdEncoding.DecodeString(d.PrivateKey)
	if err != nil {
		return nil, errors.New("the stored device key is unreadable")
	}
	key, err := x509.ParseECPrivateKey(decoded)
	if err != nil {
		return nil, errors.New("the stored device key is not a p-256 key")
	}
	if key.Curve != elliptic.P256() {
		return nil, errors.New("the stored device key uses an unexpected curve")
	}
	return key, nil
}

func (d *Device) Peer() ([]byte, error) {
	value := strings.TrimSpace(d.PeerKey)
	if value == "" {
		return nil, errors.New("the stored peer key is unreadable")
	}
	if strings.Contains(value, "-----BEGIN") {
		block, _ := pem.Decode([]byte(value))
		if block == nil {
			return nil, errors.New("the stored peer key is unreadable")
		}
		key, err := x509.ParsePKIXPublicKey(block.Bytes)
		if err != nil {
			return nil, errors.New("the stored peer key is not a public key")
		}
		encoded, err := x509.MarshalPKIXPublicKey(key)
		if err != nil {
			return nil, err
		}
		return encoded, nil
	}
	decoded, err := base64.StdEncoding.DecodeString(value)
	if err != nil || len(decoded) == 0 {
		return nil, errors.New("the stored peer key is unreadable")
	}
	return decoded, nil
}

func (d *Device) Prefixes() ([]netip.Prefix, error) {
	var out []netip.Prefix
	for _, value := range []string{d.IPv4, d.IPv6} {
		value = strings.TrimSpace(value)
		if value == "" {
			continue
		}
		if strings.Contains(value, "/") {
			prefix, err := netip.ParsePrefix(value)
			if err != nil {
				continue
			}
			out = append(out, prefix)
			continue
		}
		address, err := netip.ParseAddr(value)
		if err != nil {
			continue
		}
		out = append(out, netip.PrefixFrom(address, address.BitLen()))
	}
	if len(out) == 0 {
		return nil, errors.New("the device has no routable address")
	}
	return out, nil
}

func (d *Device) EndpointAddress() (string, error) {
	endpoint := strings.TrimSpace(d.Endpoint)
	if endpoint == "" {
		return defaultPeer, nil
	}
	if _, _, err := net.SplitHostPort(endpoint); err != nil {
		return net.JoinHostPort(endpoint, "443"), nil
	}
	return endpoint, nil
}

func (d *Device) ServerName() string { return serverName }
