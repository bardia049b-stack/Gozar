package com.gozar.desk

import com.gozar.FoxApi
import com.gozar.FoxError
import com.gozar.FoxLocation
import com.gozar.FoxPipe
import com.gozar.FoxResolver
import com.gozar.FoxRoute
import com.gozar.FoxStack
import com.gozar.FoxTunnel
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread

object TunEngine {

    @Volatile private var device: TunDevice? = null
    @Volatile private var running = false
    @Volatile private var gateways: List<Pair<String, String>> = emptyList()
    @Volatile private var dnsBackup = ""
    @Volatile var active = false
        private set

    fun isRunning(): Boolean = running

    fun start(location: FoxLocation, pass: String, proxyPort: Int): Boolean {
        if (running) return true
        if (!TunService.prepare()) {
            TunService.note("the wintun driver is missing")
            return false
        }
        val created = TunDevice.open()
        if (created == null) return false
        device = created
        FoxRoute.apply(Store.bypassLan(), Store.bypassList())
        FoxRoute.applyChain(Store.chain())
        val local = "10.111.0.2"
        applyRoutes(created.name, location)
        thread(isDaemon = true, name = "gozar-tun") {
            try {
                val stack = FoxStack(
                    created.reader(),
                    created.writer(),
                    addressOf(local),
                    FoxResolver(proxyPort),
                    { host, port ->
                        if (FoxRoute.direct(host)) {
                            com.gozar.FoxSocketPipe.connect(host, port)
                                ?: FoxTunnel.open(location, host, port, pass)
                        } else {
                            FoxTunnel.open(location, host, port, pass)
                        } ?: throw FoxError("no route to " + host)
                    },
                    { inbound, size -> if (inbound) Desk.down += size else Desk.up += size }
                )
                running = true
                active = true
                TunService.note("the packet stack is running on " + created.name)
                stack.start()
                while (running) Thread.sleep(500)
            } catch (e: Exception) {
                TunService.note("the packet stack stopped: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                running = false
                active = false
            }
        }
        return true
    }

    fun stop() {
        running = false
        active = false
        clearRoutes()
        device?.close()
        device = null
    }

    private fun applyRoutes(iface: String, location: FoxLocation) {
        val exit = try {
            java.net.InetAddress.getByName(location.host).hostAddress.orEmpty()
        } catch (e: Exception) {
            ""
        }
        when {
            TunService.windows() -> {
                TunService.elevated(
                    "netsh interface ipv4 set address name=\"$iface\" static 10.111.0.2 255.255.255.255 && " +
                        "netsh interface ipv4 set dnsservers name=\"$iface\" static 10.111.0.53 primary && " +
                        "netsh interface ipv4 add route 0.0.0.0/1 \"$iface\" && " +
                        "netsh interface ipv4 add route 128.0.0.0/1 \"$iface\" && " +
                        "netsh interface ipv4 add route 10.111.0.53/32 \"$iface\""
                )
                if (exit.isNotEmpty()) keepExit(exit)
            }
            TunService.mac() -> {
                val unit = iface.removePrefix("utun")
                TunService.elevated(
                    "ifconfig $iface 10.111.0.2 10.111.0.2 netmask 255.255.255.255 up && " +
                        "route -n add -net 0.0.0.0/1 -interface $iface && " +
                        "route -n add -net 128.0.0.0/1 -interface $iface"
                )
                dnsBackup = shell("networksetup -getdnsservers Wi-Fi").trim()
                TunService.elevated(
                    "networksetup -setdnsservers Wi-Fi 10.111.0.53 || " +
                        "networksetup -setdnsservers \"Wi-Fi\" 10.111.0.53"
                )
                if (exit.isNotEmpty()) keepExitMac(exit, unit)
            }
            else -> {
                TunService.elevated(
                    "ip link set dev $iface up && ip addr add 10.111.0.2/32 dev $iface && " +
                        "ip route add 0.0.0.0/1 dev $iface && ip route add 128.0.0.0/1 dev $iface"
                )
                if (exit.isNotEmpty()) keepExitLinux(exit)
                dnsBackup = try {
                    java.io.File("/etc/resolv.conf").readText()
                } catch (e: Exception) {
                    ""
                }
                TunService.elevated("sh -c 'printf \"nameserver 10.111.0.53\\n\" > /etc/resolv.conf'")
            }
        }
        TunService.note("routes for $iface are in place")
    }

    private fun keepExit(address: String) {
        val gateways = defaultGateways()
        gateways.firstOrNull()?.let { (gate, device) ->
            TunService.elevated("route add $address mask 255.255.255.255 $gate metric 1 if $device")
        }
    }

    private fun keepExitMac(address: String, unit: String) {
        val gate = shell("route -n get default | awk '/gateway/ {print $2}'").trim()
        val device = shell("route -n get default | awk '/interface/ {print $2}'").trim()
        if (gate.isNotEmpty() && device.isNotEmpty()) {
            TunService.elevated("route -n add -host $address $gate")
        }
    }

    private fun keepExitLinux(address: String) {
        val gate = shell("ip route show default | awk '/default/ {print $3; exit}'").trim()
        val device = shell("ip route show default | awk '/default/ {print $5; exit}'").trim()
        if (gate.isNotEmpty() && device.isNotEmpty()) {
            TunService.elevated("ip route add $address/32 via $gate dev $device")
        }
    }

    private fun defaultGateways(): List<Pair<String, String>> = emptyList()

    private fun shell(command: String): String = try {
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val text = process.inputStream.readBytes().toString(Charsets.UTF_8)
        process.waitFor()
        text
    } catch (e: Exception) {
        ""
    }

    private fun clearRoutes() {
        val name = device?.name.orEmpty()
        if (name.isEmpty()) return
        when {
            TunService.windows() -> TunService.elevated("netsh interface ipv4 delete route 0.0.0.0/1 \"$name\"")
            TunService.mac() -> {
                TunService.elevated("route -n delete -net 0.0.0.0/1 -interface $name; route -n delete -net 128.0.0.0/1 -interface $name")
                if (dnsBackup.isNotEmpty()) {
                    val servers = dnsBackup.split('\n').map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("There aren") }
                        .joinToString(" ")
                    if (servers.isEmpty()) {
                        TunService.elevated("networksetup -setdnsservers Wi-Fi Empty")
                    } else {
                        TunService.elevated("networksetup -setdnsservers Wi-Fi $servers")
                    }
                }
            }
            else -> {
                TunService.elevated(
                    "ip route del 0.0.0.0/1 dev $name; ip route del 128.0.0.0/1 dev $name; ip addr del 10.111.0.2/32 dev $name"
                )
                if (dnsBackup.isNotEmpty()) {
                    val path = java.io.File("/tmp/gozar-resolv.conf")
                    try {
                        path.writeText(dnsBackup)
                        TunService.elevated("cp /tmp/gozar-resolv.conf /etc/resolv.conf")
                    } catch (e: Exception) {
                    }
                }
            }
        }
    }

    private fun addressOf(text: String): Int {
        val parts = text.split('.')
        if (parts.size != 4) return 0
        var value = 0
        for (part in parts) {
            value = (value shl 8) or (part.toIntOrNull() ?: 0)
        }
        return value
    }
}
