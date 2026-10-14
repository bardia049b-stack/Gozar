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
        profile = prof
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
                notifyChange()
                onError?.invoke(e.message ?: "error")
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
        if (up && !wasUp) since = System.currentTimeMillis()
        if (!up && !userStop && profile != null) {
            val c = appCtx
            val prof = profile
            if (c != null && prof != null && Store.kill(c)) {
                main.postDelayed({
                    if (!up && !userStop && profile != null) connect(c, prof)
                }, 3000)
            }
        }
        notifyChange()
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
