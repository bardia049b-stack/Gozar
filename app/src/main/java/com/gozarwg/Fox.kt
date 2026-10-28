package com.gozarwg

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

object Fox {

    private const val STEP_NONE = 0
    private const val STEP_SIGNUP = 1
    private const val STEP_LOGIN = 2

    private val main = Handler(Looper.getMainLooper())
    private val delays = ConcurrentHashMap<String, Int>()

    @Volatile var email: String = ""
    @Volatile var limit: Long = 0L
    @Volatile var remaining: Long = 0L
    @Volatile var reset: String = ""
    @Volatile var busy: Boolean = false
    @Volatile var message: String = ""
    @Volatile var servers: List<FoxLocation> = emptyList()

    private var step = STEP_NONE
    private var pendingEmail = ""
    private var pendingPassword = ""
    private var pendingUid = ""
    private var pendingToken = ""

    var onChange: (() -> Unit)? = null

    fun load(c: Context) {
        email = FoxStore.email(c)
        limit = FoxStore.limit(c)
        remaining = FoxStore.remaining(c)
        reset = FoxStore.reset(c)
    }

    fun signedIn(c: Context): Boolean = FoxStore.email(c).isNotEmpty() && FoxStore.refreshToken(c).isNotEmpty()

    fun needsCode(): Boolean = step != STEP_NONE

    private fun notifyUi() {
        main.post { onChange?.invoke() }
    }

    private fun work(c: Context, body: () -> Unit) {
        busy = true
        message = ""
        notifyUi()
        val app = c.applicationContext
        thread {
            var failure: String? = null
            try {
                body()
            } catch (e: FoxError) {
                failure = e.message ?: "Sign-in failed"
            } catch (e: Exception) {
                failure = e.message ?: "Network error"
            }
            busy = false
            if (failure != null) message = failure
            load(app)
            notifyUi()
        }
    }

    fun signUp(c: Context, mail: String, password: String) {
        val clean = mail.trim()
        work(c) {
            val session = FoxApi.register(clean, password)
            pendingEmail = clean
            pendingPassword = password
            pendingUid = session.uid
            pendingToken = session.token
            step = STEP_SIGNUP
            message = "code"
        }
    }

    fun signIn(c: Context, mail: String, password: String) {
        val clean = mail.trim()
        work(c) {
            val session = FoxApi.signIn(clean, password)
            pendingEmail = clean
            pendingPassword = password
            pendingUid = session.uid
            pendingToken = session.token
            if (session.needsCode) {
                step = STEP_LOGIN
                message = "code"
            } else {
                finish(c.applicationContext, session.token, session.uid, clean, password)
            }
        }
    }

    fun submitCode(c: Context, code: String) {
        val clean = code.trim()
        if (clean.isEmpty() || step == STEP_NONE) return
        val mode = step
        work(c) {
            if (mode == STEP_SIGNUP) {
                FoxApi.verifyEmail(pendingUid, clean)
                try {
                    finish(c.applicationContext, pendingToken, pendingUid, pendingEmail, pendingPassword)
                } catch (e: FoxError) {
                    val again = FoxApi.signIn(pendingEmail, pendingPassword)
                    pendingToken = again.token
                    if (again.needsCode) {
                        step = STEP_LOGIN
                        message = "code"
                    } else {
                        finish(c.applicationContext, again.token, again.uid, pendingEmail, pendingPassword)
                    }
                }
            } else {
                FoxApi.verifySession(pendingToken, clean)
                finish(c.applicationContext, pendingToken, pendingUid, pendingEmail, pendingPassword)
            }
        }
    }

    private fun finish(c: Context, sessionToken: String, uid: String, mail: String, password: String) {
        val tokens = FoxApi.tokens(sessionToken)
        FoxStore.saveAccount(c, mail, uid, tokens)
        step = STEP_NONE
        pendingPassword = ""
        val pass = FoxApi.pass(tokens.access)
        FoxStore.saveUsage(c, pass)
        message = ""
    }

    fun refreshUsage(c: Context) {
        if (!signedIn(c)) return
        work(c) {
            val access = validAccess(c)
            val pass = FoxApi.pass(access)
            FoxStore.saveUsage(c, pass)
            message = "quota"
        }
    }

    fun loadServers(c: Context) {
        if (servers.isNotEmpty()) return
        work(c) {
            servers = FoxApi.servers()
            message = "servers"
        }
    }

    fun measure(c: Context) {
        if (servers.isEmpty()) return
        val app = c.applicationContext
        busy = true
        notifyUi()
        thread {
            for (location in servers) {
                val key = location.host + ":" + location.port
                if (delays.containsKey(key)) continue
                delays[key] = ping(location.host, location.port)
            }
            busy = false
            message = "ping"
            notifyUi()
        }
    }

    fun delayOf(location: FoxLocation): Int = delays[location.host + ":" + location.port] ?: -1

    fun hasDelays(): Boolean = servers.any { delayOf(it) >= 0 }

    private fun ping(host: String, port: Int): Int {
        val start = System.nanoTime()
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(host, port), 4000)
            ((System.nanoTime() - start) / 1_000_000L).toInt()
        } catch (e: Exception) {
            -1
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
            }
        }
    }

    fun validAccess(c: Context): String {
        val token = FoxStore.access(c)
        if (token.isNotEmpty() && FoxStore.expiresAt(c) - 120_000L > System.currentTimeMillis()) return token
        val refresh = FoxStore.refreshToken(c)
        if (refresh.isEmpty()) throw FoxError("Sign in again")
        val fresh = FoxApi.refresh(refresh)
        FoxStore.saveAccess(c, fresh)
        return fresh.access
    }

    fun selectedLocation(c: Context): FoxLocation? {
        val code = FoxStore.selected(c)
        val list = servers
        if (list.isEmpty()) return null
        return list.firstOrNull { it.code == code } ?: list.firstOrNull { it.recommended } ?: list.first()
    }

    fun select(c: Context, location: FoxLocation) {
        FoxStore.setSelected(c, location.code)
    }

    fun signOut(c: Context) {
        FoxStore.clear(c)
        step = STEP_NONE
        email = ""
        limit = 0L
        remaining = 0L
        reset = ""
        message = ""
        delays.clear()
        notifyUi()
    }
}
