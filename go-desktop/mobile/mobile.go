package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"

	connectip "github.com/Diniboy1123/connect-ip-go"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/advanced"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/bypass"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/engine"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/protect"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/tunnel"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/warp"
)

type Protector interface {
	Protect(fd int32) bool
}

type protectorFunc func(int) bool

func (f protectorFunc) Protect(fd int32) bool { return f(int(fd)) }

func SetProtector(p Protector) {
	if p == nil {
		protect.SetHook(nil)
		return
	}
	protect.SetHook(func(fd int) bool { return p.Protect(int32(fd)) })
}

type settings struct {
	ProxyHost    string          `json:"proxy_host"`
	ProxyPort    int             `json:"proxy_port"`
	Pass         string          `json:"pass"`
	DialHost     string          `json:"dial_host"`
	Chain        []string        `json:"chain"`
	MTU          int             `json:"mtu"`
	UDP          bool            `json:"udp"`
	GameMode     bool            `json:"game_mode"`
	Warp         bool            `json:"warp"`
	WarpEndpoint string          `json:"warp_endpoint"`
	WarpIdentity *warp.Identity  `json:"warp_identity"`
	Advanced     bypass.Options  `json:"advanced"`
	Params       advanced.Params `json:"params"`
	TimeoutMS    int             `json:"timeout_ms"`
	Backups      []string        `json:"backups"`
}

type session struct {
	engine    *engine.Engine
	transport *tunnel.Manager
	packets   *warpSession
	started   time.Time
	config    settings
}

type warpSession struct {
	*connectip.Conn
	client *warp.Client
}

func (w *warpSession) Close() error {
	err := w.Conn.Close()
	_ = w.client.Close()
	return err
}

var (
	mu      sync.Mutex
	current *session
	version = "dev"
)

func SetVersion(value string) {
	mu.Lock()
	version = value
	mu.Unlock()
}

func Version() string {
	mu.Lock()
	defer mu.Unlock()
	return version
}

func protectedClient(timeout time.Duration) *http.Client {
	dialer := protect.Dialer(timeout)
	return &http.Client{
		Timeout: timeout,
		Transport: &http.Transport{
			DialContext:           dialer.DialContext,
			TLSHandshakeTimeout:   timeout,
			ResponseHeaderTimeout: timeout,
			IdleConnTimeout:       90 * time.Second,
			MaxIdleConns:          8,
			ForceAttemptHTTP2:     true,
		},
	}
}

func resolveIPv4(host string, timeout time.Duration) (string, error) {
	if address, err := netip.ParseAddr(host); err == nil {
		if !address.Is4() {
			return "", errors.New("only ipv4 proxy addresses are supported")
		}
		return address.String(), nil
	}
	resolver := &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			dialer := protect.Dialer(timeout)
			for _, server := range []string{"1.1.1.1:53", "8.8.8.8:53"} {
				conn, err := dialer.DialContext(ctx, "udp", server)
				if err == nil {
					return conn, nil
				}
			}
			return nil, errors.New("no resolver could be reached outside the tunnel")
		},
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	addresses, err := resolver.LookupNetIP(ctx, "ip4", host)
	if err != nil {
		return "", err
	}
	for _, address := range addresses {
		if address.Is4() {
			return address.String(), nil
		}
	}
	return "", errors.New("the proxy has no ipv4 address")
}

func parseSettings(raw string) (settings, error) {
	var parsed settings
	if strings.TrimSpace(raw) == "" {
		return parsed, errors.New("the tunnel settings are empty")
	}
	if err := json.Unmarshal([]byte(raw), &parsed); err != nil {
		return parsed, fmt.Errorf("the tunnel settings are not valid json: %w", err)
	}
	if parsed.ProxyPort == 0 {
		parsed.ProxyPort = 443
	}
	if parsed.MTU == 0 {
		parsed.MTU = 1280
	}
	if parsed.MTU < 576 || parsed.MTU > 9000 {
		return parsed, errors.New("mtu must be between 576 and 9000")
	}
	if parsed.TimeoutMS == 0 {
		parsed.TimeoutMS = 12000
	}
	if parsed.GameMode {
		parsed.UDP = true
	}
	parsed.Params = parsed.Params.WithDefaults()
	if err := parsed.Advanced.Validate(); err != nil {
		return parsed, err
	}
	if err := parsed.Params.Validate(); err != nil {
		return parsed, err
	}
	if parsed.WarpIdentity == nil && !parsed.Warp && parsed.ProxyHost == "" {
		return parsed, errors.New("a proxy host is required")
	}
	return parsed, nil
}

func Start(fd int32, raw string) error {
	mu.Lock()
	defer mu.Unlock()
	if current != nil {
		return errors.New("the tunnel is already running")
	}
	config, err := parseSettings(raw)
	if err != nil {
		return err
	}
	timeout := time.Duration(config.TimeoutMS) * time.Millisecond
	target := tunnel.Target{Host: config.ProxyHost, Port: config.ProxyPort}
	hops, err := tunnel.ParseChain(config.Chain)
	if err != nil {
		return err
	}
	target.Chain = hops
	target.Advanced = config.Advanced
	target.Params = config.Params
	first := target.Host
	if len(hops) > 0 {
		first = hops[0].Host
	}
	if first == "" {
		return errors.New("a proxy host is required")
	}
	dialHost := config.DialHost
	if dialHost == "" {
		dialHost, err = resolveIPv4(first, timeout)
		if err != nil {
			return err
		}
	}
	target.DialHost = dialHost
	transport := tunnel.NewManager(target)
	var backups []tunnel.Target
	for _, name := range config.Backups {
		if len(backups) == 2 {
			break
		}
		if name == "" || strings.EqualFold(name, target.Host) {
			continue
		}
		address, err := resolveIPv4(name, timeout)
		if err != nil {
			continue
		}
		backup := target
		backup.Host = name
		backup.DialHost = address
		backups = append(backups, backup)
	}
	transport.SetBackups(backups)
	dial := func(host string, port int) (io.ReadWriteCloser, error) {
		mu.Lock()
		running := current
		mu.Unlock()
		if running == nil {
			return nil, errors.New("the tunnel is closed")
		}
		return running.transport.Open(host, port, running.config.Pass, timeout)
	}
	options := engine.FDOptions{MTU: uint32(config.MTU)}
	var packets *warpSession
	if config.UDP && config.Warp {
		identity := config.WarpIdentity
		if identity == nil {
			ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
			identity, err = warp.Register(ctx, protectedClient(15*time.Second))
			cancel()
			if err != nil {
				transport.Close()
				return fmt.Errorf("warp registration failed: %w", err)
			}
		}
		client := warp.NewClient(identity)
		if config.WarpEndpoint != "" {
			host, port, err := warp.ParseEndpoint(config.WarpEndpoint)
			if err != nil {
				transport.Close()
				return err
			}
			client.Endpoint = host
			if port > 0 {
				client.Port = port
			}
		}
		packets = &warpSession{client: client}
		options.Packets = func(ctx context.Context) (engine.PacketConn, error) {
			conn, err := client.Dial(ctx)
			if err != nil {
				return nil, err
			}
			return &warpSession{Conn: conn, client: client}, nil
		}
	}
	peer := &session{transport: transport, packets: packets, started: time.Now(), config: config}
	current = peer
	running, err := engine.StartFD(int(fd), dial, options)
	if err != nil {
		current = nil
		if packets != nil {
			packets.client.Close()
		}
		transport.Close()
		return err
	}
	peer.engine = running
	if config.Advanced.DoQ {
		running.EnableDoQ(config.Params.DoQServer)
	}
	return nil
}

func Stop() {
	mu.Lock()
	running := current
	current = nil
	mu.Unlock()
	if running == nil {
		return
	}
	running.engine.Stop()
	if running.packets != nil {
		running.packets.client.Close()
	}
	running.transport.Close()
}

func Running() bool {
	mu.Lock()
	defer mu.Unlock()
	return current != nil
}

func Stats() string {
	mu.Lock()
	running := current
	mu.Unlock()
	if running == nil {
		return `{"running":false}`
	}
	down, up := running.engine.Counters()
	retransmits, segments := running.engine.TCPStats()
	hits, misses, coalesced := running.engine.DNSStats()
	rate := 0.0
	if segments > 0 {
		rate = float64(retransmits) / float64(segments)
	}
	path := running.transport.LastAdvancedPath()
	if path == "" {
		path = "standard"
	}
	out := map[string]any{
		"running":         true,
		"down":            down,
		"up":              up,
		"seconds":         int64(time.Since(running.started).Seconds()),
		"retransmits":     retransmits,
		"segments":        segments,
		"retransmit_rate": rate,
		"dns_hits":        hits,
		"dns_misses":      misses,
		"dns_coalesced":   coalesced,
		"udp_flows":       running.engine.UDPFlows(),
		"udp":             running.config.UDP,
		"game_mode":       running.config.GameMode,
		"warp":            running.config.Warp,
		"path":            path,
		"advanced":        running.config.Advanced.Enabled(),
	}
	raw, err := json.Marshal(out)
	if err != nil {
		return `{"running":true}`
	}
	return string(raw)
}

func SetAdvanced(raw string) error {
	var parsed struct {
		Advanced bypass.Options  `json:"advanced"`
		Params   advanced.Params `json:"params"`
	}
	if err := json.Unmarshal([]byte(raw), &parsed); err != nil {
		return err
	}
	if err := parsed.Advanced.Validate(); err != nil {
		return err
	}
	params := parsed.Params.WithDefaults()
	if err := params.Validate(); err != nil {
		return err
	}
	if parsed.Advanced.ECH && params.ECHConfigList == "" {
		return errors.New("ech needs an ech_config_list")
	}
	if parsed.Advanced.DomainFronting && params.FrontHost == "" {
		return errors.New("domain_fronting needs a front_host")
	}
	mu.Lock()
	running := current
	if running == nil {
		mu.Unlock()
		return errors.New("the tunnel is not running")
	}
	target := tunnel.Target{
		Host:     running.config.ProxyHost,
		Port:     running.config.ProxyPort,
		DialHost: running.config.DialHost,
		Advanced: parsed.Advanced,
		Params:   params,
	}
	hops, err := tunnel.ParseChain(running.config.Chain)
	if err != nil {
		mu.Unlock()
		return err
	}
	target.Chain = hops
	previous := running.transport
	replacement := tunnel.NewManager(target)
	running.transport = replacement
	running.config.Advanced = parsed.Advanced
	running.config.Params = params
	runner := running.engine
	mu.Unlock()
	if parsed.Advanced.DoQ {
		runner.EnableDoQ(params.DoQServer)
	} else {
		runner.EnableDoQ("")
	}
	previous.Close()
	return nil
}

func SetUDP(enabled bool) error {
	mu.Lock()
	running := current
	mu.Unlock()
	if running == nil {
		return errors.New("the tunnel is not running")
	}
	if enabled == running.config.UDP {
		return nil
	}
	return errors.New("udp relaying is chosen when the tunnel starts; reconnect to change it")
}

func WarpRegister() (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancel()
	identity, err := warp.Register(ctx, protectedClient(20*time.Second))
	if err != nil {
		return "", err
	}
	raw, err := json.Marshal(identity)
	if err != nil {
		return "", err
	}
	return string(raw), nil
}

func WarpScan(endpoints string) string {
	list := strings.Fields(strings.ReplaceAll(endpoints, ",", " "))
	if len(list) == 0 {
		list = []string{"162.159.198.2:443", "162.159.198.1:443"}
	}
	type result struct {
		Endpoint string `json:"endpoint"`
		MS       int64  `json:"ms"`
		Error    string `json:"error,omitempty"`
	}
	out := make([]result, 0, len(list))
	for _, endpoint := range list {
		host, port, err := warp.ParseEndpoint(endpoint)
		if err != nil {
			out = append(out, result{Endpoint: endpoint, Error: err.Error()})
			continue
		}
		address := net.JoinHostPort(host, strconv.Itoa(port))
		start := time.Now()
		dialer := protect.Dialer(4 * time.Second)
		conn, err := dialer.Dial("tcp", address)
		if err != nil {
			out = append(out, result{Endpoint: endpoint, Error: err.Error()})
			continue
		}
		conn.Close()
		out = append(out, result{Endpoint: endpoint, MS: time.Since(start).Milliseconds()})
	}
	raw, _ := json.Marshal(out)
	return string(raw)
}

func Probe(host string, port int32) string {
	mu.Lock()
	running := current
	mu.Unlock()
	if running == nil {
		return `{"ok":false,"error":"the tunnel is not running"}`
	}
	start := time.Now()
	conn, err := running.transport.Open(host, int(port), running.config.Pass, 10*time.Second)
	if err != nil {
		raw, _ := json.Marshal(map[string]any{"ok": false, "error": err.Error()})
		return string(raw)
	}
	conn.Close()
	raw, _ := json.Marshal(map[string]any{"ok": true, "ms": time.Since(start).Milliseconds()})
	return string(raw)
}

func Validate(raw string) string {
	config, err := parseSettings(raw)
	if err != nil {
		return err.Error()
	}
	if config.Advanced.ECH && config.Params.ECHConfigList == "" {
		return "ech needs an ech_config_list"
	}
	if config.Advanced.DomainFronting && config.Params.FrontHost == "" {
		return "domain_fronting needs a front_host"
	}
	return ""
}
