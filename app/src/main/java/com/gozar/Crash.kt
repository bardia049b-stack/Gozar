package com.gozar

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Crash {

    private fun file(c: Context) = File(c.filesDir, "last-crash.txt")

    fun install(c: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val writer = StringWriter()
                error.printStackTrace(PrintWriter(writer))
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                file(c).writeText("$stamp\n${thread.name}\n\n${writer.toString()}", Charsets.UTF_8)
            } catch (ignored: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun log(c: Context): String? {
        val f = file(c)
        if (!f.exists()) return null
        return try {
            f.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun clear(c: Context) {
        try {
            file(c).delete()
        } catch (e: Exception) {
        }
    }
}
