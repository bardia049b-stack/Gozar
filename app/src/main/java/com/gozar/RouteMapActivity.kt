package com.gozar

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale

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

    private fun exitInfo(): IpInfo? {
        val place = FoxLink.location ?: return null
        val ip = FoxLink.exitIp
        if (ip.isEmpty() && place.code.isEmpty()) return null
        return IpInfo(ip = ip, city = place.city, country = place.country, code = place.code, provider = "Firefox VPN")
    }

    private fun origin(): IpInfo = IpInfo(ip = "", city = "", country = ipCountry("IR"), code = "IR", provider = "")

    private fun render() {
        if (!this::routeMap.isInitialized) return
        val origin = origin()
        val exit = exitInfo()
        val token = listOf(
            if (FoxLink.connected) FoxLink.since.toString() else "idle",
            origin.code,
            exit?.ip.orEmpty(),
            exit?.code.orEmpty()
        ).joinToString("|")
        routeMap.setEndpoints(origin.code, exit?.code, token)
        routeMap.setTraffic(if (FoxLink.connected) FoxLink.downRate else 0.0, if (FoxLink.connected) FoxLink.upRate else 0.0)
        originFlag.countryCode = origin.code
        exitFlag.countryCode = exit?.code

        val originLabel = locationLabel(origin, getString(R.string.map_unknown))
        val exitLabel = locationLabel(exit, getString(R.string.map_locating))
        originValue.text = originLabel
        exitValue.text = exitLabel
        routeBadge.text = getString(R.string.map_route_badge, shortLabel(originLabel), shortLabel(exitLabel))
        sessionValue.text = when {
            !FoxLink.connected -> getString(R.string.map_not_connected)
            else -> locationLabel(exit, getString(R.string.map_session_live)) + " · " + sessionSeconds()
        }
    }

    private fun locationLabel(info: IpInfo?, fallback: String): String {
        if (info == null) return fallback
        return listOf(info.city, info.country).filter { it.isNotBlank() }.joinToString(", ")
            .ifBlank { info.code.ifBlank { fallback } }
    }

    private fun sessionSeconds(): String {
        val total = FoxLink.elapsed().coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
    }

    private fun ipCountry(code: String): String =
        Locale("", code).getDisplayCountry(Locale.ENGLISH).ifBlank { code }

    private fun shortLabel(value: String): String = value.substringBefore(',').trim()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
