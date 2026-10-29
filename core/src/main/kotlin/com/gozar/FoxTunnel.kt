package com.gozar

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class FoxDuplex(
    private val socket: SSLSocket,
    private val input: BufferedInputStream,
    private val output: BufferedOutputStream,
    private val h2: Boolean,
    private val streamId: Int
) : FoxPipe {

    private val lock = Object()
    private var sendWindow = 65535L
    private var connectionWindow = 65535L

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!h2) return input.read(buffer, offset, length)
        while (true) {
            val header = ByteArray(9)
            if (!fill(header)) return -1
            val size = ((header[0].toInt() and 0xFF) shl 16) or ((header[1].toInt() and 0xFF) shl 8) or
                (header[2].toInt() and 0xFF)
            val type = header[3].toInt() and 0xFF
            val flags = header[4].toInt() and 0xFF
            val id = ((header[5].toInt() and 0x7F) shl 24) or ((header[6].toInt() and 0xFF) shl 16) or
                ((header[7].toInt() and 0xFF) shl 8) or (header[8].toInt() and 0xFF)
            val payload = ByteArray(size)
            if (size > 0 && !fill(payload)) return -1
            when (type) {
                0x00 -> if (id == streamId) {
                    if (size > 0) {
                        grow(size)
                        val take = minOf(size, length)
                        System.arraycopy(payload, 0, buffer, offset, take)
                        if (take < size) {
                            return take
                        }
                        return take
                    }
                    if (flags and 0x01 != 0) return -1
                }
                0x01 -> if (id == streamId && flags and 0x01 != 0) return -1
                0x03, 0x07 -> return -1
                0x04 -> if (flags and 0x01 == 0) send(frame(0x04, 0x01, 0, ByteArray(0)))
                0x06 -> if (flags and 0x01 == 0) send(frame(0x06, 0x01, 0, payload))
                0x08 -> {
                    val grow = if (payload.size >= 4) {
                        ((payload[0].toInt() and 0x7F) shl 24) or ((payload[1].toInt() and 0xFF) shl 16) or
                            ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
                    } else 0
                    synchronized(lock) {
                        if (id == 0) connectionWindow += grow else sendWindow += grow
                        lock.notifyAll()
                    }
                }
            }
        }
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (!h2) {
            output.write(buffer, offset, length)
            output.flush()
            return
        }
        var sent = 0
        while (sent < length) {
            val size = minOf(length - sent, 16384)
            waitWindow(size)
            send(frame(0x00, 0, streamId, buffer.copyOfRange(offset + sent, offset + sent + size)))
            synchronized(lock) {
                sendWindow -= size
                connectionWindow -= size
            }
            sent += size
        }
    }

    private fun waitWindow(size: Int) {
        val deadline = System.currentTimeMillis() + 25000
        synchronized(lock) {
            while ((sendWindow < size || connectionWindow < size)) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) throw java.io.IOException("upload stalled")
                lock.wait(left)
            }
        }
    }

    private fun grow(size: Int) {
        val update = byteArrayOf(
            ((size ushr 24) and 0x7F).toByte(),
            ((size ushr 16) and 0xFF).toByte(),
            ((size ushr 8) and 0xFF).toByte(),
            (size and 0xFF).toByte()
        )
        send(frame(0x08, 0, 0, update))
        send(frame(0x08, 0, streamId, update))
    }

    override fun close() {
        if (h2) {
            send(frame(0x03, 0, streamId, byteArrayOf(0, 0, 0, 0x08)))
            send(frame(0x07, 0, 0, ByteArray(8)))
        }
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    private fun send(bytes: ByteArray) {
        synchronized(lock) {
            try {
                output.write(bytes)
                output.flush()
            } catch (_: Exception) {
            }
        }
    }

    private fun fill(buffer: ByteArray): Boolean {
        var done = 0
        while (done < buffer.size) {
            val n = try {
                input.read(buffer, done, buffer.size - done)
            } catch (e: Exception) {
                return false
            }
            if (n < 0) return false
            done += n
        }
        return true
    }

    private fun frame(type: Int, flags: Int, id: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(9 + payload.size)
        out[0] = ((payload.size ushr 16) and 0xFF).toByte()
        out[1] = ((payload.size ushr 8) and 0xFF).toByte()
        out[2] = (payload.size and 0xFF).toByte()
        out[3] = type.toByte()
        out[4] = flags.toByte()
        out[5] = ((id ushr 24) and 0x7F).toByte()
        out[6] = ((id ushr 16) and 0xFF).toByte()
        out[7] = ((id ushr 8) and 0xFF).toByte()
        out[8] = (id and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 9, payload.size)
        return out
    }
}

object FoxTunnel {

    private const val AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:149.0) Gecko/20100101 Firefox/149.0"

    @Volatile private var h2DownUntil = 0L

    fun open(location: FoxLocation, host: String, port: Int, pass: String, timeout: Int = 15000): FoxDuplex {
        var firstError: Exception? = null
        val overH2 = if (System.currentTimeMillis() < h2DownUntil) null else try {
            openH2(location, host, port, pass, timeout)
        } catch (e: Exception) {
            FoxLog.add("tunnel", "h2 failed: " + (e.message ?: e.javaClass.simpleName))
            h2DownUntil = System.currentTimeMillis() + 600000
            firstError = e
            null
        }
        if (overH2 != null) return overH2
        return try {
            openH1(location, host, port, pass, timeout)
        } catch (e: Exception) {
            FoxLog.add("tunnel", "h1 failed: " + (e.message ?: e.javaClass.simpleName))
            throw firstError ?: e
        }
    }

    private fun authority(host: String, port: Int) = "$host:$port"

    private fun openH1(location: FoxLocation, host: String, port: Int, pass: String, timeout: Int): FoxDuplex {
        val socket = connect(location, arrayOf("http/1.1"), timeout)
        val target = authority(host, port)
        val output = BufferedOutputStream(socket.outputStream)
        val request = "CONNECT $target HTTP/1.1\r\nHost: $target\r\nProxy-Authorization: Bearer $pass\r\n" +
            "User-Agent: $AGENT\r\n\r\n"
        output.write(request.toByteArray(Charsets.UTF_8))
        output.flush()
        FoxLog.add("tunnel", "h1 CONNECT $target ${location.host}:${location.port}")
        val input = BufferedInputStream(socket.inputStream)
        val (status, headers) = response(input)
        FoxLog.add("tunnel", "h1 → $status " + headers.entries.joinToString(" ") { it.key + "=" + it.value.take(40) })
        if (status == 200) {
            socket.soTimeout = 0
            return FoxDuplex(socket, input, output, false, 1)
        }
        socket.close()
        throw java.io.IOException(reason(status, headers))
    }

    private fun openH2(location: FoxLocation, host: String, port: Int, pass: String, timeout: Int): FoxDuplex {
        val socket = connect(location, arrayOf("h2"), timeout)
        val output = BufferedOutputStream(socket.outputStream)
        val target = authority(host, port)
        output.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.write(frame(0x04, 0, 0, settings()))
        val block = ByteArrayOutputStream()
        block.write(pair(":method", "CONNECT"))
        block.write(pair(":authority", target))
        block.write(pair("proxy-authorization", "Bearer $pass"))
        block.write(pair("user-agent", AGENT))
        output.write(frame(0x01, 0x04, 1, block.toByteArray()))
        output.flush()
        FoxLog.add("tunnel", "h2 CONNECT $target ${location.host}:${location.port}")

        val input = BufferedInputStream(socket.inputStream)
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            val header = ByteArray(9)
            if (!readFully(input, header)) throw java.io.IOException("the proxy closed the connection")
            val size = ((header[0].toInt() and 0xFF) shl 16) or ((header[1].toInt() and 0xFF) shl 8) or
                (header[2].toInt() and 0xFF)
            val type = header[3].toInt() and 0xFF
            val flags = header[4].toInt() and 0xFF
            val id = ((header[5].toInt() and 0x7F) shl 24) or ((header[6].toInt() and 0xFF) shl 16) or
                ((header[7].toInt() and 0xFF) shl 8) or (header[8].toInt() and 0xFF)
            val payload = ByteArray(size)
            if (size > 0 && !readFully(input, payload)) throw java.io.IOException("the proxy closed the connection")
            when (type) {
                0x04 -> if (flags and 0x01 == 0) {
                    output.write(frame(0x04, 0x01, 0, ByteArray(0)))
                    output.flush()
                }
                0x06 -> if (flags and 0x01 == 0) {
                    output.write(frame(0x06, 0x01, 0, payload))
                    output.flush()
                }
                0x01 -> {
                    if (id != 1) continue
                    val status = status(payload)
                    FoxLog.add("tunnel", "h2 → status=$status")
                    if (status == 200) {
                        socket.soTimeout = 0
                        return FoxDuplex(socket, input, output, true, 1)
                    }
                    socket.close()
                    throw java.io.IOException(reason(status, mapOf()))
                }
                0x03 -> throw java.io.IOException("the proxy reset the tunnel (${code(payload)})")
                0x07 -> throw java.io.IOException("the proxy closed the connection (${code(payload)})")
            }
        }
        socket.close()
        throw java.io.IOException("the proxy did not answer in time")
    }

    fun probe(location: FoxLocation, pass: String): String {
        val tunnel = open(location, "ifconfig.me", 80, pass)
        try {
            val request = "GET /ip HTTP/1.1\r\nHost: ifconfig.me\r\nUser-Agent: curl/8\r\nAccept: */*\r\nConnection: close\r\n\r\n"
            tunnel.write(request.toByteArray(Charsets.US_ASCII), 0, request.length)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(2048)
            val deadline = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < deadline) {
                val n = try {
                    tunnel.read(buffer, 0, buffer.size)
                } catch (e: Exception) {
                    break
                }
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > 4096) break
            }
            val text = out.toString("UTF-8")
            val body = text.substringAfter("\r\n\r\n", "").trim()
            FoxLog.add("tunnel", "probe read " + out.size() + "b: " + body.take(80))
            return body.lines().firstOrNull { it.isNotBlank() }?.trim()?.take(64) ?: ""
        } finally {
            tunnel.close()
        }
    }

    private fun connect(location: FoxLocation, protocols: Array<String>, timeout: Int): SSLSocket {
        val raw = chain(location.host, location.port, timeout)
        raw.soTimeout = timeout
        val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(raw, location.host, location.port, true) as SSLSocket
        tls.sslParameters = tls.sslParameters.apply {
            applicationProtocols = protocols
            serverNames = listOf(javax.net.ssl.SNIHostName(location.host))
        }
        tls.startHandshake()
        FoxLog.add("tunnel", "tls alpn=" + (tls.applicationProtocol ?: "none") + " want=" + protocols.joinToString(","))
        return tls
    }

    private fun chain(host: String, port: Int, timeout: Int): Socket {
        val hops = FoxUpstream.hops
        val raw = Socket()
        val first = if (hops.isEmpty()) host to port else hops.first().host to hops.first().port
        raw.connect(InetSocketAddress(first.first, first.second), timeout)
        raw.soTimeout = timeout
        if (hops.isEmpty()) return raw
        try {
            for (index in 1 until hops.size) {
                val hop = hops[index]
                hopTo(raw, hops[index - 1].type, hop.host, hop.port)
                FoxLog.add("chain", "hop " + hop.label)
            }
            hopTo(raw, hops.last().type, host, port)
            FoxLog.add("chain", "exit " + host + ":" + port + " through " + hops.size + " hop(s)")
            return raw
        } catch (e: Exception) {
            FoxLog.add("chain", "failed: " + (e.message ?: e.javaClass.simpleName))
            try {
                raw.close()
            } catch (ignored: Exception) {
            }
            throw if (e is FoxError) e else FoxError("The proxy chain did not answer: " + (e.message ?: "no reply"))
        }
    }

    private fun hopTo(socket: Socket, type: String, host: String, port: Int) {
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        if (type == "http") {
            val target = "$host:$port"
            val request = "CONNECT $target HTTP/1.1\r\nHost: $target\r\nProxy-Connection: keep-alive\r\n\r\n"
            output.write(request.toByteArray(Charsets.US_ASCII))
            output.flush()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val c = input.read()
                if (c < 0) throw FoxError("The proxy closed the connection")
                head.append(c.toChar())
                if (head.length > 8192) throw FoxError("The proxy sent a very long reply")
            }
            val status = head.lineSequence().firstOrNull()
                ?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: 0
            if (status != 200) throw FoxError("The proxy refused the chain ($status)")
            return
        }
        output.write(byteArrayOf(0x05, 0x01, 0x00))
        output.flush()
        val reply = ByteArray(2)
        if (!fill(input, reply)) throw FoxError("The proxy did not answer")
        if (reply[0].toInt() != 0x05 || reply[1].toInt() != 0x00) throw FoxError("The proxy wants authentication")
        val name = host.toByteArray(Charsets.UTF_8)
        val request = ByteArrayOutputStream()
        request.write(byteArrayOf(0x05, 0x01, 0x00))
        if (name.size <= 255 && !isAddress(host)) {
            request.write(byteArrayOf(0x03, name.size.toByte()))
            request.write(name)
        } else {
            val address = InetAddress.getByName(host).address
            request.write(if (address.size == 16) 0x04 else 0x01)
            request.write(address)
        }
        request.write(byteArrayOf(((port ushr 8) and 0xFF).toByte(), (port and 0xFF).toByte()))
        output.write(request.toByteArray())
        output.flush()
        val answer = ByteArray(4)
        if (!fill(input, answer)) throw FoxError("The proxy did not answer")
        if (answer[1].toInt() != 0x00) throw FoxError("The proxy refused the chain (" + answer[1] + ")")
        val skip = when (answer[3].toInt()) {
            0x01 -> 4
            0x04 -> 16
            0x03 -> {
                val size = input.read()
                if (size < 0) throw FoxError("The proxy did not answer")
                size
            }
            else -> throw FoxError("The proxy sent an unknown reply")
        }
        val tail = ByteArray(skip + 2)
        if (!fill(input, tail)) throw FoxError("The proxy did not answer")
    }

    private fun isAddress(host: String): Boolean =
        host.isNotEmpty() && host.all { it.isDigit() || it == '.' } || host.contains(':')

    private fun fill(input: BufferedInputStream, buffer: ByteArray): Boolean {
        var done = 0
        while (done < buffer.size) {
            val size = input.read(buffer, done, buffer.size - done)
            if (size < 0) return false
            done += size
        }
        return true
    }

    private fun settings(): ByteArray {
        fun entry(id: Int, value: Int): ByteArray = byteArrayOf(
            ((id ushr 8) and 0xFF).toByte(), (id and 0xFF).toByte(),
            ((value ushr 24) and 0xFF).toByte(), ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(), (value and 0xFF).toByte()
        )
        val out = ByteArrayOutputStream()
        listOf(entry(1, 4096), entry(2, 0), entry(4, 65535), entry(5, 16384), entry(8, 0), entry(3, 100), entry(6, 65536))
            .forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun pair(name: String, value: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x00)
        literal(out, name)
        literal(out, value)
        return out.toByteArray()
    }

    private fun literal(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.write(bytes.size and 0x7F)
        out.write(bytes)
    }

    private fun frame(type: Int, flags: Int, id: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(9 + payload.size)
        out[0] = ((payload.size ushr 16) and 0xFF).toByte()
        out[1] = ((payload.size ushr 8) and 0xFF).toByte()
        out[2] = (payload.size and 0xFF).toByte()
        out[3] = type.toByte()
        out[4] = flags.toByte()
        out[5] = ((id ushr 24) and 0x7F).toByte()
        out[6] = ((id ushr 16) and 0xFF).toByte()
        out[7] = ((id ushr 8) and 0xFF).toByte()
        out[8] = (id and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 9, payload.size)
        return out
    }

    private fun code(payload: ByteArray): Int {
        if (payload.size < 4) return 0
        return ((payload[0].toInt() and 0xFF) shl 24) or ((payload[1].toInt() and 0xFF) shl 16) or
            ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
    }

    private fun status(block: ByteArray): Int? {
        if (block.isEmpty()) return null
        val first = block[0].toInt() and 0xFF
        if (first and 0x80 != 0) {
            return when (first and 0x7F) {
                8 -> 200
                9 -> 204
                10 -> 206
                11 -> 304
                12 -> 400
                13 -> 404
                14 -> 500
                else -> null
            }
        }
        if (first and 0xF0 == 0x00) {
            var index = 1
            val length = block[index].toInt() and 0xFF
            index++
            if (block.size < index + length) return null
            return String(block, index, length, Charsets.US_ASCII).toIntOrNull()
        }
        return null
    }

    private fun readFully(input: BufferedInputStream, buffer: ByteArray): Boolean {
        var done = 0
        while (done < buffer.size) {
            val n = input.read(buffer, done, buffer.size - done)
            if (n < 0) return false
            done += n
        }
        return true
    }

    private fun response(input: BufferedInputStream): Pair<Int, Map<String, String>> {
        val head = StringBuilder()
        var last = 0
        while (head.length < 8192) {
            val c = input.read()
            if (c < 0) break
            head.append(c.toChar())
            if (last == 13 && c == 10 && head.endsWith("\r\n\r\n")) break
            last = c
        }
        val lines = head.toString().split("\r\n").filter { it.isNotEmpty() }
        val status = lines.firstOrNull()?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: 0
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        return status to headers
    }

    private fun reason(status: Int?, headers: Map<String, String>): String {
        val failure = headers["x-fastly-proxy-auth-failed"]
        return when {
            status == null -> "the proxy did not answer with a status"
            status == 407 || status == 401 -> "the proxy pass was refused"
            status == 403 -> "this account has no Firefox VPN access"
            status == 429 -> "the monthly allowance is used up"
            status == 451 -> "Mozilla does not serve this region"
            failure != null -> "the pass was rejected ($failure)"
            status == 400 -> "the pass was rejected (400)"
            status == 0 -> "the proxy closed the connection"
            else -> "the proxy answered $status"
        }
    }
}
