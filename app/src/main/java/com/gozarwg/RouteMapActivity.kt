package com.gozarwg

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale

/**
 * Full-screen offline tunnel route. Its world geometry is bundled with the application and is
 * rendered by [WorldRouteView]; no OSM, web map, tile cache, or map network call is involved.
 */
class RouteMapActivity : BaseActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var routeMap: WorldRouteView
    private lateinit var originValue: TextView
    private lateinit var exitValue: TextView
    private lateinit var routeBadge: TextView
    private lateinit var sessionValue: TextView
    private lateinit var originFlag: CountryFlagView
    private lateinit var exitFlag: CountryFlagView

    private val refresher = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 130L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_route_map)
        routeMap = findViewById(R.id.fullWorldRoute)
        originValue = findViewById(R.id.tvMapOrigin)
        exitValue = findViewById(R.id.tvMapExit)
        routeBadge = findViewById(R.id.tvMapRouteBadge)
        sessionValue = findViewById(R.id.tvMapSession)
        originFlag = findViewById(R.id.ivMapOriginFlag)
        exitFlag = findViewById(R.id.ivMapExitFlag)
        findViewById<View>(R.id.btnMapBack).setOnClickListener { finish() }
        applyInsets()
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(refresher)
        handler.post(refresher)
    }

    override fun onStop() {
        handler.removeCallbacks(refresher)
        super.onStop()
    }

    private fun applyInsets() {
        val root = findViewById<View>(R.id.routeMapRoot)
        val back = findViewById<View>(R.id.btnMapBack)
        val header = findViewById<View>(R.id.mapHeader)
        val information = findViewById<View>(R.id.mapInformation)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            (back.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.leftMargin = bars.left + dp(16)
                it.topMargin = bars.top + dp(12)
                back.layoutParams = it
            }
            (header.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.leftMargin = bars.left + dp(20)
                it.rightMargin = bars.right + dp(20)
                it.topMargin = bars.top + dp(72)
                header.layoutParams = it
            }
            (information.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.leftMargin = bars.left + dp(20)
                it.rightMargin = bars.right + dp(20)
                it.bottomMargin = bars.bottom + dp(18)
                information.layoutParams = it
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun foxLive(): Boolean = FoxLink.connected && !Vpn.up

    private fun foxExit(): IpInfo? {
        val place = FoxLink.location ?: return null
        val ip = FoxLink.exitIp
        if (ip.isEmpty() && place.code.isEmpty()) return null
        return IpInfo(ip = ip, city = place.city, country = place.country, code = place.code, provider = "Firefox VPN")
    }

    private fun render() {
        if (!::routeMap.isInitialized) return
        val fox = foxLive()
        val origin = if (fox) localeOrigin() else Vpn.originInfo ?: localeOrigin()
        val exit = if (fox) foxExit() else Vpn.ipInfo
        if (!fox && Vpn.up && exit == null) fetchExit()

        val routeToken = listOf(
            if (fox) "fox" else Vpn.profile?.id.orEmpty(),
            if (fox) FoxLink.since.toString() else Vpn.since.toString(),
            origin?.ip.orEmpty(), origin?.code.orEmpty(),
            exit?.ip.orEmpty(), exit?.code.orEmpty()
        ).joinToString("|")
        routeMap.setEndpoints(origin?.code, exit?.code, routeToken)
        val rx = if (fox) FoxLink.downRate else if (Vpn.up) Vpn.downSpeed else 0.0
        val tx = if (fox) FoxLink.upRate else if (Vpn.up) Vpn.upSpeed else 0.0
        routeMap.setTraffic(rx, tx)
        originFlag.countryCode = origin?.code
        exitFlag.countryCode = exit?.code

        val originLabel = locationLabel(origin, getString(R.string.map_unknown))
        val exitLabel = locationLabel(exit, getString(R.string.map_locating))
        originValue.text = originLabel
        exitValue.text = exitLabel
        routeBadge.text = getString(R.string.map_route_badge, shortLabel(originLabel), shortLabel(exitLabel))
        val provider = exit?.provider?.takeIf { it.isNotBlank() }
        sessionValue.text = when {
            fox -> locationLabel(foxExit(), getString(R.string.map_session_live)) + " · " + sessionSeconds()
            !Vpn.up -> getString(R.string.map_not_connected)
            Vpn.latencyChecking -> provider?.let { getString(R.string.map_session_provider_live, it) }
                ?: getString(R.string.map_session_checking)
            Vpn.latencyMs > 0 -> provider?.let { getString(R.string.map_session_provider_ping, it, Vpn.latencyMs) }
                ?: getString(R.string.map_session_ping, Vpn.latencyMs)
            provider != null -> getString(R.string.map_session_provider_live, provider)
            Vpn.profile?.name?.isNotBlank() == true -> Vpn.profile!!.name
            else -> getString(R.string.map_session_live)
        }
    }

    /** IP metadata is live tunnel metadata, not map imagery; Vpn owns the shared lookup/backoff. */
    private fun fetchExit() = Vpn.refreshExitInfo()

    private fun localeOrigin(): IpInfo? {
        val country = Locale.getDefault().country.uppercase()
        if (country.length != 2) return null
        return IpInfo(ip = "", city = "", country = country, code = country, provider = "")
    }

    private fun locationLabel(info: IpInfo?, fallback: String): String {
        if (info == null) return fallback
        return listOf(info.city, info.country).filter { it.isNotBlank() }.joinToString(", ")
            .ifBlank { info.code.ifBlank { fallback } }
    }

    private fun sessionSeconds(): String {
        val total = ((System.currentTimeMillis() - FoxLink.since) / 1000L).coerceAtLeast(0)
        return String.format(java.util.Locale.US, "%02d:%02d", total / 60, total % 60)
    }

    private fun shortLabel(value: String): String = value.substringBefore(',').trim()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
