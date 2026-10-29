package com.gozar.desk

import java.io.File
import java.util.concurrent.TimeUnit

object Diag {

    fun folder(): File {
        val base = System.getenv("LOCALAPPDATA") ?: System.getenv("APPDATA") ?: System.getProperty("user.home")
        val dir = File(base, "Gozar")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun command(vararg parts: String): String {
        return try {
            val process = ProcessBuilder(*parts).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText()
            process.waitFor(20, TimeUnit.SECONDS)
            text.trim().ifEmpty { "(no output)" }
        } catch (e: Exception) {
            "ERROR: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    fun report(): String {
        val out = StringBuilder()
        out.appendLine("Gozar " + Desk.version() + " report")
        out.appendLine("os      : " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"))
        out.appendLine("admin   : " + Elevate.isAdmin())
        out.appendLine("launcher: " + (Elevate.target() ?: "(unknown)"))
        out.appendLine("wintun  : " + (TunService.wintunPath() ?: "(not found)"))
        out.appendLine("tunnel  : " + if (TunEngine.isRunning()) "running" else "stopped")
        out.appendLine("proxy   : " + SystemProxy.backend() + " port " + SystemProxy.port())
        out.appendLine("fullTun : " + Store.fullTunnel())
        out.appendLine("")
        out.appendLine("notes:")
        out.appendLine(TunService.log().ifEmpty { "(none)" })
        if (Elevate.windows()) {
            out.appendLine("")
            out.appendLine("adapters:")
            out.appendLine(command("powershell.exe", "-NoProfile", "-Command",
                "Get-NetAdapter | Select-Object Name,Status,InterfaceDescription | Format-Table -AutoSize | Out-String -Width 200"))
            out.appendLine("routes:")
            out.appendLine(command("route", "print", "-4"))
            out.appendLine("wintun service:")
            out.appendLine(command("sc.exe", "query", "Wintun"))
            out.appendLine("internet settings:")
            out.appendLine(command("reg", "query",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings", "/v", "ProxyEnable"))
            out.appendLine(command("reg", "query",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings", "/v", "ProxyServer"))
        }
        return out.toString()
    }

    fun save(): File {
        val file = File(folder(), "gozar-report.txt")
        return try {
            file.writeText(report())
            file
        } catch (e: Exception) {
            file
        }
    }
}
