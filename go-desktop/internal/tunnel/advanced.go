package tunnel

import (
	"context"
	"crypto/tls"
	"errors"
	"net"
	"strconv"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/advanced"
	"golang.org/x/net/http2"
)

// advancedEnabled reports whether an optional transport is configured well
// enough to be attempted. Missing parameters simply mean "use the standard
// path", never an error for the user.
func (m *Manager) advancedEnabled() bool {
	if m.target.Advanced.WSS {
		return true
	}
	return m.target.Advanced.ECH && m.target.Params.ECHConfigList != ""
}

// openAdvanced tries the enabled transports in order and returns the first that
// works. Every failure is returned to Open, which then runs the normal
// HTTP/2 → HTTP/1.1 chain.
func (m *Manager) openAdvanced(host string, port int, pass string, timeout time.Duration) (Dup, string, error) {
	var firstErr error
	if m.target.Advanced.WSS {
		conn, err := m.openWSS(host, port, pass, timeout)
		if err == nil {
			return conn, advanced.PathWSS, nil
		}
		firstErr = err
	}
	if m.target.Advanced.ECH && m.target.Params.ECHConfigList != "" {
		conn, err := m.openECHH2(host, port, pass, timeout)
		if err == nil {
			return conn, advanced.PathECH, nil
		}
		if firstErr == nil {
			firstErr = err
		}
	}
	if firstErr == nil {
		firstErr = errors.New("no advanced transport is configured")
	}
	return nil, "", firstErr
}

func (m *Manager) proxyAddress() string {
	host := m.target.DialHost
	if host == "" {
		host = m.target.Host
	}
	port := m.target.Port
	if port == 0 {
		port = 443
	}
	return net.JoinHostPort(host, strconv.Itoa(port))
}

func (m *Manager) advancedTLS(alpn []string, minVersion uint16) *tls.Config {
	config := &tls.Config{ServerName: m.target.Host, NextProtos: alpn, MinVersion: minVersion}
	if m.testTLS != nil {
		config = m.testTLS.Clone()
		config.ServerName = m.target.Host
		config.NextProtos = alpn
		config.MinVersion = minVersion
	}
	if m.target.Advanced.DomainFronting && m.target.Params.FrontHost != "" {
		config.ServerName = m.target.Params.FrontHost
	}
	return config
}

func (m *Manager) openWSS(host string, port int, pass string, timeout time.Duration) (Dup, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	config := m.advancedTLS([]string{"http/1.1"}, tls.VersionTLS12)
	return advanced.DialWSS(ctx, m.proxyAddress(), config, host, port, pass, m.target.Params, timeout)
}

func (m *Manager) openECHH2(host string, port int, pass string, timeout time.Duration) (Dup, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	config := m.advancedTLS([]string{"h2"}, tls.VersionTLS13)
	conn, _, err := advanced.DialTLSWithFallback(ctx, m.proxyAddress(), config, m.target.Advanced, m.target.Params, timeout)
	if err != nil {
		return nil, err
	}
	client, err := (&http2.Transport{DisableCompression: true}).NewClientConn(conn)
	if err != nil {
		conn.Close()
		return nil, err
	}
	stream, err := openStream(client, host, port, pass, timeout, func() { client.Close() })
	if err != nil {
		client.Close()
		return nil, err
	}
	return stream, nil
}

func (m *Manager) setAdvancedPath(path string) {
	m.mu.Lock()
	m.advancedPath = path
	m.mu.Unlock()
}

// LastAdvancedPath reports which optional transport served the most recent
// stream. An empty string means the standard path was used.
func (m *Manager) LastAdvancedPath() string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.advancedPath
}
