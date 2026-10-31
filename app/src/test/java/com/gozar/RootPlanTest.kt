package com.gozar

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootPlanTest {
    private fun stat(): Pair<Int, String> {
        val pid = File("/proc/self").canonicalFile.name.toInt()
        val fields = File("/proc/self/stat").readText().substringAfterLast(')').trim().split(Regex("\\s+"))
        return pid to fields[19]
    }

    private fun fake(dir: File, name: String, body: String) {
        File(dir, name).apply { writeText("#!/bin/sh\n$body\n"); setExecutable(true) }
    }

    private fun setup(failure: String = "", missingParent: Boolean = false): Triple<Process, File, File> {
        val dir = Files.createTempDirectory("gozar-root-test").toFile()
        val log = File(dir, "calls")
        val flag = File(dir, "active").apply { writeText("active") }
        fake(dir, "id", "printf '0\\n'")
        fake(dir, "ip", """
            echo "ip ${'$'}*" >> "${'$'}TEST_LOG"
            case "${'$'}*" in *"${'$'}FAIL"*) [ -z "${'$'}FAIL" ] || exit 1;; esac
            exit 0
        """.trimIndent())
        fake(dir, "iptables", """
            echo "iptables ${'$'}*" >> "${'$'}TEST_LOG"
            case "${'$'}*" in *"-t mangle -S "*) exit 1;; esac
            case "${'$'}*" in *"${'$'}FAIL"*) [ -z "${'$'}FAIL" ] || exit 1;; esac
            exit 0
        """.trimIndent())
        val (pid, start) = stat()
        val plan = RootPlan("tun0", pid, if (missingParent) "0" else start, flag.absolutePath, "a1b2c3d4", 31337, 7777, true)
        val builder = ProcessBuilder("/bin/sh", "-c", plan.commands()).redirectErrorStream(true)
        builder.environment()["PATH"] = "${dir.absolutePath}:${System.getenv("PATH")}"
        builder.environment()["TEST_LOG"] = log.absolutePath
        builder.environment()["FAIL"] = failure
        return Triple(builder.start(), flag, log)
    }

    @Test(timeout = 8000)
    fun normalStopRemovesOnlyTheInstalledPlan() {
        val (process, flag, log) = setup()
        val line = process.inputStream.bufferedReader().readLine()
        assertTrue(line == "GOZAR_ROOT_READY")
        flag.delete()
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        val calls = log.readText()
        assertTrue(calls.contains("iptables -t mangle -D OUTPUT -m owner --uid-owner 0 -j GZRa1b2c3d4"))
        assertTrue(calls.contains("iptables -t nat -D POSTROUTING"))
        assertTrue(calls.contains("ip -4 rule del pref 7777"))
        assertTrue(calls.contains("ip -4 route del table 31337"))
        assertTrue(calls.contains("-m mark --mark 0x20000/0x20000 -j RETURN"))
        assertTrue(calls.contains("-d 192.168.0.0/16 -j RETURN"))
    }

    @Test(timeout = 8000)
    fun failedInstallationRollsBackWithoutEnablingOutput() {
        val (process, _, log) = setup("nat -I POSTROUTING")
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        val calls = log.readText()
        assertTrue(calls.contains("iptables -t mangle -F GZRa1b2c3d4"))
        assertTrue(calls.contains("ip -4 rule del pref 7777"))
        assertTrue(calls.contains("ip -4 route del table 31337"))
        assertFalse(calls.contains("iptables -t mangle -I OUTPUT"))
        assertFalse(calls.contains("iptables -t nat -D POSTROUTING"))
    }

    @Test(timeout = 8000)
    fun watchdogAttemptsCleanupWhenTheParentIdentityChanges() {
        val (process, _, log) = setup(missingParent = true)
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        val calls = log.readText()
        assertTrue(calls.contains("iptables -t mangle -D OUTPUT"))
        assertTrue(calls.contains("iptables -t nat -D POSTROUTING"))
        assertTrue(calls.contains("ip -4 rule del pref 7777"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun interfaceNamesCannotInjectShellCommands() {
        RootPlan("tun0;touch /tmp/no", 1, "12", "/tmp/x", "a1b2c3d4", 31337, 7777, false)
    }
}
