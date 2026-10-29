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
        badge("EXE", "x64", "0550ae", "windows", "Gozar-%s-Windows-x64.exe" % version))),
    ("**macOS**", stack(
        badge("Binary", "Intel", "24292f", "apple", "Gozar-%s-macOS-Intel" % version),
        badge("Binary", "Apple__Silicon", "24292f", "apple", "Gozar-%s-macOS-ARM" % version))),
    ("**Linux**", stack(
        badge("Binary", "amd64", "e36209", "linux", "Gozar-%s-Linux-amd64" % version),
        badge("Binary", "arm64", "e36209", "linux", "Gozar-%s-Linux-arm64" % version))),
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
lines.append("- The desktop apps are rewritten in Go: one self-contained binary of about 9 MB for "
             "Windows, macOS and Linux, with no Java and no runtime to install.")
lines.append("- The tunnel is now a real kernel tunnel: a network adapter carries every packet, the "
             "Go stack answers TCP, UDP and DNS inside the app, and only the tunnel's own traffic "
             "goes out directly.")
lines.append("- A simple window ships with the binary: sign in, choose a server, connect, and watch "
             "the traffic counters.")
lines.append("- A system proxy mode remains for machines without administrator rights, and the proxy "
             "is always removed on exit.")
lines.append("- The MSI, DMG and DEB packages and the Kotlin desktop code were removed.")
lines.append("")
lines.append("### Install")
lines.append("")
lines.append("- **Windows**: run `Gozar-Windows-x64.exe` as administrator for the tunnel. The window "
             "opens in your browser and the tunnel driver travels inside the file.")
lines.append("- **macOS**: `chmod +x Gozar-macOS-ARM`, run it, and allow the network change prompt.")
lines.append("- **Linux**: `chmod +x Gozar-Linux-amd64`, then `sudo ./Gozar-Linux-amd64` for the tunnel.")
lines.append("- **Proxy mode** works without administrator rights on every platform.")
lines.append("")
lines.append("Every desktop file is a single self-contained binary built on its own runner, and the "
             "tunnel driver is embedded, so nothing else has to be installed.")
lines.append("")
lines.append("`gozar-public-key.asc` verifies the `.sig` file of every asset.")

print("\n".join(lines))
