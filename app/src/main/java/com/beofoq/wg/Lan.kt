package com.beofoq.wg

object Lan {
    private val privateRanges = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "100.64.0.0/10"
    )

    fun apply(text: String): String {
        return text.lines().joinToString("\n") { line ->
            val m = Regex("^(\\s*AllowedIPs\\s*=\\s*)(.+)$").find(line)
            if (m == null) {
                line
            } else {
                val ips = m.groupValues[2].split(",").map { it.trim() }.map { ip ->
                    if (ip == "0.0.0.0/0") withoutPrivate().joinToString(", ") else ip
                }
                m.groupValues[1] + ips.joinToString(", ")
            }
        }
    }

    private fun withoutPrivate(): List<String> {
        var ranges = listOf(0L to 4294967295L)
        for (p in privateRanges) {
            val cut = cidrRange(p)
            val next = mutableListOf<Pair<Long, Long>>()
            for (r in ranges) {
                if (cut.second < r.first || cut.first > r.second) {
                    next.add(r)
                } else {
                    if (cut.first > r.first) next.add(r.first to cut.first - 1)
                    if (cut.second < r.second) next.add(cut.second + 1 to r.second)
                }
            }
            ranges = next
        }
        val out = mutableListOf<String>()
        for (r in ranges) out.addAll(rangeToCidrs(r.first, r.second))
        return out
    }

    private fun cidrRange(cidr: String): Pair<Long, Long> {
        val parts = cidr.split("/")
        val ip = ipToLong(parts[0])
        val mask = parts[1].toInt()
        val size = 1L shl (32 - mask)
        val start = ip and (4294967295L - size + 1)
        return start to start + size - 1
    }

    private fun rangeToCidrs(start: Long, end: Long): List<String> {
        val out = mutableListOf<String>()
        var s = start
        while (s <= end) {
            var maxSize = s and -s
            if (maxSize == 0L) maxSize = 4294967296L
            while (s + maxSize - 1 > end) maxSize /= 2
            var bits = 0
            var v = maxSize
            while (v > 1) {
                v /= 2
                bits++
            }
            out.add(longToIp(s) + "/" + (32 - bits))
            s += maxSize
        }
        return out
    }

    private fun ipToLong(ip: String): Long {
        return ip.split(".").fold(0L) { acc, part -> (acc shl 8) or part.toLong() }
    }

    private fun longToIp(v: Long): String {
        return listOf(24, 16, 8, 0).joinToString(".") { ((v shr it) and 255).toString() }
    }
}
