package gozarcore

const (
	ModeNormal    = "normal"
	ModeGame      = "game"
	ModeRelayOnly = "relay_only"

	RouteWarp  = "warp"
	RouteRelay = "relay"
	RouteDrop  = "drop"
)

var relayPorts = map[int]bool{80: true, 443: true, 3478: true, 8080: true, 8443: true}

var gamePorts = func() map[int]bool {
	table := map[int]bool{}
	for _, port := range []int{
		88, 500, 3074, 3478, 3479, 3544, 4500, 53, 3658, 3659, 5223, 9307,
		9306, 9295, 9296, 9297, 27015, 27031, 27036, 4380, 7777, 8766,
		27000, 27050, 51820, 1900, 5353,
	} {
		table[port] = true
	}
	for port := 6112; port <= 6119; port++ {
		table[port] = true
	}
	for port := 19302; port <= 19309; port++ {
		table[port] = true
	}
	return table
}()

func IsGamePort(port int) bool { return gamePorts[port] }

func NormalizeMode(mode string) string {
	switch mode {
	case ModeGame:
		return ModeGame
	case ModeRelayOnly:
		return ModeRelayOnly
	default:
		return ModeNormal
	}
}

func EffectiveMode(mode string, batterySaver bool) string {
	switch NormalizeMode(mode) {
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

func Route(mode string, consoleMode, batterySaver bool, port int, dns bool) string {
	if !consoleMode {
		return RouteDrop
	}
	switch EffectiveMode(mode, batterySaver) {
	case ModeGame:
		return RouteWarp
	case ModeRelayOnly:
		if dns || relayPorts[port] {
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
		if relayPorts[port] {
			return RouteRelay
		}
		return RouteDrop
	}
}

func RelayNeeded(consoleMode, udpRelay bool) bool { return consoleMode && !udpRelay }
