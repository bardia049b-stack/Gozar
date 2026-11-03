package engine

import "testing"

func TestMasterOffDropsEveryDatagram(t *testing.T) {
	for _, mode := range []string{ModeNormal, ModeGame, ModeRelayOnly} {
		for _, port := range []int{53, 443, 3074, 3478, 9295, 51820} {
			if got := RouteUDP(mode, false, false, port, port == 53); got != RouteDrop {
				t.Fatalf("master off must drop UDP: mode=%s port=%d got=%s", mode, port, got)
			}
		}
	}
}

func TestGameModeSendsEverythingToWarp(t *testing.T) {
	// Non-game ports are the point of this mode: consoles and launchers pick
	// unpredictable ports, so "game" must not filter.
	for _, port := range []int{443, 80, 8080, 12345, 50000, 3074, 53} {
		if got := RouteUDP(ModeGame, true, false, port, port == 53); got != RouteWarp {
			t.Fatalf("game mode must use WARP for port %d, got %s", port, got)
		}
	}
}

func TestNormalModeOnlySendsGamePortsToWarp(t *testing.T) {
	cases := map[int]string{
		3074:  RouteWarp,
		3478:  RouteWarp, // STUN: WARP wins over the relay allowance
		9295:  RouteWarp,
		51820: RouteWarp,
		443:   RouteRelay,
		80:    RouteRelay,
		8443:  RouteRelay,
		53:    RouteRelay,
		12345: RouteDrop,
	}
	for port, want := range cases {
		if got := RouteUDP(ModeNormal, true, false, port, port == 53); got != want {
			t.Fatalf("normal mode port %d: want %s, got %s", port, want, got)
		}
	}
}

func TestRelayOnlyNeverUsesWarp(t *testing.T) {
	for _, port := range []int{53, 80, 443, 3074, 3478, 8080, 8443, 9295, 51820} {
		got := RouteUDP(ModeRelayOnly, true, false, port, port == 53)
		if got == RouteWarp {
			t.Fatalf("relay_only must keep WARP closed, port %d routed to WARP", port)
		}
	}
	if got := RouteUDP(ModeRelayOnly, true, false, 3074, false); got != RouteDrop {
		t.Fatalf("a non-relay port must be dropped in relay_only, got %s", got)
	}
	if got := RouteUDP(ModeRelayOnly, true, false, 443, false); got != RouteRelay {
		t.Fatalf("443 must stay on the relay in relay_only, got %s", got)
	}
}

func TestBatterySaverDowngradesGameToNormal(t *testing.T) {
	if got := EffectiveUDPMode(ModeGame, true); got != ModeNormal {
		t.Fatalf("battery saver must downgrade game, got %s", got)
	}
	if got := EffectiveUDPMode(ModeGame, false); got != ModeGame {
		t.Fatalf("game must stay game without the saver, got %s", got)
	}
	if got := EffectiveUDPMode(ModeRelayOnly, true); got != ModeRelayOnly {
		t.Fatalf("relay_only must survive the saver, got %s", got)
	}
	// With the saver on, web UDP leaves WARP but game ports stay.
	if got := RouteUDP(ModeGame, true, true, 443, false); got != RouteRelay {
		t.Fatalf("saver must move web UDP off WARP, got %s", got)
	}
	if got := RouteUDP(ModeGame, true, true, 3074, false); got != RouteWarp {
		t.Fatalf("saver must keep game ports on WARP, got %s", got)
	}
}

func TestUnknownModeFallsBackToNormal(t *testing.T) {
	if got := EffectiveUDPMode("nonsense", false); got != ModeNormal {
		t.Fatalf("unknown mode must normalise, got %s", got)
	}
	if got := RouteUDP("nonsense", true, false, 3074, false); got != RouteWarp {
		t.Fatalf("unknown mode must behave like normal, got %s", got)
	}
}

func TestGamePortTableCoversConsoleRanges(t *testing.T) {
	for _, port := range []int{88, 500, 3074, 3478, 3479, 3544, 4500, 9307, 6112, 6119, 19302, 19309} {
		if !IsGamePort(port) {
			t.Fatalf("port %d should be a game port", port)
		}
	}
	for _, port := range []int{80, 443, 8080, 6120, 19310, 12345} {
		if IsGamePort(port) {
			t.Fatalf("port %d should not be a game port", port)
		}
	}
	ports := GamePorts()
	for i := 1; i < len(ports); i++ {
		if ports[i] < ports[i-1] {
			t.Fatal("GamePorts must be sorted")
		}
	}
}

func TestEnginePolicyIsAppliedPerFlow(t *testing.T) {
	e := &Engine{}
	e.SetUDPPolicy(ModeNormal, true, false)
	if got := e.routeFor(3074, false); got != RouteWarp {
		t.Fatalf("want WARP for 3074, got %s", got)
	}
	if got := e.routeFor(443, false); got != RouteRelay {
		t.Fatalf("want relay for 443, got %s", got)
	}
	e.SetUDPPolicy(ModeGame, true, false)
	if got := e.routeFor(443, false); got != RouteWarp {
		t.Fatalf("after switching to game, 443 must use WARP, got %s", got)
	}
	e.SetUDPPolicy(ModeGame, false, false)
	if got := e.routeFor(3074, false); got != RouteDrop {
		t.Fatalf("master off must drop, got %s", got)
	}
}

func TestDialDatagramFallsBackWhenWarpIsDown(t *testing.T) {
	e := &Engine{}
	e.SetUDPPolicy(ModeNormal, true, false)
	relayCalled := false
	e.udpDial = func(host string, port int) (Datagram, error) {
		relayCalled = true
		return nil, ErrUDPDenied
	}
	// 3478 is both a game port and a relay port: with no WARP tunnel it must
	// fall back to the relay rather than fail.
	e.dialDatagram("1.1.1.1", 3478)
	if !relayCalled {
		t.Fatal("a missing WARP tunnel must fall back to the relay")
	}
}

func TestDialDatagramUsesWarpForGamePorts(t *testing.T) {
	e := &Engine{}
	e.SetUDPPolicy(ModeGame, true, false)
	warpCalled := false
	e.warpDial = func(host string, port int) (Datagram, error) {
		warpCalled = true
		return nil, ErrUDPDenied
	}
	e.udpDial = func(host string, port int) (Datagram, error) {
		t.Fatal("game mode must not spend the Mozilla quota")
		return nil, ErrUDPDenied
	}
	e.dialDatagram("1.1.1.1", 3074)
	if !warpCalled {
		t.Fatal("game mode must dial WARP")
	}
}

func TestDialDatagramRelayOnlyNeverTouchesWarp(t *testing.T) {
	e := &Engine{}
	e.SetUDPPolicy(ModeRelayOnly, true, false)
	e.warpDial = func(host string, port int) (Datagram, error) {
		t.Fatal("relay_only must never dial WARP")
		return nil, ErrUDPDenied
	}
	e.udpDial = func(host string, port int) (Datagram, error) { return nil, ErrUDPDenied }
	if _, err := e.dialDatagram("1.1.1.1", 3074); err == nil {
		t.Fatal("3074 is not a relay port and must be refused")
	}
	e.dialDatagram("1.1.1.1", 443)
}
