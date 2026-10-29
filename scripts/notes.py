import sys

version = sys.argv[1]
repo = sys.argv[2] if len(sys.argv) > 2 else "bardia049b-stack/GozarWG"
tag = "v" + version
base = "https://github.com/%s/releases/download/%s/" % (repo, tag)


def badge(label, value, colour, logo, file):
    return "[![%s](https://img.shields.io/badge/%s-%s-%s?logo=%s&logoColor=white)](%s%s)" % (
        label, label, value, colour, logo, base, file)


def stack(*items):
    return "<br>".join(items)


apk = "GozarWG_%s_" % version
rows = [
    ("**Android**", stack(
        badge("APK", "Universal", "1a7f37", "android", apk + "universal.apk"),
        badge("APK", "ARMv8", "2da44e", "android", apk + "arm64-v8a.apk"),
        badge("APK", "ARMv7", "2da44e", "android", apk + "armeabi-v7a.apk"),
        badge("APK", "x86__64", "2da44e", "android", apk + "x86_64.apk"))),
    ("**Windows**", stack(
        badge("MSI", "x64", "0550ae", "windows", "GozarWG-%s-Windows-x64.msi" % version))),
    ("**macOS**", stack(
        badge("DMG", "Intel", "24292f", "apple", "GozarWG-%s-macOS-Intel.dmg" % version),
        badge("DMG", "Apple__Silicon", "24292f", "apple", "GozarWG-%s-macOS-ARM.dmg" % version))),
    ("**Linux**", stack(
        badge("DEB", "amd64", "e36209", "linux", "GozarWG-%s-Linux-amd64.deb" % version))),
]

lines = []
lines.append("GozarWG %s brings the Firefox VPN client to the desktop. The Windows, macOS and Linux "
             "builds use the same screen, the same red control and the same server list as the "
             "Android app." % version)
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
lines.append("- Firefox VPN tab connects through the Mozilla proxy with the same 50 GB monthly pass.")
lines.append("- The system proxy is switched on when the tunnel is up and switched back off on disconnect.")
lines.append("- The WireGuard tab stays Android-only on the desktop for now, because the AmneziaWG core "
             "that tab needs is the Android native library. The desktop builds carry the Firefox VPN "
             "path only and say so on that tab.")
lines.append("")
lines.append("### Install")
lines.append("")
lines.append("- **Windows**: run the `.msi` and accept the prompt from the installer.")
lines.append("- **macOS**: open the `.dmg` and drag GozarWG to Applications. The first proxy change asks "
             "for your password.")
lines.append("- **Linux**: `sudo apt install ./GozarWG-%s-Linux-amd64.deb`." % version)
lines.append("")
lines.append("Every desktop file is built on its own runner and the architecture of the packed runtime "
             "is checked before upload, so the Intel and Apple Silicon images cannot be swapped and the "
             "x64 Windows and amd64 Linux files stay 64-bit only.")
lines.append("")
lines.append("`gozarwg-public-key.asc` verifies the `.sig` file of every asset.")

print("\n".join(lines))
