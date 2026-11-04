package config

import (
	"os"
	"testing"
)

func TestUDPGameModeWarpDefaultOff(t *testing.T) {
	sandbox(t)
	loaded := Load()
	if loaded.UDP || loaded.GameMode || loaded.Warp || loaded.KillSwitch {
		t.Fatalf("unsafe defaults: %+v", loaded)
	}
}

func TestLegacyConfigDoesNotEnableUDPWarp(t *testing.T) {
	target := sandbox(t)
	if err := os.WriteFile(target, []byte(`{"port":10810,"theme":"system"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	loaded := Load()
	if loaded.UDP || loaded.GameMode || loaded.Warp {
		t.Fatalf("legacy config enabled UDP/WARP: %+v", loaded)
	}
}

func TestExplicitUDPGameModeWarpRoundTrip(t *testing.T) {
	sandbox(t)
	value := defaults()
	value.UDP = true
	value.GameMode = true
	value.Warp = true
	if err := Save(value); err != nil {
		t.Fatal(err)
	}
	loaded := Load()
	if !loaded.UDP || !loaded.GameMode || !loaded.Warp {
		t.Fatalf("explicit user choice was not preserved: %+v", loaded)
	}
}
