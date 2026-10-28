package com.gozarwg

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

interface FoxPipe {
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun write(buffer: ByteArray, offset: Int, length: Int)
    fun close()
}

class FoxStack(
    private val incoming: InputStream,
    private val outgoing: OutputStream,
    private val local: Int,
    private val resolver: FoxResolver,
    private val dial: (String, Int) -> FoxPipe,
    private val meter: (Boolean, Long) -> Unit
) {

    companion object {
        const val MTU = 1400
        private const val MSS = MTU - 40
        private const val FIN = 0x01
        private const val SYN = 0x02
        private const val RST = 0x04
        private const val PSH = 0x08
        private const val ACK = 0x10
        private const val TCP = 6
        private const val UDP = 17
        private const val WINDOW = 64240
        private const val UPLOAD_CAP = 512 * 1024
        private const val FLIGHT_CAP = 256 * 1024
        private const val MASK = 0xFFFFFFFFL
    }

    private val flows = ConcurrentHashMap<Long, Flow>()
    private val names = ConcurrentHashMap<Int, String>()
    private val stamp = AtomicInteger(1)
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        thread(isDaemon = true, name = "fox-stack") { pump() }
        thread(isDaemon = true, name = "fox-reaper") {
            while (running) {
                sleep(15000)
                val limit = System.currentTimeMillis() - 180000
                for (entry in flows.entries.filter { it.value.idleAt < limit }) {
                    flows.remove(entry.key, entry.value)
                    entry.value.shutdown()
                }
            }
        }
    }

    fun stop() {
        running = false
        for (flow in flows.values) {
            try {
                flow.shutdown()
            } catch (_: Exception) {
            }
        }
        flows.clear()
    }

    fun active(): Int = flows.size

    fun nameOf(address: Int): String? = names[address]

    private fun pump() {
        val buffer = ByteArray(65535)
        while (running) {
            val size = try {
                incoming.read(buffer)
            } catch (e: Exception) {
                break
            }
            if (size < 0) break
            if (size < 20 || (buffer[0].toInt() and 0xF0) != 0x40) continue
            val header = (buffer[0].toInt() and 0x0F) * 4
            if (header < 20 || size < header + 8) continue
            val total = u16(buffer, 2)
            if (total < header + 8 || total > size) continue
            val source = i32(buffer, 12)
            if (source != local) continue
            val target = i32(buffer, 16)
            when (buffer[9].toInt() and 0xFF) {
                TCP -> segment(buffer, header, total, target)
                UDP -> datagram(buffer, header, total, target)
            }
        }
    }

    private fun segment(packet: ByteArray, header: Int, total: Int, target: Int) {
        val appPort = u16(packet, header)
        val remotePort = u16(packet, header + 2)
        val seq = u32(packet, header + 4)
        val ack = u32(packet, header + 8)
        val offset = ((packet[header + 12].toInt() and 0xF0) shr 4) * 4
        if (offset < 20) return
        val flags = packet[header + 13].toInt() and 0xFF
        val window = u16(packet, header + 14)
        val body = header + offset
        val length = total - body
        if (length < 0) return
        val key = key(appPort, target, remotePort)

        if (flags and RST != 0) {
            flows.remove(key)?.shutdown()
            return
        }

        var known = flows[key]
        if (known != null && flags and SYN != 0 && flags and ACK == 0) {
            known.shutdown()
            known = null
        }
        if (known == null) {
            if (flags and SYN == 0) {
                send(target, remotePort, appPort, 0L, (seq + length) and MASK, RST or ACK, 0, null, 0, 0)
                return
            }
            val created = Flow(key, appPort, target, remotePort, (seq + 1) and MASK)
            flows[key] = created
            send(target, remotePort, appPort, created.sent, created.expected, SYN or ACK, WINDOW, null, 0, 0)
            created.sent = (created.sent + 1) and MASK
            created.begin()
            return
        }

        known.idleAt = System.currentTimeMillis()
        known.confirm(ack, window)

        if (flags and FIN != 0) {
            known.expected = (seq + length + 1) and MASK
            send(target, remotePort, appPort, known.sent, known.expected, ACK, known.window(), null, 0, 0)
            known.appClosed()
            return
        }

        if (length > 0) {
            val fresh = seq == known.expected
            if (fresh) known.expected = (seq + length) and MASK
            if (fresh) known.push(packet, body, length)
            send(target, remotePort, appPort, known.sent, known.expected, ACK, known.window(), null, 0, 0)
        }
    }

    private fun datagram(packet: ByteArray, header: Int, total: Int, target: Int) {
        val appPort = u16(packet, header)
        val serverPort = u16(packet, header + 2)
        val length = u16(packet, header + 4)
        val start = header + 8
        if (length < 20 || start + length - 8 > total) return
        if (serverPort != 53) {
            unreachable(target, packet, total)
            return
        }
        val query = packet.copyOfRange(start, start + length - 8)
        thread(isDaemon = true, name = "fox-dns") {
            val answer = try {
                resolver.answer(query)
            } catch (e: Exception) {
                null
            } ?: return@thread
            replyUdp(target, appPort, serverPort, answer)
            learn(query, answer)
        }
    }

    private fun learn(query: ByteArray, answer: ByteArray) {
        val name = questionName(query) ?: return
        val address = firstAddress(answer)
        if (address != 0) names[address] = name
    }

    private fun questionName(query: ByteArray): String? {
        if (query.size < 13) return null
        val out = StringBuilder()
        var index = 12
        while (index < query.size) {
            val size = query[index].toInt() and 0xFF
            if (size == 0) break
            if (size > 63 || index + 1 + size > query.size) return null
            if (out.isNotEmpty()) out.append('.')
            out.append(String(query, index + 1, size, Charsets.UTF_8))
            index += size + 1
        }
        return if (out.isEmpty()) null else out.toString()
    }

    private fun firstAddress(answer: ByteArray): Int {
        if (answer.size < 12) return 0
        var questions = u16(answer, 4)
        var count = u16(answer, 6)
        var index = 12
        while (questions > 0) {
            index = skipName(answer, index)
            index += 4
            questions--
        }
        while (count > 0 && index + 12 <= answer.size) {
            index = skipName(answer, index)
            if (index + 10 > answer.size) return 0
            val type = u16(answer, index)
            val size = u16(answer, index + 8)
            index += 10
            if (index + size > answer.size) return 0
            if (type == 1 && size == 4) return i32(answer, index)
            index += size
            count--
        }
        return 0
    }

    private fun skipName(bytes: ByteArray, from: Int): Int {
        var index = from
        while (index < bytes.size) {
            val size = bytes[index].toInt() and 0xFF
            if (size == 0) return index + 1
            if (size and 0xC0 == 0xC0) return index + 2
            index += size + 1
        }
        return index
    }

    private fun replyUdp(destination: Int, destinationPort: Int, sourcePort: Int, payload: ByteArray) {
        val total = 28 + payload.size
        val packet = ByteArray(total)
        packet[0] = 0x45
        packet[8] = 64
        packet[9] = UDP.toByte()
        putI32(packet, 12, destination)
        putI32(packet, 16, local)
        putU16(packet, 2, total)
        putU16(packet, 4, stamp.incrementAndGet() and 0xFFFF)
        putU16(packet, 6, 0x4000)
        putU16(packet, 10, fold(sum(packet, 0, 20)))
        putU16(packet, 20, sourcePort)
        putU16(packet, 22, destinationPort)
        putU16(packet, 24, 8 + payload.size)
        System.arraycopy(payload, 0, packet, 28, payload.size)
        val check = fold(pseudo(destination, local, UDP, 8 + payload.size) + sum(packet, 20, 8 + payload.size))
        putU16(packet, 26, if (check == 0) 0xFFFF else check)
        emit(packet)
        meter(true, payload.size.toLong())
    }

    private fun unreachable(destination: Int, query: ByteArray, total: Int) {
        val quoted = minOf(total, 28)
        val packet = ByteArray(28 + quoted)
        packet[0] = 0x45
        packet[8] = 64
        packet[9] = 1
        putI32(packet, 12, destination)
        putI32(packet, 16, local)
        putU16(packet, 2, packet.size)
        putU16(packet, 4, stamp.incrementAndGet() and 0xFFFF)
        putU16(packet, 10, fold(sum(packet, 0, 20)))
        packet[20] = 3
        packet[21] = 3
        System.arraycopy(query, 0, packet, 28, quoted)
        putU16(packet, 22, fold(sum(packet, 20, 8 + quoted)))
        emit(packet)
    }

    private fun key(appPort: Int, remote: Int, remotePort: Int): Long =
        (appPort.toLong() shl 48) or (remotePort.toLong() shl 32) or (remote.toLong() and MASK)

    private fun send(
        destination: Int,
        destinationPort: Int,
        sourcePort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        window: Int,
        data: ByteArray?,
        offset: Int,
        length: Int
    ) {
        val tcpLength = 20 + length
        val total = 20 + tcpLength
        val packet = ByteArray(total)
        packet[0] = 0x45
        packet[8] = 64
        packet[9] = TCP.toByte()
        putI32(packet, 12, destination)
        putI32(packet, 16, local)
        putU16(packet, 2, total)
        putU16(packet, 4, stamp.incrementAndGet() and 0xFFFF)
        putU16(packet, 6, 0x4000)
        putU16(packet, 10, fold(sum(packet, 0, 20)))
        putU16(packet, 20, destinationPort)
        putU16(packet, 22, sourcePort)
        putU32(packet, 24, seq)
        putU32(packet, 28, ack)
        packet[32] = 0x50
        packet[33] = flags.toByte()
        putU16(packet, 34, window.coerceIn(0, 65535))
        if (length > 0 && data != null) System.arraycopy(data, offset, packet, 40, length)
        val check = fold(pseudo(destination, local, TCP, tcpLength) + sum(packet, 20, tcpLength))
        putU16(packet, 36, if (check == 0) 0xFFFF else check)
        emit(packet)
        if (length > 0) meter(false, length.toLong())
    }

    private fun emit(packet: ByteArray) {
        try {
            synchronized(outgoing) { outgoing.write(packet) }
        } catch (e: Exception) {
        }
    }

    private fun pseudo(source: Int, target: Int, protocol: Int, length: Int): Int =
        ((source ushr 16) and 0xFFFF) + (source and 0xFFFF) +
            ((target ushr 16) and 0xFFFF) + (target and 0xFFFF) + protocol + length

    private fun sum(bytes: ByteArray, offset: Int, length: Int): Int {
        var total = 0
        var index = offset
        val end = offset + length
        while (index + 1 < end) {
            total += ((bytes[index].toInt() and 0xFF) shl 8) or (bytes[index + 1].toInt() and 0xFF)
            index += 2
        }
        if (index < end) total += (bytes[index].toInt() and 0xFF) shl 8
        return total
    }

    private fun fold(value: Int): Int {
        var total = value
        while (total shr 16 != 0) total = (total and 0xFFFF) + (total shr 16)
        return total.inv() and 0xFFFF
    }

    private fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
        }
    }

    private fun u16(bytes: ByteArray, index: Int): Int =
        ((bytes[index].toInt() and 0xFF) shl 8) or (bytes[index + 1].toInt() and 0xFF)

    private fun u32(bytes: ByteArray, index: Int): Long =
        ((bytes[index].toLong() and 0xFF) shl 24) or ((bytes[index + 1].toLong() and 0xFF) shl 16) or
            ((bytes[index + 2].toLong() and 0xFF) shl 8) or (bytes[index + 3].toLong() and 0xFF)

    private fun i32(bytes: ByteArray, index: Int): Int =
        ((bytes[index].toInt() and 0xFF) shl 24) or ((bytes[index + 1].toInt() and 0xFF) shl 16) or
            ((bytes[index + 2].toInt() and 0xFF) shl 8) or (bytes[index + 3].toInt() and 0xFF)

    private fun putU16(bytes: ByteArray, index: Int, value: Int) {
        bytes[index] = ((value ushr 8) and 0xFF).toByte()
        bytes[index + 1] = (value and 0xFF).toByte()
    }

    private fun putU32(bytes: ByteArray, index: Int, value: Long) {
        bytes[index] = ((value ushr 24) and 0xFF).toByte()
        bytes[index + 1] = ((value ushr 16) and 0xFF).toByte()
        bytes[index + 2] = ((value ushr 8) and 0xFF).toByte()
        bytes[index + 3] = (value and 0xFF).toByte()
    }

    private fun putI32(bytes: ByteArray, index: Int, value: Int) {
        bytes[index] = ((value ushr 24) and 0xFF).toByte()
        bytes[index + 1] = ((value ushr 16) and 0xFF).toByte()
        bytes[index + 2] = ((value ushr 8) and 0xFF).toByte()
        bytes[index + 3] = (value and 0xFF).toByte()
    }

    private fun address(value: Int): String =
        "${(value ushr 24) and 0xFF}.${(value ushr 16) and 0xFF}.${(value ushr 8) and 0xFF}.${value and 0xFF}"

    private fun gap(newer: Long, older: Long): Long = (newer - older) and MASK

    private inner class Flow(
        private val key: Long,
        private val appPort: Int,
        private val remote: Int,
        private val remotePort: Int,
        @Volatile var expected: Long
    ) {
        @Volatile var sent: Long = System.nanoTime() and MASK
        @Volatile private var acknowledged: Long = sent
        @Volatile private var peer: Int = WINDOW
        @Volatile var idleAt: Long = System.currentTimeMillis()
        @Volatile private var closed = false
        @Volatile private var pipe: FoxPipe? = null
        private val queue = ArrayDeque<ByteArray>()
        private val lock = Object()
        private var queued = 0
        private var appClosed = false

        fun window(): Int = synchronized(lock) {
            if (queued > UPLOAD_CAP) 0 else WINDOW
        }

        fun push(packet: ByteArray, offset: Int, length: Int) {
            synchronized(lock) {
                if (closed || queued > UPLOAD_CAP) return
                queue.addLast(packet.copyOfRange(offset, offset + length))
                queued += length
                lock.notifyAll()
            }
        }

        fun confirm(ack: Long, window: Int) {
            synchronized(lock) {
                if (gap(ack, acknowledged) < 0x80000000L) acknowledged = ack
                peer = window
                lock.notifyAll()
            }
        }

        fun appClosed() {
            val done = synchronized(lock) {
                appClosed = true
                lock.notifyAll()
                queue.isEmpty() && (closed || pipe == null)
            }
            if (done) flowDone()
        }

        fun begin() {
            thread(isDaemon = true, name = "fox-flow") {
                val host = names[remote] ?: address(remote)
                val link = try {
                    dial(host, remotePort)
                } catch (e: Exception) {
                    send(remote, remotePort, appPort, sent, expected, RST or ACK, 0, null, 0, 0)
                    flows.remove(key, this)
                    return@thread
                }
                pipe = link
                thread(isDaemon = true, name = "fox-flow-up") { drain(link) }
                receive(link)
            }
        }

        private fun drain(link: FoxPipe) {
            while (true) {
                val chunk = synchronized(lock) {
                    while (queue.isEmpty() && !closed && !appClosed) lock.wait(180000)
                    when {
                        queue.isNotEmpty() -> queue.removeFirst().also {
                            queued -= it.size
                            lock.notifyAll()
                        }
                        else -> null
                    }
                } ?: break
                var offset = 0
                while (offset < chunk.size) {
                    val size = minOf(MSS, chunk.size - offset)
                    try {
                        link.write(chunk, offset, size)
                    } catch (e: Exception) {
                        terminate()
                        return
                    }
                    offset += size
                }
            }
            if (appClosed) flowDone()
        }

        private fun receive(link: FoxPipe) {
            val buffer = ByteArray(32768)
            while (true) {
                val size = try {
                    link.read(buffer, 0, buffer.size)
                } catch (e: Exception) {
                    -1
                }
                if (size < 0) break
                if (size == 0) continue
                var offset = 0
                while (offset < size) {
                    val piece = minOf(MSS, size - offset)
                    if (!await(piece.toLong())) {
                        terminate()
                        return
                    }
                    send(remote, remotePort, appPort, sent, expected, ACK or PSH, window(), buffer, offset, piece)
                    synchronized(lock) { sent = (sent + piece) and MASK }
                    offset += piece
                }
            }
            if (synchronized(lock) { closed }) return
            send(remote, remotePort, appPort, sent, expected, FIN or ACK, window(), null, 0, 0)
            synchronized(lock) { sent = (sent + 1) and MASK }
            if (synchronized(lock) { appClosed }) flowDone() else synchronized(lock) { closed = true }
        }

        private fun await(extra: Long): Boolean {
            val deadline = System.currentTimeMillis() + 25000
            synchronized(lock) {
                while (true) {
                    val flight = gap(sent + extra, acknowledged)
                    val allowed = minOf(peer.toLong(), FLIGHT_CAP.toLong())
                    if (flight < allowed) return true
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0 || closed) return false
                    lock.wait(left)
                }
            }
        }

        private fun flowDone() {
            synchronized(lock) { closed = true; lock.notifyAll() }
            try {
                pipe?.close()
            } catch (_: Exception) {
            }
            flows.remove(key, this)
        }

        fun shutdown() {
            synchronized(lock) { closed = true; lock.notifyAll() }
            try {
                pipe?.close()
            } catch (_: Exception) {
            }
            flows.remove(key, this)
        }

        private fun terminate() {
            send(remote, remotePort, appPort, sent, expected, RST or ACK, 0, null, 0, 0)
            shutdown()
        }

        private fun address(value: Int): String =
            "${(value ushr 24) and 0xFF}.${(value ushr 16) and 0xFF}.${(value ushr 8) and 0xFF}.${value and 0xFF}"
    }
}

class FoxResolver(private val proxyPort: Int) {

    private class Entry(val body: ByteArray, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val lock = Object()
    private var failures = 0

    fun answer(query: ByteArray): ByteArray? {
        if (query.size < 13) return null
        val name = question(query) ?: return null
        val type = questionType(query)
        val key = name + "|" + type
        val hit = cache[key]
        if (hit != null && System.currentTimeMillis() - hit.at < 120000L) return retag(query, hit.body)
        if (type == 28 || type == 65) return empty(query)
        val body = exchange(query) ?: return null
        cache[key] = Entry(body, System.currentTimeMillis())
        return retag(query, body)
    }

    private fun retag(query: ByteArray, body: ByteArray): ByteArray {
        if (body.size < 12 || query.size < 2) return body
        val copy = body.copyOf()
        copy[0] = query[0]
        copy[1] = query[1]
        return copy
    }

    private fun empty(query: ByteArray): ByteArray {
        val copy = query.copyOf()
        copy[2] = ((copy[2].toInt() or 0x80) and 0xFF).toByte()
        copy[3] = ((copy[3].toInt() and 0xF0) or 0x80).toByte()
        putShort(copy, 6, 0)
        putShort(copy, 8, 0)
        putShort(copy, 10, 0)
        return copy
    }

    private fun exchange(query: ByteArray): ByteArray? {
        synchronized(lock) {
            if (failures > 40) return null
            val body = try {
                over("https://dns.google/dns-query", query)
            } catch (e: Exception) {
                null
            } ?: try {
                over("https://mozilla.cloudflare-dns.com/dns-query", query)
            } catch (e: Exception) {
                null
            }
            if (body == null) {
                failures++
                return null
            }
            failures = 0
            return body
        }
    }

    private fun over(url: String, query: ByteArray): ByteArray? {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort))
        val connection = URL(url).openConnection(proxy) as HttpsURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("content-type", "application/dns-message")
        connection.setRequestProperty("accept", "application/dns-message")
        connection.outputStream.use { it.write(query) }
        if (connection.responseCode != 200) return null
        val body = connection.inputStream.use { it.readBytes() }
        return if (body.size > 12) body else null
    }

    private fun question(query: ByteArray): String? {
        val out = StringBuilder()
        var index = 12
        while (index < query.size) {
            val size = query[index].toInt() and 0xFF
            if (size == 0) break
            if (size > 63 || index + 1 + size > query.size) return null
            if (out.isNotEmpty()) out.append('.')
            out.append(String(query, index + 1, size, Charsets.UTF_8).lowercase())
            index += size + 1
        }
        return if (out.isEmpty()) null else out.toString()
    }

    private fun questionType(query: ByteArray): Int {
        var index = 12
        while (index < query.size) {
            val size = query[index].toInt() and 0xFF
            if (size == 0) {
                index += 1
                break
            }
            index += size + 1
        }
        if (index + 2 > query.size) return 1
        return ((query[index].toInt() and 0xFF) shl 8) or (query[index + 1].toInt() and 0xFF)
    }

    private fun putShort(bytes: ByteArray, index: Int, value: Int) {
        if (index + 1 >= bytes.size) return
        bytes[index] = ((value ushr 8) and 0xFF).toByte()
        bytes[index + 1] = (value and 0xFF).toByte()
    }
}
