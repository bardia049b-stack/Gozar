package app

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"

	connectip "github.com/Diniboy1123/connect-ip-go"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/config"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/engine"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/warp"
)

// warpPackets owns the WARP client for one datagram session so closing the
// relay also releases the QUIC transport instead of leaking it.
type warpPackets struct {
	*connectip.Conn
	client *warp.Client
}

func (w *warpPackets) Close() error {
	err := w.Conn.Close()
	_ = w.client.Close()
	return err
}

// loadWarpIdentity reads the sealed identity, registering a new anonymous one
// on first use. The file holds the private key encrypted, never in the clear.
func loadWarpIdentity(ctx context.Context, path string, register func(context.Context, *http.Client) (*warp.Identity, error)) (*warp.Identity, error) {
	if raw, err := os.ReadFile(path); err == nil {
		plain, err := config.UnsealSecret(string(raw))
		if err == nil {
			var identity warp.Identity
			if json.Unmarshal(plain, &identity) == nil && identity.PrivateKey != "" && identity.ID != "" {
				return &identity, nil
			}
		}
	}
	if register == nil {
		register = warp.Register
	}
	identity, err := register(ctx, nil)
	if err != nil {
		return nil, err
	}
	raw, err := json.Marshal(identity)
	if err != nil {
		return nil, err
	}
	sealed, err := config.SealSecret(raw)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, err
	}
	if err := os.WriteFile(path, []byte(sealed), 0o600); err != nil {
		return nil, err
	}
	return identity, nil
}

// bestWarpEndpoint prefers the endpoint the latency scan measured as fastest.
func bestWarpEndpoint() string {
	path := filepath.Join(config.Dir(), "warp_endpoints.json")
	report, err := warp.LoadResults(path)
	if err != nil || report == nil {
		return ""
	}
	if report.Best != "" {
		return report.Best
	}
	for _, result := range report.Results {
		if result.OK {
			return result.Endpoint
		}
	}
	return ""
}

// newPacketDialer builds the UDP path. endpoint may be "host" or "host:port";
// an empty endpoint uses the one Cloudflare assigned at registration.
func newPacketDialer(identityPath, endpoint string, register func(context.Context, *http.Client) (*warp.Identity, error)) engine.PacketDialer {
	return func(ctx context.Context) (engine.PacketConn, error) {
		identity, err := loadWarpIdentity(ctx, identityPath, register)
		if err != nil {
			return nil, err
		}
		client := warp.NewClient(identity)
		if endpoint != "" {
			host, portText, err := net.SplitHostPort(endpoint)
			if err == nil {
				client.Endpoint = host
				if port, err := strconv.Atoi(portText); err == nil && port > 0 {
					client.Port = port
				}
			} else {
				client.Endpoint = endpoint
			}
		}
		conn, err := client.Dial(ctx)
		if err != nil {
			_ = client.Close()
			return nil, err
		}
		return &warpPackets{Conn: conn, client: client}, nil
	}
}

// packetDialer enables UDP (games, WebRTC/STUN) when the user asked for it.
// Returning nil keeps the previous behaviour: DNS-only UDP.
func (a *App) packetDialer() engine.PacketDialer {
	a.mu.Lock()
	enabled := a.cfg.UDP || a.cfg.GameMode
	a.mu.Unlock()
	if !enabled {
		return nil
	}
	path := filepath.Join(config.Dir(), "warp_identity.json")
	return newPacketDialer(path, bestWarpEndpoint(), nil)
}
