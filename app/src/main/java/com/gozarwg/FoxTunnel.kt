package com.gozarwg

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class FoxDuplex(
    private val socket: SSLSocket,
    private val input: BufferedInputStream,
    private val output: BufferedOutputStream
) {

    fun read(buffer: ByteArray, offset: Int, length: Int): Int = input.read(buffer, offset, length)

    fun write(buffer: ByteArray, offset: Int, length: Int) {
        output.write(buffer, offset, length)
        output.flush()
    }

    fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }
}

object FoxTunnel {

    private const val AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:149.0) Gecko/20100101 Firefox/149.0"

    fun open(location: FoxLocation, host: String, port: Int, pass: String, timeout: Int = 15000): FoxDuplex {
        val authority = if (port == 80 || port == 443) host else "$host:$port"
        val socket = connect(location, timeout)
        try {
            val output = BufferedOutputStream(socket.outputStream)
            val request = "CONNECT $authority HTTP/1.1\r\n" +
                "Host: $authority\r\n" +
                "Proxy-Authorization: Bearer $pass\r\n" +
                "User-Agent: $AGENT\r\n" +
                "Proxy-Connection: keep-alive\r\n\r\n"
            output.write(request.toByteArray(Charsets.UTF_8))
            output.flush()

            val input = BufferedInputStream(socket.inputStream)
            val (status, headers) = response(input)
            if (status == 200) {
                socket.soTimeout = 0
                return FoxDuplex(socket, input, output)
            }
            socket.close()
            throw java.io.IOException(reason(status, headers))
        } catch (e: Exception) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
            throw e
        }
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
            return body.lines().firstOrNull { it.isNotBlank() }?.trim()?.take(64) ?: ""
        } finally {
            tunnel.close()
        }
    }

    private fun connect(location: FoxLocation, timeout: Int): SSLSocket {
        val raw = Socket()
        raw.connect(InetSocketAddress(location.host, location.port), timeout)
        raw.soTimeout = timeout
        val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(raw, location.host, location.port, true) as SSLSocket
        tls.sslParameters = tls.sslParameters.apply {
            applicationProtocols = arrayOf("http/1.1")
            serverNames = listOf(javax.net.ssl.SNIHostName(location.host))
        }
        tls.startHandshake()
        return tls
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

    private fun reason(status: Int, headers: Map<String, String>): String {
        val failure = headers["x-fastly-proxy-auth-failed"]
        return when {
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
