package com.gozar

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxStackTest {
    @Test
    fun ipv4TcpPacketsFlowThroughFakeTunnelWithoutAnOsDevice() {
        MemoryTunnel().use { tunnel ->
            val initial = 1000L
            tunnel.input.offer(tcp(initial, 0, 0x02, byteArrayOf()))
            val synAck = tunnel.output.next()
            assertEquals(0x12, synAck[33].toInt() and 0xff)
            assertEquals(initial + 1, u32(synAck, 28))
            val peerSeq = u32(synAck, 24) + 1
            tunnel.input.offer(tcp(initial + 1, peerSeq, 0x10, byteArrayOf()))
            val body = "packet-level-loopback".toByteArray(Charsets.US_ASCII)
            tunnel.input.offer(tcp(initial + 1, peerSeq, 0x18, body))
            val response = tunnel.output.find { it.size > 40 && (it[9].toInt() and 0xff) == 6 }
            assertArrayEquals(body, response.copyOfRange(40, response.size))
            assertEquals("203.0.113.7:443", tunnel.dialed.get())
            assertArrayEquals(body, tunnel.pipe.upload.toByteArray())
            assertTrue(tunnel.uploaded.get() >= body.size)
            assertTrue(tunnel.downloaded.get() >= body.size)
        }
    }

    @Test
    fun unsupportedUdpReturnsIcmpWithoutCallingTheTunnel() {
        MemoryTunnel().use { tunnel ->
            tunnel.input.offer(udp(443, ByteArray(16) { it.toByte() }))
            val response = tunnel.output.next()
            assertEquals(1, response[9].toInt() and 0xff)
            assertEquals(3, response[20].toInt() and 0xff)
            assertEquals(3, response[21].toInt() and 0xff)
            assertEquals(null, tunnel.dialed.get())
        }
    }

    @Test
    fun ipv6DnsQuestionIsAnsweredLocallyWithoutExternalQueries() {
        MemoryTunnel().use { tunnel ->
            val query = byteArrayOf(
                0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x03, 0x77, 0x77, 0x77, 0x07, 0x65, 0x78, 0x61, 0x6d, 0x70, 0x6c, 0x65,
                0x07, 0x69, 0x6e, 0x76, 0x61, 0x6c, 0x69, 0x64, 0x00, 0x00, 0x1c, 0x00, 0x01
            )
            tunnel.input.offer(udp(53, query))
            val response = tunnel.output.next()
            assertEquals(17, response[9].toInt() and 0xff)
            val answer = response.copyOfRange(28, response.size)
            assertEquals(0x12, answer[0].toInt() and 0xff)
            assertEquals(0x34, answer[1].toInt() and 0xff)
            assertTrue(answer[2].toInt() and 0x80 != 0)
            assertEquals(0, answer[6].toInt() and 0xff)
            assertEquals(0, answer[7].toInt() and 0xff)
            assertEquals(null, tunnel.dialed.get())
        }
    }

    private class MemoryTunnel : Closeable {
        val input = PacketInput()
        val output = PacketOutput()
        val pipe = EchoPipe()
        val dialed = AtomicReference<String?>()
        val uploaded = AtomicInteger()
        val downloaded = AtomicInteger()
        private val stack = FoxStack(input, output, 0x0a6f0002, FoxResolver(0), { host, port ->
            dialed.set("$host:$port")
            pipe
        }, { inbound, bytes ->
            if (inbound) downloaded.addAndGet(bytes.toInt()) else uploaded.addAndGet(bytes.toInt())
        })

        init {
            stack.start()
        }

        override fun close() {
            stack.stop()
            input.close()
        }
    }

    private class PacketInput : InputStream() {
        private val packets = LinkedBlockingQueue<ByteArray>()
        fun offer(packet: ByteArray) = packets.put(packet)
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val packet = packets.poll(5, TimeUnit.SECONDS) ?: return -1
            if (packet.isEmpty()) return -1
            System.arraycopy(packet, 0, buffer, offset, packet.size)
            return packet.size
        }
        override fun close() {
            packets.offer(byteArrayOf())
        }
    }

    private class PacketOutput : OutputStream() {
        private val packets = LinkedBlockingQueue<ByteArray>()
        override fun write(value: Int) = throw UnsupportedOperationException()
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            packets.put(buffer.copyOfRange(offset, offset + length))
        }
        fun next(): ByteArray {
            val packet = packets.poll(5, TimeUnit.SECONDS)
            assertNotNull("no packet returned", packet)
            return packet!!
        }
        fun find(predicate: (ByteArray) -> Boolean): ByteArray {
            repeat(5) {
                val packet = next()
                if (predicate(packet)) return packet
            }
            throw AssertionError("expected packet was not emitted")
        }
    }

    private class EchoPipe : FoxPipe {
        val upload = ByteArrayOutputStream()
        private val pending = LinkedBlockingQueue<ByteArray>()
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            val data = buffer.copyOfRange(offset, offset + length)
            upload.write(data)
            pending.put(data)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val data = pending.poll(5, TimeUnit.SECONDS) ?: return -1
            if (data.isEmpty()) return -1
            val count = minOf(data.size, length)
            System.arraycopy(data, 0, buffer, offset, count)
            return count
        }
        override fun close() {
            pending.offer(byteArrayOf())
        }
    }

    companion object {
        private fun ipv4(protocol: Int, payloadLength: Int): ByteArray {
            val packet = ByteArray(20 + payloadLength)
            packet[0] = 0x45
            packet[8] = 64
            packet[9] = protocol.toByte()
            put16(packet, 2, packet.size)
            byteArrayOf(10, 111, 0, 2).copyInto(packet, 12)
            byteArrayOf(203.toByte(), 0, 113, 7).copyInto(packet, 16)
            return packet
        }

        private fun tcp(sequence: Long, acknowledgment: Long, flags: Int, data: ByteArray): ByteArray {
            val packet = ipv4(6, 20 + data.size)
            put16(packet, 20, 40000)
            put16(packet, 22, 443)
            put32(packet, 24, sequence)
            put32(packet, 28, acknowledgment)
            packet[32] = 0x50
            packet[33] = flags.toByte()
            put16(packet, 34, 64000)
            data.copyInto(packet, 40)
            return packet
        }

        private fun udp(port: Int, data: ByteArray): ByteArray {
            val packet = ipv4(17, 8 + data.size)
            put16(packet, 20, 40000)
            put16(packet, 22, port)
            put16(packet, 24, 8 + data.size)
            data.copyInto(packet, 28)
            return packet
        }

        private fun put16(data: ByteArray, offset: Int, value: Int) {
            data[offset] = (value ushr 8).toByte()
            data[offset + 1] = value.toByte()
        }

        private fun put32(data: ByteArray, offset: Int, value: Long) {
            data[offset] = (value ushr 24).toByte()
            data[offset + 1] = (value ushr 16).toByte()
            data[offset + 2] = (value ushr 8).toByte()
            data[offset + 3] = value.toByte()
        }

        private fun u32(data: ByteArray, offset: Int): Long =
            ((data[offset].toLong() and 255) shl 24) or ((data[offset + 1].toLong() and 255) shl 16) or
                ((data[offset + 2].toLong() and 255) shl 8) or (data[offset + 3].toLong() and 255)
    }
}
