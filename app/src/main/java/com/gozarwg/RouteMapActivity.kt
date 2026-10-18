package com.gozarwg

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale

/** Full-screen connected-route view. It receives no sample data of its own: every packet shown is
 * driven by the VPN monitor's real upload/download counters. */
class RouteMapActivity : BaseActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var world: WorldRouteView

    private val refresher = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 850L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_route_map)
        world = findViewById(R.id.fullWorldRoute)
        findViewById<View>(R.id.btnMapBack).setOnClickListener { finish() }

        val root = findViewById<View>(R.id.routeMapRoot)
        val back = findViewById<View>(R.id.btnMapBack)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            (back.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.leftMargin = bars.left + dp(16)
                params.topMargin = bars.top + dp(16)
                back.layoutParams = params
            }
            insets
        }
        render()
    }

    override fun onStart() {
        super.onStart()
        Vpn.listener = { render() }
        handler.removeCallbacks(refresher)
        handler.post(refresher)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(refresher)
        Vpn.listener = null
    }

    private fun render() {
        if (!::world.isInitialized) return
        world.state = when {
            Vpn.up && Vpn.handshakeOk -> ConnectControl.ON
            Vpn.up || Vpn.connecting -> ConnectControl.BUSY
            else -> ConnectControl.OFF
        }
        world.originCode = Vpn.originInfo?.code.orEmpty().ifBlank { localeCountry() }
        world.exitCode = Vpn.ipInfo?.code.orEmpty()
        world.downloadBytesPerSecond = Vpn.downSpeed
        world.uploadBytesPerSecond = Vpn.upSpeed
    }

    private fun localeCountry(): String {
        val configured = resources.configuration.locales[0]
        return configured.country.ifBlank {
            Locale.getDefault().country.ifBlank {
                if (configured.language.equals("fa", true)) "IR" else ""
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
