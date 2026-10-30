package com.gozar

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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
    @Volatile var stage: String = ""
    @Volatile var servers: List<FoxLocation> = emptyList()

    @Volatile private var byLink = false
    private var step = STEP_NONE
    private var pendingEmail = ""
    private var pendingPassword = ""
    private var pendingUid = ""
    private var pendingToken = ""

    var onChange: (() -> Unit)? = null

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun watch(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun unwatch(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun load(c: Context) {
        email = FoxStore.email(c)
        limit = FoxStore.limit(c)
        remaining = FoxStore.remaining(c)
        reset = FoxStore.reset(c)
    }

    fun signedIn(c: Context): Boolean = FoxStore.email(c).isNotEmpty() && FoxStore.refreshToken(c).isNotEmpty()

    fun needsCode(): Boolean = step != STEP_NONE && !byLink

    fun needsStep(): Boolean = step != STEP_NONE

    private fun notifyUi() {
        main.post {
            onChange?.invoke()
            for (listener in listeners) {
                try {
                    listener()
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun say(c: Context, text: Int) {
        stage = c.getString(text)
        notifyUi()
    }

    private fun work(c: Context, first: Int = 0, body: () -> Unit) {
        busy = true
        message = ""
        if (first != 0) stage = c.getString(first)
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
            stage = ""
            if (failure != null) message = failure
            load(app)
            notifyUi()
        }
    }

    fun signUp(c: Context, mail: String, password: String) {
        val clean = mail.trim()
        work(c, R.string.fox_stage_signup) {
            val session = try {
                FoxApi.register(clean, password)
            } catch (e: FoxError) {
                if (e.errno == 101 || e.errno == 144) {
                    signInNow(c.applicationContext, clean, password, true)
                    return@work
                }
                throw e
            }
            pendingEmail = clean
            pendingPassword = password
            pendingUid = session.uid
            pendingToken = session.token
            byLink = false
            if (session.needsCode) {
                step = STEP_SIGNUP
                message = "code"
            } else {
                finish(c.applicationContext, session.token, session.uid, clean, password)
            }
        }
    }

    private fun signInNow(c: Context, mail: String, password: String, created: Boolean) {
        if (created) message = ""
        val session = FoxApi.signIn(mail, password)
        pendingEmail = mail
        pendingPassword = password
        pendingUid = session.uid
        pendingToken = session.token
        byLink = session.byLink
        if (session.needsCode) {
            step = STEP_LOGIN
            message = if (byLink) "link" else "code"
        } else {
            finish(c, session.token, session.uid, mail, password)
        }
    }

    fun isLinkStep(): Boolean = step != STEP_NONE && byLink

    fun confirmLink(c: Context) {
        if (step == STEP_NONE) return
        work(c) {
            say(c, R.string.fox_stage_link)
            try {
                if (FoxApi.emailVerified(pendingToken)) {
                    val again = FoxApi.signIn(pendingEmail, pendingPassword)
                    pendingToken = again.token
                    pendingUid = again.uid
                    if (!again.needsCode) {
                        finish(c.applicationContext, again.token, again.uid, pendingEmail, pendingPassword)
                        return@work
                    }
                    byLink = again.byLink
                    message = if (byLink) "link" else "code"
                    throw FoxError(if (byLink) "The email link is not confirmed yet" else "Mozilla emailed a code to finish signing in")
                }
                finish(c.applicationContext, pendingToken, pendingUid, pendingEmail, pendingPassword)
            } catch (e: FoxError) {
                val again = FoxApi.signIn(pendingEmail, pendingPassword)
                pendingToken = again.token
                if (again.needsCode) {
                    byLink = again.byLink
                    message = if (byLink) "link" else "code"
                    throw FoxError(if (byLink) "The email link is not confirmed yet" else "Mozilla now wants the emailed code")
                }
                finish(c.applicationContext, again.token, again.uid, pendingEmail, pendingPassword)
            }
        }
    }

    fun signIn(c: Context, mail: String, password: String) {
        val clean = mail.trim()
        work(c, R.string.fox_stage_signin) {
            signInNow(c.applicationContext, clean, password, false)
        }
    }

    fun submitCode(c: Context, raw: String) {
        val clean = cleanCode(raw)
        if (clean.isEmpty() || step == STEP_NONE) return
        val mode = step
        work(c) {
            say(c, R.string.fox_stage_code)
            if (mode == STEP_SIGNUP) {
                FoxApi.verifyEmail(pendingToken, pendingUid, clean)
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

    private fun cleanCode(raw: String): String {
        val text = raw.trim()
        if (text.contains("code=")) {
            val code = text.substringAfter("code=").substringBefore("&").substringBefore(" ").trim()
            if (code.isNotEmpty()) return code
        }
        return text.filter { !it.isWhitespace() && it != '-' }
    }

    fun resend(c: Context) {
        if (step == STEP_NONE) return
        val mode = step
        val app = c.applicationContext
        work(c) {
            if (mode == STEP_SIGNUP) {
                FoxApi.resendSignupCode(pendingToken)
                message = "code"
            } else {
                signInNow(app, pendingEmail, pendingPassword, false)
            }
        }
    }

    private fun finish(c: Context, sessionToken: String, uid: String, mail: String, password: String) {
        say(c, R.string.fox_stage_pass)
        val tokens = FoxApi.tokens(sessionToken)
        FoxStore.saveAccount(c, mail, uid, tokens)
        step = STEP_NONE
        byLink = false
        pendingPassword = ""
        val pass = FoxApi.pass(tokens.access)
        FoxStore.saveUsage(c, pass)
        message = ""
    }

    fun refreshUsage(c: Context) {
        if (!signedIn(c)) return
        work(c, R.string.fox_stage_usage) {
            val access = validAccess(c)
            val pass = FoxApi.pass(access)
            FoxStore.saveUsage(c, pass)
            message = "quota"
        }
    }

    fun loadServers(c: Context) {
        if (servers.isNotEmpty()) return
        work(c, R.string.fox_stage_servers) {
            servers = FoxApi.servers()
            message = "servers"
        }
    }

    fun measure(c: Context) {
        if (servers.isEmpty()) return
        val app = c.applicationContext
        busy = true
        stage = c.getString(R.string.fox_stage_measure)
        notifyUi()
        thread {
            for (location in servers) {
                val key = location.host + ":" + location.port
                if (delays.containsKey(key)) continue
                delays[key] = ping(location.host, location.port)
            }
            val chosen = FoxStore.selected(app)
            if (chosen == "REC" || chosen.isEmpty() || servers.none { it.code == chosen }) {
                val best = servers.filter { delayOf(it) > 0 }.minByOrNull { delayOf(it) }
                if (best != null) FoxStore.setSelected(app, best.code)
            }
            busy = false
            stage = ""
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
        byLink = false
        email = ""
        limit = 0L
        remaining = 0L
        reset = ""
        message = ""
        delays.clear()
        notifyUi()
    }
}
