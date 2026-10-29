package com.gozar.desk

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object Elevate {

    private val os = System.getProperty("os.name").orEmpty().lowercase()

    fun windows(): Boolean = os.contains("win")

    fun mac(): Boolean = os.contains("mac") || os.contains("darwin")

    fun linux(): Boolean = !windows() && !mac()

    private var cached: Boolean? = null

    fun isAdmin(): Boolean {
        cached?.let { return it }
        val result = when {
            windows() -> exits("fltmc.exe") || exits("net", "session")
            linux() -> try {
                File("/proc/self/status").readLines()
                    .firstOrNull { it.startsWith("Uid:") }
                    ?.trim()?.split(Regex("\\s+"))?.getOrNull(1) == "0"
            } catch (e: Exception) {
                false
            }
            else -> true
        }
        cached = result
        return result
    }

    private fun exits(vararg command: String): Boolean {
        return try {
            val process = ProcessBuilder(*command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            process.waitFor(10, TimeUnit.SECONDS)
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    fun target(): String? {
        val property = System.getProperty("jpackage.app-path")
        if (!property.isNullOrEmpty()) {
            val file = File(property)
            if (file.exists()) return file.absolutePath
        }
        val command = System.getProperty("sun.java.command").orEmpty().substringBefore(' ')
        return when {
            command.endsWith(".jar") -> File(command).absolutePath
            else -> null
        }
    }

    fun relaunchAsAdmin(): Boolean {
        val executable = target()
        if (windows()) {
            val script = if (executable != null && File(executable).isFile) {
                "Start-Process -FilePath '" + executable.replace("'", "''") + "' -Verb RunAs"
            } else {
                val java = File(System.getProperty("java.home"), "bin/javaw.exe")
                "Start-Process -FilePath '" + java.absolutePath.replace("'", "''") +
                    "' -ArgumentList '-jar','" +
                    File(System.getProperty("sun.java.command").orEmpty().substringBefore(' '))
                        .absolutePath.replace("'", "''") + "' -Verb RunAs"
            }
            val started = try {
                val process = ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-WindowStyle", "Hidden", "-Command", script
                ).redirectErrorStream(true).start()
                process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0
            } catch (e: Exception) {
                false
            }
            if (started) {
                Thread.sleep(400)
                Runtime.getRuntime().halt(0)
            }
            return started
        }
        if (linux() && executable != null) {
            return try {
                ProcessBuilder("pkexec", executable).start()
                Thread.sleep(400)
                Runtime.getRuntime().halt(0)
                true
            } catch (e: Exception) {
                false
            }
        }
        return false
    }

    fun restartInBackground() {
        thread(isDaemon = true, name = "gozar-elevate") { relaunchAsAdmin() }
    }
}
