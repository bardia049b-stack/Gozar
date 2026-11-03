# Datagram relay and the console gateway

This document describes the two new subsystems that ship in 1.10.4: a real
RFC 9298 (CONNECT-UDP) datagram path on the desktop client, and a LAN console
gateway that lets a PlayStation or an Xbox use the tunnel through a Windows,
Linux or macOS computer.

Nothing here changes how the TCP tunnel, MASQUE transport, sign-in flow or
server selection work. The datagram path is additive and can be switched off
without touching the TCP path.

## 1. What the relay actually carries

The production relay is Mozilla's MASQUE endpoint. Its policy was measured
directly (three locations, identical results):

| Probe | Result |
| --- | --- |
| TCP 80 / 443 / 8080 / 8443 | `200` |
| UDP 80 / 443 / 8080 / 8443 / 3478 | `200` |
| UDP 53 / 88 / 500 / 3074 / 3479 / 4500 / 9295 | `502` |
| `connect-ip` (RFC 9484) | `404` |

Two consequences follow and both are reflected in the client:

1. `CONNECT-UDP` is available, so real datagrams can be relayed — QUIC
   (HTTP/3), WebRTC, STUN, DNS-over-QUIC, game traffic that uses the allowed
   ports, and so on.
2. Only the five UDP ports above are open. Anything else is answered with
   `502`, which the client turns into a denial for that port for ten minutes so
   applications fall back to TCP immediately instead of hanging.

`CONNECT-IP` is not offered by this relay, so a transparent full-device IP
tunnel over MASQUE is not possible on this account type.

## 2. The desktop datagram path

`go-desktop/internal/tunnel/udp.go` implements a minimal, dependency-free
CONNECT-UDP client on top of quic-go's QUIC transport:

* QUIC with datagrams enabled, TLS 1.3, ALPN `h3`, an HTTP/3 control stream and
  a `SETTINGS` frame carrying `SETTINGS_H3_DATAGRAM` (`0x33`).
* A request stream carrying QPACK-encoded `CONNECT` with `:protocol=connect-udp`,
  the RFC 9298 path `/.well-known/masque/udp/{host}/{port}/`,
  `capsule-protocol: ?1` and `proxy-authorization: Bearer …`.
* Datagrams framed exactly as the specification requires: the quarter stream ID
  as a varint followed by the UDP payload. The client never adds a context ID,
  because the specification does not define one — an extra byte there is the
  classic interoperability bug and it was found and fixed with a real relay
  before shipping.
* A single reader goroutine demultiplexes inbound datagrams by quarter stream
  ID, so one QUIC connection can carry many flows without cross-talk.
* Limits: 1200-byte initial packets with path MTU discovery disabled (the relay
  does not fragment), a five-minute idle timeout, 20-second keep-alive, and a
  128-datagram queue per flow so a slow consumer cannot exhaust memory.

`go-desktop/internal/engine/udp.go` wires that into the user-space network
stack:

* Every UDP flow that is not DNS goes through the gVisor forwarder, gets its own
  `tunnel.OpenUDP` session, and is relayed in both directions with byte counters.
* A port is relayed only when it is in the allowlist. The default allowlist is
  `80, 443, 3478, 8080, 8443`; users can add more from Settings
  (`udp_ports`). A refused port is blocked for ten minutes and the stack answers
  with ICMP port unreachable so applications switch to TCP at once.
* Flows are closed after 60 seconds of silence in either direction, so a stale
  game session cannot keep a QUIC connection open forever.
* DNS keeps its own dedicated path (DoH over the TCP tunnel), independent of the
  datagram relay.

### Verification

* `TestUDPTunnelCarriesDatagrams` — a loopback HTTP/3 proxy echoes a datagram
  through the real client code; the payload round-trips byte for byte.
* `TestUDPTunnelRejectsMissingPass` — a tunnel without credentials is refused
  before any network traffic.
* `TestUDTRelayReachesTheDatagramTunnel` — a virtual network stack sends a UDP
  datagram, the engine opens a session, and the reply arrives at the socket.
* `TestOtherUDPIsRefusedWithICMP` — a port that is not relayed produces ICMP
  port unreachable and never reaches the DNS resolver.
* `TestRelayPolicyDefaultsAndOverrides` — default allowlist, extra ports,
  cooldown and reset behaviour.
* Live end-to-end run: the shipped client code opened a CONNECT-UDP tunnel to a
  MASQUE relay, sent a STUN binding request, and received a 32-byte binding
  success for `stun.l.google.com:3478` in 238 ms.

## 3. The console gateway

`go-desktop/internal/bridge` is a small, deliberately boring proxy that only
listens on a private address:

* HTTP `CONNECT` for TCP (this is all a PlayStation proxy field can do), plus
  plain HTTP proxying for stores and update servers.
* DNS on UDP 53 for consoles that accept a manual resolver. Queries are answered
  through the same DoH resolver the tunnel already uses, so DNS never leaks to
  the local network.
* Access control: the loopback interface plus an explicit allowlist of CIDR
  ranges. The default is the local `/24` of the gateway machine; anything else is
  dropped before a byte is read.
* Authentication: `Proxy-Authorization: Basic` with a 96-bit password generated
  per machine. PlayStation's proxy field has no username and password, so for
  consoles the allowlist is the control and the password protects the DNS port
  from random LAN hosts; both are shown and rotatable in Settings.
* Limits: 48 concurrent clients, 16 KiB request heads, 30-second header
  deadline, 32 KiB copy buffers, byte counters per direction.
* The gateway is torn down together with the tunnel; it never outlives a
  disconnect and never listens on a public interface.

### Console facts that shaped the UI

| Console | Manual proxy | Manual DNS | Menu path |
| --- | --- | --- | --- |
| PlayStation 5 | Yes, address + port, no credentials, TCP only | Yes | Settings → Network → Settings → Set Up Internet Connection → (Wi-Fi/LAN) → Advanced Settings |
| Xbox Series X\|S | **No proxy field at all** | Yes | Settings → General → Network settings → Advanced settings |

Because consoles send only TCP to a proxy, the datagrams of a game never reach
the proxy — they go straight out of the console's own interface. That is why the
gateway is not enough by itself for multiplayer: the console must send *all* of
its traffic through the computer. On Windows the supported arrangement is a
shared connection (ICS) or a bridged adapter; on Linux a NAT rule; on macOS
Internet Sharing. The app therefore reports exactly what it can do:

* Store, updates, sign-in, streaming and everything TCP → through the gateway.
* Game datagrams → through the gateway only if the console routes its traffic to
  the computer; the app cannot force that from the console menu, and says so.
* UDP ports the relay does not carry (3074, 88, 500, 4500, …) are refused by the
  relay itself and cannot be fixed on the client.

### Verification

* `TestBridgeTunnelsConnectAndRejectsOutsiders` — CONNECT is tunnelled, byte
  counters move, loopback is always allowed, an unlisted LAN address is refused
  and an allowlisted subnet is admitted.
* `TestBridgeRequiresCredentialsWhenSet` — a request without credentials gets
  `407` with a challenge; the correct Basic value gets `200`.
* `TestSplitTargetHandlesIPv6AndPorts` — bracketed IPv6 targets, default ports
  and malformed hosts.

## 4. Settings surface

Desktop (Flutter) gains a **Console and gaming** section with:

* The two console cards, drawn as vector marks, each with the exact menu path,
  selectable and stored as `console_kind`.
* A gateway switch that can only be turned on while the tunnel is up, showing
  the endpoint, the number of connected consoles and the transferred bytes.
* Endpoint copy, password rotation and allowlist editing.
* A datagram relay switch (disabled while connected, because it changes the
  engine) and an editor for extra UDP ports.

Android gains a **Console** row in Connection settings with the same two
profiles and the same menu paths, and turning a profile on also enables LAN
bypass so local streaming keeps working. Android does not yet relay arbitrary
datagrams: its user-space stack is TCP-only, and claiming otherwise would be a
lie. The row says that plainly and points at the desktop gateway.

## 5. Threat model for the gateway

* Only addresses in the allowlist can reach the listeners; loopback is always
  allowed for local testing.
* The password is random per machine, stored sealed by the same DPAPI/AES-GCM
  layer as the account tokens, and compared in constant time.
* The gateway cannot be reached from outside the LAN because it binds a private
  address and the tunnel device has no inbound route.
* It does not proxy to link-local or multicast destinations, does not follow
  redirects, and strips hop-by-hop headers (`Connection`,
  `Proxy-Connection`, `Proxy-Authorization`) before forwarding.
* It never logs URLs, only counters and the last peer address.
