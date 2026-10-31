package cz.netkit.shared.speed

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeedMeasureTest {
    private fun assertClose(expected: Double, actual: Double?, eps: Double = 1e-6) {
        assertTrue(actual != null && abs(expected - actual) < eps, "expected $expected, was $actual")
    }

    /** Constant [mbps] from t = 0, sampled every [stepMs] up to [endMs]. */
    private fun constant(mbps: Double, endMs: Long = 10_000, stepMs: Long = 250): List<ByteSample> {
        val bytesPerMs = mbps * 1_000_000 / 8 / 1_000
        return (stepMs..endMs step stepMs).map { ByteSample(it, (it * bytesPerMs).toLong()) }
    }

    @Test fun megabitsUseDecimalUnits() {
        assertClose(1.0, megabitsPerSecond(125_000.0, 1_000.0))
        assertClose(800.0, megabitsPerSecond(100_000_000.0, 1_000.0))
        assertNull(megabitsPerSecond(1.0, 0.0))
        assertNull(megabitsPerSecond(-1.0, 10.0))
    }

    @Test fun traceRejectsNonMonotonicReadingsAndRespectsCapacity() {
        val trace = ThroughputTrace(maxSamples = 3)
        assertTrue(trace.add(250, 100))
        assertFalse(trace.add(250, 200), "same time")
        assertFalse(trace.add(500, 50), "decreasing bytes")
        assertTrue(trace.add(500, 100))
        assertTrue(trace.add(750, 300))
        assertFalse(trace.add(1_000, 400), "full")
        assertEquals(3, trace.size)
    }

    @Test fun bytesAtInterpolatesWithImplicitOrigin() {
        val samples = listOf(ByteSample(1_000, 1_000), ByteSample(2_000, 3_000))
        assertClose(500.0, bytesAt(samples, 500.0))
        assertClose(2_000.0, bytesAt(samples, 1_500.0))
        assertClose(3_000.0, bytesAt(samples, 5_000.0))
        assertClose(0.0, bytesAt(emptyList(), 1_000.0))
    }

    @Test fun constantRateSummaryMatchesRateAndUsesMinimumWarmup() {
        val summary = assertNotNull(summarizeThroughput(constant(400.0)))
        assertClose(400.0, summary.mbps, 1e-3)
        assertEquals(SPEED_WARMUP_MIN_MS, summary.warmupMs)
        assertEquals(8_000L, summary.measuredMs)
        assertClose(400.0, summary.peakMbps, 1e-2)
    }

    @Test fun slowStartIsExcludedFromTheMean() {
        // 0.5 s of nothing, then a ramp 0 → 500 Mbit/s over 2 s, then flat 500 Mbit/s.
        val samples = ArrayList<ByteSample>()
        var bytes = 0.0
        for (t in 250L..10_000L step 250L) {
            val rate = when {
                t <= 500 -> 0.0
                t <= 2_500 -> 500.0 * (t - 500) / 2_000.0
                else -> 500.0
            }
            bytes += rate * 1_000_000 / 8 / 1_000 * 250
            samples.add(ByteSample(t, bytes.toLong()))
        }
        val summary = assertNotNull(summarizeThroughput(samples))
        assertTrue(summary.warmupMs >= SPEED_WARMUP_MIN_MS)
        assertTrue(abs(summary.mbps - 500.0) < 15.0, "mean ${summary.mbps}")
        // A naive whole-run average would be far lower.
        val naive = assertNotNull(megabitsPerSecond(samples.last().bytes.toDouble(), 10_000.0))
        assertTrue(naive < 450.0)
    }

    @Test fun verySlowRampExtendsWarmupButNotBeyondCap() {
        // Linear ramp over the first 6 s (high BDP): warm-up grows above 2 s, capped at 4 s.
        val samples = ArrayList<ByteSample>()
        var bytes = 0.0
        for (t in 250L..10_000L step 250L) {
            val rate = if (t < 6_000) 300.0 * t / 6_000.0 else 300.0
            bytes += rate * 1_000_000 / 8 / 1_000 * 250
            samples.add(ByteSample(t, bytes.toLong()))
        }
        val warmup = detectWarmupMs(samples)
        assertTrue(warmup in 2_250L..SPEED_WARMUP_MAX_MS, "warmup $warmup")
    }

    @Test fun shortDirectionScalesWarmupDown() {
        // A server that closes after 3 s still yields a value with a 20 % warm-up.
        val summary = assertNotNull(summarizeThroughput(constant(100.0, endMs = 3_000)))
        assertEquals(600L, summary.warmupMs)
        assertClose(100.0, summary.mbps, 1e-2)
        assertNull(summarizeThroughput(constant(100.0, endMs = 1_000)), "less than 1 s measured")
        assertNull(summarizeThroughput(emptyList()))
    }

    @Test fun rateSeriesIsAMovingWindow() {
        val samples = listOf(ByteSample(500, 0), ByteSample(1_000, 0), ByteSample(1_500, 125_000), ByteSample(2_000, 125_000))
        val rates = rateSeriesMbps(samples, 1_000)
        assertClose(0.0, rates[1])
        assertClose(1.0, rates[2])
        assertClose(1.0, rates[3])
    }

    @Test fun latencyStatsUseMedianAndMeanAbsoluteDifference() {
        val stats = latencyStats(listOf(10.0, 12.0, null, 11.0, 15.0))
        assertEquals(5, stats.sent)
        assertEquals(1, stats.lost)
        assertClose(11.5, stats.medianMs)
        // |12−10| + |11−12| + |15−11| = 7 over 3 differences.
        assertClose(7.0 / 3.0, stats.jitterMs)
        assertClose(10.0, stats.minMs)
        assertClose(15.0, stats.maxMs)
        val single = latencyStats(listOf(5.0))
        assertNull(single.jitterMs)
        val none = latencyStats(listOf(null, null))
        assertNull(none.medianMs)
        assertEquals(2, none.lost)
        assertNull(latencyStats(listOf(Double.NaN)).medianMs)
    }

    @Test fun medianOddEvenEmpty() {
        assertClose(2.0, median(listOf(3.0, 1.0, 2.0)))
        assertClose(2.5, median(listOf(4.0, 1.0, 2.0, 3.0)))
        assertNull(median(emptyList()))
    }

    @Test fun bufferbloatGradesAndIncrease() {
        val idle = latencyStats(listOf(10.0, 10.0, 10.0))
        val loaded = latencyStats(listOf(50.0, 60.0, 70.0))
        assertClose(50.0, latencyIncreaseMs(idle, loaded))
        assertClose(0.0, latencyIncreaseMs(loaded, idle), 1e-9)
        assertNull(latencyIncreaseMs(null, loaded))
        assertEquals(BufferbloatGrade.A_PLUS, bufferbloatGrade(2.0))
        assertEquals(BufferbloatGrade.A, bufferbloatGrade(5.0))
        assertEquals(BufferbloatGrade.B, bufferbloatGrade(50.0))
        assertEquals(BufferbloatGrade.C, bufferbloatGrade(150.0))
        assertEquals(BufferbloatGrade.D, bufferbloatGrade(399.0))
        assertEquals(BufferbloatGrade.F, bufferbloatGrade(400.0))
    }

    @Test fun niceChartTopRoundsUp() {
        assertClose(100.0, niceChartTop(10.0, 100.0))
        assertClose(500.0, niceChartTop(420.0, 100.0))
        assertClose(250.0, niceChartTop(210.0, 100.0))
        assertClose(1_000.0, niceChartTop(900.0, 100.0))
        assertClose(2_000.0, niceChartTop(910.0, 100.0))
        assertClose(100.0, niceChartTop(Double.NaN, 100.0))
    }

    @Test fun chartPointsAreLeftAlignedAndPadded() {
        val points = chartPoints(constant(80.0, endMs = 2_000), capacity = 20)
        assertEquals(20, points.size)
        assertNotNull(points[0])
        assertNotNull(points[7])
        assertNull(points[8])
        assertClose(80.0, points[7], 1e-2)
        val gap = chartPoints(listOf(ByteSample(250, 1_000), ByteSample(750, 3_000)), capacity = 4)
        assertNotNull(gap[1], "single missing slot is filled")
        assertTrue(chartPoints(emptyList(), capacity = 3).all { it == null })
    }
}
