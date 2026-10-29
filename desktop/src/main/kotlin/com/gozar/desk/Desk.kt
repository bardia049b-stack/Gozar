package com.gozar.desk

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gozar.FoxApi
import com.gozar.FoxError
import com.gozar.FoxLocation
import com.gozar.FoxLog
import com.gozar.FoxPass
import com.gozar.FoxSession
import com.gozar.FoxTokens
import kotlin.concurrent.thread

object Desk {

    private const val STEP_NONE = 0
    private const val STEP_SIGNUP = 1
    private const val STEP_LOGIN = 2

    var email by mutableStateOf("")
    var limit by mutableStateOf(0L)
    var remaining by mutableStateOf(0L)
    var reset by mutableStateOf("")
    var busy by mutableStateOf(false)
    var stage by mutableStateOf("")
    var message by mutableStateOf("")
    var servers by mutableStateOf<List<FoxLocation>>(emptyList())
    var delays by mutableStateOf<Map<String, Int>>(emptyMap())
    var selectedCode by mutableStateOf("REC")
    var signedIn by mutableStateOf(false)
    var step by mutableStateOf(STEP_NONE)
    var byLink by mutableStateOf(false)
    var screen by mutableStateOf(0)
    var exitCountry by mutableStateOf("")

    var live by mutableStateOf(false)
    var connecting by mutableStateOf(false)
    var exitIp by mutableStateOf("")
    var down by mutableStateOf(0L)
    var up by mutableStateOf(0L)
    var downRate by mutableStateOf(0.0)
    var upRate by mutableStateOf(0.0)
    var since by mutableStateOf(0L)
    var fault by mutableStateOf("")
    var proxyPort by mutableStateOf(0)
    var systemProxy by mutableStateOf(false)

    private var pendingEmail = ""
    private var pendingPassword = ""
    private var pendingUid = ""
    private var pendingToken = ""

    fun version(): String = "1.7.1"

    fun say(text: String) {
        message = text
    }

    fun load() {
        email = Store.email()
        limit = Store.limit()
        remaining = Store.remaining()
        reset = Store.reset()
        selectedCode = Store.selected()
        signedIn = email.isNotEmpty() && Store.refresh().isNotEmpty()
    }

    fun needsStep(): Boolean = step != STEP_NONE

    fun isLinkStep(): Boolean = step != STEP_NONE && byLink

    private fun work(first: String, body: () -> Unit) {
        busy = true
        message = ""
        stage = first
        thread(isDaemon = true, name = "gozar-work") {
            var failure: String? = null
            try {
                body()
            } catch (e: FoxError) {
                failure = e.message ?: "Failed"
            } catch (e: Exception) {
                failure = e.message ?: "Network error"
            }
            busy = false
            stage = ""
            if (failure != null) message = failure
            load()
        }
    }

    fun signUp(mail: String, secret: String) {
        work("Creating your account…") {
            val session: FoxSession = try {
                FoxApi.register(mail, secret)
            } catch (e: FoxError) {
                if (e.errno == 101 || e.errno == 144) {
                    signInNow(mail, secret)
                    return@work
                }
                throw e
            }
            pendingEmail = mail
            pendingPassword = secret
            pendingUid = session.uid
            pendingToken = session.token
            byLink = false
            if (session.needsCode) {
                step = STEP_SIGNUP
                message = "code"
            } else {
                finish(session.token, session.uid, mail, secret)
            }
        }
    }

    fun signIn(mail: String, secret: String) {
        work("Signing in…") {
            signInNow(mail, secret)
        }
    }

    private fun signInNow(mail: String, secret: String) {
        stage = "Signing in…"
        val session = FoxApi.signIn(mail, secret)
        pendingEmail = mail
        pendingPassword = secret
        pendingUid = session.uid
        pendingToken = session.token
        byLink = session.byLink
        if (session.needsCode) {
            step = STEP_LOGIN
            message = if (byLink) "link" else "code"
        } else {
            finish(session.token, session.uid, mail, secret)
        }
    }

    fun confirmLink() {
        if (step == STEP_NONE) return
        work("Waiting for the confirmation…") {
            if (FoxApi.emailVerified(pendingToken)) {
                finish(pendingToken, pendingUid, pendingEmail, pendingPassword)
            } else {
                message = "link"
                throw FoxError("Open the link from the email once, then press Confirm again")
            }
        }
    }

    fun submitCode(code: String) {
        if (code.isEmpty() || step == STEP_NONE) return
        val mode = step
        work("Checking the code…") {
            if (mode == STEP_SIGNUP) {
                FoxApi.verifyEmail(pendingToken, pendingUid, code)
                finish(pendingToken, pendingUid, pendingEmail, pendingPassword)
            } else {
                FoxApi.verifySession(pendingToken, code)
                finish(pendingToken, pendingUid, pendingEmail, pendingPassword)
            }
        }
    }

    fun resend() {
        val mode = step
        work("Sending the code again…") {
            if (mode == STEP_SIGNUP) FoxApi.resendSignupCode(pendingToken)
            else FoxApi.signIn(pendingEmail, pendingPassword)
            message = if (byLink) "link" else "code"
        }
    }

    private fun finish(sessionToken: String, uid: String, mail: String, secret: String) {
        stage = "Getting your monthly pass…"
        val tokens: FoxTokens = FoxApi.tokens(sessionToken)
        Store.saveAccount(mail, uid, tokens.access, tokens.refresh, tokens.expiresIn)
        step = STEP_NONE
        byLink = false
        val pass = FoxApi.pass(tokens.access)
        Store.saveUsage(pass.token, pass.limit, pass.remaining, pass.reset)
        message = ""
    }

    fun refreshUsage() {
        if (!signedIn) return
        work("Refreshing your usage…") {
            val pass: FoxPass = FoxApi.pass(access())
            Store.saveUsage(pass.token, pass.limit, pass.remaining, pass.reset)
        }
    }

    fun signOut() {
        Connect.stop()
        Store.clear()
        step = STEP_NONE
        byLink = false
        email = ""
        limit = 0L
        remaining = 0L
        reset = ""
        message = ""
        delays = emptyMap()
        load()
    }

    fun loadServers() {
        if (servers.isNotEmpty()) return
        work("Loading the server list…") {
            servers = FoxApi.servers()
        }
    }

    fun measure() {
        if (servers.isEmpty() || busy) return
        busy = true
        stage = "Measuring every server…"
        thread(isDaemon = true, name = "gozar-measure") {
            val found = HashMap<String, Int>()
            for (location in servers) {
                found[key(location)] = ping(location.host, location.port)
                delays = HashMap(found)
            }
            busy = false
            stage = ""
            if (selectedCode == "REC" || selectedCode.isEmpty() || servers.none { it.code == selectedCode }) {
                val best = servers.filter { (found[key(it)] ?: -1) > 0 }.minByOrNull { found[key(it)] ?: Int.MAX_VALUE }
                if (best != null) select(best.code)
            }
        }
    }

    fun delayOf(location: FoxLocation): Int = delays[key(location)] ?: -1

    private fun key(location: FoxLocation): String = location.host + ":" + location.port

    private fun ping(host: String, port: Int): Int {
        val start = System.nanoTime()
        val socket = java.net.Socket()
        return try {
            socket.connect(java.net.InetSocketAddress(host, port), 4000)
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

    fun select(code: String) {
        selectedCode = code
        Store.setSelected(code)
    }

    fun selected(): FoxLocation? {
        if (servers.isEmpty()) return null
        return servers.firstOrNull { it.code == selectedCode } ?: servers.firstOrNull { it.recommended } ?: servers.first()
    }

    fun access(): String {
        val token = Store.access()
        if (token.isNotEmpty() && Store.expiresAt() - 120_000L > System.currentTimeMillis()) return token
        val refresh = Store.refresh()
        if (refresh.isEmpty()) throw FoxError("Sign in again")
        val fresh = FoxApi.refresh(refresh)
        Store.saveTokens(fresh.access, fresh.refresh, fresh.expiresIn)
        return fresh.access
    }

    fun currentPass(): String {
        val stored = Store.pass()
        if (stored.isNotEmpty()) return stored
        val pass = FoxApi.pass(access())
        Store.saveUsage(pass.token, pass.limit, pass.remaining, pass.reset)
        return pass.token
    }

    fun startTicker() {
        thread(isDaemon = true, name = "gozar-meter") {
            var lastDown = down
            var lastUp = up
            var lastAt = System.currentTimeMillis()
            while (live) {
                Thread.sleep(1000)
                if (!live) break
                val now = System.currentTimeMillis()
                val span = (now - lastAt).coerceAtLeast(1)
                downRate = (down - lastDown) * 1000.0 / span
                upRate = (up - lastUp) * 1000.0 / span
                lastDown = down
                lastUp = up
                lastAt = now
            }
            downRate = 0.0
            upRate = 0.0
        }
    }

    fun elapsed(): Long = if (live && since > 0L) (System.currentTimeMillis() - since) / 1000L else 0L

    init {
        FoxLog.enabled = false
    }
}
