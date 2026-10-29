import sys

version = sys.argv[1]
repo = sys.argv[2] if len(sys.argv) > 2 else "bardia049b-stack/Gozar"
tag = "v" + version
base = "https://github.com/%s/releases/download/%s/" % (repo, tag)


def badge(label, value, colour, logo, file):
    return "[![%s](https://img.shields.io/badge/%s-%s-%s?logo=%s&logoColor=white)](%s%s)" % (
        label, label, value, colour, logo, base, file)


def stack(*items):
    return "<br>".join(items)


apk = "Gozar_%s_" % version
rows = [
    ("**Android**", stack(
        badge("APK", "Universal", "1a7f37", "android", apk + "universal.apk"),
        badge("APK", "ARMv8", "2da44e", "android", apk + "arm64-v8a.apk"),
        badge("APK", "ARMv7", "2da44e", "android", apk + "armeabi-v7a.apk"),
        badge("APK", "x86__64", "2da44e", "android", apk + "x86_64.apk"))),
    ("**Windows**", stack(
        badge("MSI", "x64", "0550ae", "windows", "Gozar-%s-Windows-x64.msi" % version))),
    ("**macOS**", stack(
        badge("DMG", "Intel", "24292f", "apple", "Gozar-%s-macOS-Intel.dmg" % version),
        badge("DMG", "Apple__Silicon", "24292f", "apple", "Gozar-%s-macOS-ARM.dmg" % version))),
    ("**Linux**", stack(
        badge("DEB", "amd64", "e36209", "linux", "Gozar-%s-Linux-amd64.deb" % version))),
]

lines = []
lines.append("Gozar %s is the Firefox VPN client: one Firefox-only screen on Android, Windows, macOS "
             "and Linux." % version)
lines.append("")
lines.append("| System | Download |")
lines.append("|:--:|:--:|")
for name, cell in rows:
    lines.append("| %s | %s |" % (name, cell))
lines.append("")
lines.append("### What changed")
lines.append("")
lines.append("- Desktop builds for Windows (x64), macOS (Intel and Apple Silicon) and Linux (amd64).")
lines.append("- The log screen was removed from every platform.")
lines.append("- The Firefox tunnel uses the Mozilla proxy with the same 50 GB monthly pass.")
lines.append("- The system proxy is switched on when the tunnel is up and switched back off on disconnect.")
lines.append("- WireGuard and AmneziaWG were removed from every platform; Gozar is a Firefox client only.")
lines.append("- First launch opens a sign-in screen with email and password, Google and Apple shortcuts and a "
             "create-account switch.")
lines.append("- Settings shows the signed-in account, monthly usage and a sign-out button.")
lines.append("- The package name, the window title, the installer names and the artifacts are Gozar now.")
lines.append("- The app has a new launcher icon on every platform.")
lines.append("")
lines.append("### Install")
lines.append("")
lines.append("- **Windows**: run the `.msi` and accept the prompt from the installer.")
lines.append("- **macOS**: open the `.dmg` and drag Gozar to Applications. The first proxy change asks "
             "for your password.")
lines.append("- **Linux**: `sudo apt install ./Gozar-%s-Linux-amd64.deb`." % version)
lines.append("")
lines.append("Every desktop file is built on its own runner and the architecture of the packed runtime "
             "is checked before upload, so the Intel and Apple Silicon images cannot be swapped and the "
             "x64 Windows and amd64 Linux files stay 64-bit only.")
lines.append("")
lines.append("`gozar-public-key.asc` verifies the `.sig` file of every asset.")

print("\n".join(lines))
