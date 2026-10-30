import sys

version = sys.argv[1]
repo = sys.argv[2] if len(sys.argv) > 2 else 'bardia049b-stack/Gozar'
android = sys.argv[3] if len(sys.argv) > 3 else version
tag = sys.argv[4] if len(sys.argv) > 4 else f'v{version}'
base = f'https://github.com/{repo}/releases/download/{tag}/'


def badge(label, value, colour, logo, filename):
    return f'[![{label} {value}](https://img.shields.io/badge/{label}-{value}-{colour}?logo={logo}&logoColor=white)]({base}{filename})'


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
    ('**Windows**', badge('ZIP', 'x64', '0550ae', 'windows', 'Gozar-Windows-x64.zip')),
    ('**macOS**', stack(
        badge('TAR.XZ', 'Apple__Silicon', '24292f', 'apple', 'Gozar-macOS-ARM.tar.xz'),
        badge('TAR.XZ', 'Intel', '24292f', 'apple', 'Gozar-macOS-Intel.tar.xz'))),
    ('**Linux**', badge('TAR.XZ', 'amd64', 'e36209', 'linux', 'Gozar-Linux-amd64.tar.xz')),
]

print(f'# Gozar Android {android} / Desktop {version}')
print()
print('Firefox-only Gozar for Android, Windows, macOS and Linux.')
print()
print('| System | Download |')
print('|:--:|:--:|')
for name, downloads in rows:
    print(f'| {name} | {downloads} |')
print()
print('### Android: certificate error on some Android 13 devices')
print()
print('Some devices may report `Trust anchor for certification path not found` when connecting to a Firefox VPN server. '
      'A likely cause is that an older Android system certificate store does not contain the GlobalSign root used by the server. '
      'A public Fastly VPN server was observed using GlobalSign Root E46; device and server certificate chains can vary.')
print()
print('This release adds the verified GlobalSign E46 and R46 roots alongside Android system roots, '
      'only for `fastly-masque.net` and its subdomains. Certificate-chain and HTTPS hostname verification remain enabled. '
      'TLS failures during the connection probe are shown as errors instead of being reported as a successful connection. '
      'The Android HTTP/2 tunnel also retains data when a frame is larger than a read buffer, and the previous diagnostic and crash logging has been removed.')
print()
print('**This is an attempted fix, not a confirmed solution.** It was checked with public certificate chains and local tunnel simulations, '
      'but not on the affected physical Android 13 device or with a real Firefox account. '
      'Another certificate chain, an incorrect device date, network interception, or a future VPN server on a different base domain could still cause the error. '
      'The added roots do not apply to unrelated domains.')
print()
print('If the problem continues, check the device date and time, try another network or server, and report the Android version, device model and exact error. '
      'Please do not share passwords, access tokens or screenshots containing credentials.')
print()
print('### Windows: Wi-Fi icon may say “No Internet”')
print()
print('While Gozar is connected, Windows may display “No Internet” on the Wi-Fi or network icon even when websites continue to work. '
      'Windows determines that icon using its Network Connectivity Status Indicator (NCSI) probes. '
      'With virtual VPN interfaces such as Wintun, routing, DNS or probe settings can cause the indicator to disagree with actual connectivity. '
      'This can be a cosmetic status-detection behavior and does not, by itself, mean your internet connection is down.')
print()
print('Open a website or test an application to check the connection. If traffic also fails, there may be a genuine network or VPN problem. '
      'This release does not change Windows routing and does not claim to fix the icon.')
print()
print('### Other information')
print()
print('- Desktop DNS uses HTTPS through the Firefox proxy; unsupported non-DNS UDP is refused so compatible apps can fall back to TCP.')
print('- Windows routing commands run without opening console windows. The desktop interface uses Flutter and a Go core, without a JVM.')
print('- Android sign-in keeps its fields visible and locked while a spinner appears inside the button. Language selection remains on sign-in and in Settings.')
print()
print('### Install')
print()
print('- **Android**: install the universal APK or the file named for your device. The APKs contain no ABI-specific native libraries and may be identical.')
print('- **Windows**: extract the ZIP and run `gozar.exe`. Keep all extracted files together. Administrator permission is requested for TUN.')
print('- **macOS**: extract with `tar -xJf ARCHIVE.tar.xz` and open `Gozar.app`. The app is ad-hoc signed, not notarized.')
print('- **Linux**: extract the archive, keep the bundle together, and launch with the permissions needed for TUN.')
print()
print('### Important limitations')
print()
print('The desktop tunnel has not been independently verified on a live connection in this build. '
      'Mozilla may present an unsupported authentication challenge or refuse a request. '
      'IPv6 can bypass the tunnel, and UDP other than DNS is not supported. Mozilla also restricts destination ports. '
      'Do not rely on this experimental tunnel for privacy or complete device-wide coverage.')
print()
print('Desktop routing rules, per-app routing, proxy chains, tunnel-in-tunnel, the route map and automatic updates are not implemented. '
      'Access and refresh tokens are stored in a local configuration file, not the OS keychain. '
      'The Windows executable is not Authenticode-signed.')
print()
print('### Signatures')
print()
print('Each APK and desktop archive has a separate OpenPGP `.sig` file. Import `gozar-public-key.asc` and verify the downloaded file with GPG. '
      'These detached signatures are not Windows Authenticode or Apple notarization.')
