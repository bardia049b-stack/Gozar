import base64
import sys
from urllib.parse import urlencode

version = sys.argv[1]
repo = sys.argv[2] if len(sys.argv) > 2 else 'bardia049b-stack/Gozar1'
android = sys.argv[3] if len(sys.argv) > 3 else version
tag = sys.argv[4] if len(sys.argv) > 4 else f'v{version}'
base = f'https://github.com/{repo}/releases/download/{tag}/'
windows_svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16"><path fill="white" d="M0 0h7v7H0zm9 0h7v7H9zM0 9h7v7H0zm9 0h7v7H9z"/></svg>'
windows_logo = 'data:image/svg+xml;base64,' + base64.b64encode(windows_svg.encode()).decode()


def badge(label, value, colour, logo, filename):
    query = urlencode({'logo': logo, 'logoColor': 'white'})
    return f'[![{label} {value}](https://img.shields.io/badge/{label}-{value}-{colour}?{query})]({base}{filename})'


def stack(*items):
    return '<br>'.join(items)


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

print(f'# Gozar Android {android} / Desktop {version}')
print()
print('Firefox-only Gozar for Android, Windows, macOS and Linux. Earlier releases remain available.')
print()
print('| System | Download |')
print('|:--:|:--|')
for name, downloads in rows:
    print(f'| {name} | {downloads} |')
print()
print('The Windows EXE and Linux DEB include the app, native core, libraries and licenses. '
      'Portable archives are also available. Separate core downloads are native developer components, not graphical apps. '
      'Desktop downloads are individually limited to under 17 MB and do not include a JVM.')
print()
print('## Adaptive tunneling')
print()
print('- **Android:** Gozar chooses between HTTPS CONNECT over HTTP/2 and HTTP/1.1 for new connections, '
      'using endpoint-specific connection timing and temporary failure history. It periodically samples the alternative and keeps certificate and hostname verification enabled.')
print('- **Desktop:** the core reuses a small pool of HTTP/2 connections and can try HTTP/3 CONNECT when the selected endpoint and network actually accept it. '
      'It falls back to HTTP/2 or HTTP/1.1 when appropriate. A configured TCP proxy chain is never bypassed to try HTTP/3. '
      'The selector learns from new connection attempts; it does not move existing connections or guarantee the fastest path.')
print('- HTTP/3 was exercised against a local simulated proxy, including session reuse and rejection of an untrusted certificate. '
      'Support on a particular Firefox endpoint or network has not been established. HTTP/3 to a proxy does **not** mean arbitrary destination UDP is supported.')
print()
print('## Reliability fixes')
print()
print('- Fixed an Android TCP upload path that could acknowledge data without retaining it under backpressure. '
      'The tunnel now bounds its queue, reopens the receive window as space becomes available, and closes a connection that completes after its flow was cancelled.')
print('- Updated Android DNS handling to allow concurrent lookups, honor bounded response TTLs, avoid caching failed answers as successful results, '
      'and recover after failures instead of remaining latched. Large UDP answers are marked truncated so clients can retry over TCP; TCP DNS requests use the same DNS-over-HTTPS path and teach the tunnel domain routing from the answer. A specific route for the in-tunnel DNS address also takes precedence over an optional LAN exclusion.')
print('- Improved HTTP/2 handling of padded frames, data frames larger than the caller’s read buffer, long authorization headers, flow-control windows, write errors, and TCP half-close. '
      'Fallback does not disable TLS verification.')
print('- Fixed native TUN packet headroom for modern Linux TUN offload and macOS utun, released incoming packet buffers correctly, '
      'and made device read/write failures stop the tunnel rather than silently dropping packets. Linux proxy bypass follows the actual route selected for the upstream, including direct and non-default-interface routes; cleanup removes only Gozar-created bypass routes and leaves newer DNS changes intact. On Windows, tunnel startup waits until the adapter is up and its address is preferred before directing traffic to it; default-route selection uses the lowest available metric.')
print('- Android and desktop now schedule proxy-pass renewal before the advertised expiry, retry temporary failures, '
      'and stop the renewal task on disconnect. New desktop connections read the current pass instead of a token captured at connection time. '
      'Account and connection generation checks prevent a cancelled renewal from replacing a newer session.')
print()
print('## Android root routing — experimental and opt-in')
print()
print('A new Settings option requests superuser permission using `su` and checks for UID 0. '
      'Only after explicit consent does Gozar attempt extra routing for IPv4 TCP and DNS from root-owned processes while the normal VPN is connected. '
      'It attempts to preserve protected/explicitly selected sockets and LAN bypass, uses source NAT for the tunnel, '
      'and removes its own rules on normal disconnect or partial setup failure. A process watchdog attempts cleanup after an app crash.')
print()
print('**Important:** no rooted Android device was available for physical verification. '
      'Firewall implementations, policy marks, SELinux, NAT and crash cleanup vary by device; the option may fail or affect connectivity. '
      'Non-DNS UDP, IPv6 and some kernel-originated traffic may remain outside the tunnel. Per-app selection does not define the UID 0 policy. '
      'Do not rely on this experimental option for leak protection or assume it covers all device traffic.')
print()
print('## Android interface and Android 13')
print()
print('- The per-app sheet appears immediately with a loading indicator while installed and system applications are enumerated. '
      'It can be dismissed during loading; filtering and selection remain available after loading.')
print('- The account switcher now presents the active account, saved alternatives and an add-account action in a clearer sheet. '
      'Switching or adding an account disconnects the active VPN first; signing out returns to sign-in.')
print('- The Android 13 certificate-chain fix from the preceding release remains in place. '
      'The user reports that their friend tested that earlier fix on the affected Android 13 phone and successfully connected. '
      'This release has not separately been verified on that phone; certificate and hostname checks remain enabled.')
print()
print('## Desktop and platform limits')
print()
print('The Windows installer installs per user and offers an optional desktop shortcut. The VPN app requests administrator approval '
      'for its TUN device. The EXE is not Authenticode-signed. macOS builds are ad-hoc signed and not notarized. '
      'Linux TUN setup needs suitable privileges. Real Linux TUN, Windows Wintun and macOS utun devices were exercised with local mock upstream and DNS services. The tests covered TCP uploads, half-close, UDP/TCP DNS and shutdown. They do not verify connectivity to a real Firefox server or production account.')
print()
print('Desktop per-app and per-domain routing, a dedicated LAN-bypass control and a route map are not implemented. '
      'The existing proxy-chain editor accepts unauthenticated SOCKS5 and HTTP CONNECT hops; changes require disconnection. '
      'IPv6 and general non-DNS UDP coverage are not established. No leak-free, universal-coverage, '
      'or network-speed guarantee is made. Windows interface “No Internet” indicators can reflect Windows connectivity checks '
      'or a real connectivity problem and should be investigated separately.')
print()
print('## Verification and signing')
print()
print('Automated unit tests and local simulations cover the changed tunnel, resolver, selector, root command planning and interface paths; '
      'they do not replace testing with real accounts and networks. Each published file is accompanied by a detached OpenPGP signature and '
      '`gozar-public-key.asc`. The Android release APKs must be signed with the existing official Android key to upgrade an existing install. '
      'Do not substitute a debug or newly generated key as an official upgrade.')
