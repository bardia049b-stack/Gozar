package com.gozarwg

import android.content.Context
import android.telephony.TelephonyManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class IpInfo(
    val ip: String,
    val city: String,
    val country: String,
    val code: String,
    val provider: String
)

object Ip {
    /** Resolves the egress metadata of whichever network is active for this process. */
    fun fetch(): IpInfo? {
        return fetchFrom("https://ipapi.co/json/", "ip", "country_name", "country_code") { it.optString("org") }
            ?: fetchFrom("https://ipwho.is/", "ip", "country", "country_code") {
                val connection = it.optJSONObject("connection")
                connection?.optString("isp").orEmpty().ifBlank { connection?.optString("org").orEmpty() }
            }
    }

    /**
     * Captures the pre-tunnel origin. IP geolocation databases can lag or place mobile egress IPs
     * in a neighbouring country, so an available active carrier country is used as a tie-breaker.
     * No GPS/location permission is requested or used.
     */
    fun fetchOrigin(context: Context): IpInfo? {
        val networkInfo = fetch()
        val carrierCode = carrierCountry(context) ?: return networkInfo
        if (networkInfo?.code?.equals(carrierCode, true) == true) return networkInfo
        return (networkInfo ?: IpInfo("", "", "", "", "")).copy(
            city = "",
            country = countryName(carrierCode),
            code = carrierCode
        )
    }

    private fun fetchFrom(
        url: String,
        ipKey: String,
        countryKey: String,
        codeKey: String,
        providerOf: (JSONObject) -> String
    ): IpInfo? {
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
            IpInfo(
                ip,
                j.optString("city"),
                j.optString(countryKey),
                j.optString(codeKey).uppercase(),
                providerOf(j)
            )
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun carrierCountry(context: Context): String? {
        return try {
            val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            sequenceOf(telephony?.networkCountryIso, telephony?.simCountryIso)
                .map { it.orEmpty().trim().uppercase() }
                .firstOrNull { it.length == 2 }
        } catch (_: Exception) {
            null
        }
    }

    private fun countryName(code: String): String =
        Locale("", code).getDisplayCountry(Locale.ENGLISH).ifBlank { code }

}
