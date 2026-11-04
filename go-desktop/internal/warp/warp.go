package warp

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	connectip "github.com/Diniboy1123/connect-ip-go"
	"github.com/quic-go/quic-go"
	"github.com/quic-go/quic-go/http3"
	"github.com/yosida95/uritemplate/v3"
	"golang.org/x/net/http2"
)

var APIBase = DefaultAPIBase

const (
	DefaultAPIBase = "https://api.cloudflareclient.com/v0a4471"
	ConnectSNI     = "consumer-masque.cloudflareclient.com"
	ConnectURI     = "https://cloudflareaccess.com"
	Protocol       = "cf-connect-ip"
	MTU            = 1180
	DefaultPort    = 443
)

type Identity struct {
	ID         string `json:"id"`
	Token      string `json:"token"`
	PrivateKey string `json:"private_key"`
	IPv4       string `json:"ipv4"`
	IPv6       string `json:"ipv6"`
	PeerKey    string `json:"peer_key"`
	Endpoint   string `json:"endpoint"`
}

type registration struct {
	Key       string `json:"key"`
	InstallID string `json:"install_id"`
	FcmToken  string `json:"fcm_token"`
	Tos       string `json:"tos"`
	Model     string `json:"model"`
	Serial    string `json:"serial_number"`
	OsVersion string `json:"os_version"`
	KeyType   string `json:"key_type"`
	TunType   string `json:"tunnel_type"`
	Locale    string `json:"locale"`
}

type accountData struct {
	ID     string `json:"id"`
	Token  string `json:"token"`
	Config struct {
		ClientID  string `json:"client_id"`
		Interface struct {
			Addresses struct {
				V4 string `json:"v4"`
				V6 string `json:"v6"`
			} `json:"addresses"`
		} `json:"interface"`
		Peers []struct {
			PublicKey string `json:"public_key"`
			Endpoint  struct {
				V4   string `json:"v4"`
				V6   string `json:"v6"`
				Host string `json:"host"`
			} `json:"endpoint"`
		} `json:"peers"`
	} `json:"config"`
}

func GenerateKey() (*ecdsa.PrivateKey, error) {
	return ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
}

func Register(ctx context.Context, client *http.Client) (*Identity, error) {
	if client == nil {
		client = &http.Client{Timeout: 30 * time.Second}
	}
	key, err := GenerateKey()
	if err != nil {
		return nil, err
	}
	public, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		return nil, err
	}
	private, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return nil, err
	}
	serial := make([]byte, 8)
	if _, err := rand.Read(serial); err != nil {
		return nil, err
	}
	body := registration{
		Key:     base64.StdEncoding.EncodeToString(public),
		Tos:     time.Now().Format("2006-01-02T15:04:05.000-07:00"),
		Model:   "PC",
		Serial:  fmt.Sprintf("%x", serial),
		KeyType: "secp256r1",
		TunType: "masque",
		Locale:  "en_US",
	}
	payload, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, APIBase+"/reg", bytes.NewReader(payload))
	if err != nil {
		return nil, err
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("User-Agent", "WARP for Android")
	request.Header.Set("CF-Client-Version", "a-6.35-4471")
	response, err := client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	data, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	if err != nil {
		return nil, err
	}
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("warp registration failed: HTTP %d", response.StatusCode)
	}
	var account accountData
	if err := json.Unmarshal(data, &account); err != nil {
		return nil, err
	}
	if account.ID == "" || account.Token == "" || len(account.Config.Peers) == 0 {
		return nil, errors.New("warp registration returned an incomplete device")
	}
	peer := account.Config.Peers[0]
	endpoint := strings.TrimSuffix(peer.Endpoint.V4, ":0")
	if endpoint == "" {
		endpoint = peer.Endpoint.Host
	}
	return &Identity{
		ID:         account.ID,
		Token:      account.Token,
		PrivateKey: base64.StdEncoding.EncodeToString(private),
		IPv4:       account.Config.Interface.Addresses.V4,
		IPv6:       account.Config.Interface.Addresses.V6,
		PeerKey:    peer.PublicKey,
		Endpoint:   endpoint,
	}, nil
}

func (id *Identity) privateKey() (*ecdsa.PrivateKey, error) {
	der, err := base64.StdEncoding.DecodeString(id.PrivateKey)
	if err != nil {
		return nil, err
	}
	return x509.ParseECPrivateKey(der)
}

func (id *Identity) certificate() (tls.Certificate, error) {
	key, err := id.privateKey()
	if err != nil {
		return tls.Certificate{}, err
	}
	template := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "Gozar WARP"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(24 * time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		return tls.Certificate{}, err
	}
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}, nil
}

func (id *Identity) tlsConfig(alpn []string) (*tls.Config, error) {
	certificate, err := id.certificate()
	if err != nil {
		return nil, err
	}
	peerDer, err := base64.StdEncoding.DecodeString(id.PeerKey)
	if err != nil {
		return nil, err
	}
	peerKey, err := x509.ParsePKIXPublicKey(peerDer)
	if err != nil {
		return nil, err
	}
	peerECDSA, ok := peerKey.(*ecdsa.PublicKey)
	if !ok {
		return nil, errors.New("warp peer key is not P-256")
	}
	want, err := x509.MarshalPKIXPublicKey(peerECDSA)
	if err != nil {
		return nil, err
	}
	pin := sha256.Sum256(want)
	return &tls.Config{
		Certificates:       []tls.Certificate{certificate},
		ServerName:         ConnectSNI,
		NextProtos:         alpn,
		MinVersion:         tls.VersionTLS13,
		InsecureSkipVerify: true,
		VerifyPeerCertificate: func(rawCerts [][]byte, _ [][]*x509.Certificate) error {
			if len(rawCerts) == 0 {
				return errors.New("warp peer certificate missing")
			}
			cert, err := x509.ParseCertificate(rawCerts[0])
			if err != nil {
				return err
			}
			got, err := x509.MarshalPKIXPublicKey(cert.PublicKey)
			if err != nil {
				return err
			}
			if sha256.Sum256(got) != pin {
				return errors.New("warp peer public key mismatch")
			}
			return nil
		},
	}, nil
}

type Client struct {
	Identity *Identity
	Endpoint string
	Port     int
	mu       sync.Mutex
	h3       *quic.Conn
	h3Client *http3.ClientConn
	h2       *http.Client
}

func NewClient(identity *Identity) *Client {
	endpoint := identity.Endpoint
	if endpoint == "" {
		endpoint = "162.159.198.2"
	}
	return &Client{Identity: identity, Endpoint: endpoint, Port: DefaultPort}
}

func (c *Client) address() string {
	port := c.Port
	if port == 0 {
		port = DefaultPort
	}
	return net.JoinHostPort(c.Endpoint, strconv.Itoa(port))
}

func (c *Client) DialH3(ctx context.Context) (*connectip.Conn, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.h3Client == nil {
		config, err := c.Identity.tlsConfig([]string{http3.NextProtoH3})
		if err != nil {
			return nil, err
		}
		conn, err := quic.DialAddr(ctx, c.address(), config, &quic.Config{
			EnableDatagrams:   true,
			KeepAlivePeriod:   10 * time.Second,
			MaxIdleTimeout:    45 * time.Second,
			InitialPacketSize: 1242,
		})
		if err != nil {
			return nil, err
		}
		c.h3 = conn
		c.h3Client = (&http3.Transport{DisableCompression: true}).NewClientConn(conn)
	}
	template := uritemplate.MustNew(ConnectURI)
	headers := http.Header{"User-Agent": []string{""}}
	conn, _, err := connectip.Dial(ctx, c.h3Client, template, Protocol, headers, true)
	if err != nil {
		c.h3Client = nil
		if c.h3 != nil {
			c.h3.CloseWithError(0, "")
			c.h3 = nil
		}
		return nil, err
	}
	return conn, nil
}

func (c *Client) DialH2(ctx context.Context) (*connectip.Conn, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.h2 == nil {
		config, err := c.Identity.tlsConfig([]string{"h2"})
		if err != nil {
			return nil, err
		}
		transport := &http2.Transport{
			TLSClientConfig: config,
			DialTLSContext: func(ctx context.Context, network, addr string, _ *tls.Config) (net.Conn, error) {
				dialer := &tls.Dialer{Config: config}
				return dialer.DialContext(ctx, network, c.address())
			},
		}
		c.h2 = &http.Client{Transport: transport}
	}
	template := uritemplate.MustNew(ConnectURI)
	headers := http.Header{"Capsule-Protocol": []string{"?1"}, "User-Agent": []string{""}}
	conn, _, err := connectip.DialH2(ctx, c.h2, template, headers)
	if err != nil {
		c.h2 = nil
		return nil, err
	}
	return conn, nil
}

func (c *Client) Dial(ctx context.Context) (*connectip.Conn, error) {
	conn, err := c.DialH3(ctx)
	if err == nil {
		return conn, nil
	}
	return c.DialH2(ctx)
}

func (c *Client) Close() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.h3 != nil {
		c.h3.CloseWithError(0, "")
		c.h3 = nil
	}
	c.h3Client = nil
	if c.h2 != nil {
		c.h2.CloseIdleConnections()
		c.h2 = nil
	}
	return nil
}

func EndpointHost(raw string) string {
	if strings.HasPrefix(raw, "[") {
		host, _, err := net.SplitHostPort(raw)
		if err == nil {
			return strings.Trim(host, "[]")
		}
	}
	if host, _, err := net.SplitHostPort(raw); err == nil {
		return host
	}
	return strings.TrimSuffix(raw, ":0")
}

func ParseEndpoint(raw string) (string, int, error) {
	if !strings.Contains(raw, ":") {
		return raw, DefaultPort, nil
	}
	host, portText, err := net.SplitHostPort(raw)
	if err != nil {
		return "", 0, err
	}
	port, err := strconv.Atoi(portText)
	if err != nil || port < 1 || port > 65535 {
		return "", 0, errors.New("invalid endpoint port")
	}
	return strings.Trim(host, "[]"), port, nil
}

func ValidEndpoint(raw string) bool {
	host, port, err := ParseEndpoint(raw)
	return err == nil && port > 0 && (net.ParseIP(host) != nil || isHostname(host))
}

func isHostname(host string) bool {
	if host == "" || len(host) > 253 {
		return false
	}
	for _, part := range strings.Split(host, ".") {
		if part == "" || len(part) > 63 {
			return false
		}
		for _, r := range part {
			if !(r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '-') {
				return false
			}
		}
	}
	return true
}
