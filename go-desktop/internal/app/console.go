package app

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"io"
	"net"
	"net/netip"
	"strings"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/bridge"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/config"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/engine"
)

var consoleKinds = map[string]string{
	"playstation": "PlayStation 5",
	"xbox":        "Xbox Series X|S",
}

func (a *App) SetUDPRelay(enabled bool) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.live || a.connecting {
		return errors.New("disconnect before changing the datagram relay")
	}
	next := a.cfg
	next.UDPRelay = enabled
	if err := config.Save(next); err != nil {
		return err
	}
	a.cfg = next
	return nil
}

func (a *App) SetExtraUDPPorts(values []string) error {
	ports := engine.NormalizePorts(values)
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.live || a.connecting {
		return errors.New("disconnect before changing the datagram ports")
	}
	next := a.cfg
	next.ExtraUDPPorts = values
	if err := config.Save(next); err != nil {
		return err
	}
	a.cfg = next
	engine.SetRelayPorts(ports)
	return nil
}

func (a *App) SetConsoleKind(kind string) error {
	if _, ok := consoleKinds[kind]; !ok {
		return errors.New("unknown console profile")
	}
	a.mu.Lock()
	next := a.cfg
	next.ConsoleKind = kind
	if err := config.Save(next); err != nil {
		a.mu.Unlock()
		return err
	}
	a.cfg = next
	active := a.bridge != nil
	a.mu.Unlock()
	if active {
		if err := a.restartBridge(); err != nil {
			return err
		}
	}
	return nil
}

func (a *App) SetConsoleMode(enabled bool) error {
	a.mu.Lock()
	if enabled && !a.live {
		a.mu.Unlock()
		return errors.New("connect to a server before starting the console gateway")
	}
	if !enabled {
		next := a.cfg
		next.ConsoleMode = false
		if err := config.Save(next); err != nil {
			a.mu.Unlock()
			return err
		}
		a.cfg = next
		a.mu.Unlock()
		a.stopBridge()
		return nil
	}
	next := a.cfg
	next.ConsoleMode = true
	if next.BridgePass == "" {
		pass, err := bridgeSecret()
		if err != nil {
			a.mu.Unlock()
			return err
		}
		next.BridgePass = pass
	}
	if err := config.Save(next); err != nil {
		a.mu.Unlock()
		return err
	}
	a.cfg = next
	a.mu.Unlock()
	return a.startBridge()
}

func (a *App) RotateBridgeSecret() error {
	a.mu.Lock()
	pass, err := bridgeSecret()
	if err != nil {
		a.mu.Unlock()
		return err
	}
	next := a.cfg
	next.BridgePass = pass
	if err := config.Save(next); err != nil {
		a.mu.Unlock()
		return err
	}
	a.cfg = next
	active := a.bridge != nil
	a.mu.Unlock()
	if active {
		return a.restartBridge()
	}
	return nil
}

func (a *App) SetBridgePort(port int) error {
	if port < 1024 || port > 65535 {
		return errors.New("choose a gateway port between 1024 and 65535")
	}
	a.mu.Lock()
	next := a.cfg
	next.BridgePort = port
	if err := config.Save(next); err != nil {
		a.mu.Unlock()
		return err
	}
	a.cfg = next
	active := a.bridge != nil
	a.mu.Unlock()
	if active {
		return a.restartBridge()
	}
	return nil
}

func (a *App) SetBridgeAllow(values []string) error {
	allow, err := parseAllow(values)
	if err != nil {
		return err
	}
	a.mu.Lock()
	next := a.cfg
	next.BridgeAllow = allow
	if err := config.Save(next); err != nil {
		a.mu.Unlock()
		return err
	}
	a.cfg = next
	active := a.bridge != nil
	a.mu.Unlock()
	if active {
		return a.restartBridge()
	}
	return nil
}

func (a *App) startBridge() error {
	a.mu.Lock()
	if !a.live || a.engine == nil {
		a.mu.Unlock()
		return errors.New("the tunnel is not running")
	}
	settings := a.cfg
	allowed, err := parseAllow(settings.BridgeAllow)
	if err != nil {
		a.mu.Unlock()
		return err
	}
	if len(allowed) == 0 {
		if adapter := localIPv4(); adapter != "" {
			if address, err := netip.ParseAddr(adapter); err == nil {
				if prefix, err := address.Prefix(24); err == nil {
					allowed = []string{prefix.Masked().String()}
					settings.BridgeAllow = allowed
				}
			}
		}
	}
	prefixes := make([]netip.Prefix, 0, len(allowed))
	for _, value := range allowed {
		prefixes = append(prefixes, netip.MustParsePrefix(value))
	}
	if settings.BridgePass == "" {
		pass, err := bridgeSecret()
		if err != nil {
			a.mu.Unlock()
			return err
		}
		settings.BridgePass = pass
	}
	address := bridgeAddress(prefixes)
	localPort := settings.BridgePort
	if localPort == 0 {
		localPort = 8899
	}
	resolver := a.engine.Resolver()
	egress := a.egress
	transport := a.transport
	a.mu.Unlock()
	if err := config.Save(settings); err != nil {
		return err
	}

	dial := func(host string, port int) (io.ReadWriteCloser, error) {
		if egress != nil {
			return egress(host, port)
		}
		current, err := a.currentPass()
		if err != nil {
			return nil, err
		}
		return transport.Open(host, port, current, 20*time.Second)
	}
	server, err := bridge.New(bridge.Config{
		Address:    address,
		Port:       localPort,
		Allow:      prefixes,
		User:       settings.BridgeUser,
		Pass:       settings.BridgePass,
		MaxClients: 48,
	}, dial, resolver)
	if err != nil {
		return err
	}
	if err := server.Start(); err != nil {
		return err
	}
	a.mu.Lock()
	if a.bridge != nil {
		a.bridge.Stop()
	}
	a.bridge = server
	a.cfg = settings
	a.mu.Unlock()
	return nil
}

func (a *App) stopBridge() {
	a.mu.Lock()
	server := a.bridge
	a.bridge = nil
	a.mu.Unlock()
	if server != nil {
		server.Stop()
	}
}

func (a *App) restartBridge() error {
	a.stopBridge()
	return a.startBridge()
}

func (a *App) BridgeState() bridge.Stats {
	a.mu.Lock()
	server := a.bridge
	settings := a.cfg
	a.mu.Unlock()
	stats := bridge.Stats{}
	if server != nil {
		stats = server.Stats()
	}
	stats.Requires = settings.BridgePass != ""
	stats.Port = settings.BridgePort
	if stats.Address == "" {
		adapter := localIPv4()
		if adapter != "" {
			stats.Address = adapter
		}
	}
	stats.Allow = append([]string{}, settings.BridgeAllow...)
	return stats
}

func (a *App) bridgeSettings() (kind string, consoleMode bool, port int, user string, secret string, allow []string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.cfg.ConsoleKind, a.cfg.ConsoleMode, a.cfg.BridgePort, a.cfg.BridgeUser, a.cfg.BridgePass, append([]string{}, a.cfg.BridgeAllow...)
}

func bridgeSecret() (string, error) {
	buffer := make([]byte, 12)
	if _, err := rand.Read(buffer); err != nil {
		return "", err
	}
	return hex.EncodeToString(buffer), nil
}

func parseAllow(values []string) ([]string, error) {
	out := make([]string, 0, len(values))
	for _, value := range values {
		value = strings.TrimSpace(value)
		if value == "" {
			continue
		}
		if prefix, err := netip.ParsePrefix(value); err == nil {
			out = append(out, prefix.Masked().String())
			continue
		}
		address, err := netip.ParseAddr(value)
		if err != nil {
			return nil, errors.New("the console allowlist needs addresses or CIDR ranges")
		}
		out = append(out, netip.PrefixFrom(address.Unmap(), address.Unmap().BitLen()).String())
	}
	return out, nil
}

func bridgeAddress(prefixes []netip.Prefix) string {
	for _, candidate := range localIPv4s() {
		address, err := netip.ParseAddr(candidate)
		if err != nil {
			continue
		}
		for _, allowed := range prefixes {
			if allowed.Contains(address) {
				return candidate
			}
		}
	}
	if adapter := localIPv4(); adapter != "" {
		return adapter
	}
	return "127.0.0.1"
}

func localIPv4() string {
	addresses := localIPv4s()
	if len(addresses) == 0 {
		return ""
	}
	return addresses[0]
}

func localIPv4s() []string {
	var out []string
	interfaces, err := net.Interfaces()
	if err != nil {
		return out
	}
	for _, adapter := range interfaces {
		if adapter.Flags&net.FlagUp == 0 || adapter.Flags&net.FlagLoopback != 0 {
			continue
		}
		addresses, err := adapter.Addrs()
		if err != nil {
			continue
		}
		for _, entry := range addresses {
			ip, _, err := net.ParseCIDR(entry.String())
			if err != nil || ip.To4() == nil || ip.IsLoopback() || ip.IsLinkLocalUnicast() {
				continue
			}
			out = append(out, ip.String())
		}
	}
	return out
}
