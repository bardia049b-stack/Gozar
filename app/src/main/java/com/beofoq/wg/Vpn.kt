package com.beofoq.wg

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.amnezia.awg.backend.Backend
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.NoopTunnelActionHandler
import org.amnezia.awg.backend.ProxyGoBackend
import org.amnezia.awg.backend.Statistics
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config
import org.amnezia.awg.config.proxy.Socks5Proxy
import kotlin.concurrent.thread

object Vpn {
    @Volatile var up = false
    @Volatile var connecting = false
    @Volatile var profile: Profile? = null
    @Volatile var since = 0L
    @Volatile var downSpeed = 0.0
    @Volatile var upSpeed = 0.0
    @Volatile var totalRx = 0L
    @Volatile var totalTx = 0L
    @Volatile var errorAt = 0L

    @Volatile private var monitoring = false
    private var mLastRx = -1L
    private var mLastTx = -1L
    private var mLastTime = 0L
    var listener: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var appCtx: Context? = null
    private var backend: Backend? = null
    private var backendProxy: Boolean? = null
    private var tun: Tun? = null
    private var userStop = true
    private val main = Handler(Looper.getMainLooper())

    class Tun(private val n: String, private val cb: (Tunnel.State) -> Unit) : Tunnel {
        override fun getName(): String = n
        override fun onStateChange(newState: Tunnel.State) = cb(newState)
        override fun isIpv4ResolutionPreferred(): Boolean = false
        override fun isMetered(): Boolean = false
    }

    private fun backendFor(c: Context): Backend {
        val wantProxy = Store.proxy(c)
        val b = backend
        if (b != null && backendProxy == wantProxy) return b
        val created: Backend = if (wantProxy) {
            ProxyGoBackend(c.applicationContext, NoopTunnelActionHandler())
        } else {
            GoBackend(c.applicationContext, NoopTunnelActionHandler())
        }
        backend = created
        backendProxy = wantProxy
        return created
    }

    fun connect(c: Context, prof: Profile) {
        appCtx = c.applicationContext
        BlockService.stop(c.applicationContext)
        profile = prof
        errorAt = 0L
        userStop = false
        connecting = true
        notifyChange()
        thread {
            try {
                val cfg = buildConfig(c, prof)
                val t = Tun(prof.id) { s -> onState(s) }
                tun = t
                backendFor(c).setState(t, Tunnel.State.UP, cfg)
                connecting = false
                notifyChange()
            } catch (e: Exception) {
                connecting = false
                up = false
                errorAt = System.currentTimeMillis()
                notifyChange()
                val msg = e.message ?: "error"
                main.post { onError?.invoke(msg) }
            }
        }
    }

    fun disconnect() {
        userStop = true
        val t = tun ?: return
        val b = backend ?: return
        connecting = true
        notifyChange()
        thread {
            try {
                b.setState(t, Tunnel.State.DOWN, null)
            } catch (e: Exception) {
            }
            connecting = false
            up = false
            profile = null
            notifyChange()
        }
    }

    private fun onState(s: Tunnel.State) {
        val wasUp = up
        up = s == Tunnel.State.UP
        if (up && !wasUp) {
            since = System.currentTimeMillis()
            errorAt = 0L
            startMonitor()
        }
        if (!up) {
            downSpeed = 0.0
            upSpeed = 0.0
            appCtx?.let { Notify.cancel(it) }
        }
        if (!up && !userStop && profile != null) {
            val c = appCtx
            val prof = profile
            if (c != null && prof != null && Store.kill(c)) {
                BlockService.start(c.applicationContext)
                main.postDelayed({
                    if (!up && !userStop && profile != null) connect(c, prof)
                }, 3000)
            }
        }
        notifyChange()
    }

    private fun startMonitor() {
        if (monitoring) return
        monitoring = true
        mLastRx = -1L
        mLastTx = -1L
        mLastTime = 0L
        thread {
            try {
                while (up) {
                    Thread.sleep(1000)
                    if (!up) break
                    val st = stats() ?: continue
                    val rx = st.totalRx()
                    val tx = st.totalTx()
                    totalRx = rx
                    totalTx = tx
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (mLastRx >= 0 && now - mLastTime >= 900) {
                        val dt = (now - mLastTime) / 1000.0
                        val draw = if (rx >= mLastRx) (rx - mLastRx) / dt else 0.0
                        val uraw = if (tx >= mLastTx) (tx - mLastTx) / dt else 0.0
                        downSpeed = if (downSpeed <= 0.0) draw else draw * 0.55 + downSpeed * 0.45
                        upSpeed = if (upSpeed <= 0.0) uraw else uraw * 0.55 + upSpeed * 0.45
                        if (draw <= 0.0) downSpeed *= 0.7
                        if (uraw <= 0.0) upSpeed *= 0.7
                        mLastRx = rx
                        mLastTx = tx
                        mLastTime = now
                    } else if (mLastRx < 0) {
                        mLastRx = rx
                        mLastTx = tx
                        mLastTime = now
                    }
                    val c = appCtx
                    val p = profile
                    if (c != null && p != null) Notify.update(c, p.name, downSpeed, upSpeed)
                }
            } catch (e: Exception) {
            }
            monitoring = false
            downSpeed = 0.0
            upSpeed = 0.0
        }
    }

    private fun notifyChange() {
        val l = listener ?: return
        main.post { l.invoke(up) }
    }

    private fun buildConfig(c: Context, prof: Profile): Config {
        var text = prof.text
        if (Store.lan(c) && !Store.proxy(c)) text = Lan.apply(text)
        val mode = Store.splitMode(c)
        val apps = Store.splitApps(c)
        if (mode != 0 && apps.isNotEmpty()) {
            val key = if (mode == 1) "ExcludedApplications" else "IncludedApplications"
            text = addInterfaceLine(text, "$key = ${apps.joinToString(", ")}")
        }
        val cfg = Config.parse(text.byteInputStream(Charsets.UTF_8))
        if (!Store.proxy(c)) return cfg
        val builder = Config.Builder()
            .setInterface(cfg.getInterface())
            .addPeers(cfg.peers)
            .setDnsSettings(cfg.dnsSettings)
            .addProxy(Socks5Proxy("127.0.0.1:" + Store.proxyPort(c), null, null))
        return builder.build()
    }

    private fun addInterfaceLine(text: String, line: String): String {
        val lines = text.lines().toMutableList()
        var inInterface = false
        for (i in lines.indices) {
            val t = lines[i].trim()
            if (t.startsWith("[")) {
                if (inInterface) {
                    lines.add(i, line)
                    return lines.joinToString("\n")
                }
                inInterface = t.equals("[Interface]", true)
            }
        }
        if (inInterface) lines.add(line)
        return lines.joinToString("\n")
    }

    fun stats(): Statistics? {
        val t = tun ?: return null
        val b = backend ?: return null
        if (!up) return null
        return try {
            b.getStatistics(t)
        } catch (e: Exception) {
            null
        }
    }

    fun handshakeAgeSec(): Long {
        val t = tun ?: return -1
        val prof = profile ?: return -1
        val cfg = try {
            Config.parse(prof.text.byteInputStream(Charsets.UTF_8))
        } catch (e: Exception) {
            return -1
        }
        val st = stats() ?: return -1
        val key = cfg.peers.firstOrNull()?.publicKey ?: return -1
        val peer = st.peer(key) ?: return -1
        val ms = peer.latestHandshakeEpochMillis()
        if (ms <= 0) return -1
        return (System.currentTimeMillis() - ms) / 1000
    }

    fun coreVersion(c: Context): String {
        return try {
            backendFor(c).version
        } catch (e: Exception) {
            ""
        }
    }
}
