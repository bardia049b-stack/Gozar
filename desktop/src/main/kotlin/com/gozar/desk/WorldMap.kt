package com.gozar.desk

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val WORLD_WIDTH = 65535f
private const val WORLD_HEIGHT = WORLD_WIDTH / 2f
private const val Y_SCALE = 0.5f
private const val REVEAL_MS = 1100f

private class Marker(val x: Float, val y: Float, val code: String)

private class Packet(var phase: Float, var direction: Float)

private class Shape(val code: String, val path: Path, val anchorX: Float, val anchorY: Float)

private object World {

    val shapes: List<Shape> by lazy { read() }

    val land: Path by lazy {
        val all = Path()
        for (shape in shapes) all.addPath(shape.path)
        all
    }

    private val byCode: Map<String, Shape> by lazy { shapes.associateBy { it.code } }

    fun of(code: String): Shape? = byCode[code.trim().uppercase()]

    fun marker(code: String): Marker? {
        val shape = of(code) ?: return null
        return Marker(shape.anchorX, shape.anchorY, shape.code)
    }

    val grid: Path by lazy {
        val path = Path()
        var lon = -180f
        while (lon <= 180f) {
            val x = (lon + 180f) / 360f * WORLD_WIDTH
            path.moveTo(x, 0f)
            path.lineTo(x, WORLD_HEIGHT)
            lon += 30f
        }
        var lat = -90f
        while (lat <= 90f) {
            val y = (90f - lat) / 180f * WORLD_HEIGHT
            path.moveTo(0f, y)
            path.lineTo(WORLD_WIDTH, y)
            lat += 30f
        }
        path
    }

    private fun read(): List<Shape> {
        val bytes = World::class.java.getResourceAsStream("/world.bin")?.use { it.readBytes() } ?: return emptyList()
        if (bytes.size < 6) return emptyList()
        if (String(bytes, 0, 4) != "ZWM1") return emptyList()
        var offset = 4
        fun u8(): Int = bytes[offset++].toInt() and 0xFF
        fun u16(): Int {
            val value = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2
            return value
        }
        val count = u16()
        val out = ArrayList<Shape>(count)
        for (index in 0 until count) {
            if (offset + 6 > bytes.size) break
            val code = "${bytes[offset++].toInt().toChar()}${bytes[offset++].toInt().toChar()}"
            val anchorX = u16().toFloat()
            val anchorY = u16().toFloat() * Y_SCALE
            val path = Path()
            val rings = u8()
            for (ring in 0 until rings) {
                val points = u16()
                val values = FloatArray(points * 2)
                for (point in 0 until points) {
                    if (offset + 4 > bytes.size) break
                    values[point * 2] = u16().toFloat()
                    values[point * 2 + 1] = u16().toFloat() * Y_SCALE
                }
                if (values.size >= 6) {
                    path.moveTo(values[0], values[1])
                    var step = 2
                    while (step < values.size) {
                        path.lineTo(values[step], values[step + 1])
                        step += 2
                    }
                    path.close()
                }
            }
            out.add(Shape(code.replace("\u0000", ""), path, anchorX, anchorY))
        }
        return out
    }
}

private class MapState {

    var scale = 0f
    var offsetX = 0f
    var offsetY = 0f
    var frameWidth = 0f
    var frameHeight = 0f
    var token = ""
    var origin: Marker? = null
    var exit: Marker? = null
    var originCode = ""
    var exitCode = ""
    var startedAt = 0L
    var download = 0.0
    var upload = 0.0
    var tickAt = 0L
    val down = Packet(1f, -1f)
    val up = Packet(0f, 1f)

    private val route = Path()
    private val shown = Path()
    private val measure = PathMeasure()
    private var fromX = Float.NaN
    private var fromY = Float.NaN
    private var toX = Float.NaN
    private var toY = Float.NaN

    fun ends(originCode: String, exitCode: String, token: String) {
        val nextOrigin = World.marker(originCode)
        val nextExit = World.marker(exitCode)
        val changed = nextOrigin?.code != origin?.code || nextExit?.code != exit?.code || token != this.token
        this.originCode = originCode.trim().uppercase()
        this.exitCode = exitCode.trim().uppercase()
        this.token = token
        origin = nextOrigin
        exit = nextExit
        if (changed) {
            startedAt = System.nanoTime()
            tickAt = 0L
            down.phase = 1f
            down.direction = -1f
            up.phase = 0f
            up.direction = 1f
            fit = false
        }
    }

    var fit = false

    fun reset(width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        val whole = min(width / WORLD_WIDTH, height / WORLD_HEIGHT)
        val focus = listOfNotNull(origin, exit)
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
            clamp(width, height)
        }
        fit = true
    }

    fun clamp(width: Float, height: Float) {
        val mapWidth = WORLD_WIDTH * scale
        val mapHeight = WORLD_HEIGHT * scale
        offsetX = if (mapWidth <= width) (width - mapWidth) / 2f else offsetX.coerceIn(width - mapWidth, 0f)
        offsetY = if (mapHeight <= height) (height - mapHeight) / 2f else offsetY.coerceIn(height - mapHeight, 0f)
    }

    fun pan(dx: Float, dy: Float, width: Float, height: Float) {
        offsetX += dx
        offsetY += dy
        clamp(width, height)
    }

    fun zoom(factor: Float, focus: Offset, width: Float, height: Float) {
        val floor = min(width / WORLD_WIDTH, height / WORLD_HEIGHT)
        val next = (scale * factor).coerceIn(floor, floor * 24f)
        val applied = if (scale == 0f) 1f else next / scale
        offsetX = focus.x - (focus.x - offsetX) * applied
        offsetY = focus.y - (focus.y - offsetY) * applied
        scale = next
        clamp(width, height)
    }

    fun route(): PathMeasure {
        val start = origin ?: return measure
        val end = exit ?: return measure
        if (start.x == fromX && start.y == fromY && end.x == toX && end.y == toY) return measure
        fromX = start.x
        fromY = start.y
        toX = end.x
        toY = end.y
        val dx = end.x - start.x
        val dy = end.y - start.y
        val length = hypot(dx, dy)
        route.reset()
        if (length < 1f) {
            measure.setPath(route, false)
            return measure
        }
        val bow = length * 0.22f
        val controlX = (start.x + end.x) / 2f - dy / length * bow
        val controlY = (start.y + end.y) / 2f + dx / length * bow
        route.moveTo(start.x, start.y)
        route.quadraticTo(controlX, controlY, end.x, end.y)
        measure.setPath(route, false)
        return measure
    }

    fun segment(length: Float): Path {
        shown.reset()
        measure.getSegment(0f, length, shown, true)
        return shown
    }

    fun advance(now: Long) {
        val previous = tickAt
        tickAt = now
        if (previous == 0L) return
        val seconds = ((now - previous).coerceIn(1L, 48L) / 1000.0).toFloat()
        if (download > 0.4) step(down, speed(download) * seconds)
        if (upload > 0.4) step(up, speed(upload) * seconds)
    }

    private fun speed(bytes: Double): Float =
        (0.05 + ln(bytes + 1.0) * 0.035).coerceIn(0.06, 0.70).toFloat()

    private fun step(packet: Packet, amount: Float) {
        var phase = packet.phase + packet.direction * amount
        var direction = packet.direction
        while (phase > 1f || phase < 0f) {
            if (phase > 1f) {
                phase = 2f - phase
                direction = -1f
            } else {
                phase = -phase
                direction = 1f
            }
        }
        packet.phase = phase
        packet.direction = direction
    }

    fun fraction(packet: Packet): Float = (0.5 - 0.5 * cos(packet.phase * PI)).toFloat()
}

@Composable
fun WorldMap(
    originCode: String,
    exitCode: String,
    token: String,
    download: Double,
    upload: Double,
    modifier: Modifier = Modifier
) {
    val state = remember { MapState() }
    val measurer = rememberTextMeasurer()
    state.download = download
    state.upload = upload
    remember(originCode, exitCode, token) {
        state.ends(originCode, exitCode, token)
        true
    }
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = withFrameNanos { it }
            val revealing = (now - state.startedAt) / 1_000_000L < REVEAL_MS.toLong() + 60L
            if (revealing || max(download, upload) > 0.4) tick = now
        }
    }
    Canvas(
        modifier.pointerInput(Unit) {
            detectDragGestures { change, drag ->
                change.consume()
                state.pan(drag.x, drag.y, size.width.toFloat(), size.height.toFloat())
            }
        }.pointerInput(Unit) {
            detectTapGestures(onDoubleTap = { state.reset(size.width.toFloat(), size.height.toFloat()) })
        }.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Scroll) {
                        val change = event.changes.firstOrNull() ?: continue
                        val delta = change.scrollDelta.y
                        if (delta != 0f) {
                            state.zoom(1f - delta.coerceIn(-2f, 2f) * 0.12f, change.position,
                                size.width.toFloat(), size.height.toFloat())
                            change.consume()
                        }
                    }
                }
            }
        }
    ) {
        tick.hashCode()
        val width = size.width
        val height = size.height
        if (state.scale == 0f || state.frameWidth != width || state.frameHeight != height) {
            state.frameWidth = width
            state.frameHeight = height
            state.reset(width, height)
        }
        drawRect(Palette.mapSurface)
        val px = 1f / state.scale

        drawContext.canvas.save()
        drawContext.canvas.translate(state.offsetX, state.offsetY)
        drawContext.canvas.scale(state.scale, state.scale)

        drawPath(World.grid, Palette.mapGrid.copy(alpha = 0.5f), style = Stroke(width = px))
        drawPath(World.land, Palette.mapLand.copy(alpha = 0.10f))
        drawPath(World.land, Palette.mapLand.copy(alpha = 0.28f),
            style = Stroke(width = px * 1.1f, join = StrokeJoin.Round))
        World.of(state.originCode)?.let { shape ->
            drawPath(shape.path, Palette.mapOrigin.copy(alpha = 0.34f))
            drawPath(shape.path, Palette.mapOrigin.copy(alpha = 0.88f),
                style = Stroke(width = px * 1.7f, join = StrokeJoin.Round))
        }
        World.of(state.exitCode)?.let { shape ->
            drawPath(shape.path, Palette.mapExit.copy(alpha = 0.42f))
            drawPath(shape.path, Palette.mapExit, style = Stroke(width = px * 2f, join = StrokeJoin.Round))
        }

        val now = System.nanoTime()
        val reveal = ((now - state.startedAt) / 1_000_000.0 / REVEAL_MS).toFloat().coerceIn(0f, 1f)
        val origin = state.origin
        val exit = state.exit
        if (origin != null && exit != null && reveal > 0.01f) {
            val measure = state.route()
            if (measure.length > 1f) {
                val length = measure.length * reveal
                val shown = state.segment(length)
                drawPath(shown, Palette.mapExit.copy(alpha = 0.12f),
                    style = Stroke(width = px * 9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(shown, Palette.mapExit.copy(alpha = 0.45f),
                    style = Stroke(width = px * 3.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(shown, Palette.mapExit.copy(alpha = 0.96f),
                    style = Stroke(width = px * 1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))

                val tip = measure.getPosition(length)
                val tangent = measure.getTangent(length)
                val tangentLength = hypot(tangent.x, tangent.y)
                if (tangentLength > 0.0001f) {
                    val ux = tangent.x / tangentLength
                    val uy = tangent.y / tangentLength
                    val head = px * 15f
                    val wing = px * 7.5f
                    val baseX = tip.x - ux * head
                    val baseY = tip.y - uy * head
                    val arrow = Path().apply {
                        moveTo(tip.x, tip.y)
                        lineTo(baseX - uy * wing, baseY + ux * wing)
                        lineTo(baseX + uy * wing, baseY - ux * wing)
                        close()
                    }
                    drawPath(arrow, Palette.mapExit.copy(alpha = 0.28f),
                        style = Stroke(width = px * 5f, join = StrokeJoin.Round))
                    drawPath(arrow, Palette.mapExit)
                }

                if (reveal > 0.98f && max(download, upload) > 0.4) {
                    state.advance(now)
                    if (download > 0.4) dot(measure, state.fraction(state.down), Palette.mapExit, px)
                    if (upload > 0.4) dot(measure, state.fraction(state.up), Palette.mapOrigin, px)
                } else {
                    state.tickAt = now
                }
            }
        }
        drawContext.canvas.restore()

        origin?.let { mark(it, state, measurer) }
        exit?.let { mark(it, state, measurer) }
    }
}

private fun DrawScope.dot(measure: PathMeasure, fraction: Float, color: Color, px: Float) {
    val position = measure.getPosition(measure.length * fraction.coerceIn(0f, 1f))
    drawCircle(color.copy(alpha = 0.22f), radius = px * 9f, center = position)
    drawCircle(color, radius = px * 3.2f, center = position)
}

private fun DrawScope.mark(marker: Marker, state: MapState, measurer: TextMeasurer) {
    val x = state.offsetX + marker.x * state.scale
    val y = state.offsetY + marker.y * state.scale
    val radius = 13.dp.toPx()
    if (x < -radius || y < -radius || x > size.width + radius || y > size.height + radius) return
    val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(x - radius, y - radius, x + radius, y + radius)) }
    clipPath(circle) {
        val flag = Flag.of(marker.code.lowercase())
        if (flag != null) {
            drawImage(
                image = flag,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(flag.width, flag.height),
                dstOffset = IntOffset((x - radius).roundToInt(), (y - radius).roundToInt()),
                dstSize = IntSize((radius * 2).roundToInt(), (radius * 2).roundToInt())
            )
        } else {
            drawPath(circle, Palette.surface.copy(alpha = 0.9f))
            val layout = measurer.measure(
                marker.code,
                style = TextStyle(color = Palette.onSurface, fontSize = (radius * 0.7f).toSp(), fontWeight = FontWeight.Bold)
            )
            drawText(layout, topLeft = Offset(x - layout.size.width / 2f, y - layout.size.height / 2f))
        }
    }
}

