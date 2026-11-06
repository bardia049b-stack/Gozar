package com.gozar

import java.io.File
import java.io.FileOutputStream

/**
 * Signed update payloads with rollback protection.
 *
 * Blob layout ("GOZU1"):
 *   magic   5 bytes  "GOZU1"
 *   version 8 bytes  big-endian, monotonic per channel
 *   length  4 bytes  big-endian payload length
 *   payload length bytes
 *   sig     64 bytes Ed25519 over every byte above
 *
 * Devices only verify; signing happens offline with scripts/SignGozar.java.
 */
object FoxUpdate {

    /** Raw 32-byte Ed25519 public key of the update signer. */
    val PUBLIC_KEY: ByteArray = FoxCrypto.unhex(
        "c17559cbf174b06e994fdf03c287fee9e9d5261b6df92bbf1c993c8335634350"
    )

    private val MAGIC = byteArrayOf('G'.code.toByte(), 'O'.code.toByte(), 'Z'.code.toByte(), 'U'.code.toByte(), '1'.code.toByte())

    class Signed(val version: Long, val payload: ByteArray)

    fun verify(blob: ByteArray, publicKey: ByteArray = PUBLIC_KEY): Signed? {
        if (blob.size < MAGIC.size + 8 + 4 + 1 + 64) return null
        for (i in MAGIC.indices) if (blob[i] != MAGIC[i]) return null
        val length = ((blob[13].toInt() and 0xFF) shl 24) or ((blob[14].toInt() and 0xFF) shl 16) or
            ((blob[15].toInt() and 0xFF) shl 8) or (blob[16].toInt() and 0xFF)
        if (length <= 0 || MAGIC.size + 8 + 4 + length + 64 != blob.size) return null
        val prefix = blob.copyOfRange(0, blob.size - 64)
        val signature = blob.copyOfRange(blob.size - 64, blob.size)
        if (!FoxEd25519.verify(publicKey, prefix, signature)) return null
        var version = 0L
        for (i in 5 until 13) version = (version shl 8) or (blob[i].toLong() and 0xFF)
        if (version <= 0) return null
        return Signed(version, blob.copyOfRange(MAGIC.size + 8 + 4, MAGIC.size + 8 + 4 + length))
    }

    /** Rebuilds the signed prefix for a version/payload pair (used by tests and tooling). */
    fun prefix(version: Long, payload: ByteArray): ByteArray {
        val out = ByteArray(MAGIC.size + 8 + 4 + payload.size)
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.size)
        for (i in 0 until 8) out[5 + i] = (version ushr (56 - 8 * i)).toByte()
        out[13] = ((payload.size ushr 24) and 0xFF).toByte()
        out[14] = ((payload.size ushr 16) and 0xFF).toByte()
        out[15] = ((payload.size ushr 8) and 0xFF).toByte()
        out[16] = (payload.size and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 17, payload.size)
        return out
    }
}

/**
 * Per-channel rollback floor plus a two-generation payload cache.
 *
 * [accept] enforces version monotonicity against the stored floor, so a
 * captured old (even validly signed) blob can never be replayed to downgrade
 * a device. [rollback] restores the previous generation locally without
 * lowering the floor. With no directory attached the store works in memory
 * only (floor resets on process start).
 */
class FoxUpdateStore(
    private val root: File?,
    val publicKey: ByteArray = FoxUpdate.PUBLIC_KEY
) {

    private var memoryFloor = HashMap<String, Long>()
    private val memoryCache = HashMap<String, ByteArray>()
    private val memoryPrevious = HashMap<String, ByteArray>()

    init {
        root?.mkdirs()
    }

    fun floor(channel: String): Long {
        val file = file("floor", channel) ?: return memoryFloor[channel] ?: 0L
        return try {
            file.readText().trim().toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    fun cache(channel: String): ByteArray? {
        val file = file("cache", channel) ?: return memoryCache[channel]
        return try {
            file.takeIf { it.exists() }?.readBytes()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Verifies [blob] and, when it is newer than the floor, adopts it:
     * floor first (crash-safe: a half-adopted version must never allow an
     * older one), then the two-generation cache rotation. Returns the payload
     * or null when the blob is rejected.
     */
    fun accept(channel: String, blob: ByteArray): ByteArray? {
        val signed = FoxUpdate.verify(blob, publicKey) ?: return null
        if (signed.version < floor(channel)) return null
        if (root == null) {
            memoryFloor[channel] = signed.version
            memoryCache[channel]?.let { memoryPrevious[channel] = it }
            memoryCache[channel] = signed.payload
            return signed.payload
        }
        writeAtomic(file("floor", channel)!!, signed.version.toString().toByteArray(Charsets.US_ASCII))
        val cache = file("cache", channel)!!
        val previous = file("previous", channel)!!
        try {
            if (cache.exists()) cache.renameTo(previous)
            writeAtomic(cache, signed.payload)
        } catch (e: Exception) {
            return null
        }
        return signed.payload
    }

    /**
     * Commits an already-verified payload: floor first (crash-safe), then the
     * two-generation cache rotation. Callers that must validate the payload
     * before adopting it use this instead of [accept].
     */
    fun adopt(channel: String, version: Long, payload: ByteArray) {
        if (root == null) {
            memoryFloor[channel] = version
            memoryCache[channel]?.let { memoryPrevious[channel] = it }
            memoryCache[channel] = payload
            return
        }
        writeAtomic(file("floor", channel)!!, version.toString().toByteArray(Charsets.US_ASCII))
        val cache = file("cache", channel)!!
        val previous = file("previous", channel)!!
        try {
            if (cache.exists()) cache.renameTo(previous)
            writeAtomic(cache, payload)
        } catch (_: Exception) {
        }
    }

    /**
     * Restores the previous generation of [channel] without touching the
     * floor. Returns the restored payload, or null when there is nothing to
     * roll back to.
     */
    fun rollback(channel: String): ByteArray? {
        if (root == null) {
            val previous = memoryPrevious[channel] ?: return null
            memoryCache[channel]?.let { memoryPrevious[channel] = it }
            memoryCache[channel] = previous
            return previous
        }
        val cache = file("cache", channel)!!
        val previous = file("previous", channel)!!
        if (!previous.exists()) return null
        return try {
            val restored = previous.readBytes()
            val stash = file("stash", channel)!!
            if (cache.exists()) cache.renameTo(stash)
            previous.renameTo(cache)
            if (stash.exists()) stash.renameTo(previous)
            restored
        } catch (e: Exception) {
            null
        }
    }

    private fun file(kind: String, channel: String): File? {
        if (root == null) return null
        val safe = channel.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        return File(root, "fox-$kind-$safe")
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (target.exists() && !target.delete()) throw java.io.IOException("could not replace ${target.name}")
        if (!tmp.renameTo(target)) throw java.io.IOException("could not commit ${target.name}")
    }
}
