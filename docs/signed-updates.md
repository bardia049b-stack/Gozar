# Signed updates and rollback protection

Every list and release prompt a device adopts over the network is verified
with Ed25519 (RFC 8032) and gated by a monotonic version floor, so a captured
old payload — even a validly signed one — can never be replayed to downgrade
a device, and an unsigned or tampered payload is rejected outright.

## Blob format ("GOZU1")

```
magic   5 bytes   "GOZU1"
version 8 bytes   big-endian, monotonic per channel
length  4 bytes   big-endian payload length
payload N bytes
sig     64 bytes  Ed25519 over every byte above
```

## Channels

| Channel | Payload | Adopted by |
| --- | --- | --- |
| `geo` | Iranian domain list (text) | `FoxGeo.applyDownloaded` — fetched as `packaging/ir-domains.bin` through the tunnel |
| `release` | JSON `{version, url, apkSha256}` | `UpdateChecker.evaluate` — fetched as the `gozar-release.bin` asset of the latest GitHub release |

The signing public key is pinned in `FoxUpdate.PUBLIC_KEY`. Devices only
verify; the verifier (`FoxEd25519`) is pure Kotlin on BigInteger so it runs
on every supported Android API level.

## Store semantics (`FoxUpdateStore`)

- `accept(channel, blob)`: verify → reject when `version < floor` → advance
  floor first (crash-safe: a half-adopted version can never allow an older
  one) → rotate the two-generation cache (`cache` ← new, `previous` ← old).
- `rollback(channel)`: restores `previous` locally without lowering the
  floor. Rollback is an operator action, never automatic on fetch failure;
  failed fetches keep the current generation.
- Disk-backed stores fsync + rename atomically; corrupt floor files fall
  back to zero, which only re-arms TOFU — signatures still gate everything.
- Without a directory the store is memory-only (floor resets per process).

## Release procedure

The private key lives outside the repository (pkcs8, base64). To publish:

```sh
# one-time
java scripts/SignGozar.java genkey update-key.pkcs8.b64   # prints the raw public hex

# update the geo list
java scripts/SignGozar.java sign update-key.pkcs8.b64 <N+1> packaging/ir-domains.txt packaging/ir-domains.bin
git add packaging/ir-domains.bin && git commit && git push   # clients fetch it from the repo

# update release prompts: sign the manifest and attach it to the GitHub release as gozar-release.bin
echo '{"version":"1.11.1","url":"https://github.com/bardia049b-stack/Gozar/releases/tag/v1.11.1","apkSha256":"<sha256 of the apk>"}' > manifest.json
java scripts/SignGozar.java sign update-key.pkcs8.b64 <serial+1> manifest.json gozar-release.bin
# upload gozar-release.bin as a release asset named exactly "gozar-release.bin"
```

The release serial must increase with every release; the device floor makes
older serials inert. `UpdateChecker` additionally pins the asset URL and the
manifest `url` to `github.com/bardia049b-stack/{Gozar,Gozar1}/releases/…`.

## Key custody

If the key is lost, clients keep working with their bundled/cached lists;
new signed updates become impossible until an app release ships a new pinned
key. If the key is compromised, ship an app release with a new pinned key —
the floor means the compromised key still cannot downgrade devices below
their current version.
