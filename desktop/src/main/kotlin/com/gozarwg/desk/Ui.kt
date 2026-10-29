package com.gozarwg.desk

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private object Flag {

    private val cache = HashMap<String, ImageBitmap?>()

    fun of(code: String): ImageBitmap? = synchronized(cache) {
        if (cache.containsKey(code)) return cache[code]
        val found = try {
            Flag::class.java.getResourceAsStream("/flags/" + code + ".png")?.use { stream ->
                org.jetbrains.skia.Image.makeFromEncoded(stream.readBytes()).toComposeImageBitmap()
            }
        } catch (e: Exception) {
            null
        }
        cache[code] = found
        found
    }
}

@Composable
fun GozarApp() {
    LaunchedEffect(Unit) {
        Desk.load()
        Desk.loadServers()
    }
    Surface(color = Palette.surface, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
            Header()
            Spacer(Modifier.height(10.dp))
            ModeSwitch()
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
            ) {
                if (Desk.tab == 1) FoxTab() else WireGuardTab()
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun Header() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource("fox_logo.png"),
            contentDescription = "Firefox VPN",
            modifier = Modifier.size(34.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Firefox VPN", style = MaterialTheme.typography.titleMedium, color = Palette.onSurface)
            Text(
                if (Desk.email.isEmpty()) "Not signed in" else Desk.email,
                style = MaterialTheme.typography.bodySmall,
                color = Palette.onSurfaceSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (Desk.signedIn) {
            TextButton(onClick = { Desk.refreshUsage() }) { Text("Refresh") }
            TextButton(onClick = { Desk.signOut() }) { Text("Sign out") }
        }
    }
}

@Composable
private fun ModeSwitch() {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Palette.surfaceLow).padding(4.dp)
    ) {
        Segment("WireGuard", Desk.tab == 0, Modifier.weight(1f)) { Desk.tab = 0 }
        Segment("Firefox VPN", Desk.tab == 1, Modifier.weight(1f)) { Desk.tab = 1 }
    }
}

@Composable
private fun Segment(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (active) Palette.foxRed else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (active) Color.White else Palette.onSurfaceSoft,
            style = MaterialTheme.typography.titleSmall
        )
    }
}

@Composable
private fun FoxTab() {
    HeroCard()
    Spacer(Modifier.height(14.dp))
    if (Desk.live) StatRow()
    else if (Desk.signedIn) Text(
        "Press the round control to start the tunnel. The app points the system proxy at itself, so browsers and other apps that follow the proxy settings use Firefox VPN.",
        style = MaterialTheme.typography.bodySmall,
        color = Palette.onSurfaceSoft
    )
    Spacer(Modifier.height(14.dp))
    ServerRow()
    if (!Desk.signedIn || Desk.needsStep()) {
        Spacer(Modifier.height(14.dp))
        SignInCard()
    }
}

@Composable
private fun HeroCard() {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Palette.foxRed, Palette.foxRedDark)))
            .padding(vertical = 18.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ConnectControl(
            state = when {
                Desk.connecting -> 1
                Desk.live -> 2
                Desk.fault.isNotEmpty() -> 3
                else -> 0
            },
            elapsed = Desk.elapsed(),
            onClick = {
                when {
                    !Desk.signedIn -> Unit
                    Desk.live || Desk.connecting -> Connect.stop()
                    else -> Connect.start()
                }
            }
        )
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                Desk.live -> "Connected · Firefox VPN"
                Desk.connecting -> "Connecting…"
                else -> "Free plan · 50 GB a month"
            },
            style = MaterialTheme.typography.titleLarge,
            color = Palette.foxCream
        )
        Spacer(Modifier.height(2.dp))
        Text(
            if (Desk.limit > 0) "${bytes((Desk.limit - Desk.remaining).coerceAtLeast(0))} of ${bytes(Desk.limit)} used"
            else "Sign in with a Firefox account to activate 50 GB",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.foxDim
        )
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { if (Desk.limit > 0) ((Desk.limit - Desk.remaining).toFloat() / Desk.limit).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = Palette.foxCream,
            trackColor = Color(0x4DFFFFFF)
        )
        if (Desk.reset.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Resets ${Desk.reset}", style = MaterialTheme.typography.bodySmall, color = Palette.foxDim)
        }
        if (Desk.fault.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(Desk.fault, style = MaterialTheme.typography.bodySmall, color = Palette.foxCream, textAlign = TextAlign.Center)
        }
        if (Desk.live) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Desk.selected()?.let { Badge(it.code) }
                Spacer(Modifier.width(8.dp))
                Text(Desk.exitIp.ifEmpty { "…" }, color = Palette.foxCream, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(10.dp))
                Desk.selected()?.let { place ->
                    val delay = Desk.delayOf(place)
                    if (delay > 0) Text("$delay ms", color = Palette.foxDim, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (Desk.proxyPort > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (Desk.systemProxy) "System proxy set to 127.0.0.1:${Desk.proxyPort}"
                    else "Local proxy on 127.0.0.1:${Desk.proxyPort} — point your apps at it",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.foxDim,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun StatRow() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        MetricTile("↓", speed(Desk.downRate), "Download", Palette.tileContainer, Palette.tileOnContainer)
        MetricTile("↑", speed(Desk.upRate), "Upload", Palette.primary, Palette.onPrimary)
        MetricTile("Σ", bytes(Desk.down + Desk.up), "Total", Palette.tertiaryContainer, Palette.onTertiaryContainer)
        MetricTile("◷", clock(Desk.elapsed()), "Session", Palette.secondaryContainer, Palette.onSecondaryContainer)
    }
}

@Composable
private fun MetricTile(mark: String, value: String, label: String, fill: Color, ink: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(fill),
            contentAlignment = Alignment.Center
        ) {
            Text(mark, color = ink, fontSize = 22.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(value, color = Palette.onSurface, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        Text(label, color = Palette.onSurfaceSoft, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ServerRow() {
    var open by remember { mutableStateOf(false) }
    val place = Desk.selected()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Palette.surfaceLow)
            .clickable { open = !open }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (place != null) Badge(place.code) else Box(Modifier.size(40.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                place?.country ?: "No server yet",
                style = MaterialTheme.typography.titleMedium,
                color = Palette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                when {
                    place == null -> "Pick a server"
                    place.city.isEmpty() -> "Recommended"
                    else -> place.city
                },
                style = MaterialTheme.typography.bodySmall,
                color = Palette.onSurfaceSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(if (open) "⌃" else "›", color = Palette.onSurfaceSoft, fontSize = 20.sp)
    }
    if (open) {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${Desk.servers.count { Desk.delayOf(it) > 0 }} / ${Desk.servers.size}",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.onSurfaceSoft,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { Desk.measure() }) { Text("Measure all") }
        }
        Column(Modifier.fillMaxWidth().height(240.dp).verticalScroll(rememberScrollState())) {
            for (location in Desk.servers) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { Desk.select(location.code); open = false }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Badge(location.code)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(location.country, color = Palette.onSurface, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (location.city.isEmpty()) "Recommended" else location.city,
                            color = Palette.onSurfaceSoft,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        when {
                            Desk.delayOf(location) > 0 -> "${Desk.delayOf(location)} ms"
                            else -> "—"
                        },
                        color = Palette.onSurfaceSoft,
                        style = MaterialTheme.typography.labelMedium
                    )
                    if (location.code == Desk.selectedCode) {
                        Spacer(Modifier.width(8.dp))
                        Text("✓", color = Palette.foxRed, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun Badge(code: String, side: Int = 40) {
    val mark = remember(code) { Flag.of(code.lowercase()) }
    Box(
        Modifier.size(side.dp).clip(CircleShape).background(Palette.surfaceHighest),
        contentAlignment = Alignment.Center
    ) {
        if (mark != null) {
            Image(mark, contentDescription = code, modifier = Modifier.size(side.dp).clip(CircleShape))
        } else {
            Text(code.uppercase(), color = Palette.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SignInCard() {
    var mail by remember { mutableStateOf(if (Desk.email.isEmpty()) "" else Desk.email) }
    var secret by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val step = Desk.needsStep()
    val link = Desk.isLinkStep()

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Palette.surfaceMid)
            .padding(18.dp)
    ) {
        Text(
            if (step) "Check your email" else "Firefox account",
            style = MaterialTheme.typography.titleMedium,
            color = Palette.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (step) (if (link) "Mozilla sent a confirmation link. Open it once, then press Confirm."
            else "Mozilla emailed a 6-digit code. Enter it here.")
            else "Sign in with your Firefox account. 50 GB every month, no card needed.",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.onSurfaceSoft
        )
        if (!step) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = mail,
                onValueChange = { mail = it },
                label = { Text("Email") },
                singleLine = true,
                enabled = !Desk.busy,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                label = { Text("Password") },
                singleLine = true,
                enabled = !Desk.busy,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        Desk.signIn(mail.trim(), secret)
                        secret = ""
                    },
                    enabled = !Desk.busy,
                    modifier = Modifier.weight(1f)
                ) { Text("Sign in") }
                OutlinedButton(
                    onClick = {
                        Desk.signUp(mail.trim(), secret)
                        secret = ""
                    },
                    enabled = !Desk.busy,
                    modifier = Modifier.weight(1f)
                ) { Text("Create account") }
            }
        } else {
            Spacer(Modifier.height(12.dp))
            if (!link) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Code") },
                    singleLine = true,
                    enabled = !Desk.busy,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { if (link) Desk.confirmLink() else Desk.submitCode(code.trim()) },
                    enabled = !Desk.busy,
                    modifier = Modifier.weight(1f)
                ) { Text(if (link) "Confirm" else "Continue") }
                if (!link) {
                    OutlinedButton(
                        onClick = { Desk.resend() },
                        enabled = !Desk.busy,
                        modifier = Modifier.weight(1f)
                    ) { Text("Send again") }
                }
            }
        }
        if (Desk.busy) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Palette.primary)
                Spacer(Modifier.width(10.dp))
                Text(Desk.stage.ifEmpty { "Working…" }, color = Palette.onSurfaceSoft, style = MaterialTheme.typography.bodySmall)
            }
        }
        val trouble = Desk.message.isNotEmpty() && Desk.message != "code" && Desk.message != "link"
        if (trouble) {
            Spacer(Modifier.height(10.dp))
            Text(Desk.message, color = Palette.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun WireGuardTab() {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Palette.surfaceLow)
            .padding(20.dp)
    ) {
        Text("WireGuard profiles", style = MaterialTheme.typography.titleMedium, color = Palette.onSurface)
        Spacer(Modifier.height(6.dp))
        Text(
            "AmneziaWG profiles are handled by the Android build, which ships the native tunnel core. The desktop build carries the Firefox VPN core only, so this tab stays empty here.",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.onSurfaceSoft
        )
    }
}

@Composable
private fun ConnectControl(state: Int, elapsed: Long, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "beat")
    val beat by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3000, easing = androidx.compose.animation.core.LinearEasing)),
        label = "turn"
    )
    val sink by animateFloatAsState(if (state == 2 || state == 1) 1f else 0.96f, label = "press")
    Box(
        Modifier.size(168.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val side = size.minDimension
            val radius = side * 0.425f
            val petals = when (state) {
                2 -> 12
                3 -> 6
                else -> 12
            }
            val depth = when (state) {
                2 -> 0.16f
                3 -> 0.22f
                else -> 0.05f
            }
            val turn = when (state) {
                1 -> beat
                2 -> ((elapsed % 60L) * 6L).toFloat()
                else -> 0f
            }
            val cream = Palette.foxCream
            val peach = Palette.foxPeach
            val warm = Palette.foxOrange
            val from = when (state) {
                2 -> peach
                3 -> warm
                else -> cream
            }
            val to = when (state) {
                2 -> mix(peach, cream, 0.2f)
                3 -> warm
                1 -> peach
                else -> mix(cream, peach, 0.38f)
            }
            val brush = Brush.linearGradient(
                colors = listOf(from, to),
                start = Offset(center.x - radius, center.y - radius),
                end = Offset(center.x + radius, center.y + radius)
            )
            rotate(turn, center) {
                val path = Path()
                val steps = 240
                for (index in 0..steps) {
                    val angle = (index.toFloat() / steps) * 2f * PI.toFloat()
                    val wave = 1f + depth * cos(angle * petals)
                    val reach = radius * sink * wave / (1f + depth)
                    val x = center.x + reach * cos(angle)
                    val y = center.y + reach * sin(angle)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(path, brush)
            }
        }
        when (state) {
            2 -> Text(
                clock(elapsed),
                color = Palette.foxRedDark,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
            3 -> Text("!", color = Palette.foxRedDark, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            else -> PowerGlyph(Palette.foxRedDark)
        }
    }
}

private fun mix(first: Color, second: Color, share: Float): Color = Color(
    red = first.red + (second.red - first.red) * share,
    green = first.green + (second.green - first.green) * share,
    blue = first.blue + (second.blue - first.blue) * share,
    alpha = 1f
)

@Composable
private fun PowerGlyph(ink: Color) {
    Canvas(Modifier.size(56.dp)) {
        val stroke = size.minDimension * 0.1f
        drawArc(
            color = ink,
            startAngle = -60f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(stroke, stroke),
            size = Size(size.width - stroke * 2, size.height - stroke * 2),
            style = Stroke(width = stroke)
        )
        drawLine(
            color = ink,
            start = Offset(size.width / 2f, stroke),
            end = Offset(size.width / 2f, size.height * 0.46f),
            strokeWidth = stroke
        )
    }
}

private fun bytes(value: Long): String {
    if (value < 1024) return "$value B"
    val units = listOf("KB", "MB", "GB", "TB")
    var size = value.toDouble()
    var unit = -1
    while (size >= 1024 && unit < units.size - 1) {
        size /= 1024
        unit++
    }
    return String.format("%.1f %s", size, units[unit])
}

private fun speed(value: Double): String = bytes(value.toLong()) + "/s"

private fun clock(seconds: Long): String =
    String.format("%02d:%02d", (seconds / 60) % 100, seconds % 60)
