package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestNormalizeUDPMode(t *testing.T) {
	cases := map[string]string{
		"normal":     UDPNormal,
		"game":       UDPGame,
		"relay_only": UDPRelayOnly,
		"GAME":       UDPGame,
		"  game  ":   UDPGame,
		"":           UDPNormal,
		"warp":       UDPNormal,
		"relay":      UDPNormal,
		"nonsense":   UDPNormal,
	}
	for input, want := range cases {
		if got := NormalizeUDPMode(input); got != want {
			t.Fatalf("NormalizeUDPMode(%q) = %q, want %q", input, got, want)
		}
	}
}

func TestUDPModeDefaultsToNormal(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", dir)
	t.Setenv("HOME", dir)
	if got := Load().UDPMode; got != UDPNormal {
		t.Fatalf("a fresh config must default to normal, got %q", got)
	}
	if Load().BatterySaver {
		t.Fatal("battery saver must default to off")
	}
}

func TestUDPModeRoundTripsThroughSaveAndLoad(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", dir)
	t.Setenv("HOME", dir)
	for _, mode := range []string{UDPNormal, UDPGame, UDPRelayOnly} {
		cfg := defaults()
		cfg.UDPMode = mode
		cfg.BatterySaver = true
		if err := Save(cfg); err != nil {
			t.Fatalf("save failed: %v", err)
		}
		loaded := Load()
		if loaded.UDPMode != mode {
			t.Fatalf("udpMode did not survive the round trip: want %q, got %q", mode, loaded.UDPMode)
		}
		if !loaded.BatterySaver {
			t.Fatal("battery_saver did not survive the round trip")
		}
	}
}

func TestUDPModeIsWrittenUnderTheAgreedJSONKey(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", dir)
	t.Setenv("HOME", dir)
	cfg := defaults()
	cfg.UDPMode = UDPGame
	if err := Save(cfg); err != nil {
		t.Fatalf("save failed: %v", err)
	}
	data, err := os.ReadFile(filepath.Join(Dir(), "config.json"))
	if err != nil {
		t.Fatalf("read failed: %v", err)
	}
	if !contains(string(data), `"udpMode": "game"`) {
		t.Fatalf("the mobile and desktop cores must agree on the key:\n%s", data)
	}
}

func TestSaveNormalisesABadMode(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", dir)
	t.Setenv("HOME", dir)
	cfg := defaults()
	cfg.UDPMode = "broken"
	if err := Save(cfg); err != nil {
		t.Fatalf("save failed: %v", err)
	}
	if got := Load().UDPMode; got != UDPNormal {
		t.Fatalf("a bad mode must be normalised on save, got %q", got)
	}
}

func TestLoadRepairsACorruptMode(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", dir)
	t.Setenv("HOME", dir)
	if err := os.MkdirAll(Dir(), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(Dir(), "config.json"), []byte(`{"udpMode":"wat","settings_version":3}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if got := Load().UDPMode; got != UDPNormal {
		t.Fatalf("a corrupt mode must load as normal, got %q", got)
	}
}

func TestOlderConfigsMigrateToNormal(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", dir)
	t.Setenv("HOME", dir)
	if err := os.MkdirAll(Dir(), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(Dir(), "config.json"), []byte(`{"settings_version":2,"udp_relay":true}`), 0o600); err != nil {
		t.Fatal(err)
	}
	loaded := Load()
	if loaded.UDPMode != UDPNormal {
		t.Fatalf("an older config must migrate to normal, got %q", loaded.UDPMode)
	}
	if loaded.SettingsVersion != schema {
		t.Fatalf("the schema must be upgraded, got %d", loaded.SettingsVersion)
	}
}

func contains(haystack, needle string) bool {
	return len(haystack) >= len(needle) && indexOf(haystack, needle) >= 0
}

func indexOf(haystack, needle string) int {
	for i := 0; i+len(needle) <= len(haystack); i++ {
		if haystack[i:i+len(needle)] == needle {
			return i
		}
	}
	return -1
}
