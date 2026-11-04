package app

import (
	"context"
	"path/filepath"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/config"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/warp"
)

// ScanWarpEndpoints registers a temporary WARP identity, probes official WARP
// endpoints with a real P-256 MASQUE handshake (max 10 workers), and persists
// warp_endpoints.json. It never returns or logs the private key/token.
func (a *App) ScanWarpEndpoints() (map[string]any, error) {
	registerCtx, cancelRegister := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancelRegister()
	identity, err := warp.Register(registerCtx, nil)
	if err != nil {
		return nil, err
	}
	scanner := warp.DefaultScanner()
	scanner.Identity = identity
	scanCtx, cancelScan := context.WithTimeout(context.Background(), 70*time.Second)
	defer cancelScan()
	path := filepath.Join(config.Dir(), "warp_endpoints.json")
	report, err := scanner.ScanAndSave(scanCtx, path)
	if err != nil {
		return nil, err
	}
	return map[string]any{
		"best":    report.Best,
		"path":    path,
		"results": report.Results,
		"count":   len(report.Results),
	}, nil
}
