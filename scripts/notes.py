import base64
import sys
from urllib.parse import urlencode

SECURITY = r"""# Gozar 1.10.6 — UDP that games can actually use, on every platform

This release fixes the reason online games kept failing. Gozar now decides, per
datagram, whether traffic should go through Cloudflare WARP, stay direct, or
spend your Mozilla quota — and it does the same on Android, Windows, Linux and
macOS.

## Three UDP modes

A new **UDP Mode** section sits in Settings → Connection, under a single
**Console / Game Mode** master switch.

- **Off (default).** Mozilla TCP only. Lowest data and battery use. Nothing
  changes for ordinary browsing.
- **Normal.** Web and video calls use the Mozilla relay; game traffic uses WARP.
  This spends the least WARP quota while still opening your NAT.
- **Game Mode.** Every UDP port goes through WARP, including ports no relay
  would carry. This gives the best NAT type and ping on Xbox and PS5, and uses
  more WARP data.
- **Mozilla only (emergency).** WARP stays closed and UDP uses just Mozilla's
  allowed ports. Use this when WARP itself is blocked. Browsing and calls keep
  working; online games may not.

## Game, browse and keep your Firefox quota

The three paths run at the same time. A console can play on WARP while a browser
on the same machine uses the Mozilla relay, and Iranian sites still connect
directly instead of taking a round trip abroad. On Android this lives in the VPN
service's dial path; on the desktop it lives in the engine, with a side WARP
tunnel that is only created when the first game datagram needs it.

## A warning you will actually see

If Console / Game Mode is on but **UDP Relay** is off, a yellow warning appears
in Settings with a one-tap fix: *"To make online games work, you also need to
turn on UDP Relay."* It is there in both Persian and English, on Android and on
the desktop.

## Battery

Game Mode keeps two tunnels alive, so it costs more battery — the settings
screen says so plainly. An optional **Battery saver** keeps WARP only for game
ports even when Game Mode is selected. On Android the tunnel runs in a foreground
service, as before.

## Sign-ins no longer risk an IP ban

Mozilla account calls are now serialised and backed off. On HTTP 429 or 503
Gozar waits, honours `Retry-After` in both seconds and HTTP-date form, and retries
with an exponential delay capped at one minute. If Mozilla still refuses, you
get a message telling you how long to wait instead of a silent failure.

## Staying connected

WARP is watched, not assumed. If the tunnel dies — a Wi-Fi to mobile-data switch,
a filtered network, a suspended laptop — Gozar rebuilds it in the background with
a 1s, 2s, 4s … backoff capped at 30 seconds, and falls back to the Mozilla relay
in the meantime. A WARP failure never crashes or hangs the app.

## A bug that dropped small game packets

The Android packet parser required a UDP length of at least 20 bytes. The UDP
header is 8 bytes, so small datagrams — exactly the kind games send — were
silently discarded. Fixed, with a regression test.

## New look

Every icon is now pure Material 3 on one fixed Material red, with no coloured
backgrounds and a variety of Material frames instead of one repeated shape. The
launcher icon is a white **G** on red.

## Verified, not just written

- **Live UDP against the production Cloudflare API:** device registered, QUIC
  tunnel up, DNS over UDP 53 answered in 8 ms (61 bytes, 2 answers), datagrams
  sent on UDP 3074 and UDP 443, and Cloudflare's own trace endpoint reported
  `warp=on` from the tunnel's egress address.
- **33 Kotlin unit tests** and **Go tests** covering routing for all three modes,
  config save/load, the small-packet fix, datagram replies, and the 429 backoff.
- **14 Flutter tests** covering state parsing and the UDP Relay warning.
- Android APKs built; `go build`, `go vet` and `flutter analyze` all clean.
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
