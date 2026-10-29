package com.gozar.desk

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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import com.gozar.FoxRoute
import com.gozar.FoxUpstream
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.VisualTransformation

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
        if (!Desk.signedIn) LoginScreen() else Shell()
    }
}

@Composable
private fun Shell() {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp)) {
            when (Desk.screen) {
                1 -> MapScreen()
                2 -> SettingsScreen()
                else -> HomeScreen()
            }
        }
        BottomBar()
    }
}

@Composable
private fun BottomBar() {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.surfaceLow)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Tab("Home", Desk.screen == 0, Modifier.weight(1f)) { Desk.screen = 0 }
        Tab("Settings", Desk.screen == 2, Modifier.weight(1f)) { Desk.screen = 2 }
    }
}

@Composable
private fun Tab(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(if (active) Palette.surfaceHighest else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (active) Palette.onSurface else Palette.onSurfaceSoft,
            style = MaterialTheme.typography.titleSmall
        )
    }
}

@Composable
private fun HomeScreen() {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(10.dp))
        Header()
        Spacer(Modifier.height(14.dp))
        HeroCard()
        Spacer(Modifier.height(14.dp))
        if (Desk.live) StatRow()
        Spacer(Modifier.height(14.dp))
        ServerRow()
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Palette.surfaceLow)
                .clickable { Desk.screen = 1 }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("◈", color = Palette.mapExit, fontSize = 20.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Live route", style = MaterialTheme.typography.titleSmall, color = Palette.onSurface)
                Text(
                    if (Desk.live) "Your traffic on the map" else "Open the map",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.onSurfaceSoft
                )
            }
            Text("›", color = Palette.onSurfaceSoft, fontSize = 20.sp)
        }
        Spacer(Modifier.height(18.dp))
        Footer()
    }
}

@Composable
private fun Header() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource("app_mark.png"),
            contentDescription = "Gozar",
            modifier = Modifier.size(36.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Gozar", style = MaterialTheme.typography.titleMedium, color = Palette.onSurface)
            Text(
                Desk.email.ifEmpty { "Not signed in" },
                style = MaterialTheme.typography.bodySmall,
                color = Palette.onSurfaceSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(onClick = { Desk.refreshUsage() }) { Text("Usage") }
    }
}

@Composable
private fun Footer() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Gozar " + Desk.version(),
            style = MaterialTheme.typography.labelSmall,
            color = Palette.onSurfaceSoft,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = {
            val file = Diag.save()
            Shortcuts.reveal(file)
        }) { Text("Report") }
        TextButton(onClick = { Shortcuts.openConfigFolder() }) {
            Text("Folder", style = MaterialTheme.typography.labelSmall)
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
        if (Desk.needsAdmin) {
            TextButton(onClick = { Elevate.restartInBackground() }) {
                Text("Restart as administrator", color = Palette.foxCream)
            }
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
                    else "Local proxy on 127.0.0.1:${Desk.proxyPort}",
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
        Column(Modifier.fillMaxWidth().height(260.dp).verticalScroll(rememberScrollState())) {
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
            Text(code.uppercase(), color = Palette.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MapScreen() {
    val place = Desk.selected()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { Desk.screen = 0 }) { Text("‹  Home") }
            Column(Modifier.weight(1f)) {
                Text("Live route", style = MaterialTheme.typography.titleMedium, color = Palette.onSurface)
                Text(
                    if (Desk.live) "Origin and tunnel exit" else "Connect to draw your route",
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.onSurfaceSoft
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
        ) {
            WorldMap(
                originCode = if (Desk.live) "IR" else "",
                exitCode = if (Desk.live) place?.code.orEmpty() else "",
                token = (if (Desk.live) Desk.since.toString() else "idle") + place?.code.orEmpty(),
                download = if (Desk.live) Desk.downRate else 0.0,
                upload = if (Desk.live) Desk.upRate else 0.0,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (Desk.live && place != null) Badge("IR", 28)
            Spacer(Modifier.width(8.dp))
            Text(
                if (Desk.live) "Iran" else "—",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.onSurfaceSoft,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (Desk.live && place != null) place.country else "Tunnel exit",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.onSurface,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End
            )
            if (Desk.live && place != null) {
                Spacer(Modifier.width(8.dp))
                Badge(place.code, 28)
            }
        }
    }
}

@Composable
private fun SettingsScreen() {
    val used = (Desk.limit - Desk.remaining).coerceAtLeast(0)
    var routingOpen by remember { mutableStateOf(false) }
    var chainOpen by remember { mutableStateOf(false) }
    var routingLan by remember { mutableStateOf(Store.bypassLan()) }
    var routingList by remember { mutableStateOf(Store.bypassList()) }
    var chainText by remember { mutableStateOf(Store.chain()) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { Desk.screen = 0 }) { Text("‹  Home") }
            Text(
                "Settings",
                style = MaterialTheme.typography.titleLarge,
                color = Palette.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Palette.surfaceMid)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(Palette.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        Desk.email.take(1).uppercase().ifEmpty { "?" },
                        color = Palette.onPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        Desk.email.ifEmpty { "Not signed in" },
                        style = MaterialTheme.typography.titleMedium,
                        color = Palette.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (Desk.limit > 0) "${bytes(used)} of ${bytes(Desk.limit)} used" else "Free plan · 50 GB a month",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.onSurfaceSoft
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { if (Desk.limit > 0) (used.toFloat() / Desk.limit).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = Palette.primary,
                trackColor = Palette.surfaceHighest
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { Desk.refreshUsage() }, modifier = Modifier.weight(1f)) { Text("Refresh usage") }
                OutlinedButton(onClick = { Desk.signOut() }, modifier = Modifier.weight(1f)) { Text("Sign out") }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "Routing and engines",
            style = MaterialTheme.typography.titleSmall,
            color = Palette.onSurfaceSoft
        )
        Spacer(Modifier.height(8.dp))
        SettingRow(
            "Smart routing",
            if (routingLan || FoxRoute.split(routingList).isNotEmpty())
                "Local network bypass · ${FoxRoute.split(routingList).size} rule(s)"
            else
                "Everything goes through the tunnel"
        ) { routingOpen = true }
        Spacer(Modifier.height(10.dp))
        var fullTunnel by remember { mutableStateOf(Store.fullTunnel()) }
        SettingRow(
            "Full tunnel (TUN)",
            when {
                Desk.tunActive -> "Every app is tunnelled · " + (TunService.last().ifEmpty { "on" })
                fullTunnel -> if (Elevate.windows() && !Elevate.isAdmin()) "On · needs administrator rights"
                    else "On · every app is tunnelled"
                else -> "Off · only apps that follow the system proxy"
            }
        ) {
            fullTunnel = !fullTunnel
            Store.setFullTunnel(fullTunnel)
        }
        Spacer(Modifier.height(10.dp))
        SettingRow(
            "Proxy chain",
            if (FoxUpstream.parse(chainText).isNotEmpty())
                "${FoxUpstream.parse(chainText).size} hop(s) before Mozilla"
            else
                "Direct to Mozilla"
        ) { chainOpen = true }
        Spacer(Modifier.height(18.dp))
        SettingRow("Install shortcut", "Desktop and Start menu") { Shortcuts.ensure() }
        Spacer(Modifier.height(10.dp))
        SettingRow("Settings folder", "Session and preferences") { Shortcuts.openConfigFolder() }
        Spacer(Modifier.height(10.dp))
        SettingRow("Project", "Source code and releases") {
            Shortcuts.openUrl("https://github.com/bardia049b-stack/Gozar")
        }
        Spacer(Modifier.height(18.dp))
        Footer()
    }
    if (routingOpen) {
        AlertDialog(
            onDismissRequest = { routingOpen = false },
            title = { Text("Smart routing") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Bypass local network", color = Palette.onSurface, modifier = Modifier.weight(1f))
                        Switch(checked = routingLan, onCheckedChange = { routingLan = it })
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = routingList,
                        onValueChange = { routingList = it },
                        label = { Text("Direct hosts") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Store.setBypassLan(routingLan)
                    Store.setBypassList(routingList)
                    FoxRoute.apply(routingLan, routingList)
                    routingOpen = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { routingOpen = false }) { Text("Cancel") } }
        )
    }
    if (chainOpen) {
        AlertDialog(
            onDismissRequest = { chainOpen = false },
            title = { Text("Proxy chain") },
            text = {
                Column {
                    Text(
                        "Tunnel the tunnel: traffic goes through these hops before Mozilla.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.onSurfaceSoft
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = chainText,
                        onValueChange = { chainText = it },
                        label = { Text("socks5://host:port") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Store.setChain(chainText)
                    FoxRoute.applyChain(chainText)
                    chainOpen = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { chainOpen = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.surfaceLow)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Palette.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.onSurfaceSoft)
        }
        Text("›", color = Palette.onSurfaceSoft, fontSize = 20.sp)
    }
}

@Composable
private fun Well(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    fieldBg: Color,
    fieldLine: Color,
    textMain: Color,
    textDim: Color,
    accent: Color,
    enabled: Boolean,
    password: Boolean = false
) {
    var shown by remember { mutableStateOf(false) }
    var focus by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(fieldBg)
            .border(
                1.dp,
                if (focus) accent else fieldLine,
                RoundedCornerShape(16.dp)
            )
            .padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = true,
            cursorBrush = SolidColor(accent),
            visualTransformation = if (password && !shown) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = textMain),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth()) {
                    if (value.isEmpty()) {
                        Text(hint, color = textDim, style = MaterialTheme.typography.bodyLarge)
                    }
                    inner()
                }
            },
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { focus = it.isFocused }
        )
        if (password) {
            Text(
                if (shown) "hide" else "show",
                color = textDim,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.clickable { shown = !shown }.padding(start = 8.dp)
            )
        }
    }
}

@Composable
private fun LoginScreen() {
    val bg = Color(0xFF0A0B0E)
    val cardBg = Color(0xFF14161B)
    val cardLine = Color(0x24FFFFFF)
    val line = Color(0x1FFFFFFF)
    val fieldBg = Color(0xFF22252D)
    val fieldLine = Color(0x33FFFFFF)
    val textMain = Color(0xFFF4F5F9)
    val textDim = Color(0xFF9EA1AF)
    val accent = Color(0xFFFF3B54)
    val primaryFill = Color(0xFFE4002B)

    var mail by remember { mutableStateOf(Desk.email) }
    var secret by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var create by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf("") }
    val step = Desk.needsStep()
    val link = Desk.isLinkStep()

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = textMain,
        unfocusedTextColor = textMain,
        focusedContainerColor = fieldBg,
        unfocusedContainerColor = fieldBg,
        disabledContainerColor = fieldBg,
        cursorColor = accent,
        focusedBorderColor = accent,
        unfocusedBorderColor = fieldLine,
        disabledBorderColor = fieldLine,
        focusedLabelColor = accent,
        unfocusedLabelColor = textDim,
        disabledLabelColor = textDim
    )

    Box(Modifier.fillMaxSize().background(bg)) {
        Image(
            painter = painterResource("login_fox.png"),
            contentDescription = null,
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(0.94f)
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(96.dp))
            Surface(
                color = cardBg,
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.dp, cardLine),
                modifier = Modifier.fillMaxWidth().widthIn(max = 460.dp)
            ) {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 24.dp)) {
                    Text(
                        when {
                            step -> "Check your email"
                            create -> "Create account"
                            else -> "Sign in"
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        color = textMain
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when {
                            step && link -> "Mozilla sent a confirmation link. Open it once, then press Confirm."
                            step -> "Mozilla emailed a 6-digit code. Enter it below."
                            create -> "One Firefox account gives you 50 GB every month on every device."
                            else -> "Use your Firefox account to unlock 50 GB every month."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = textDim
                    )
                    Spacer(Modifier.height(20.dp))
                    if (!step) {
                        Well(mail, { mail = it; localError = "" }, "Email", fieldBg, fieldLine, textMain, textDim, accent, !Desk.busy)
                        Spacer(Modifier.height(12.dp))
                        Well(secret, { secret = it; localError = "" }, "Password", fieldBg, fieldLine, textMain, textDim, accent, !Desk.busy, password = true)
                        if (create) {
                            Spacer(Modifier.height(12.dp))
                            Well(confirm, { confirm = it; localError = "" }, "Repeat password", fieldBg, fieldLine, textMain, textDim, accent, !Desk.busy, password = true)
                        }
                        if (!create) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(
                                    onClick = { Shortcuts.openUrl("https://accounts.firefox.com/reset_password") },
                                    colors = ButtonDefaults.textButtonColors(contentColor = accent)
                                ) {
                                    Text("Forgot password?", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        } else {
                            Spacer(Modifier.height(8.dp))
                        }
                    } else if (!link) {
                        Well(code, { code = it }, "6-digit code", fieldBg, fieldLine, textMain, textDim, accent, !Desk.busy)
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (Desk.busy) return@Button
                            localError = ""
                            when {
                                step && link -> Desk.confirmLink()
                                step -> Desk.submitCode(code.trim())
                                mail.isBlank() || secret.isEmpty() -> localError = "Enter your email and password."
                                create && confirm != secret -> localError = "The two passwords do not match."
                                else -> {
                                    if (create) Desk.signUp(mail.trim(), secret) else Desk.signIn(mail.trim(), secret)
                                    secret = ""
                                    confirm = ""
                                }
                            }
                        },
                        enabled = !Desk.busy,
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = primaryFill, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth().height(54.dp)
                    ) {
                        Text(
                            when {
                                step && link -> "Confirm"
                                step -> "Continue"
                                create -> "Create account"
                                else -> "Log in"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    val trouble = localError.ifEmpty { if (Desk.message != "code" && Desk.message != "link") Desk.message else "" }
                    if (Desk.busy) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = accent)
                            Spacer(Modifier.width(9.dp))
                            Text(
                                Desk.stage.ifEmpty { "Working…" },
                                style = MaterialTheme.typography.bodySmall,
                                color = textDim
                            )
                        }
                    } else if (trouble.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            trouble,
                            style = MaterialTheme.typography.bodySmall,
                            color = accent,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (!step) {
                        Spacer(Modifier.height(14.dp))
                        TextButton(
                            onClick = { create = !create; localError = "" },
                            colors = ButtonDefaults.textButtonColors(contentColor = accent),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (create) "Already have an account? Log in" else "New here? Create an account")
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Gozar never sees your password. Sign-in happens on Mozilla servers.",
                        style = MaterialTheme.typography.labelSmall,
                        color = textDim,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
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
