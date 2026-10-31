package cz.netkit.app.speed

import cz.netkit.shared.speed.Ndt7Measurement
import cz.netkit.shared.speed.Ndt7MessageSizer
import cz.netkit.shared.speed.Ndt7Protocol
import cz.netkit.shared.speed.Ndt7Target
import cz.netkit.shared.speed.parseNdt7Locate
import cz.netkit.shared.speed.parseNdt7Measurement
import cz.netkit.shared.speed.withNdt7ClientMetadata
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** Servers tried per direction when the handshake fails. */
internal const val NDT7_MAX_TARGETS = 3
private val RETRYABLE = setOf(SpeedFailureKind.DNS, SpeedFailureKind.CONNECT, SpeedFailureKind.TLS,
    SpeedFailureKind.TIMEOUT, SpeedFailureKind.HTTP_STATUS)

/** How an NDT7 direction ended. ABRUPT (EOF/RST after data) is tolerated by the spec and kept. */
internal enum class Ndt7End { SERVER_CLOSED, ABRUPT, HARD_CAP }

internal data class Ndt7Outcome(val end: Ndt7End, val durationMs: Long)

/** Time limits of one direction; defaults are the spec values, tests shorten them. */
internal data class Ndt7Timing(
    val handshakeMs: Long = Ndt7Protocol.HANDSHAKE_TIMEOUT_MS,
    val uploadSendMs: Long = Ndt7Protocol.UPLOAD_SEND_MS,
    val softCloseMs: Long = Ndt7Protocol.SOFT_CLOSE_MS,
    val hardCapMs: Long = Ndt7Protocol.HARD_CAP_MS,
)

/** Receives server measurement messages (OkHttp reader thread). [elapsedMs] since the handshake. */
internal fun interface Ndt7MeasurementSink {
    fun onMeasurement(elapsedMs: Long, measurement: Ndt7Measurement)
}

/**
 * Own NDT7 client (spec v0.11.0) on OkHttp WebSockets. [client] must be bound to the Wi-Fi network.
 * Access tokens in the target URLs are never logged ([Ndt7Target.toString] hides them).
 */
internal class Ndt7Client(
    client: OkHttpClient,
    private val userAgent: String,
    private val clientVersion: String,
    private val clock: () -> Long = ::monotonicMs,
    private val locateUrl: String = Ndt7Protocol.LOCATE_URL,
    private val timing: Ndt7Timing = Ndt7Timing(),
) {
    /** The locate API may redirect (spec: MUST handle 3xx), but never from https to http. */
    private val http = client.newBuilder()
        .followRedirects(true)
        .followSslRedirects(false)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
    /** The call timeout covers TCP + TLS + WebSocket handshake only (OkHttp exits it on upgrade). */
    private val sockets = client.newBuilder()
        .callTimeout(timing.handshakeMs, TimeUnit.MILLISECONDS)
        .readTimeout(timing.hardCapMs, TimeUnit.MILLISECONDS)
        .build()

    /** Nearest servers from `locate.measurementlab.net` (only secure M-Lab URLs survive parsing). */
    suspend fun locate(): List<Ndt7Target> = try {
        withCalls { registry ->
            val call = http.newCall(Request.Builder().url(locateUrl)
                .header("User-Agent", userAgent).header("Accept", "application/json").get().build())
            if (!registry.register(call)) throw SpeedTestFailure(SpeedFailureKind.CONNECT)
            call.execute().use { response ->
                when {
                    response.code == 204 -> throw SpeedTestFailure(SpeedFailureKind.NO_CAPACITY)
                    !response.isSuccessful -> throw SpeedTestFailure(SpeedFailureKind.HTTP_STATUS, response.code)
                }
                val text = readBounded(response.body, Ndt7Protocol.LOCATE_MAX_CHARS)
                    ?: throw SpeedTestFailure(SpeedFailureKind.PROTOCOL)
                parseNdt7Locate(text).ifEmpty { throw SpeedTestFailure(SpeedFailureKind.NO_SERVER) }
            }
        }
    } catch (error: java.io.IOException) {
        throw classifySpeedFailure(error)
    }

    /**
     * Runs [block] on the located servers in order until one completes the handshake: the next
     * server is tried only when the failure happened before any data ([meter] not started) and is a
     * transport/handshake failure. At most [maxTries] servers, and a further try starts only within
     * [Ndt7Timing.hardCapMs] of the first one.
     */
    suspend fun <T> firstReachable(
        targets: List<Ndt7Target>,
        meter: DirectionMeter,
        maxTries: Int = NDT7_MAX_TARGETS,
        block: suspend (Ndt7Target) -> T,
    ): Pair<Ndt7Target, T> {
        val began = clock()
        var last: SpeedTestFailure? = null
        for ((index, target) in targets.take(maxTries).withIndex()) {
            if (index > 0 && clock() - began >= timing.hardCapMs) break
            try {
                return target to block(target)
            } catch (failure: SpeedTestFailure) {
                if (meter.startedAtMs >= 0 || failure.kind !in RETRYABLE) throw failure
                last = failure
            }
        }
        throw last ?: SpeedTestFailure(SpeedFailureKind.NO_SERVER)
    }

    private fun upgrade(url: String) = Request.Builder()
        .url(withNdt7ClientMetadata(url, "netkit-android", clientVersion))
        .header("User-Agent", userAgent)
        .header("Sec-WebSocket-Protocol", Ndt7Protocol.SUBPROTOCOL)
        .build()

    /**
     * Download: counts received binary bytes into [meter] until the server closes (≤ 10 s). The
     * client closes gracefully after 13 s and cancels the socket after 15 s.
     */
    suspend fun download(url: String, meter: DirectionMeter, sink: Ndt7MeasurementSink): Ndt7Outcome =
        run(url, meter, sink, upload = false)

    /**
     * Upload: sends random binary messages (size scaled per spec appendix) for 10 s, then closes.
     * Client-side bytes are those already handed to the socket (queued minus `queueSize`).
     */
    suspend fun upload(url: String, meter: DirectionMeter, sink: Ndt7MeasurementSink): Ndt7Outcome =
        run(url, meter, sink, upload = true)

    private suspend fun run(url: String, meter: DirectionMeter, sink: Ndt7MeasurementSink, upload: Boolean): Ndt7Outcome = coroutineScope {
        val opened = CompletableDeferred<Long>()
        val finished = CompletableDeferred<Ndt7End>()
        val protocolViolation = AtomicBoolean(false)
        val openedAtMs = AtomicLong(-1L)
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val now = clock()
                if (!upload) meter.start(now)
                openedAtMs.set(now)
                opened.complete(now)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val start = openedAtMs.get().takeIf { it >= 0 } ?: return
                parseNdt7Measurement(text)?.let { sink.onMeasurement(clock() - start, it) }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (upload) {
                    // Spec: the server MUST NOT send binary messages during upload.
                    protocolViolation.set(true)
                    webSocket.cancel()
                    finished.complete(Ndt7End.ABRUPT)
                    return
                }
                meter.bytes.addAndGet(bytes.size.toLong())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                meter.finish(clock())
                webSocket.close(1000, null)
                finished.complete(Ndt7End.SERVER_CLOSED)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                meter.finish(clock())
                finished.complete(Ndt7End.SERVER_CLOSED)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                meter.finish(clock())
                if (!opened.isCompleted) {
                    val failure = response?.code?.takeIf { it != 101 }
                        ?.let { SpeedTestFailure(SpeedFailureKind.HTTP_STATUS, it, t) } ?: classifySpeedFailure(t)
                    opened.completeExceptionally(failure)
                }
                finished.complete(Ndt7End.ABRUPT)
            }
        }
        // A URL OkHttp cannot parse (should not pass validNdt7Url) is a protocol error, not a crash.
        val request = try { upgrade(url) } catch (error: IllegalArgumentException) {
            throw SpeedTestFailure(SpeedFailureKind.PROTOCOL, cause = error)
        }
        val socket = sockets.newWebSocket(request, listener)
        try {
            val openedAt = withTimeoutOrNull(timing.handshakeMs) { opened.await() }
                ?: throw SpeedTestFailure(SpeedFailureKind.TIMEOUT)
            val sender = if (upload) launch(Dispatchers.IO) { sendLoop(socket, openedAt, meter, finished) } else null
            val end = withTimeoutOrNull(timing.softCloseMs) { finished.await() } ?: run {
                meter.finish(clock())
                socket.close(1000, null)
                withTimeoutOrNull(timing.hardCapMs - timing.softCloseMs) { finished.await() }
                    ?: Ndt7End.HARD_CAP
            }
            sender?.cancel()
            if (protocolViolation.get()) throw SpeedTestFailure(SpeedFailureKind.PROTOCOL)
            if (meter.bytes.get() == 0L) throw SpeedTestFailure(SpeedFailureKind.NO_DATA)
            Ndt7Outcome(end, clock() - openedAt)
        } finally {
            meter.finish(clock())
            socket.cancel()
        }
    }

    private suspend fun sendLoop(socket: WebSocket, openedAt: Long, meter: DirectionMeter, finished: CompletableDeferred<Ndt7End>) {
        val payloads = HashMap<Int, ByteString>()
        fun message(size: Int) = payloads.getOrPut(size) { Random.nextBytes(size).toByteString() }
        val sizer = Ndt7MessageSizer()
        meter.start(openedAt)
        try {
            while (currentCoroutineContext().isActive && !finished.isCompleted &&
                clock() - openedAt < timing.uploadSendMs) {
                val size = sizer.size
                // Keep the OkHttp queue (hard limit 16 MiB) short: enough to keep the socket busy.
                if (socket.queueSize() < 2L * size + 256 * 1024) {
                    if (!socket.send(message(size))) break
                    sizer.onQueued(size)
                } else {
                    delay(1)
                }
                meter.bytes.set((sizer.queuedBytes - socket.queueSize()).coerceAtLeast(0))
            }
        } finally {
            meter.bytes.set((sizer.queuedBytes - socket.queueSize()).coerceAtLeast(0))
            meter.finish(clock())
            socket.close(1000, null)
        }
    }
}
