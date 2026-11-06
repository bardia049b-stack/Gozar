package com.gozar

import java.io.ByteArrayOutputStream

object FoxGeo {

    @Volatile
    private var domains: Set<String> = loadDomains()

    private val prefixes: List<LongRange> = loadPrefixes()

    fun direct(host: String): Boolean {
        val name = host.trim().lowercase().removeSuffix(".").trim('[', ']')
        if (name.isEmpty() || name.length > 253) return false
        if (name == "ir" || name.endsWith(".ir")) return true
        ipv4(name)?.let { address ->
            return prefixes.any { address in it }
        }
        var suffix = name
        while (true) {
            if (domains.contains(suffix)) return true
            val index = suffix.indexOf('.')
            if (index < 0) return false
            suffix = suffix.substring(index + 1)
        }
    }

    fun update(text: String): Boolean {
        val parsed = parse(text) ?: return false
        domains = parsed
        return true
    }

    private const val CHANNEL = "geo"

    @Volatile
    private var store: FoxUpdateStore = FoxUpdateStore(null)

    /**
     * Attaches disk-backed rollback protection and seeds the list from the
     * last verified payload when the process restarts offline.
     */
    fun attachStore(attached: FoxUpdateStore) {
        store = attached
        val cached = attached.cache(CHANNEL) ?: return
        applyPayload(cached)
    }

    /**
     * Applies a signed blob; rejects anything unsigned, tampered, or older
     * than the floor. The payload is parsed before the store adopts it, so a
     * broken list can never evict the last good cache generation.
     */
    internal fun applyDownloaded(bytes: ByteArray): Boolean {
        val signed = FoxUpdate.verify(bytes, store.publicKey) ?: return false
        if (signed.version < store.floor(CHANNEL)) return false
        val parsed = parse(String(signed.payload, Charsets.UTF_8)) ?: return false
        store.adopt(CHANNEL, signed.version, signed.payload)
        domains = parsed
        return true
    }

    private fun applyPayload(payload: ByteArray): Boolean {
        val parsed = parse(String(payload, Charsets.UTF_8)) ?: return false
        domains = parsed
        return true
    }

    fun refresh(location: FoxLocation, pass: String): Boolean {
        return try {
            val pipe = FoxTunnel.open(location, "raw.githubusercontent.com", 443, pass, 15000)
            try {
                val request = ("GET /bardia049b-stack/Gozar/main/packaging/ir-domains.bin HTTP/1.1\r\n" +
                    "Host: raw.githubusercontent.com\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
                pipe.write(request, 0, request.size)
                val buffer = ByteArray(8192)
                val all = ByteArrayOutputStream()
                while (true) {
                    val count = pipe.read(buffer, 0, buffer.size)
                    if (count <= 0) break
                    all.write(buffer, 0, count)
                    if (all.size() > 1_100_000) return false
                }
                applyDownloaded(stripHttpHeaders(all.toByteArray()))
            } finally {
                pipe.close()
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Splits a raw HTTP/1 response into its body; returns empty bytes on any non-200 reply. */
    private fun stripHttpHeaders(bytes: ByteArray): ByteArray {
        outer@ for (i in 0..bytes.size - 4) {
            if (bytes[i] != 13.toByte() || bytes[i + 1] != 10.toByte() ||
                bytes[i + 2] != 13.toByte() || bytes[i + 3] != 10.toByte()
            ) {
                continue@outer
            }
            val head = String(bytes, 0, i, Charsets.US_ASCII)
            if (!head.startsWith("HTTP/") || !head.contains(" 200 ")) return ByteArray(0)
            return bytes.copyOfRange(i + 4, bytes.size)
        }
        return ByteArray(0)
    }

    private fun parse(text: String): Set<String>? {
        val parsed = HashSet<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val domain = line.lowercase().removeSuffix(".")
            if (!valid(domain)) return null
            parsed.add(domain)
            if (parsed.size > 20000) return null
        }
        return if (parsed.isEmpty()) null else parsed
    }

    private fun valid(domain: String): Boolean {
        if (domain.length > 253 || domain.contains("://")) return false
        val labels = domain.split('.')
        if (labels.size < 2) return false
        for (label in labels) {
            if (label.isEmpty() || label.length > 63 || label.startsWith("-") || label.endsWith("-")) return false
            if (!label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) return false
        }
        return true
    }

    private fun ipv4(name: String): Long? {
        val parts = name.split('.')
        if (parts.size != 4) return null
        var value = 0L
        for (part in parts) {
            val number = part.toIntOrNull() ?: return null
            if (number < 0 || number > 255) return null
            value = (value shl 8) or number.toLong()
        }
        return value
    }

    private fun loadDomains(): Set<String> {
        val text = resource("/ir-domains.txt") ?: return emptySet()
        return parse(text) ?: emptySet()
    }

    private fun loadPrefixes(): List<LongRange> {
        val text = resource("/ir-ipv4.cidr") ?: return emptyList()
        val ranges = ArrayList<LongRange>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val slash = line.indexOf('/')
            if (slash <= 0) continue
            val address = ipv4(line.substring(0, slash)) ?: continue
            val bits = line.substring(slash + 1).toIntOrNull() ?: continue
            if (bits < 0 || bits > 32) continue
            val mask = if (bits == 0) 0L else (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
            val start = address and mask
            val end = start or (mask.inv() and 0xFFFFFFFFL)
            ranges.add(start..end)
        }
        return ranges
    }

    private fun resource(name: String): String? {
        return try {
            FoxGeo::class.java.getResourceAsStream(name)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }
}
