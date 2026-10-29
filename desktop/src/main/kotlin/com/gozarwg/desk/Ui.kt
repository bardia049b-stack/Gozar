package com.gozarwg.desk

import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gozarwg.FoxLocation
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal object Flag {

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
        Shortcuts.ensure()
    }
    Surface(color = Palette.surface, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (Desk.screen == 2) {
                MapScreen()
            } else {
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    ModeSwitch()
                    if (Desk.screen == 0) {
                        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                            if (Desk.tab == 1) FoxHome() else WireGuardHome()
                            Spacer(Modifier.height(18.dp))
                        }
                    } else {
                        SettingsScreen()
                    }
                }
                BottomBar()
            }
        }
    }
}

@Composable
private fun ModeSwitch() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Palette.surfaceLow)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Tab("WireGuard", Desk.tab == 0, "amnezia_mark.png", Modifier.weight(1f)) { Desk.tab = 0 }
        Tab("Firefox VPN", Desk.tab == 1, "fox_logo.png", Modifier.weight(1f)) { Desk.tab = 1 }
    }
}

@Composable
private fun Tab(label: String, active: Boolean, icon: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (active) Palette.foxRed else Color.Transparent)
            .clickable { Desk.screen = 0; onClick() }
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(18.dp).clip(CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = if (active) Color.White else Palette.onSurfaceSoft,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1
        )
    }
}

@Composable
private fun BottomBar() {
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(Palette.surfaceLow)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavItem("Home", Desk.screen == 0, Modifier.weight(1f)) { Desk.screen = 0 }
        NavItem("Settings", Desk.screen == 1, Modifier.weight(1f)) { Desk.screen = 1 }
    }
}

@Composable
private fun NavItem(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val ink = if (active) Palette.foxRed else Palette.onSurfaceSoft
    Column(
        modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Canvas(Modifier.size(22.dp)) {
            val stroke = size.minDimension * 0.09f
            if (label == "Home") {
                val path = Path().apply {
                    moveTo(size.width * 0.12f, size.height * 0.46f)
                    lineTo(size.width * 0.5f, size.height * 0.14f)
                    lineTo(size.width * 0.88f, size.height * 0.46f)
                    lineTo(size.width * 0.88f, size.height * 0.86f)
                    lineTo(size.width * 0.58f, size.height * 0.86f)
                    lineTo(size.width * 0.58f, size.height * 0.62f)
                    lineTo(size.width * 0.42f, size.height * 0.62f)
                    lineTo(size.width * 0.42f, size.height * 0.86f)
                    lineTo(size.width * 0.12f, size.height * 0.86f)
                    close()
                }
                drawPath(path, ink, style = Stroke(stroke))
            } else {
                val centre = Offset(size.width / 2f, size.height / 2f)
                drawCircle(ink, size.minDimension * 0.2f, centre, style = Stroke(stroke))
                for (index in 0 until 8) {
                    val angle = (index / 8f) * 2f * PI.toFloat()
                    val inner = size.minDimension * 0.3f
                    val outer = size.minDimension * 0.46f
                    drawLine(
                        ink,
                        Offset(centre.x + cos(angle) * inner, centre.y + sin(angle) * inner),
                        Offset(centre.x + cos(angle) * outer, centre.y + sin(angle) * outer),
                        strokeWidth = stroke * 1.4f
                    )
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(label, color = ink, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun FoxHome() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Header()
        Spacer(Modifier.height(10.dp))
        HeroCard()
        Spacer(Modifier.height(14.dp))
        if (Desk.live) StatRow()
        else if (Desk.signedIn) Text(
            "Press the round control to start. The app points the system proxy at itself, so browsers and other apps that follow the proxy settings take the tunnel.",
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
            Text(
                Desk.fault,
                style = MaterialTheme.typography.bodySmall,
                color = Palette.foxCream,
                textAlign = TextAlign.Center
            )
        }
        if (Desk.live) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Desk.selected()?.let { Badge(it.code, 22) }
                Spacer(Modifier.width(8.dp))
                Text(Desk.exitIp.ifEmpty { "…" }, color = Palette.foxCream, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(10.dp))
                Desk.selected()?.let { place ->
                    val delay = Desk.delayOf(place)
                    if (delay > 0) Text("$delay ms", color = Palette.foxDim, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { openMap() }) {
            Text("Live map", color = Palette.foxCream, style = MaterialTheme.typography.labelLarge)
        }
        if (Desk.live && Desk.proxyPort > 0) {
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

private fun openMap() {
    Desk.exitCode = Desk.selected()?.code?.uppercase().orEmpty()
    Desk.originCode = if (Desk.live) Desk.originCode else ""
    Desk.screen = 2
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
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(92.dp)) {
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
                        .clickable {
                            Desk.select(location.code)
                            open = false
                        }
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
                        if (Desk.delayOf(location) > 0) "${Desk.delayOf(location)} ms" else "—",
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
            Text(code.uppercase(), color = Palette.onSurface, fontSize = (side * 0.3f).sp, fontWeight = FontWeight.Bold)
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
                Text(
                    Desk.stage.ifEmpty { "Working…" },
                    color = Palette.onSurfaceSoft,
                    style = MaterialTheme.typography.bodySmall
                )
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
private fun WireGuardHome() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource("amnezia_mark.png"),
                contentDescription = "WireGuard",
                modifier = Modifier.size(34.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("WireGuard", style = MaterialTheme.typography.titleMedium, color = Palette.onSurface)
                Text(
                    "AmneziaWG profiles",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.onSurfaceSoft
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(Palette.surfaceMid, Palette.surfaceLow)))
                .padding(vertical = 22.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Canvas(Modifier.size(96.dp)) {
                drawCircle(Palette.surfaceHighest, size.minDimension * 0.46f, center)
                drawCircle(Palette.onSurfaceSoft, size.minDimension * 0.26f, center, style = Stroke(size.minDimension * 0.06f))
                drawLine(
                    Palette.onSurfaceSoft,
                    Offset(center.x, center.y - size.minDimension * 0.4f),
                    Offset(center.x, center.y - size.minDimension * 0.2f),
                    strokeWidth = size.minDimension * 0.06f
                )
            }
            Spacer(Modifier.height(14.dp))
            Text("Not connected", style = MaterialTheme.typography.titleLarge, color = Palette.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                "The AmneziaWG engine is Android-only in this build. The desktop app carries the Firefox VPN tunnel.",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.onSurfaceSoft,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            MetricTile("↓", "0 B/s", "Download", Palette.tileContainer, Palette.tileOnContainer)
            MetricTile("↑", "0 B/s", "Upload", Palette.primary, Palette.onPrimary)
            MetricTile("Σ", "0 B", "Total", Palette.tertiaryContainer, Palette.onTertiaryContainer)
        }
        Spacer(Modifier.height(14.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Palette.surfaceLow)
                .padding(16.dp)
        ) {
            Text("Profile", style = MaterialTheme.typography.titleSmall, color = Palette.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                "No profile on this platform yet. Import and the map appear on Android; the desktop port of the engine is the next step.",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.onSurfaceSoft
            )
        }
        Spacer(Modifier.height(14.dp))
        TextButton(onClick = { openMap() }) { Text("Live map") }
    }
}

@Composable
private fun MapScreen() {
    Surface(color = Palette.surface, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            WorldRoute(
                originCode = Desk.originCode,
                exitCode = Desk.exitCode,
                download = Desk.downRate,
                upload = Desk.upRate
            )
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0x66000000))
                            .clickable { Desk.screen = 0 },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("‹", color = Color.White, fontSize = 24.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Live route", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (Desk.live) "${clock(Desk.elapsed())} on the tunnel" else "World map, drawn offline",
                            color = Color(0xCCFFFFFF),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xCC12131A))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (Desk.originCode.isNotEmpty()) {
                        Badge(Desk.originCode, 30)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("You", color = Color(0xAAFFFFFF), style = MaterialTheme.typography.labelSmall)
                            Text(Desk.originCode, color = Color.White, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("→", color = Color(0x88FFFFFF), fontSize = 20.sp)
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text(
                            Desk.selected()?.country ?: "No exit",
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            textAlign = TextAlign.End
                        )
                        Text("Tunnel exit", color = Color(0xAAFFFFFF), style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.width(10.dp))
                    if (Desk.exitCode.isNotEmpty()) Badge(Desk.exitCode, 30)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen() {
    var note by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
        Text("Settings", style = MaterialTheme.typography.titleLarge, color = Palette.onSurface)
        Spacer(Modifier.height(12.dp))
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Desktop shortcut", style = MaterialTheme.typography.titleSmall, color = Palette.onSurface)
                    Text(
                        "Put GozarWG on the desktop",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.onSurfaceSoft
                    )
                }
                OutlinedButton(onClick = { note = if (Shortcuts.ensure()) "Shortcut is on the desktop" else "Could not write the shortcut" }) {
                    Text("Create")
                }
            }
            if (note.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(note, style = MaterialTheme.typography.bodySmall, color = Palette.onSurfaceSoft)
            }
        }
        Spacer(Modifier.height(10.dp))
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Config folder", style = MaterialTheme.typography.titleSmall, color = Palette.onSurface)
                    Text(
                        "Session and settings live here",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.onSurfaceSoft
                    )
                }
                OutlinedButton(onClick = { Shortcuts.openConfigFolder() }) { Text("Open") }
            }
        }
        Spacer(Modifier.height(10.dp))
        Card {
            Column {
                Text("Account", style = MaterialTheme.typography.titleSmall, color = Palette.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (Desk.email.isEmpty()) "Not signed in" else Desk.email,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.onSurfaceSoft
                )
                if (Desk.signedIn) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { Desk.refreshUsage() }) { Text("Refresh usage") }
                        OutlinedButton(onClick = { Desk.signOut() }) { Text("Sign out") }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Card {
            Column {
                Text("About", style = MaterialTheme.typography.titleSmall, color = Palette.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(
                    "GozarWG 1.7.0 · Firefox VPN tunnel over a local proxy, offline world map, flags from the same set as the Android build.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.onSurfaceSoft
                )
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Palette.surfaceLow)
            .padding(16.dp)
    ) { content() }
}

@Composable
private fun ConnectControl(state: Int, elapsed: Long, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "beat")
    val beat by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing)),
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
            val petals = if (state == 3) 6 else 12
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
                fontFamily = FontFamily.Monospace
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
            size = androidx.compose.ui.geometry.Size(size.width - stroke * 2, size.height - stroke * 2),
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

private fun clock(seconds: Long): String = String.format("%02d:%02d", (seconds / 60) % 100, seconds % 60)
