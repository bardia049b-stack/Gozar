package config

import (
	"encoding/json"
	"os"
	"path/filepath"
)

type Config struct {
	Email      string   `json:"email"`
	UID        string   `json:"uid"`
	Access     string   `json:"access"`
	Refresh    string   `json:"refresh"`
	ExpiresAt  int64    `json:"expires_at"`
	Server     string   `json:"server"`
	Language   string   `json:"language"`
	FullTunnel bool     `json:"full_tunnel"`
	BypassLAN  bool     `json:"bypass_lan"`
	Bypass     []string `json:"bypass"`
	Chain      []string `json:"chain"`
	Port       int      `json:"port"`
}

func Dir() string {
	base, err := os.UserConfigDir()
	if err != nil {
		base = os.Getenv("HOME")
	}
	dir := filepath.Join(base, "Gozar")
	os.MkdirAll(dir, 0o755)
	return dir
}

func path() string { return filepath.Join(Dir(), "config.json") }

func defaults() Config { return Config{FullTunnel: true, BypassLAN: true, Port: 10810} }

func Load() Config {
	fresh := defaults()
	data, err := os.ReadFile(path())
	if err != nil {
		return fresh
	}
	var loaded Config
	if json.Unmarshal(data, &loaded) != nil {
		return fresh
	}
	if loaded.Port == 0 {
		loaded.Port = fresh.Port
	}
	return loaded
}

func Save(current Config) error {
	data, err := json.MarshalIndent(current, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path(), data, 0o600)
}

func (c Config) SignedIn() bool { return c.Refresh != "" || c.Access != "" }
