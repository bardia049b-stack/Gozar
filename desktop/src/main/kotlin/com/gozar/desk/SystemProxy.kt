package com.gozar.desk

import java.util.concurrent.TimeUnit

object SystemProxy {

    private val os = System.getProperty("os.name").orEmpty().lowercase()

    private fun windows(): Boolean = os.contains("win")

    private fun mac(): Boolean = os.contains("mac") || os.contains("darwin")

    private fun run(vararg command: String): Boolean {
        return try {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            process.waitFor(20, TimeUnit.SECONDS)
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    fun backend(): String = when {
        windows() -> "registry"
        mac() -> "networksetup"
        else -> "gsettings"
    }

    fun enable(port: Int): Boolean {
        return when {
            windows() -> registry(port, true)
            mac() -> elevate(
                listOf(
                    "networksetup -setwebproxy \"$1\" 127.0.0.1 $port",
                    "networksetup -setsecurewebproxy \"$1\" 127.0.0.1 $port",
                    "networksetup -setsocksfirewallproxy \"$1\" 127.0.0.1 $port"
                )
            )
            else -> gnome(port, true)
        }
    }

    fun disable(): Boolean {
        return when {
            windows() -> registry(0, false)
            mac() -> elevate(
                listOf(
                    "networksetup -setwebproxystate \"$1\" off",
                    "networksetup -setsecurewebproxystate \"$1\" off",
                    "networksetup -setsocksfirewallproxystate \"$1\" off"
                )
            )
            else -> gnome(0, false)
        }
    }

    private fun registry(port: Int, on: Boolean): Boolean {
        if (!on) {
            val ok = run("reg", "add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings",
                "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", "0", "/f")
            run("netsh", "winhttp", "reset", "proxy")
            return ok
        }
        val ok = run("reg", "add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings",
            "/v", "ProxyServer", "/t", "REG_SZ", "/d", "127.0.0.1:$port", "/f")
        val enabled = run("reg", "add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings",
            "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", "1", "/f")
        run("netsh", "winhttp", "set", "proxy", "127.0.0.1:$port")
        return ok && enabled
    }

    /**
     * macOS keeps the proxy per network service and needs an administrator for the write, so the
     * commands run through one osascript prompt instead of three separate ones.
     */
    private fun elevate(templates: List<String>): Boolean {
        val script = """
            set services to {}
            try
              set services to paragraphs of (do shell script "networksetup -listallnetworkservices | tail -n +2")
            end try
            repeat with svc in services
              if svc does not start with "*" then
                repeat with t in {${templates.joinToString(", ") { "\"${it.replace("\"", "\\\"")}\"" }}}
                  try
                    set cmd to my replaceText(t, "${'$'}1", svc)
                    do shell script cmd with administrator privileges
                  end try
                end repeat
              end if
            end repeat
            on replaceText(theText, findText, replaceText)
              set theText to theText as text
              set AppleScript's text item delimiters to findText
              set parts to text items of theText
              set AppleScript's text item delimiters to replaceText
              set theText to parts as text
              set AppleScript's text item delimiters to ""
              return theText
            end replaceText
        """.trimIndent()
        return run("osascript", "-e", script)
    }

    private fun gnome(port: Int, on: Boolean): Boolean {
        if (!on) {
            return run("gsettings", "set", "org.gnome.system.proxy", "mode", "none")
        }
        var ok = run("gsettings", "set", "org.gnome.system.proxy", "mode", "manual")
        ok = run("gsettings", "set", "org.gnome.system.proxy.http", "host", "127.0.0.1") && ok
        ok = run("gsettings", "set", "org.gnome.system.proxy.http", "port", port.toString()) && ok
        ok = run("gsettings", "set", "org.gnome.system.proxy.https", "host", "127.0.0.1") && ok
        ok = run("gsettings", "set", "org.gnome.system.proxy.https", "port", port.toString()) && ok
        ok = run("gsettings", "set", "org.gnome.system.proxy.socks", "host", "127.0.0.1") && ok
        ok = run("gsettings", "set", "org.gnome.system.proxy.socks", "port", port.toString()) && ok
        return ok
    }
}
