package com.gozarwg

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.min

class WorldRouteView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var state = ConnectControl.OFF
        set(value) {
            if (field == value) return
            field = value
            if (value == ConnectControl.ON) revealRoute() else if (value == ConnectControl.OFF) reveal = 0f
            sync()
            invalidate()
        }

    var originCode = ""
        set(value) {
            if (field == value) return
            field = value.uppercase().take(2)
            invalidate()
        }

    var exitCode = ""
        set(value) {
            if (field == value) return
            field = value.uppercase().take(2)
            if (state == ConnectControl.ON) revealRoute()
            invalidate()
        }

    private data class Country(val code: String, val x: Float, val y: Float)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val route = Path()
    private val shownRoute = Path()
    private val routeMeasure = PathMeasure()
    private val map = Path()
    private val position = FloatArray(2)
    private val tangent = FloatArray(2)
    private val orbitInner = RectF()
    private val orbitOuter = RectF()
    private val countries = ArrayList<Country>()
    private val countriesByCode = HashMap<String, Country>()
    private var accent = 0
    private var origin = 0
    private var ink = 0
    private var reveal = 0f
    private var phase = 0f
    private var stamp = 0L
    private var running = false
    private var revealAnimator: ValueAnimator? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || !running) return
            val now = System.nanoTime()
            val elapsed = if (stamp == 0L) 16f else ((now - stamp) / 1_000_000f).coerceIn(1f, 42f)
            stamp = now
            phase = (phase + elapsed / if (state == ConnectControl.BUSY) 2100f else 5200f) % 1f
            invalidate()
            postOnAnimation(this)
        }
    }

    init {
        parse()
        updateColors()
        setWillNotDraw(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateColors()
        sync()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        removeCallbacks(ticker)
        revealAnimator?.cancel()
    }

    private fun sync() {
        val animate = state == ConnectControl.BUSY || state == ConnectControl.ON
        if (animate && !running) {
            running = true
            stamp = 0L
            postOnAnimation(ticker)
        } else if (!animate && running) {
            running = false
            removeCallbacks(ticker)
        }
    }

    private fun revealRoute() {
        revealAnimator?.cancel()
        if (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) {
            reveal = 1f
            return
        }
        reveal = 0f
        revealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100L
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                reveal = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0 || countries.isEmpty()) return
        val scale = min(width / WORLD_WIDTH, height / WORLD_HEIGHT)
        if (scale <= 0f) return
        val offsetX = (width - WORLD_WIDTH * scale) / 2f
        val offsetY = (height - WORLD_HEIGHT * scale) / 2f
        val activeColor = accent
        val originColor = origin
        val mapInk = ink
        val active = state == ConnectControl.ON
        val searching = state == ConnectControl.BUSY
        val strength = when {
            active -> 1f
            searching -> 0.94f
            else -> 0.82f
        }

        fill.color = alpha(mapInk, 8)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)

        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        val px = 1f / scale
        drawGrid(canvas, mapInk, px)
        fill.color = alpha(mapInk, (34f * strength).toInt())
        canvas.drawPath(map, fill)
        stroke.color = alpha(mapInk, (138f * strength).toInt())
        stroke.strokeWidth = 1.1f * px
        stroke.pathEffect = null
        canvas.drawPath(map, stroke)

        val origin = country(originCode)
        val exit = country(exitCode)
        origin?.let { drawMarker(canvas, it, originColor, px, active) }
        exit?.let { drawMarker(canvas, it, activeColor, px, active || searching) }
        if (origin != null && exit != null && (active || searching)) {
            drawRoute(canvas, origin, exit, activeColor, px, active)
        } else if (exit != null && (active || searching)) {
            drawOrbit(canvas, exit, activeColor, px, active)
        }
        canvas.restore()
    }

    private fun drawGrid(canvas: Canvas, ink: Int, px: Float) {
        stroke.color = alpha(ink, 28)
        stroke.strokeWidth = 0.8f * px
        stroke.pathEffect = null
        var longitude = 0f
        while (longitude <= WORLD_WIDTH) {
            canvas.drawLine(longitude, 0f, longitude, WORLD_HEIGHT, stroke)
            longitude += WORLD_WIDTH / 12f
        }
        var latitude = 0f
        while (latitude <= WORLD_HEIGHT) {
            canvas.drawLine(0f, latitude, WORLD_WIDTH, latitude, stroke)
            latitude += WORLD_HEIGHT / 6f
        }
    }

    private fun drawMarker(canvas: Canvas, country: Country, color: Int, px: Float, live: Boolean) {
        val pulse = if (live) 0.5f + 0.5f * kotlin.math.sin(phase * Math.PI.toFloat() * 2f) else 0f
        fill.color = alpha(color, (26 + pulse * 30).toInt())
        canvas.drawCircle(country.x, country.y, (18f + pulse * 8f) * px, fill)
        fill.color = alpha(color, 190)
        canvas.drawCircle(country.x, country.y, 4f * px, fill)
        stroke.color = alpha(color, 220)
        stroke.strokeWidth = 1.4f * px
        canvas.drawCircle(country.x, country.y, 9f * px, stroke)
    }

    private fun drawRoute(canvas: Canvas, origin: Country, exit: Country, color: Int, px: Float, live: Boolean) {
        val dx = exit.x - origin.x
        val dy = exit.y - origin.y
        val length = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (length < 1f) return
        val bow = length * 0.22f
        val controlX = (origin.x + exit.x) / 2f - dy / length * bow
        val controlY = (origin.y + exit.y) / 2f + dx / length * bow
        route.reset()
        route.moveTo(origin.x, origin.y)
        route.quadTo(controlX, controlY, exit.x, exit.y)
        routeMeasure.setPath(route, false)
        val amount = routeMeasure.length * if (live) reveal else 0.38f
        shownRoute.reset()
        routeMeasure.getSegment(0f, amount, shownRoute, true)
        stroke.pathEffect = null
        stroke.color = alpha(color, 28)
        stroke.strokeWidth = 9f * px
        canvas.drawPath(shownRoute, stroke)
        stroke.color = alpha(color, 120)
        stroke.strokeWidth = 3.2f * px
        canvas.drawPath(shownRoute, stroke)
        stroke.color = alpha(color, 238)
        stroke.strokeWidth = 1.2f * px
        canvas.drawPath(shownRoute, stroke)
        if (amount <= 0f) return
        val travel = if (live && reveal > 0.98f) routeMeasure.length * phase else amount
        routeMeasure.getPosTan(travel, position, tangent)
        fill.color = alpha(color, 68)
        canvas.drawCircle(position[0], position[1], 8f * px, fill)
        fill.color = color
        canvas.drawCircle(position[0], position[1], 3.2f * px, fill)
    }

    private fun drawOrbit(canvas: Canvas, country: Country, color: Int, px: Float, live: Boolean) {
        stroke.color = alpha(color, if (live) 180 else 110)
        stroke.strokeWidth = 1.2f * px
        stroke.pathEffect = null
        val inner = 21f * px
        val outer = 33f * px
        orbitInner.set(country.x - inner, country.y - inner, country.x + inner, country.y + inner)
        orbitOuter.set(country.x - outer, country.y - outer, country.x + outer, country.y + outer)
        canvas.save()
        canvas.rotate(phase * 360f, country.x, country.y)
        canvas.drawArc(orbitInner, -92f, 230f, false, stroke)
        canvas.drawArc(orbitOuter, 88f, 230f, false, stroke)
        canvas.restore()
    }

    private fun country(code: String): Country? = countriesByCode[code]

    private fun updateColors() {
        accent = color(R.color.m3_ok)
        origin = color(R.color.m3_primary)
        ink = color(R.color.m3_on_surface_variant)
    }

    private fun parse() {
        val bytes = try {
            resources.openRawResource(R.raw.world).use { it.readBytes() }
        } catch (e: Exception) {
            return
        }
        if (bytes.size <= 6 || bytes[0] != 'Z'.code.toByte() || bytes[1] != 'W'.code.toByte() ||
            bytes[2] != 'M'.code.toByte() || bytes[3] != '1'.code.toByte()) {
            return
        }
        var offset = 4
        fun u8(): Int = bytes[offset++].toInt() and 0xFF
        fun u16(): Int {
            val value = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2
            return value
        }
        try {
            repeat(u16()) {
                val code = "${bytes[offset++].toInt().toChar()}${bytes[offset++].toInt().toChar()}"
                val x = u16().toFloat()
                val y = u16().toFloat() * 0.5f
                val country = Country(code, x, y)
                countries += country
                countriesByCode[code] = country
                repeat(u8()) {
                    val points = u16()
                    if (points < 3) {
                        repeat(points) { u16(); u16() }
                    } else {
                        map.moveTo(u16().toFloat(), u16().toFloat() * 0.5f)
                        repeat(points - 1) { map.lineTo(u16().toFloat(), u16().toFloat() * 0.5f) }
                        map.close()
                    }
                }
            }
        } catch (e: Exception) {
            countries.clear()
            countriesByCode.clear()
            map.reset()
        }
    }

    private fun alpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun color(id: Int): Int = ContextCompat.getColor(context, id)

    companion object {
        private const val WORLD_WIDTH = 65535f
        private const val WORLD_HEIGHT = WORLD_WIDTH / 2f
    }
}
