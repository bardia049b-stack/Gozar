package com.gozar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

private fun address(a: Int, b: Int, c: Int, d: Int): Int =
    (a shl 24) or (b shl 16) or (c shl 8) or d

class FoxStackUdpTest {

    private class Recorded(val route: String, val host: String, val port: Int)

    private class FakeDatagram : FoxDatagram {
        val sent = ConcurrentLinkedQueue<ByteArray>()
        private val inbox = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        @Volatile var closed = false

        fun deliver(payload: ByteArray) = inbox.put(payload)

        override fun send(payload: ByteArray, length: Int) {
            sent.add(payload.copyOf(length))
        }

        override fun receive(buffer: ByteArray, timeoutMs: Int): Int {
            if (closed) return -1
            val payload = inbox.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return -2
            val size = minOf(payload.size, buffer.size)
            System.arraycopy(payload, 0, buffer, 0, size)
            return size
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeEngine : FoxUdpEngine {
        val opened = ConcurrentLinkedQueue<Recorded>()
        val flows = ConcurrentLinkedQueue<FakeDatagram>()

        override fun open(host: String, port: Int, route: String): FoxDatagram? {
            opened.add(Recorded(route, host, port))
            val flow = FakeDatagram()
            flows.add(flow)
            return flow
        }

        override fun warpReady(): Boolean = true
        override fun invalidate() {}
        override fun shutdown() {}
    }

    private fun udpPacket(sourcePort: Int, destinationPort: Int, destination: Int, payload: ByteArray): ByteArray {
        val total = 28 + payload.size
        val packet = ByteArray(total)
        packet[0] = 0x45
        packet[2] = (total shr 8).toByte()
        packet[3] = (total and 0xFF).toByte()
        packet[6] = 0x40
        packet[8] = 64
        packet[9] = 17
        packet[12] = 10; packet[13] = 111; packet[14] = 0; packet[15] = 2
        packet[16] = ((destination ushr 24) and 0xFF).toByte()
        packet[17] = ((destination ushr 16) and 0xFF).toByte()
        packet[18] = ((destination ushr 8) and 0xFF).toByte()
        packet[19] = (destination and 0xFF).toByte()
        packet[20] = (sourcePort shr 8).toByte()
        packet[21] = (sourcePort and 0xFF).toByte()
        packet[22] = (destinationPort shr 8).toByte()
        packet[23] = (destinationPort and 0xFF).toByte()
        val length = 8 + payload.size
        packet[24] = (length shr 8).toByte()
        packet[25] = (length and 0xFF).toByte()
        System.arraycopy(payload, 0, packet, 28, payload.size)
        return packet
    }

    private class PacketInput : InputStream() {
        private val queue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        @Volatile private var closed = false

        fun push(packet: ByteArray) = queue.put(packet)

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            while (!closed) {
                val packet = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                val size = minOf(packet.size, length)
                System.arraycopy(packet, 0, buffer, offset, size)
                return size
            }
            return -1
        }

        override fun close() {
            closed = true
        }
    }

    private class Harness(config: FoxConfig) {
        val engine = FakeEngine()
        val sink = PacketInput()
        val outgoing = ByteArrayOutputStream()
        val stack: FoxStack

        init {
            val sync = object : java.io.OutputStream() {
                override fun write(b: Int) = synchronized(outgoing) { outgoing.write(b) }
                override fun write(b: ByteArray, off: Int, len: Int) = synchronized(outgoing) { outgoing.write(b, off, len) }
            }
            stack = FoxStack(
                sink, sync, address(10, 111, 0, 2),
                FoxResolver(1, endpoints = emptyList()),
                { _, _, _ -> throw java.io.IOException("no tcp in this test") },
                { _, _ -> },
                engine,
                { config }
            )
            stack.start()
        }

        fun push(packet: ByteArray) = sink.push(packet)

        fun awaitFlows(count: Int): Boolean {
            val deadline = System.currentTimeMillis() + 4000
            while (System.currentTimeMillis() < deadline) {
                if (engine.opened.size >= count) return true
                Thread.sleep(20)
            }
            return false
        }

        fun stop() {
            stack.stop()
            try { sink.close() } catch (_: Exception) {}
        }
    }

    @Test
    fun gameModeRoutesWebUdpToWarp() {
        val harness = Harness(FoxConfig(udpMode = UdpPolicy.GAME, consoleMode = true))
        try {
            harness.push(udpPacket(40000, 443, address(93, 184, 216, 34), "hello".toByteArray()))
            assertTrue(harness.awaitFlows(1))
            val record = harness.engine.opened.first()
            assertEquals(UdpPolicy.ROUTE_WARP, record.route)
            assertEquals(443, record.port)
            assertEquals("93.184.216.34", record.host)
            val flow = harness.engine.flows.first()
            val deadline = System.currentTimeMillis() + 2000
            while (flow.sent.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals("hello", String(flow.sent.first()))
        } finally {
            harness.stop()
        }
    }

    @Test
    fun normalModeKeepsWebOnRelayAndGamesOnWarp() {
        val harness = Harness(FoxConfig(udpMode = UdpPolicy.NORMAL, consoleMode = true))
        try {
            harness.push(udpPacket(40001, 443, address(93, 184, 216, 34), "web".toByteArray()))
            harness.push(udpPacket(40002, 3074, address(1, 1, 1, 1), "game".toByteArray()))
            assertTrue(harness.awaitFlows(2))
            val routes = harness.engine.opened.associate { it.port to it.route }
            assertEquals(UdpPolicy.ROUTE_RELAY, routes[443])
            assertEquals(UdpPolicy.ROUTE_WARP, routes[3074])
        } finally {
            harness.stop()
        }
    }

    @Test
    fun relayOnlyNeverOpensWarp() {
        val harness = Harness(FoxConfig(udpMode = UdpPolicy.RELAY_ONLY, consoleMode = true))
        try {
            harness.push(udpPacket(40003, 3074, address(1, 1, 1, 1), "game".toByteArray()))
            harness.push(udpPacket(40004, 443, address(93, 184, 216, 34), "web".toByteArray()))
            assertTrue(harness.awaitFlows(1))
            Thread.sleep(300)
            assertTrue(harness.engine.opened.none { it.route == UdpPolicy.ROUTE_WARP })
            assertEquals(1, harness.engine.opened.size)
            assertEquals(443, harness.engine.opened.first().port)
        } finally {
            harness.stop()
        }
    }

    @Test
    fun masterOffOpensNoDatagramPath() {
        val harness = Harness(FoxConfig(udpMode = UdpPolicy.GAME, consoleMode = false))
        try {
            harness.push(udpPacket(40005, 3074, address(1, 1, 1, 1), "game".toByteArray()))
            harness.push(udpPacket(40006, 443, address(93, 184, 216, 34), "web".toByteArray()))
            Thread.sleep(500)
            assertEquals(0, harness.engine.opened.size)
            assertEquals(0, harness.stack.datagramCount())
        } finally {
            harness.stop()
        }
    }

    @Test
    fun batterySaverKeepsOnlyGamePortsOnWarp() {
        val harness = Harness(FoxConfig(udpMode = UdpPolicy.GAME, consoleMode = true, batterySaver = true))
        try {
            harness.push(udpPacket(40007, 443, address(93, 184, 216, 34), "web".toByteArray()))
            harness.push(udpPacket(40008, 3478, address(1, 1, 1, 1), "stun".toByteArray()))
            assertTrue(harness.awaitFlows(2))
            val routes = harness.engine.opened.associate { it.port to it.route }
            assertEquals(UdpPolicy.ROUTE_RELAY, routes[443])
            assertEquals(UdpPolicy.ROUTE_WARP, routes[3478])
        } finally {
            harness.stop()
        }
    }

    @Test
    fun replyDatagramReachesTheApplication() {
        val harness = Harness(FoxConfig(udpMode = UdpPolicy.GAME, consoleMode = true))
        try {
            harness.push(udpPacket(40009, 3074, address(1, 1, 1, 1), "ping".toByteArray()))
            assertTrue(harness.awaitFlows(1))
            val flow = harness.engine.flows.first()
            val deadline = System.currentTimeMillis() + 2000
            while (flow.sent.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
            flow.deliver("pong".toByteArray())
            val answered = System.currentTimeMillis() + 3000
            var found = false
            while (System.currentTimeMillis() < answered && !found) {
                val bytes = synchronized(harness.outgoing) { harness.outgoing.toByteArray() }
                found = String(bytes, Charsets.ISO_8859_1).contains("pong")
                if (!found) Thread.sleep(20)
            }
            assertTrue("the reply datagram must be written back to the tunnel", found)
        } finally {
            harness.stop()
        }
    }

    @Test
    fun tinyGameDatagramsAreNotDropped() {
        val harness = Harness(FoxConfig(udpMode = UdpPolicy.GAME, consoleMode = true))
        try {
            harness.push(udpPacket(40010, 3074, address(1, 1, 1, 1), byteArrayOf(0x01)))
            assertTrue(harness.awaitFlows(1))
            val flow = harness.engine.flows.first()
            val deadline = System.currentTimeMillis() + 2000
            while (flow.sent.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals(1, flow.sent.first().size)
        } finally {
            harness.stop()
        }
    }
}
