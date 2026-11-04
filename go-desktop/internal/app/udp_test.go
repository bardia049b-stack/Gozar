package app

import (
	"context"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/config"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/packet"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/warp"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/warptest"
	"net/netip"
)

// The WARP private key must never be written in the clear, and registration
// must happen once, not on every packet.
func TestWarpIdentityIsSealedAtRestAndReused(t *testing.T) {
	path := filepath.Join(t.TempDir(), "warp_identity.json")
	identity := &warp.Identity{ID: "device-1", Token: "token-1", PrivateKey: "SUPER-SECRET-PRIVATE-KEY", IPv4: "172.16.0.2", PeerKey: "peer", Endpoint: "162.159.198.1"}
	calls := 0
	register := func(context.Context, *http.Client) (*warp.Identity, error) {
		calls++
		return identity, nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	first, err := loadWarpIdentity(ctx, path, register)
	if err != nil {
		t.Fatal(err)
	}
	if first.PrivateKey != identity.PrivateKey {
		t.Fatal("the identity was not returned intact")
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(raw), identity.PrivateKey) || strings.Contains(string(raw), identity.Token) {
		t.Fatal("the WARP identity was stored in the clear")
	}
	if json.Valid(raw) {
		t.Fatal("the stored identity looks like plain JSON")
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("identity file permissions are %v, want 0600", info.Mode().Perm())
	}
	second, err := loadWarpIdentity(ctx, path, register)
	if err != nil {
		t.Fatal(err)
	}
	if calls != 1 {
		t.Fatalf("registered %d times, want 1", calls)
	}
	if second.ID != identity.ID || second.PrivateKey != identity.PrivateKey {
		t.Fatal("the sealed identity did not round-trip")
	}
	// The seal must be the same protection the account tokens use.
	sealed, err := config.SealSecret([]byte("probe"))
	if err != nil {
		t.Fatal(err)
	}
	plain, err := config.UnsealSecret(sealed)
	if err != nil || string(plain) != "probe" {
		t.Fatalf("secret sealing is broken: %q %v", plain, err)
	}
}

// The application-level UDP path must reach a real MASQUE responder.
func TestPacketDialerCarriesUDPOverWARP(t *testing.T) {
	server, err := warptest.StartUDPEcho()
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	path := filepath.Join(t.TempDir(), "warp_identity.json")
	register := func(context.Context, *http.Client) (*warp.Identity, error) { return server.Identity, nil }
	dial := newPacketDialer(path, server.Addr, register)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	conn, err := dial(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	payload := []byte("game-packet")
	out := packet.BuildUDP(netip.MustParseAddr("172.16.0.2"), netip.MustParseAddr("10.111.0.3"), 40000, 3074, payload)
	if _, err := conn.WritePacket(out); err != nil {
		t.Fatal(err)
	}
	buffer := make([]byte, 4096)
	count, err := conn.ReadPacket(buffer, true)
	if err != nil {
		t.Fatal(err)
	}
	src, _, srcPort, dstPort, answer, err := packet.ParseUDP(buffer[:count])
	if err != nil {
		t.Fatal(err)
	}
	if string(answer) != string(payload) || srcPort != 3074 || dstPort != 40000 || src.String() != "10.111.0.3" {
		t.Fatalf("unexpected answer: %s %d->%d %q", src, srcPort, dstPort, answer)
	}
}

// UDP stays off unless the user turns it on, so the default install behaves
// exactly like 1.10.2.
func TestPacketDialerFollowsTheUDPToggle(t *testing.T) {
	a := &App{cfg: config.Config{FullTunnel: true}}
	if a.packetDialer() != nil {
		t.Fatal("UDP was enabled by default")
	}
	a.cfg.GameMode = true
	if a.packetDialer() == nil {
		t.Fatal("game mode did not enable the UDP path")
	}
	a.cfg.GameMode = false
	a.cfg.UDP = true
	if a.packetDialer() == nil {
		t.Fatal("the UDP toggle did not enable the UDP path")
	}
}
