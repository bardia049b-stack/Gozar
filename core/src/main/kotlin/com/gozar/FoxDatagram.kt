package com.gozar

interface FoxDatagram {
    fun send(payload: ByteArray, length: Int)

    fun receive(buffer: ByteArray, timeoutMs: Int): Int

    fun close()
}

interface FoxUdpEngine {
    fun open(host: String, port: Int, route: String): FoxDatagram?

    fun warpReady(): Boolean

    fun invalidate()

    fun shutdown()
}
