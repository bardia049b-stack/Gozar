# Gozar v1.10.4 — Release Notes

Date: 2026-11-05
Status: **Released.** Every gate below ran on GitHub Actions. Items marked ❌ were **not** proven and must not be assumed to work.

This release closes the feature gap between desktop and Android, cuts the bytes the client puts on the wire, connects the security features that existed but were never on the traffic path, and adds a real operating-system kill switch on Linux and macOS.

## 1. Android reaches parity with desktop

The Android client used to be pure JVM: a userspace TCP/IP stack that could only carry TCP. UDP was dropped, so game consoles, WebRTC and STUN did not work through the tunnel.

- The Go core is now compiled into the APK. `gomobile bind` produces `gozar-core.aar` for **arm64-v8a, armeabi-v7a, x86 and x86_64**, and CI fails if any ABI is missing from the archive.
- `internal/engine.StartFD` attaches the gVisor stack directly to the file descriptor returned by `VpnService.establish()`. It duplicates the descriptor, runs in raw-IP mode and never touches the privileged TUN helpers, so it works inside the Android sandbox without root.
- Every socket the Go core opens is handed back to Kotlin through `mobile.Protector` and excluded from the VPN with `VpnService.protect()`. Without this the tunnel would swallow its own upstream connection and loop. It covers TCP, the DNS resolver and the QUIC sockets used by WARP and DoQ.
- **Game Mode** and a **UDP relay** switch are in Settings → Connection. Turning either on selects the native core, enables the datagram relay and registers a WARP identity on first use.
- All six advanced transports (DoQ, ECH, TFO, MPTCP, WSS, domain fronting) are reachable from Settings → Connection → Advanced transports on Android, with the same validation and the same automatic fallbacks as desktop.
- The JVM stack is still the default for a plain TCP session, so nothing changes for users who do not enable the new switches.
- Because the APK now contains native libraries, the four ABI splits are genuinely different files. In 1.10.3 they were byte-identical, which was correct at the time (there was no native code to split) but is no longer the case.

## 2. Less data for the same traffic

- **Measured, not estimated.** A new CI test pushes a real 10 MB download through the full pipeline — userspace stack, TLS, HTTP/2 CONNECT proxy — and counts the bytes on the socket, not the bytes the application asked for.

  | Workload | Payload | Bytes on the wire | Overhead |
  |:--|--:|--:|--:|
  | Plain tunnel | 10 MiB | 10,540,066 | 1.005× |
  | Fragment + padding + jitter | 10 MiB | 10,540,155 | 1.005× |

  The acceptance budget is 12 MB. Both runs pass with a wide margin.

- **Retransmission counter.** The engine exposes `TCPStats()` (retransmits and segments sent). The `data-budget` job enforces the 5 % ceiling: measured **1/7683 = 0.01 %** plain and **1/7683 = 0.01 %** obfuscated.

  A note on how this is measured, because it matters. The test harness connects two userspace stacks to each other, so the "peer" is a second gVisor stack rather than the operating system's TCP stack, which is what the product actually talks to. On the Windows runner that synthetic peer acknowledges slowly and the inner stack records a high spurious retransmission count (up to 81 %) **while the bytes on the real socket stay at 1.005x and the payload arrives intact**. The hard 5 % gate therefore runs in the Linux `data-budget` job (`GOZAR_RETRANSMIT_GATE=1`), and the other platforms log the number instead of failing on a harness artifact. The wire-budget gate itself runs on every platform.
- **DNS cache floor and negative caching.** Answers used to be discarded when their TTL was below the cache floor, and NXDOMAIN/NODATA answers were never cached at all, so a failing lookup was repeated on every retry. The floor is now 60 s, the ceiling 900 s, and negative answers are held for 120 s.
- **Single-flight** collapses parallel identical queries into one upstream lookup; a test fires 32 concurrent queries and asserts exactly one reaches the resolver.
- **Keep-alive tuning** is now wired: the TCP keep-alive on the proxy socket moves from 30 s to 180 s and the idle watchdog probe from 30 s to 120 s when the option is on. A test opens 25 sequential 4 KB requests and asserts they reuse one session (112,623 bytes on the wire for 100 KB of payload).
- Hit, miss and coalesced counters are exported so the saving is visible instead of claimed.

## 3. The security features are on the traffic path

`FragmentConn`, `Pad` and `Jitter` existed and were covered by unit tests, but nothing in the connection path called them. They are now used:

- `bypass.FragmentConn` splits the TLS ClientHello when `tls_fragment` is enabled.
- Padding is sent as an `X-Padding` header on the CONNECT request — a real HTTP header a proxy ignores, rather than trailing bytes that would corrupt the stream. It is applied on **both** the HTTP/1.1 and the HTTP/2 write paths.
- Jitter delays the CONNECT write by a random interval up to 120 ms.
- Tests assert the header reaches a real proxy, that its payload is a multiple of 128 bytes and unpads cleanly, that it is absent when the option is off, that eight jittered connects take measurably longer, and that a 500-byte write leaves the socket in exact 96-byte chunks.
- A toxiproxy job runs the whole obfuscated tunnel through `slicer` + `latency` + `bandwidth` toxics and round-trips 120 KB intact.

## 4. A real kill switch on Linux and macOS

- **Linux:** an `nftables` table with `policy drop` on output, allowing the tunnel interface, loopback, the process cgroup (so the client can still reach its own proxy), the pinned proxy addresses, the internal resolver and DHCP. Applying is idempotent and reaping removes a table left behind by a crash.
- **macOS:** the rules load into a dedicated `gozar` **anchor**. The previous implementation replaced the whole system ruleset with `pfctl -f`, which would have destroyed the user's own firewall configuration. The current ruleset is now read, preserved, extended with the anchor and restored on removal.
- The guard is applied after the tunnel comes up and removed on teardown, and only when the kill-switch setting is on.
- `Reap()` runs at startup and clears state left by a previous crash.
- A CI job builds a network namespace with a veth pair and proves the whole cycle: baseline reachable → apply → **traffic cut** → `SIGKILL` the holder → **still cut** → reap → **restored** → clean apply/remove cycle.

## 5. Extra automated tests

- **Live WARP against the real Cloudflare endpoint.** CI registers an anonymous identity with `api.cloudflareclient.com`, opens a MASQUE CONNECT-IP tunnel to `162.159.198.2:443` and sends a real DNS query for `cloudflare.com` to `1.1.1.1` **through** the tunnel, then requires a populated answer. This is the first test in the project that touches Cloudflare's production service rather than an in-process mock.
- **Network drop and recovery.** A download succeeds, the path is cut, the tunnel must fail, the path returns and the tunnel must recover.
- **No 24-hour soak in this release**, as requested. The 5-minute soak gate from 1.10.3 still runs.

## 6. `bypass.Validate()` actually validates

It returned `nil` unconditionally. It now rejects combinations that cannot work, with a reason:

| Rejected combination | Why |
|:--|:--|
| ECH + domain fronting | ECH encrypts the name fronting needs to display |
| Domain fronting + non-standard ports | The cover name needs 443 to be believable |
| WSS + H2 multiplexing | An HTTP/1.1 Upgrade cannot share an HTTP/2 connection |
| TFO + MPTCP | The kernel does not accept fast open on a multipath socket |
| ECH + WSS | ECH needs a direct TLS handshake |
| MPTCP on non-Linux | Not available |
| TFO outside Linux/macOS | Not available |

Nine working combinations are asserted to stay accepted, and the platform limits are checked per OS. Android validates through the same code before a setting is stored, and rolls the toggles back if the set is invalid.

## What was verified and what was not

| Item | State |
|:--|:--|
| Go core builds for Android arm64/arm/386/amd64 | ✅ CI |
| AAR contains all four ABIs | ✅ CI gate |
| 10 MB download ≤ 12 MB on the wire | ✅ 1.005× |
| Retransmission rate < 5 % | ✅ 0.01 % on the Linux gate; see the harness note above for Windows |
| DNS floor, negative cache, single-flight | ✅ |
| Fragment / padding / jitter on the real path | ✅ |
| Obfuscated tunnel through toxiproxy | ✅ CI |
| Kill switch cuts and restores on Linux | ✅ netns + SIGKILL |
| `bypass.Validate()` rejects bad sets | ✅ |
| Live WARP to 162.159.198.2:443 | ✅ CI |
| Drop / reconnect recovery | ✅ |
| macOS `pf` anchor against a real Mac | ❌ syntax-checked only; the anchor load/restore path was not exercised on macOS hardware |
| Real PS5 / Xbox over an Android hotspot | ❌ no console hardware in CI; the UDP path is proven with game ports (3074/3478/27015/4500) against a local echo endpoint |
| 24-hour soak | ❌ deliberately excluded from this version |
| OpenWrt beyond install/process smoke | ❌ unchanged |

## Versions

- Go core: `1.10.4`
- Android: `versionName 1.10.4`, `versionCode 36`
- Desktop: `1.10.4+36`

The previous release, **v1.10.3**, is untouched.
