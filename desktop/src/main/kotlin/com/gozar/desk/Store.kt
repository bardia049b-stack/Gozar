package com.gozar.desk

import java.io.File
import java.util.Properties

object Store {

    fun folder(): File {
        val home = System.getProperty("user.home") ?: "."
        val base = when {
            System.getProperty("os.name").orEmpty().lowercase().contains("win") ->
                File(System.getenv("APPDATA") ?: home, "Gozar")
            else -> File(home, ".config/gozar")
        }
        base.mkdirs()
        return base
    }

    private val file: File by lazy { File(folder(), "session.properties") }

    private fun read(): Properties {
        val properties = Properties()
        try {
            if (file.exists()) file.inputStream().use { properties.load(it) }
        } catch (e: Exception) {
        }
        return properties
    }

    private fun write(properties: Properties) {
        try {
            file.outputStream().use { properties.store(it, "gozar") }
        } catch (e: Exception) {
        }
    }

    private fun get(key: String): String = read().getProperty(key).orEmpty()

    private fun put(vararg pairs: Pair<String, String>) {
        val properties = read()
        for ((key, value) in pairs) properties.setProperty(key, value)
        write(properties)
    }

    fun email(): String = get("email")
    fun uid(): String = get("uid")
    fun access(): String = get("access")
    fun refresh(): String = get("refresh")
    fun expiresAt(): Long = get("expires_at").toLongOrNull() ?: 0L
    fun pass(): String = get("pass")
    fun limit(): Long = get("limit").toLongOrNull() ?: 0L
    fun remaining(): Long = get("remaining").toLongOrNull() ?: 0L
    fun reset(): String = get("reset")
    fun selected(): String = get("selected").ifEmpty { "REC" }
    fun bypassLan(): Boolean = get("bypass_lan") == "1"
    fun setBypassLan(value: Boolean) = put("bypass_lan" to if (value) "1" else "0")
    fun bypassList(): String = get("bypass_list")
    fun setBypassList(value: String) = put("bypass_list" to value)
    fun fullTunnel(): Boolean = get("full_tunnel").ifEmpty { "1" } == "1"
    fun setFullTunnel(value: Boolean) = put("full_tunnel" to if (value) "1" else "0")
    fun chain(): String = get("chain")
    fun setChain(value: String) = put("chain" to value)

    fun saveAccount(email: String, uid: String, access: String, refresh: String, expiresIn: Int) =
        put(
            "email" to email,
            "uid" to uid,
            "access" to access,
            "refresh" to refresh,
            "expires_at" to (System.currentTimeMillis() + expiresIn * 1000L).toString()
        )

    fun saveTokens(access: String, refresh: String, expiresIn: Int) =
        put(
            "access" to access,
            "refresh" to refresh,
            "expires_at" to (System.currentTimeMillis() + expiresIn * 1000L).toString()
        )

    fun saveUsage(token: String, limit: Long, remaining: Long, reset: String) =
        put(
            "pass" to token,
            "limit" to limit.toString(),
            "remaining" to remaining.toString(),
            "reset" to reset
        )

    fun setSelected(code: String) = put("selected" to code)

    fun clear() {
        val selected = selected()
        val properties = read()
        val email = properties.getProperty("email").orEmpty()
        properties.clear()
        properties.setProperty("selected", selected)
        if (email.isNotEmpty()) properties.remove("email")
        write(properties)
    }
}
