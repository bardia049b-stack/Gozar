package com.gozar.desk

import com.gozar.FoxPipe
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

object ProxyTest {

    private class Direct(private val socket: Socket) : FoxPipe {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            socket.getInputStream().read(buffer, offset, length)

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            socket.getOutputStream().write(buffer, offset, length)
            socket.getOutputStream().flush()
        }

        override fun close() {
            try {
                socket.close()
            } catch (e: Exception) {
            }
        }
    }

    private fun echo(server: ServerSocket) {
        thread(isDaemon = true) {
            while (true) {
                val client = try {
                    server.accept()
                } catch (e: Exception) {
                    return@thread
                }
                thread(isDaemon = true) {
                    try {
                        val input = BufferedInputStream(client.getInputStream())
                        val output = BufferedOutputStream(client.getOutputStream())
                        val head = StringBuilder()
                        while (!head.endsWith("\r\n\r\n") && head.length < 4096) {
                            val c = input.read()
                            if (c < 0) break
                            head.append(c.toChar())
                        }
                        val body = "answer=" + head.lineSequence().first()
                        output.write(("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").toByteArray())
                        output.flush()
                    } catch (e: Exception) {
                    } finally {
                        try {
                            client.close()
                        } catch (e: Exception) {
                        }
                    }
                }
            }
        }
    }

    private fun fill(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var done = 0
        while (done < size) {
            val read = input.read(buffer, done, size - done)
            if (read < 0) throw IllegalStateException("short read")
            done += read
        }
        return buffer
    }

    private fun text(input: InputStream): String {
        val out = StringBuilder()
        while (out.length < 8192 && !out.contains("\r\n\r\n")) {
            val c = input.read()
            if (c < 0) return out.toString()
            out.append(c.toChar())
        }
        val head = out.toString()
        val length = head.lineSequence()
            .firstOrNull { it.startsWith("Content-Length:", true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        var left = length
        while (left > 0) {
            val c = input.read()
            if (c < 0) break
            out.append(c.toChar())
            left--
        }
        return out.toString()
    }

    fun run(): String {
        val report = StringBuilder()
        var failures = 0

        fun check(name: String, ok: Boolean, detail: String = "") {
            if (!ok) failures++
            report.append(if (ok) "ok   " else "FAIL ").append(name)
            if (detail.isNotEmpty()) report.append(" -> ").append(detail)
            report.append('\n')
        }

        val origin = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        echo(origin)
        val target = origin.localPort
        val proxyPort = 18999
        val proxy = LocalProxy(proxyPort) { host, port -> Direct(Socket().apply { connect(InetSocketAddress(host, port), 4000) }) }
        check("proxy start", proxy.start(), proxyPort.toString())

        try {
            val socks = Socket("127.0.0.1", proxyPort)
            socks.soTimeout = 8000
            val input = BufferedInputStream(socks.getInputStream())
            val output = BufferedOutputStream(socks.getOutputStream())
            output.write(byteArrayOf(0x05, 0x01, 0x00))
            output.flush()
            val greet = fill(input, 2)
            check("socks greeting", greet[0].toInt() == 0x05 && greet[1].toInt() == 0x00)
            val host = "127.0.0.1".toByteArray()
            output.write(byteArrayOf(0x05, 0x01, 0x00, 0x03, host.size.toByte()))
            output.flush()
            output.write(host)
            output.write(byteArrayOf(((target shr 8) and 0xFF).toByte(), (target and 0xFF).toByte()))
            output.flush()
            val head = fill(input, 4)
            check("socks connect reply", head[1].toInt() == 0x00, "code " + head[1].toInt())
            if (head[3].toInt() == 0x01) fill(input, 6) else fill(input, 4)
            output.write("GET /socks HTTP/1.1\r\nHost: test\r\n\r\n".toByteArray())
            output.flush()
            val answer = text(input)
            check("socks payload", answer.contains("answer=GET /socks"), answer.take(60))
            socks.close()

            val web = Socket("127.0.0.1", proxyPort)
            web.soTimeout = 8000
            val webInput = BufferedInputStream(web.getInputStream())
            val webOutput = BufferedOutputStream(web.getOutputStream())
            webOutput.write("CONNECT 127.0.0.1:$target HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
            webOutput.flush()
            val established = readLine(webInput)
            check("connect reply", established.contains("200"), established)
            webOutput.write("GET /connect HTTP/1.1\r\nHost: test\r\n\r\n".toByteArray())
            webOutput.flush()
            val through = text(webInput)
            check("connect payload", through.contains("answer=GET /connect"), through.take(60))
            web.close()

            val plain = Socket("127.0.0.1", proxyPort)
            plain.soTimeout = 8000
            val plainInput = BufferedInputStream(plain.getInputStream())
            val plainOutput = BufferedOutputStream(plain.getOutputStream())
            plainOutput.write("GET http://127.0.0.1:$target/plain HTTP/1.1\r\nHost: 127.0.0.1\r\nProxy-Connection: keep-alive\r\n\r\n".toByteArray())
            plainOutput.flush()
            val forwarded = text(plainInput)
            check("plain http forwarded", forwarded.contains("answer=GET /plain"), forwarded.take(60))
            check("proxy header stripped", !forwarded.contains("Proxy-Connection", true))
            plain.close()
        } catch (e: Exception) {
            check("transport", false, e.message ?: e.toString())
        } finally {
            proxy.stop()
            try {
                origin.close()
            } catch (e: Exception) {
            }
        }

        report.append(if (failures == 0) "ALL OK" else "FAILURES: $failures")
        return report.toString()
    }

    private fun readLine(input: InputStream): String {
        val builder = StringBuilder()
        var last = 0
        while (builder.length < 512) {
            val c = input.read()
            if (c < 0) break
            builder.append(c.toChar())
            if (last == '\r'.code && c == '\n'.code) break
            last = c
        }
        return builder.toString().trim()
    }
}
