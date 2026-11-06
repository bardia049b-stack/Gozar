package com.gozar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxGeoTest {
    @Test
    fun iranianDomainsAndAddressesAreDirect() {
        assertTrue(FoxGeo.direct("aparat.com"))
        assertTrue(FoxGeo.direct("www.aparat.com"))
        assertTrue(FoxGeo.direct("digikala.com"))
        assertTrue(FoxGeo.direct("api.digikala.com"))
        assertTrue(FoxGeo.direct("varzesh3.com"))
        assertTrue(FoxGeo.direct("shad.ir"))
        assertTrue(FoxGeo.direct("sub.shad.ir"))
        assertTrue(FoxGeo.direct("2.57.3.1"))
    }

    @Test
    fun foreignDomainsAndAddressesUseTheTunnel() {
        assertFalse(FoxGeo.direct("google.com"))
        assertFalse(FoxGeo.direct("www.google.com"))
        assertFalse(FoxGeo.direct("youtube.com"))
        assertFalse(FoxGeo.direct("youtu.be"))
        assertFalse(FoxGeo.direct("notaparat.com"))
        assertFalse(FoxGeo.direct("aparat.com.evil.example"))
        assertFalse(FoxGeo.direct("8.8.8.8"))
    }

    @Test
    fun invalidUpdateKeepsTheWorkingList() {
        assertFalse(FoxGeo.update("# comment\nbad_domain\n"))
        assertTrue(FoxGeo.direct("aparat.com"))
        assertFalse(FoxGeo.update(""))
        assertTrue(FoxGeo.direct("digikala.com"))
    }

    @Test
    fun routeUsesGeoBeforeLanSetting() {
        FoxRoute.apply(false, "")
        assertTrue(FoxRoute.direct("aparat.com"))
        assertTrue(FoxRoute.direct("digikala.com"))
        assertFalse(FoxRoute.direct("google.com"))
        assertFalse(FoxRoute.direct("youtube.com"))
    }

    @Test
    fun signedGeoListReplacesTheBundledOneAndUnsignedIsRejected() {
        val original = javaClass.getResourceAsStream("/ir-domains.txt")!!.bufferedReader().readText()
        val key = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val encoded = key.public.encoded
        val pub = encoded.copyOfRange(encoded.size - 32, encoded.size)
        try {
            FoxGeo.attachStore(FoxUpdateStore(null, pub))

            // The legacy unsigned text channel no longer applies.
            assertFalse(FoxGeo.applyDownloaded("test-signed.example\n".toByteArray()))
            assertTrue(FoxGeo.direct("aparat.com"))

            val blob = signGeo(key, 3, "test-signed.example\n".toByteArray())
            assertTrue(FoxGeo.applyDownloaded(blob))
            assertTrue(FoxGeo.direct("test-signed.example"))
            assertFalse(FoxGeo.direct("aparat.com"))

            // Tampered blobs keep the active list.
            val tampered = blob.copyOf()
            tampered[20] = (tampered[20].toInt() xor 0x01).toByte()
            assertFalse(FoxGeo.applyDownloaded(tampered))
            assertTrue(FoxGeo.direct("test-signed.example"))

            // Older versions cannot be replayed.
            assertFalse(FoxGeo.applyDownloaded(signGeo(key, 2, "replayed.example\n".toByteArray())))
            assertFalse(FoxGeo.direct("replayed.example"))

            // A signed-but-unparsable payload must not evict the working list.
            assertFalse(FoxGeo.applyDownloaded(signGeo(key, 4, "not a domain!!\n".toByteArray())))
            assertTrue(FoxGeo.direct("test-signed.example"))
        } finally {
            FoxGeo.attachStore(FoxUpdateStore(null))
            FoxGeo.update(original)
        }
    }

    private fun signGeo(key: java.security.KeyPair, version: Long, payload: ByteArray): ByteArray {
        val prefix = FoxUpdate.prefix(version, payload)
        val signer = java.security.Signature.getInstance("Ed25519")
        signer.initSign(key.private)
        signer.update(prefix)
        return prefix + signer.sign()
    }
}
