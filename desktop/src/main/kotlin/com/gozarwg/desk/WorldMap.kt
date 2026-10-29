package com.gozarwg.desk

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private class Country(
    val code: String,
    val anchorX: Float,
    val anchorY: Float,
    val path: Path
)

private class Spot(val x: Float, val y: Float, val code: String)

private object World {

    const val WIDTH = 65535f
    const val HEIGHT = WIDTH / 2f
    private const val Y_SCALE = 0.5f

    val countries: List<Country> by lazy { read() }
    val land: Path by lazy {
        Path().apply { countries.forEach { addPath(it.path) } }
    }
    val grid: Path by lazy {
        val path = Path()
        var lon = -180f
        while (lon <= 180f) {
            val x = (lon + 180f) / 360f * WIDTH
            path.moveTo(x, 0f)
            path.lineTo(x, HEIGHT)
            lon += 30f
        }
        var lat = -90f
        while (lat <= 90f) {
            val y = (90f - lat) / 180f * HEIGHT
            path.moveTo(0f, y)
            path.lineTo(WIDTH, y)
            lat += 30f
        }
        path
    }

    fun spot(code: String?): Spot? {
        val wanted = code?.trim()?.uppercase()?.takeIf { it.length == 2 } ?: return null
        val country = countries.firstOrNull { it.code == wanted } ?: return null
        return Spot(country.anchorX, country.anchorY, country.code)
    }

    fun shape(code: String): Path? = countries.firstOrNull { it.code == code.uppercase() }?.path

    private fun read(): List<Country> {
        return try {
            val bytes = World::class.java.getResourceAsStream("/world.bin")?.use { it.readBytes() } ?: return emptyList()
            if (bytes.size < 6) return emptyList()
            if (bytes[0].toInt().toChar() != 'Z' || bytes[1].toInt().toChar() != 'W') return emptyList()
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
                val path = Path()
                repeat(u8()) {
                    val points = u16()
                    val ring = FloatArray(points * 2)
                    for (index in 0 until points) {
                        ring[index * 2] = u16().toFloat()
                        ring[index * 2 + 1] = u16().toFloat() * Y_SCALE
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
                }
                Country(code, anchorX, anchorY, path)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

@Composable
fun WorldRoute(
    originCode: String,
    exitCode: String,
    download: Double,
    upload: Double,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var scale by remember { mutableStateOf(0f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var box by remember { mutableStateOf(Size.Zero) }
    var frame by remember { mutableStateOf(0L) }
    var revealAt by remember { mutableStateOf(0L) }
    var packetAt by remember { mutableStateOf(0L) }
    val down = remember { floatArrayOf(1f, -1f) }
    val up = remember { floatArrayOf(0f, 1f) }

    val origin = remember(originCode) { World.spot(originCode) }
    val exit = remember(exitCode) { World.spot(exitCode) }
    val marker = with(density) { 13.dp.toPx() }

    fun whole(side: Size): Float = if (side.width <= 0f) 1f else min(side.width / World.WIDTH, side.height / World.HEIGHT)

    fun clamp(side: Size) {
        val mapWidth = World.WIDTH * scale
        val mapHeight = World.HEIGHT * scale
        offsetX = if (mapWidth <= side.width) (side.width - mapWidth) / 2f else offsetX.coerceIn(side.width - mapWidth, 0f)
        offsetY = if (mapHeight <= side.height) (side.height - mapHeight) / 2f else offsetY.coerceIn(side.height - mapHeight, 0f)
    }

    fun frameFor(side: Size) {
        if (side.width <= 0f || side.height <= 0f) return
        val base = whole(side)
        val focus = listOfNotNull(origin, exit)
        if (focus.isEmpty()) {
            scale = base
            offsetX = (side.width - World.WIDTH * base) / 2f
            offsetY = (side.height - World.HEIGHT * base) / 2f
            return
        }
        val centerX = focus.map { it.x }.average().toFloat()
        val centerY = focus.map { it.y }.average().toFloat()
        scale = if (focus.size < 2) {
            base * 2.6f
        } else {
            val spanX = (focus.maxOf { it.x } - focus.minOf { it.x }) * 1.9f + World.WIDTH * 0.06f
            val spanY = (focus.maxOf { it.y } - focus.minOf { it.y }) * 2.4f + World.HEIGHT * 0.06f
            min(side.width / max(spanX, 1f), side.height / max(spanY, 1f)).coerceIn(base * 0.95f, base * 4.5f)
        }
        offsetX = side.width / 2f - centerX * scale
        offsetY = side.height / 2f - centerY * scale
        clamp(side)
    }

    LaunchedEffect(box, originCode, exitCode) {
        if (box.width > 0f) {
            revealAt = System.currentTimeMillis()
            frameFor(box)
        }
    }

    LaunchedEffect(box) {
        val started = System.currentTimeMillis()
        revealAt = started
        packetAt = 0L
        while (isActive) {
            frame = System.currentTimeMillis()
            delay(16)
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(box) {
                detectDragGestures { _, drag ->
                    offsetX += drag.x
                    offsetY += drag.y
                    clamp(box)
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (size != box) {
                box = size
                frameFor(size)
            }
            if (scale <= 0f) return@Canvas
            val now = frame
            val reveal = ((now - revealAt).toFloat() / REVEAL_MS).coerceIn(0f, 1f)
            val px = { value: Float -> value / scale }

            drawRect(Color(0xFF12131A))
            translate(offsetX, offsetY) {
                scale(scale, scale, pivot = Offset.Zero) {
                    drawPath(World.grid, Color(0xFF868198).copy(alpha = 0.10f), style = Stroke(px(1f)))
                    drawPath(World.land, Color(0xFFEEEAF7).copy(alpha = 0.10f))
                    drawPath(World.land, Color(0xFFEEEAF7).copy(alpha = 0.28f), style = Stroke(px(1.1f)))
                    World.shape(originCode)?.let { path ->
                        drawPath(path, Palette.mapOrigin.copy(alpha = 0.34f))
                        drawPath(path, Palette.mapOrigin.copy(alpha = 0.88f), style = Stroke(px(1.7f)))
                    }
                    World.shape(exitCode)?.let { path ->
                        drawPath(path, Palette.mapExit.copy(alpha = 0.42f))
                        drawPath(path, Palette.mapExit.copy(alpha = 1f), style = Stroke(px(2f)))
                    }
                    if (origin != null && exit != null && reveal > 0.01f) {
                        route(this, origin, exit, reveal, now, down, up, download, upload, packetAt, px)
                        if (reveal > 0.98f && max(download, upload) > 0.4) packetAt = now
                    }
                }
            }
            origin?.let { mark(this, it, offsetX, offsetY, scale, marker) }
            exit?.let { mark(this, it, offsetX, offsetY, scale, marker) }
        }
    }
}

private fun route(
    scope: DrawScope,
    from: Spot,
    to: Spot,
    reveal: Float,
    now: Long,
    down: FloatArray,
    up: FloatArray,
    download: Double,
    upload: Double,
    packetAt: Long,
    px: (Float) -> Float
) {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val length = hypot(dx, dy)
    if (length < 1f) return
    val bow = length * 0.22f
    val path = Path().apply {
        moveTo(from.x, from.y)
        quadraticBezierTo((from.x + to.x) / 2f - dy / length * bow, (from.y + to.y) / 2f + dx / length * bow, to.x, to.y)
    }
    val measure = PathMeasure()
    measure.setPath(path, false)
    val drawn = Path()
    measure.getSegment(0f, measure.length * reveal, drawn, true)

    scope.drawPath(drawn, Palette.mapExit.copy(alpha = 0.12f), style = Stroke(px(9f)))
    scope.drawPath(drawn, Palette.mapExit.copy(alpha = 0.45f), style = Stroke(px(3.4f)))
    scope.drawPath(drawn, Palette.mapExit.copy(alpha = 0.96f), style = Stroke(px(1.4f)))

    val tip = measure.getPosition(measure.length * reveal)
    val tangent = measure.getTangent(measure.length * reveal)
    val tangentLength = hypot(tangent.x, tangent.y)
    if (tangentLength > 0.0001f) {
        val ux = tangent.x / tangentLength
        val uy = tangent.y / tangentLength
        val head = px(15f)
        val wing = px(7.5f)
        val baseX = tip.x - ux * head
        val baseY = tip.y - uy * head
        val arrow = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(baseX - uy * wing, baseY + ux * wing)
            lineTo(baseX + uy * wing, baseY - ux * wing)
            close()
        }
        scope.drawPath(arrow, Palette.mapExit.copy(alpha = 0.28f), style = Stroke(px(5f)))
        scope.drawPath(arrow, Palette.mapExit)
    }

    if (reveal > 0.98f && max(download, upload) > 0.4) {
        val step = if (packetAt == 0L) 0f else ((now - packetAt).coerceIn(1L, 48L) / 1000f)
        if (download > 0.4) {
            advance(down, speed(download) * step)
            dot(scope, measure, eased(down[0]), Palette.mapExit, px)
        }
        if (upload > 0.4) {
            advance(up, speed(upload) * step)
            dot(scope, measure, eased(up[0]), Palette.mapOrigin, px)
        }
    }
}

private fun advance(packet: FloatArray, amount: Float) {
    var phase = packet[0] + packet[1] * amount
    var direction = packet[1]
    while (phase > 1f || phase < 0f) {
        if (phase > 1f) {
            phase = 2f - phase
            direction = -1f
        } else {
            phase = -phase
            direction = 1f
        }
    }
    packet[0] = phase
    packet[1] = direction
}

private fun speed(bytesPerSecond: Double): Float =
    (0.05 + ln(bytesPerSecond + 1.0) * 0.035).coerceIn(0.06, 0.70).toFloat()

private fun eased(phase: Float): Float = (0.5 - 0.5 * cos(phase * PI)).toFloat()

private fun dot(scope: DrawScope, measure: PathMeasure, fraction: Float, color: Color, px: (Float) -> Float) {
    val at = measure.getPosition(measure.length * fraction.coerceIn(0f, 1f))
    scope.drawCircle(color.copy(alpha = 0.22f), px(9f), at)
    scope.drawCircle(color, px(3.2f), at)
}

private fun mark(scope: DrawScope, spot: Spot, offsetX: Float, offsetY: Float, scale: Float, radius: Float) {
    val x = offsetX + spot.x * scale
    val y = offsetY + spot.y * scale
    val bounds = Rect(x - radius, y - radius, x + radius, y + radius)
    val clip = Path().apply { addOval(bounds) }
    scope.clipPath(clip) {
        val flag = Flag.of(spot.code.lowercase())
        if (flag != null) {
            scope.drawImage(
                image = flag,
                dstOffset = androidx.compose.ui.unit.IntOffset(
                    (x - radius).roundToInt(),
                    (y - radius).roundToInt()
                ),
                dstSize = androidx.compose.ui.unit.IntSize(
                    (radius * 2).roundToInt(),
                    (radius * 2).roundToInt()
                )
            )
        } else {
            scope.drawCircle(Color(0xFF12131A), radius, Offset(x, y))
        }
    }
}

private const val REVEAL_MS = 1100f
