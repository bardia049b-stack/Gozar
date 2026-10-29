import sys

version = sys.argv[1]
repo = sys.argv[2] if len(sys.argv) > 2 else 'bardia049b-stack/Gozar'
android = sys.argv[3] if len(sys.argv) > 3 else version
base = f'https://github.com/{repo}/releases/download/v{version}/'

files = [
    ('Android · universal', f'Gozar_{android}_universal.apk'),
    ('Android · ARM64', f'Gozar_{android}_arm64-v8a.apk'),
    ('Android · ARMv7', f'Gozar_{android}_armeabi-v7a.apk'),
    ('Android · x64', f'Gozar_{android}_x86_64.apk'),
    ('Windows · x64', 'Gozar-Windows-x64.zip'),
    ('macOS · Apple Silicon', 'Gozar-macOS-ARM.tar.xz'),
    ('macOS · Intel', 'Gozar-macOS-Intel.tar.xz'),
    ('Linux · amd64', 'Gozar-Linux-amd64.tar.xz'),
]

print(f'# Gozar {version}')
print()
print('Desktop uses a native Flutter Material 3 interface and a Go core. The Android app is unchanged.')
print()
print('| Platform | Download |')
print('| --- | --- |')
for label, name in files:
    print(f'| {label} | [{name}]({base}{name}) |')
print()
print('The desktop tunnel is experimental and has not been verified in a live connection in this revision. '
      'IPv6 is not routed through the tunnel and may bypass it; UDP other than DNS is not supported. '
      'Do not rely on it for privacy or as a complete device-wide VPN.')
print()
print('Windows requests administrator privileges at launch. Linux and macOS require administrator '
      'privileges for the tunnel. Keep the extracted application files together. The macOS build is '
      'ad-hoc signed, not notarized; Windows is not Authenticode-signed.')
print()
print('macOS archives can be extracted with tar -xJf ARCHIVE.tar.xz in Terminal.')
print()
print('Every archive and APK has a detached OpenPGP signature; use gozar-public-key.asc to verify it.')
