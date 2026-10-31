package cz.netkit.shared.wifi

import kotlin.math.log10
import kotlin.math.round

/*
 * M2 "Spectrum" and "Channel health" (WiFiman-like) — pure data for the UI.
 *
 * Typical Compose usage (no composables live here):
 *
 *   val spectrum = bandSpectrum(state.accessPoints, band)        // null for WifiBand.UNKNOWN
 *   spectrum?.traces?.forEach { trace ->                          // strongest first
 *       trace.shapes.forEach { s ->                               // 1 shape, 2 for 80+80
 *           // x = spectrum.axis.fraction(mhz) * width; y from trace.peakDbm
 *           // trapezoid: (s.lowerMhz, floor) → (s.plateauLowerMhz, peak)
 *           //            → (s.plateauUpperMhz, peak) → (s.upperMhz, floor)
 *       }
 *   }
 *   val counts = wifiBandCounts(state.accessPoints)               // chips All / 2.4 / 5 / 6
 *   when (val h = channelHealthReport(state, band, SystemClock.elapsedRealtime())) {
 *       is ChannelHealthReport.Ready -> h.channels               // one entry per axis channel
 *       is ChannelHealthReport.Stale -> offerNewScan()            // never show old health
 *       is ChannelHealthReport.NoData -> showNoBandData()         // no AP observed in band
 *   }
 *
 * Like the existing occupancy chart, the spectrum is a visualisation of client scan results,
 * not RF measurement. The trace keeps the snapshot even after the recommendation window;
 * channel health follows the recommendation age policy (wifiDatasetAge.allowsRecommendation).
 */

/** One channel tick on the axis. [labelled] marks the subset the UI should print. */
data class SpectrumTick(val channel: Int, val frequencyMhz: Double, val labelled: Boolean)

/** Frequency range inside the axis without any listed 20 MHz channel (e.g. 5 GHz UNII-2/2C gap). */
data class SpectrumGap(val lowerMhz: Double, val upperMhz: Double)

/**
 * Linear frequency axis for one band. The axis is **not** an evenly spaced channel index:
 * gaps between channel blocks occupy their real width so that bonded spans keep proportions.
 *
 * @property channels 20 MHz primary channels shown on the axis, ascending by frequency.
 * @property ticks one tick per channel; only [SpectrumTick.labelled] ones carry a printed label.
 * @property gaps regions between channel blocks; the UI may shade them.
 */
data class SpectrumAxis(
    val band: WifiBand,
    val minMhz: Double,
    val maxMhz: Double,
    val channels: List<Int>,
    val ticks: List<SpectrumTick>,
    val gaps: List<SpectrumGap>,
) {
    val spanMhz: Double get() = maxMhz - minMhz

    /** Position 0.0..1.0 along the axis; values outside the axis are clamped to its edge. */
    fun fraction(frequencyMhz: Double): Double = ((frequencyMhz - minMhz) / spanMhz).coerceIn(0.0, 1.0)
}

/**
 * One trapezoid of an AP. [lowerMhz]..[upperMhz] is the occupied channel (from
 * [occupiedFrequencySegments]); the plateau is narrowed by [SPECTRUM_TRAPEZOID_SKIRT_FRACTION]
 * on each side. The skirt is purely visual, not a spectral-mask model.
 */
data class SpectrumShape(
    val lowerMhz: Double,
    val upperMhz: Double,
    val plateauLowerMhz: Double,
    val plateauUpperMhz: Double,
) {
    val centerMhz: Double get() = (lowerMhz + upperMhz) / 2
}

/**
 * Drawable AP. [key] is [accessPointKey] (stable list/legend key), [peakDbm] is RSSI,
 * [shapes] has one item, or two for 80+80 MHz.
 */
data class SpectrumTrace(
    val key: String,
    val accessPoint: AccessPoint,
    val primaryChannel: Int,
    val peakDbm: Int,
    val shapes: List<SpectrumShape>,
)

data class BandSpectrum(val axis: SpectrumAxis, val traces: List<SpectrumTrace>)

/** Visual skirt of a trapezoid: fraction of the segment width on each side (20 MHz → 2 MHz). */
const val SPECTRUM_TRAPEZOID_SKIRT_FRACTION = 0.1

private const val CHANNEL_HALF_WIDTH_MHZ = 10.0
private val LABELLED_5GHZ = setOf(36, 52, 100, 116, 132, 149, 165)
private val LABELLED_6GHZ = (1..233 step 32).toSet() // 1, 33, 65, 97, 129, 161, 193, 225

/**
 * Axis for [band]: 2.4 GHz channels 1–13 (14 only when an AP on 2484 MHz is present),
 * 5 GHz the global 20 MHz candidates 36–64, 100–144, 149–177 (see [recommendationChannels]),
 * 6 GHz 1–233 step 4 (special channel 2 only when present). Range is the lower edge of the
 * first channel to the upper edge of the last one. Returns null for [WifiBand.UNKNOWN].
 * The list of channels is a global raster, not a statement about regional permission.
 */
fun spectrumAxis(band: WifiBand, points: List<AccessPoint> = emptyList()): SpectrumAxis? {
    val present = snapshotAccessPoints(points).filter { it.band == band }.mapNotNull { channelForFrequency(it.frequencyMhz) }.toSet()
    val channels = when (band) {
        WifiBand.GHZ_2_4 -> (1..13).toList() + listOfNotNull(14.takeIf { it in present })
        WifiBand.GHZ_5 -> recommendationChannels(WifiBand.GHZ_5, 20)
        WifiBand.GHZ_6 -> listOfNotNull(2.takeIf { it in present }) + recommendationChannels(WifiBand.GHZ_6, 20)
        WifiBand.UNKNOWN -> return null
    }
    val centers = channels.map { frequencyForChannel(it, band)!! }
    val ticks = channels.zip(centers) { channel, center ->
        SpectrumTick(channel, center, when (band) {
            WifiBand.GHZ_5 -> channel in LABELLED_5GHZ
            WifiBand.GHZ_6 -> channel in LABELLED_6GHZ
            else -> true
        })
    }
    val gaps = centers.zipWithNext().mapNotNull { (previous, next) ->
        val lower = previous + CHANNEL_HALF_WIDTH_MHZ
        val upper = next - CHANNEL_HALF_WIDTH_MHZ
        if (upper > lower) SpectrumGap(lower, upper) else null
    }
    return SpectrumAxis(band, centers.first() - CHANNEL_HALF_WIDTH_MHZ, centers.last() + CHANNEL_HALF_WIDTH_MHZ,
        channels, ticks, gaps)
}

/**
 * All plottable APs of [band] (valid frequency, see [snapshotAccessPoints]), strongest first,
 * ties broken by [accessPointKey]. Unlike [chartAccessPoints] there is no display cap here;
 * the UI may `take(WIFI_CHART_AP_LIMIT)` when it reuses the six-colour palette.
 * Bonded widths use the AP's centre frequency fields; without them primary ±width/2.
 */
fun spectrumTraces(points: List<AccessPoint>, band: WifiBand): List<SpectrumTrace> = snapshotAccessPoints(points)
    .filter { it.band == band }
    .sortedWith(compareByDescending<AccessPoint> { it.rssiDbm }.thenBy { accessPointKey(it) })
    .map { ap ->
        SpectrumTrace(accessPointKey(ap), ap, channelForFrequency(ap.frequencyMhz)!!, ap.rssiDbm,
            occupiedFrequencySegments(ap).map { segment ->
                val skirt = (segment.upperMhz - segment.lowerMhz) * SPECTRUM_TRAPEZOID_SKIRT_FRACTION
                SpectrumShape(segment.lowerMhz, segment.upperMhz, segment.lowerMhz + skirt, segment.upperMhz - skirt)
            })
    }

/** Axis + traces of one band; null for [WifiBand.UNKNOWN]. Not gated by dataset age (like the chart). */
fun bandSpectrum(points: List<AccessPoint>, band: WifiBand): BandSpectrum? =
    spectrumAxis(band, points)?.let { BandSpectrum(it, spectrumTraces(points, band)) }

/** AP counts for the band chips. Only plottable observations (valid frequency) are counted. */
data class WifiBandCounts(val ghz24: Int, val ghz5: Int, val ghz6: Int) {
    val all: Int get() = ghz24 + ghz5 + ghz6

    /** `null` means "All", matching the UI band filter type `WifiBand?`. */
    fun count(band: WifiBand?): Int = when (band) {
        null -> all
        WifiBand.GHZ_2_4 -> ghz24
        WifiBand.GHZ_5 -> ghz5
        WifiBand.GHZ_6 -> ghz6
        WifiBand.UNKNOWN -> 0
    }
}

fun wifiBandCounts(points: List<AccessPoint>): WifiBandCounts {
    val valid = snapshotAccessPoints(points)
    return WifiBandCounts(valid.count { it.band == WifiBand.GHZ_2_4 }, valid.count { it.band == WifiBand.GHZ_5 },
        valid.count { it.band == WifiBand.GHZ_6 })
}

// ---------------------------------------------------------------------------------------------
// Channel health
// ---------------------------------------------------------------------------------------------

/** Ordered from best to worst; combination takes the worse (higher ordinal) value. */
enum class ChannelHealth { EXCELLENT, GOOD, FAIR, POOR }

/*
 * Overlap pressure = overlapScore(channel, 20 MHz) expressed in dBm: the RSSI-weighted (mW)
 * sum of neighbour spectrum overlapping the 20 MHz channel. One neighbour fully on the channel
 * at -70 dBm gives -70 dBm; one half-overlapping at -67 dBm gives about -70 dBm.
 * The lowest threshold sits at the usual -82 dBm 20 MHz preamble-detect (CCA) level: neighbours
 * below it are unlikely to defer our transmissions. Each value is the start of the category.
 */
const val HEALTH_PRESSURE_GOOD_FROM_DBM = -82.0
const val HEALTH_PRESSURE_FAIR_FROM_DBM = -72.0
const val HEALTH_PRESSURE_POOR_FROM_DBM = -62.0

/** BSS Load channel utilization (busy time sensed by the AP); each value starts the category. */
const val HEALTH_UTILIZATION_GOOD_FROM_PERCENT = 20
const val HEALTH_UTILIZATION_FAIR_FROM_PERCENT = 40
const val HEALTH_UTILIZATION_POOR_FROM_PERCENT = 70

/** Pressure in dBm (rounded to 0.01 dB before comparison); null = no overlapping neighbour. */
fun pressureHealth(pressureDbm: Double?): ChannelHealth = when {
    pressureDbm == null -> ChannelHealth.EXCELLENT
    pressureDbm >= HEALTH_PRESSURE_POOR_FROM_DBM -> ChannelHealth.POOR
    pressureDbm >= HEALTH_PRESSURE_FAIR_FROM_DBM -> ChannelHealth.FAIR
    pressureDbm >= HEALTH_PRESSURE_GOOD_FROM_DBM -> ChannelHealth.GOOD
    else -> ChannelHealth.EXCELLENT
}

/** null when no AP on the channel advertises BSS Load: absence is "unknown", not "idle". */
fun utilizationHealth(percent: Int?): ChannelHealth? = when {
    percent == null -> null
    percent >= HEALTH_UTILIZATION_POOR_FROM_PERCENT -> ChannelHealth.POOR
    percent >= HEALTH_UTILIZATION_FAIR_FROM_PERCENT -> ChannelHealth.FAIR
    percent >= HEALTH_UTILIZATION_GOOD_FROM_PERCENT -> ChannelHealth.GOOD
    else -> ChannelHealth.EXCELLENT
}

/** Utilization can only raise severity; low utilization never hides strong overlap. */
fun combineChannelHealth(pressure: ChannelHealth, utilization: ChannelHealth?): ChannelHealth =
    if (utilization != null && utilization.ordinal > pressure.ordinal) utilization else pressure

/**
 * Health of one 20 MHz primary channel.
 *
 * @property pressureMw raw [overlapScore] of neighbours (excluded/own APs not counted).
 * @property pressureDbm [pressureMw] in dBm rounded to 0.01; null when no neighbour overlaps.
 * @property overlappingAccessPoints neighbours whose occupied span overlaps this channel (> 0 MHz).
 * @property maxUtilizationPercent highest BSS Load utilization among APs whose primary channel
 *   is this channel (own APs included, see [channelHealthReport]); null if none advertises it.
 * @property mayRequireDfs [candidateMayRequireDfs] for 20 MHz.
 * @property connectedHere the connected AP's primary channel is this channel.
 */
data class ChannelHealthEntry(
    val band: WifiBand,
    val channel: Int,
    val centerFrequencyMhz: Double,
    val health: ChannelHealth,
    val pressureHealth: ChannelHealth,
    val utilizationHealth: ChannelHealth?,
    val pressureMw: Double,
    val pressureDbm: Double?,
    val overlappingAccessPoints: Int,
    val maxUtilizationPercent: Int?,
    val mayRequireDfs: Boolean,
    val connectedHere: Boolean,
)

sealed interface ChannelHealthReport {
    val band: WifiBand

    /** Channels in axis order ([spectrumAxis]); [age] is FRESH or AGING (UI shows the AGING warning). */
    data class Ready(override val band: WifiBand, val age: WifiDatasetAge, val channels: List<ChannelHealthEntry>) : ChannelHealthReport

    /** Dataset is UNKNOWN or EXPIRED by [wifiDatasetAge]; no health values are produced. */
    data class Stale(override val band: WifiBand, val age: WifiDatasetAge) : ChannelHealthReport

    /** No plottable AP (including own ones) in this band, or [WifiBand.UNKNOWN]. */
    data class NoData(override val band: WifiBand) : ChannelHealthReport
}

/**
 * Pure scoring of one supplied snapshot. UI callers must use [channelHealthReport] for the
 * age policy and own-AP exclusion. [isExcluded] removes APs from pressure and overlap count
 * only; BSS Load utilization is taken from every AP on the channel.
 */
fun scoreChannelHealth(
    points: List<AccessPoint>,
    band: WifiBand,
    isExcluded: (AccessPoint) -> Boolean = { it.isCurrent },
    connectedChannel: Int? = null,
): List<ChannelHealthEntry> {
    val axis = spectrumAxis(band, points) ?: return emptyList()
    val inBand = snapshotAccessPoints(points).filter { it.band == band }
    val neighbours = inBand.filterNot(isExcluded)
    return axis.ticks.map { tick ->
        val lower = tick.frequencyMhz - CHANNEL_HALF_WIDTH_MHZ
        val upper = tick.frequencyMhz + CHANNEL_HALF_WIDTH_MHZ
        val pressureMw = overlapScore(tick.channel, band, neighbours, 20)
        val pressureDbm = if (pressureMw > 0.0) round(10 * log10(pressureMw) * 100) / 100 else null
        val overlapping = neighbours.count { ap ->
            occupiedFrequencySegments(ap).any { minOf(upper, it.upperMhz) - maxOf(lower, it.lowerMhz) > 0.0 }
        }
        val utilization = inBand.filter { channelForFrequency(it.frequencyMhz) == tick.channel }
            .mapNotNull { it.channelUtilizationPercent?.coerceIn(0, 100) }.maxOrNull()
        val byPressure = pressureHealth(pressureDbm)
        val byUtilization = utilizationHealth(utilization)
        ChannelHealthEntry(band, tick.channel, tick.frequencyMhz, combineChannelHealth(byPressure, byUtilization),
            byPressure, byUtilization, pressureMw, pressureDbm, overlapping, utilization,
            candidateMayRequireDfs(band, tick.channel, 20), connectedChannel == tick.channel)
    }
}

/**
 * Channel health of [band] from the current dataset.
 *
 * - Age: produced only when `wifiDatasetAge(state.lastUpdatedElapsedMs, now).allowsRecommendation`;
 *   otherwise [ChannelHealthReport.Stale] (same policy as [recommendDatasetChannels]).
 * - Own APs: [isOwnAccessPoint] (connected BSSID, "Moje AP", opt-in same SSID) is excluded from
 *   pressure and overlap count exactly like recommendations — the question is "how crowded is
 *   this channel by others". Their BSS Load utilization **is** used: it is the AP's measurement of
 *   the channel's busy time (all traffic, including neighbours), the only airtime hint available.
 * - Band with no observed AP at all → [ChannelHealthReport.NoData] (the phone may not scan it;
 *   an empty band is not evidence of a clean one). An own AP alone proves the band is scanned.
 */
fun channelHealthReport(state: WifiAnalyzerState, band: WifiBand, nowElapsedMs: Long): ChannelHealthReport {
    val age = wifiDatasetAge(state.lastUpdatedElapsedMs, nowElapsedMs)
    if (!age.allowsRecommendation) return ChannelHealthReport.Stale(band, age)
    if (band == WifiBand.UNKNOWN || snapshotAccessPoints(state.accessPoints).none { it.band == band })
        return ChannelHealthReport.NoData(band)
    val connected = state.accessPoints.firstOrNull { it.isCurrent && it.band == band }?.frequencyMhz
        ?: state.current?.frequencyMhz?.takeIf { bandForFrequency(it) == band }
    return ChannelHealthReport.Ready(band, age, scoreChannelHealth(state.accessPoints, band,
        isExcluded = { isOwnAccessPoint(it, state) }, connectedChannel = connected?.let(::channelForFrequency)))
}
