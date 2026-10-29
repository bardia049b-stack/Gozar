package com.gozar

import android.content.Context

object FoxStore {

    private fun p(c: Context) = c.getSharedPreferences("fox", Context.MODE_PRIVATE)

    fun email(c: Context): String = p(c).getString("email", "") ?: ""
    fun uid(c: Context): String = p(c).getString("uid", "") ?: ""
    fun access(c: Context): String = p(c).getString("access", "") ?: ""
    fun refreshToken(c: Context): String = p(c).getString("refresh", "") ?: ""
    fun expiresAt(c: Context): Long = p(c).getLong("expires_at", 0L)
    fun pass(c: Context): String = p(c).getString("pass", "") ?: ""
    fun passUntil(c: Context): Long = p(c).getLong("pass_until", 0L)
    fun limit(c: Context): Long = p(c).getLong("limit", 0L)
    fun remaining(c: Context): Long = p(c).getLong("remaining", 0L)
    fun reset(c: Context): String = p(c).getString("reset", "") ?: ""
    fun selected(c: Context): String = p(c).getString("selected", "REC") ?: "REC"

    fun setSelected(c: Context, code: String) = p(c).edit().putString("selected", code).apply()

    fun saveAccount(c: Context, email: String, uid: String, tokens: FoxTokens) {
        p(c).edit()
            .putString("email", email)
            .putString("uid", uid)
            .putString("access", tokens.access)
            .putString("refresh", tokens.refresh)
            .putLong("expires_at", System.currentTimeMillis() + tokens.expiresIn * 1000L)
            .apply()
    }

    fun saveAccess(c: Context, tokens: FoxTokens) {
        p(c).edit()
            .putString("access", tokens.access)
            .putString("refresh", tokens.refresh)
            .putLong("expires_at", System.currentTimeMillis() + tokens.expiresIn * 1000L)
            .apply()
    }

    fun saveUsage(c: Context, pass: FoxPass) {
        p(c).edit()
            .putString("pass", pass.token)
            .putLong("pass_until", System.currentTimeMillis() + 4 * 60 * 1000L)
            .putLong("limit", pass.limit)
            .putLong("remaining", pass.remaining)
            .putString("reset", pass.reset)
            .apply()
    }

    fun clear(c: Context) {
        val selected = selected(c)
        p(c).edit().clear().apply()
        setSelected(c, selected)
    }
}
