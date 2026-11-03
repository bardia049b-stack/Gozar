package engine

import "sync"

const (
	ModeNormal    = "normal"
	ModeGame      = "game"
	ModeRelayOnly = "relay_only"
)

const (
	RouteWarp  = "warp"
	RouteRelay = "relay"
	RouteDrop  = "drop"
)

var gamePorts = map[int]bool{
	88: true, 500: true, 3074: true, 3478: true, 3479: true, 3544: true,
	4500: true, 53: true, 3658: true, 3659: true, 5223: true, 9307: true,
	9306: true, 9295: true, 9296: true, 9297: true, 27015: true, 27031: true,
	27036: true, 4380: true, 7777: true, 8766: true, 27000: true, 27050: true,
	51820: true, 1900: true, 5353: true,
}

func init() {
	for port := 6112; port <= 6119; port++ {
		gamePorts[port] = true
	}
	for port := 19302; port <= 19309; port++ {
		gamePorts[port] = true
	}
}

func IsGamePort(port int) bool { return gamePorts[port] }

func GamePorts() []int {
	out := make([]int, 0, len(gamePorts))
	for port := range gamePorts {
		out = append(out, port)
	}
	sortInts(out)
	return out
}

func sortInts(values []int) {
	for i := 1; i < len(values); i++ {
		for j := i; j > 0 && values[j] < values[j-1]; j-- {
			values[j], values[j-1] = values[j-1], values[j]
		}
	}
}

func EffectiveUDPMode(mode string, batterySaver bool) string {
	switch mode {
	case ModeGame:
		if batterySaver {
			return ModeNormal
		}
		return ModeGame
	case ModeRelayOnly:
		return ModeRelayOnly
	default:
		return ModeNormal
	}
}

func RouteUDP(mode string, consoleMode, batterySaver bool, port int, dns bool) string {
	if !consoleMode {
		return RouteDrop
	}
	switch EffectiveUDPMode(mode, batterySaver) {
	case ModeGame:
		return RouteWarp
	case ModeRelayOnly:
		if dns || udpAllowed(port) {
			return RouteRelay
		}
		return RouteDrop
	default:
		if dns {
			return RouteRelay
		}
		if IsGamePort(port) {
			return RouteWarp
		}
		if udpAllowed(port) {
			return RouteRelay
		}
		return RouteDrop
	}
}

type udpPolicy struct {
	mu      sync.RWMutex
	mode    string
	console bool
	saver   bool
}

func (p *udpPolicy) set(mode string, console, saver bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	switch mode {
	case ModeGame, ModeRelayOnly:
		p.mode = mode
	default:
		p.mode = ModeNormal
	}
	p.console, p.saver = console, saver
}

func (p *udpPolicy) get() (string, bool, bool) {
	p.mu.RLock()
	defer p.mu.RUnlock()
	mode := p.mode
	if mode == "" {
		mode = ModeNormal
	}
	return mode, p.console, p.saver
}

func (e *Engine) SetUDPPolicy(mode string, consoleMode, batterySaver bool) {
	e.policy.set(mode, consoleMode, batterySaver)
	resetUDPDenials()
}

func (e *Engine) SetWARPDDialer(dial DatagramDialer) {
	e.warpDial = dial
}

func (e *Engine) routeFor(port int, dns bool) string {
	mode, console, saver := e.policy.get()
	return RouteUDP(mode, console, saver, port, dns)
}

func RelayNeeded(consoleMode, udpRelay bool) bool { return consoleMode && !udpRelay }
