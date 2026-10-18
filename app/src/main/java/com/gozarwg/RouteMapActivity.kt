package com.gozarwg

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.ln
import kotlin.math.max

/**
 * Full-screen online OpenStreetMap route. The base map is loaded from OSM tiles rather than a
 * custom country illustration. Markers and moving packets are layered from the live VPN session.
 */
class RouteMapActivity : BaseActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var map: MapView
    private lateinit var route: Polyline
    private lateinit var originMarker: Marker
    private lateinit var exitMarker: Marker
    private lateinit var downloadPacket: Marker
    private lateinit var uploadPacket: Marker
    private var routeKey = ""
    private var phase = 0.0

    private val refresher = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 120L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A named agent is required by OpenStreetMap's public tile-use policy.
        Configuration.getInstance().userAgentValue = "$packageName/2.3"
        setContentView(R.layout.activity_route_map)
        map = findViewById(R.id.fullWorldRoute)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.setTilesScaledToDpi(true)
        map.controller.setZoom(2.2)
        map.controller.setCenter(GeoPoint(24.0, 12.0))
        addOverlays()
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
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(refresher)
        handler.post(refresher)
        map.onResume()
    }

    override fun onStop() {
        map.onPause()
        handler.removeCallbacks(refresher)
        super.onStop()
    }

    private fun addOverlays() {
        route = Polyline().apply {
            outlinePaint.color = ContextCompat.getColor(this@RouteMapActivity, R.color.m3_ok)
            outlinePaint.strokeWidth = dp(4).toFloat()
            setGeodesic(true)
        }
        originMarker = marker(R.drawable.map_marker_origin)
        exitMarker = marker(R.drawable.map_marker_exit)
        downloadPacket = marker(R.drawable.map_marker_packet)
        uploadPacket = marker(R.drawable.map_marker_packet_up)
        map.overlays.add(route)
        map.overlays.add(originMarker)
        map.overlays.add(exitMarker)
        map.overlays.add(downloadPacket)
        map.overlays.add(uploadPacket)
    }

    private fun marker(drawable: Int): Marker = Marker(map).apply {
        icon = AppCompatResources.getDrawable(this@RouteMapActivity, drawable)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        infoWindow = null
        isEnabled = false
        alpha = 1f
    }

    private fun render() {
        if (!::map.isInitialized) return
        val origin = pointOf(Vpn.originInfo)
        val exit = pointOf(Vpn.ipInfo)
        if (origin != null) originMarker.position = origin
        originMarker.isEnabled = origin != null
        if (exit != null) exitMarker.position = exit
        exitMarker.isEnabled = exit != null

        if (origin != null && exit != null) {
            val key = "${origin.latitude},${origin.longitude}:${exit.latitude},${exit.longitude}"
            if (key != routeKey) {
                routeKey = key
                route.setPoints(listOf(origin, exit))
                fitRoute(origin, exit)
            }
            movePackets(origin, exit)
        } else {
            downloadPacket.isEnabled = false
            uploadPacket.isEnabled = false
            if (routeKey.isNotEmpty()) {
                routeKey = ""
                route.setPoints(emptyList())
            }
        }
        map.invalidate()
    }

    /** Only moves a dot when the corresponding tunnel byte-rate is real and non-zero. */
    private fun movePackets(origin: GeoPoint, exit: GeoPoint) {
        val down = Vpn.downSpeed
        val up = Vpn.upSpeed
        val activity = max(down, up)
        if (activity > 1.0) {
            phase = (phase + 0.006 + ln(activity + 1.0) / 140.0) % 1.0
        }
        if (down > 1.0) {
            // Download travels from the tunnel exit towards the local origin.
            downloadPacket.position = interpolate(exit, origin, phase)
            downloadPacket.isEnabled = true
        } else {
            downloadPacket.isEnabled = false
        }
        if (up > 1.0) {
            // Upload travels from the local origin towards the tunnel exit.
            uploadPacket.position = interpolate(origin, exit, phase)
            uploadPacket.isEnabled = true
        } else {
            uploadPacket.isEnabled = false
        }
    }

    private fun fitRoute(origin: GeoPoint, exit: GeoPoint) {
        map.post {
            if (origin.latitude == exit.latitude && origin.longitude == exit.longitude) {
                map.controller.setZoom(4.0)
                map.controller.setCenter(origin)
            } else {
                map.zoomToBoundingBox(BoundingBox.fromGeoPoints(listOf(origin, exit)), true)
            }
        }
    }

    private fun interpolate(from: GeoPoint, to: GeoPoint, fraction: Double): GeoPoint {
        val value = fraction.coerceIn(0.0, 1.0)
        return GeoPoint(
            from.latitude + (to.latitude - from.latitude) * value,
            from.longitude + (to.longitude - from.longitude) * value
        )
    }

    private fun pointOf(info: IpInfo?): GeoPoint? {
        if (info == null) return null
        val latitude = info.latitude
        val longitude = info.longitude
        if (latitude != null && longitude != null) return GeoPoint(latitude, longitude)
        return fallbackCountryCenters[info.code.uppercase()]?.let { GeoPoint(it.first, it.second) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        // Used only if a provider omits coordinates; the route is upgraded as soon as real IP
        // coordinates are available.
        val fallbackCountryCenters = mapOf(
            "IR" to (32.4 to 53.7), "TR" to (39.0 to 35.2), "NL" to (52.1 to 5.3),
            "DE" to (51.2 to 10.5), "US" to (39.8 to -98.6), "GB" to (54.0 to -2.0),
            "FR" to (46.2 to 2.2), "CA" to (56.1 to -106.3), "AE" to (24.3 to 54.4),
            "SE" to (62.0 to 15.0), "FI" to (64.5 to 26.0), "JP" to (36.2 to 138.3)
        )
    }
}
