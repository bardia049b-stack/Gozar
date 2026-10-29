package app

import (
	"errors"
	"io"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/bardia049b-stack/gozar/go-desktop/internal/config"
	"github.com/bardia049b-stack/gozar/go-desktop/internal/engine"
	"github.com/bardia049b-stack/gozar/go-desktop/internal/fxa"
	"github.com/bardia049b-stack/gozar/go-desktop/internal/socks"
	"github.com/bardia049b-stack/gozar/go-desktop/internal/sysproxy"
	"github.com/bardia049b-stack/gozar/go-desktop/internal/tundev"
	"github.com/bardia049b-stack/gozar/go-desktop/internal/tunnel"
	"github.com/bardia049b-stack/gozar/go-desktop/internal/wintun"
)

type State struct {
	SignedIn   bool           `json:"signed_in"`
	Email      string         `json:"email"`
	Language   string         `json:"language"`
	Stage      string         `json:"stage"`
	Live       bool           `json:"live"`
	Connecting bool           `json:"connecting"`
	Mode       string         `json:"mode"`
	Exit       string         `json:"exit"`
	Server     fxa.Location   `json:"server"`
	Servers    []fxa.Location `json:"servers"`
	Down       int64          `json:"down"`
	Up         int64          `json:"up"`
	RateDown   int64          `json:"rate_down"`
	RateUp     int64          `json:"rate_up"`
	Since      int64          `json:"since"`
	Fault      string         `json:"fault"`
	NeedsAdmin bool           `json:"needs_admin"`
	Limit      int64          `json:"limit"`
	Remaining  int64          `json:"remaining"`
	Reset      string         `json:"reset"`
	Port       int            `json:"port"`
	Device     string         `json:"device"`
	Version    string         `json:"version"`
	Admin      bool           `json:"admin"`
}

type App struct {
	mu         sync.Mutex
	cfg        config.Config
	servers    []fxa.Location
	pass       *fxa.Pass
	engine     *engine.Engine
	proxy      *socks.Server
	stage      string
	fault      string
	live       bool
	connecting bool
	needAdmin  bool
	exit       string
	since      int64
	rateDown   atomic.Int64
	rateUp     atomic.Int64
	lastDown   int64
	lastUp     int64
	version    string
	admin      bool
}

func New(version string) *App {
	created := &App{cfg: config.Load(), version: version, admin: tundev.Privileged()}
	if sysproxy.Stale() {
		sysproxy.Clear()
	}
	go created.sampler()
	return created
}

func (a *App) State() State {
	a.mu.Lock()
	defer a.mu.Unlock()
	state := State{
		SignedIn:   a.cfg.SignedIn(),
		Email:      a.cfg.Email,
		Language:   a.cfg.Language,
		Stage:      a.stage,
		Live:       a.live,
		Connecting: a.connecting,
		Mode:       a.mode(),
		Exit:       a.exit,
		Servers:    append([]fxa.Location(nil), a.servers...),
		Fault:      a.fault,
		NeedsAdmin: a.needAdmin,
		Port:       a.cfg.Port,
		Version:    a.version,
		Admin:      a.admin,
		RateDown:   a.rateDown.Load(),
		RateUp:     a.rateUp.Load(),
	}
	if a.pass != nil {
		state.Limit = a.pass.Limit
		state.Remaining = a.pass.Remaining
		state.Reset = a.pass.Reset
	}
	state.Since = a.since
	for _, place := range a.servers {
		if strings.EqualFold(place.Host, a.cfg.Server) || strings.EqualFold(place.Code, a.cfg.Server) {
			state.Server = place
			break
		}
	}
	if a.engine != nil {
		state.Down, state.Up = a.engine.Counters()
		state.Device = a.engine.Device()
	} else if a.proxy != nil {
		state.Down = a.lastDown
		state.Up = a.lastUp
	}
	return state
}

func (a *App) mode() string {
	if a.cfg.FullTunnel {
		return "tun"
	}
	return "proxy"
}

func (a *App) sampler() {
	for {
		time.Sleep(time.Second)
		a.mu.Lock()
		engineNow := a.engine
		var down, up int64
		if engineNow != nil {
			down, up = engineNow.Counters()
		}
		a.rateDown.Store(down - a.lastDown)
		a.rateUp.Store(up - a.lastUp)
		a.lastDown, a.lastUp = down, up
		a.mu.Unlock()
	}
}

func (a *App) Rates() (int64, int64) {
	return a.rateDown.Load(), a.rateUp.Load()
}

func (a *App) LoadServers() ([]fxa.Location, error) {
	servers, err := fxa.Servers()
	if err != nil {
		return nil, err
	}
	a.mu.Lock()
	a.servers = servers
	if a.cfg.Server == "" {
		for _, place := range servers {
			if place.Recommended {
				a.cfg.Server = place.Code
				break
			}
		}
	}
	err = config.Save(a.cfg)
	a.mu.Unlock()
	return servers, err
}

func (a *App) SetServer(key string) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.live || a.connecting {
		return errors.New("disconnect before changing the server")
	}
	for _, place := range a.servers {
		if strings.EqualFold(place.Host, key) || strings.EqualFold(place.Code, key) {
			a.cfg.Server = place.Host
			return config.Save(a.cfg)
		}
	}
	return errors.New("server not found")
}

func (a *App) SetMode(tunMode bool) {
	a.mu.Lock()
	a.cfg.FullTunnel = tunMode
	config.Save(a.cfg)
	a.mu.Unlock()
}

func (a *App) SetLanguage(language string) error {
	if language != "" && language != "system" && language != "fa" && language != "en" {
		return errors.New("unsupported language")
	}
	if language == "system" {
		language = ""
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	a.cfg.Language = language
	return config.Save(a.cfg)
}

func (a *App) SignIn(email, password, code string) error {
	a.setStage("در حال ورود…")
	session, err := fxa.SignIn(email, password)
	if err != nil {
		a.setStage("")
		return err
	}
	if session.NeedsOTP {
		if code == "" {
			a.setStage("کد تأیید را از ایمیل وارد کنید")
			return errCode
		}
		if err := fxa.VerifyCode(session.Token, session.UID, code); err != nil {
			a.setStage("")
			return err
		}
	}
	a.setStage("در حال دریافت مجوز…")
	tokens, err := fxa.SessionTokens(session.Token)
	if err != nil {
		a.setStage("")
		return err
	}
	a.mu.Lock()
	a.cfg.Email = strings.TrimSpace(email)
	a.cfg.UID = session.UID
	a.cfg.Access = tokens.Access
	a.cfg.Refresh = tokens.Refresh
	a.cfg.ExpiresAt = time.Now().Add(time.Duration(tokens.ExpiresIn-60) * time.Second).Unix()
	saveErr := config.Save(a.cfg)
	a.mu.Unlock()
	if saveErr != nil {
		a.setStage("")
		return saveErr
	}
	a.setStage("")
	go a.RefreshUsage()
	return nil
}

var errCode = errors.New("code needed")

func NeedsCode(err error) bool { return errors.Is(err, errCode) }

func (a *App) SignOut() {
	a.Disconnect()
	a.mu.Lock()
	a.cfg.Email = ""
	a.cfg.UID = ""
	a.cfg.Access = ""
	a.cfg.Refresh = ""
	a.cfg.ExpiresAt = 0
	a.pass = nil
	config.Save(a.cfg)
	a.mu.Unlock()
}

func (a *App) access() (string, error) {
	a.mu.Lock()
	access, refresh, expiry := a.cfg.Access, a.cfg.Refresh, a.cfg.ExpiresAt
	a.mu.Unlock()
	if access == "" && refresh == "" {
		return "", errors.New("not signed in")
	}
	if expiry > 0 && time.Now().Unix() > expiry && refresh != "" {
		tokens, err := fxa.Refresh(refresh)
		if err != nil {
			return "", err
		}
		a.mu.Lock()
		a.cfg.Access = tokens.Access
		a.cfg.Refresh = tokens.Refresh
		a.cfg.ExpiresAt = time.Now().Add(time.Duration(tokens.ExpiresIn-60) * time.Second).Unix()
		config.Save(a.cfg)
		a.mu.Unlock()
		return tokens.Access, nil
	}
	return access, nil
}

func (a *App) RefreshUsage() {
	access, err := a.access()
	if err != nil {
		return
	}
	pass, err := fxa.PassToken(access)
	if err != nil {
		return
	}
	a.mu.Lock()
	a.pass = pass
	a.mu.Unlock()
}

func (a *App) Connect() error {
	a.mu.Lock()
	if a.live || a.connecting {
		a.mu.Unlock()
		return nil
	}
	a.connecting = true
	a.fault = ""
	a.needAdmin = false
	a.stage = "در حال اتصال…"
	a.mu.Unlock()

	err := a.connect()
	a.mu.Lock()
	a.connecting = false
	a.stage = ""
	if err != nil {
		a.fault = err.Error()
		if strings.Contains(err.Error(), "administrator") || strings.Contains(err.Error(), "root") {
			a.needAdmin = true
		}
	}
	a.mu.Unlock()
	if err != nil {
		a.teardown()
	}
	return err
}

func (a *App) connect() error {
	access, err := a.access()
	if err != nil {
		return err
	}
	a.mu.Lock()
	a.stage = "دریافت پاس پروکسی…"
	a.mu.Unlock()
	pass, err := fxa.PassToken(access)
	if err != nil {
		return err
	}
	a.mu.Lock()
	a.pass = pass
	a.mu.Unlock()

	if len(a.servers) == 0 {
		if _, err := a.LoadServers(); err != nil {
			return err
		}
	}
	a.mu.Lock()
	code := a.cfg.Server
	full := a.cfg.FullTunnel
	port := a.cfg.Port
	servers := a.servers
	a.mu.Unlock()
	target, err := pick(servers, code)
	if err != nil {
		return err
	}

	var keep []netip.Addr
	if full {
		keep = tundev.Neighbours(target.Host)
		if len(keep) == 0 {
			return errors.New("could not resolve the proxy server before starting the tunnel")
		}
		target.DialHost = keep[0].String()
		keep = keep[:1]
	}
	dial := func(host string, remotePort int) (io.ReadWriteCloser, error) {
		return tunnel.Open(target, host, remotePort, pass.Token, 15*time.Second)
	}

	a.mu.Lock()
	a.stage = "برقراری تونل…"
	a.mu.Unlock()

	if full {
		if err := wintun.Ensure(); err != nil {
			return err
		}
		created, err := engine.Start(target.Host, dial, keep)
		if err != nil {
			return err
		}
		a.mu.Lock()
		a.engine = created
		a.live = true
		a.since = time.Now().Unix()
		a.mu.Unlock()
	} else {
		server, err := socks.Listen(net.JoinHostPort("127.0.0.1", strconv.Itoa(port)), func(host string, remotePort int) (io.ReadWriteCloser, error) {
			return dial(host, remotePort)
		})
		if err != nil {
			return err
		}
		a.mu.Lock()
		a.proxy = server
		a.cfg.Port = server.Port()
		a.live = true
		a.since = time.Now().Unix()
		a.mu.Unlock()
		if err := sysproxy.Set(server.Port()); err != nil {
			return err
		}
	}
	a.mu.Lock()
	a.stage = ""
	a.mu.Unlock()
	if ip, err := tunnel.Probe(target, pass.Token, 12*time.Second); err == nil {
		a.mu.Lock()
		a.exit = ip
		a.mu.Unlock()
	}
	return nil
}

func (a *App) teardown() {
	a.mu.Lock()
	engineNow := a.engine
	proxyNow := a.proxy
	a.engine = nil
	a.proxy = nil
	a.live = false
	a.exit = ""
	a.since = 0
	a.mu.Unlock()
	if engineNow != nil {
		engineNow.Stop()
	}
	if proxyNow != nil {
		proxyNow.Close()
		sysproxy.Clear()
	}
}

func (a *App) Disconnect() {
	a.teardown()
	a.mu.Lock()
	a.fault = ""
	a.needAdmin = false
	a.mu.Unlock()
}

func (a *App) Close() {
	a.teardown()
}

func (a *App) setStage(text string) {
	a.mu.Lock()
	a.stage = text
	a.mu.Unlock()
}

func pick(servers []fxa.Location, code string) (tunnel.Target, error) {
	if len(servers) == 0 {
		return tunnel.Target{}, errors.New("no servers")
	}
	for _, place := range servers {
		if code != "" && (strings.EqualFold(place.Code, code) || strings.EqualFold(place.Host, code)) {
			return tunnel.Target{Host: place.Host, Port: place.Port}, nil
		}
	}
	for _, place := range servers {
		if place.Recommended {
			return tunnel.Target{Host: place.Host, Port: place.Port}, nil
		}
	}
	return tunnel.Target{Host: servers[0].Host, Port: servers[0].Port}, nil
}

func (a *App) Config() config.Config { return a.cfg }
