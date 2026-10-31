package cz.netkit.app.speed

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Parsed request line + headers of the loopback test server. */
internal data class TestRequest(val method: String, val path: String, val query: String, val headers: Map<String, String>) {
    fun header(name: String): String? = headers[name.lowercase()]
}

/**
 * Minimal threaded HTTP/1.1 server on 127.0.0.1 for client tests (keep-alive, Content-Length or
 * close-delimited bodies). [handler] returns `false` to close the connection.
 */
internal class LoopbackServer(
    private val handler: (TestRequest, InputStream, OutputStream, Socket) -> Boolean,
) : Closeable {
    private val server = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool()
    private val sockets = ConcurrentLinkedQueue<Socket>()
    val connections = AtomicInteger()
    val open = AtomicInteger()
    val port: Int get() = server.localPort
    val base: String get() = "http://127.0.0.1:$port/"

    init {
        pool.execute {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: IOException) { return@execute }
                sockets.add(socket)
                connections.incrementAndGet()
                pool.execute { serve(socket) }
            }
        }
    }

    private fun serve(socket: Socket) {
        open.incrementAndGet()
        try {
            socket.use {
                val input = BufferedInputStream(it.getInputStream())
                val output = it.getOutputStream()
                while (true) {
                    val request = readRequest(input) ?: return
                    if (!handler(request, input, output, it)) return
                }
            }
        } catch (_: IOException) {
            // Client cancelled or closed: expected in these tests.
        } finally {
            open.decrementAndGet()
        }
    }

    override fun close() {
        server.close()
        sockets.forEach { runCatching { it.close() } }
        pool.shutdownNow()
        pool.awaitTermination(2, TimeUnit.SECONDS)
    }

    companion object {
        fun readLine(input: InputStream): String? {
            val out = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (out.isEmpty()) null else out.toString()
                if (b == '\n'.code) return out.toString().removeSuffix("\r")
                out.append(b.toChar())
                if (out.length > 8192) throw IOException("line too long")
            }
        }

        fun readRequest(input: InputStream): TestRequest? {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) return null
            val parts = line.split(' ')
            val target = parts.getOrElse(1) { "/" }
            val headers = HashMap<String, String>()
            while (true) {
                val h = readLine(input) ?: return null
                if (h.isEmpty()) break
                val colon = h.indexOf(':')
                if (colon > 0) headers[h.substring(0, colon).trim().lowercase()] = h.substring(colon + 1).trim()
            }
            return TestRequest(parts[0], target.substringBefore('?'), target.substringAfter('?', ""), headers)
        }

        fun discard(input: InputStream, length: Long) {
            val buffer = ByteArray(64 * 1024)
            var left = length
            while (left > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n < 0) throw IOException("eof")
                left -= n
            }
        }

        fun respond(output: OutputStream, code: Int, body: ByteArray = ByteArray(0), extra: String = "") {
            output.write("HTTP/1.1 $code X\r\nContent-Length: ${body.size}\r\n$extra\r\n".toByteArray())
            output.write(body)
            output.flush()
        }
    }
}

// --- WebSocket (server side of RFC 6455, just enough for NDT7 tests) -------------------------

internal object TestWebSocket {
    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    fun accept(request: TestRequest, output: OutputStream, protocol: String?) {
        val key = request.header("sec-websocket-key") ?: throw IOException("no key")
        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray()))
        val proto = protocol?.let { "Sec-WebSocket-Protocol: $it\r\n" }.orEmpty()
        output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n$proto\r\n").toByteArray())
        output.flush()
    }

    /** Unmasked server frame. */
    fun writeFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
        output.write(0x80 or opcode)
        when {
            payload.size < 126 -> output.write(payload.size)
            payload.size < 65_536 -> { output.write(126); output.write(payload.size ushr 8); output.write(payload.size and 0xff) }
            else -> { output.write(127); for (shift in 56 downTo 0 step 8) output.write(((payload.size.toLong() ushr shift) and 0xff).toInt()) }
        }
        output.write(payload)
        output.flush()
    }

    fun writeClose(output: OutputStream) = writeFrame(output, 8, byteArrayOf(0x03, 0xE8.toByte()))

    data class Frame(val opcode: Int, val length: Long, val payload: ByteArray?)

    /** Reads one (masked) client frame; payloads larger than 64 KiB are skipped, not kept. */
    fun readFrame(input: InputStream): Frame? {
        val b0 = input.read(); if (b0 < 0) return null
        val b1 = input.read(); if (b1 < 0) return null
        var length = (b1 and 0x7f).toLong()
        if (length == 126L) length = ((input.read() shl 8) or input.read()).toLong()
        else if (length == 127L) { length = 0; repeat(8) { length = (length shl 8) or input.read().toLong() } }
        val mask = if (b1 and 0x80 != 0) ByteArray(4).also { readFully(input, it) } else null
        val keep = length <= 65_536
        val payload = if (keep) ByteArray(length.toInt()).also { readFully(input, it) } else { LoopbackServer.discard(input, length); null }
        if (payload != null && mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        return Frame(b0 and 0x0f, length, payload)
    }

    private fun readFully(input: InputStream, into: ByteArray) {
        var off = 0
        while (off < into.size) {
            val n = input.read(into, off, into.size - off)
            if (n < 0) throw IOException("eof")
            off += n
        }
    }
}
