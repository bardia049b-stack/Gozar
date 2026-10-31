package com.gozar

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
    private val stateLock = Any()
    private var generation = 0L
    private val main = Handler(Looper.getMainLooper())
    private var lastDown = 0L
    private var lastUp = 0L
    private var lastAt = 0L
    private var ticker: Thread? = null
    private var renewer: Thread? = null

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
        val app = c.applicationContext
        val attempt = synchronized(stateLock) {
            if (connected || connecting) return
            generation += 1
            connecting = true
            error = ""
            generation
        }
        tick()
        thread {
            try {
                FoxRoute.apply(Store.bypassLan(app), Store.bypassList(app))
                FoxRoute.applyChain(Store.chain(app))
                FoxTunnel.resetTransport()
                val access = Fox.validAccess(app)
                if (!current(attempt)) return@thread
                var pass = FoxApi.pass(access.token)
                if (!storePass(app, pass, access, attempt)) return@thread
                if (Fox.servers.isEmpty()) {
                    val servers = FoxApi.servers()
                    if (!current(attempt)) return@thread
                    Fox.servers = servers
                }
                val chosen = Fox.selectedLocation(app) ?: throw FoxError("No server available")
                val lower = pass.limit > 0 && pass.remaining <= 0
                if (lower) throw FoxError("Your 50 GB for this month is used up")
                synchronized(stateLock) {
                    if (attempt != generation) return@thread
                    location = chosen
                    socks = FoxProxy(SOCKS_PORT) { FoxStore.pass(app).ifEmpty { null } }
                    web = FoxProxy(WEB_PORT) { FoxStore.pass(app).ifEmpty { null } }
                    if (socks?.start() != true || web?.start() != true) {
                        throw FoxError("The local proxy did not start")
                    }
                }
                val detected = try {
                    FoxTunnel.probe(chosen, pass.token)
                } catch (e: Exception) {
                    if (FoxTunnel.isTlsFailure(e)) throw e
                    val reason = e.message ?: ""
                    if (reason.contains("rejected the pass") || reason.contains("pass was refused") || reason.contains("no status")) {
                        if (!current(attempt)) return@thread
                        val renewed = Fox.validAccess(app)
                        pass = FoxApi.pass(renewed.token)
                        if (!storePass(app, pass, renewed, attempt)) return@thread
                        FoxTunnel.probe(chosen, pass.token)
                    } else if (reason.contains("allowance") || reason.contains("region") || reason.contains("access") || reason.contains("rejected") || reason.contains("closed the connection")) {
                        throw FoxError(reason)
                    } else {
                        ""
                    }
                }
                synchronized(stateLock) {
                    if (attempt != generation) return@thread
                    exitIp = detected
                    connected = true
                    connecting = false
                    since = System.currentTimeMillis()
                    down = 0L
                    up = 0L
                    error = ""
                    if (Store.recents(app)) Store.setLastServer(app, chosen.host)
                    startTicker()
                    startRenewal(app, attempt)
                }
                tick()
                FoxVpnService.activateRoot()
            } catch (e: Exception) {
                synchronized(stateLock) {
                    if (attempt != generation) return@thread
                    connected = false
                    connecting = false
                    error = if (FoxTunnel.isTlsFailure(e)) {
                        app.getString(R.string.fox_secure_connection_error)
                    } else {
                        e.message ?: "Could not connect"
                    }
                    stopServers()
                }
                tick()
            }
        }
    }

    private fun current(attempt: Long): Boolean = synchronized(stateLock) { attempt == generation }

    private fun storePass(app: Context, pass: FoxPass, access: FoxAccess, attempt: Long, live: Boolean = false): Boolean =
        synchronized(stateLock) {
            if (attempt != generation || (live && !connected)) false
            else FoxStore.saveUsage(app, pass, access.id, access.revision)
        }

    fun stop() {
        synchronized(stateLock) {
            generation += 1
            stopServers()
            connected = false
            connecting = false
            exitIp = ""
            downRate = 0.0
            upRate = 0.0
            since = 0L
            error = ""
            stopTicker()
            stopRenewal()
        }
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
                if (!FoxVpnService.isAlive()) {
                    error = "The system closed the tunnel"
                    stop()
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

    private fun startRenewal(app: Context, attempt: Long) {
        stopRenewal()
        renewer = thread(isDaemon = true, name = "fox-renew") {
            var retry = 0L
            while (current(attempt) && connected) {
                val wait = if (retry > 0) retry else FoxStore.renewalDelay(FoxStore.passUntil(app))
                try {
                    Thread.sleep(wait)
                } catch (_: InterruptedException) {
                    break
                }
                if (!current(attempt) || !connected) break
                try {
                    val access = Fox.validAccess(app)
                    val pass = FoxApi.pass(access.token)
                    if (!storePass(app, pass, access, attempt, true)) break
                    retry = 0L
                    Fox.load(app)
                    tick()
                } catch (_: Exception) {
                    retry = if (retry == 0L) 15_000L else (retry * 2).coerceAtMost(60_000L)
                }
            }
        }
    }

    private fun stopRenewal() {
        renewer?.interrupt()
        renewer = null
    }

    private fun stopTicker() {
        ticker?.interrupt()
        ticker = null
    }

    fun elapsed(): Long = if (connected) (System.currentTimeMillis() - since) / 1000 else 0L
}
