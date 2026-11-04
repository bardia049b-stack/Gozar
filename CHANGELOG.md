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

### Known limitations
- Advanced transports (DoQ, ECH, domain fronting, TFO, MPTCP, WSS) are not yet certified as production-stable paths.
- 24-hour stability and real-device post-release checks remain manual gates.
