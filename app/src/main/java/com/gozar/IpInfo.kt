package com.gozar

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
    /**
     * Resolves the egress metadata of the network active for this process.
     * Each request explicitly closes its HTTP connection so post-tunnel lookup cannot reuse the
     * direct-network keep-alive connection used to sample the pre-tunnel origin.
     */
    fun fetch(): IpInfo? {
        return fetchFrom("https://ipapi.co/json/", "ip", "country_name", "country_code") { it.optString("org") }
            ?: fetchFrom("https://ipwho.is/", "ip", "country", "country_code") {
                val connection = it.optJSONObject("connection")
                connection?.optString("isp").orEmpty().ifBlank { connection?.optString("org").orEmpty() }
            }
            ?: fetchIpInfo()
            ?: fetchCloudflareTrace()
            ?: fetchIpOnly()
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
        return try {
            val json = JSONObject(request(url) ?: return null)
            val ip = json.optString(ipKey)
            if (ip.isBlank()) return null
            IpInfo(
                ip,
                json.optString("city"),
                json.optString(countryKey),
                json.optString(codeKey).uppercase(),
                providerOf(json)
            )
        } catch (_: Exception) {
            null
        }
    }

    /** A separate provider with a useful ASN/organisation name when the first two are unavailable. */
    private fun fetchIpInfo(): IpInfo? {
        return try {
            val json = JSONObject(request("https://ipinfo.io/json") ?: return null)
            val ip = json.optString("ip")
            val code = json.optString("country").trim().uppercase()
            if (ip.isBlank()) return null
            IpInfo(
                ip = ip,
                city = json.optString("city"),
                country = if (code.length == 2) countryName(code) else "",
                code = code.takeIf { it.length == 2 }.orEmpty(),
                provider = json.optString("org")
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Cloudflare's trace endpoint supplies the egress IP and visitor country without pretending
     * that the Cloudflare POP is the VPN provider. It is a location fallback for the offline map.
     */
    private fun fetchCloudflareTrace(): IpInfo? {
        return try {
            val fields = request("https://www.cloudflare.com/cdn-cgi/trace")
                ?.lineSequence()
                ?.mapNotNull { line ->
                    val split = line.indexOf('=')
                    if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
                }
                ?.toMap()
                ?: return null
            val ip = fields["ip"].orEmpty()
            val code = fields["loc"].orEmpty().trim().uppercase()
            if (ip.isBlank()) return null
            IpInfo(ip, "", if (code.length == 2) countryName(code) else "", code.take(2), "")
        } catch (_: Exception) {
            null
        }
    }

    /** Final IP-only fallback keeps the live exit address visible even if every geo service is blocked. */
    private fun fetchIpOnly(): IpInfo? {
        return try {
            val json = JSONObject(request("https://api.ipify.org?format=json") ?: return null)
            val ip = json.optString("ip")
            if (ip.isBlank()) null else IpInfo(ip, "", "", "", "")
        } catch (_: Exception) {
            null
        }
    }

    private fun request(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            connection.useCaches = false
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Gozar")
            connection.setRequestProperty("Accept", "application/json, text/plain;q=0.9, */*;q=0.8")
            // A direct pre-tunnel request must never be held open and reused after the VPN comes up.
            connection.setRequestProperty("Connection", "close")
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
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
