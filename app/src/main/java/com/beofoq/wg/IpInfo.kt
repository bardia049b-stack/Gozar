package com.beofoq.wg

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class IpInfo(val ip: String, val city: String, val country: String, val code: String)

object Ip {
    fun fetch(): IpInfo? {
        return fetchFrom("https://ipapi.co/json/", "ip", "country_name", "country_code")
            ?: fetchFrom("https://ipwho.is/", "ip", "country", "country_code")
    }

    private fun fetchFrom(url: String, ipKey: String, countryKey: String, codeKey: String): IpInfo? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "GozarWG")
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val j = JSONObject(body)
            val ip = j.optString(ipKey)
            if (ip.isBlank()) return null
            IpInfo(ip, j.optString("city"), j.optString(countryKey), j.optString(codeKey).uppercase())
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun flag(code: String): String {
        if (code.length != 2) return ""
        val sb = StringBuilder()
        for (ch in code.uppercase()) {
            sb.appendCodePoint(0x1F1E6 + (ch - 'A'))
        }
        return sb.toString()
    }
}
