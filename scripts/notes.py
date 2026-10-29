import sys

version = sys.argv[1]
repo = sys.argv[2] if len(sys.argv) > 2 else 'bardia049b-stack/Gozar'
android = sys.argv[3] if len(sys.argv) > 3 else version
tag = sys.argv[4] if len(sys.argv) > 4 else f'v{version}'
base = f'https://github.com/{repo}/releases/download/{tag}/'

files = [
    ('Android · universal', f'Gozar_{android}_universal.apk'),
    ('Android · ARM64', f'Gozar_{android}_arm64-v8a.apk'),
    ('Android · ARMv7', f'Gozar_{android}_armeabi-v7a.apk'),
    ('Android · x64', f'Gozar_{android}_x86_64.apk'),
    ('Android · x86', f'Gozar_{android}_x86.apk'),
    ('Windows · x64', 'Gozar-Windows-x64.zip'),
    ('macOS · Apple Silicon', 'Gozar-macOS-ARM.tar.xz'),
    ('macOS · Intel', 'Gozar-macOS-Intel.tar.xz'),
    ('Linux · amd64', 'Gozar-Linux-amd64.tar.xz'),
]

print(f'# Gozar Desktop {version}')
print()
print(f'Desktop uses a native Flutter Material 3 interface and a Go core. The Android app remains at {android} and its source is unchanged.')
print('رابط دسکتاپ بومی Flutter و هستهٔ آن Go است؛ نسخه و سورس اندروید تغییری نکرده‌اند.')
print()
print('| Platform | Download |')
print('| --- | --- |')
for label, name in files:
    print(f'| {label} | [{name}]({base}{name}) |')
print()
print('**Experimental:** The desktop tunnel and sign-in with a real Firefox account were not tested live. '
      'IPv6 may bypass the tunnel; UDP other than DNS is not supported. '
      'Do not rely on this build for privacy or as a complete device-wide VPN.')
print()
print('**هشدار:** تونل واقعی و ورود با حساب واقعی تست نشده‌اند. IPv6 ممکن است خارج از تونل برود و '
      'UDP به‌جز DNS پشتیبانی نمی‌شود؛ برای حفظ حریم خصوصی به این نسخه اتکا نکنید.')
print()
print('Desktop features not yet implemented: routing rules, per-app routing, proxy chains, '
      'tunnel-in-tunnel, the route map and automatic updates.')
print('قابلیت‌های مسیریابی، انتخاب برنامه‌ها، زنجیرهٔ پروکسی، تونل‌در‌تونل، نقشه و به‌روزرسانی '
      'خودکار در دسکتاپ هنوز پیاده‌سازی نشده‌اند.')
print()
print('The account password is not saved; access and refresh tokens are stored in a local '
      'configuration file rather than an OS keychain. Mozilla anti-bot challenges may prevent sign-in.')
print()
print('Windows requests administrator privileges at launch. Linux and macOS require administrator '
      'privileges for the tunnel. Keep the extracted application files together. The macOS build is '
      'ad-hoc signed, not notarized; Windows is not Authenticode-signed.')
print()
print('macOS archives can be extracted with tar -xJf ARCHIVE.tar.xz in Terminal. '
      'The 17 MB limit applies to download size, not extracted size.')
print()
print('Every archive and APK has a detached OpenPGP signature; use gozar-public-key.asc to verify it.')
