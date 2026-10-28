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

    fun call(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeout: Int = 25000
    ): FoxReply {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = timeout
        connection.readTimeout = timeout
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", UA)
        connection.setRequestProperty("Accept", "*/*")
        connection.setRequestProperty("Cache-Control", "max-age=0")
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
            return FoxReply(code, text, found)
        } finally {
            connection.disconnect()
        }
    }
}
