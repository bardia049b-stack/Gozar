package com.gozar

import java.math.BigInteger

/**
 * Ed25519 signature verification (RFC 8032), implemented on BigInteger so it
 * runs on every Android API level Gozar supports (platform EdDSA needs
 * API 33) and stays small enough to audit. Verification only: signing
 * happens offline with scripts/SignGozar.java.
 *
 * The implementation favors clarity over speed: verification runs once per
 * update check, not on any hot path.
 */
object FoxEd25519 {

    private val ZERO = BigInteger.ZERO
    private val ONE = BigInteger.ONE
    private val TWO = BigInteger.valueOf(2)
    private val THREE = BigInteger.valueOf(3)
    private val FIVE = BigInteger.valueOf(5)
    private val SEVEN = BigInteger.valueOf(7)
    private val EIGHT = BigInteger.valueOf(8)

    /** p = 2^255 - 19 */
    private val P = TWO.pow(255).subtract(BigInteger.valueOf(19))

    /** l = 2^252 + 27742317777372353535851937790883648493 (group order) */
    private val L = TWO.pow(252).add(BigInteger("27742317777372353535851937790883648493"))

    /** d = -121665/121666 mod p */
    private val D = BigInteger("37095705934669439343138083508754565189542113879843219016388785533085940283555")

    /** sqrt(-1) mod p */
    private val SQRT_M1 = BigInteger("19681161376707505956807079304988542015446066515923890162744021073123829784752")

    /** Base point B */
    private val B = triple(
        BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202"),
        BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960")
    )

    /** (p - 5) / 8, used by the RFC 8032 square-root candidate */
    private val EXP = P.subtract(FIVE).divide(EIGHT)

    private class Triple(val x: BigInteger, val y: BigInteger, val z: BigInteger)

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val a = decodePoint(publicKey) ?: return false
        val r = decodePoint(signature.copyOfRange(0, 32)) ?: return false
        val s = BigInteger(1, signature.copyOfRange(32, 64).reversedArray())
        if (s >= L) return false
        val hashed = ByteArray(64 + message.size)
        System.arraycopy(signature, 0, hashed, 0, 32)
        System.arraycopy(publicKey, 0, hashed, 32, 32)
        System.arraycopy(message, 0, hashed, 64, message.size)
        val k = BigInteger(1, sha512(hashed).reversedArray())
        // RFC 8032: the signature is valid when [s]B == R + [k]A.
        return sameAffine(scalarmult(s, B), add(r, scalarmult(k, a)))
    }

    /** Decompresses an encoded point; returns null when the encoding is invalid. */
    private fun decodePoint(bytes: ByteArray): Triple? {
        val sign = (bytes[31].toInt() ushr 7) and 1
        val encoded = bytes.copyOf()
        encoded[31] = (encoded[31].toInt() and 0x7F).toByte()
        val y = BigInteger(1, encoded.reversedArray())
        if (y >= P) return null
        val y2 = y.multiply(y).mod(P)
        val u = y2.subtract(ONE).mod(P)
        val v = D.multiply(y2).add(ONE).mod(P)
        if (v == ZERO) return null
        val v3 = v.modPow(THREE, P)
        val v7 = v.modPow(SEVEN, P)
        var x = u.multiply(v3).mod(P)
            .multiply(u.multiply(v7).mod(P).modPow(EXP, P))
            .mod(P)
        val check = v.multiply(x).mod(P).multiply(x).mod(P)
        when (check) {
            u -> Unit
            u.negate().mod(P) -> x = x.multiply(SQRT_M1).mod(P)
            else -> return null
        }
        if (x == ZERO && sign == 1) return null
        if (x.testBit(0) != (sign == 1)) x = P.subtract(x)
        return triple(x, y)
    }

    private fun triple(x: BigInteger, y: BigInteger) = Triple(x, y, ONE)

    /** Double-and-add over the bits of [scalar]; scalars may be up to 512 bits (the challenge k). */
    private fun scalarmult(scalar: BigInteger, point: Triple): Triple {
        var result: Triple? = null
        for (i in scalar.bitLength() - 1 downTo 0) {
            val current = result
            result = when {
                current == null && scalar.testBit(i) -> point
                current == null -> null
                scalar.testBit(i) -> add(double(current), point)
                else -> double(current)
            }
        }
        return result ?: triple(ZERO, ONE)
    }

    /** Unified twisted-Edwards addition for a = -1 in projective coordinates (add-2008-hwcd-3). */
    private fun add(p: Triple, q: Triple): Triple {
        val aa = p.z.multiply(q.z).mod(P)
        val b = aa.multiply(aa).mod(P)
        val c = p.x.multiply(q.x).mod(P)
        val d = p.y.multiply(q.y).mod(P)
        val e = D.multiply(c).mod(P).multiply(d).mod(P)
        val f = b.subtract(e).mod(P)
        val g = b.add(e).mod(P)
        val sum = p.x.add(p.y).mod(P).multiply(q.x.add(q.y).mod(P)).mod(P)
        val x3 = aa.multiply(f).mod(P).multiply(sum.subtract(c).subtract(d).mod(P)).mod(P)
        val y3 = aa.multiply(g).mod(P).multiply(d.add(c)).mod(P)
        val z3 = f.multiply(g).mod(P)
        return Triple(x3, y3, z3)
    }

    /** Doubling for a = -1 (dbl-2008-hwcd). */
    private fun double(p: Triple): Triple {
        val a = p.x.multiply(p.x).mod(P)
        val b = p.y.multiply(p.y).mod(P)
        val c = TWO.multiply(p.z.multiply(p.z).mod(P)).mod(P)
        val d = a.negate().mod(P) // a = -1, so D = -A
        val e = p.x.add(p.y).mod(P).pow(2).mod(P).subtract(a).subtract(b).mod(P)
        val g = d.add(b).mod(P)
        val f = g.subtract(c).mod(P)
        val h = d.subtract(b).mod(P)
        return Triple(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P))
    }

    /** Compares two projective points by cross-multiplication (no inversion). */
    private fun sameAffine(p: Triple, q: Triple): Boolean =
        p.x.multiply(q.z).mod(P) == q.x.multiply(p.z).mod(P) &&
            p.y.multiply(q.z).mod(P) == q.y.multiply(p.z).mod(P)

    // ---- SHA-512 (FIPS 180-4) -------------------------------------------------

    private val K_HEX = arrayOf(
        "428a2f98d728ae22", "7137449123ef65cd", "b5c0fbcfec4d3b2f", "e9b5dba58189dbbc",
        "3956c25bf348b538", "59f111f1b605d019", "923f82a4af194f9b", "ab1c5ed5da6d8118",
        "d807aa98a3030242", "12835b0145706fbe", "243185be4ee4b28c", "550c7dc3d5ffb4e2",
        "72be5d74f27b896f", "80deb1fe3b1696b1", "9bdc06a725c71235", "c19bf174cf692694",
        "e49b69c19ef14ad2", "efbe4786384f25e3", "0fc19dc68b8cd5b5", "240ca1cc77ac9c65",
        "2de92c6f592b0275", "4a7484aa6ea6e483", "5cb0a9dcbd41fbd4", "76f988da831153b5",
        "983e5152ee66dfab", "a831c66d2db43210", "b00327c898fb213f", "bf597fc7beef0ee4",
        "c6e00bf33da88fc2", "d5a79147930aa725", "06ca6351e003826f", "142929670a0e6e70",
        "27b70a8546d22ffc", "2e1b21385c26c926", "4d2c6dfc5ac42aed", "53380d139d95b3df",
        "650a73548baf63de", "766a0abb3c77b2a8", "81c2c92e47edaee6", "92722c851482353b",
        "a2bfe8a14cf10364", "a81a664bbc423001", "c24b8b70d0f89791", "c76c51a30654be30",
        "d192e819d6ef5218", "d69906245565a910", "f40e35855771202a", "106aa07032bbd1b8",
        "19a4c116b8d2d0c8", "1e376c085141ab53", "2748774cdf8eeb99", "34b0bcb5e19b48a8",
        "391c0cb3c5c95a63", "4ed8aa4ae3418acb", "5b9cca4f7763e373", "682e6ff3d6b2b8a3",
        "748f82ee5defb2fc", "78a5636f43172f60", "84c87814a1f0ab72", "8cc702081a6439ec",
        "90befffa23631e28", "a4506cebde82bde9", "bef9a3f7b2c67915", "c67178f2e372532b",
        "ca273eceea26619c", "d186b8c721c0c207", "eada7dd6cde0eb1e", "f57d4f7fee6ed178",
        "06f067aa72176fba", "0a637dc5a2c898a6", "113f9804bef90dae", "1b710b35131c471b",
        "28db77f523047d84", "32caab7b40c72493", "3c9ebe0a15c9bebc", "431d67c49c100d4c",
        "4cc5d4becb3e42b6", "597f299cfc657e2a", "5fcb6fab3ad6faec", "6c44198c4a475817"
    )

    private val K = LongArray(80) { BigInteger(K_HEX[it], 16).toLong() }

    private val H_HEX = arrayOf(
        "6a09e667f3bcc908", "bb67ae8584caa73b", "3c6ef372fe94f82b", "a54ff53a5f1d36f1",
        "510e527fade682d1", "9b05688c2b3e6c1f", "1f83d9abfb41bd6b", "5be0cd19137e2179"
    )

    fun sha512(message: ByteArray): ByteArray {
        val h = LongArray(8) { BigInteger(H_HEX[it], 16).toLong() }
        val padded = pad(message)
        val w = LongArray(80)
        for (block in 0 until padded.size / 128) {
            for (i in 0 until 16) {
                var word = 0L
                for (j in 0 until 8) word = (word shl 8) or (padded[block * 128 + i * 8 + j].toLong() and 0xFF)
                w[i] = word
            }
            for (i in 16 until 80) {
                val s0 = rotr(w[i - 15], 1) xor rotr(w[i - 15], 8) xor (w[i - 15] ushr 7)
                val s1 = rotr(w[i - 2], 19) xor rotr(w[i - 2], 61) xor (w[i - 2] ushr 6)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var v = h[7]
            for (i in 0 until 80) {
                val s1 = rotr(e, 14) xor rotr(e, 18) xor rotr(e, 41)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = v + s1 + ch + K[i] + w[i]
                val s0 = rotr(a, 28) xor rotr(a, 34) xor rotr(a, 39)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                v = g; g = f; f = e; e = d + t1
                d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d
            h[4] += e; h[5] += f; h[6] += g; h[7] += v
        }
        val out = ByteArray(64)
        for (i in 0 until 8) {
            for (j in 0 until 8) out[i * 8 + j] = (h[i] ushr (56 - 8 * j)).toByte()
        }
        return out
    }

    private fun pad(message: ByteArray): ByteArray {
        val blocks = (message.size + 1 + 16 + 127) / 128
        val out = ByteArray(blocks * 128)
        System.arraycopy(message, 0, out, 0, message.size)
        out[message.size] = 0x80.toByte()
        val bits = message.size.toLong() * 8
        for (i in 0 until 8) out[out.size - 1 - i] = (bits ushr (8 * i)).toByte()
        return out
    }

    private fun rotr(x: Long, n: Int): Long = (x ushr n) or (x shl (64 - n))
}
