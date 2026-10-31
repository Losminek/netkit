package cz.netkit.shared.wifi

import kotlin.math.pow
import cz.netkit.shared.wol.parseIpv4

enum class WifiBand { GHZ_2_4, GHZ_5, GHZ_6, UNKNOWN }
enum class WifiSecurity { OPEN, WEP, WPA, WPA2, WPA3, WPA2_WPA3, OWE, OWE_TRANSITION, UNKNOWN }
enum class WifiSort { SIGNAL, NAME, CHANNEL }

data class AccessPoint(
    val ssid: String?,
    val bssid: String,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val centerFrequencyMhz: Int? = null,
    val channel: Int?,
    val band: WifiBand,
    val widthMhz: Int?,
    val security: WifiSecurity,
    val standard: String?,
    val ageMs: Long,
    val seenAtElapsedMs: Long? = null,
    val locallyAdministered: Boolean,
    val isCurrent: Boolean = false,
    val isSelected: Boolean = false,
    val secondaryCenterFrequencyMhz: Int? = null,
    val is80Plus80: Boolean = false,
    /** BSS Load (IE 11) station count as advertised by the AP; null when the element is absent. */
    val stationCount: Int? = null,
    /** BSS Load (IE 11) channel utilization 0..100 % of the AP's primary channel; null when absent. */
    val channelUtilizationPercent: Int? = null,
)

data class SignalSample(val elapsedMs: Long, val rssiDbm: Int)
/** channel is the center channel, not a selected primary channel, for bonded widths. */
data class ChannelRecommendation(val band: WifiBand, val channel: Int, val score: Double,
                                 val widthMhz: Int = 20, val mayRequireDfs: Boolean = false)
data class OccupiedFrequencySegment(val lowerMhz: Double, val upperMhz: Double)

data class CurrentWifi(
    val ssid: String?, val bssid: String?, val rssiDbm: Int?, val frequencyMhz: Int?,
    val standard: String?, val rxMbps: Int?, val txMbps: Int?, val ipAddress: String?,
    val gateway: String?, val dnsServers: List<String>, val security: WifiSecurity?,
    val widthMhz: Int? = null,
)

enum class WifiScanStatus { IDLE, SCANNING, READY, THROTTLED_OR_FAILED, CANCELLED, PERMISSION_REQUIRED, LOCATION_DISABLED, WIFI_DISABLED, ERROR }

data class WifiAnalyzerState(
    val accessPoints: List<AccessPoint> = emptyList(),
    val current: CurrentWifi? = null,
    val selectedBssid: String? = null,
    val historyBssid: String? = null,
    val status: WifiScanStatus = WifiScanStatus.IDLE,
    /** Receipt of the last confirmed non-empty scan; reading cache never advances this clock. */
    val lastUpdatedElapsedMs: Long? = null,
    val excludeConnectedSsid: Boolean = false,
    val ownBssids: Set<String> = emptySet(),

)

fun bandForFrequency(frequencyMhz: Int): WifiBand = when (frequencyMhz) {
    in 2400..2500 -> WifiBand.GHZ_2_4
    in 4900..5924 -> WifiBand.GHZ_5
    in 5925..7125 -> WifiBand.GHZ_6
    else -> WifiBand.UNKNOWN
}

/** Returns the primary channel number. 5935 MHz is the special 6 GHz channel 2. */
fun channelForFrequency(frequencyMhz: Int): Int? {
    if (frequencyMhz == 2484) return 14
    if (frequencyMhz == 5935) return 2
    val base = when (frequencyMhz) {
        in 2412..2472 -> 2407
        in 5160..5885 -> 5000
        in 5955..7115 -> 5950
        else -> return null
    }
    val delta = frequencyMhz - base
    if (delta <= 0 || delta % 5 != 0) return null
    return delta / 5
}

fun signalLevel(rssiDbm: Int): Int = when {
    rssiDbm >= -50 -> 4
    rssiDbm >= -60 -> 3
    rssiDbm >= -70 -> 2
    rssiDbm >= -80 -> 1
    else -> 0
}

fun isLocallyAdministered(bssid: String): Boolean {
    val first = bssid.split(':', '-', '.').firstOrNull()?.take(2)?.toIntOrNull(16) ?: return false
    return first and 0x02 != 0
}

fun classifySecurity(capabilities: String): WifiSecurity {
    val caps = capabilities.uppercase()
    val meaningful = caps.split("][")
        .map { it.trim('[', ']').trim() }
        .filter { it.isNotEmpty() && it !in setOf("ESS", "WPS", "IBSS", "UTF-8", "WMM", "WPS-PBC", "WPS-PIN") }
    val securityTokens = meaningful.joinToString("|")
    val hasRsn = securityTokens.contains("RSN")
    val hasWpa3Enterprise192 = securityTokens.contains("SUITE_B_192")
    val hasWpa3Enterprise = hasRsn && securityTokens.contains("EAP/SHA256") &&
        !securityTokens.contains("EAP/SHA1") &&
        meaningful.contains("MFPR") && meaningful.contains("MFPC") &&
        !securityTokens.contains("WEP") && !securityTokens.contains("TKIP")
    return when {
        caps.isBlank() -> WifiSecurity.UNKNOWN
        "OWE_TRANSITION" in securityTokens -> WifiSecurity.OWE_TRANSITION
        "OWE" in securityTokens -> WifiSecurity.OWE
        "PSK" in securityTokens && "SAE" in securityTokens -> WifiSecurity.WPA2_WPA3
        "SAE" in securityTokens || "WPA3" in securityTokens || hasWpa3Enterprise192 || hasWpa3Enterprise -> WifiSecurity.WPA3
        "WEP" in securityTokens -> WifiSecurity.WEP
        "WPA2" in securityTokens || "RSN" in securityTokens -> WifiSecurity.WPA2
        "WPA" in securityTokens -> WifiSecurity.WPA
        securityTokens.isEmpty() -> WifiSecurity.OPEN
        else -> WifiSecurity.UNKNOWN
    }
}

/** Frequency occupancy is modelled as a symmetric channel-width interval. */
fun occupiedFrequencySegments(ap: AccessPoint): List<OccupiedFrequencySegment> {
    val centers = listOfNotNull(ap.centerFrequencyMhz ?: ap.frequencyMhz, ap.secondaryCenterFrequencyMhz)
    val segmentWidth = if (ap.is80Plus80) {
        (ap.widthMhz ?: 160).coerceAtLeast(40) / 2.0
    } else (ap.widthMhz ?: 20).coerceAtLeast(20).toDouble()
    return centers.map { center -> OccupiedFrequencySegment(center - segmentWidth / 2, center + segmentWidth / 2) }
}

fun overlapScore(candidateChannel: Int, band: WifiBand, networks: List<AccessPoint>, widthMhz: Int = 20): Double {
    require(widthMhz in listOf(20, 40, 80, 160))
    val center = frequencyForChannel(candidateChannel, band) ?: return Double.POSITIVE_INFINITY
    val lower = center - widthMhz / 2.0
    val upper = center + widthMhz / 2.0
    return networks.asSequence().filter { it.band == band }.sumOf { ap ->
        occupiedFrequencySegments(ap).sumOf { segment ->
            val overlap = (minOf(upper, segment.upperMhz) - maxOf(lower, segment.lowerMhz)).coerceAtLeast(0.0)
            if (overlap == 0.0) 0.0 else overlap / widthMhz * dbmToMilliwatts(ap.rssiDbm)
        }
    }
}

const val WIFI_DATASET_WARNING_MS = 30_000L
const val WIFI_DATASET_EXPIRY_MS = 5 * 60_000L

enum class WifiDatasetFreshness { UNKNOWN, FRESH, AGING, EXPIRED }
data class WifiDatasetAge(val freshness: WifiDatasetFreshness, val ageMs: Long?) {
    val allowsRecommendation: Boolean get() = freshness == WifiDatasetFreshness.FRESH || freshness == WifiDatasetFreshness.AGING
}

/** A-3: age belongs to the received scan, not to individually expiring access points. */
fun wifiDatasetAge(receivedElapsedMs: Long?, nowElapsedMs: Long): WifiDatasetAge {
    if (receivedElapsedMs == null || receivedElapsedMs < 0 || nowElapsedMs < receivedElapsedMs)
        return WifiDatasetAge(WifiDatasetFreshness.UNKNOWN, null)
    val age = nowElapsedMs - receivedElapsedMs
    return WifiDatasetAge(when {
        age > WIFI_DATASET_EXPIRY_MS -> WifiDatasetFreshness.EXPIRED
        age > WIFI_DATASET_WARNING_MS -> WifiDatasetFreshness.AGING
        else -> WifiDatasetFreshness.FRESH
    }, age)
}

fun accessPointAgeMs(point: AccessPoint, nowElapsedMs: Long?): Long = when {
    nowElapsedMs == null -> point.ageMs.coerceAtLeast(0)
    point.seenAtElapsedMs == null -> point.ageMs.coerceAtLeast(0)
    point.seenAtElapsedMs > nowElapsedMs || point.seenAtElapsedMs < 0 -> Long.MAX_VALUE
    else -> nowElapsedMs - point.seenAtElapsedMs
}

/** Membership is fixed until the next non-empty scan; invalid frequency is not a plottable channel. */
fun snapshotAccessPoints(points: List<AccessPoint>): List<AccessPoint> =
    points.filter { channelForFrequency(it.frequencyMhz) != null }

fun recommendDatasetChannels(state: WifiAnalyzerState, nowElapsedMs: Long, widthMhz: Int = 20): List<ChannelRecommendation> =
    if (wifiDatasetAge(state.lastUpdatedElapsedMs, nowElapsedMs).allowsRecommendation)
        recommendChannels(state.accessPoints.filterNot { isOwnAccessPoint(it, state) }, widthMhz) else emptyList()

/** SSID equality is only used after the user explicitly opts in; it does not prove ownership. */
fun isOwnAccessPoint(point: AccessPoint, state: WifiAnalyzerState): Boolean = point.isCurrent ||
    normalizeBssid(point.bssid)?.let { it in state.ownBssids } == true ||
    (state.excludeConnectedSsid && state.current?.ssid?.takeIf { it.isNotBlank() }?.let { it == point.ssid } == true)

fun withOwnAccessPoint(state: WifiAnalyzerState, bssid: String, owned: Boolean): WifiAnalyzerState {
    val normalized = normalizeBssid(bssid) ?: return state
    return state.copy(ownBssids = if (owned) state.ownBssids + normalized else state.ownBssids - normalized)
}

/** Display cap only: recommendations always use the complete dataset. */
const val WIFI_CHART_AP_LIMIT = 6
fun chartAccessPoints(points: List<AccessPoint>, band: WifiBand): List<AccessPoint> = snapshotAccessPoints(points)
    .filter { it.band == band }
    .sortedWith(compareByDescending<AccessPoint> { it.rssiDbm }.thenBy { accessPointKey(it) })
    .take(WIFI_CHART_AP_LIMIT)

/** Keep the original measurement timestamp; no cache/clock tick may manufacture history samples. */
fun signalSampleForDataset(state: WifiAnalyzerState, bssid: String?, nowElapsedMs: Long): SignalSample? {
    if (!wifiDatasetAge(state.lastUpdatedElapsedMs, nowElapsedMs).allowsRecommendation) return null
    val selected = normalizeBssid(bssid) ?: return null
    val point = state.accessPoints.firstOrNull { normalizeBssid(it.bssid) == selected } ?: return null
    val timestamp = point.seenAtElapsedMs ?: return null
    if (timestamp < 0 || timestamp > state.lastUpdatedElapsedMs!!) return null
    return SignalSample(timestamp, point.rssiDbm)
}

/** Global candidate centers; device/country permission and DFS availability are not inferred. */
fun recommendationChannels(band: WifiBand, widthMhz: Int): List<Int> = when (band) {
    WifiBand.GHZ_2_4 -> if (widthMhz == 20) listOf(1, 6, 11) else emptyList()
    WifiBand.GHZ_5 -> when (widthMhz) {
        20 -> (36..64 step 4).toList() + (100..144 step 4) + (149..177 step 4)
        40 -> listOf(38, 46, 54, 62, 102, 110, 118, 126, 134, 142, 151, 159, 167, 175)
        80 -> listOf(42, 58, 106, 122, 138, 155, 171)
        160 -> listOf(50, 114, 163)
        else -> emptyList()
    }
    WifiBand.GHZ_6 -> when (widthMhz) {
        20 -> (1..233 step 4).toList()
        40 -> (3..227 step 8).toList()
        80 -> (7..215 step 16).toList()
        160 -> (15..207 step 32).toList()
        else -> emptyList()
    }
    WifiBand.UNKNOWN -> emptyList()
}

fun candidateMayRequireDfs(band: WifiBand, channel: Int, widthMhz: Int): Boolean {
    val center = frequencyForChannel(channel, band) ?: return false
    return band == WifiBand.GHZ_5 && center + widthMhz / 2.0 > 5250 && center - widthMhz / 2.0 < 5730
}

/** Pure scoring of one supplied snapshot. UI callers must use recommendDatasetChannels for its age policy. */
fun recommendChannels(networks: List<AccessPoint>, widthMhz: Int = 20): List<ChannelRecommendation> {
    require(widthMhz in listOf(20, 40, 80, 160))
    val neighbours = snapshotAccessPoints(networks).filterNot { it.isCurrent }
    return listOf(WifiBand.GHZ_2_4, WifiBand.GHZ_5, WifiBand.GHZ_6).mapNotNull { band ->
        if (neighbours.none { it.band == band }) return@mapNotNull null
        val width = if (band == WifiBand.GHZ_2_4) 20 else widthMhz
        recommendationChannels(band, width).map { channel ->
            ChannelRecommendation(band, channel, overlapScore(channel, band, neighbours, width), width,
                candidateMayRequireDfs(band, channel, width))
        }.minWithOrNull(compareBy<ChannelRecommendation> { it.score }.thenBy { it.mayRequireDfs }.thenBy { it.channel })
    }
}

fun sortedAccessPoints(points: List<AccessPoint>, band: WifiBand?, sort: WifiSort, ssidFilter: String = ""): List<AccessPoint> =
    points.asSequence().filter { band == null || it.band == band }
        .filter { ssidFilter.isBlank() || it.ssid?.contains(ssidFilter.trim(), ignoreCase = true) == true }
        .sortedWith(when (sort) {
            WifiSort.SIGNAL -> compareByDescending<AccessPoint> { it.rssiDbm }.thenBy { it.ssid }.thenBy { accessPointKey(it) }
            WifiSort.NAME -> compareBy<AccessPoint> { it.ssid.orEmpty().lowercase() }.thenByDescending { it.rssiDbm }.thenBy { accessPointKey(it) }
            WifiSort.CHANNEL -> compareBy<AccessPoint> { it.band.ordinal }.thenBy { it.channel ?: Int.MAX_VALUE }.thenBy { accessPointKey(it) }
        }).toList()

internal fun frequencyForChannel(channel: Int, band: WifiBand): Double? = when (band) {
    WifiBand.GHZ_2_4 -> when (channel) { 14 -> 2484.0; in 1..13 -> 2407.0 + channel * 5; else -> null }
    WifiBand.GHZ_5 -> if (channel > 0) 5000.0 + channel * 5 else null
    WifiBand.GHZ_6 -> if (channel == 2) 5935.0 else if (channel > 0) 5950.0 + channel * 5 else null
    WifiBand.UNKNOWN -> null
}

private fun dbmToMilliwatts(dbm: Int): Double = 10.0.pow(dbm / 10.0)

/** Stable UI key: anonymous observations cannot be identified as a particular BSSID. */
fun accessPointKey(point: AccessPoint): String = normalizeBssid(point.bssid)?.let { "bssid:$it" }
    ?: "anonymous:${point.ssid?.length ?: -1}:${point.ssid.orEmpty()}:${point.frequencyMhz}:${point.security.name}"

fun normalizeBssid(value: String?): String? = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

/** Prefer the newest valid timestamp, then strongest signal. Anonymous duplicates are coalesced. */
fun deduplicateAccessPoints(points: List<AccessPoint>): List<AccessPoint> = points
    .groupBy(::accessPointKey)
    .values.map { observations ->
        observations.maxWith(compareBy<AccessPoint> { it.seenAtElapsedMs?.takeIf { time -> time >= 0 } ?: Long.MIN_VALUE }
            .thenBy { it.rssiDbm })
    }

/** Inputs are already resolved gateway addresses from default routes; no DNS lookup occurs. */
fun preferIpv4Address(addresses: List<String>): String? = addresses.firstOrNull { parseIpv4(it) != null } ?: addresses.firstOrNull()

fun toggleWifiSelection(selected: String?, clicked: String): String? {
    val normalized = normalizeBssid(clicked) ?: return selected
    return normalized.takeUnless { it == normalizeBssid(selected) }
}

fun appendSignalSample(history: List<SignalSample>, sample: SignalSample): List<SignalSample> =
    if (history.lastOrNull()?.elapsedMs?.let { it >= sample.elapsedMs } == true) history
    else (history + sample).takeLast(90)

/** Publish before current-network lookup. Failed/cache reads never rejuvenate a confirmed snapshot. */
fun publishScanResult(old: WifiAnalyzerState, points: List<AccessPoint>, fresh: Boolean, receivedElapsedMs: Long?): WifiAnalyzerState {
    val confirmed = fresh && points.isNotEmpty() && receivedElapsedMs != null && receivedElapsedMs >= 0
    return old.copy(
        accessPoints = when {
            confirmed -> points
            old.accessPoints.isNotEmpty() -> old.accessPoints
            else -> points // Initial cache is visible, but has no confirmed dataset clock.
        },
        status = if (confirmed) WifiScanStatus.READY else WifiScanStatus.THROTTLED_OR_FAILED,
        lastUpdatedElapsedMs = if (confirmed) receivedElapsedMs else old.lastUpdatedElapsedMs,
    )
}

enum class WifiAccessIssue { NONE, PERMISSION_REQUIRED, PRECISE_REQUIRED, PERMANENTLY_DENIED, LOCATION_DISABLED, WIFI_DISABLED }

fun wifiAccessIssue(fine: Boolean, coarse: Boolean, requestedBefore: Boolean, showRationale: Boolean,
                    locationEnabled: Boolean, wifiEnabled: Boolean): WifiAccessIssue = when {
    !fine && coarse -> WifiAccessIssue.PRECISE_REQUIRED
    !fine && requestedBefore && !showRationale -> WifiAccessIssue.PERMANENTLY_DENIED
    !fine -> WifiAccessIssue.PERMISSION_REQUIRED
    !locationEnabled -> WifiAccessIssue.LOCATION_DISABLED
    !wifiEnabled -> WifiAccessIssue.WIFI_DISABLED
    else -> WifiAccessIssue.NONE
}
