package app

import (
	"bufio"
	"context"
	"crypto/tls"
	"errors"
	"io"
	"net"
	"net/netip"
	"path/filepath"
	"strings"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/config"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/engine"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/fxa"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/tundev"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/tunnel"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/warp"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/wintun"
)

const warpMTU = warp.DefaultMTU

func (a *App) warpEnabled() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.cfg.WARP
}

func (a *App) warpVia() string {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.warpViaLocked()
}

func (a *App) warpViaLocked() string {
	if a.cfg.WARPVia != "relay" || len(a.cfg.Chain) > 0 {
		return "direct"
	}
	return "relay"
}

func (a *App) SetWARPVia(value string) error {
	if value != "direct" && value != "relay" {
		return errors.New("unknown warp carrier")
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.live || a.connecting {
		return errors.New("disconnect before changing the warp carrier")
	}
	next := a.cfg
	next.WARPVia = value
	if err := config.Save(next); err != nil {
		return err
	}
	a.cfg = next
	return nil
}

func (a *App) SetWARP(enabled bool) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.live || a.connecting {
		return errors.New("disconnect before changing the warp path")
	}
	next := a.cfg
	next.WARP = enabled
	if err := config.Save(next); err != nil {
		return err
	}
	a.cfg = next
	return nil
}

func (a *App) connectWARP() error {
	a.mu.Lock()
	a.stage = "برقراری تونل WARP…"
	mtu := a.cfg.WARPMTU
	consoleMode := a.cfg.ConsoleMode
	a.mu.Unlock()
	if mtu <= 0 || mtu > warpMTU {
		mtu = warpMTU
	}
	if err := wintun.Ensure(); err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	device, err := warp.DeviceFor(ctx, filepath.Join(config.Dir(), "warp.json"))
	if err != nil {
		return err
	}
	carrier, release, keep, err := a.warpCarrier(device)
	if err != nil {
		return err
	}
	created, err := warp.OpenStack(ctx, device, carrier, mtu)
	if err != nil {
		release()
		return err
	}
	dial := func(host string, port int) (io.ReadWriteCloser, error) {
		return created.Dial(host, port)
	}
	core, err := engine.Start("warp", dial, keep)
	if err != nil {
		created.Close()
		return err
	}
	core.SetFullDatagramDialer(func(host string, port int) (engine.Datagram, error) {
		return created.Datagram(host, port)
	})
	a.mu.Lock()
	a.engine = core
	a.warpstack = created
	a.warpRelease = release
	a.egress = dial
	a.transport = nil
	a.activeServer = fxa.Location{Host: "warp"}
	a.live = true
	a.since = time.Now().Unix()
	a.stage = ""
	a.mu.Unlock()
	go a.watchTunnel(core)
	if consoleMode {
		if err := a.startBridge(); err != nil {
			a.mu.Lock()
			a.fault = "The console gateway could not start: " + err.Error()
			a.mu.Unlock()
		}
	}
	a.startRenewal()
	if address := warpTrace(created.DialConn); address != "" {
		a.mu.Lock()
		a.exit = address
		a.mu.Unlock()
	}
	return nil
}

func (a *App) warpCarrier(device *warp.Device) (warp.Carrier, func(), []netip.Addr, error) {
	a.mu.Lock()
	via := a.cfg.WARPVia
	chain := append([]string(nil), a.cfg.Chain...)
	a.mu.Unlock()
	keep := keepAddresses(device.Endpoint)
	if via == "direct" || len(chain) > 0 {
		socket, err := warp.Listen(device.Endpoint)
		if err != nil {
			return warp.Carrier{}, nil, nil, err
		}
		return warp.Carrier{Packet: socket}, func() { socket.Close() }, keep, nil
	}
	target, err := a.relayTarget()
	if err != nil {
		return warp.Carrier{}, nil, nil, err
	}
	manager := tunnel.NewManager(target)
	dial := func(host string, port int) (io.ReadWriteCloser, error) {
		current, err := a.currentPass()
		if err != nil {
			return nil, err
		}
		return manager.Open(host, port, current, 20*time.Second)
	}
	keep = append(keep, keepAddresses(target.DialHost)...)
	return warp.Carrier{Stream: dial}, func() { manager.Close() }, keep, nil
}

func keepAddresses(endpoint string) []netip.Addr {
	host, _, err := net.SplitHostPort(endpoint)
	if err != nil {
		host = endpoint
	}
	address, err := netip.ParseAddr(host)
	if err != nil || address.IsLoopback() {
		return nil
	}
	return []netip.Addr{address}
}

func (a *App) relayTarget() (tunnel.Target, error) {
	a.mu.Lock()
	code := a.cfg.Server
	servers := append([]fxa.Location(nil), a.servers...)
	chain := append([]string(nil), a.cfg.Chain...)
	a.mu.Unlock()
	if len(servers) == 0 {
		if _, err := a.LoadServers(); err != nil {
			return tunnel.Target{}, err
		}
		a.mu.Lock()
		servers = append([]fxa.Location(nil), a.servers...)
		a.mu.Unlock()
	}
	place, err := pick(servers, code)
	if err != nil {
		return tunnel.Target{}, err
	}
	hops, err := tunnel.ParseChain(chain)
	if err != nil {
		return tunnel.Target{}, err
	}
	place.Chain = hops
	firstHost := place.Host
	if len(hops) > 0 {
		firstHost = hops[0].Host
	}
	for _, address := range tundev.Neighbours(firstHost) {
		if address.Is4() {
			place.DialHost = address.String()
			break
		}
	}
	if place.DialHost == "" {
		return tunnel.Target{}, errors.New("could not resolve the first proxy to IPv4 before starting the tunnel")
	}
	return place, nil
}

func warpTrace(dial func(string, int) (net.Conn, error)) string {
	const host = "one.one.one.one"
	conn, err := dial("1.1.1.1", 443)
	if err != nil {
		return ""
	}
	defer conn.Close()
	secure := tls.Client(conn, &tls.Config{ServerName: host, MinVersion: tls.VersionTLS12})
	_ = secure.SetDeadline(time.Now().Add(10 * time.Second))
	if err := secure.Handshake(); err != nil {
		return ""
	}
	request := "GET /cdn-cgi/trace HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n"
	if _, err := io.WriteString(secure, request); err != nil {
		return ""
	}
	reader := bufio.NewReader(io.LimitReader(secure, 8192))
	for {
		line, err := reader.ReadString('\n')
		if value, ok := strings.CutPrefix(strings.TrimSpace(line), "ip="); ok {
			return strings.TrimSpace(value)
		}
		if err != nil {
			return ""
		}
	}
}
