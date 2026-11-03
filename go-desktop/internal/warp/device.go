package warp

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
)

type storedDevice struct {
	ID          string `json:"id"`
	Token       string `json:"token"`
	PrivateKey  string `json:"private_key"`
	PeerKey     string `json:"peer_key"`
	IPv4        string `json:"ipv4"`
	IPv6        string `json:"ipv6,omitempty"`
	Endpoint    string `json:"endpoint"`
	AccountType string `json:"account_type,omitempty"`
}

func SaveDevice(path string, device *Device) error {
	if device == nil {
		return errors.New("the warp device is missing")
	}
	payload, err := json.Marshal(storedDevice{
		ID:          device.ID,
		Token:       device.Token,
		PrivateKey:  device.PrivateKey,
		PeerKey:     device.PeerKey,
		IPv4:        device.IPv4,
		IPv6:        device.IPv6,
		Endpoint:    device.Endpoint,
		AccountType: device.AccountType,
	})
	if err != nil {
		return err
	}
	if dir := filepath.Dir(path); dir != "" {
		if err := os.MkdirAll(dir, 0o700); err != nil {
			return err
		}
	}
	temporary := path + ".tmp"
	if err := os.WriteFile(temporary, payload, 0o600); err != nil {
		return err
	}
	return os.Rename(temporary, path)
}

func LoadDevice(path string) (*Device, error) {
	payload, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var stored storedDevice
	if err := json.Unmarshal(payload, &stored); err != nil {
		return nil, err
	}
	if stored.PrivateKey == "" || stored.PeerKey == "" || stored.IPv4 == "" || stored.Endpoint == "" {
		return nil, errors.New("the stored warp device is incomplete")
	}
	return &Device{
		ID:          stored.ID,
		Token:       stored.Token,
		PrivateKey:  stored.PrivateKey,
		PeerKey:     stored.PeerKey,
		IPv4:        stored.IPv4,
		IPv6:        stored.IPv6,
		Endpoint:    stored.Endpoint,
		AccountType: stored.AccountType,
	}, nil
}

func DeviceFor(ctx context.Context, path string) (*Device, error) {
	if device, err := LoadDevice(path); err == nil {
		return device, nil
	}
	device, err := Register(ctx)
	if err != nil {
		return nil, err
	}
	if err := SaveDevice(path, device); err != nil {
		return nil, err
	}
	return device, nil
}
