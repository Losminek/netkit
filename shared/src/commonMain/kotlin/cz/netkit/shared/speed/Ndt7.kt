package cz.netkit.shared.speed

import cz.netkit.shared.json.JSON_MAX_DEPTH
import cz.netkit.shared.json.jsonNestingDepth
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * NDT7 (M-Lab), implemented from the public specification
 * (github.com/m-lab/ndt-server, `spec/ndt7-protocol.md`, v0.11.0): one WebSocket over TLS per
 * direction, subprotocol [SUBPROTOCOL], binary messages generate load, text messages carry JSON
 * measurements. The server ends a direction after ≤ 10 s; the client may close after 13 s.
 */
object Ndt7Protocol {
    const val SUBPROTOCOL = "net.measurementlab.ndt.v7"
    const val LOCATE_URL = "https://locate.measurementlab.net/v2/nearest/ndt/ndt7"
    const val DOWNLOAD_PATH = "/ndt/v7/download"
    const val UPLOAD_PATH = "/ndt/v7/upload"
    /** Client upload sending time (the spec's expected duration). */
    const val UPLOAD_SEND_MS = 10_000L
    /** The client closes the WebSocket gracefully after this time (spec: "at least thirteen seconds"). */
    const val SOFT_CLOSE_MS = 13_000L
    /** Hard cap per direction after the handshake: the connection is cancelled. */
    const val HARD_CAP_MS = 15_000L
    /** TCP + TLS + WebSocket handshake timeout (spec: 5–10 s). */
    const val HANDSHAKE_TIMEOUT_MS = 10_000L
    /** Largest text measurement message that is parsed; longer ones are ignored. */
    const val MEASUREMENT_MAX_CHARS = 64 * 1024
    /** Largest locate answer that is parsed. */
    const val LOCATE_MAX_CHARS = 64 * 1024
    /** Initial binary upload message size (spec: 1 << 13). */
    const val MESSAGE_INITIAL_BYTES = 1 shl 13
    /** Our cap of the scaled upload message (spec allows up to 1 << 24; 1 MiB bounds memory). */
    const val MESSAGE_MAX_BYTES = 1 shl 20
    /** Only M-Lab hosts receive the access token. */
    const val HOST_SUFFIX = ".measurement-lab.org"
}

/**
 * Upload message sizing from the spec appendix: start at [Ndt7Protocol.MESSAGE_INITIAL_BYTES] and
 * double while the size is smaller than 1/16 of the bytes queued so far, up to [maxBytes].
 */
class Ndt7MessageSizer(private val maxBytes: Int = Ndt7Protocol.MESSAGE_MAX_BYTES) {
    var size: Int = Ndt7Protocol.MESSAGE_INITIAL_BYTES
        private set
    var queuedBytes: Long = 0
        private set

    fun onQueued(bytes: Int) {
        queuedBytes += bytes
        if (size < maxBytes && size < queuedBytes / 16) size = minOf(size * 2, maxBytes)
    }
}

/** A located NDT7 server; URLs carry M-Lab access tokens and must not be logged or stored. */
data class Ndt7Target(
    val machine: String,
    val city: String?,
    val country: String?,
    val downloadUrl: String,
    val uploadUrl: String,
) {
    /** Host of the download URL, safe to show and store (no token). */
    val host: String get() = ndt7Host(downloadUrl).orEmpty()
    override fun toString(): String = "Ndt7Target(machine=$machine, city=$city, country=$country)"
}

@Serializable private data class LocateJson(val results: List<LocateResultJson> = emptyList())
@Serializable private data class LocateResultJson(
    val machine: String? = null,
    val location: LocateLocationJson? = null,
    val urls: Map<String, String> = emptyMap(),
)
@Serializable private data class LocateLocationJson(val city: String? = null, val country: String? = null)

private val json = Json { ignoreUnknownKeys = true; isLenient = false }

/**
 * Parses a `locate/v2/nearest/ndt/ndt7` answer. Keeps only results with both secure (`wss://`)
 * URLs on an M-Lab host with the expected paths; plain `ws://` URLs are never used.
 */
fun parseNdt7Locate(text: String): List<Ndt7Target> {
    if (text.length > Ndt7Protocol.LOCATE_MAX_CHARS) return emptyList()
    if (jsonNestingDepth(text) > JSON_MAX_DEPTH) return emptyList()
    val parsed = try {
        json.decodeFromString(LocateJson.serializer(), text)
    } catch (_: SerializationException) {
        return emptyList()
    } catch (_: IllegalArgumentException) {
        return emptyList()
    }
    return parsed.results.mapNotNull { result ->
        val down = result.urls["wss:///ndt/v7/download"]?.takeIf { validNdt7Url(it, Ndt7Protocol.DOWNLOAD_PATH) }
        val up = result.urls["wss:///ndt/v7/upload"]?.takeIf { validNdt7Url(it, Ndt7Protocol.UPLOAD_PATH) }
        if (down == null || up == null) return@mapNotNull null
        val machine = result.machine?.takeIf { it.length in 1..253 && it.all(::safeNameChar) } ?: ndt7Host(down) ?: return@mapNotNull null
        Ndt7Target(
            machine = machine,
            city = result.location?.city?.let(::cleanLabel),
            country = result.location?.country?.let(::cleanLabel),
            downloadUrl = down,
            uploadUrl = up,
        )
    }
}

private fun safeNameChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '.'
private fun cleanLabel(text: String): String? = text.filter { it.code >= 0x20 && it.code != 0x7f }.trim().take(64).ifEmpty { null }

/** Authority (`host[:port]`) of a `wss://` URL and the index where it ends in [url]; `null` when not `wss://`. */
private fun ndt7Authority(url: String): Pair<String, Int>? {
    if (!url.startsWith("wss://")) return null
    val rest = url.substring(6)
    // The authority ends at the first '/', '?' or '#', so a '/' inside the query is never taken as the path.
    val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
    return rest.substring(0, end) to (6 + end)
}

/**
 * Host of a `wss://host[:port]/…` URL; `null` for anything else. The host must be a DNS name of
 * non-empty labels (no `a..b`, no leading/trailing `-`, no trailing dot), so OkHttp never rejects it.
 */
fun ndt7Host(url: String): String? {
    val (authority, _) = ndt7Authority(url) ?: return null
    if ('@' in authority || authority.isEmpty()) return null
    val host = authority.substringBefore(':').lowercase()
    val port = authority.substringAfter(':', "")
    if (':' in authority && (port.isEmpty() || port.length > 5 || !port.all { it in '0'..'9' } || port.toInt() !in 1..65535)) return null
    if (host.isEmpty() || host.length > 253 || !host.all(::safeNameChar)) return null
    val labels = host.split('.')
    if (labels.any { it.isEmpty() || it.length > 63 || it.startsWith('-') || it.endsWith('-') }) return null
    return host
}

/** `wss://` on an M-Lab host whose path (directly after the authority, before `?`/`#`) is exactly [path]. */
fun validNdt7Url(url: String, path: String): Boolean {
    if (url.length > 4096 || url.any { it.code <= 0x20 || it.code >= 0x7f }) return false
    val host = ndt7Host(url) ?: return false
    if (!host.endsWith(Ndt7Protocol.HOST_SUFFIX)) return false
    val (_, end) = ndt7Authority(url) ?: return false
    val tail = url.substring(end)
    if (!tail.startsWith("/")) return false
    val stop = tail.indexOfFirst { it == '?' || it == '#' }.let { if (it < 0) tail.length else it }
    return tail.substring(0, stop) == path
}

/** Appends client metadata (spec: optional query parameters, no duplicates, no empty values). */
fun withNdt7ClientMetadata(url: String, clientName: String, clientVersion: String): String {
    fun enc(s: String) = s.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "-._~" }
    val extra = "client_name=${enc(clientName)}&client_version=${enc(clientVersion)}"
    if (url.contains("client_name=")) return url
    return if ('?' in url) "$url&$extra" else "$url?$extra"
}

// --- Measurement messages ---------------------------------------------------------------------

@Serializable
data class Ndt7AppInfo(
    @SerialName("ElapsedTime") val elapsedTimeUs: Double? = null,
    @SerialName("NumBytes") val numBytes: Double? = null,
)

@Serializable
data class Ndt7TcpInfo(
    @SerialName("BusyTime") val busyTimeUs: Double? = null,
    @SerialName("BytesAcked") val bytesAcked: Double? = null,
    @SerialName("BytesReceived") val bytesReceived: Double? = null,
    @SerialName("BytesSent") val bytesSent: Double? = null,
    @SerialName("BytesRetrans") val bytesRetrans: Double? = null,
    @SerialName("ElapsedTime") val elapsedTimeUs: Double? = null,
    @SerialName("MinRTT") val minRttUs: Double? = null,
    @SerialName("RTT") val rttUs: Double? = null,
    @SerialName("RTTVar") val rttVarUs: Double? = null,
    @SerialName("RWndLimited") val rwndLimitedUs: Double? = null,
    @SerialName("SndBufLimited") val sndBufLimitedUs: Double? = null,
)

/** BBR state of the server socket (sent by M-Lab servers, not part of the v0.11 spec text). */
@Serializable
data class Ndt7BbrInfo(
    @SerialName("BW") val bandwidthBytesPerSecond: Double? = null,
    @SerialName("MinRTT") val minRttUs: Double? = null,
    @SerialName("ElapsedTime") val elapsedTimeUs: Double? = null,
)

/**
 * One measurement message. `ConnectionInfo` (client IP and port) is deliberately not decoded:
 * the app neither shows nor stores it.
 */
@Serializable
data class Ndt7Measurement(
    @SerialName("AppInfo") val appInfo: Ndt7AppInfo? = null,
    @SerialName("Origin") val origin: String? = null,
    @SerialName("Test") val test: String? = null,
    @SerialName("TCPInfo") val tcpInfo: Ndt7TcpInfo? = null,
    @SerialName("BBRInfo") val bbrInfo: Ndt7BbrInfo? = null,
)

/** Parses a text message; `null` for oversized, malformed or non-object JSON. */
fun parseNdt7Measurement(text: String): Ndt7Measurement? {
    if (text.length > Ndt7Protocol.MEASUREMENT_MAX_CHARS) return null
    if (jsonNestingDepth(text) > JSON_MAX_DEPTH) return null
    return try {
        json.decodeFromString(Ndt7Measurement.serializer(), text)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * What the server reported for one direction (its newest message is the summary, per the spec).
 *
 * @property appMbps application-level goodput measured by the server (`AppInfo`).
 * @property tcpMbps kernel-level rate: `BytesAcked` (download, server sends) or `BytesReceived`
 *   (upload, server receives) over `TCPInfo.ElapsedTime`; includes TLS/WebSocket overhead.
 * @property retransPercent `BytesRetrans / BytesSent` — meaningful for download only.
 * @property minRttMs / [rttMs] kernel RTT of the server socket; with the server sending (download)
 *   [rttMs] under load is the loaded latency the TCP sender sees.
 * @property bbrMbps BBR bottleneck bandwidth estimate.
 */
data class Ndt7ServerView(
    val appMbps: Double?,
    val tcpMbps: Double?,
    val retransPercent: Double?,
    val minRttMs: Double?,
    val rttMs: Double?,
    val bbrMbps: Double?,
)

fun ndt7ServerView(message: Ndt7Measurement, download: Boolean): Ndt7ServerView {
    fun valid(v: Double?) = v?.takeIf { it.isFinite() && it >= 0.0 }
    val app = message.appInfo
    val appMbps = rateBitsPerMicro(valid(app?.numBytes), valid(app?.elapsedTimeUs))
    val tcp = message.tcpInfo
    val tcpBytes = if (download) valid(tcp?.bytesAcked) else valid(tcp?.bytesReceived)
    val tcpMbps = rateBitsPerMicro(tcpBytes, valid(tcp?.elapsedTimeUs))
    val sent = valid(tcp?.bytesSent)
    val retrans = valid(tcp?.bytesRetrans)
    val retransPercent = if (download && sent != null && sent > 0.0 && retrans != null) retrans / sent * 100.0 else null
    val bbr = valid(message.bbrInfo?.bandwidthBytesPerSecond)?.let { it * 8.0 / 1_000_000.0 }
    return Ndt7ServerView(
        appMbps = appMbps,
        tcpMbps = tcpMbps,
        retransPercent = retransPercent,
        minRttMs = valid(tcp?.minRttUs)?.div(1_000.0),
        rttMs = valid(tcp?.rttUs)?.div(1_000.0),
        bbrMbps = bbr,
    )
}

/** bytes / µs × 8 = Mbit/s. */
private fun rateBitsPerMicro(bytes: Double?, micros: Double?): Double? =
    if (bytes == null || micros == null || micros <= 0.0) null else bytes * 8.0 / micros
