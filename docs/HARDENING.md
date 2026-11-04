# Gozar core hardening

## Scope

This branch implements the requested core-hardening work in layers. It does not
claim a signed release: release publication remains gated on CI, platform
runtime checks, and signing credentials that are not present in this sandbox.

## Implemented

- Windows WFP dynamic session with DNS and IPv6 filters.
- uTLS Chrome ClientHello and first-flight fragmentation.
- Ed25519 + SHA256 update verification and `gozar-sign`.
- Wintun System32-only loading and hash verification.
- Cloudflare WARP registration/scanner/client using P-256 and RFC 9484-style
  connect-ip, with HTTP/3 preferred and HTTP/2 fallback.
- HTTP/2 session pool prewarming.
- Account rotation worker at 90% usage or HTTP 429/402.
- MTU 1280 and TCP SYN MSS clamping.
- GeoIP/GeoSite direct routing primitives for `.ir` and Iranian CIDRs.
- Linux nftables/cgroup and macOS pf kill-switch rule generation.
- Linux Secret Service and macOS Keychain token-storage helpers.
- Signed remote network settings bundle.
- Headless control socket and `gozarctl`.
- Android wake lock, MTU 1280, DNS single-flight/cache improvements.
- Reproducible Go core build checks and signed-manifest release gating.
- Scheduled competitor snapshot workflow.

## User-controlled UDP/Game Mode/WARP

`udp`, `game_mode`, and `warp` default to `false`. Enabling them must route UDP
through WARP; disabling them returns traffic to the TCP tunnel. The WARP client
and scanner are implemented, but the Android/desktop packet-path integration is
not complete and must not be enabled by default.

## Privilege limitations

Linux Flatpak/Snap cannot reliably apply system WFP/nftables/pf-equivalent
kill switches or create TUN devices without host privileges. Use the native
package or systemd service for full-tunnel and kill-switch features.

## Testing

Run:

```bash
cd go-desktop
go vet ./...
go test ./...
GOOS=windows GOARCH=amd64 go build ./...
GOOS=darwin GOARCH=arm64 go build ./...
cd ..
./gradlew :core:test --no-daemon --max-workers=2
./gradlew :app:testDebugUnitTest --no-daemon --max-workers=2
```

Windows WFP, macOS pf, Linux nftables runtime application, Keychain/Secret
Service, and Android device behavior still require platform runtime validation.
