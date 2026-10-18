package com.gozarwg

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Offline, gesture-driven world route view adapted from ZedSecure's AGPL-3.0-or-later
 * WorldMapCanvas and WorldMap implementations.  The compact vector map is bundled in
 * res/raw/world.bin; it never requests map tiles or other map network resources.
 */
class WorldRouteView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Country(
        val code: String,
        val anchorX: Float,
        val anchorY: Float,
        val rings: List<FloatArray>,
        val path: Path
    )

    private data class Point(val x: Float, val y: Float, val code: String)

    private val land = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val flagPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
    }
    private val flagBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val routePath = Path()
    private val arrowPath = Path()
    private val gridPath = Path()
    private val countries: List<Country> = readCountries()
    private val allLand = Path().apply { countries.forEach { addPath(it.path) } }

    private val surfaceColor = ContextCompat.getColor(context, R.color.map_surface)
    private val landColor = ContextCompat.getColor(context, R.color.map_land)
    private val gridColor = ContextCompat.getColor(context, R.color.map_grid)
    private val originColor = ContextCompat.getColor(context, R.color.map_origin)
    private val exitColor = ContextCompat.getColor(context, R.color.map_exit)

    private var origin: Point? = null
    private var exit: Point? = null
    private var originCode = ""
    private var exitCode = ""
    private var downloadBps = 0.0
    private var uploadBps = 0.0
    private var routeStartedAt = SystemClock.uptimeMillis()

    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var hasFrame = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (width == 0 || height == 0) return false
            val floor = wholeWorldScale()
            val next = (scale * detector.scaleFactor).coerceIn(floor, floor * 24f)
            val applied = if (scale == 0f) 1f else next / scale
            offsetX = detector.focusX - (detector.focusX - offsetX) * applied
            offsetY = detector.focusY - (detector.focusY - offsetY) * applied
            scale = next
            clampOffset()
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            offsetX -= distanceX
            offsetY -= distanceY
            clampOffset()
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            resetFrame()
            performClick()
            return true
        }
    })

    init {
        isClickable = true
        contentDescription = context.getString(R.string.live_map_content)
        buildGrid()
    }

    /** Sets the two ISO country anchors and returns the camera to the route framing. */
    fun setEndpoints(originCountry: String?, exitCountry: String?) {
        val nextOrigin = pointFor(originCountry)
        val nextExit = pointFor(exitCountry)
        val changed = nextOrigin?.code != origin?.code || nextExit?.code != exit?.code
        originCode = originCountry.orEmpty().trim().uppercase()
        exitCode = exitCountry.orEmpty().trim().uppercase()
        origin = nextOrigin
        exit = nextExit
        if (changed) {
            routeStartedAt = SystemClock.uptimeMillis()
            post { resetFrame() }
        } else {
            invalidate()
        }
    }

    /** Drives packet motion only from the actual tunnel throughput reported by [Vpn]. */
    fun setTraffic(download: Double, upload: Double) {
        downloadBps = download.coerceAtLeast(0.0)
        uploadBps = upload.coerceAtLeast(0.0)
        invalidate()
    }

    /** Double-tap uses the same route-aware automatic framing. */
    fun resetFrame() {
        if (width <= 0 || height <= 0) {
            hasFrame = false
            return
        }
        val focus = listOfNotNull(origin, exit)
        val whole = wholeWorldScale()
        if (focus.isEmpty()) {
            scale = whole
            offsetX = (width - WORLD_WIDTH * scale) / 2f
            offsetY = (height - WORLD_HEIGHT * scale) / 2f
        } else {
            val centerX = focus.map { it.x }.average().toFloat()
            val centerY = focus.map { it.y }.average().toFloat()
            scale = if (focus.size < 2) {
                whole * 2.6f
            } else {
                val spanX = (focus.maxOf { it.x } - focus.minOf { it.x }) * 1.9f + WORLD_WIDTH * 0.06f
                val spanY = (focus.maxOf { it.y } - focus.minOf { it.y }) * 2.4f + WORLD_HEIGHT * 0.06f
                min(width / max(spanX, 1f), height / max(spanY, 1f)).coerceIn(whole * 0.95f, whole * 4.5f)
            }
            offsetX = width / 2f - centerX * scale
            offsetY = height / 2f - centerY * scale
            clampOffset()
        }
        hasFrame = true
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!hasFrame || w != oldw || h != oldh) resetFrame()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val scaled = scaleDetector.onTouchEvent(event)
        val gestured = gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && !scaled && !gestured) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(surfaceColor)
        if (width <= 0 || height <= 0) return
        if (!hasFrame) resetFrame()

        val px: (Float) -> Float = { dp -> dp / scale }
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)

        // Geometry and color layers follow the ZedSecure world canvas: subtle graticule,
        // understated land, country route highlights, then the curved route itself.
        line.color = gridColor
        line.strokeWidth = px(1f)
        canvas.drawPath(gridPath, line)

        land.color = alpha(landColor, 0.10f)
        canvas.drawPath(allLand, land)
        outline.color = alpha(landColor, 0.28f)
        outline.strokeWidth = px(1.1f)
        canvas.drawPath(allLand, outline)

        countryPath(originCode)?.let { path ->
            land.color = alpha(originColor, 0.34f)
            canvas.drawPath(path, land)
            outline.color = alpha(originColor, 0.88f)
            outline.strokeWidth = px(1.7f)
            canvas.drawPath(path, outline)
        }
        countryPath(exitCode)?.let { path ->
            land.color = alpha(exitColor, 0.42f)
            canvas.drawPath(path, land)
            outline.color = exitColor
            outline.strokeWidth = px(2f)
            canvas.drawPath(path, outline)
        }

        val now = SystemClock.uptimeMillis()
        val reveal = ((now - routeStartedAt).toFloat() / ROUTE_REVEAL_MS).coerceIn(0f, 1f)
        if (origin != null && exit != null && reveal > 0.01f) drawRoute(canvas, origin!!, exit!!, reveal, now, px)

        origin?.let { drawHalo(canvas, it, originColor, now, px) }
        exit?.let { drawHalo(canvas, it, exitColor, now, px) }
        canvas.restore()

        origin?.let { drawMarker(canvas, it, originColor) }
        exit?.let { drawMarker(canvas, it, exitColor) }

        // Keep the low-cost route pulse alive only while a route is visible.
        if (origin != null || exit != null) postInvalidateOnAnimation()
    }

    private fun drawRoute(
        canvas: Canvas,
        from: Point,
        to: Point,
        reveal: Float,
        now: Long,
        px: (Float) -> Float
    ) {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val length = hypot(dx, dy)
        if (length < 1f) return
        val bow = length * 0.22f
        val controlX = (from.x + to.x) / 2f - dy / length * bow
        val controlY = (from.y + to.y) / 2f + dx / length * bow
        routePath.reset()
        routePath.moveTo(from.x, from.y)
        routePath.quadTo(controlX, controlY, to.x, to.y)

        val measure = android.graphics.PathMeasure(routePath, false)
        val shown = Path()
        val shownLength = measure.length * reveal
        measure.getSegment(0f, shownLength, shown, true)

        line.color = alpha(exitColor, 0.12f)
        line.strokeWidth = px(9f)
        canvas.drawPath(shown, line)
        line.color = alpha(exitColor, 0.45f)
        line.strokeWidth = px(3.4f)
        canvas.drawPath(shown, line)
        line.color = alpha(exitColor, 0.96f)
        line.strokeWidth = px(1.4f)
        canvas.drawPath(shown, line)

        val position = FloatArray(2)
        val tangent = FloatArray(2)
        measure.getPosTan(shownLength, position, tangent)
        val tangentLength = hypot(tangent[0], tangent[1])
        if (tangentLength > 0.0001f) {
            val ux = tangent[0] / tangentLength
            val uy = tangent[1] / tangentLength
            val head = px(15f)
            val wing = px(7.5f)
            val baseX = position[0] - ux * head
            val baseY = position[1] - uy * head
            arrowPath.reset()
            arrowPath.moveTo(position[0], position[1])
            arrowPath.lineTo(baseX - uy * wing, baseY + ux * wing)
            arrowPath.lineTo(baseX + uy * wing, baseY - ux * wing)
            arrowPath.close()
            line.color = alpha(exitColor, 0.28f)
            line.strokeWidth = px(5f)
            canvas.drawPath(arrowPath, line)
            fill.color = exitColor
            canvas.drawPath(arrowPath, fill)
        }

        val activity = max(downloadBps, uploadBps)
        if (reveal > 0.98f && activity > 1.0) {
            // Higher real throughput increases travel velocity; idle connections do not get fake packets.
            val period = (11_000.0 - ln(activity + 1.0) * 850.0).coerceIn(2_300.0, 10_500.0)
            val phase = ((now % period.toLong()).toDouble() / period).toFloat()
            val at = FloatArray(2)
            measure.getPosTan(measure.length * phase, at, null)
            fill.color = alpha(exitColor, 0.22f)
            canvas.drawCircle(at[0], at[1], px(9f), fill)
            fill.color = exitColor
            canvas.drawCircle(at[0], at[1], px(3.2f), fill)

            if (uploadBps > 1.0) {
                val reverseAt = FloatArray(2)
                measure.getPosTan(measure.length * (1f - phase), reverseAt, null)
                fill.color = alpha(originColor, 0.26f)
                canvas.drawCircle(reverseAt[0], reverseAt[1], px(7f), fill)
                fill.color = originColor
                canvas.drawCircle(reverseAt[0], reverseAt[1], px(2.5f), fill)
            }
        }
    }

    private fun drawHalo(canvas: Canvas, point: Point, color: Int, now: Long, px: (Float) -> Float) {
        val breathe = ((sin(now.toDouble() / 350.0) + 1.0) / 2.0).toFloat()
        fill.color = alpha(color, 0.10f + 0.10f * breathe)
        canvas.drawCircle(point.x, point.y, px(26f + 8f * breathe), fill)
        fill.color = alpha(color, 0.24f)
        canvas.drawCircle(point.x, point.y, px(16f), fill)
    }

    /** ZedSecure/flag-icons artwork is clipped only inside round endpoint markers—never country geometry. */
    private fun drawMarker(canvas: Canvas, point: Point, ring: Int) {
        val x = offsetX + point.x * scale
        val y = offsetY + point.y * scale
        val radius = markerRadius()
        if (x < -radius || y < -radius || x > width + radius || y > height + radius) return

        fill.color = alpha(ring, 0.30f)
        canvas.drawCircle(x, y, radius, fill)
        outline.color = ring
        outline.strokeWidth = dp(2f)
        canvas.drawCircle(x, y, radius - dp(1f), outline)

        canvas.save()
        val inner = radius - dp(3f)
        val clip = Path().apply { addCircle(x, y, inner, Path.Direction.CW) }
        canvas.clipPath(clip)
        val flag = FlagAssets.bitmap(context, point.code)
        if (flag != null) {
            FlagAssets.drawCropped(
                canvas,
                flag,
                android.graphics.RectF(x - inner, y - inner, x + inner, y + inner),
                flagBitmapPaint
            )
        } else {
            fill.color = alpha(surfaceColor, 0.90f)
            canvas.drawCircle(x, y, inner, fill)
            flagPaint.color = ContextCompat.getColor(context, R.color.m3_on_surface)
            flagPaint.textSize = radius * 0.76f
            val base = y - (flagPaint.ascent() + flagPaint.descent()) / 2f
            canvas.drawText(point.code, x, base, flagPaint)
        }
        canvas.restore()
    }

    private fun buildGrid() {
        var lon = -180f
        while (lon <= 180f) {
            val x = (lon + 180f) / 360f * WORLD_WIDTH
            gridPath.moveTo(x, 0f)
            gridPath.lineTo(x, WORLD_HEIGHT)
            lon += 30f
        }
        var lat = -90f
        while (lat <= 90f) {
            val y = (90f - lat) / 180f * WORLD_HEIGHT
            gridPath.moveTo(0f, y)
            gridPath.lineTo(WORLD_WIDTH, y)
            lat += 30f
        }
    }

    private fun pointFor(code: String?): Point? {
        val normalized = code?.trim()?.uppercase()?.takeIf { it.length == 2 } ?: return null
        val country = countries.firstOrNull { it.code == normalized } ?: return null
        return Point(country.anchorX, country.anchorY, country.code)
    }

    private fun countryPath(code: String): Path? = countries.firstOrNull { it.code == code }?.path

    private fun wholeWorldScale(): Float =
        if (width <= 0 || height <= 0) 1f else min(width / WORLD_WIDTH, height / WORLD_HEIGHT)

    private fun clampOffset() {
        val mapWidth = WORLD_WIDTH * scale
        val mapHeight = WORLD_HEIGHT * scale
        offsetX = if (mapWidth <= width) (width - mapWidth) / 2f else offsetX.coerceIn(width - mapWidth, 0f)
        offsetY = if (mapHeight <= height) (height - mapHeight) / 2f else offsetY.coerceIn(height - mapHeight, 0f)
    }

    private fun markerRadius(): Float = dp(13f)
    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun alpha(color: Int, amount: Float): Int =
        (color and 0x00FFFFFF) or ((amount.coerceIn(0f, 1f) * 255f).toInt() shl 24)

    private fun readCountries(): List<Country> = try {
        val bytes = resources.openRawResource(R.raw.world).use { it.readBytes() }
        require(bytes.size > 6 && bytes[0].toInt().toChar() == 'Z' && bytes[1].toInt().toChar() == 'W' &&
            bytes[2].toInt().toChar() == 'M' && bytes[3].toInt().toChar() == '1')
        var offset = 4
        fun u8(): Int = bytes[offset++].toInt() and 0xFF
        fun u16(): Int {
            val value = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2
            return value
        }
        val count = u16()
        List(count) {
            val code = "${bytes[offset++].toInt().toChar()}${bytes[offset++].toInt().toChar()}"
            val anchorX = u16().toFloat()
            val anchorY = u16().toFloat() * Y_SCALE
            val rings = ArrayList<FloatArray>()
            val path = Path()
            repeat(u8()) {
                val points = u16()
                val ring = FloatArray(points * 2)
                for (index in 0 until points) {
                    val x = u16().toFloat()
                    val y = u16().toFloat() * Y_SCALE
                    ring[index * 2] = x
                    ring[index * 2 + 1] = y
                }
                if (ring.size >= 6) {
                    path.moveTo(ring[0], ring[1])
                    var point = 2
                    while (point < ring.size) {
                        path.lineTo(ring[point], ring[point + 1])
                        point += 2
                    }
                    path.close()
                }
                rings += ring
            }
            Country(code, anchorX, anchorY, rings, path)
        }
    } catch (_: Exception) {
        emptyList()
    }

    private companion object {
        const val WORLD_WIDTH = 65535f
        const val WORLD_HEIGHT = WORLD_WIDTH / 2f
        const val Y_SCALE = 0.5f
        const val ROUTE_REVEAL_MS = 1_100L
    }
}
