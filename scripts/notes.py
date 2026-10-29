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

print(f'# Gozar Desktop {version}')
print()
print(f'Firefox-only Gozar for Android {android}, Windows, macOS and Linux.')
print()
print('| System | Download |')
print('|:--:|:--:|')
for name, downloads in rows:
    print(f'| {name} | {downloads} |')
print()
print('### What changed')
print()
print('- Desktop DNS now uses HTTPS through the Firefox proxy, rather than the unsupported DNS port 53.')
print('- Unsupported UDP is refused, allowing compatible browsers to fall back from QUIC to TCP.')
print('- Windows routing commands run without opening console windows.')
print('- Android sign-in keeps its fields visible and locked while a spinner appears inside the button.')
print('- Android language selection remains on sign-in and in Settings, not on the home header.')
print('- Desktop keeps its native Flutter Material 3 interface and Go core; no JVM is bundled.')
print()
print('### Install')
print()
print('- **Android**: install the universal APK or the file named for your device. The APKs contain no native ABI-specific libraries and may be identical.')
print('- **Windows**: extract the ZIP and run `gozar.exe`. Keep all extracted files together. Administrator permission is requested for TUN.')
print('- **macOS**: extract with `tar -xJf ARCHIVE.tar.xz` and open `Gozar.app`. The app is ad-hoc signed, not notarized.')
print('- **Linux**: extract the archive, keep the bundle together, and launch with the permissions needed for TUN.')
print()
print('### Important limitations')
print()
print('The desktop tunnel and real Firefox account login and registration have not been verified live in this build. '
      'Mozilla may present an unsupported challenge or refuse a request. IPv6 can bypass the tunnel, and UDP other than DNS is not supported. '
      'Mozilla also restricts destination ports. Do not rely on this experimental tunnel for privacy or complete device-wide coverage.')
print()
print('Desktop routing rules, per-app routing, proxy chains, tunnel-in-tunnel, the route map and automatic updates are not implemented. '
      'Access and refresh tokens are stored in a local configuration file, not the OS keychain. '
      'The Windows executable is not Authenticode-signed.')
print()
print('### Signatures')
print()
print('Each APK and desktop archive has a separate OpenPGP `.sig` file. Import `gozar-public-key.asc` and verify the downloaded file with GPG. '
      'These detached signatures are not Windows Authenticode or Apple notarization.')
