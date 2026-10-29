package com.gozarwg

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FoxLog {

    private const val LIMIT = 1200
    private val lines = ArrayList<String>(LIMIT)
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile var enabled = true

    fun add(tag: String, text: String) {
        if (!enabled) return
        val stamp = clock.format(Date())
        synchronized(lines) {
            if (lines.size >= LIMIT) lines.removeAt(0)
            lines.add("$stamp [$tag] ${text.replace("\n", " ").take(600)}")
        }
    }

    fun secret(label: String, value: String) {
        if (value.isEmpty()) {
            add("pass", "$label empty")
            return
        }
        val head = value.take(14)
        add("pass", "$label len=${value.length} head=$head…")
        val parts = value.split(".")
        if (parts.size == 3) {
            val claims = decode(parts[1])
            add("pass", "$label claims=$claims")
        } else {
            add("pass", "$label is not a JWT (${parts.size} parts)")
        }
    }

    fun dump(): String = synchronized(lines) { lines.joinToString("\n") }

    fun clear() {
        synchronized(lines) { lines.clear() }
    }

    fun count(): Int = synchronized(lines) { lines.size }

    private fun decode(part: String): String {
        val text = StringBuilder()
        var buffer = 0
        var bits = 0
        for (mark in part) {
            val value = when (mark) {
                in 'A'..'Z' -> mark - 'A'
                in 'a'..'z' -> mark - 'a' + 26
                in '0'..'9' -> mark - '0' + 52
                '-' -> 62
                '_' -> 63
                '=' -> break
                else -> return "unreadable"
            }
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                text.append(((buffer shr bits) and 0xFF).toChar())
            }
        }
        return text.toString().take(260)
    }
}
