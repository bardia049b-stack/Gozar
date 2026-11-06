package com.gozar

import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FoxUpdateTest {

    private lateinit var key: KeyPair
    private lateinit var public: ByteArray

    @Before
    fun prepare() {
        key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val encoded = key.public.encoded
        public = encoded.copyOfRange(encoded.size - 32, encoded.size)
    }

    private fun sign(version: Long, payload: ByteArray): ByteArray {
        val prefix = FoxUpdate.prefix(version, payload)
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(key.private)
        signer.update(prefix)
        return prefix + signer.sign()
    }

    @Test
    fun signedBlobsVerifyAndTamperingFails() {
        val payload = "ir-domains payload".toByteArray()
        val blob = sign(7, payload)
        val signed = FoxUpdate.verify(blob, public)
        assertEquals(7L, signed?.version)
        assertArrayEquals(payload, signed?.payload)

        val flipped = blob.copyOf()
        flipped[20] = (flipped[20].toInt() xor 0x01).toByte()
        assertNull(FoxUpdate.verify(flipped, public))

        val truncated = blob.copyOfRange(0, blob.size - 1)
        assertNull(FoxUpdate.verify(truncated, public))

        val badMagic = blob.copyOf()
        badMagic[0] = 'X'.code.toByte()
        assertNull(FoxUpdate.verify(badMagic, public))

        val otherKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val otherEncoded = otherKey.public.encoded
        assertNull(FoxUpdate.verify(blob, otherEncoded.copyOfRange(otherEncoded.size - 32, otherEncoded.size)))

        assertNull(FoxUpdate.verify(ByteArray(0), public))
    }

    @Test
    fun versionsMustNeverGoBackwards() {
        val store = FoxUpdateStore(null, public)
        assertArrayEquals("five".toByteArray(), store.accept("geo", sign(5, "five".toByteArray())))
        assertNull(store.accept("geo", sign(4, "four".toByteArray())))
        // Re-adopting the current version is idempotent (a device that lost
        // its cache must be able to re-fetch it).
        assertArrayEquals("five-again".toByteArray(), store.accept("geo", sign(5, "five-again".toByteArray())))
        assertArrayEquals("six".toByteArray(), store.accept("geo", sign(6, "six".toByteArray())))
        assertEquals(6L, store.floor("geo"))
        assertArrayEquals("six".toByteArray(), store.cache("geo"))
        // Channels are independent.
        assertArrayEquals("one".toByteArray(), store.accept("other", sign(1, "one".toByteArray())))
    }

    @Test
    fun rollbackRestoresThePreviousGenerationWithoutLoweringTheFloor() {
        val store = FoxUpdateStore(null, public)
        store.accept("geo", sign(1, "generation-one".toByteArray()))
        store.accept("geo", sign(2, "generation-two".toByteArray()))
        assertArrayEquals("generation-one".toByteArray(), store.rollback("geo"))
        assertArrayEquals("generation-one".toByteArray(), store.cache("geo"))
        assertEquals(2L, store.floor("geo"))
        // The floor still blocks older versions after a rollback; re-accepting
        // the floor version itself stays idempotent.
        assertNull(store.accept("geo", sign(1, "generation-one".toByteArray())))
        assertArrayEquals("generation-two".toByteArray(), store.accept("geo", sign(2, "generation-two".toByteArray())))
        assertNull(store.rollback("unknown-channel"))
    }

    @Test
    fun theFloorSurvivesRestartsWhenDiskBacked() {
        val root = createTempDir()
        try {
            val first = FoxUpdateStore(root, public)
            assertArrayEquals("two".toByteArray(), first.accept("geo", sign(2, "two".toByteArray())))

            val second = FoxUpdateStore(root, public)
            assertEquals(2L, second.floor("geo"))
            assertNull(second.accept("geo", sign(1, "one".toByteArray())))
            assertArrayEquals("three".toByteArray(), second.accept("geo", sign(3, "three".toByteArray())))
            assertArrayEquals("three".toByteArray(), second.cache("geo"))
            assertArrayEquals("two".toByteArray(), second.rollback("geo"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptStateCannotDisableVerification() {
        val root = createTempDir()
        try {
            File(root, "fox-floor-geo").writeText("not-a-number")
            val store = FoxUpdateStore(root, public)
            // A corrupt floor falls back to zero, but signatures still gate everything.
            assertNull(store.accept("geo", "GOZU1-forgery".toByteArray()))
            assertArrayEquals("four".toByteArray(), store.accept("geo", sign(4, "four".toByteArray())))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createTempDir(): File =
        java.nio.file.Files.createTempDirectory("fox-update-test").toFile()
}
