package com.gozar

data class FoxHop(val type: String, val host: String, val port: Int) {
    val label: String
        get() = (if (type == "http") "HTTP" else "SOCKS5") + " " + host + ":" + port
}

object FoxUpstream {

    @Volatile
    var hops: List<FoxHop> = emptyList()

    fun parse(raw: String): List<FoxHop> {
        val list = ArrayList<FoxHop>()
        for (part in raw.split(',', '\n')) {
            val text = part.trim()
            if (text.isEmpty()) continue
            val type = when {
                text.startsWith("http://", true) -> "http"
                text.startsWith("https://", true) -> "http"
                else -> "socks5"
            }
            val body = text.substringAfter("://", text)
            val host = body.substringBeforeLast(':').trim()
            val port = body.substringAfterLast(':', "").trim().toIntOrNull() ?: 0
            if (host.isNotEmpty() && port in 1..65535) list.add(FoxHop(type, host, port))
        }
        return list
    }

    fun text(): String = hops.joinToString(" , ") { hop ->
        (if (hop.type == "http") "http://" else "socks5://") + hop.host + ":" + hop.port
    }

    fun count(): Int = hops.size
}
