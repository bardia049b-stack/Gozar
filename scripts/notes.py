import base64
import sys
from urllib.parse import urlencode

SECURITY = r"""# Gozar 1.10.5 — a real WARP tunnel, every port, verified end to end

This release adds the path the previous one could not offer: Gozar now registers
its own Cloudflare WARP device and carries full IP traffic through a connect-ip
tunnel (RFC 9484). It is added next to the existing Mozilla relay, not instead of
it: the relay path is still there, still works, and is still what you get when the
new path is off.

## What is new

**A real WARP tunnel.** Gozar registers a free WARP device with a P-256 key,
dials `162.159.198.2:443` over QUIC with TLS 1.3 and a self-signed client
certificate pinned to the registered peer key, and asks for an IP flow with
`cf-connect-ip`. WARP assigns `172.16.0.2` and a `2606:4700:110:…` address, and
from that moment every IP packet is carried in HTTP datagrams.

**Every port, in both directions.** This is the point of the change. The relay
path carries datagrams on a handful of ports (80, 443, 3478, 8080, 8443 as
measured) and refuses the rest, which is why game traffic and console voice chat
kept failing. A connect-ip tunnel carries packets, not flows, so the port set
stops existing. The desktop engine and the console gateway both dial through the
new stack, and UDP is no longer filtered by a list.

**Two carriers, one tunnel.** Direct QUIC is the default because it is fastest
(handshake in well under a second). The second carrier wraps the same tunnel in a
TCP CONNECT to the Mozilla relay, for networks where UDP 443 is filtered. You can
switch between them in Settings; the tunnel code is identical.

**An honest MTU.** The netstack runs at 1180 bytes. The limit was measured
against the live service, not guessed: 1184 works, 1188 stalls, and the failure
looks exactly like a slow site because the certificate chain is the first burst
that has to be split. Fixed at the safe value.

**New icons.** The launcher icon is now the official Material shield on the single
red the app already uses, generated from the official Material source instead of a
hand-drawn glyph. The PlayStation and Xbox marks are the official marks, rendered
in that same single red instead of approximated shapes in brand colours.

## What was verified

Live, against the real service, before this release was built:

* Device registration through the production API, including the P-256 key type and
  the PEM peer key the edge actually returns.
* QUIC handshake to Cloudflare with the registered certificate, `200` on the
  connect-ip request, `Cf-Warp-Colo` naming the colo.
* A real DNS query from the assigned address, answered with a real datagram.
* TCP 80 and TCP 443 through the tunnel, with a full TLS handshake and a real
  egress address reported by Cloudflare.
* UDP on a port outside the relay set.
* A 1 MB download through the tunnel, about 1 MB/s, with DNS resolved over TCP
  inside the same tunnel.
* The whole thing again through the Mozilla relay: connect-ip over HTTP/2 inside a
  relay TCP flow, then TLS and UDP through the nested netstack, reported as
  `warp=on` by Cloudflare.

## What is not in this release

**The Android app keeps its current transport.** Its core is a separate Kotlin
implementation with a TCP-only userspace stack, so the new tunnel is not wired
into it yet. The Android build in this release has the new icons and the same
relay path it had before. Saying otherwise would be a lie, and this project does
not ship lies in release notes.

**No fake numbers.** The new path reports the real egress address that Cloudflare
returns, and if the tunnel does not come up, the connection fails with the real
error instead of a spinner.

## Changes worth knowing

* `warp.json` appears next to the configuration file and holds the registered
  device. It is created mode 0600. Delete it to force a new registration.
* The WARP switch requires a disconnect first, like the other tunnel settings.
* The console gateway uses the active path automatically: with WARP on, a console
  pointed at the gateway gets the full port range.

## Upgrading

Nothing to do. Existing settings, accounts, servers and the relay path carry over
unchanged. The new path is off until you turn it on in Settings.

---

"""


def badge(label, value, colour, logo, filename):
    query = urlencode({'logo': logo, 'logoColor': 'white'})
    return f'[![{label} {value}](https://img.shields.io/badge/{label}-{value}-{colour}?{query})]({base}{filename})'


def stack(*items):
    return '<br>'.join(items)


version = sys.argv[1]
repo = sys.argv[2] if len(sys.argv) > 2 else 'bardia049b-stack/Gozar1'
android = sys.argv[3] if len(sys.argv) > 3 else version
tag = sys.argv[4] if len(sys.argv) > 4 else f'v{version}'
base = f'https://github.com/{repo}/releases/download/{tag}/'
windows_svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16"><path fill="white" d="M0 0h7v7H0zm9 0h7v7H9zM0 9h7v7H0zm9 0h7v7H9z"/></svg>'
windows_logo = 'data:image/svg+xml;base64,' + base64.b64encode(windows_svg.encode()).decode()

apk = f'Gozar_{android}_'
rows = [
    ('**Android**', stack(
        badge('APK', 'Universal', '1a7f37', 'android', apk + 'universal.apk'),
        badge('APK', 'ARM64', '2da44e', 'android', apk + 'arm64-v8a.apk'),
        badge('APK', 'ARMv7', '2da44e', 'android', apk + 'armeabi-v7a.apk'),
        badge('APK', 'x86__64', '2da44e', 'android', apk + 'x86_64.apk'),
        badge('APK', 'x86', '2da44e', 'android', apk + 'x86.apk'))),
    ('**Windows**', stack(
        badge('Installer', 'EXE__x64', '0550ae', windows_logo, 'Gozar-Windows-x64-Setup.exe'),
        badge('Portable', 'ZIP__x64', '0550ae', windows_logo, 'Gozar-Windows-x64.zip'))),
    ('**Linux**', stack(
        badge('Package', 'DEB__amd64', 'e36209', 'linux', 'Gozar-Linux-amd64.deb'),
        badge('Portable', 'TAR.XZ__amd64', 'e36209', 'linux', 'Gozar-Linux-amd64.tar.xz'))),
    ('**macOS**', stack(
        badge('TAR.XZ', 'Apple__Silicon', '24292f', 'apple', 'Gozar-macOS-ARM.tar.xz'),
        badge('TAR.XZ', 'Intel', '24292f', 'apple', 'Gozar-macOS-Intel.tar.xz'))),
    ('**Separate cores**', stack(
        badge('Core', 'Windows__x64', '4b4b4b', windows_logo, 'Gozar-Core-Windows-x64.zip'),
        badge('Core', 'Windows__ARM64', '4b4b4b', windows_logo, 'Gozar-Core-Windows-arm64.zip'),
        badge('Core', 'Linux__amd64', '4b4b4b', 'linux', 'Gozar-Core-Linux-amd64.tar.xz'),
        badge('Core', 'Linux__ARM64', '4b4b4b', 'linux', 'Gozar-Core-Linux-arm64.tar.xz'),
        badge('Core', 'macOS__Intel', '4b4b4b', 'apple', 'Gozar-Core-macOS-Intel.tar.xz'),
        badge('Core', 'macOS__ARM64', '4b4b4b', 'apple', 'Gozar-Core-macOS-ARM.tar.xz'))),
]

print(SECURITY)
print('| System | Download |')
print('|:--:|:--|')
for name, downloads in rows:
    print(f'| {name} | {downloads} |')
