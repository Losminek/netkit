package cz.netkit.shared.wifi

import cz.netkit.shared.chart.ChartEvent
import cz.netkit.shared.chart.ChartEventKind
import cz.netkit.shared.lan.ipv4Number
import cz.netkit.shared.lan.ipv4Text
import kotlin.math.abs

/*
 * Live Wi-Fi telemetry (M2): pure, platform-free building blocks for the "Signal" screen.
 * Platform code samples the connected link, byte counters and latency; everything that can be
 * decided without the platform (buffering, statistics, classification, rates, roaming) lives here.
 */

/** Default sampling period of the live screen. */
const val LIVE_SAMPLE_INTERVAL_MS = 1_000L

/**
 * Default ring buffer capacity: 900 samples = 15 minutes at 1 Hz (docs/design.md §6, the
 * longest window of the expanded chart). Memory only, never persisted.
 */
const val LIVE_SERIES_DEFAULT_CAPACITY = 900

/**
 * One point of a live time series.
 *
 * @property elapsedMs monotonic timestamp (Android `SystemClock.elapsedRealtime()`), never wall clock.
 * @property value measured value, or `null` when the sample was lost/failed (timeout, no Wi-Fi,
 *   counter unavailable). A `null` sample is a real observation of "no value", not missing data.
 */
data class LiveSample(val elapsedMs: Long, val value: Double?)

/**
 * Bounded ring buffer of [LiveSample]s with strictly increasing timestamps.
 *
 * - When full, the oldest sample is evicted.
 * - A sample whose [LiveSample.elapsedMs] is not strictly greater than the last accepted one
 *   (out of order or duplicate) or is negative is dropped and [add] returns `false`.
 * - Non-finite values (NaN, ±Infinity) are stored as `null` (lost sample).
 *
 * Not thread-safe: confine an instance to one thread/dispatcher. Publish [snapshot] to UI state.
 */
class LiveRingBuffer(val capacity: Int = LIVE_SERIES_DEFAULT_CAPACITY) {
    init { require(capacity > 0) { "capacity" } }

    private val times = LongArray(capacity)
    private val values = arrayOfNulls<Double>(capacity)
    private var head = 0 // index of the oldest sample
    private var count = 0

    val size: Int get() = count
    fun isEmpty(): Boolean = count == 0

    /** Last accepted sample, or `null` when empty. */
    val last: LiveSample? get() = if (count == 0) null else sampleAt(count - 1)

    /** Adds a sample; returns `false` when it was dropped as out of order/duplicate/negative. */
    fun add(sample: LiveSample): Boolean {
        if (sample.elapsedMs < 0) return false
        if (count > 0 && sample.elapsedMs <= times[physical(count - 1)]) return false
        val value = sample.value?.takeIf { it.isFinite() }
        if (count < capacity) {
            val index = physical(count)
            times[index] = sample.elapsedMs; values[index] = value
            count++
        } else {
            times[head] = sample.elapsedMs; values[head] = value
            head = (head + 1) % capacity
        }
        return true
    }

    fun add(elapsedMs: Long, value: Double?): Boolean = add(LiveSample(elapsedMs, value))

    fun clear() {
        head = 0; count = 0
        values.fill(null)
    }

    /** Immutable copy ordered from oldest to newest. */
    fun snapshot(): List<LiveSample> = List(count) { sampleAt(it) }

    private fun physical(logical: Int): Int = (head + logical) % capacity
    private fun sampleAt(logical: Int): LiveSample = physical(logical).let { LiveSample(times[it], values[it]) }
}

/**
 * Statistics over a window of samples.
 *
 * @property last value of the newest sample in the window (`null` = the newest sample was lost or window empty).
 * @property lastElapsedMs timestamp of the newest sample in the window.
 * @property min minimum over valid (non-null) samples; `null` without valid samples.
 * @property avg arithmetic mean over valid samples; `null` without valid samples.
 * @property max maximum over valid samples; `null` without valid samples.
 * @property sampleCount all samples in the window, including lost ones.
 * @property validCount samples with a value.
 * @property lossPercent lost / all × 100 (0–100); `null` for an empty window.
 * @property jitter mean absolute difference between consecutive valid samples, in the unit of the
 *   series (ms for latency). Lost samples between two valid ones are skipped, not treated as 0.
 *   `null` with fewer than two valid samples.
 */
data class LiveStats(
    val last: Double?,
    val lastElapsedMs: Long?,
    val min: Double?,
    val avg: Double?,
    val max: Double?,
    val sampleCount: Int,
    val validCount: Int,
    val lossPercent: Double?,
    val jitter: Double?,
) {
    companion object {
        val EMPTY = LiveStats(null, null, null, null, null, 0, 0, null, null)
    }
}

/**
 * Computes [LiveStats]. With [windowMs] only samples with
 * `elapsedMs > newestElapsedMs - windowMs` are included (window anchored at the newest sample,
 * so a paused screen keeps its last statistics). `windowMs == null` uses all samples.
 */
fun liveStats(samples: List<LiveSample>, windowMs: Long? = null): LiveStats {
    if (samples.isEmpty()) return LiveStats.EMPTY
    require(windowMs == null || windowMs > 0) { "windowMs" }
    val newest = samples.last().elapsedMs
    val window = if (windowMs == null) samples else samples.filter { it.elapsedMs > newest - windowMs }
    if (window.isEmpty()) return LiveStats.EMPTY
    val valid = window.mapNotNull { it.value }
    var jitterSum = 0.0
    for (i in 1 until valid.size) jitterSum += abs(valid[i] - valid[i - 1])
    return LiveStats(
        last = window.last().value,
        lastElapsedMs = window.last().elapsedMs,
        min = valid.minOrNull(),
        avg = if (valid.isEmpty()) null else valid.sum() / valid.size,
        max = valid.maxOrNull(),
        sampleCount = window.size,
        validCount = valid.size,
        lossPercent = (window.size - valid.size) * 100.0 / window.size,
        jitter = if (valid.size < 2) null else jitterSum / (valid.size - 1),
    )
}

/**
 * Immutable published series: samples oldest → newest plus statistics over the whole buffer.
 * UI draws [samples]; a gap in timestamps larger than ~1.5 × sampling period means "not sampled"
 * (screen paused), whereas a `null` value means "sampled, but lost".
 */
data class LiveSeries(
    val samples: List<LiveSample> = emptyList(),
    val capacity: Int = LIVE_SERIES_DEFAULT_CAPACITY,
    val stats: LiveStats = LiveStats.EMPTY,
) {
    companion object {
        fun of(buffer: LiveRingBuffer): LiveSeries = buffer.snapshot().let { LiveSeries(it, buffer.capacity, liveStats(it)) }
    }
}

// --- Signal quality -------------------------------------------------------------------------

/** Lower bound (inclusive) of [SignalQuality.EXCELLENT]. */
const val SIGNAL_EXCELLENT_MIN_DBM = -55
/** Lower bound (inclusive) of [SignalQuality.GOOD]; typical minimum for VoIP/video. */
const val SIGNAL_GOOD_MIN_DBM = -67
/** Lower bound (inclusive) of [SignalQuality.FAIR]; below this the link is [SignalQuality.POOR]. */
const val SIGNAL_FAIR_MIN_DBM = -75
/** Android `WifiInfo.INVALID_RSSI`. */
const val WIFI_INVALID_RSSI = -127
/** Plausible RSSI range; values outside are treated as unknown. */
val WIFI_VALID_RSSI_RANGE = -126..-1

enum class SignalQuality { EXCELLENT, GOOD, FAIR, POOR, UNKNOWN }

/**
 * Classifies RSSI: EXCELLENT ≥ [SIGNAL_EXCELLENT_MIN_DBM], GOOD ≥ [SIGNAL_GOOD_MIN_DBM],
 * FAIR ≥ [SIGNAL_FAIR_MIN_DBM], otherwise POOR. `null`, [WIFI_INVALID_RSSI] and values outside
 * [WIFI_VALID_RSSI_RANGE] are UNKNOWN.
 */
fun signalQuality(rssiDbm: Int?): SignalQuality = when {
    rssiDbm == null || rssiDbm !in WIFI_VALID_RSSI_RANGE -> SignalQuality.UNKNOWN
    rssiDbm >= SIGNAL_EXCELLENT_MIN_DBM -> SignalQuality.EXCELLENT
    rssiDbm >= SIGNAL_GOOD_MIN_DBM -> SignalQuality.GOOD
    rssiDbm >= SIGNAL_FAIR_MIN_DBM -> SignalQuality.FAIR
    else -> SignalQuality.POOR
}

/** RSSI if plausible, otherwise `null` (e.g. `WifiInfo.INVALID_RSSI`). */
fun validRssiDbm(rssiDbm: Int?): Int? = rssiDbm?.takeIf { it in WIFI_VALID_RSSI_RANGE }

// --- Throughput ----------------------------------------------------------------------------

/** Android `TrafficStats.UNSUPPORTED`. */
const val TRAFFIC_STATS_UNSUPPORTED = -1L

/** Cumulative byte counter reading at a monotonic time. */
data class ByteCounterSample(val elapsedMs: Long, val bytes: Long)

/**
 * Rate in Mb/s (10^6 bit/s) between two counter readings, or `null` when not computable:
 * missing previous reading, any counter negative ([TRAFFIC_STATS_UNSUPPORTED]), interval ≤ 0,
 * or the counter decreased (reset/wrap — a gap, never a negative rate).
 */
fun throughputMbps(previous: ByteCounterSample?, current: ByteCounterSample): Double? {
    if (previous == null || previous.bytes < 0 || current.bytes < 0) return null
    val intervalMs = current.elapsedMs - previous.elapsedMs
    if (intervalMs <= 0) return null
    val delta = current.bytes - previous.bytes
    if (delta < 0) return null
    return delta * 8.0 / (intervalMs * 1_000.0)
}

/**
 * Stateful rate meter over one cumulative counter.
 *
 * - First reading, reset (counter decrease) and a reading after [reset] return `null` and become the new baseline.
 * - An unsupported/negative counter returns `null` and clears the baseline.
 * - A reading with zero interval returns `null` and keeps the old baseline; a reading with a
 *   negative interval (clock went back) returns `null` and becomes the baseline.
 */
class ByteRateMeter {
    private var baseline: ByteCounterSample? = null

    /**
     * `true` when the next valid reading can yield a rate. Callers use it to tell "first reading
     * after start/reset" (no sample to plot) from "counter reset mid-run" (a gap sample).
     */
    val hasBaseline: Boolean get() = baseline != null

    fun next(elapsedMs: Long, bytes: Long): Double? {
        val current = ByteCounterSample(elapsedMs, bytes)
        if (bytes < 0) { baseline = null; return null }
        val previous = baseline
        if (previous != null && elapsedMs == previous.elapsedMs) return null
        baseline = current
        return throughputMbps(previous, current)
    }

    fun reset() { baseline = null }
}

// --- Roaming -------------------------------------------------------------------------------

/** Android redacted BSSID returned without location access. */
const val WIFI_REDACTED_BSSID = "02:00:00:00:00:00"

/** Normalized BSSID usable as an AP identity, or `null` for absent/redacted values. */
fun usableBssid(value: String?): String? = normalizeBssid(value)?.takeUnless { it == WIFI_REDACTED_BSSID }

/** Default cap of retained roaming events. */
const val ROAMING_EVENT_LIMIT = 50

/**
 * The connected BSSID changed between two link snapshots.
 *
 * @property afterGap `true` when the change was not observed directly: between the two known
 *   BSSIDs there were snapshots without a usable BSSID (disconnect, redaction) or sampling was
 *   paused. Such an event can be a reconnect rather than a seamless roam.
 */
data class RoamingEvent(
    val elapsedMs: Long,
    val fromBssid: String,
    val toBssid: String,
    val fromFrequencyMhz: Int?,
    val toFrequencyMhz: Int?,
    val afterGap: Boolean,
)

/**
 * Detects roaming from consecutive (bssid, frequency) snapshots. Compares normalized BSSIDs
 * ([normalizeBssid]); absent/redacted BSSIDs never produce an event and mark a gap. Snapshots
 * with a timestamp not greater than the previous one are ignored. Keeps at most [maxEvents]
 * newest events. Not thread-safe.
 */
class RoamingDetector(val maxEvents: Int = ROAMING_EVENT_LIMIT) {
    init { require(maxEvents > 0) { "maxEvents" } }

    private var lastBssid: String? = null
    private var lastFrequencyMhz: Int? = null
    private var lastElapsedMs: Long? = null
    private var gap = false
    private val retained = ArrayDeque<RoamingEvent>()

    /** Events oldest → newest. */
    val events: List<RoamingEvent> get() = retained.toList()

    /** Returns the new event, if this snapshot completed a BSSID change. */
    fun observe(elapsedMs: Long, bssid: String?, frequencyMhz: Int?): RoamingEvent? {
        lastElapsedMs?.let { if (elapsedMs <= it) return null }
        lastElapsedMs = elapsedMs
        val current = usableBssid(bssid)
        if (current == null) { if (lastBssid != null) gap = true; return null }
        val previous = lastBssid
        val previousFrequency = lastFrequencyMhz
        lastBssid = current
        lastFrequencyMhz = frequencyMhz
        val wasGap = gap
        gap = false
        if (previous == null || previous == current) return null
        val event = RoamingEvent(elapsedMs, previous, current, previousFrequency, frequencyMhz, wasGap)
        retained.addLast(event)
        while (retained.size > maxEvents) retained.removeFirst()
        return event
    }

    /** Sampling paused/resumed: the next BSSID change is reported with `afterGap = true`. */
    fun markGap() { if (lastBssid != null) gap = true }

    /** Forget history and the baseline BSSID. */
    fun clear() {
        lastBssid = null; lastFrequencyMhz = null; lastElapsedMs = null; gap = false
        retained.clear()
    }
}

// --- Chart timeline and events -----------------------------------------------------------------

/**
 * Common x axis of the live charts. Each series is sampled by its own loop with its own
 * timestamps, but a chart draws all its lines and events by one index. The axis follows the
 * master series (the 1 Hz link reading, which adds a sample every tick, `null` without Wi-Fi):
 * one slot per master sample. Wherever the master timestamps jump by more than 1.5 × [intervalMs]
 * (sampling paused, e.g. while another tab was open), the jump is filled with empty slots (`null`
 * time), one per missed interval, so the axis stays uniform in time: slot `i` is
 * `(size − 1 − i) × intervalMs` before the newest sample, as the chart labels, the scrub bubble,
 * event times and window statistics assume. Lines break across the pause instead of bridging it.
 * The timeline keeps at most `maxSlots` newest slots, so a pause longer than the history leaves
 * only empty slots before the samples taken since.
 *
 * Indices follow the master buffer: when its oldest sample is evicted, every slot moves one to
 * the left, and [chartEvents] moves and drops events the same way.
 */
class LiveTimeline private constructor(
    /** Slot times oldest → newest; `null` = pause marker. */
    val slots: List<Long?>,
    val intervalMs: Long,
) {
    private val timedSlots: IntArray = slots.indices.filter { slots[it] != null }.toIntArray()
    private val times: LongArray = LongArray(timedSlots.size) { slots[timedSlots[it]]!! }

    val size: Int get() = slots.size

    /**
     * Projects [samples] (oldest → newest, strictly increasing times) onto the slots. A slot takes
     * the next unused sample within ±0.75 × [intervalMs] of its time, in order, so two loops with
     * slightly different phase still pair one to one. A `null` sample value (lost) stays `null`.
     *
     * A single slot without a sample between two paired slots, and the newest slot while its
     * sample is still in flight, repeat the previous value: that is phase drift between the loops,
     * not a lost sample. Other slots without a nearby sample are `null` (series not sampled then).
     */
    fun alignedValues(samples: List<LiveSample>): List<Float?> {
        val n = slots.size
        val out = arrayOfNulls<Float>(n)
        val paired = BooleanArray(n)
        val tolerance = intervalMs * 3 / 4
        var next = 0
        for (i in 0 until n) {
            val time = slots[i] ?: continue
            while (next < samples.size && samples[next].elapsedMs < time - tolerance) next++
            if (next < samples.size && samples[next].elapsedMs <= time + tolerance) {
                out[i] = samples[next].value?.toFloat()
                paired[i] = true
                next++
            }
        }
        for (i in 1 until n) {
            if (paired[i] || slots[i] == null || !paired[i - 1]) continue
            if (i == n - 1 || paired[i + 1]) out[i] = out[i - 1]
        }
        return out.asList()
    }

    /**
     * Places [events] on the nearest timed slot within 1.5 × [intervalMs]; events farther from
     * every slot (older than the history) are dropped. Output keeps the input order.
     */
    fun chartEvents(events: List<LiveEvent>): List<ChartEvent> {
        if (times.isEmpty()) return emptyList()
        val tolerance = intervalMs * 3 / 2
        return events.mapNotNull { event ->
            val nearest = nearestTime(event.elapsedMs)
            if (abs(times[nearest] - event.elapsedMs) > tolerance) null
            else ChartEvent(timedSlots[nearest], event.kind, event.detail)
        }
    }

    private fun nearestTime(elapsedMs: Long): Int {
        var low = 0
        var high = times.size - 1
        while (low < high) {
            val mid = (low + high) ushr 1
            if (times[mid] < elapsedMs) low = mid + 1 else high = mid
        }
        // low = first time >= elapsedMs (or the last one); its predecessor may be closer.
        return if (low > 0 && elapsedMs - times[low - 1] <= abs(times[low] - elapsedMs)) low - 1 else low
    }

    companion object {
        val EMPTY = LiveTimeline(emptyList(), LIVE_SAMPLE_INTERVAL_MS)

        /**
         * Timeline of [master] samples with empty slots for pauses, at most [maxSlots] newest
         * slots; see [LiveTimeline].
         */
        fun of(
            master: List<LiveSample>, intervalMs: Long = LIVE_SAMPLE_INTERVAL_MS,
            maxSlots: Int = LIVE_SERIES_DEFAULT_CAPACITY,
        ): LiveTimeline {
            require(intervalMs > 0) { "intervalMs" }
            require(maxSlots > 0) { "maxSlots" }
            val slots = ArrayList<Long?>(master.size + 4)
            var previous: Long? = null
            for (sample in master) {
                previous?.let { slots.addEmpty(pauseSlots(sample.elapsedMs - it, intervalMs, maxSlots)) }
                slots.add(sample.elapsedMs)
                previous = sample.elapsedMs
            }
            val kept = if (slots.size > maxSlots) slots.subList(slots.size - maxSlots, slots.size).toList() else slots
            return LiveTimeline(kept, intervalMs)
        }

        /**
         * Empty slots for a jump of [deltaMs] between two master samples: none for normal ticks
         * (up to 1.5 intervals, phase drift), otherwise one per missed interval, at most [maxSlots].
         */
        internal fun pauseSlots(deltaMs: Long, intervalMs: Long, maxSlots: Int): Int {
            if (deltaMs <= intervalMs * 3 / 2) return 0
            val intervals = (deltaMs + intervalMs / 2) / intervalMs // rounded
            return (intervals - 1).coerceIn(1L, maxSlots.toLong()).toInt()
        }

        private fun ArrayList<Long?>.addEmpty(count: Int) { repeat(count) { add(null) } }
    }
}

/**
 * Something worth marking in the live charts, at a monotonic time (placed on a slot by
 * [LiveTimeline.chartEvents]). [detail] is language-neutral ("36 → 100", "1.1.1.1 ×3").
 */
data class LiveEvent(val elapsedMs: Long, val kind: ChartEventKind, val detail: String? = null)

/** Latency target whose echoes can be lost. */
enum class LiveProbe { GATEWAY, INTERNET }

/** Default cap of retained chart events (15 min of history rarely holds more). */
const val LIVE_EVENT_LIMIT = 200

/** "…:ED:BD": the last two octets tell the APs of one deployment apart. */
fun shortBssid(bssid: String): String = "…:" + bssid.split(':').takeLast(2).joinToString(":").uppercase()

/**
 * Collects chart events of the live screen, oldest → newest, at most [maxEvents]:
 * - [ChartEventKind.ROAMING] for every [RoamingEvent] (BSSID change);
 * - [ChartEventKind.CHANNEL_CHANGE] when the channel changes while the AP stays the same. With a
 *   redacted BSSID (no location permission) the AP is unknown, so a channel change is reported
 *   even if it was a roam to an AP on another channel;
 * - [ChartEventKind.PACKET_LOSS] once per run of consecutive lost echoes of one target; the run
 *   updates its detail ("1.1.1.1 ×3") instead of adding an event per second.
 *
 * Not thread-safe. [prune] drops events older than the chart history.
 */
class LiveEventLog(val maxEvents: Int = LIVE_EVENT_LIMIT) {
    init { require(maxEvents > 0) { "maxEvents" } }

    private val retained = ArrayDeque<LiveEvent>()
    private var lastChannel: Int? = null
    private var lastBssid: String? = null
    private val runs = HashMap<LiveProbe, LossRun>()

    private class LossRun(val event: LiveEvent, val count: Int)

    /** Events oldest → newest. */
    val events: List<LiveEvent> get() = retained.toList()

    /**
     * One link reading. [channel] `null` = no link (keeps the baseline, so a reconnect to the
     * same AP on another channel still counts). [roam] is what [RoamingDetector.observe]
     * returned for the same reading.
     */
    fun observeLink(elapsedMs: Long, bssid: String?, channel: Int?, roam: RoamingEvent?) {
        val current = usableBssid(bssid)
        if (roam != null) {
            add(LiveEvent(elapsedMs, ChartEventKind.ROAMING, "${shortBssid(roam.fromBssid)} → ${shortBssid(roam.toBssid)}"))
        } else if (channel != null) {
            val previous = lastChannel
            val sameAp = current == null || lastBssid == null || current == lastBssid
            if (previous != null && previous != channel && sameAp) {
                add(LiveEvent(elapsedMs, ChartEventKind.CHANNEL_CHANGE, "$previous → $channel"))
            }
        }
        if (channel != null) lastChannel = channel
        if (current != null) lastBssid = current
    }

    /**
     * One probe attempt of [probe]. [lost] = the echo got no reply; any other outcome (reply, no
     * Wi-Fi, no target) ends the current loss run.
     */
    fun observeProbe(probe: LiveProbe, elapsedMs: Long, target: String?, lost: Boolean) {
        if (!lost) { runs.remove(probe); return }
        val run = runs[probe]
        val index = run?.let { r -> retained.indexOfLast { it === r.event } } ?: -1
        if (run == null || index < 0) {
            val event = LiveEvent(elapsedMs, ChartEventKind.PACKET_LOSS, target)
            add(event)
            runs[probe] = LossRun(event, 1)
        } else {
            val count = run.count + 1
            val event = run.event.copy(detail = listOfNotNull(target, "×$count").joinToString(" "))
            retained[index] = event
            runs[probe] = LossRun(event, count)
        }
    }

    /** Drops events before [oldestElapsedMs] (oldest sample still in the history; `null` = none). */
    fun prune(oldestElapsedMs: Long?) {
        if (oldestElapsedMs == null) { retained.clear(); return }
        while (retained.isNotEmpty() && retained.first().elapsedMs < oldestElapsedMs) retained.removeFirst()
    }

    /** Forgets events, loss runs and the channel/BSSID baseline. */
    fun clear() {
        retained.clear(); runs.clear()
        lastChannel = null; lastBssid = null
    }

    private fun add(event: LiveEvent) {
        retained.addLast(event)
        while (retained.size > maxEvents) retained.removeFirst()
    }
}

// --- Targets -------------------------------------------------------------------------------

/** Default internet latency target (numeric, no DNS). */
const val LIVE_DEFAULT_INTERNET_TARGET = "1.1.1.1"
/** Default DNS latency probe name. */
const val LIVE_DEFAULT_DNS_NAME = "one.one.one.one"

/**
 * Validates a user-entered internet ICMP target. Only strict dotted IPv4 is accepted (no DNS);
 * unspecified `0.0.0.0/8`, loopback `127.0.0.0/8` and multicast/reserved/broadcast `224.0.0.0/3`
 * are rejected. Returns the canonical text, or `null`.
 */
fun validLiveInternetTarget(input: String): String? {
    val value = ipv4Number(input.trim()) ?: return null
    val first = (value shr 24).toInt()
    if (first == 0 || first == 127 || first >= 224) return null
    return ipv4Text(value)
}

/**
 * Validates a DNS probe name: ASCII hostname, total ≤ 253, labels 1–63 of letters/digits/hyphen
 * without leading/trailing hyphen, optional trailing dot, at least two labels, not an IPv4
 * literal. Returns the lowercase name without trailing dot, or `null`.
 */
fun validLiveDnsName(input: String): String? {
    val name = input.trim().removeSuffix(".").lowercase()
    if (name.isEmpty() || name.length > 253 || ipv4Number(name) != null) return null
    val labels = name.split('.')
    if (labels.size < 2) return null
    val ok = labels.all { label ->
        label.length in 1..63 && label.first() != '-' && label.last() != '-' &&
            label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
    }
    if (!ok || labels.last().all { it in '0'..'9' }) return null
    return name
}

/**
 * Delay until the next fixed-rate tick. Ticks are anchored at [startedAtMs] + [periodMs]; when
 * the work overran, the schedule skips to "now" rather than bursting to catch up.
 */
fun liveTickDelayMs(startedAtMs: Long, nowMs: Long, periodMs: Long = LIVE_SAMPLE_INTERVAL_MS): Long {
    require(periodMs > 0)
    return (startedAtMs + periodMs - nowMs).coerceIn(0, periodMs)
}
