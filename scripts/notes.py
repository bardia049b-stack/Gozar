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
print('Firefox-only Gozar for Android, Windows, macOS and Linux. This release keeps the earlier releases available.')
print()
print('| System | Download |')
print('|:--:|:--:|')
for name, downloads in rows:
    print(f'| {name} | {downloads} |')
print()
print('## Android 13: Firefox VPN certificate connection error')
print()
print('Some Android 13 devices reported `java.security.cert.CertPathValidatorException: Trust anchor for certification path not found` when connecting. '
      'A public Fastly Firefox VPN endpoint was observed presenting a chain to GlobalSign Root E46; the relevant newer roots are absent from the AOSP Android 13 root set. '
      'Different Android vendors and server chains can behave differently.')
print()
print('Gozar adds the verified GlobalSign E46 and R46 roots alongside the system roots **only for `fastly-masque.net` and its subdomains**. '
      'Certificate-chain validation and hostname verification stay enabled. A TLS failure during the connection probe is reported as a failure rather than a successful connection. '
      'The HTTP/2 tunnel also preserves data when a frame is larger than the caller’s read buffer.')
print()
print('**Physical-device confirmation:** the user reports that their friend tested the preceding certificate fix on the previously affected Android 13 phone '
      'and successfully connected. That confirms the reported case on that device; this new release itself has not been independently tested on that phone. '
      'Other trust stores, server domains, incorrect device time, or network interception can still cause certificate errors.')
print()
print('The certificate scope, hostname verification and tunnel handling were additionally checked with local tests and simulations. '
      'If a certificate error persists, check the date and time, try another network or server, and report the Android version and exact error. '
      'Never share passwords, access tokens, or screenshots containing credentials.')
print()
print('## Accounts and sign-in')
print()
print('- Add another Firefox account from **Settings → Manage accounts**, keep multiple sessions, and switch between them inside Gozar. '
      'Adding or switching accounts disconnects an active VPN first; cancelling sign-in leaves the previous session intact.')
print('- Signing out stops the VPN and removes only the active session. Other saved Gozar sessions remain available from the sign-in screen. '
      'Session tokens and quota information are kept separate; passwords are not saved.')
print('- Android’s email icon opens the **system account chooser** and accepts the selected name only if it is an email address. '
      'It does not perform Google Sign-In or request contacts/account-list permission. The chooser can include non-email system accounts; '
      'those cannot be used to fill the email field. Manual entry is always available.')
print('- Linux can show email-like identities from GNOME Online Accounts when that service is available, after an explicit click. '
      'This is separate from the saved Gozar sessions. Windows and macOS do not have a verified universal system-email picker in this build; '
      'manual entry remains available. Not every Linux desktop provides GNOME Online Accounts.')
print('- Android keeps the email and password visible but locked while signing in, with only a spinner in the button. '
      'Language selection is on the sign-in screen and in Settings.')
print()
print('## Android interface')
print()
print('Persian and right-to-left sign-in and account/quota presentation have been adjusted, the Persian application name is گذر, '
      'and settings press feedback follows the shape of its row. The app-selection sheet uses a nested-scrolling list that includes installed and system applications. '
      'The VPN builder now uses either an allow-list or an exclude-self list, not both. A selection with no valid installed apps is rejected.')
print()
print('Direct-host matching is applied to ordinary TUN TCP flows as well as the local proxy, using the DNS-to-address information available to the app. '
      'Domain-to-IP associations, shared IP addresses, DNS changes, unsupported UDP, and platform routing can limit this feature; '
      'it is not a guarantee that all traffic for a domain follows the intended rule. Android 13 and newer can also exclude LAN ranges at the VPN route level.')
print()
print('## Desktop settings')
print()
print('The Flutter Material 3 interface now exposes functional language and light/dark/system appearance choices, Firefox account switching and sign-out, '
      'a remembered last-connected server preference, a manual GitHub release check, an about/source link, and TON support. '
      'The ordered proxy-chain editor supports unauthenticated SOCKS5 and HTTP CONNECT hops; the Go tunnel uses the chain when it connects. '
      'A local proxy can be used as the first hop for a tunnel-in-tunnel configuration when that proxy is already running.')
print()
print('Desktop per-app and per-domain routing, a dedicated LAN-bypass control, and a route map are **not implemented** in this build. '
      'The settings screen says so rather than pretending to apply a rule. Existing operating-system LAN routes can still bypass the tunnel. '
      'Proxy authentication is not supported. Changes to a proxy chain require disconnection.')
print()
print('## Planned desktop improvements — not in this release')
print()
print('The items below describe intended future work, not features available today or a promise of a delivery date. '
      'Each needs platform-specific implementation and testing; some may be limited by the operating system, providers, or Firefox VPN service.')
print()
print('### Per-app selection and split tunneling')
print()
print('We intend to investigate application-based routing on Windows and Linux first: a searchable list of installed and system programs, '
      'followed by a way to select which programs use or bypass the tunnel. A real implementation must associate network flows with the correct program, '
      'route explicitly excluded traffic through the physical interface, and keep the VPN core’s own connections from looping back into its tunnel. '
      'Existing connections, permissions, multiple processes, DNS, UDP and unexpected shutdowns all need careful handling. '
      'macOS requires separate feasibility work and may have different capabilities. Until implemented, there is no per-app bypass in desktop Gozar.')
print()
print('### Domain rules, LAN bypass and route visibility')
print()
print('We also intend to investigate hostname/domain rules, an explicit LAN-bypass preference and a route view resembling the Android settings. '
      'A domain can resolve to several changing or shared IP addresses, so DNS-to-address matching alone cannot guarantee perfect routing. '
      'A future LAN bypass must distinguish intended local traffic from public traffic, show what is excluded, '
      'and clean up operating-system routes on disconnect and errors. LAN access can expose traffic to the local network; it should be an explicit choice. '
      'None of these desktop controls is available in this release.')
print()
print('### Desktop system-email choices and account security')
print()
print('We plan to investigate provider-supported system-account selection on Windows and macOS while retaining manual email entry. '
      'Operating systems and identity providers do not offer one unrestricted universal email list, so availability and consent may vary. '
      'Such a picker must remain separate from Gozar’s saved account switcher and must not read system passwords. '
      'We also intend to evaluate OS-backed credential storage and migration of existing sessions; currently desktop tokens remain in a local file and are not encrypted by Gozar.')
print()
print('### Connectivity, traffic coverage and polish')
print()
print('Further work may investigate Windows NCSI reporting, safer recovery of routes and DNS after interruptions, '
      'and clearer connection diagnostics. IPv6 and UDP coverage require separate research and depend on the upstream Firefox proxy, '
      'which carries TCP and restricts destination ports; future support for arbitrary UDP or leak-free device-wide tunneling is not promised. '
      'The settings interface and route visualization can be refined alongside those underlying capabilities. '
      'As requested, no live desktop TUN connection was tested by the development agent for this release.')
print()
print('## Windows: Wi-Fi icon may say “No Internet”')
print()
print('Windows uses Network Connectivity Status Indicator (NCSI) probes to decide what its network icon displays. '
      'With a Wintun VPN interface, interface-specific routing, DNS, proxy or probe settings can make that indicator disagree with actual connectivity. '
      'If Gozar is connected and websites work, the label may be cosmetic; it does not on its own prove an outage. '
      'If websites and applications also fail, treat that as a real connection problem rather than dismissing it as an icon issue. '
      'This release does not claim to fix Windows NCSI reporting or change its policies.')
print()
print('NCSI uses configured connectivity probes and the routing and DNS available to each interface. '
      'Gozar’s Wintun interface and the more-specific IPv4 routes used for the VPN can affect which interface handles a probe; '
      'this is a plausible explanation, not a confirmed diagnosis of any particular Windows installation. '
      'The label can also be affected by local policy, captive portals, DNS, proxies, firewalls or blocked probe endpoints. '
      'Gozar does not change NCSI registry or policy settings to hide the warning.')
print()
print('To distinguish an indicator issue from a real outage, check whether several websites actually load while Gozar says it is connected. '
      'If they do not, disconnect Gozar and check the same sites again, try another server or network, and review Windows proxy settings. '
      'When asking for help, include the Windows version, whether sites work with the VPN disconnected, and any non-sensitive error text; '
      'do not post passwords, tokens, or screenshots containing credentials.')
print()
print('Windows routing commands run without opening console windows. Gozar does not set a system proxy. '
      'If an older version left one behind, review your proxy settings manually rather than deleting a proxy whose ownership is unknown.')
print()
print('## Install')
print()
print('- **Android:** install the universal APK or the file named for your device. The APKs contain no ABI-specific native libraries and may be identical.')
print('- **Windows:** extract the ZIP and run `gozar.exe`, keeping the extracted files together. TUN requires administrator permission.')
print('- **macOS:** extract with `tar -xJf ARCHIVE.tar.xz` and open `Gozar.app`. Choose the Intel or Apple Silicon archive for your Mac; each contains only its own architecture. The app is ad-hoc signed, not notarized.')
print('- **Linux:** extract the archive, keep the bundle together, and launch with the privileges needed for TUN.')
print()
print('## Testing and security limits')
print()
print('The Android physical-device result above is user-reported. The new account-management and interface behavior is exercised by local unit/widget tests and simulations; '
      'real account sign-in and OS-provided account pickers are subject to provider behavior. The agent did not test a live desktop TUN connection. '
      'IPv6 can bypass the desktop tunnel, non-DNS UDP is unsupported, and Mozilla restricts destination ports. '
      'Do not rely on this experimental client for leak-free or complete device-wide coverage.')
print()
print('Session tokens are sensitive. Android uses app-private preferences; desktop uses a local configuration file, not the OS keychain. '
      'Tokens are not encrypted by Gozar. Windows binaries are not Authenticode-signed.')
print()
print('## Signatures and support')
print()
print('Every APK and desktop archive has a detached OpenPGP `.sig` file. Import `gozar-public-key.asc` and verify the downloaded file with GPG; '
      'these signatures are not Windows Authenticode or Apple notarization.')
print()
print('If you would like to support development, Gozar also shows an optional TON donation address in Settings: '
      '`UQDxiU-jPFK_TgB0wOzZndhf3IKJLTLm8Vg_Uaif7_C2eCL3`.')
