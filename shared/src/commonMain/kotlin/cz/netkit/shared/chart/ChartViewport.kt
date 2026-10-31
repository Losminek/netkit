package cz.netkit.shared.chart

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** A trailing sample window. Positive pan moves into history; zoom keeps the right edge. */
data class ChartViewport(val windowSamples: Int, val zoom: Float = 1f, val panSamples: Float = 0f) {
    init {
        require(windowSamples > 0) { "windowSamples must be positive" }
    }

    /** Includes empty slots before the first sample. Used for the time axis, not data slicing. */
    val visibleSamples: Int
        get() = ceil(windowSamples.toDouble() / boundedZoom()).toInt().coerceAtLeast(1)

    fun visible(total: Int): IntRange {
        if (total <= 0) return IntRange.EMPTY
        val end = total - 1 - boundedPan(total).roundToInt()
        return (end - visibleSamples + 1).coerceAtLeast(0)..end
    }

    fun zoomBy(factor: Float, total: Int): ChartViewport {
        val multiplier = if (factor.isNaN()) 1f else factor
        val next = copy(zoom = (boundedZoom() * multiplier).coerceIn(1f, 10f))
        return next.copy(panSamples = next.boundedPan(total))
    }

    fun panBy(deltaSamples: Float, total: Int): ChartViewport {
        val delta = if (deltaSamples.isNaN()) 0f else deltaSamples
        val next = copy(zoom = boundedZoom(), panSamples = boundedPan(total) + delta)
        return next.copy(panSamples = next.boundedPan(total))
    }

    private fun boundedZoom() = if (zoom.isNaN()) 1f else zoom.coerceIn(1f, 10f)

    private fun boundedPan(total: Int): Float {
        val limit = (total.toLong() - visibleSamples).coerceAtLeast(0).toFloat()
        return if (panSamples.isNaN()) 0f else panSamples.coerceIn(0f, limit)
    }
}

/** Returns -1 for an empty range; finite fractions outside 0..1 are clamped. */
fun nearestIndex(fraction: Float, range: IntRange): Int {
    if (range.isEmpty()) return -1
    val f = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
    return (range.first.toDouble() + f * (range.last.toDouble() - range.first)).roundToInt()
}

data class WindowStats(val min: Float, val avg: Float, val max: Float)

/** Non-finite telemetry, like null, is missing data. Out-of-buffer portions are ignored. */
fun windowStats(values: List<Float?>, range: IntRange): WindowStats? {
    if (range.isEmpty() || values.isEmpty()) return null
    var min = Float.POSITIVE_INFINITY
    var max = Float.NEGATIVE_INFINITY
    var sum = 0.0
    var count = 0
    for (index in range.first.coerceAtLeast(0)..range.last.coerceAtMost(values.lastIndex)) {
        val value = values[index]?.takeIf { it.isFinite() } ?: continue
        min = minOf(min, value)
        max = maxOf(max, value)
        sum += value
        count++
    }
    return if (count == 0) null else WindowStats(min, (sum / count).toFloat(), max)
}

fun niceMax(v: Float): Float {
    if (v.isNaN() || v <= 0f) return 1f
    if (v == Float.POSITIVE_INFINITY) return v
    val magnitude = 10.0.pow(floor(log10(v.toDouble())))
    // Compare rounded Float candidates so exact decimal Float boundaries stay on that tick.
    for (step in NICE_STEPS) {
        val candidate = (step * magnitude).toFloat()
        if (candidate >= v) return candidate
    }
    return (10.0 * magnitude).toFloat()
}

private val NICE_STEPS = doubleArrayOf(1.0, 2.0, 2.5, 5.0, 10.0)

fun autoTicks(max: Float): List<Float> = listOf(0f, max / 4f, max / 2f, max * 0.75f, max)

/**
 * Chart event types. Wi-Fi: [ROAMING], [PACKET_LOSS], [CHANNEL_CHANGE]. Mobile signal (M4):
 * [RAT_CHANGE] (LTE ↔ 5G NSA ↔ 5G SA …) and [DATA_SIM_SWITCH] (data moved to another SIM).
 */
enum class ChartEventKind { ROAMING, PACKET_LOSS, CHANNEL_CHANGE, RAT_CHANGE, DATA_SIM_SWITCH }

/** Index in the same buffer as every chart line (the caller reindexes events when trimming). */
data class ChartEvent(val index: Int, val kind: ChartEventKind, val detail: String? = null)
