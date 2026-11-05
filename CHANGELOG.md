# Changelog

## v1.10.3 — 2026-11-05

### Added
- Single Go version package and `--version` alignment tests for `v1.10.3`.
- Real P-256 WARP MASQUE endpoint scanner with a hard 10-worker cap.
- `warp_endpoints.json` persistence and desktop “Auto-scan WARP” settings action.
- DNS integration tests for the internal resolver `10.111.0.53`.
- 10 MB download overhead-budget regression test.
- Repository secret scan for private keys and GitHub PATs.
- OpenWrt init.d process-start smoke test in the official rootfs container.
- macOS DMG artifacts and signed `SHA256SUMS.txt` release pipeline.

### Changed
- Tunnel DNS address is now `10.111.0.53` instead of `1.1.1.1`.
- Flutter desktop version and Android version metadata aligned to 1.10.3.
- Release notes explicitly state OpenWrt live-tunnel testing is not performed in CI.

### Added (transport, resilience and verification)
- Real advanced transports with automatic fallback, all off by default and
  labelled stable: DoQ (RFC 9250), ECH (RFC 9460), domain fronting, TCP Fast
  Open, MPTCP and WebSocket tunneling (RFC 6455). See `docs/ADVANCED.md`.
- Desktop UDP relay over WARP MASQUE: games and WebRTC/STUN (3074, 3478, 27015,
  4500, ...) now traverse the tunnel, fragmented to the 1180-byte inner MTU,
  metered, and re-dialled automatically after an endpoint dies.
- Idle UDP mappings expire after 30 seconds like a NAT entry, fixing a goroutine
  and endpoint leak found by the soak.
- Proxy failover: two backup locations are resolved and route-protected at
  connect time; a dead primary is replaced mid-session and the winner stays
  sticky, while credential/quota refusals are still surfaced to the user.
- Idle-path watchdog: a real CONNECT probe every 30 s only when no traffic
  moved, rebuilding the session after three failures with growing backoff.
- Flutter settings for all six advanced options, a transport-parameter dialog,
  a live-path indicator, and widget tests; Flutter analyze/test now run on every
  push and gate the release.
- `Soak` workflow: 5-minute soak on every push, weekly 15-minute soak, and
  on-demand stages up to 4 x 350 min (~23 h 20 m) with heap/goroutine/CPU budgets.
- `Verify Release` workflow: re-downloads published assets and verifies the key
  fingerprint, signed SHA256SUMS, every signature, a clean Debian install, the
  OpenWrt package contents and the APK certificate; live connect only with test
  credentials, otherwise reported as NOT PERFORMED.
- `docs/ADVANCED.md` and `docs/TESTING.md` document exactly what is proven.

### Known limitations
- Android keeps its own Kotlin core: no advanced transports and no UDP game path.
- A full 24-hour soak has not been run; only short soaks (3-5 minutes) have.
- WARP reachability, real operator DPI, real-device installs and OS-level kill
  switch remain unproven; `bypass.Validate()` is still a no-op and Android HTTP
  proxy authentication is unsupported.
