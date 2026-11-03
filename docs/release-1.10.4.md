# Gozar 1.10.4 — real datagrams, and a console gateway that tells the truth

Gozar 1.10.4 adds the two things people kept asking for, and refuses to fake a
third one. The desktop client now relays **UDP datagrams over the same MASQUE
tunnel** it already uses for TCP: QUIC, HTTP/3, WebRTC, STUN, voice chat,
DNS-over-QUIC and game traffic that uses a port the relay carries. The desktop
client also ships a **console gateway** so a PlayStation 5 or an Xbox can use the
tunnel through the computer, with the real menu paths printed inside Settings
instead of vague advice. What it does **not** do — and will not pretend to do —
is magically turn a relay that only carries five UDP ports into a full IP
tunnel, or give Android a datagram path its TCP-only user-space stack cannot
honestly support yet.

Nothing about the working TCP tunnel changed. The transport, the sign-in flow,
the server selection, the proxy chain and the split-routing rules are the same
code paths. If you only use Gozar to read the news, nothing here can break it.

---

## 1. Datagram relay on desktop

### The problem it solves

A proxy that can only carry TCP is a proxy that breaks a third of the modern
internet. QUIC is how Google, Cloudflare, Meta and half of the CDN world serve
content now. Voice calls, screen sharing, multiplayer and DNS-over-QUIC are all
UDP. Until now Gozar tunnelled TCP and quietly dropped everything else, which
meant an app would sit there waiting for a QUIC handshake that never completed
until it gave up and fell back to TCP — on a good day, ten seconds of nothing on
a page load.

### What was built

`go-desktop/internal/tunnel/udp.go` is a small, self-contained RFC 9298 client:
QUIC with datagrams enabled, an HTTP/3 control stream, a `SETTINGS` frame with
`SETTINGS_H3_DATAGRAM`, a QPACK-encoded extended `CONNECT` carrying
`:protocol=connect-udp`, the standard path
`/.well-known/masque/udp/{host}/{port}/`, `capsule-protocol: ?1`, and the
`proxy-authorization` header the relay expects. Datagrams are framed with the
quarter stream ID as a varint followed by the payload — and **only** that. The
specification defines no extra context byte there, and a stray byte in that
position is exactly the kind of detail that makes a client work against your own
test server and fail against a real relay. It was found the hard way, against a
real relay, and removed.

One QUIC connection serves many flows: the reader demultiplexes inbound
datagrams by quarter stream ID, so thirty game sessions do not mean thirty
handshakes. Each flow has a 128-datagram queue, a five-minute idle timeout, a
20-second keep-alive and 1200-byte initial packets with path MTU discovery
disabled, because the relay does not fragment for you.

On the engine side, every UDP flow that is not DNS now goes through the gVisor
forwarder, gets its own tunnel session, and is relayed in both directions with
byte counters feeding the same speed graph as TCP.

### What the relay lets through, measured

The production relay was probed from three locations with identical results:

| Probe | Result |
| --- | --- |
| TCP 80 / 443 / 8080 / 8443 | `200` |
| UDP 80 / 443 / 8080 / 8443 / 3478 | `200` |
| UDP 53 / 88 / 500 / 3074 / 3479 / 4500 / 9295 | `502` |
| `connect-ip` | `404` |

So the default allowlist is **UDP 80, 443, 3478, 8080, 8443**, and it is a
policy you can extend but not invent: a port the relay answers with `502` stays
refused no matter what the client wants. When that happens Gozar blocks that
port for ten minutes and answers with ICMP port unreachable, so an application
switches to TCP immediately instead of hanging. Settings exposes an extra-ports
field for anything the relay later opens up.

### What this means in practice

* Browsing: HTTP/3 works now. Pages that were waiting on a QUIC probe finish
  faster because the probe succeeds instead of timing out.
* Calls and streaming: WebRTC media over 443 or 3478 gets through; the relay's
  port policy decides the rest.
* Games: anything that lives on 443 or 3478 works; 3074, 88, 500 and 4500 are
  refused by the relay, and no client-side trick changes that.
* DNS: unchanged, still DoH over the TCP tunnel, deliberately separate from the
  datagram path so a UDP outage can never take name resolution down with it.

---

## 2. The console gateway

### Why a gateway and not a proxy

The two consoles are not the same animal.

| | PlayStation 5 | Xbox Series X\|S |
| --- | --- | --- |
| Manual proxy | Yes — address and port only, no credentials, TCP only | **No field at all** |
| Manual DNS | Yes | Yes |
| Menu path | Settings → Network → Settings → Set Up Internet Connection → (Wi-Fi/LAN) → Advanced Settings | Settings → General → Network settings → Advanced settings |

A PlayStation can be pointed at an HTTP proxy, which is why the gateway speaks
`CONNECT`. An Xbox cannot; the only lever it gives you is DNS and the gateway
address, so the computer has to be the router. Both realities are in the app's
text, not in a marketing paragraph.

### What the gateway is

`go-desktop/internal/bridge` is intentionally boring:

* HTTP `CONNECT` for TCP plus plain HTTP proxying for stores and update servers.
* UDP 53 DNS, answered through the tunnel's DoH resolver so queries never leak to
  the local network.
* An allowlist of CIDR ranges — loopback plus the local `/24` by default — with
  everything else dropped before a byte is read.
* A 96-bit password per machine, sealed with the same DPAPI / AES-GCM layer as
  your account tokens, compared in constant time.
* Hard limits: 48 clients, 16 KiB request heads, 30-second header deadline,
  32 KiB copy buffers, counters and a last-peer field, no URL logging.
* Lifecycle tied to the tunnel: disconnect and it is gone. It never binds a
  public interface.

### The honest part

A proxy on the console only carries the console's TCP. Game datagrams go out of
the console's own network interface and never touch the proxy. The gateway
therefore gives you the store, updates, sign-in, streaming and everything else
TCP, and it gives you clean DNS. For game datagrams the console must route *all*
traffic through the computer — a shared connection on Windows, NAT on Linux,
Internet Sharing on macOS — and even then the relay's UDP allowlist decides which
ports survive. Gozar says this in the Settings screen and in this note instead of
implying that a proxy fixes multiplayer.

---

## 3. Settings, on both products

**Desktop** gains a *Console and gaming* section: two selectable console cards
with vector marks and the exact menu paths, a gateway switch that only unlocks
while the tunnel is up, the endpoint with copy, connected-console and byte
counters, password rotation, allowlist editing, a datagram-relay switch and an
extra-ports editor. While connected the relay switch is disabled, because
changing the engine mid-session is how you get half-tunnelled flows.

**Android** gains a *Console* row in Connection settings with the same two
profiles and the same menu paths, and turning a profile on enables LAN bypass so
local streaming keeps working. Android does **not** claim a datagram path: the
user-space stack is TCP-only, so UDP there is still DNS only, and the text says
so and points at the desktop gateway.

---

## 4. Security notes

* The gateway is allowlist-first: refuse by default, admit by CIDR, loopback for
  local tests.
* The password is random per machine, never printed in logs, sealed at rest and
  compared in constant time.
* Hop-by-hop headers are stripped before forwarding; oversized heads and slow
  request heads are rejected.
* The datagram path refuses to open a session without credentials, and a
  credential is never written to a log line.
* The tunnel itself is unchanged: TLS 1.3, MASQUE, no new listening socket except
  the gateway you explicitly switch on, on a private address, while connected.

---

## 5. What was tested

Go, on this commit:

* `TestUDPTunnelCarriesDatagrams` — a loopback HTTP/3 proxy echoes a datagram
  through the real client code; the payload round-trips byte for byte.
* `TestUDPTunnelRejectsMissingPass` — a tunnel with no credentials is refused
  before any packet is sent.
* `TestUDTRelayReachesTheDatagramTunnel` — a virtual network stack sends UDP, the
  engine opens a session, the reply reaches the socket and the counters move.
* `TestOtherUDPIsRefusedWithICMP` — a non-relayed port produces ICMP port
  unreachable and never reaches the DNS resolver.
* `TestRelayPolicyDefaultsAndOverrides` — defaults, extra ports, cooldown, reset.
* `TestBridgeTunnelsConnectAndRejectsOutsiders` — CONNECT works, counters move,
  loopback allowed, an unlisted LAN address refused, an allowlisted subnet
  admitted.
* `TestBridgeRequiresCredentialsWhenSet` — `407` challenge, then `200` with the
  right Basic value.
* `TestSplitTargetHandlesIPv6AndPorts` — bracketed IPv6, default ports,
  malformed hosts.
* The whole existing suite (`go test ./...`), plus `go vet ./...`.

Live, against a real MASQUE relay and the real internet: the shipped client code
opened a CONNECT-UDP tunnel, sent a STUN binding request, and received a 32-byte
binding success for `stun.l.google.com:3478` in 238 ms.

What is **not** claimed: Android datagram relay (not built), `connect-ip` on this
account type (relay answers `404`), and multiplayer on ports the relay refuses.

---

## 6. Upgrading

Install over 1.10.3. Your account, chain, routing and DNS settings are kept; the
datagram relay turns on for existing profiles because it is additive, and the
gateway stays off until you switch it on. Android's console row is optional and
changes nothing until you pick a profile.

If you connected a console before, re-check its proxy or DNS entry: the gateway
now shows the address it actually bound, which may differ from the one you typed
when a VPN adapter was also present.
