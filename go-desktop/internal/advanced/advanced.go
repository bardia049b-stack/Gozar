// Package advanced implements the optional anti-censorship transports.
//
// Every transport here is a real protocol implementation (not a stub) and every
// entry point falls back to the plain path automatically when the network,
// the peer or the operating system does not support it. All options are
// disabled by default; enabling one never removes the standard path.
package advanced

import (
	"context"
	"crypto/tls"
	"encoding/base64"
	"errors"
	"net"
	"strings"
	"syscall"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/bypass"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/protect"
)

// Transport names. They are returned by the dialers so callers and tests can
// prove which path was really used, including after a fallback.
const (
	PathTCP   = "tcp"
	PathTFO   = "tfo"
	PathMPTCP = "mptcp"
	PathTLS   = "tls"
	PathECH   = "ech"
	PathFront = "fronted"
	PathWSS   = "wss"
	PathDoQ   = "doq"
	PathDoH   = "doh"
)

// DefaultDoQServer is a public RFC 9250 resolver. It is only contacted when the
// user explicitly enables DoQ.
const DefaultDoQServer = "dns.adguard-dns.com:853"

// Params carries the non-boolean settings the transports need. Empty values
// mean "use the default" or "this transport cannot run, fall back".
type Params struct {
	DoQServer     string `json:"doq_server,omitempty"`
	ECHConfigList string `json:"ech_config_list,omitempty"`
	FrontHost     string `json:"front_host,omitempty"`
	WSSPath       string `json:"wss_path,omitempty"`
}

func (p Params) WithDefaults() Params {
	if strings.TrimSpace(p.DoQServer) == "" {
		p.DoQServer = DefaultDoQServer
	}
	if strings.TrimSpace(p.WSSPath) == "" {
		p.WSSPath = "/gozar-tunnel"
	}
	return p
}

// Validate rejects malformed settings instead of silently dialling nonsense.
func (p Params) Validate() error {
	if p.DoQServer != "" {
		host, port, err := net.SplitHostPort(p.DoQServer)
		if err != nil || host == "" || !validPort(port) {
			return errors.New("the DoQ server must be host:port")
		}
	}
	if p.FrontHost != "" && !validHost(p.FrontHost) {
		return errors.New("the fronting host must be a bare DNS name")
	}
	if p.WSSPath != "" && !strings.HasPrefix(p.WSSPath, "/") {
		return errors.New("the WebSocket path must start with /")
	}
	if p.ECHConfigList != "" {
		if _, err := p.ECHConfigBytes(); err != nil {
			return err
		}
	}
	return nil
}

func validPort(port string) bool {
	if port == "" || len(port) > 5 {
		return false
	}
	value := 0
	for _, r := range port {
		if r < '0' || r > '9' {
			return false
		}
		value = value*10 + int(r-'0')
	}
	return value > 0 && value <= 65535
}

func validHost(host string) bool {
	if host == "" || len(host) > 253 || strings.ContainsAny(host, " \t\r\n/:") {
		return false
	}
	for _, part := range strings.Split(host, ".") {
		if part == "" || len(part) > 63 {
			return false
		}
	}
	return true
}

// ECHConfigBytes decodes and structurally validates the base64 ECHConfigList.
func (p Params) ECHConfigBytes() ([]byte, error) {
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(p.ECHConfigList))
	if err != nil {
		return nil, errors.New("the ECH config list must be base64")
	}
	if _, err := ParseECHConfigList(raw); err != nil {
		return nil, err
	}
	return raw, nil
}

// DialTCP opens a TCP connection, preferring the enabled socket optimizations
// and falling back to a plain dial. The returned string names the path used.
func DialTCP(ctx context.Context, address string, options bypass.Options, timeout time.Duration) (net.Conn, string, error) {
	if timeout <= 0 {
		timeout = 15 * time.Second
	}
	type attempt struct {
		name            string
		mptcp, fastopen bool
	}
	var order []attempt
	if options.MPTCP && options.TFO {
		order = append(order, attempt{PathMPTCP + "+" + PathTFO, true, true})
	}
	if options.MPTCP {
		order = append(order, attempt{PathMPTCP, true, false})
	}
	if options.TFO {
		order = append(order, attempt{PathTFO, false, true})
	}
	order = append(order, attempt{PathTCP, false, false})
	var firstErr error
	for _, item := range order {
		conn, err := dialTCP(ctx, address, item.mptcp, item.fastopen, timeout)
		if err == nil {
			return conn, item.name, nil
		}
		if firstErr == nil {
			firstErr = err
		}
		if ctx.Err() != nil {
			return nil, "", ctx.Err()
		}
	}
	if firstErr == nil {
		firstErr = errors.New("no TCP path succeeded")
	}
	return nil, "", firstErr
}

func dialTCP(ctx context.Context, address string, mptcp, fastopen bool, timeout time.Duration) (net.Conn, error) {
	dialer := protect.Dialer(timeout)
	if mptcp {
		dialer.SetMultipathTCP(true)
	}
	if fastopen {
		dialer.Control = func(network, address string, raw syscall.RawConn) error {
			return enableFastOpen(raw)
		}
	}
	return dialer.DialContext(ctx, "tcp", address)
}

// TLSConfig applies ECH and domain fronting to a copy of base. The second
// return value names the resulting path.
func TLSConfig(base *tls.Config, options bypass.Options, params Params) (*tls.Config, string, error) {
	if base == nil {
		base = &tls.Config{}
	}
	config := base.Clone()
	path := PathTLS
	if options.DomainFronting && params.FrontHost != "" {
		if !validHost(params.FrontHost) {
			return nil, "", errors.New("invalid fronting host")
		}
		config.ServerName = params.FrontHost
		path = PathFront
	}
	if options.ECH && params.ECHConfigList != "" {
		list, err := params.ECHConfigBytes()
		if err != nil {
			return nil, "", err
		}
		config.EncryptedClientHelloConfigList = list
		config.MinVersion = tls.VersionTLS13
		path = PathECH
	}
	return config, path, nil
}

// DialTLSWithFallback opens a connection and performs a TLS handshake with the
// enabled options. When the advanced hello is rejected it automatically
// re-dials and retries with the standard hello, so ECH or fronting can never
// take the tunnel down on their own.
func DialTLSWithFallback(ctx context.Context, address string, base *tls.Config, options bypass.Options, params Params, timeout time.Duration) (net.Conn, string, error) {
	if timeout <= 0 {
		timeout = 15 * time.Second
	}
	config, path, err := TLSConfig(base, options, params)
	if err != nil {
		return nil, "", err
	}
	raw, tcpPath, err := DialTCP(ctx, address, options, timeout)
	if err != nil {
		return nil, "", err
	}
	_ = raw.SetDeadline(time.Now().Add(timeout))
	client := tls.Client(raw, config)
	if err := client.HandshakeContext(ctx); err == nil {
		_ = raw.SetDeadline(time.Time{})
		return client, path, nil
	} else if path == PathTLS {
		raw.Close()
		return nil, "", err
	}
	raw.Close()
	// Fallback: standard TLS, no ECH, real SNI.
	fallback, fallbackPath, err := DialTLSWithFallback(ctx, address, base, bypass.Options{}, params, timeout)
	if err != nil {
		return nil, "", err
	}
	return fallback, fallbackPath + ":" + tcpPath, nil
}
