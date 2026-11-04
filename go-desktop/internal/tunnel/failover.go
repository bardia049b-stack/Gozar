package tunnel

import (
	"crypto/tls"
	"crypto/x509"
	"errors"
	"net"
	"strconv"
	"time"
)

// SetBackups installs alternate proxies. When the active proxy cannot be
// reached, Open tries the backups in order and stays on the first one that
// works, so a dead primary does not end the session.
//
// A proxy *refusal* (bad credentials, quota, policy) is never treated as a
// reason to switch: that would hide an account problem behind another server.
func (m *Manager) SetBackups(targets []Target) {
	m.mu.Lock()
	if m.closed {
		m.mu.Unlock()
		return
	}
	previous := m.backups
	m.backups = nil
	m.active = 0
	m.mu.Unlock()
	for _, old := range previous {
		_ = old.Close()
	}
	fresh := make([]*Manager, 0, len(targets))
	for _, target := range targets {
		if target.Host == "" {
			continue
		}
		if target.Port == 0 {
			target.Port = m.target.Port
		}
		if target.Host == m.target.Host && target.Port == m.target.Port {
			continue
		}
		backup := NewManager(target)
		backup.testTLS = m.testTLS
		fresh = append(fresh, backup)
	}
	m.mu.Lock()
	if m.closed {
		m.mu.Unlock()
		for _, manager := range fresh {
			_ = manager.Close()
		}
		return
	}
	m.backups = append(m.backups, fresh...)
	m.mu.Unlock()
}

// ActiveHost is the proxy currently carrying traffic.
func (m *Manager) ActiveHost() string {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.active > 0 && m.active-1 < len(m.backups) {
		return m.backups[m.active-1].target.Host
	}
	return m.target.Host
}

// candidate pairs a manager with its stable index (0 is the primary).
type candidate struct {
	index   int
	manager *Manager
}

// failoverOrder lists the proxies to try, starting with the active one.
func (m *Manager) failoverOrder() []candidate {
	m.mu.Lock()
	defer m.mu.Unlock()
	all := make([]*Manager, 0, len(m.backups)+1)
	all = append(all, m)
	all = append(all, m.backups...)
	active := m.active
	if active >= len(all) {
		active = 0
	}
	order := make([]candidate, 0, len(all))
	order = append(order, candidate{index: active, manager: all[active]})
	for index, manager := range all {
		if index != active {
			order = append(order, candidate{index: index, manager: manager})
		}
	}
	return order
}

// openWithFailover is the public Open: it walks the active proxy and then the
// backups, giving each a fair share of the caller's timeout.
func (m *Manager) openWithFailover(host string, port int, pass string, timeout time.Duration) (Dup, error) {
	order := m.failoverOrder()
	if len(order) == 1 {
		return m.openOnce(host, port, pass, timeout)
	}
	share := timeout / time.Duration(len(order))
	if share < 3*time.Second {
		share = 3 * time.Second
	}
	var lastErr error
	for _, entry := range order {
		if m.isClosed() {
			return nil, errors.New("tunnel is closed")
		}
		conn, err := entry.manager.openOnce(host, port, pass, share)
		if err == nil {
			m.mu.Lock()
			m.active = entry.index
			m.mu.Unlock()
			return conn, nil
		}
		if isRefusal(err) {
			return nil, err
		}
		lastErr = err
	}
	if lastErr == nil {
		lastErr = errors.New("no proxy could be reached")
	}
	return nil, lastErr
}

// ActiveEndpoint is the proxy currently carrying traffic, host and port.
func (m *Manager) ActiveEndpoint() string {
	m.mu.Lock()
	defer m.mu.Unlock()
	target := m.target
	if m.active > 0 && m.active-1 < len(m.backups) {
		target = m.backups[m.active-1].target
	}
	return net.JoinHostPort(target.Host, strconv.Itoa(target.Port))
}

// SetProxyRoots replaces the trust store used for the proxy's TLS certificate.
// Call it before the first Open. It lets a user pin a private CA, and it lets
// the tests run a real TLS proxy without touching the machine's root store.
func (m *Manager) SetProxyRoots(roots *x509.CertPool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if roots == nil {
		return
	}
	m.testTLS = &tls.Config{RootCAs: roots}
	for _, backup := range m.backups {
		backup.mu.Lock()
		backup.testTLS = m.testTLS
		backup.mu.Unlock()
	}
}
