package com.gozarwg

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

object FoxLink {

    const val SOCKS_PORT = 10810
    const val WEB_PORT = 10811

    @Volatile var connected = false
    @Volatile var connecting = false
    @Volatile var error: String = ""
    @Volatile var location: FoxLocation? = null
    @Volatile var exitIp: String = ""
    @Volatile var since: Long = 0L
    @Volatile var down: Long = 0L
    @Volatile var up: Long = 0L
    @Volatile var downRate: Double = 0.0
    @Volatile var upRate: Double = 0.0

    private var socks: FoxProxy? = null
    private var web: FoxProxy? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastDown = 0L
    private var lastUp = 0L
    private var lastAt = 0L
    private var ticker: Thread? = null

    var onChange: (() -> Unit)? = null

    fun tick() {
        main.post { onChange?.invoke() }
    }

    fun fault(message: String) {
        error = message
        tick()
    }

    fun count(inbound: Boolean, bytes: Long) {
        if (inbound) down += bytes else up += bytes
    }

    fun start(c: Context) {
        if (connected || connecting) return
        val app = c.applicationContext
        connecting = true
        error = ""
        tick()
        thread {
            try {
                val access = Fox.validAccess(app)
                val pass = FoxApi.pass(access)
                FoxStore.saveUsage(app, pass)
                if (Fox.servers.isEmpty()) Fox.servers = FoxApi.servers()
                val chosen = Fox.selectedLocation(app) ?: throw FoxError("No server available")
                val lower = pass.limit > 0 && pass.remaining <= 0
                if (lower) throw FoxError("Your 50 GB for this month is used up")
                location = chosen
                socks = FoxProxy(SOCKS_PORT) { FoxStore.pass(app).ifEmpty { null } }
                web = FoxProxy(WEB_PORT) { FoxStore.pass(app).ifEmpty { null } }
                if (socks?.start() != true || web?.start() != true) {
                    throw FoxError("The local proxy did not start")
                }
                exitIp = try {
                    FoxH2.probe(chosen, pass.token)
                } catch (e: Exception) {
                    ""
                }
                connected = true
                connecting = false
                since = System.currentTimeMillis()
                down = 0L
                up = 0L
                error = ""
                startTicker()
                tick()
            } catch (e: Exception) {
                connected = false
                connecting = false
                error = e.message ?: "Could not connect"
                stopServers()
                tick()
            }
        }
    }

    fun stop() {
        stopServers()
        connected = false
        connecting = false
        exitIp = ""
        downRate = 0.0
        upRate = 0.0
        since = 0L
        error = ""
        stopTicker()
        tick()
    }

    private fun stopServers() {
        socks?.stop()
        web?.stop()
        socks = null
        web = null
    }

    private fun startTicker() {
        stopTicker()
        lastDown = down
        lastUp = up
        lastAt = System.currentTimeMillis()
        ticker = thread(isDaemon = true, name = "fox-meter") {
            while (connected) {
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    break
                }
                val now = System.currentTimeMillis()
                val span = (now - lastAt).coerceAtLeast(1)
                downRate = (down - lastDown) * 1000.0 / span
                upRate = (up - lastUp) * 1000.0 / span
                lastDown = down
                lastUp = up
                lastAt = now
                tick()
            }
        }
    }

    private fun stopTicker() {
        ticker?.interrupt()
        ticker = null
    }

    fun elapsed(): Long = if (connected) (System.currentTimeMillis() - since) / 1000 else 0L
}
