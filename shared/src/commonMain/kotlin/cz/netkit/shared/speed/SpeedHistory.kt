package cz.netkit.shared.speed

import cz.netkit.shared.net.NetKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
enum class SpeedEngine { LIBRESPEED, NDT7 }

/**
 * One stored result. No IP address, ISP string, access token or BSSID is kept; the SSID is
 * stored because the result is meaningless without knowing which network was measured.
 *
 * @property timestampMs wall-clock time of the start (epoch ms).
 * @property partial incomplete run: a direction failed, the Wi-Fi was lost, or some parallel streams
 *   gave up ([downloadStreams]/[uploadStreams] below the planned count). Runs stopped by the user are
 *   not stored at all.
 * @property downloadStreams / [uploadStreams] streams that worked to the end, out of [plannedDownloadStreams] /
 *   [plannedUploadStreams] (LibreSpeed; NDT7 always 1/1); `null` in records written before this field.
 * @property networkKind network the test ran on; `null` in records written before M4, which were
 *   always Wi-Fi (see [measuredKind]). The schema stays 1: an older app ignores the field.
 */
@Serializable
data class SpeedRecord(
    val timestampMs: Long,
    val engine: SpeedEngine,
    val serverName: String,
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
    val idleLatencyMs: Double? = null,
    val idleJitterMs: Double? = null,
    val loadedDownloadMs: Double? = null,
    val loadedUploadMs: Double? = null,
    val serverDownloadMbps: Double? = null,
    val serverUploadMbps: Double? = null,
    val retransPercent: Double? = null,
    val ssid: String? = null,
    val frequencyMhz: Int? = null,
    val rssiDbm: Int? = null,
    val rxLinkMbps: Int? = null,
    val txLinkMbps: Int? = null,
    val partial: Boolean = false,
    val downloadStreams: Int? = null,
    val uploadStreams: Int? = null,
    val plannedDownloadStreams: Int? = null,
    val plannedUploadStreams: Int? = null,
    val networkKind: NetKind? = null,
) {
    /** Records without [networkKind] come from versions that tested over Wi-Fi only. */
    val measuredKind: NetKind get() = networkKind ?: NetKind.WIFI
}

/**
 * The speed-test file in app-private, non-backed-up storage.
 *
 * @property ndt7ConsentMs when the user acknowledged that M-Lab publishes NDT7 results with the
 *   client IP; `null` = never, the notice is shown before the first NDT7 run.
 * @property customUrl last valid user-entered LibreSpeed base URL.
 * @property records newest first, at most [SPEED_HISTORY_MAX].
 */
@Serializable
data class SpeedStoreData(
    val schema: Int = SPEED_STORE_SCHEMA,
    val ndt7ConsentMs: Long? = null,
    val customUrl: String? = null,
    val records: List<SpeedRecord> = emptyList(),
)

const val SPEED_STORE_SCHEMA = 1
const val SPEED_STORE_MAX_BYTES = 256 * 1024
private const val TEXT_MAX = 128

class SpeedStoreCorrupt(message: String) : Exception(message)

/** Newest first, capped at [max]. */
fun SpeedStoreData.withRecord(record: SpeedRecord, max: Int = SPEED_HISTORY_MAX): SpeedStoreData =
    copy(records = (listOf(record) + records).take(max))

object SpeedStoreCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(data: SpeedStoreData): String {
        val text = json.encodeToString(SpeedStoreData.serializer(), sanitize(data))
        require(text.length <= SPEED_STORE_MAX_BYTES) { "speed store too large" }
        return text
    }

    /** Strict decode: unknown future schema, oversize or invalid values are [SpeedStoreCorrupt]. */
    fun decode(text: String): SpeedStoreData {
        if (text.length > SPEED_STORE_MAX_BYTES) throw SpeedStoreCorrupt("size")
        try {
            val schema = json.parseToJsonElement(text).jsonObject["schema"]?.jsonPrimitive?.intOrNull
            if (schema != SPEED_STORE_SCHEMA) throw SpeedStoreCorrupt("schema")
            val data = json.decodeFromString(SpeedStoreData.serializer(), text)
            if (!data.records.all(::validRecord)) throw SpeedStoreCorrupt("values")
            val custom = data.customUrl?.let { (checkLibreSpeedUrl(it) as? LibreSpeedUrlCheck.Valid)?.server?.baseUrl }
            return data.copy(records = data.records.take(SPEED_HISTORY_MAX), customUrl = custom)
        } catch (_: SerializationException) {
            throw SpeedStoreCorrupt("json")
        } catch (_: IllegalArgumentException) {
            throw SpeedStoreCorrupt("json")
        }
    }

    private fun sanitize(data: SpeedStoreData) = data.copy(
        schema = SPEED_STORE_SCHEMA,
        records = data.records.filter(::validRecord).take(SPEED_HISTORY_MAX),
    )
}

private fun validRecord(r: SpeedRecord): Boolean {
    fun okRate(v: Double?) = v == null || (v.isFinite() && v in 0.0..1_000_000.0)
    fun okMs(v: Double?) = v == null || (v.isFinite() && v in 0.0..600_000.0)
    return r.timestampMs > 0 && r.serverName.length in 1..TEXT_MAX && (r.ssid?.length ?: 0) <= TEXT_MAX &&
        okRate(r.downloadMbps) && okRate(r.uploadMbps) && okRate(r.serverDownloadMbps) && okRate(r.serverUploadMbps) &&
        okMs(r.idleLatencyMs) && okMs(r.idleJitterMs) && okMs(r.loadedDownloadMs) && okMs(r.loadedUploadMs) &&
        (r.retransPercent == null || (r.retransPercent.isFinite() && r.retransPercent in 0.0..100.0)) &&
        (r.rssiDbm == null || r.rssiDbm in -127..0) && (r.frequencyMhz == null || r.frequencyMhz in 1..100_000) &&
        (r.rxLinkMbps == null || r.rxLinkMbps in 0..100_000) && (r.txLinkMbps == null || r.txLinkMbps in 0..100_000) &&
        listOf(r.downloadStreams, r.uploadStreams, r.plannedDownloadStreams, r.plannedUploadStreams).all { it == null || it in 0..64 }
}
