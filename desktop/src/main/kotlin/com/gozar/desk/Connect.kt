package com.gozar.desk

import com.gozar.FoxApi
import com.gozar.FoxError
import com.gozar.FoxLocation
import com.gozar.FoxTunnel
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class LocalProxy(private val port: Int, private val tunnel: (String, Int) -> com.gozar.FoxPipe) {

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false

    fun start(): Boolean {
        return try {
            val socket = ServerSocket(port, 64, InetAddress.getByName("127.0.0.1"))
            server = socket
            running = true
            thread(isDaemon = true, name = "gozar-proxy") { accept(socket) }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun stop() {
        running = false
        TunEngine.stop()
        Desk.tunActive = false
        try {
            server?.close()
        } catch (e: Exception) {
        }
        server = null
    }

    private fun accept(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                break
            }
            thread(isDaemon = true, name = "gozar-client") { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.tcpNoDelay = true
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            input.mark(1)
            val first = input.read()
            input.reset()
            if (first == 0x05) socks(input, output) else web(input, output)
        } catch (e: Exception) {
        } finally {
            try {
                client.close()
            } catch (e: Exception) {
            }
        }
    }

    private fun socks(input: InputStream, output: BufferedOutputStream) {
        val version = input.read()
        if (version != 0x05) return
        val count = input.read()
        if (count <= 0) return
        skip(input, count)
        output.write(byteArrayOf(0x05, 0x00))
        output.flush()
        val head = ByteArray(4)
        if (full(input, head) < 0) return
        if (head[1].toInt() != 0x01) {
            output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            return
        }
        val host = when (head[3].toInt()) {
            0x01 -> {
                val raw = ByteArray(4)
                if (full(input, raw) < 0) return
                InetAddress.getByAddress(raw).hostAddress ?: return
            }
            0x03 -> {
                val size = input.read()
                if (size <= 0) return
                val raw = ByteArray(size)
                if (full(input, raw) < 0) return
                String(raw, Charsets.UTF_8)
            }
            0x04 -> {
                val raw = ByteArray(16)
                if (full(input, raw) < 0) return
                InetAddress.getByAddress(raw).hostAddress ?: return
            }
            else -> return
        }
        val tail = ByteArray(2)
        if (full(input, tail) < 0) return
        val target = ((tail[0].toInt() and 0xFF) shl 8) or (tail[1].toInt() and 0xFF)
        val pipe = open(host, target)
        if (pipe == null) {
            output.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            return
        }
        output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        output.flush()
        pump(input, output, pipe)
    }

    private fun web(input: InputStream, output: BufferedOutputStream) {
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val first = lines.firstOrNull()?.split(" ") ?: return
        if (first.size < 3) return
        val method = first[0]
        val target = first[1]
        if (method.equals("CONNECT", true)) {
            val colon = target.lastIndexOf(':')
            if (colon <= 0) return
            val host = target.substring(0, colon).trim('[', ']')
            val port = target.substring(colon + 1).toIntOrNull() ?: 443
            val pipe = open(host, port)
            if (pipe == null) {
                output.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                output.flush()
                return
            }
            output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
            output.flush()
            pump(input, output, pipe)
            return
        }
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            output.write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
            output.flush()
            return
        }
        val url = java.net.URL(target)
        if (url.protocol == "https") {
            output.write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
            output.flush()
            return
        }
        val port = if (url.port > 0) url.port else 80
        val pipe = open(url.host, port) ?: return
        val path = (url.path.ifEmpty { "/" }) + (url.query?.let { "?$it" } ?: "")
        val rebuilt = StringBuilder("$method $path ${first[2]}\r\n")
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val name = line.substringBefore(':').trim()
            if (name.equals("Proxy-Connection", true)) continue
            if (name.equals("Connection", true)) continue
            if (name.equals("Proxy-Authorization", true)) continue
            rebuilt.append(line).append("\r\n")
        }
        rebuilt.append("Connection: close\r\n\r\n")
        val raw = rebuilt.toString().toByteArray(Charsets.UTF_8)
        pipe.write(raw, 0, raw.size)
        pump(input, output, pipe)
    }

    private fun open(host: String, port: Int): com.gozar.FoxPipe? {
        return try {
            tunnel(host, port)
        } catch (e: Exception) {
            null
        }
    }

    private fun pump(input: InputStream, output: BufferedOutputStream, pipe: com.gozar.FoxPipe) {
        val up = thread(isDaemon = true, name = "gozar-up") {
            val buffer = ByteArray(16384)
            try {
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    pipe.write(buffer, 0, size)
                    Desk.up += size
                }
            } catch (e: Exception) {
            }
            try {
                pipe.close()
            } catch (e: Exception) {
            }
        }
        val buffer = ByteArray(16384)
        try {
            while (true) {
                val size = pipe.read(buffer, 0, buffer.size)
                if (size < 0) break
                output.write(buffer, 0, size)
                output.flush()
                Desk.down += size
            }
        } catch (e: Exception) {
        }
        try {
            up.interrupt()
        } catch (e: Exception) {
        }
        try {
            pipe.close()
        } catch (e: Exception) {
        }
    }

    private fun readHead(input: InputStream): String? {
        val builder = StringBuilder()
        var last = 0
        while (builder.length < 16384) {
            val c = input.read()
            if (c < 0) return if (builder.isEmpty()) null else builder.toString()
            builder.append(c.toChar())
            if (last == '\r'.code && c == '\n'.code && builder.endsWith("\r\n\r\n")) return builder.toString()
            last = c
        }
        return null
    }

    private fun full(input: InputStream, buffer: ByteArray): Int {
        var done = 0
        while (done < buffer.size) {
            val size = input.read(buffer, done, buffer.size - done)
            if (size < 0) return -1
            done += size
        }
        return done
    }

    private fun skip(input: InputStream, count: Int) {
        full(input, ByteArray(count))
    }
}

object Connect {

    @Volatile private var proxy: LocalProxy? = null
    @Volatile private var location: FoxLocation? = null
    @Volatile private var running = false

    fun start() {
        if (running || Desk.connecting) return
        Desk.connecting = true
        Desk.fault = ""
        thread(isDaemon = true, name = "gozar-connect") {
            try {
                com.gozar.FoxRoute.apply(Store.bypassLan(), Store.bypassList())
                com.gozar.FoxRoute.applyChain(Store.chain())
                val pass = Desk.currentPass()
                if (Desk.servers.isEmpty()) Desk.servers = FoxApi.servers()
                val chosen = Desk.selected() ?: throw FoxError("No server available")
                if (Desk.limit > 0 && Desk.remaining <= 0) throw FoxError("Your 50 GB for this month is used up")
                location = chosen
                val port = freePort()
                val engine = LocalProxy(port) { host, target ->
                    if (com.gozar.FoxRoute.direct(host)) {
                        com.gozar.FoxSocketPipe.connect(host, target)
                            ?: com.gozar.FoxTunnel.open(chosen, host, target, pass)
                    } else {
                        com.gozar.FoxTunnel.open(chosen, host, target, pass)
                    }
                }
                if (!engine.start()) throw FoxError("The local proxy did not start")
                proxy = engine
                Desk.proxyPort = port
                Desk.down = 0
                Desk.up = 0
                Desk.exitIp = probe(chosen, pass)
                Desk.since = System.currentTimeMillis()
                running = true
                Desk.live = true
                Desk.connecting = false
                if (Store.fullTunnel()) {
                    val tun = TunEngine.start(chosen, pass, port)
                    Desk.tunActive = tun
                    if (!tun) Desk.fault = TunService.last()
                }
                if (Desk.tunActive) {
                    if (Desk.systemProxy) {
                        SystemProxy.disable()
                        Desk.systemProxy = false
                    }
                } else {
                    Desk.systemProxy = SystemProxy.enable(port)
                }
                Desk.startTicker()
            } catch (e: Exception) {
                stop()
                Desk.fault = e.message ?: "Could not connect"
                Desk.connecting = false
            }
        }
    }

    fun stop() {
        running = false
        TunEngine.stop()
        Desk.tunActive = false
        if (Desk.systemProxy || Desk.proxyPort > 0) SystemProxy.disable()
        Desk.systemProxy = false
        proxy?.stop()
        proxy = null
        location = null
        Desk.proxyPort = 0
        Desk.live = false
        Desk.connecting = false
        Desk.exitIp = ""
        Desk.down = 0
        Desk.up = 0
        Desk.downRate = 0.0
        Desk.upRate = 0.0
        Desk.since = 0L
    }

    private fun freePort(): Int {
        for (port in 10810..10830) {
            try {
                ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use { return port }
            } catch (e: Exception) {
            }
        }
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { return it.localPort }
    }

    private fun probe(chosen: FoxLocation, pass: String): String {
        return try {
            FoxTunnel.probe(chosen, pass)
        } catch (e: Exception) {
            ""
        }
    }
}
