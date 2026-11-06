package com.gozar

import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxEd25519Test {

    private fun unhex(text: String): ByteArray {
        val out = ByteArray(text.length / 2)
        for (i in out.indices) out[i] = Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16).toByte()
        return out
    }

    private fun rawPublic(pair: java.security.PublicKey): ByteArray {
        val encoded = pair.encoded
        return encoded.copyOfRange(encoded.size - 32, encoded.size)
    }

    @Test
    fun rfc8032VectorsVerify() {
        // RFC 8032 section 7.1, test 1 (empty message)
        assertTrue(
            FoxEd25519.verify(
                unhex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"),
                ByteArray(0),
                unhex(
                    "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                        "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
                )
            )
        )
        // test 2 (single byte 0x72)
        assertTrue(
            FoxEd25519.verify(
                unhex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"),
                byteArrayOf(0x72),
                unhex(
                    "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
                        "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"
                )
            )
        )
        // test 3 (two bytes 0xaf 0x82)
        assertTrue(
            FoxEd25519.verify(
                unhex("fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025"),
                unhex("af82"),
                unhex(
                    "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac" +
                        "18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"
                )
            )
        )
    }

    @Test
    fun platformSignaturesVerifyAndTamperingFails() {
        val generator = KeyPairGenerator.getInstance("Ed25519")
        val random = SecureRandom()
        for (round in 0 until 5) {
            val pair = generator.generateKeyPair()
            val message = ByteArray(random.nextInt(400))
            random.nextBytes(message)
            val signer = Signature.getInstance("Ed25519")
            signer.initSign(pair.private)
            signer.update(message)
            val signature = signer.sign()
            val public = rawPublic(pair.public)
            assertTrue("round $round must verify", FoxEd25519.verify(public, message, signature))

            val tampered = signature.copyOf()
            tampered[round] = (tampered[round].toInt() xor 0x01).toByte()
            assertFalse(FoxEd25519.verify(public, message, tampered))

            val wrongMessage = message + 0x20.toByte()
            assertFalse(FoxEd25519.verify(public, wrongMessage, signature))

            val other = generator.generateKeyPair()
            assertFalse(FoxEd25519.verify(rawPublic(other.public), message, signature))
        }
    }

    @Test
    fun malformedInputsAreRejected() {
        val generator = KeyPairGenerator.getInstance("Ed25519")
        val pair = generator.generateKeyPair()
        val message = "payload".toByteArray()
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(pair.private)
        signer.update(message)
        val signature = signer.sign()
        val public = rawPublic(pair.public)

        assertFalse(FoxEd25519.verify(public.copyOfRange(0, 31), message, signature))
        assertFalse(FoxEd25519.verify(public, message, signature.copyOfRange(0, 63)))
        // s >= l is not a canonical signature: add the group order to s.
        val orderShifted = signature.copyOf()
        val l = java.math.BigInteger(
            "27742317777372353535851937790883648493"
        ).add(java.math.BigInteger.TWO.pow(252))
        val s = java.math.BigInteger(1, signature.copyOfRange(32, 64).reversedArray()).add(l)
        val sBytes = s.toByteArray().let { raw ->
            if (raw.size <= 32) raw else raw.copyOfRange(raw.size - 32, raw.size)
        }
        System.arraycopy(sBytes, 0, orderShifted, 32 + (32 - sBytes.size), sBytes.size)
        assertFalse(FoxEd25519.verify(public, message, orderShifted))
    }

    @Test
    fun sha512MatchesTheStandardVector() {
        assertArrayEquals(
            unhex(
                "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
                    "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f"
            ),
            FoxEd25519.sha512("abc".toByteArray())
        )
        // 112 bytes exercises the padding edge where an extra block starts.
        val digest = FoxEd25519.sha512(ByteArray(112) { 'a'.code.toByte() })
        val reference = java.security.MessageDigest.getInstance("SHA-512").digest(ByteArray(112) { 'a'.code.toByte() })
        assertArrayEquals(reference, digest)
    }
}
