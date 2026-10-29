package com.gozar.desk

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.io.File
import java.io.InputStream
import java.io.OutputStream

interface LibC : Library {
    fun open(path: String, flags: Int): Int
    fun close(fd: Int): Int
    fun read(fd: Int, buffer: ByteArray, size: Int): Int
    fun write(fd: Int, buffer: ByteArray, size: Int): Int
    fun ioctl(fd: Int, request: Long, arg: Pointer): Int
    fun socket(domain: Int, type: Int, protocol: Int): Int
    fun connect(fd: Int, address: Pointer, size: Int): Int
}

class TunDevice(
    val name: String,
    private val input: InputStream,
    private val output: OutputStream,
    private val shutdown: () -> Unit
) {
    fun reader(): InputStream = input
    fun writer(): OutputStream = output
    fun close() {
        try {
            shutdown()
        } catch (e: Exception) {
        }
        try {
            input.close()
        } catch (e: Exception) {
        }
        try {
            output.close()
        } catch (e: Exception) {
        }
    }

    companion object {

        private const val O_RDWR = 2

        fun open(): TunDevice? = when {
            TunService.windows() -> windows()
            TunService.mac() -> mac()
            else -> linux()
        }

        private fun linux(): TunDevice? {
            val libc = TunService.libc() ?: return null
            val fd = libc.open("/dev/net/tun", O_RDWR)
            if (fd < 0) {
                TunService.note("the kernel refused /dev/net/tun; run the app as root")
                return null
            }
            val request = Memory(40).apply { clear(40) }
            request.setString(0, "gozar0")
            request.setShort(16, (0x0001 or 0x1000).toShort())
            val answer = libc.ioctl(fd, 0x400454caL, request)
            if (answer < 0) {
                TunService.note("TUNSETIFF failed; the tunnel device could not be created")
                libc.close(fd)
                return null
            }
            TunService.note("linux tun device gozar0 is ready")
            return TunDevice(
                "gozar0",
                FdInput(libc, fd),
                FdOutput(libc, fd)
            ) { libc.close(fd) }
        }

        private fun mac(): TunDevice? {
            val libc = TunService.libc() ?: return null
            val fd = libc.socket(32, 2, 2)
            if (fd < 0) {
                TunService.note("the utun control socket could not be opened")
                return null
            }
            val info = Memory(104).apply { clear(104) }
            info.setString(4, "com.apple.net.utun_control")
            if (libc.ioctl(fd, 0xc0644e03L, info) < 0) {
                TunService.note("the utun control name was not accepted")
                libc.close(fd)
                return null
            }
            val id = info.getInt(0)
            val address = Memory(32).apply { clear(32) }
            address.setByte(0, 32)
            address.setByte(1, 32)
            address.setShort(2, 2)
            address.setInt(4, id)
            address.setInt(8, 0)
            if (libc.connect(fd, address, 32) < 0) {
                TunService.note("utun did not accept the connection")
                libc.close(fd)
                return null
            }
            val unit = Memory(4)
            if (libc.ioctl(fd, 0xc01869d9L, unit) < 0) {
                TunService.note("the utun unit number could not be read")
            }
            val name = "utun" + (unit.getInt(0) - 1).coerceAtLeast(0)
            TunService.note("macOS tunnel device $name is ready")
            return TunDevice(
                name,
                FdInput(libc, fd, skip = 4),
                FdOutput(libc, fd, prefix = 2)
            ) { libc.close(fd) }
        }

        private fun windows(): TunDevice? {
            val library = try {
                val path = TunService.wintunPath()
                if (path != null) NativeLibrary.getInstance(path) else NativeLibrary.getInstance("wintun")
            } catch (e: Exception) {
                TunService.note("wintun.dll was not found; the full tunnel needs it")
                return null
            }
            return Wintun.create(library)
        }
    }
}

class FdInput(
    private val libc: LibC,
    private val fd: Int,
    private val skip: Int = 0
) : InputStream() {

    @Volatile private var open = true

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!open) return -1
        if (offset == 0 && length == buffer.size) {
            val size = libc.read(fd, buffer, length)
            if (size <= 0) return -1
            if (skip == 0) return size
            if (size <= skip) return read(buffer, offset, length)
            System.arraycopy(buffer, skip, buffer, 0, size - skip)
            return size - skip
        }
        val temp = ByteArray(length + skip)
        val size = libc.read(fd, temp, temp.size)
        if (size <= skip) return -1
        val body = size - skip
        System.arraycopy(temp, skip, buffer, offset, body)
        return body
    }

    override fun close() {
        open = false
    }
}

class FdOutput(
    private val libc: LibC,
    private val fd: Int,
    private val prefix: Int = 0
) : OutputStream() {

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (prefix == 0 && offset == 0 && length == buffer.size) {
            libc.write(fd, buffer, length)
            return
        }
        val packet = ByteArray(prefix + length)
        if (prefix == 2) {
            packet[0] = 0
            packet[1] = 2
        }
        System.arraycopy(buffer, offset, packet, prefix, length)
        libc.write(fd, packet, packet.size)
    }
}

object Wintun {

    private const val RING = 0x400000

    fun create(library: NativeLibrary): TunDevice? {
        return try {
            val createAdapter = library.getFunction("WintunCreateAdapter")
            val startSession = library.getFunction("WintunStartSession")
            val receive = library.getFunction("WintunReceivePacket")
            val releasePacket = library.getFunction("WintunReleaseReceivePacket")
            val allocate = library.getFunction("WintunAllocateSendPacket")
            val send = library.getFunction("WintunSendPacket")
            val adapter = createAdapter.invokePointer(arrayOf("Gozar", "Gozar", null))
            if (adapter == null) {
                TunService.note("wintun refused to create the adapter; run the app as administrator")
                return null
            }
            val session = startSession.invokePointer(arrayOf(adapter, RING))
            if (session == null) {
                TunService.note("the wintun session did not start")
                return null
            }
            val size = Memory(4)
            val input = object : InputStream() {
                override fun read(): Int {
                    val one = ByteArray(1)
                    return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    while (true) {
                        val pointer = receive.invokePointer(arrayOf(session, size))
                        if (pointer != null) {
                            val total = size.getInt(0)
                            val take = minOf(length, total)
                            pointer.read(0, buffer, offset, take)
                            releasePacket.invoke(arrayOf(session, pointer))
                            return take
                        }
                        Thread.sleep(1)
                    }
                }
            }
            val output = object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    val packet = allocate.invokePointer(arrayOf(session, length)) ?: return
                    packet.write(0, buffer, offset, length)
                    send.invoke(arrayOf(session, packet))
                }
            }
            TunService.note("windows adapter Gozar is ready")
            TunDevice("Gozar", input, output) {
                try {
                    library.getFunction("WintunEndSession").invoke(arrayOf(session))
                } catch (e: Exception) {
                }
                try {
                    library.getFunction("WintunCloseAdapter").invoke(arrayOf(adapter))
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
            TunService.note("wintun failed: " + (e.message ?: e.javaClass.simpleName))
            null
        }
    }
}

object TunService {

    private val notes = ArrayList<String>()

    fun windows(): Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    fun mac(): Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    fun libc(): LibC? = try {
        Native.load("c", LibC::class.java)
    } catch (e: Exception) {
        null
    }

    fun note(text: String) {
        synchronized(notes) {
            notes.add(text)
            if (notes.size > 40) notes.removeAt(0)
        }
    }

    fun last(): String = synchronized(notes) { notes.lastOrNull().orEmpty() }

    fun log(): String = synchronized(notes) { notes.joinToString("\n") }

    fun run(vararg command: String): Boolean {
        return try {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            process.inputStream.readBytes()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    fun elevated(command: String): Boolean {
        return when {
            windows() -> run(
                "powershell", "-NoProfile", "-Command",
                "Start-Process -Verb RunAs -WindowStyle Hidden -FilePath cmd -ArgumentList '/c $command'"
            )
            mac() -> run(
                "osascript", "-e",
                "do shell script \"$command\" with administrator privileges"
            )
            else -> run("pkexec", "sh", "-c", command) || run("sudo", "-n", "sh", "-c", command)
        }
    }

    fun serve(resource: String, target: File): Boolean {
        return try {
            val stream = TunService::class.java.getResourceAsStream("/" + resource) ?: return false
            stream.use { input -> target.outputStream().use { out -> input.copyTo(out) } }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun prepare(): Boolean {
        if (!windows()) return true
        return wintunPath() != null
    }

    fun wintunPath(): String? {
        if (!windows()) return null
        val base = System.getenv("LOCALAPPDATA") ?: System.getenv("APPDATA") ?: System.getProperty("user.home")
        val folder = File(base, "Gozar")
        if (!folder.exists() && !folder.mkdirs()) return null
        val target = File(folder, "wintun.dll")
        if (target.exists() && target.length() > 10000) return target.absolutePath
        if (!serve("wintun.dll", target)) return null
        return if (target.length() > 10000) target.absolutePath else null
    }

    fun jnaLoaded(): Boolean = try {
        Native.getNativeSize(Int::class.java)
        true
    } catch (e: Exception) {
        false
    }

    fun pointerText(pointer: Pointer?): String = pointer?.getString(0) ?: ""
}
