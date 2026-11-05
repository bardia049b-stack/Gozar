import base64
import sys
from urllib.parse import urlencode

SECURITY = r"""## Gozar 1.10.4 — Android parity, lower data usage, real kill switch

Android now ships the Go core. `gomobile bind` builds `gozar-core.aar` for arm64-v8a, armeabi-v7a, x86 and x86_64, and the gVisor stack attaches straight to the descriptor from `VpnService.establish()`. Every socket the core opens is excluded from the VPN through `VpnService.protect()`, so the tunnel cannot swallow its own upstream. **Game Mode** and a **UDP relay** switch turn on the datagram path, which means Xbox, PlayStation, WebRTC and STUN ports finally traverse the tunnel on a phone. All six advanced transports (DoQ, ECH, TFO, MPTCP, WSS, domain fronting) are now reachable from Android settings with the same validation and fallbacks as desktop. A plain TCP session still uses the JVM stack, so the default behaviour is unchanged.

Data usage is measured rather than estimated. A CI test pushes a real 10 MB download through the userspace stack, TLS and an HTTP/2 CONNECT proxy and counts bytes on the socket: **10,540,066 bytes for a 10 MiB payload (1.005x)**, against a 12 MB budget. With fragmentation, padding and jitter enabled it is 10,540,155 bytes. The engine now exports a retransmission counter, and the Linux `data-budget` job enforces the 5 % ceiling (measured **0.01 %**). The harness peers two userspace stacks, so on the Windows runner the synthetic peer acks slowly and inflates that inner counter while the real socket stays at 1.005x; the number is logged there rather than treated as a product regression. The DNS cache gained a 60 s floor, a 900 s ceiling and negative caching for NXDOMAIN and empty answers, single-flight collapses parallel identical queries into one upstream lookup, and keep-alive tuning is wired to stretch the proxy keep-alive from 30 s to 180 s and the idle probe from 30 s to 120 s.

The security features that existed but were never called are now on the traffic path. `FragmentConn` splits the ClientHello, padding travels as an `X-Padding` header on the CONNECT request on both the HTTP/1.1 and HTTP/2 write paths, and jitter delays that write. A toxiproxy job runs the obfuscated tunnel through slicer, latency and bandwidth toxics and round-trips 120 KB intact.

Linux and macOS get a real kill switch. Linux uses an nftables table with a drop policy that still allows the tunnel interface, the client cgroup, the pinned proxy addresses and DHCP. macOS loads into a dedicated `gozar` **anchor** and preserves the user's existing ruleset — the previous code would have replaced the whole system firewall. A CI job proves the full cycle in a network namespace: reachable, apply, **cut**, `SIGKILL` the holder, **still cut**, reap, **restored**.

`bypass.Validate()` no longer returns nil unconditionally. It rejects ECH with domain fronting, fronting on non-standard ports, WSS with HTTP/2 multiplexing, TFO with MPTCP, ECH inside WSS, and platform-impossible options, each with a reason. Android validates through the same code and rolls the toggles back on a bad set.

Two new live tests: CI registers a real WARP identity with Cloudflare and sends a DNS query **through** a MASQUE tunnel to `162.159.198.2:443`, and a drop/reconnect test cuts the path and requires the tunnel to recover. There is **no 24-hour soak** in this version. Real PS5/Xbox hardware and a macOS `pf` load on Apple hardware were **not** tested; see the table in the release notes for the full honest list.
"""


def badge(label, value, colour, logo, filename, prefix=None):
    query = urlencode({'logo': logo, 'logoColor': 'white'})
    path = f'{label}-{value}-{colour}'.replace(' ', '%20')
    target = (base if prefix is None else prefix) + filename
    shown = value.replace('__', '_')
    return f'[![{label} {shown}](https://img.shields.io/badge/{path}?{query})]({target})'


def stack(*items):
    return '<br>'.join(items)


version = sys.argv[1]
repo = sys.argv[2] if len(sys.argv) > 2 else 'bardia049b-stack/Gozar'
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
    ('**Flatpak**', stack(
        badge('Flatpak', 'x86__64', '4a90d9', 'flatpak', f'Gozar-{version}-x86_64.flatpak'))),
    ('**Snap**', stack(
        badge('Snap', 'amd64', '82bea0', 'snapcraft', f'Gozar_{version}_amd64.snap'))),
    ('**AppImage**', stack(
        badge('AppImage', 'x86__64', '8e44ad', 'appimage', f'Gozar-{version}-x86_64.AppImage'))),
    ('**Fedora**', stack(
        badge('RPM', 'x86__64', '51a2da', 'fedora', 'Gozar-Fedora-x86_64.rpm'))),
    ('**RHEL**', stack(
        badge('RPM', 'x86__64', 'ee0000', 'redhat', 'Gozar-RHEL-x86_64.rpm'))),
    ('**Arch Linux**', stack(
        badge('Pkg', 'x86__64', '1793d1', 'archlinux', f'Gozar-{version}-x86_64.pkg.tar.zst'))),
    ('**AUR**', stack(
        badge('AUR', 'PKGBUILD', '2D5A8C', 'archlinux', '',
              prefix=f'https://github.com/{repo}/tree/main/packaging/aur'))),
    ('**OpenWrt**', stack(
        badge('IPK', 'mips', '00a3e0', 'openwrt', 'Gozar-OpenWrt-mips_24kc.ipk'),
        badge('IPK', 'mipsel', '00a3e0', 'openwrt', 'Gozar-OpenWrt-mipsel_24kc.ipk'),
        badge('IPK', 'armv7', '00a3e0', 'openwrt', 'Gozar-OpenWrt-armv7.ipk'),
        badge('IPK', 'aarch64', '00a3e0', 'openwrt', 'Gozar-OpenWrt-aarch64.ipk'),
        badge('LuCI', 'all', '0074a6', 'openwrt', 'LuCI-Gozar-all.ipk'))),
    ('**Raspberry Pi**', stack(
        badge('Raspberry Pi', '64__ARM64', 'c51a4a', 'raspberrypi', 'Gozar-RaspberryPi-arm64.tar.xz'),
        badge('Raspberry Pi', '32__ARMv7', 'c51a4a', 'raspberrypi', 'Gozar-RaspberryPi-armv7.tar.xz'))),
    ('**FreeBSD**', stack(
        badge('PKG', 'amd64', 'ab2b28', 'freebsd', 'Gozar-FreeBSD-amd64.pkg'),
        badge('PKG', 'arm64', 'ab2b28', 'freebsd', 'Gozar-FreeBSD-arm64.pkg'))),
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
