package app

import (
	"errors"
	"sort"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/advanced"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/bypass"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/config"
)

// AdvancedOption is one user-visible transport toggle. None of them is labelled
// experimental: each one is a complete implementation with an automatic
// fallback to the standard tunnel, and all of them ship disabled.
type AdvancedOption struct {
	Name        string `json:"name"`
	Enabled     bool   `json:"enabled"`
	NeedsParams bool   `json:"needs_params"`
	Ready       bool   `json:"ready"`
	Path        string `json:"path"`
}

type AdvancedState struct {
	Options []AdvancedOption `json:"options"`
	Params  advanced.Params  `json:"params"`
	Live    string           `json:"live_path"`
}

var advancedNames = []string{"doq", "ech", "domain_fronting", "tfo", "mptcp", "wss"}

func advancedFlag(options bypass.Options, name string) bool {
	switch name {
	case "doq":
		return options.DoQ
	case "ech":
		return options.ECH
	case "domain_fronting":
		return options.DomainFronting
	case "tfo":
		return options.TFO
	case "mptcp":
		return options.MPTCP
	case "wss":
		return options.WSS
	}
	return false
}

func withAdvancedFlag(options bypass.Options, name string, enabled bool) (bypass.Options, bool) {
	switch name {
	case "doq":
		options.DoQ = enabled
	case "ech":
		options.ECH = enabled
	case "domain_fronting":
		options.DomainFronting = enabled
	case "tfo":
		options.TFO = enabled
	case "mptcp":
		options.MPTCP = enabled
	case "wss":
		options.WSS = enabled
	default:
		return options, false
	}
	return options, true
}

func advancedPath(name string) string {
	switch name {
	case "doq":
		return advanced.PathDoQ + " → " + advanced.PathDoH
	case "ech":
		return advanced.PathECH + " → " + advanced.PathTLS
	case "domain_fronting":
		return advanced.PathFront + " → " + advanced.PathTLS
	case "tfo":
		return advanced.PathTFO + " → " + advanced.PathTCP
	case "mptcp":
		return advanced.PathMPTCP + " → " + advanced.PathTCP
	case "wss":
		return advanced.PathWSS + " → h2 → h1"
	}
	return ""
}

// AdvancedState reports the toggles, their parameters and whether the
// parameters needed by a transport are present.
func (a *App) AdvancedState() AdvancedState {
	a.mu.Lock()
	options := a.cfg.Advanced
	params := a.cfg.AdvancedParams.WithDefaults()
	a.mu.Unlock()
	state := AdvancedState{Params: params}
	if a.transport != nil {
		state.Live = a.transport.LastAdvancedPath()
	}
	names := append([]string(nil), advancedNames...)
	sort.Strings(names)
	for _, name := range names {
		needs := name == "ech" || name == "domain_fronting"
		ready := true
		switch name {
		case "ech":
			ready = params.ECHConfigList != ""
		case "domain_fronting":
			ready = params.FrontHost != ""
		}
		state.Options = append(state.Options, AdvancedOption{
			Name: name, Enabled: advancedFlag(options, name), NeedsParams: needs, Ready: ready, Path: advancedPath(name),
		})
	}
	return state
}

// SetAdvanced persists one toggle. Transport changes take effect on the next
// connection; DoQ is applied to the running resolver immediately.
func (a *App) SetAdvanced(name string, enabled bool) error {
	a.mu.Lock()
	next, ok := withAdvancedFlag(a.cfg.Advanced, name)
	if !ok {
		a.mu.Unlock()
		return errors.New("unknown advanced option")
	}
	if err := next.Validate(); err != nil {
		a.mu.Unlock()
		return err
	}
	a.cfg.Advanced = next
	cfg := a.cfg
	engineNow := a.engine
	a.mu.Unlock()
	if err := config.Save(cfg); err != nil {
		return err
	}
	if name == "doq" && engineNow != nil {
		server := ""
		if enabled {
			server = cfg.AdvancedParams.WithDefaults().DoQServer
		}
		engineNow.EnableDoQ(server)
	}
	return nil
}

// SetAdvancedParams stores the DoQ server, ECH config list, fronting host and
// WebSocket path after validating them.
func (a *App) SetAdvancedParams(params advanced.Params) error {
	params = params.WithDefaults()
	if err := params.Validate(); err != nil {
		return err
	}
	a.mu.Lock()
	a.cfg.AdvancedParams = params
	cfg := a.cfg
	engineNow := a.engine
	a.mu.Unlock()
	if err := config.Save(cfg); err != nil {
		return err
	}
	if engineNow != nil && cfg.Advanced.DoQ {
		engineNow.EnableDoQ(params.DoQServer)
	}
	return nil
}
