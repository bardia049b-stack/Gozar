package com.gozar

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object Store {

    val ADVANCED = listOf("doq", "ech", "tfo", "mptcp", "wss", "domain_fronting")


    private fun p(c: Context) = c.getSharedPreferences("store", Context.MODE_PRIVATE)

    fun lang(c: Context) = p(c).getString("lang", "") ?: ""
    fun setLang(c: Context, v: String) = p(c).edit().putString("lang", v).apply()
    fun theme(c: Context) = p(c).getString("theme", "auto") ?: "auto"
    fun setTheme(c: Context, v: String) = p(c).edit().putString("theme", v).apply()
    fun recents(c: Context) = p(c).getBoolean("recents", false)
    fun setRecents(c: Context, v: Boolean) {
        val edit = p(c).edit().putBoolean("recents", v)
        if (!v) edit.remove("last_server")
        edit.apply()
    }
    fun lastServer(c: Context) = p(c).getString("last_server", "") ?: ""
    fun setLastServer(c: Context, host: String) = p(c).edit().putString("last_server", host).apply()
    fun geolocation(c: Context) = p(c).getBoolean("geolocation", true)
    fun setGeolocation(c: Context, v: Boolean) = p(c).edit().putBoolean("geolocation", v).apply()
    fun bypassLan(c: Context) = p(c).getBoolean("bypass_lan", false)
    fun setBypassLan(c: Context, v: Boolean) = p(c).edit().putBoolean("bypass_lan", v).apply()
    fun bypassList(c: Context) = p(c).getString("bypass_list", "") ?: ""
    fun setBypassList(c: Context, v: String) = p(c).edit().putString("bypass_list", v).apply()
    fun chain(c: Context) = p(c).getString("chain", "") ?: ""
    fun setChain(c: Context, v: String) = p(c).edit().putString("chain", v).apply()
    fun rootRouting(c: Context) = p(c).getBoolean("root_routing", false)
    fun setRootRouting(c: Context, v: Boolean) = p(c).edit().putBoolean("root_routing", v).apply()
    fun dnsProfile(c: Context) = p(c).getString("dns_profile", DnsPolicy.AUTO) ?: DnsPolicy.AUTO
    fun setDnsProfile(c: Context, value: String) = p(c).edit().putString("dns_profile", value).apply()
    fun customDns(c: Context) = p(c).getString("custom_dns", "") ?: ""
    fun setCustomDns(c: Context, value: String) = p(c).edit().putString("custom_dns", value).apply()
    fun blockAds(c: Context) = p(c).getBoolean("block_ads", false)
    fun setBlockAds(c: Context, value: Boolean) = p(c).edit().putBoolean("block_ads", value).apply()
    fun notificationAsked(c: Context) = p(c).getBoolean("notification_asked", false)
    fun setNotificationAsked(c: Context) = p(c).edit().putBoolean("notification_asked", true).apply()
    fun apps(c: Context) = p(c).getStringSet("apps", emptySet())?.toSet() ?: emptySet()
    fun setApps(c: Context, v: Set<String>) = p(c).edit().putStringSet("apps", v).apply()

    fun gameMode(c: Context) = p(c).getBoolean("game_mode", false)
    fun setGameMode(c: Context, v: Boolean) = p(c).edit().putBoolean("game_mode", v).apply()
    fun udpRelay(c: Context) = p(c).getBoolean("udp_relay", false)
    fun setUdpRelay(c: Context, v: Boolean) = p(c).edit().putBoolean("udp_relay", v).apply()
    fun warp(c: Context) = p(c).getBoolean("warp", false)
    fun setWarp(c: Context, v: Boolean) = p(c).edit().putBoolean("warp", v).apply()
    fun warpEndpoint(c: Context) = p(c).getString("warp_endpoint", "") ?: ""
    fun setWarpEndpoint(c: Context, v: String) = p(c).edit().putString("warp_endpoint", v).apply()
    fun warpIdentity(c: Context) = p(c).getString("warp_identity", "") ?: ""
    fun setWarpIdentity(c: Context, v: String) = p(c).edit().putString("warp_identity", v).apply()
    fun killSwitch(c: Context) = p(c).getBoolean("kill_switch", false)
    fun setKillSwitch(c: Context, v: Boolean) = p(c).edit().putBoolean("kill_switch", v).apply()

    fun advanced(c: Context, name: String) = p(c).getBoolean("adv_$name", false)
    fun setAdvanced(c: Context, name: String, v: Boolean) = p(c).edit().putBoolean("adv_$name", v).apply()
    fun advancedParam(c: Context, name: String) = p(c).getString("advp_$name", "") ?: ""
    fun setAdvancedParam(c: Context, name: String, v: String) = p(c).edit().putString("advp_$name", v).apply()

    fun coreEnabled(c: Context): Boolean = gameMode(c) || udpRelay(c) || warp(c) ||
        ADVANCED.any { advanced(c, it) }

    fun updateVersion(c: Context) = p(c).getString("update_version", "") ?: ""
    fun updateUrl(c: Context) = p(c).getString("update_url", "") ?: ""
    fun setUpdate(c: Context, version: String, url: String) = p(c).edit()
        .putString("update_version", version)
        .putString("update_url", url)
        .apply()

    fun clearUpdate(c: Context) = p(c).edit().remove("update_version").remove("update_url").apply()
    fun notifiedUpdate(c: Context) = p(c).getString("update_notified", "") ?: ""
    fun setNotifiedUpdate(c: Context, version: String) = p(c).edit().putString("update_notified", version).apply()
    fun updateCheckedAt(c: Context) = p(c).getLong("update_checked_at", 0L)
    fun setUpdateCheckedAt(c: Context, value: Long) = p(c).edit().putLong("update_checked_at", value).apply()

    fun wrap(base: Context): Context {
        val conf = Configuration(base.resources.configuration)
        val locale = when (lang(base)) {
            "fa" -> Locale("fa")
            "en" -> Locale("en")
            else -> null
        }
        if (locale != null) {
            conf.setLocale(locale)
            conf.setLayoutDirection(locale)
        }
        return base.createConfigurationContext(conf)
    }
}
