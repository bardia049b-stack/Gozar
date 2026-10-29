package com.gozar.desk

import com.gozar.FoxApi

object SelfTest {

    fun run(): String {
        val report = StringBuilder()
        var failures = 0
        fun check(name: String, ok: Boolean, detail: String = "") {
            if (!ok) failures++
            report.append(if (ok) "ok   " else "FAIL ").append(name)
            if (detail.isNotEmpty()) report.append(" -> ").append(detail)
            report.append('\n')
        }
        check("session file", Store.email().isNotEmpty() || Store.email().isEmpty())
        val servers = try {
            FoxApi.servers()
        } catch (e: Exception) {
            report.append("FAIL server list -> ").append(e.message).append('\n')
            failures++
            emptyList()
        }
        check("server list", servers.isNotEmpty(), servers.size.toString())
        if (servers.isNotEmpty()) {
            val first = servers.first()
            check("server fields", first.host.isNotEmpty() && first.port > 0, first.host + ":" + first.port)
            val start = System.nanoTime()
            val reachable = try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(first.host, first.port), 4000)
                    true
                }
            } catch (e: Exception) {
                false
            }
            check("proxy reachable", reachable,
                ((System.nanoTime() - start) / 1_000_000L).toString() + " ms")
        }
        check("system proxy backend", SystemProxy.backend() in setOf("registry", "networksetup", "gsettings"),
            SystemProxy.backend())
        report.append(if (failures == 0) "ALL OK" else "FAILURES: $failures")
        return report.toString()
    }
}
