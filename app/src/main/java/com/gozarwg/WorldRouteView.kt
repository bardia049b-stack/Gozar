package com.gozarwg

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Offline vector world map used by the connected-route screen.
 *
 * The country geometry is the AGPL-compatible adapted world data stored in world.bin.  Each
 * country's path is intentionally retained here rather than flattened: it lets the map clip a
 * country flag to its real boundary instead of showing a floating rectangular flag.
 */
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
            val normalized = value.uppercase().take(2)
            if (field == normalized) return
            field = normalized
            invalidate()
        }

    var exitCode = ""
        set(value) {
            val normalized = value.uppercase().take(2)
            if (field == normalized) return
            field = normalized
            if (state == ConnectControl.ON) revealRoute()
            invalidate()
        }

    /** Actual tunnel speed samples. Packets are only animated when the matching value is non-zero. */
    var downloadBytesPerSecond = 0.0
        set(value) {
            val next = value.coerceAtLeast(0.0)
            if (abs(field - next) < 0.5) return
            field = next
            invalidate()
        }

    var uploadBytesPerSecond = 0.0
        set(value) {
            val next = value.coerceAtLeast(0.0)
            if (abs(field - next) < 0.5) return
            field = next
            invalidate()
        }

    private data class Country(
        val code: String,
        val x: Float,
        val y: Float,
        val shape: Path,
        val bounds: RectF
    )

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val flagText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
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
            phase = (phase + elapsed / if (state == ConnectControl.BUSY) 2100f else 4300f) % 1f
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
            duration = 900L
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
        val sidePadding = min(width, height) * 0.035f
        val scale = min((width - sidePadding * 2f) / WORLD_WIDTH, (height - sidePadding * 2f) / WORLD_HEIGHT)
        if (scale <= 0f) return
        val offsetX = (width - WORLD_WIDTH * scale) / 2f
        val offsetY = (height - WORLD_HEIGHT * scale) / 2f
        val active = state == ConnectControl.ON
        val searching = state == ConnectControl.BUSY
        val strength = when {
            active -> 1f
            searching -> 0.94f
            else -> 0.78f
        }

        fill.color = alpha(ink, 5)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)

        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        val px = 1f / scale
        drawGrid(canvas, ink, px)
        fill.color = alpha(ink, (31f * strength).toInt())
        canvas.drawPath(map, fill)

        val originCountry = country(originCode)
        val exitCountry = country(exitCode)
        originCountry?.let { drawCountryFlag(canvas, it, px) }
        if (exitCountry != null && exitCountry !== originCountry) drawCountryFlag(canvas, exitCountry, px)

        stroke.color = alpha(ink, (158f * strength).toInt())
        stroke.strokeWidth = 1.05f * px
        stroke.pathEffect = null
        canvas.drawPath(map, stroke)

        originCountry?.let { drawMarker(canvas, it, origin, px, active || searching) }
        exitCountry?.let { drawMarker(canvas, it, accent, px, active || searching) }
        if (originCountry != null && exitCountry != null && (active || searching)) {
            drawRoute(canvas, originCountry, exitCountry, accent, px, active)
        } else if (exitCountry != null && (active || searching)) {
            drawOrbit(canvas, exitCountry, accent, px, active)
        }
        canvas.restore()
    }

    private fun drawGrid(canvas: Canvas, ink: Int, px: Float) {
        stroke.color = alpha(ink, 23)
        stroke.strokeWidth = 0.7f * px
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

    /** Draws the national flag inside the actual country outline. Iran uses a lion-and-sun flag. */
    private fun drawCountryFlag(canvas: Canvas, country: Country, px: Float) {
        if (country.bounds.isEmpty()) return
        canvas.save()
        canvas.clipPath(country.shape)
        if (country.code == "IR") {
            drawIranFlag(canvas, country.bounds)
        } else {
            drawNationalFlag(canvas, country.code, country.bounds)
        }
        canvas.restore()

        stroke.color = alpha(if (country.code == exitCode) accent else origin, 226)
        stroke.strokeWidth = 1.45f * px
        stroke.pathEffect = null
        canvas.drawPath(country.shape, stroke)
    }

    private fun drawNationalFlag(canvas: Canvas, code: String, bounds: RectF) {
        val spec = flagSpec(code)
        val height = bounds.height() / spec.colors.size
        spec.colors.forEachIndexed { index, color ->
            fill.color = color
            if (spec.vertical) {
                val width = bounds.width() / spec.colors.size
                canvas.drawRect(bounds.left + index * width, bounds.top, bounds.left + (index + 1) * width, bounds.bottom, fill)
            } else {
                canvas.drawRect(bounds.left, bounds.top + index * height, bounds.right, bounds.top + (index + 1) * height, fill)
            }
        }
        when (code) {
            "US" -> drawUsCanton(canvas, bounds)
            "JP" -> {
                fill.color = Color.rgb(188, 0, 45)
                canvas.drawCircle(bounds.centerX(), bounds.centerY(), min(bounds.width(), bounds.height()) * 0.23f, fill)
            }
            "TR" -> drawCrescent(canvas, bounds, Color.WHITE)
            else -> drawEmojiFlag(canvas, code, bounds)
        }
    }

    /** A real platform flag emoji is layered over the colour layout where Android provides it. */
    private fun drawEmojiFlag(canvas: Canvas, code: String, bounds: RectF) {
        val symbol = Ip.flag(code)
        if (symbol.isBlank()) return
        flagText.textSize = max(bounds.width() * 0.82f, bounds.height() * 1.45f)
        val metrics = flagText.fontMetrics
        flagText.color = Color.WHITE
        flagText.alpha = 215
        canvas.drawText(symbol, bounds.centerX(), bounds.centerY() - (metrics.ascent + metrics.descent) / 2f, flagText)
        flagText.alpha = 255
    }

    private data class FlagSpec(val colors: IntArray, val vertical: Boolean = false)

    private fun flagSpec(code: String): FlagSpec = when (code) {
        "NL" -> FlagSpec(intArrayOf(Color.rgb(174, 28, 40), Color.WHITE, Color.rgb(33, 70, 139)))
        "DE" -> FlagSpec(intArrayOf(Color.BLACK, Color.rgb(221, 0, 0), Color.rgb(255, 206, 0)))
        "FR" -> FlagSpec(intArrayOf(Color.rgb(0, 35, 149), Color.WHITE, Color.rgb(237, 41, 57)), true)
        "IT" -> FlagSpec(intArrayOf(Color.rgb(0, 146, 70), Color.WHITE, Color.rgb(206, 43, 55)), true)
        "BE" -> FlagSpec(intArrayOf(Color.BLACK, Color.rgb(253, 218, 37), Color.rgb(239, 51, 64)), true)
        "IE" -> FlagSpec(intArrayOf(Color.rgb(22, 155, 98), Color.WHITE, Color.rgb(255, 136, 62)), true)
        "RU" -> FlagSpec(intArrayOf(Color.WHITE, Color.rgb(0, 57, 166), Color.rgb(213, 43, 30)))
        "UA" -> FlagSpec(intArrayOf(Color.rgb(0, 87, 183), Color.rgb(255, 215, 0)))
        "PL" -> FlagSpec(intArrayOf(Color.WHITE, Color.rgb(220, 20, 60)))
        "AT" -> FlagSpec(intArrayOf(Color.rgb(237, 41, 57), Color.WHITE, Color.rgb(237, 41, 57)))
        "RO" -> FlagSpec(intArrayOf(Color.rgb(0, 43, 127), Color.rgb(252, 209, 22), Color.rgb(206, 17, 38)), true)
        "HU" -> FlagSpec(intArrayOf(Color.rgb(205, 42, 62), Color.WHITE, Color.rgb(67, 111, 68)))
        "BG" -> FlagSpec(intArrayOf(Color.WHITE, Color.rgb(0, 150, 110), Color.rgb(214, 38, 18)))
        "LT" -> FlagSpec(intArrayOf(Color.rgb(253, 185, 19), Color.rgb(0, 106, 68), Color.rgb(193, 39, 45)))
        "LV" -> FlagSpec(intArrayOf(Color.rgb(158, 48, 57), Color.WHITE, Color.rgb(158, 48, 57)))
        "EE" -> FlagSpec(intArrayOf(Color.rgb(0, 114, 206), Color.BLACK, Color.WHITE))
        "CA" -> FlagSpec(intArrayOf(Color.rgb(214, 0, 28), Color.WHITE, Color.rgb(214, 0, 28)), true)
        "MX" -> FlagSpec(intArrayOf(Color.rgb(0, 104, 71), Color.WHITE, Color.rgb(206, 17, 38)), true)
        "BR" -> FlagSpec(intArrayOf(Color.rgb(0, 156, 59)))
        "AR" -> FlagSpec(intArrayOf(Color.rgb(116, 172, 223), Color.WHITE, Color.rgb(116, 172, 223)))
        "AU", "NZ" -> FlagSpec(intArrayOf(Color.rgb(0, 36, 125)))
        "IN" -> FlagSpec(intArrayOf(Color.rgb(255, 153, 51), Color.WHITE, Color.rgb(19, 136, 8)))
        "JP" -> FlagSpec(intArrayOf(Color.WHITE))
        "KR" -> FlagSpec(intArrayOf(Color.WHITE))
        "CN" -> FlagSpec(intArrayOf(Color.rgb(222, 41, 16)))
        "TR" -> FlagSpec(intArrayOf(Color.rgb(227, 10, 23)))
        "SA" -> FlagSpec(intArrayOf(Color.rgb(0, 108, 53)))
        "AE" -> FlagSpec(intArrayOf(Color.rgb(0, 115, 47), Color.WHITE, Color.BLACK))
        "EG" -> FlagSpec(intArrayOf(Color.rgb(206, 17, 38), Color.WHITE, Color.BLACK))
        "ID" -> FlagSpec(intArrayOf(Color.rgb(206, 17, 38), Color.WHITE))
        "SG" -> FlagSpec(intArrayOf(Color.rgb(237, 41, 57), Color.WHITE))
        "SE" -> FlagSpec(intArrayOf(Color.rgb(0, 106, 167)))
        "NO" -> FlagSpec(intArrayOf(Color.rgb(186, 12, 47)))
        "FI" -> FlagSpec(intArrayOf(Color.WHITE))
        "GB" -> FlagSpec(intArrayOf(Color.rgb(1, 33, 105)))
        "US" -> FlagSpec(IntArray(13) { if (it % 2 == 0) Color.rgb(178, 34, 52) else Color.WHITE })
        else -> {
            val hash = code.fold(0) { sum, c -> sum * 31 + c.code }
            val first = Color.rgb(45 + (hash ushr 16 and 0x5F), 65 + (hash ushr 8 and 0x5F), 105 + (hash and 0x5F))
            FlagSpec(intArrayOf(first, Color.WHITE, Color.rgb(20, 70, 120)))
        }
    }

    private fun drawUsCanton(canvas: Canvas, bounds: RectF) {
        fill.color = Color.rgb(60, 59, 110)
        canvas.drawRect(bounds.left, bounds.top, bounds.left + bounds.width() * 0.45f, bounds.top + bounds.height() * 0.54f, fill)
    }

    private fun drawCrescent(canvas: Canvas, bounds: RectF, color: Int) {
        val r = min(bounds.width(), bounds.height()) * 0.23f
        fill.color = color
        canvas.drawCircle(bounds.centerX() - r * 0.12f, bounds.centerY(), r, fill)
        fill.color = Color.rgb(227, 10, 23)
        canvas.drawCircle(bounds.centerX() + r * 0.30f, bounds.centerY() - r * 0.06f, r * 0.84f, fill)
    }

    /** Historic Iranian tricolour with a compact hand-drawn lion and sun in its white band. */
    private fun drawIranFlag(canvas: Canvas, bounds: RectF) {
        val h = bounds.height() / 3f
        fill.color = Color.rgb(35, 123, 68)
        canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.top + h, fill)
        fill.color = Color.WHITE
        canvas.drawRect(bounds.left, bounds.top + h, bounds.right, bounds.top + h * 2f, fill)
        fill.color = Color.rgb(220, 45, 45)
        canvas.drawRect(bounds.left, bounds.top + h * 2f, bounds.right, bounds.bottom, fill)

        val unit = min(bounds.width() / 16f, bounds.height() / 7f)
        if (unit <= 0f) return
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val gold = Color.rgb(188, 139, 41)
        fill.color = gold
        canvas.drawCircle(cx + unit * 2.4f, cy - unit * 0.42f, unit * 1.32f, fill)
        stroke.color = gold
        stroke.strokeWidth = max(unit * 0.18f, 0.5f)
        for (i in 0 until 12) {
            val angle = Math.PI * 2.0 * i / 12.0
            val x1 = cx + unit * 2.4f + kotlin.math.cos(angle).toFloat() * unit * 1.55f
            val y1 = cy - unit * 0.42f + kotlin.math.sin(angle).toFloat() * unit * 1.55f
            val x2 = cx + unit * 2.4f + kotlin.math.cos(angle).toFloat() * unit * 2.1f
            val y2 = cy - unit * 0.42f + kotlin.math.sin(angle).toFloat() * unit * 2.1f
            canvas.drawLine(x1, y1, x2, y2, stroke)
        }

        val lion = Path().apply {
            moveTo(cx - unit * 4.1f, cy + unit * 0.75f)
            cubicTo(cx - unit * 3.4f, cy - unit * 1.0f, cx - unit * 1.0f, cy - unit * 1.25f, cx + unit * 0.55f, cy - unit * 0.58f)
            cubicTo(cx + unit * 1.15f, cy - unit * 0.4f, cx + unit * 1.55f, cy - unit * 0.68f, cx + unit * 1.9f, cy - unit * 1.2f)
            lineTo(cx + unit * 2.7f, cy - unit * 1.12f)
            lineTo(cx + unit * 2.1f, cy - unit * 0.38f)
            lineTo(cx + unit * 1.62f, cy + unit * 0.62f)
            lineTo(cx + unit * 0.78f, cy + unit * 0.85f)
            lineTo(cx + unit * 0.58f, cy + unit * 1.75f)
            lineTo(cx - unit * 0.05f, cy + unit * 1.75f)
            lineTo(cx - unit * 0.23f, cy + unit * 0.92f)
            lineTo(cx - unit * 1.85f, cy + unit * 0.98f)
            lineTo(cx - unit * 2.08f, cy + unit * 1.75f)
            lineTo(cx - unit * 2.72f, cy + unit * 1.75f)
            lineTo(cx - unit * 2.72f, cy + unit * 0.8f)
            cubicTo(cx - unit * 4.5f, cy + unit * 1.9f, cx - unit * 5.0f, cy - unit * 0.4f, cx - unit * 3.9f, cy - unit * 0.82f)
            cubicTo(cx - unit * 5.0f, cy + unit * 0.1f, cx - unit * 5.0f, cy + unit * 1.3f, cx - unit * 4.1f, cy + unit * 0.75f)
            close()
        }
        fill.color = gold
        canvas.drawPath(lion, fill)
        stroke.color = Color.rgb(128, 83, 19)
        stroke.strokeWidth = max(unit * 0.13f, 0.45f)
        canvas.drawPath(lion, stroke)
        canvas.drawLine(cx + unit * 0.7f, cy - unit * 0.18f, cx + unit * 3.35f, cy - unit * 2.75f, stroke)
    }

    private fun drawMarker(canvas: Canvas, country: Country, color: Int, px: Float, live: Boolean) {
        val pulse = if (live) 0.5f + 0.5f * kotlin.math.sin(phase * Math.PI.toFloat() * 2f) else 0f
        fill.color = alpha(color, (36 + pulse * 38).toInt())
        canvas.drawCircle(country.x, country.y, (19f + pulse * 8f) * px, fill)
        fill.color = alpha(color, 225)
        canvas.drawCircle(country.x, country.y, 4.2f * px, fill)
        stroke.color = Color.WHITE
        stroke.strokeWidth = 1.25f * px
        canvas.drawCircle(country.x, country.y, 4.2f * px, stroke)
    }

    private fun drawRoute(canvas: Canvas, originCountry: Country, exitCountry: Country, color: Int, px: Float, live: Boolean) {
        val dx = exitCountry.x - originCountry.x
        val dy = exitCountry.y - originCountry.y
        val length = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (length < 1f) return
        val bow = length * 0.22f
        val controlX = (originCountry.x + exitCountry.x) / 2f - dy / length * bow
        val controlY = (originCountry.y + exitCountry.y) / 2f + dx / length * bow
        route.reset()
        route.moveTo(originCountry.x, originCountry.y)
        route.quadTo(controlX, controlY, exitCountry.x, exitCountry.y)
        routeMeasure.setPath(route, false)
        val amount = routeMeasure.length * if (live) reveal else 0.38f
        shownRoute.reset()
        routeMeasure.getSegment(0f, amount, shownRoute, true)

        stroke.pathEffect = null
        stroke.color = alpha(color, 34)
        stroke.strokeWidth = 9f * px
        canvas.drawPath(shownRoute, stroke)
        stroke.color = alpha(color, 135)
        stroke.strokeWidth = 3f * px
        canvas.drawPath(shownRoute, stroke)
        stroke.color = alpha(color, 245)
        stroke.strokeWidth = 1.15f * px
        canvas.drawPath(shownRoute, stroke)
        if (live && reveal > 0.98f) drawTraffic(canvas, px)
    }

    /** These particles come from the VPN monitor's real byte/s samples; no traffic means no packet. */
    private fun drawTraffic(canvas: Canvas, px: Float) {
        val length = routeMeasure.length
        if (length <= 0f) return
        drawPackets(canvas, length, downloadBytesPerSecond, accent, px, reverse = true)
        drawPackets(canvas, length, uploadBytesPerSecond, origin, px, reverse = false)
    }

    private fun drawPackets(canvas: Canvas, length: Float, bytesPerSecond: Double, color: Int, px: Float, reverse: Boolean) {
        if (bytesPerSecond < 1.0) return
        val logarithmic = (ln(bytesPerSecond + 1.0) / ln(1024.0)).toFloat().coerceIn(0.1f, 2.0f)
        val count = (1 + logarithmic.toInt()).coerceIn(1, 3)
        val speed = 0.38f + logarithmic * 0.25f
        for (index in 0 until count) {
            val f = ((phase * speed + index.toFloat() / count) % 1f)
            val progress = if (reverse) 1f - f else f
            routeMeasure.getPosTan(length * progress, position, tangent)
            fill.color = alpha(color, 72)
            canvas.drawCircle(position[0], position[1], 9f * px, fill)
            fill.color = Color.WHITE
            canvas.drawCircle(position[0], position[1], 3.3f * px, fill)
            fill.color = color
            canvas.drawCircle(position[0], position[1], 1.9f * px, fill)
        }
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
                val shape = Path()
                repeat(u8()) {
                    val points = u16()
                    if (points < 3) {
                        repeat(points) { u16(); u16() }
                    } else {
                        val firstX = u16().toFloat()
                        val firstY = u16().toFloat() * 0.5f
                        map.moveTo(firstX, firstY)
                        shape.moveTo(firstX, firstY)
                        repeat(points - 1) {
                            val px = u16().toFloat()
                            val py = u16().toFloat() * 0.5f
                            map.lineTo(px, py)
                            shape.lineTo(px, py)
                        }
                        map.close()
                        shape.close()
                    }
                }
                val bounds = RectF()
                shape.computeBounds(bounds, true)
                val country = Country(code, x, y, shape, bounds)
                countries += country
                countriesByCode[code] = country
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
