package cz.netkit.app.speed

import android.net.Network
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody
import cz.netkit.shared.speed.SPEED_DOWNLOAD_STREAMS

/** Monotonic milliseconds (JVM-testable; same clock for every timestamp of one run). */
internal fun monotonicMs(): Long = System.nanoTime() / 1_000_000L

/** Typed failure of a speed-test step; the UI shows a localized text, never the exception message. */
enum class SpeedFailureKind {
    NO_NETWORK, NETWORK_LOST, DNS, CONNECT, TLS, TIMEOUT, HTTP_STATUS, PROTOCOL,
    /** The server answers, but not like a LibreSpeed backend (HTML/text, tiny garbage, non-empty empty.php). */
    NOT_LIBRESPEED,
    NO_CAPACITY, NO_SERVER, NO_DATA,
}

internal class SpeedTestFailure(val kind: SpeedFailureKind, val httpCode: Int? = null, cause: Throwable? = null) :
    IOException(kind.name + (httpCode?.let { " $it" } ?: ""), cause)

/** Maps transport exceptions to [SpeedFailureKind]; exception texts are not shown to the user. */
internal fun classifySpeedFailure(error: Throwable): SpeedTestFailure = when (error) {
    is SpeedTestFailure -> error
    is UnknownHostException -> SpeedTestFailure(SpeedFailureKind.DNS, cause = error)
    is SSLException -> SpeedTestFailure(SpeedFailureKind.TLS, cause = error)
    is SocketTimeoutException -> SpeedTestFailure(SpeedFailureKind.TIMEOUT, cause = error)
    is InterruptedIOException -> SpeedTestFailure(SpeedFailureKind.TIMEOUT, cause = error)
    is ConnectException, is NoRouteToHostException -> SpeedTestFailure(SpeedFailureKind.CONNECT, cause = error)
    else -> SpeedTestFailure(SpeedFailureKind.CONNECT, cause = error)
}

/** DNS through the Wi-Fi [Network] only (no fallback to the default network / mobile data). */
internal class NetworkDns(private val network: Network) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = try {
            network.getAllByName(hostname)
        } catch (error: UnknownHostException) {
            throw error
        } catch (error: Exception) {
            throw UnknownHostException(hostname).apply { initCause(error) }
        }
        if (addresses.isNullOrEmpty()) throw UnknownHostException(hostname)
        return addresses.toList()
    }
}

/**
 * Base client for every speed-test request: no cache, no automatic retries or redirects, and
 * HTTP/1.1 only, so N parallel streams are N TCP connections (HTTP/2 would multiplex them onto
 * one connection and turn a multi-stream test into a single-stream one).
 *
 * `Proxy.NO_PROXY`: the default `ProxySelector` reflects the proxy of the *default* network, not of
 * the bound Wi-Fi, and a proxy would also measure the proxy instead of the path. A Wi-Fi that only
 * works through an HTTP proxy therefore fails the test (documented in speedtest.md §4).
 */
internal fun speedHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .proxy(Proxy.NO_PROXY)
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.SECONDS)
    .writeTimeout(5, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false)
    .followRedirects(false)
    .followSslRedirects(false)
    .protocols(listOf(Protocol.HTTP_1_1))
    .connectionPool(ConnectionPool(SPEED_DOWNLOAD_STREAMS + 2, 30, TimeUnit.SECONDS))
    // Upload payload is random; compressing it would only cost CPU.
    .minWebSocketMessageToCompress(Long.MAX_VALUE)
    .build()

/** Binds sockets and DNS of [this] client to the Wi-Fi [network]. */
internal fun OkHttpClient.boundTo(network: Network): OkHttpClient =
    newBuilder().socketFactory(network.socketFactory).dns(NetworkDns(network)).build()

/**
 * In-flight calls of one step. [closeAll] cancels them and refuses new ones; it is called from a
 * non-blocked coroutine, which is what unblocks worker threads stuck in socket reads/writes.
 */
internal class CallRegistry {
    private val lock = Any()
    private val calls = HashSet<Call>()
    private var closed = false

    val isClosed: Boolean get() = synchronized(lock) { closed }

    fun register(call: Call): Boolean {
        val accepted = synchronized(lock) { if (closed) false else { calls.add(call); true } }
        if (!accepted) call.cancel()
        return accepted
    }

    fun unregister(call: Call) {
        synchronized(lock) { calls.remove(call) }
    }

    fun closeAll() {
        val pending = synchronized(lock) {
            closed = true
            calls.toList().also { calls.clear() }
        }
        pending.forEach { it.cancel() }
    }
}

/**
 * Runs blocking [block] on IO. Cancelling the caller (Stop, leaving the screen) closes the registry,
 * which cancels the calls and makes the blocked thread return promptly.
 */
internal suspend fun <T> withCalls(block: CoroutineScope.(CallRegistry) -> T): T = coroutineScope {
    val registry = CallRegistry()
    val work = async(Dispatchers.IO) { block(registry) }
    try {
        work.await()
    } finally {
        registry.closeAll()
    }
}

/** Reads at most [maxChars] bytes of a small UTF-8 body; `null` when it is longer. */
internal fun readBounded(body: ResponseBody, maxChars: Int): String? {
    val source = body.source()
    if (source.request(maxChars.toLong() + 1)) return null
    return source.buffer.readUtf8()
}

/**
 * Byte counter of one running direction. Workers add bytes from any thread; [finish] freezes the
 * client-side measurement (e.g. upload stopped sending) so a draining tail is not sampled as zero rate.
 */
internal class DirectionMeter {
    val bytes = AtomicLong()
    @Volatile var startedAtMs: Long = -1L
        private set
    @Volatile var finishedAtMs: Long = -1L
        private set

    fun start(nowMs: Long) { if (startedAtMs < 0) startedAtMs = nowMs }
    fun finish(nowMs: Long) { if (startedAtMs >= 0 && finishedAtMs < 0) finishedAtMs = nowMs }
}
