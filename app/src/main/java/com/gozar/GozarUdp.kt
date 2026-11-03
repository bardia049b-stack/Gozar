package com.gozar

import android.content.Context
import gozarcore.Gozarcore
import gozarcore.Datagram
import gozarcore.Stream
import gozarcore.Warp
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class GozarUdp(private val context: Context) : FoxUdpEngine {

    private val lock = Any()
    private val flows = ConcurrentHashMap.newKeySet<FoxDatagram>()
    private val pipes = ConcurrentHashMap.newKeySet<NativePipe>()

    @Volatile private var warp: Warp? = null
    @Volatile private var warpFailedAt = 0L
    @Volatile private var warpFailures = 0
    @Volatile private var shut = false

    override fun open(host: String, port: Int, route: String): FoxDatagram? {
        if (shut) return null
        if (route == UdpPolicy.ROUTE_WARP) {
            val tunnel = warpOrNull() ?: return relayFlow(host, port)
            val native = try {
                tunnel.datagram(host, port.toLong())
            } catch (_: Exception) {
                null
            }
            if (native != null) {
                noteWarpSuccess()
                return track(NativeDatagram(native))
            }
            noteWarpFailure()
            return relayFlow(host, port)
        }
        return relayFlow(host, port)
    }

    fun warpStream(host: String, port: Int): FoxPipe? {
        if (shut) return null
        val tunnel = warpOrNull() ?: return null
        val native = try {
            tunnel.stream(host, port.toLong())
        } catch (_: Exception) {
            null
        }
        if (native == null) {
            noteWarpFailure()
            return null
        }
        noteWarpSuccess()
        val pipe = NativePipe(native)
        pipes.add(pipe)
        return pipe
    }

    private fun relayFlow(host: String, port: Int): FoxDatagram? {
        if (!Store.udpRelay(context)) return null
        val location = FoxLink.location ?: return null
        val pass = FoxStore.pass(context)
        if (pass.isEmpty()) return null
        val relay = try {
            Gozarcore.openRelay(location.host, location.port.toLong(), location.host, pass)
        } catch (_: Exception) {
            return null
        }
        val native = try {
            relay.datagram(host, port.toLong())
        } catch (_: Exception) {
            null
        } ?: return null
        return track(NativeDatagram(native))
    }

    private fun track(flow: FoxDatagram): FoxDatagram {
        flows.add(flow)
        return flow
    }

    private fun warpOrNull(): Warp? {
        if (shut) return null
        synchronized(lock) {
            val current = warp
            if (current != null && current.alive()) return current
            if (current != null) {
                try {
                    current.close()
                } catch (_: Exception) {
                }
                warp = null
            }
            if (System.currentTimeMillis() < warpFailedAt) return null
            val config = Store.config(context)
            val location = FoxLink.location
            val pass = FoxStore.pass(context)
            val created = try {
                Gozarcore.openWarp(
                    context.filesDir.absolutePath,
                    config.warpVia,
                    location?.host ?: "",
                    (location?.port ?: 0).toLong(),
                    location?.host ?: "",
                    pass,
                    0L,
                    25000L
                )
            } catch (_: Exception) {
                null
            }
            if (created == null) {
                noteWarpFailure()
                return null
            }
            warp = created
            return created
        }
    }

    private fun noteWarpFailure() {
        synchronized(lock) {
            if (warpFailures < 5) warpFailures++
            val delay = 1000L shl (warpFailures - 1)
            warpFailedAt = System.currentTimeMillis() + minOf(delay, 30000L)
            val current = warp
            warp = null
            if (current != null) {
                try {
                    current.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun noteWarpSuccess() {
        warpFailures = 0
        warpFailedAt = 0L
    }

    override fun warpReady(): Boolean = warp?.alive() == true

    override fun invalidate() {
        synchronized(lock) {
            val current = warp
            warp = null
            if (current != null) {
                try {
                    current.close()
                } catch (_: Exception) {
                }
            }
            warpFailedAt = System.currentTimeMillis() + 1000L
        }
        for (flow in flows) {
            try {
                flow.close()
            } catch (_: Exception) {
            }
        }
        flows.clear()
    }

    override fun shutdown() {
        shut = true
        invalidate()
        for (pipe in pipes) {
            try {
                pipe.close()
            } catch (_: Exception) {
            }
        }
        pipes.clear()
    }

    private class NativeDatagram(private val native: Datagram) : FoxDatagram {
        @Volatile private var closed = false

        override fun send(payload: ByteArray, length: Int) {
            if (closed) throw IOException("datagram closed")
            val body = if (length == payload.size) payload else payload.copyOf(length)
            native.send(body)
        }

        override fun receive(buffer: ByteArray, timeoutMs: Int): Int {
            if (closed) return -1
            val payload = try {
                native.receive(timeoutMs.toLong())
            } catch (_: Exception) {
                return -1
            } ?: return -2
            if (payload.isEmpty()) return -1
            val size = minOf(payload.size, buffer.size)
            System.arraycopy(payload, 0, buffer, 0, size)
            return size
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                native.close()
            } catch (_: Exception) {
            }
        }
    }

    private inner class NativePipe(private val native: Stream) : FoxPipe {
        @Volatile private var closed = false
        private var pending: ByteArray? = null
        private var pendingAt = 0

        init {
            native.setDeadline(60000L)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (closed) return -1
            while (true) {
                val held = pending
                if (held != null) {
                    val size = minOf(length, held.size - pendingAt)
                    System.arraycopy(held, pendingAt, buffer, offset, size)
                    pendingAt += size
                    if (pendingAt >= held.size) {
                        pending = null
                        pendingAt = 0
                    }
                    return size
                }
                val chunk = try {
                    native.read(length.toLong())
                } catch (_: Exception) {
                    return -1
                } ?: continue
                if (chunk.isEmpty()) return -1
                val size = minOf(length, chunk.size)
                System.arraycopy(chunk, 0, buffer, offset, size)
                if (chunk.size > size) {
                    pending = chunk
                    pendingAt = size
                }
                return size
            }
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (closed) throw IOException("stream closed")
            val body = if (offset == 0 && length == buffer.size) buffer else buffer.copyOfRange(offset, offset + length)
            val written = native.write(body)
            if (written < 0) throw IOException("stream write failed")
            if (written < body.size) throw IOException("short write")
        }

        override fun close() {
            if (closed) return
            closed = true
            pipes.remove(this)
            try {
                native.close()
            } catch (_: Exception) {
            }
        }
    }
}
