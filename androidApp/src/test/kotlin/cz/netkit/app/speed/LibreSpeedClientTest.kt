package cz.netkit.app.speed

import cz.netkit.shared.speed.LibreSpeedProtocol
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** LibreSpeed client against an in-process HTTP/1.1 server on 127.0.0.1 (no external network). */
class LibreSpeedClientTest {
    private class Stats {
        val garbageRequests = AtomicInteger()
        val uploadBodies = ConcurrentLinkedQueue<Long>()
        val uploadBytes = AtomicLong()
        val acceptEncoding = ConcurrentLinkedQueue<String>()
        val userAgents = ConcurrentLinkedQueue<String>()
    }

    /** LibreSpeed-like backend; [maxBody] simulates a body-size limit (413 + close without reading). */
    private fun backend(stats: Stats, garbageStatus: Int = 200, maxBody: Long = Long.MAX_VALUE, slowGarbage: Boolean = false) =
        LoopbackServer { request, input, output, _ ->
            request.header("user-agent")?.let(stats.userAgents::add)
            when {
                request.path == "/backend/empty.php" && request.method == "GET" -> { LoopbackServer.respond(output, 200); true }
                request.path == "/backend/empty.php" && request.method == "POST" -> {
                    val length = request.header("content-length")?.toLong() ?: 0
                    if (length > maxBody) { LoopbackServer.respond(output, 413, extra = "Connection: close\r\n"); false }
                    else {
                        LoopbackServer.discard(input, length)
                        stats.uploadBodies.add(length)
                        stats.uploadBytes.addAndGet(length)
                        LoopbackServer.respond(output, 200)
                        true
                    }
                }
                request.path == "/backend/garbage.php" -> {
                    stats.garbageRequests.incrementAndGet()
                    request.header("accept-encoding")?.let(stats.acceptEncoding::add)
                    if (garbageStatus != 200) { LoopbackServer.respond(output, garbageStatus); true }
                    else {
                        val mib = request.query.substringAfter("ckSize=").substringBefore('&').toInt()
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: ${mib.toLong() shl 20}\r\n\r\n".toByteArray())
                        val chunk = ByteArray(64 * 1024)
                        repeat(mib * 16) {
                            output.write(chunk)
                            if (slowGarbage) Thread.sleep(50)
                        }
                        output.flush()
                        true
                    }
                }
                request.path == "/backend/getIP.php" -> {
                    LoopbackServer.respond(output, 200, """{"processedString":"192.0.2.7 - Test ISP","rawIspInfo":""}""".toByteArray())
                    true
                }
                else -> { LoopbackServer.respond(output, 404); true }
            }
        }

    private fun client(server: LoopbackServer) =
        LibreSpeedClient(speedHttpClient(), server.base, "NetKit-test", allowLoopbackCleartext = true)

    @Test fun refusesCleartextOutsideTests() {
        assertFailsWith<IllegalArgumentException> { LibreSpeedClient(speedHttpClient(), "http://example.org/", "ua") }
        assertFailsWith<IllegalArgumentException> { LibreSpeedClient(speedHttpClient(), "http://10.0.0.1:80/", "ua", allowLoopbackCleartext = true) }
    }

    @Test fun idleLatencyAndIpInfo() = runBlocking {
        val stats = Stats()
        backend(stats).use { server ->
            val c = client(server)
            val samples = c.idleLatency(5, gapMs = 10)
            assertEquals(5, samples.size)
            assertTrue(samples.all { it != null && it >= 0.0 })
            // One warm-up + five probes reuse a single kept-alive connection.
            assertEquals(1, server.connections.get())
            assertEquals("192.0.2.7 - Test ISP", c.ipInfo()?.summary)
            assertTrue(stats.userAgents.all { it == "NetKit-test" })
        }
    }

    @Test fun idleLatencyFailsTypedWhenServerIsDown() = runBlocking {
        val port = LoopbackServer { _, _, _, _ -> false }.use { it.port }
        val c = LibreSpeedClient(speedHttpClient(), "http://127.0.0.1:$port/", "ua", allowLoopbackCleartext = true)
        val failure = assertFailsWith<SpeedTestFailure> { c.idleLatency(3, 10) }
        assertEquals(SpeedFailureKind.CONNECT, failure.kind)
    }

    @Test fun downloadUsesParallelConnectionsAndIdentityEncoding() = runBlocking {
        val stats = Stats()
        backend(stats).use { server ->
            val meter = DirectionMeter()
            val loaded = ConcurrentLinkedQueue<Double?>()
            val c = client(server)
            c.download(4, 700, meter) { registry, start -> c.loadedProbeLoop(registry, start, 100) { _, ms -> loaded.add(ms) } }
            assertTrue(meter.bytes.get() > 1_000_000, "bytes ${meter.bytes.get()}")
            assertTrue(meter.finishedAtMs >= meter.startedAtMs)
            // 4 transfer connections + 1 probe connection: HTTP/1.1, no multiplexing.
            assertTrue(server.connections.get() >= 5, "connections ${server.connections.get()}")
            assertTrue(stats.acceptEncoding.all { it == "identity" })
            assertTrue(loaded.isNotEmpty())
        }
    }

    @Test fun downloadHttpErrorIsTyped() = runBlocking {
        backend(Stats(), garbageStatus = 503).use { server ->
            val failure = assertFailsWith<SpeedTestFailure> { client(server).download(2, 500, DirectionMeter()) }
            assertEquals(SpeedFailureKind.HTTP_STATUS, failure.kind)
            assertEquals(503, failure.httpCode)
        }
    }

    @Test fun uploadStreamsRandomBodiesAndHalvesOnBodyLimit() = runBlocking {
        val stats = Stats()
        backend(stats, maxBody = 1L shl 20).use { server ->
            val meter = DirectionMeter()
            val c = client(server)
            c.upload(3, 1_200, meter)
            assertEquals(1 shl 20, c.uploadBodyBytes)
            assertTrue(stats.uploadBodies.isNotEmpty())
            assertTrue(stats.uploadBodies.all { it <= 1L shl 20 })
            // The client counts what it wrote; the server received at least the completed bodies.
            assertTrue(meter.bytes.get() >= stats.uploadBytes.get(), "client ${meter.bytes.get()} server ${stats.uploadBytes.get()}")
        }
    }

    @Test fun cancellationStopsAllStreamsPromptly() = runBlocking {
        val stats = Stats()
        backend(stats, slowGarbage = true).use { server ->
            val meter = DirectionMeter()
            val job = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                client(server).download(4, 30_000, meter)
            }
            withTimeout(5_000) { while (server.open.get() < 4) delay(20) }
            val cancelledAt = System.nanoTime()
            job.cancel()
            withTimeout(3_000) { job.join() }
            assertTrue((System.nanoTime() - cancelledAt) / 1_000_000 < 2_000, "cancel took too long")
            withTimeout(3_000) { while (server.open.get() > 0) delay(20) }
            assertNotNull(meter.finishedAtMs.takeIf { it >= 0 })
        }
    }

    @Test fun uploadPayloadIsNotCompressible() {
        // Random payload: a repeated-byte body would let a compressing middlebox inflate the result.
        val field = LibreSpeedClient::class.java.getDeclaredField("payload").apply { isAccessible = true }
        val payload = field.get(LibreSpeedClient(speedHttpClient(), "https://x.example/", "ua")) as ByteArray
        assertEquals(1 shl 20, payload.size)
        val deflater = java.util.zip.Deflater()
        deflater.setInput(payload); deflater.finish()
        val out = ByteArray(payload.size + 1024)
        val compressed = deflater.deflate(out)
        assertTrue(compressed > payload.size * 0.99, "compressed to $compressed")
    }

    @Test fun clientIgnoresSystemProxy() {
        // The default ProxySelector belongs to the default network, not to the bound Wi-Fi.
        assertEquals(java.net.Proxy.NO_PROXY, speedHttpClient().proxy)
    }

    @Test fun catchAllWebAppIsNotLibreSpeed() = runBlocking {
        val page = "<!doctype html><html><body>${"x".repeat(2_000)}</body></html>".toByteArray()
        LoopbackServer { _, _, output, _ ->
            LoopbackServer.respond(output, 200, page, "Content-Type: text/html\r\n"); true
        }.use { server ->
            val failure = assertFailsWith<SpeedTestFailure> { client(server).idleLatency(3, 10) }
            assertEquals(SpeedFailureKind.NOT_LIBRESPEED, failure.kind)
        }
    }

    @Test fun tinyOrHtmlGarbageFailsTheDownload() = runBlocking {
        for (type in listOf("application/octet-stream", "text/html; charset=UTF-8")) {
            LoopbackServer { request, _, output, _ ->
                if (request.path == "/backend/garbage.php") LoopbackServer.respond(output, 200, ByteArray(4_096), "Content-Type: $type\r\n")
                else LoopbackServer.respond(output, 200)
                true
            }.use { server ->
                val started = System.nanoTime()
                val failure = assertFailsWith<SpeedTestFailure> { client(server).download(3, 5_000, DirectionMeter()) }
                assertEquals(SpeedFailureKind.NOT_LIBRESPEED, failure.kind, type)
                assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000, "fails fast, does not loop for the whole direction")
            }
        }
    }

    @Test fun connectionLimitIsReportedAsDegradedStreams() = runBlocking {
        // A proxy with limit_conn 2: further connections get 503 on every request.
        val allowed = java.util.Collections.synchronizedSet(HashSet<java.net.Socket>())
        LoopbackServer { request, _, output, socket ->
            if (request.path == "/backend/garbage.php") {
                val ok = synchronized(allowed) { socket in allowed || (allowed.size < 2 && allowed.add(socket)) }
                if (!ok) { LoopbackServer.respond(output, 503); return@LoopbackServer true }
                output.write("HTTP/1.1 200 OK\r\nContent-Length: ${25L shl 20}\r\n\r\n".toByteArray())
                val chunk = ByteArray(64 * 1024)
                try { repeat(25 * 16) { output.write(chunk); Thread.sleep(2) } } catch (_: java.io.IOException) { return@LoopbackServer false }
                true
            } else { LoopbackServer.respond(output, 200); true }
        }.use { server ->
            val meter = DirectionMeter()
            val streams = client(server).download(4, 800, meter)
            assertEquals(4, streams.planned)
            assertEquals(2, streams.working)
            assertEquals(503, streams.firstFailureCode)
            assertTrue(meter.bytes.get() > 0)
        }
    }

    @Test fun garbageContentTypeRules() {
        assertTrue(garbageContentType(null))
        assertTrue(garbageContentType("application/octet-stream"))
        assertFalse(garbageContentType("text/html; charset=UTF-8"))
        assertFalse(garbageContentType("application/json"))
    }

    @Test fun garbageChunkIsTwentyFiveMiB() {
        assertTrue(LibreSpeedProtocol.garbageUrl("https://x/", 1).contains("ckSize=25"))
    }
}
