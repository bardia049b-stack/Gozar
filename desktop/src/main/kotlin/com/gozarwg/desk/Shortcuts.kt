package com.gozarwg.desk

import java.io.File
import java.util.concurrent.TimeUnit

object Shortcuts {

    private val os = System.getProperty("os.name").orEmpty().lowercase()

    private fun launcher(): String {
        val fromPackage = System.getProperty("jpackage.app-path").orEmpty()
        if (fromPackage.isNotEmpty()) return fromPackage
        return ProcessHandle.current().info().command().orElse("")
    }

    private fun iconFile(): File? {
        return try {
            val base = when {
                os.contains("win") -> File(System.getenv("APPDATA") ?: return null, "GozarWG")
                else -> File(System.getProperty("user.home"), ".local/share/icons")
            }
            if (!base.exists()) base.mkdirs()
            val target = if (os.contains("win")) File(base, "gozarwg.png") else File(base, "gozarwg.png")
            if (!target.exists() || target.length() < 1024L) {
                Shortcuts::class.java.getResourceAsStream("/fox_logo.png")?.use { stream ->
                    target.outputStream().use { out -> stream.copyTo(out) }
                }
            }
            target
        } catch (e: Exception) {
            null
        }
    }

    fun ensure(): Boolean {
        return try {
            when {
                os.contains("win") -> windows()
                os.contains("mac") || os.contains("darwin") -> true
                else -> linux()
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun windows(): Boolean {
        val exe = launcher()
        if (exe.isEmpty()) return false
        val script = """
            ${'$'}shell = New-Object -ComObject WScript.Shell
            ${'$'}desktop = [Environment]::GetFolderPath('Desktop')
            ${'$'}link = ${'$'}shell.CreateShortcut("${'$'}desktop\GozarWG.lnk")
            ${'$'}link.TargetPath = '$exe'
            ${'$'}link.WorkingDirectory = Split-Path '$exe'
            ${'$'}link.IconLocation = '$exe,0'
            ${'$'}link.Description = 'GozarWG'
            ${'$'}link.Save()
        """.trimIndent()
        val file = File.createTempFile("gozarwg-link", ".ps1")
        file.writeText(script)
        val ok = run("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", file.absolutePath)
        file.delete()
        return ok
    }

    private fun linux(): Boolean {
        val exe = launcher()
        if (exe.isEmpty()) return false
        val home = File(System.getProperty("user.home"))
        val icon = iconFile()?.absolutePath ?: exe
        val entry = """
            [Desktop Entry]
            Type=Application
            Version=1.0
            Name=GozarWG
            Comment=Firefox VPN and WireGuard
            Exec=$exe
            Icon=$icon
            Terminal=false
            Categories=Network;Security;
            StartupWMClass=GozarWG
        """.trimIndent().plus("\n")
        val applications = File(home, ".local/share/applications")
        applications.mkdirs()
        File(applications, "gozarwg.desktop").writeText(entry)
        val desktop = File(home, "Desktop")
        if (desktop.exists() || desktop.mkdirs()) {
            val shortcut = File(desktop, "gozarwg.desktop")
            shortcut.writeText(entry)
            shortcut.setExecutable(true)
            run("gio", "set", shortcut.absolutePath, "metadata::trusted", "true")
        }
        run("update-desktop-database", applications.absolutePath)
        return true
    }

    fun openConfigFolder(): Boolean {
        val folder = Store.folder()
        return when {
            os.contains("win") -> run("explorer", folder.absolutePath)
            os.contains("mac") || os.contains("darwin") -> run("open", folder.absolutePath)
            else -> run("xdg-open", folder.absolutePath)
        }
    }

    private fun run(vararg command: String): Boolean {
        return try {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            process.waitFor(20, TimeUnit.SECONDS)
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }
}
