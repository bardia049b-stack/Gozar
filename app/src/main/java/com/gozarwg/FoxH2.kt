package com.gozarwg

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

class FoxDuplex(
    private val socket: SSLSocket,
    private val streamId: Int
) {

    private val input = BufferedInputStream(socket.inputStream)
    private val output = BufferedOutputStream(socket.outputStream)
    private val lock = Object()
    private val chunks = ArrayDeque<ByteArray>()
    private var position = 0
    private var finished = false
    private var broken: String? = null
    private var sendWindow = 65535L
    private var writeLock = Object()
    @Volatile var closed = false
        private set

    init {
        thread(isDaemon = true, name = "fox-h2-$streamId") { pump() }
    }

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        synchronized(lock) {
            while (chunks.isEmpty() && !finished && broken == null && !closed) {
                lock.wait(30000)
                if (chunks.isEmpty() && !finished && broken == null) {
                    if (socket.isClosed) break
                }
            }
            val head = chunks.firstOrNull()
            if (head == null) {
                broken?.let { throw java.io.IOException(it) }
                return -1
            }
            val take = minOf(length, head.size - position)
            System.arraycopy(head, position, buffer, offset, take)
            position += take
            if (position >= head.size) {
                chunks.removeFirst()
                position = 0
            }
            return take
        }
    }

    fun write(buffer: ByteArray, offset: Int, length: Int) {
        var sent = 0
        while (sent < length) {
            val size = minOf(length - sent, 16384)
            awaitWindow(size)
            val frame = frame(0x00, 0, streamId, buffer.copyOfRange(offset + sent, offset + sent + size))
            synchronized(writeLock) {
                output.write(frame)
                output.flush()
            }
            sendWindow -= size
            sent += size
        }
    }

    private fun awaitWindow(size: Int) {
        val deadline = System.currentTimeMillis() + 30000
        synchronized(writeLock) {
            while (sendWindow < size && !closed) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) throw java.io.IOException("upload stalled")
                writeLock.wait(left)
            }
        }
    }

    private fun pump() {
        try {
            while (!closed) {
                val header = ByteArray(9)
                if (!readFully(header)) break
                val length = ((header[0].toInt() and 0xFF) shl 16) or
                    ((header[1].toInt() and 0xFF) shl 8) or
                    (header[2].toInt() and 0xFF)
                val type = header[3].toInt() and 0xFF
                val flags = header[4].toInt() and 0xFF
                val id = ((header[5].toInt() and 0x7F) shl 24) or
                    ((header[6].toInt() and 0xFF) shl 16) or
                    ((header[7].toInt() and 0xFF) shl 8) or
                    (header[8].toInt() and 0xFF)
                val payload = ByteArray(length)
                if (length > 0 && !readFully(payload)) break
                when (type) {
                    0x00 -> {
                        if (id == streamId && length > 0) {
                            synchronized(lock) {
                                chunks.addLast(payload)
                                lock.notifyAll()
                            }
                            grow(length)
                        }
                        if (flags and 0x01 != 0) {
                            finish()
                            return
                        }
                    }
                    0x01 -> if (flags and 0x01 != 0) {
                        finish()
                        return
                    }
                    0x03 -> {
                        finish()
                        return
                    }
                    0x04 -> if (flags and 0x01 == 0) sendFrame(frame(0x04, 0x01, 0, ByteArray(0)))
                    0x06 -> if (flags and 0x01 == 0) sendFrame(frame(0x06, 0x01, 0, payload))
                    0x07 -> {
                        finish()
                        return
                    }
                    0x08 -> {
                        val grow = if (payload.size >= 4) {
                            ((payload[0].toInt() and 0x7F) shl 24) or ((payload[1].toInt() and 0xFF) shl 16) or
                                ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
                        } else 0
                        synchronized(writeLock) {
                            sendWindow += grow
                            writeLock.notifyAll()
                        }
                    }
                }
            }
            finish()
        } catch (e: Exception) {
            synchronized(lock) {
                if (!finished) broken = e.message ?: "tunnel closed"
                finished = true
                lock.notifyAll()
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
        sendFrame(frame(0x08, 0, 0, update))
        sendFrame(frame(0x08, 0, streamId, update))
    }

    private fun finish() {
        synchronized(lock) {
            finished = true
            lock.notifyAll()
        }
    }

    private fun sendFrame(bytes: ByteArray) {
        try {
            synchronized(writeLock) {
                output.write(bytes)
                output.flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun readFully(buffer: ByteArray): Boolean {
        var done = 0
        while (done < buffer.size) {
            val n = input.read(buffer, done, buffer.size - done)
            if (n < 0) return false
            done += n
        }
        return true
    }

    fun close() {
        if (closed) return
        closed = true
        sendFrame(frame(0x03, 0, streamId, byteArrayOf(0, 0, 0, 0x08)))
        sendFrame(frame(0x07, 0, 0, byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)))
        try {
            socket.close()
        } catch (_: Exception) {
        }
        finish()
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

object FoxH2 {

    private const val PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"

    fun open(location: FoxLocation, host: String, port: Int, pass: String, timeout: Int = 15000): FoxDuplex {
        val authority = if (port == 443) host else "$host:$port"
        val raw = Socket()
        raw.connect(InetSocketAddress(location.host, location.port), timeout)
        raw.soTimeout = timeout
        val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, location.host, location.port, true) as SSLSocket
        tls.sslParameters = tls.sslParameters.apply {
            applicationProtocols = arrayOf("h2")
            serverNames = listOf(javax.net.ssl.SNIHostName(location.host))
        }
        tls.startHandshake()
        if (tls.applicationProtocol != "h2") {
            tls.close()
            throw java.io.IOException("proxy refused HTTP/2")
        }
        tls.soTimeout = 0
        val out = BufferedOutputStream(tls.outputStream)
        out.write(PREFACE.toByteArray(Charsets.US_ASCII))
        out.write(simpleFrame(0x04, 0, 0, ByteArray(0)))
        out.write(simpleFrame(0x08, 0, 0, byteArrayOf(0x7F, 0xFF.toByte(), 0x00, 0x00)))
        out.write(simpleFrame(0x01, 0x04, 1, headers(authority, pass)))
        out.flush()

        val input = BufferedInputStream(tls.inputStream)
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            val header = ByteArray(9)
            if (!fill(input, header)) throw java.io.IOException("proxy closed the tunnel")
            val length = ((header[0].toInt() and 0xFF) shl 16) or
                ((header[1].toInt() and 0xFF) shl 8) or (header[2].toInt() and 0xFF)
            val type = header[3].toInt() and 0xFF
            val flags = header[4].toInt() and 0xFF
            val id = ((header[5].toInt() and 0x7F) shl 24) or
                ((header[6].toInt() and 0xFF) shl 16) or
                ((header[7].toInt() and 0xFF) shl 8) or (header[8].toInt() and 0xFF)
            val payload = ByteArray(length)
            if (length > 0 && !fill(input, payload)) throw java.io.IOException("proxy closed the tunnel")
            when (type) {
                0x04 -> if (flags and 0x01 == 0) {
                    out.write(simpleFrame(0x04, 0x01, 0, ByteArray(0)))
                    out.flush()
                }
                0x06 -> if (flags and 0x01 == 0) {
                    out.write(simpleFrame(0x06, 0x01, 0, payload))
                    out.flush()
                }
                0x01 -> {
                    if (id != 1) continue
                    val status = status(payload)
                    if (status == 200) return FoxDuplex(tls, 1)
                    tls.close()
                    throw java.io.IOException(refusal(status))
                }
                0x03, 0x07 -> {
                    tls.close()
                    throw java.io.IOException("the proxy refused the tunnel")
                }
            }
        }
        tls.close()
        throw java.io.IOException("the proxy did not answer in time")
    }

    fun probe(location: FoxLocation, access: String): String {
        val tunnel = open(location, "ifconfig.me", 80, access)
        try {
            val request = "GET /ip HTTP/1.1\r\nHost: ifconfig.me\r\nUser-Agent: curl/8\r\nConnection: close\r\n\r\n"
            tunnel.write(request.toByteArray(Charsets.US_ASCII), 0, request.length)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            val deadline = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < deadline) {
                val n = tunnel.read(buffer, 0, buffer.size)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > 8192) break
            }
            val text = out.toString("UTF-8")
            val body = text.substringAfter("\r\n\r\n", "")
            return body.trim().substringAfter("[").substringBefore("]").ifEmpty { body.trim() }
        } finally {
            tunnel.close()
        }
    }

    private fun refusal(status: Int?): String = when (status) {
        401, 407 -> "the proxy pass was refused"
        403 -> "this account has no Firefox VPN access"
        429 -> "the monthly allowance is used up"
        451 -> "Mozilla does not serve this region"
        null -> "the proxy refused the tunnel"
        else -> "the proxy answered $status"
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
            val name = first and 0x0F
            if (name == 8 && block.size > 1) {
                var index = 1
                val length = block[index].toInt() and 0xFF
                if (length and 0x80 != 0) return null
                index++
                if (block.size < index + length) return null
                return String(block, index, length, Charsets.US_ASCII).toIntOrNull()
            }
        }
        return null
    }

    private fun headers(authority: String, pass: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x02)
        literal(out, "CONNECT")
        out.write(0x01)
        literal(out, authority)
        out.write(0x00)
        literal(out, "proxy-authorization")
        literal(out, "Bearer $pass")
        return out.toByteArray()
    }

    private fun literal(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.write(bytes.size and 0x7F)
        out.write(bytes)
    }

    private fun simpleFrame(type: Int, flags: Int, id: Int, payload: ByteArray): ByteArray {
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

    private fun fill(input: BufferedInputStream, buffer: ByteArray): Boolean {
        var done = 0
        while (done < buffer.size) {
            val n = input.read(buffer, done, buffer.size - done)
            if (n < 0) return false
            done += n
        }
        return true
    }
}
