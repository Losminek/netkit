package cz.netkit.app.speed

import cz.netkit.shared.speed.Ndt7Measurement
import cz.netkit.shared.speed.Ndt7Protocol
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** NDT7 client against an in-process WebSocket server on 127.0.0.1 (no external network). */
class Ndt7ClientTest {
    private val fast = Ndt7Timing(handshakeMs = 2_000, uploadSendMs = 800, softCloseMs = 1_500, hardCapMs = 2_500)
    private fun client(timing: Ndt7Timing = fast, locate: String = Ndt7Protocol.LOCATE_URL) =
        Ndt7Client(speedHttpClient(), "NetKit-test", "0.0.1", timing = timing, locateUrl = locate)

    private val measurement = """{"AppInfo":{"ElapsedTime":500000,"NumBytes":1000000},"ConnectionInfo":{"Client":"127.0.0.1:1"},
        "TCPInfo":{"BytesAcked":1100000,"ElapsedTime":500000,"MinRTT":900,"RTT":4200}}""".replace("\n", "")

    @Test fun downloadCountsBinaryBytesAndParsesMeasurements() = runBlocking {
        val protocolHeader = AtomicReference<String?>()
        val query = AtomicReference<String?>()
        val sent = AtomicLong()
        LoopbackServer { request, input, output, _ ->
            protocolHeader.set(request.header("sec-websocket-protocol"))
            query.set(request.query)
            TestWebSocket.accept(request, output, Ndt7Protocol.SUBPROTOCOL)
            val chunk = ByteArray(32 * 1024)
            repeat(40) { i ->
                TestWebSocket.writeFrame(output, 2, chunk)
                sent.addAndGet(chunk.size.toLong())
                if (i % 10 == 0) TestWebSocket.writeFrame(output, 1, measurement.toByteArray())
                Thread.sleep(10)
            }
            TestWebSocket.writeClose(output)
            TestWebSocket.readFrame(input) // client's close
            false
        }.use { server ->
            val meter = DirectionMeter()
            val messages = ConcurrentLinkedQueue<Ndt7Measurement>()
            val outcome = client().download("ws://127.0.0.1:${server.port}/ndt/v7/download?access_token=T", meter) { _, m -> messages.add(m) }
            assertEquals(Ndt7End.SERVER_CLOSED, outcome.end)
            assertEquals(sent.get(), meter.bytes.get())
            assertTrue(messages.size >= 3)
            assertEquals(4200.0, messages.last().tcpInfo?.rttUs)
            assertEquals(Ndt7Protocol.SUBPROTOCOL, protocolHeader.get())
            assertTrue(query.get()!!.contains("access_token=T&client_name=netkit-android&client_version=0.0.1"))
        }
    }

    @Test fun uploadSendsScaledMessagesAndStopsAfterSendTime() = runBlocking {
        val received = AtomicLong()
        val sizes = ConcurrentLinkedQueue<Long>()
        LoopbackServer { request, input, output, _ ->
            TestWebSocket.accept(request, output, Ndt7Protocol.SUBPROTOCOL)
            var lastReport = System.nanoTime()
            while (true) {
                val frame = TestWebSocket.readFrame(input) ?: break
                if (frame.opcode == 8) { TestWebSocket.writeClose(output); break }
                if (frame.opcode == 2) { received.addAndGet(frame.length); sizes.add(frame.length) }
                if (System.nanoTime() - lastReport > 200_000_000) {
                    TestWebSocket.writeFrame(output, 1, """{"AppInfo":{"ElapsedTime":1,"NumBytes":${received.get()}}}""".toByteArray())
                    lastReport = System.nanoTime()
                }
            }
            false
        }.use { server ->
            val meter = DirectionMeter()
            val messages = ConcurrentLinkedQueue<Ndt7Measurement>()
            val started = System.nanoTime()
            val outcome = client().upload("ws://127.0.0.1:${server.port}/ndt/v7/upload", meter) { _, m -> messages.add(m) }
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(Ndt7End.SERVER_CLOSED, outcome.end)
            assertTrue(tookMs in 700..2_400, "took $tookMs ms")
            assertTrue(meter.bytes.get() > 0)
            assertTrue(received.get() >= meter.bytes.get() - 2L * (1 shl 20), "server ${received.get()} client ${meter.bytes.get()}")
            assertEquals(8_192L, sizes.first())
            assertTrue(sizes.all { it <= Ndt7Protocol.MESSAGE_MAX_BYTES })
            assertTrue(sizes.maxOrNull()!! > 8_192L, "message size scaled up")
            assertTrue(messages.isNotEmpty())
        }
    }

    @Test fun serverThatNeverClosesHitsTheHardCap() = runBlocking {
        LoopbackServer { request, _, output, socket ->
            TestWebSocket.accept(request, output, Ndt7Protocol.SUBPROTOCOL)
            val chunk = ByteArray(8 * 1024)
            try {
                while (!socket.isClosed) { TestWebSocket.writeFrame(output, 2, chunk); Thread.sleep(20) }
            } catch (_: java.io.IOException) { }
            false
        }.use { server ->
            val started = System.nanoTime()
            val outcome = client().download("ws://127.0.0.1:${server.port}/ndt/v7/download", DirectionMeter()) { _, _ -> }
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(outcome.end == Ndt7End.HARD_CAP || outcome.end == Ndt7End.ABRUPT, "end ${outcome.end}")
            assertTrue(tookMs < fast.hardCapMs + 1_500, "took $tookMs ms")
        }
    }

    @Test fun binaryMessageDuringUploadIsAProtocolError() = runBlocking {
        LoopbackServer { request, _, output, _ ->
            TestWebSocket.accept(request, output, Ndt7Protocol.SUBPROTOCOL)
            TestWebSocket.writeFrame(output, 2, ByteArray(2048))
            Thread.sleep(500)
            false
        }.use { server ->
            val failure = assertFailsWith<SpeedTestFailure> {
                client().upload("ws://127.0.0.1:${server.port}/ndt/v7/upload", DirectionMeter()) { _, _ -> }
            }
            assertEquals(SpeedFailureKind.PROTOCOL, failure.kind)
        }
    }

    @Test fun rejectedUpgradeIsTypedHttpStatus() = runBlocking {
        LoopbackServer { _, _, output, _ -> LoopbackServer.respond(output, 403); false }.use { server ->
            val failure = assertFailsWith<SpeedTestFailure> {
                client().download("ws://127.0.0.1:${server.port}/ndt/v7/download", DirectionMeter()) { _, _ -> }
            }
            assertEquals(SpeedFailureKind.HTTP_STATUS, failure.kind)
            assertEquals(403, failure.httpCode)
        }
    }

    private fun target(port: Int, name: String) = cz.netkit.shared.speed.Ndt7Target(name, null, null,
        "ws://127.0.0.1:$port/ndt/v7/download", "ws://127.0.0.1:$port/ndt/v7/upload")

    @Test fun unreachableServerFallsBackToTheNextLocatedOne() = runBlocking {
        val refusedPort = LoopbackServer { _, _, _, _ -> false }.use { it.port }
        LoopbackServer { request, input, output, _ ->
            TestWebSocket.accept(request, output, Ndt7Protocol.SUBPROTOCOL)
            repeat(5) { TestWebSocket.writeFrame(output, 2, ByteArray(4_096)) }
            TestWebSocket.writeClose(output)
            TestWebSocket.readFrame(input)
            false
        }.use { server ->
            val meter = DirectionMeter()
            val c = client()
            val tried = ArrayList<String>()
            val (used, outcome) = c.firstReachable(listOf(target(refusedPort, "down"), target(server.port, "up")), meter) {
                tried.add(it.machine)
                c.download(it.downloadUrl, meter) { _, _ -> }
            }
            assertEquals(listOf("down", "up"), tried)
            assertEquals("up", used.machine)
            assertEquals(Ndt7End.SERVER_CLOSED, outcome.end)
            assertEquals(5L * 4_096, meter.bytes.get())
        }
    }

    @Test fun protocolErrorOrStartedTransferIsNotRetried() = runBlocking {
        val tries = java.util.concurrent.atomic.AtomicInteger()
        val failure = assertFailsWith<SpeedTestFailure> {
            client().firstReachable(listOf(target(1, "a"), target(2, "b")), DirectionMeter()) {
                tries.incrementAndGet(); throw SpeedTestFailure(SpeedFailureKind.PROTOCOL)
            }
        }
        assertEquals(SpeedFailureKind.PROTOCOL, failure.kind)
        assertEquals(1, tries.get())
        val started = DirectionMeter().apply { start(1) }
        assertFailsWith<SpeedTestFailure> {
            client().firstReachable(listOf(target(1, "a"), target(2, "b")), started) {
                tries.incrementAndGet(); throw SpeedTestFailure(SpeedFailureKind.CONNECT)
            }
        }
        assertEquals(2, tries.get(), "data already flowed: no second server")
        val all = assertFailsWith<SpeedTestFailure> {
            client().firstReachable(List(5) { target(it + 1, "t$it") }, DirectionMeter()) {
                tries.incrementAndGet(); throw SpeedTestFailure(SpeedFailureKind.CONNECT)
            }
        }
        assertEquals(SpeedFailureKind.CONNECT, all.kind)
        assertEquals(2 + NDT7_MAX_TARGETS, tries.get(), "at most $NDT7_MAX_TARGETS servers")
    }

    @Test fun malformedUrlIsATypedFailureNotACrash() = runBlocking {
        val failure = assertFailsWith<SpeedTestFailure> {
            client().download("wss://a..measurement-lab.org/ndt/v7/download", DirectionMeter()) { _, _ -> }
        }
        assertEquals(SpeedFailureKind.PROTOCOL, failure.kind)
    }

    @Test fun locateParsesTargetsAndReportsNoCapacity() = runBlocking {
        val body = """{"results":[{"machine":"mlab1-prg01.mlab-oti.measurement-lab.org","location":{"city":"Prague","country":"CZ"},
            "urls":{"wss:///ndt/v7/download":"wss://ndt-mlab1-prg01.mlab-oti.measurement-lab.org/ndt/v7/download?access_token=A",
            "wss:///ndt/v7/upload":"wss://ndt-mlab1-prg01.mlab-oti.measurement-lab.org/ndt/v7/upload?access_token=A"}}]}"""
        LoopbackServer { request, _, output, _ ->
            assertEquals("application/json", request.header("accept"))
            LoopbackServer.respond(output, 200, body.toByteArray()); true
        }.use { server ->
            val targets = client(locate = "${server.base}v2/nearest/ndt/ndt7").locate()
            assertEquals("Prague", targets.single().city)
        }
        LoopbackServer { _, _, output, _ -> LoopbackServer.respond(output, 204); true }.use { server ->
            val failure = assertFailsWith<SpeedTestFailure> { client(locate = server.base).locate() }
            assertEquals(SpeedFailureKind.NO_CAPACITY, failure.kind)
        }
        LoopbackServer { _, _, output, _ -> LoopbackServer.respond(output, 200, """{"results":[]}""".toByteArray()); true }.use { server ->
            val failure = assertFailsWith<SpeedTestFailure> { client(locate = server.base).locate() }
            assertEquals(SpeedFailureKind.NO_SERVER, failure.kind)
        }
    }
}
