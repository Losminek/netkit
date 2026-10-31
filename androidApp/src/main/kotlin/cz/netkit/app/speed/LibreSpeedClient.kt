package cz.netkit.app.speed

import cz.netkit.shared.speed.LibreSpeedIpInfo
import cz.netkit.shared.speed.LibreSpeedProtocol
import cz.netkit.shared.speed.parseLibreSpeedIpInfo
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink

/** Read/write granularity of the transfer loops (also the counter update step). */
private const val IO_CHUNK = 64 * 1024
/** Shared random upload payload; bodies repeat it, so memory stays at this size. */
private const val PAYLOAD_BYTES = 1024 * 1024
/** Per-request limit of one latency probe. */
internal const val LIBRESPEED_PING_TIMEOUT_MS = 2_000L
/** Consecutive failures after which a stream gives up for the rest of the direction. */
private const val STREAM_MAX_ERRORS = 3
private val OCTET = "application/octet-stream".toMediaType()

/**
 * Own client of the LibreSpeed HTTP protocol (see [LibreSpeedProtocol]); no LibreSpeed code.
 *
 * [client] must already be bound to the Wi-Fi network ([boundTo]). Bodies are streamed through a
 * fixed 64 KiB buffer and discarded; nothing accumulates. Every blocking call is registered in a
 * [CallRegistry], so Stop/leaving the screen cancels the sockets immediately.
 *
 * @param allowLoopbackCleartext tests only: permits `http://127.0.0.1:…` (the app itself forbids
 *   cleartext in `network_security_config` and [cz.netkit.shared.speed.checkLibreSpeedUrl]).
 */
internal class LibreSpeedClient(
    client: OkHttpClient,
    private val baseUrl: String,
    private val userAgent: String,
    private val clock: () -> Long = ::monotonicMs,
    allowLoopbackCleartext: Boolean = false,
) {
    init {
        require(baseUrl.startsWith("https://") || (allowLoopbackCleartext && baseUrl.startsWith("http://127.0.0.1:"))) {
            "LibreSpeed requires https"
        }
        require(baseUrl.endsWith("/"))
    }

    private val transfer = client
    /** Own one-connection pool: probes reuse one kept-alive connection and never queue behind a transfer. */
    private val prober = client.newBuilder()
        .connectionPool(ConnectionPool(1, 30, TimeUnit.SECONDS))
        .callTimeout(LIBRESPEED_PING_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    private val payload = Random.nextBytes(PAYLOAD_BYTES)
    private val uploadSize = AtomicInteger(LibreSpeedProtocol.UPLOAD_MAX_BYTES)

    /** Closes the idle probe connection (the transfer pool belongs to the caller's client). */
    fun release() = prober.connectionPool.evictAll()

    /** Current upload body size (halved after failures before the first accepted upload). */
    val uploadBodyBytes: Int get() = uploadSize.get()

    private fun request(url: String) = Request.Builder().url(url)
        .header("User-Agent", userAgent)
        .header("Cache-Control", "no-cache")
        // Count the bytes that crossed the network, never a decompressed size.
        .header("Accept-Encoding", "identity")

    /**
     * One HTTP round trip (GET `empty.php`) on the kept-alive probe connection: time from sending the
     * request to the response headers. Includes server processing; it is not an ICMP ping.
     * Throws on any failure.
     */
    fun probe(registry: CallRegistry): Double {
        val call = prober.newCall(request(LibreSpeedProtocol.emptyUrl(baseUrl, Random.nextLong())).get().build())
        if (!registry.register(call)) throw IOException("cancelled")
        try {
            val started = System.nanoTime()
            call.execute().use { response ->
                val elapsed = (System.nanoTime() - started) / 1_000_000.0
                if (!response.isSuccessful) throw SpeedTestFailure(SpeedFailureKind.HTTP_STATUS, response.code)
                // LibreSpeed empty.php answers with an empty body; an SPA catch-all returns a page.
                if (readBounded(response.body, LibreSpeedProtocol.EMPTY_MAX_BYTES) == null) {
                    throw SpeedTestFailure(SpeedFailureKind.NOT_LIBRESPEED)
                }
                return elapsed
            }
        } finally {
            registry.unregister(call)
        }
    }

    /**
     * Unloaded latency: one warm-up probe (TCP + TLS setup, discarded; its failure fails the test
     * with a typed [SpeedTestFailure]), then [count] probes [gapMs] apart. A failed probe is `null`.
     */
    suspend fun idleLatency(count: Int, gapMs: Long, onSample: (Double?) -> Unit = {}): List<Double?> = withCalls { registry ->
        try {
            probe(registry)
        } catch (error: Exception) {
            throw classifySpeedFailure(error)
        }
        val out = ArrayList<Double?>(count)
        repeat(count) {
            if (registry.isClosed) return@withCalls out
            val sample = try { probe(registry) } catch (_: IOException) { null }
            out.add(sample)
            onSample(sample)
            Thread.sleep(gapMs)
        }
        out
    }

    /** Probes every [intervalMs] until cancelled; reports (elapsed since [startMs], RTT or `null`). */
    fun loadedProbeLoop(registry: CallRegistry, startMs: Long, intervalMs: Long, onSample: (Long, Double?) -> Unit) {
        while (!registry.isClosed) {
            val began = clock()
            val sample = try { probe(registry) } catch (_: IOException) { null }
            if (registry.isClosed) return
            onSample(began - startMs, sample)
            val wait = intervalMs - (clock() - began)
            if (wait > 0) try { Thread.sleep(wait) } catch (_: InterruptedException) { return }
        }
    }

    /** `getIP.php` (without `isp=true`): public IP for display only; `null` on any problem (it is optional). */
    suspend fun ipInfo(): LibreSpeedIpInfo? = try {
        withCalls { registry ->
            val call = prober.newCall(request(LibreSpeedProtocol.ipInfoUrl(baseUrl)).get().build())
            if (!registry.register(call)) return@withCalls null
            call.execute().use { response ->
                if (!response.isSuccessful) null
                else readBounded(response.body, LibreSpeedProtocol.IP_INFO_MAX_CHARS)?.let(::parseLibreSpeedIpInfo)
            }
        }
    } catch (_: IOException) {
        null
    }

    /**
     * Download for [durationMs] with [streams] parallel connections, each repeatedly fetching
     * `garbage.php?ckSize=25`. Bytes go to [meter]; [onLoaded] runs alongside on the probe
     * connection (loaded latency). Throws when no byte arrived or every stream gave up; otherwise
     * reports how many streams worked to the end (a stream gives up after 3 consecutive errors).
     */
    suspend fun download(streams: Int, durationMs: Long, meter: DirectionMeter, onLoaded: ((CallRegistry, Long) -> Unit)? = null) =
        runDirection(streams, durationMs, meter, onLoaded) { registry, stop, failure ->
            val buffer = ByteArray(IO_CHUNK)
            var errors = 0
            while (!stop()) {
                val call = transfer.newCall(request(LibreSpeedProtocol.garbageUrl(baseUrl, Random.nextLong())).get().build())
                if (!registry.register(call)) return@runDirection false
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) throw SpeedTestFailure(SpeedFailureKind.HTTP_STATUS, response.code)
                        if (!garbageContentType(response.header("Content-Type"))) throw SpeedTestFailure(SpeedFailureKind.NOT_LIBRESPEED)
                        val declared = response.body.contentLength()
                        if (declared in 0 until LibreSpeedProtocol.GARBAGE_MIN_BYTES) throw SpeedTestFailure(SpeedFailureKind.NOT_LIBRESPEED)
                        val input = response.body.byteStream()
                        var received = 0L
                        var complete = false
                        while (!stop()) {
                            val n = input.read(buffer)
                            if (n < 0) { complete = true; break }
                            received += n
                            meter.bytes.addAndGet(n.toLong())
                        }
                        // A complete answer far below the requested MiB is a web page, not garbage.php.
                        if (complete && received < LibreSpeedProtocol.GARBAGE_MIN_BYTES) throw SpeedTestFailure(SpeedFailureKind.NOT_LIBRESPEED)
                    }
                    errors = 0
                } catch (error: IOException) {
                    if (stop()) return@runDirection false
                    failure.compareAndSet(null, classifySpeedFailure(error))
                    if (++errors >= STREAM_MAX_ERRORS) return@runDirection true
                    Thread.sleep(100)
                } finally {
                    registry.unregister(call)
                }
            }
            false
        }

    /**
     * Upload for [durationMs] with [streams] parallel connections POSTing incompressible random
     * bodies to `empty.php`. Bytes are counted as they are written to the socket. Bodies start at
     * 8 MiB; until one upload is accepted, every failure (e.g. 413 or a reset from a body-size
     * limit) halves the size down to 256 KiB.
     */
    suspend fun upload(streams: Int, durationMs: Long, meter: DirectionMeter, onLoaded: ((CallRegistry, Long) -> Unit)? = null): DirectionStreams {
        val accepted = AtomicBoolean(false)
        return runDirection(streams, durationMs, meter, onLoaded) { registry, stop, failure ->
            var errors = 0
            while (!stop()) {
                val size = uploadSize.get()
                val body = PayloadBody(payload, size.toLong(), meter, stop)
                val call = transfer.newCall(request(LibreSpeedProtocol.emptyUrl(baseUrl, Random.nextLong())).post(body).build())
                if (!registry.register(call)) return@runDirection false
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) throw SpeedTestFailure(SpeedFailureKind.HTTP_STATUS, response.code)
                        if (readBounded(response.body, LibreSpeedProtocol.EMPTY_MAX_BYTES) == null) {
                            throw SpeedTestFailure(SpeedFailureKind.NOT_LIBRESPEED)
                        }
                    }
                    accepted.set(true)
                    errors = 0
                } catch (error: IOException) {
                    if (stop()) return@runDirection false
                    val typed = classifySpeedFailure(error)
                    if (typed.kind != SpeedFailureKind.NOT_LIBRESPEED && !accepted.get() && size > LibreSpeedProtocol.UPLOAD_MIN_BYTES) {
                        uploadSize.compareAndSet(size, size / 2)
                        continue
                    }
                    failure.compareAndSet(null, typed)
                    if (++errors >= STREAM_MAX_ERRORS) return@runDirection true
                    Thread.sleep(100)
                } finally {
                    registry.unregister(call)
                }
            }
            false
        }
    }

    private suspend fun runDirection(
        streams: Int,
        durationMs: Long,
        meter: DirectionMeter,
        onLoaded: ((CallRegistry, Long) -> Unit)?,
        worker: (CallRegistry, () -> Boolean, AtomicReference<SpeedTestFailure?>) -> Boolean,
    ): DirectionStreams {
        val failure = AtomicReference<SpeedTestFailure?>(null)
        val gaveUp = AtomicInteger(0)
        coroutineScope {
            val registry = CallRegistry()
            val start = clock()
            meter.start(start)
            val deadline = start + durationMs
            val stop = { registry.isClosed || clock() >= deadline }
            try {
                val workers = List(streams) { launch(Dispatchers.IO) { if (worker(registry, stop, failure)) gaveUp.incrementAndGet() } }
                val probe = onLoaded?.let { launch(Dispatchers.IO) { it(registry, start) } }
                withTimeoutOrNull(durationMs) { workers.joinAll() }
                meter.finish(minOf(clock(), deadline))
                registry.closeAll()
                probe?.join()
            } finally {
                meter.finish(clock())
                registry.closeAll()
            }
        }
        // No data at all, or every stream gave up (e.g. a non-LibreSpeed server answering with tiny pages).
        if (meter.bytes.get() == 0L || gaveUp.get() >= streams) throw failure.get() ?: SpeedTestFailure(SpeedFailureKind.NO_DATA)
        val first = failure.get()
        return DirectionStreams(planned = streams, working = streams - gaveUp.get(), firstFailureKind = first?.kind, firstFailureCode = first?.httpCode)
    }
}

/**
 * Streams of one direction. [working] < [planned] means some connections failed (e.g. a proxy with a
 * per-client connection limit answering 503) and the result is only partially multi-stream.
 */
data class DirectionStreams(val planned: Int, val working: Int, val firstFailureKind: SpeedFailureKind? = null, val firstFailureCode: Int? = null)

/** garbage.php is binary; HTML, text or JSON means another web app answered (absent type is tolerated). */
internal fun garbageContentType(header: String?): Boolean {
    val type = header?.substringBefore(';')?.trim()?.lowercase() ?: return true
    return !(type.startsWith("text/") || type.contains("html") || type.contains("json") || type.contains("xml"))
}

/** Streams [length] bytes by repeating [payload]; counts each 64 KiB write and aborts once [stop] is true. */
private class PayloadBody(
    private val payload: ByteArray,
    private val length: Long,
    private val meter: DirectionMeter,
    private val stop: () -> Boolean,
) : RequestBody() {
    override fun contentType() = OCTET
    override fun contentLength() = length
    override fun isOneShot() = true

    override fun writeTo(sink: BufferedSink) {
        var written = 0L
        while (written < length) {
            if (stop()) throw IOException("upload stopped")
            val offset = (written % payload.size).toInt()
            val n = minOf(length - written, IO_CHUNK.toLong(), (payload.size - offset).toLong()).toInt()
            sink.write(payload, offset, n)
            meter.bytes.addAndGet(n.toLong())
            written += n
        }
    }
}
