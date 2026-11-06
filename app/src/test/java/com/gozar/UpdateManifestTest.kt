package com.gozar

import android.app.Application
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class UpdateManifestTest {

    private lateinit var key: KeyPair
    private var serial = 0L

    @Before
    fun prepare() {
        key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val encoded = key.public.encoded
        UpdateChecker.attachStore(FoxUpdateStore(null, encoded.copyOfRange(encoded.size - 32, encoded.size)))
    }

    private fun manifest(version: String, url: String): ByteArray {
        val payload = JSONObject().put("version", version).put("url", url)
            .put("apkSha256", "0".repeat(64)).toString().toByteArray()
        val prefix = FoxUpdate.prefix(++serial, payload)
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(key.private)
        signer.update(prefix)
        return prefix + signer.sign()
    }

    private fun release(asset: ByteArray? = null): JSONObject {
        val json = JSONObject().put("tag_name", "v9.9.9")
        if (asset != null) {
            json.put(
                "assets",
                JSONArray().put(
                    JSONObject().put("name", "gozar-release.bin")
                        .put("browser_download_url", "https://github.com/bardia049b-stack/Gozar/releases/download/v9.9.9/gozar-release.bin")
                )
            )
        }
        return json
    }

    @Test
    fun signedManifestAnnouncesTheUpdate() {
        val blob = manifest("9.9.9", "https://github.com/bardia049b-stack/Gozar/releases/tag/v9.9.9")
        val result = UpdateChecker.evaluate(release(blob), "1.0.0") { blob }
        assertTrue(result is UpdateChecker.Result.Available)
        assertEquals("9.9.9", (result as UpdateChecker.Result.Available).version)
    }

    @Test
    fun tamperedOrUnsignedReleasesAreRejected() {
        val blob = manifest("9.9.9", "https://github.com/bardia049b-stack/Gozar/releases/tag/v9.9.9")
        val tampered = blob.copyOf()
        tampered[25] = (tampered[25].toInt() xor 0x01).toByte()
        assertEquals(UpdateChecker.Result.Failed, UpdateChecker.evaluate(release(tampered), "1.0.0") { tampered })
        assertEquals(UpdateChecker.Result.Failed, UpdateChecker.evaluate(release(blob), "1.0.0") { null })
        assertEquals(UpdateChecker.Result.Failed, UpdateChecker.evaluate(release(), "1.0.0") { blob })
    }

    @Test
    fun replayedOlderSerialsCannotDowngradeThePrompt() {
        // The captured old blob is created first, so it carries the lower serial.
        val older = manifest("9.9.8", "https://github.com/bardia049b-stack/Gozar/releases/tag/v9.9.8")
        val newest = manifest("9.9.9", "https://github.com/bardia049b-stack/Gozar/releases/tag/v9.9.9")
        assertTrue(UpdateChecker.evaluate(release(newest), "1.0.0") { newest } is UpdateChecker.Result.Available)
        // Replaying the captured older blob fails on the serial floor.
        assertEquals(UpdateChecker.Result.Failed, UpdateChecker.evaluate(release(older), "9.9.9") { older })
    }

    @Test
    fun manifestsPointingAwayFromThePinnedRepositoryAreRejected() {
        val blob = manifest("9.9.9", "https://evil.example.com/gozar.apk")
        assertEquals(UpdateChecker.Result.Failed, UpdateChecker.evaluate(release(blob), "1.0.0") { blob })
        val foreignAsset = JSONObject().put("tag_name", "v9.9.9").put(
            "assets",
            JSONArray().put(
                JSONObject().put("name", "gozar-release.bin")
                    .put("browser_download_url", "https://evil.example.com/gozar-release.bin")
            )
        )
        assertEquals(UpdateChecker.Result.Failed, UpdateChecker.evaluate(foreignAsset, "1.0.0") { blob })
    }

    @Test
    fun installedVersionAtOrAboveTheManifestIsCurrent() {
        val blob = manifest("9.9.9", "https://github.com/bardia049b-stack/Gozar/releases/tag/v9.9.9")
        assertEquals(UpdateChecker.Result.Current, UpdateChecker.evaluate(release(blob), "9.9.9") { blob })
        val newer = manifest("9.9.9", "https://github.com/bardia049b-stack/Gozar/releases/tag/v9.9.9")
        assertEquals(UpdateChecker.Result.Current, UpdateChecker.evaluate(release(newer), "10.0.0") { newer })
    }

    @Test
    fun assetTrustFollowsThePinnedDownloadPath() {
        assertTrue(UpdateChecker.trustedAsset("https://github.com/bardia049b-stack/Gozar/releases/download/v1/x.bin"))
        assertTrue(UpdateChecker.trustedAsset("https://github.com/bardia049b-stack/Gozar1/releases/download/v1/x.bin"))
        org.junit.Assert.assertFalse(UpdateChecker.trustedAsset("https://github.com/bardia049b-stack/Gozar/raw/main/x.bin"))
        org.junit.Assert.assertFalse(UpdateChecker.trustedAsset("https://evil.example.com/bardia049b-stack/Gozar/releases/download/v1/x.bin"))
        org.junit.Assert.assertFalse(UpdateChecker.trustedAsset("http://github.com/bardia049b-stack/Gozar/releases/download/v1/x.bin"))
    }
}
