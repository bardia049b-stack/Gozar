package com.beofoq.wg

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object Store {
    private fun p(c: Context) = c.getSharedPreferences("store", Context.MODE_PRIVATE)

    fun lang(c: Context) = p(c).getString("lang", "") ?: ""
    fun setLang(c: Context, v: String) = p(c).edit().putString("lang", v).apply()
    fun theme(c: Context) = p(c).getString("theme", "auto") ?: "auto"
    fun setTheme(c: Context, v: String) = p(c).edit().putString("theme", v).apply()
    fun kill(c: Context) = p(c).getBoolean("kill", false)
    fun setKill(c: Context, v: Boolean) = p(c).edit().putBoolean("kill", v).apply()
    fun auto(c: Context) = p(c).getBoolean("auto", false)
    fun setAuto(c: Context, v: Boolean) = p(c).edit().putBoolean("auto", v).apply()
    fun lan(c: Context) = p(c).getBoolean("lan", true)
    fun setLan(c: Context, v: Boolean) = p(c).edit().putBoolean("lan", v).apply()
    fun proxy(c: Context) = p(c).getBoolean("proxy", false)
    fun setProxy(c: Context, v: Boolean) = p(c).edit().putBoolean("proxy", v).apply()
    fun proxyPort(c: Context) = p(c).getInt("proxy_port", 10808)
    fun setProxyPort(c: Context, v: Int) = p(c).edit().putInt("proxy_port", v).apply()
    fun splitMode(c: Context) = p(c).getInt("split_mode", 0)
    fun setSplitMode(c: Context, v: Int) = p(c).edit().putInt("split_mode", v).apply()
    fun splitApps(c: Context): Set<String> = p(c).getStringSet("split_apps", emptySet()) ?: emptySet()
    fun setSplitApps(c: Context, v: Set<String>) = p(c).edit().putStringSet("split_apps", v).apply()
    fun selected(c: Context) = p(c).getString("selected", "") ?: ""
    fun setSelected(c: Context, v: String) = p(c).edit().putString("selected", v).apply()

    fun wrap(base: Context): Context {
        val conf = Configuration(base.resources.configuration)
        val l = lang(base)
        val locale = when (l) {
            "fa" -> Locale("fa")
            "en" -> Locale("en")
            else -> null
        }
        if (locale != null) {
            conf.setLocale(locale)
            conf.setLayoutDirection(locale)
        }
        val night = when (theme(base)) {
            "light" -> Configuration.UI_MODE_NIGHT_NO
            "dark" -> Configuration.UI_MODE_NIGHT_YES
            else -> conf.uiMode and Configuration.UI_MODE_NIGHT_MASK
        }
        conf.uiMode = (conf.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(conf)
    }
}
