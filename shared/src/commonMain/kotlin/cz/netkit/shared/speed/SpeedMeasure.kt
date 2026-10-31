package cz.netkit.shared.speed

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

// --- Method constants (docs/funkce/speedtest.md §2) -----------------------------------------

/** Parallel HTTPS download streams of the LibreSpeed engine (LibreSpeed's own default is 6). */
const val SPEED_DOWNLOAD_STREAMS = 6
/** Parallel HTTPS upload streams of the LibreSpeed engine. */
const val SPEED_UPLOAD_STREAMS = 3
/** Duration of one direction (download or upload), warm-up included. */
const val SPEED_PHASE_MS = 10_000L
/** Minimum warm-up excluded from the result (TCP slow start, socket buffers filling). */
const val SPEED_WARMUP_MIN_MS = 2_000L
/** Longest warm-up the ramp detection may choose (high-BDP paths ramp slower). */
const val SPEED_WARMUP_MAX_MS = 4_000L
/** Sampling period of the byte counters (chart resolution). */
const val SPEED_SAMPLE_MS = 250L
/** Width of the moving window behind every chart point and the peak value. */
const val SPEED_RATE_WINDOW_MS = 1_000L
/** Unloaded latency samples before the test (one extra warm-up request is discarded). */
const val SPEED_IDLE_PINGS = 12
/** Period of the latency probe while a direction is loaded. */
const val SPEED_LOADED_PING_INTERVAL_MS = 250L
/** Number of kept results. */
const val SPEED_HISTORY_MAX = 20
/** Chart slots: 15 s hard cap (NDT7) at [SPEED_SAMPLE_MS]. */
const val SPEED_CHART_CAPACITY = 60

/** One cumulative byte-counter reading of a running direction; [elapsedMs] since the direction started. */
data class ByteSample(val elapsedMs: Long, val bytes: Long)

/**
 * Bounded, monotonic record of cumulative byte readings for one direction. Readings that go back
 * in time or decrease are ignored, so a racing sampler cannot produce negative rates.
 */
class ThroughputTrace(private val maxSamples: Int = 4 * SPEED_CHART_CAPACITY) {
    private val items = ArrayList<ByteSample>()
    val samples: List<ByteSample> get() = items.toList()
    val size: Int get() = items.size

    /** Adds a reading; returns `false` when it was rejected (non-monotonic or trace full). */
    fun add(elapsedMs: Long, bytes: Long): Boolean {
        if (elapsedMs < 0 || bytes < 0 || items.size >= maxSamples) return false
        val last = items.lastOrNull()
        if (last != null && (elapsedMs <= last.elapsedMs || bytes < last.bytes)) return false
        items.add(ByteSample(elapsedMs, bytes))
        return true
    }
}

/** Mbit/s from bytes over milliseconds (decimal megabits, as every speed test reports). */
fun megabitsPerSecond(bytes: Double, milliseconds: Double): Double? =
    if (milliseconds <= 0.0 || bytes < 0.0) null else bytes * 8.0 / (milliseconds * 1_000.0)

/** Cumulative bytes at [t], linearly interpolated between readings; an implicit (0, 0) start. */
fun bytesAt(samples: List<ByteSample>, t: Double): Double {
    if (samples.isEmpty() || t <= 0.0) return 0.0
    var previous = ByteSample(0, 0)
    for (sample in samples) {
        if (sample.elapsedMs >= t) {
            val span = (sample.elapsedMs - previous.elapsedMs).toDouble()
            if (span <= 0.0) return sample.bytes.toDouble()
            val fraction = (t - previous.elapsedMs) / span
            return previous.bytes + (sample.bytes - previous.bytes) * fraction
        }
        previous = sample
    }
    return samples.last().bytes.toDouble()
}

/**
 * Moving-window rate in Mbit/s at every reading: bytes in `(t − windowMs, t]` over the window
 * (shorter at the very start, where the window is clipped to 0). Used for the live chart.
 */
fun rateSeriesMbps(samples: List<ByteSample>, windowMs: Long = SPEED_RATE_WINDOW_MS): List<Double> =
    samples.map { sample ->
        val from = (sample.elapsedMs - windowMs).coerceAtLeast(0L).toDouble()
        megabitsPerSecond(sample.bytes - bytesAt(samples, from), sample.elapsedMs - from) ?: 0.0
    }

/**
 * Warm-up to exclude: at least [minMs] (scaled to 20 % for a direction shorter than 2 × [minMs],
 * e.g. a server that closed early). When the windowed rate at that point is still below 80 % of
 * the median rate after it (slow ramp, high BDP), the warm-up extends to the first reading that
 * reaches 80 %, at most [maxMs] and never past half of the direction.
 */
fun detectWarmupMs(
    samples: List<ByteSample>,
    minMs: Long = SPEED_WARMUP_MIN_MS,
    maxMs: Long = SPEED_WARMUP_MAX_MS,
    windowMs: Long = SPEED_RATE_WINDOW_MS,
): Long {
    val end = samples.lastOrNull()?.elapsedMs ?: return 0L
    val base = if (end < 2 * minMs) end / 5 else minMs
    val cap = minOf(maxMs, end / 2).coerceAtLeast(base)
    val rates = rateSeriesMbps(samples, windowMs)
    val after = samples.indices.filter { samples[it].elapsedMs >= base }.map { rates[it] }
    val reference = median(after) ?: return base
    if (reference <= 0.0) return base
    val threshold = reference * 0.8
    var first = true
    for (i in samples.indices) {
        val t = samples[i].elapsedMs
        if (t < base) continue
        if (t > cap) break
        // Already ramped at the minimum warm-up: keep the minimum exactly (interpolated in the summary).
        if (rates[i] >= threshold) return if (first) base else t
        first = false
    }
    return cap
}

/**
 * Result of one direction.
 *
 * @property mbps mean rate after the warm-up: bytes in `(warmupMs, end]` over that time.
 * @property peakMbps highest moving-window rate after the warm-up.
 * @property warmupMs excluded start (see [detectWarmupMs]).
 * @property measuredMs time the mean is computed over.
 * @property totalBytes all bytes including the warm-up.
 */
data class ThroughputSummary(
    val mbps: Double,
    val peakMbps: Double,
    val warmupMs: Long,
    val measuredMs: Long,
    val totalBytes: Long,
)

/** Summary of a direction; `null` without readings or with less than 1 s of measured time after the warm-up. */
fun summarizeThroughput(
    samples: List<ByteSample>,
    minWarmupMs: Long = SPEED_WARMUP_MIN_MS,
    maxWarmupMs: Long = SPEED_WARMUP_MAX_MS,
    windowMs: Long = SPEED_RATE_WINDOW_MS,
): ThroughputSummary? {
    val last = samples.lastOrNull() ?: return null
    val warmup = detectWarmupMs(samples, minWarmupMs, maxWarmupMs, windowMs)
    val measured = last.elapsedMs - warmup
    if (measured < 1_000L) return null
    val bytes = last.bytes - bytesAt(samples, warmup.toDouble())
    val mean = megabitsPerSecond(bytes, measured.toDouble()) ?: return null
    val rates = rateSeriesMbps(samples, windowMs)
    val peak = samples.indices.filter { samples[it].elapsedMs >= warmup + windowMs || samples[it].elapsedMs == last.elapsedMs }
        .maxOfOrNull { rates[it] } ?: mean
    return ThroughputSummary(mean, maxOf(peak, mean), warmup, measured, last.bytes)
}

// --- Latency ----------------------------------------------------------------------------------

/**
 * Latency statistics of an ordered probe sequence.
 *
 * @property sent probes attempted; [lost] of them got no answer (`null` sample).
 * @property medianMs median RTT of the answered probes.
 * @property jitterMs mean absolute difference of consecutive answered RTTs (RFC 3550 style, unsmoothed).
 */
data class LatencyStats(
    val sent: Int,
    val lost: Int,
    val medianMs: Double?,
    val jitterMs: Double?,
    val minMs: Double?,
    val maxMs: Double?,
)

fun latencyStats(samples: List<Double?>): LatencyStats {
    val ok = samples.filterNotNull().filter { it.isFinite() && it >= 0.0 }
    val jitter = if (ok.size < 2) null else ok.zipWithNext { a, b -> abs(b - a) }.average()
    return LatencyStats(
        sent = samples.size,
        lost = samples.size - ok.size,
        medianMs = median(ok),
        jitterMs = jitter,
        minMs = ok.minOrNull(),
        maxMs = ok.maxOrNull(),
    )
}

/** Median; average of the two middle values for an even count; `null` for an empty list. */
fun median(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
}

/** Bufferbloat grade of the latency increase under load (thresholds as commonly used by Waveform/DSLReports). */
enum class BufferbloatGrade { A_PLUS, A, B, C, D, F }

/** Loaded minus idle median RTT; `null` when either is unknown. Negative noise is reported as 0. */
fun latencyIncreaseMs(idle: LatencyStats?, loaded: LatencyStats?): Double? {
    val a = idle?.medianMs ?: return null
    val b = loaded?.medianMs ?: return null
    return (b - a).coerceAtLeast(0.0)
}

fun bufferbloatGrade(increaseMs: Double): BufferbloatGrade = when {
    increaseMs < 5.0 -> BufferbloatGrade.A_PLUS
    increaseMs < 30.0 -> BufferbloatGrade.A
    increaseMs < 60.0 -> BufferbloatGrade.B
    increaseMs < 200.0 -> BufferbloatGrade.C
    increaseMs < 400.0 -> BufferbloatGrade.D
    else -> BufferbloatGrade.F
}

// --- Chart helpers ----------------------------------------------------------------------------

/** Smallest 1/2/2.5/5 × 10ⁿ above [max] with 10 % headroom, at least [minimum]. */
fun niceChartTop(max: Double, minimum: Double): Double {
    val target = max * 1.1
    if (!target.isFinite() || target <= minimum) return minimum
    val base = 10.0.pow(floor(log10(target)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { it * base >= target }
    return ceil(step * base)
}

/**
 * Chart points for a direction: one moving-window rate per [SPEED_SAMPLE_MS] slot, left-aligned and
 * padded with `null` up to [capacity] so the line grows from the left edge like a progress trace.
 */
fun chartPoints(samples: List<ByteSample>, capacity: Int = SPEED_CHART_CAPACITY, slotMs: Long = SPEED_SAMPLE_MS): List<Double?> {
    val out = MutableList<Double?>(capacity) { null }
    if (samples.isEmpty()) return out
    val rates = rateSeriesMbps(samples)
    samples.forEachIndexed { i, s ->
        val slot = ((s.elapsedMs + slotMs / 2) / slotMs - 1).toInt()
        if (slot in 0 until capacity) out[slot] = rates[i]
    }
    // Fill single missing slots (sampler jitter) so the line does not break.
    for (i in 1 until capacity - 1) if (out[i] == null && out[i - 1] != null && out[i + 1] != null) out[i] = (out[i - 1]!! + out[i + 1]!!) / 2
    return out
}
