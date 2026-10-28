package com.gozarwg

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class FoxReply(val code: Int, val body: String, val headers: Map<String, String>) {
    fun header(name: String): String? {
        for ((k, v) in headers) if (k.equals(name, true)) return v
        return null
    }
}

object FoxNet {

    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:149.0) Gecko/20100101 Firefox/149.0"
    const val CLIENT_ID = "5882386c6d801776"
    const val SCOPE = "profile https://identity.mozilla.com/apps/vpn"
    const val AUTH = "https://api.accounts.firefox.com/v1"
    const val GUARDIAN = "https://vpn.mozilla.org"
    const val SERVERS = "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/vpn-serverlist/records"

    private val jar = FoxJar()

    fun forget() = jar.clear()

    fun call(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeout: Int = 25000
    ): FoxReply {
        if (url.startsWith(AUTH) && jar.header().isEmpty()) FoxShield.solve(jar)
        FoxLog.add("http", "$method $url" + (if (body != null) " body=${body.length}b" else ""))
        var reply = once(url, method, body, headers, timeout)
        FoxLog.add("http", "→ ${reply.code} ${reply.body.length}b ${snippet(reply)}")
        var round = 0
        while (reply.code == 406 && round < 4) {
            round++
            val origin = try {
                val parsed = URL(url)
                parsed.protocol + "://" + parsed.host
            } catch (_: Exception) {
                "https://accounts.firefox.com"
            }
            val solved = FoxShield.solve(jar, origin)
            FoxLog.add("shield", "406 → solve($origin)=" + solved + " cookies=" + jar.header().length + "b")
            if (!solved) break
            reply = once(url, method, body, headers, timeout)
            FoxLog.add("http", "retry → ${reply.code} ${reply.body.length}b ${snippet(reply)}")
        }
        return reply
    }

    private fun snippet(reply: FoxReply): String {
        val body = reply.body.trim()
        return if (body.isEmpty()) "" else body.take(160)
    }

    private fun once(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>,
        timeout: Int
    ): FoxReply {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = timeout
        connection.readTimeout = timeout
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", UA)
        connection.setRequestProperty("Accept", "*/*")
        connection.setRequestProperty("Cache-Control", "max-age=0")
        if (jar.header().isNotEmpty()) connection.setRequestProperty("Cookie", jar.header())
        if (body != null) connection.setRequestProperty("Content-Type", "application/json")
        for ((k, v) in headers) connection.setRequestProperty(k, v)
        try {
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream: InputStream? = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val found = HashMap<String, String>()
            for ((k, values) in connection.headerFields) {
                if (k == null || values.isEmpty()) continue
                found[k] = values.joinToString(",")
            }
            val reply = FoxReply(code, text, found)
            jar.absorb(reply)
            return reply
        } finally {
            connection.disconnect()
        }
    }
}
