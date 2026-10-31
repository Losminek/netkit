package cz.netkit.shared.wifi

import cz.netkit.shared.chart.ChartEvent
import cz.netkit.shared.chart.ChartEventKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveTelemetryTest {
    private fun assertClose(expected: Double, actual: Double?, eps: Double = 1e-9) {
        assertTrue(actual != null && kotlin.math.abs(expected - actual) < eps, "expected $expected, was $actual")
    }

    // --- Ring buffer -------------------------------------------------------------------------

    @Test fun ringBufferDefaultsToFifteenMinutesAtOneHertz() {
        assertEquals(900, LiveRingBuffer().capacity)
        assertEquals(900, LiveSeries().capacity)
        assertFailsWith<IllegalArgumentException> { LiveRingBuffer(0) }
    }

    @Test fun ringBufferEvictsOldestAtCapacity() {
        val buffer = LiveRingBuffer(3)
        for (t in 1L..5L) assertTrue(buffer.add(t * 1000, t.toDouble()))
        assertEquals(3, buffer.size)
        assertEquals(listOf(3000L, 4000L, 5000L), buffer.snapshot().map { it.elapsedMs })
        assertEquals(listOf(3.0, 4.0, 5.0), buffer.snapshot().map { it.value })
        assertEquals(LiveSample(5000, 5.0), buffer.last)
    }

    @Test fun ringBufferWrapsManyTimesInOrder() {
        val buffer = LiveRingBuffer(4)
        for (t in 1L..1003L) buffer.add(t, if (t % 7 == 0L) null else t.toDouble())
        assertEquals((1000L..1003L).toList(), buffer.snapshot().map { it.elapsedMs })
        // 1001 = 7 × 143 is a lost sample.
        assertEquals(listOf(1000.0, null, 1002.0, 1003.0), buffer.snapshot().map { it.value })
    }

    @Test fun ringBufferDropsOutOfOrderDuplicateAndNegativeTimestamps() {
        val buffer = LiveRingBuffer(5)
        assertFalse(buffer.add(-1, 1.0))
        assertTrue(buffer.add(1000, 1.0))
        assertFalse(buffer.add(1000, 2.0), "duplicate")
        assertFalse(buffer.add(999, 3.0), "out of order")
        assertTrue(buffer.add(1001, null))
        assertEquals(listOf(LiveSample(1000, 1.0), LiveSample(1001, null)), buffer.snapshot())
    }

    @Test fun ringBufferStoresNonFiniteAsLostAndClears() {
        val buffer = LiveRingBuffer(3)
        buffer.add(1, Double.NaN)
        buffer.add(2, Double.POSITIVE_INFINITY)
        assertEquals(listOf(null, null), buffer.snapshot().map { it.value })
        buffer.clear()
        assertTrue(buffer.isEmpty())
        assertNull(buffer.last)
        assertTrue(buffer.add(1, 1.0), "timestamps restart after clear")
    }

    @Test fun snapshotIsIndependentOfLaterWrites() {
        val buffer = LiveRingBuffer(2)
        buffer.add(1, 1.0)
        val snapshot = buffer.snapshot()
        buffer.add(2, 2.0); buffer.add(3, 3.0)
        assertEquals(listOf(LiveSample(1, 1.0)), snapshot)
        val series = LiveSeries.of(buffer)
        assertEquals(2, series.capacity)
        assertEquals(2, series.stats.sampleCount)
    }

    // --- Stats -------------------------------------------------------------------------------

    @Test fun emptyStats() {
        assertEquals(LiveStats.EMPTY, liveStats(emptyList()))
        assertNull(LiveStats.EMPTY.lossPercent)
    }

    @Test fun statsWithLossAndJitterSkippingNulls() {
        val samples = listOf(
            LiveSample(1000, 10.0), LiveSample(2000, null), LiveSample(3000, 14.0),
            LiveSample(4000, 12.0), LiveSample(5000, null),
        )
        val stats = liveStats(samples)
        assertNull(stats.last, "newest sample was lost")
        assertEquals(5000, stats.lastElapsedMs)
        assertEquals(10.0, stats.min)
        assertEquals(14.0, stats.max)
        assertClose(12.0, stats.avg)
        assertEquals(5, stats.sampleCount)
        assertEquals(3, stats.validCount)
        assertClose(40.0, stats.lossPercent)
        // |14-10| + |12-14| over 2 consecutive valid pairs.
        assertClose(3.0, stats.jitter)
    }

    @Test fun statsAllLostAndSingleValid() {
        val lost = liveStats(listOf(LiveSample(1, null), LiveSample(2, null)))
        assertClose(100.0, lost.lossPercent)
        assertNull(lost.avg); assertNull(lost.min); assertNull(lost.max); assertNull(lost.jitter)
        val single = liveStats(listOf(LiveSample(1, 5.0)))
        assertClose(0.0, single.lossPercent)
        assertNull(single.jitter)
        assertEquals(5.0, single.last)
    }

    @Test fun statsWindowIsAnchoredAtNewestSample() {
        val samples = (1L..10L).map { LiveSample(it * 1000, it.toDouble()) }
        val window = liveStats(samples, windowMs = 3_000)
        assertEquals(3, window.sampleCount)
        assertEquals(8.0, window.min)
        assertEquals(10.0, window.last)
        assertFailsWith<IllegalArgumentException> { liveStats(samples, windowMs = 0) }
    }

    // --- Quality -----------------------------------------------------------------------------

    @Test fun qualityThresholdsAtBoundaries() {
        assertEquals(SignalQuality.EXCELLENT, signalQuality(-30))
        assertEquals(SignalQuality.EXCELLENT, signalQuality(SIGNAL_EXCELLENT_MIN_DBM))
        assertEquals(SignalQuality.GOOD, signalQuality(SIGNAL_EXCELLENT_MIN_DBM - 1))
        assertEquals(SignalQuality.GOOD, signalQuality(SIGNAL_GOOD_MIN_DBM))
        assertEquals(SignalQuality.FAIR, signalQuality(SIGNAL_GOOD_MIN_DBM - 1))
        assertEquals(SignalQuality.FAIR, signalQuality(SIGNAL_FAIR_MIN_DBM))
        assertEquals(SignalQuality.POOR, signalQuality(SIGNAL_FAIR_MIN_DBM - 1))
        assertEquals(SignalQuality.POOR, signalQuality(-126))
        assertEquals(-55, SIGNAL_EXCELLENT_MIN_DBM)
        assertEquals(-67, SIGNAL_GOOD_MIN_DBM)
        assertEquals(-75, SIGNAL_FAIR_MIN_DBM)
    }

    @Test fun invalidRssiIsUnknown() {
        assertEquals(SignalQuality.UNKNOWN, signalQuality(null))
        assertEquals(SignalQuality.UNKNOWN, signalQuality(WIFI_INVALID_RSSI))
        assertEquals(SignalQuality.UNKNOWN, signalQuality(0))
        assertEquals(SignalQuality.UNKNOWN, signalQuality(12))
        assertNull(validRssiDbm(-127))
        assertEquals(-60, validRssiDbm(-60))
    }

    // --- Throughput --------------------------------------------------------------------------

    @Test fun throughputBetweenSamples() {
        // 1 250 000 B in 1 s = 10 Mb/s.
        assertClose(10.0, throughputMbps(ByteCounterSample(1000, 0), ByteCounterSample(2000, 1_250_000)))
        // Same bytes in 2 s = 5 Mb/s.
        assertClose(5.0, throughputMbps(ByteCounterSample(1000, 0), ByteCounterSample(3000, 1_250_000)))
        assertClose(0.0, throughputMbps(ByteCounterSample(1000, 5), ByteCounterSample(2000, 5)))
        assertNull(throughputMbps(null, ByteCounterSample(1000, 5)))
    }

    @Test fun throughputResetUnsupportedAndBadInterval() {
        assertNull(throughputMbps(ByteCounterSample(1000, 500), ByteCounterSample(2000, 100)), "reset is a gap")
        assertNull(throughputMbps(ByteCounterSample(1000, TRAFFIC_STATS_UNSUPPORTED), ByteCounterSample(2000, 100)))
        assertNull(throughputMbps(ByteCounterSample(1000, 100), ByteCounterSample(2000, TRAFFIC_STATS_UNSUPPORTED)))
        assertNull(throughputMbps(ByteCounterSample(1000, 100), ByteCounterSample(1000, 200)), "zero interval")
        assertNull(throughputMbps(ByteCounterSample(2000, 100), ByteCounterSample(1000, 200)), "negative interval")
    }

    @Test fun rateMeterRebaselinesAfterResetAndUnsupported() {
        val meter = ByteRateMeter()
        assertFalse(meter.hasBaseline)
        assertNull(meter.next(1000, 0), "first reading is baseline")
        assertTrue(meter.hasBaseline)
        assertClose(8.0, meter.next(2000, 1_000_000))
        assertNull(meter.next(2000, 9_000_000), "zero interval keeps baseline")
        assertClose(8.0, meter.next(3000, 2_000_000))
        assertNull(meter.next(4000, 10), "counter reset")
        assertClose(0.008, meter.next(5000, 1_010))
        assertNull(meter.next(6000, TRAFFIC_STATS_UNSUPPORTED))
        assertFalse(meter.hasBaseline)
        assertNull(meter.next(7000, 2_000), "baseline cleared by unsupported")
        assertClose(0.008, meter.next(8000, 3_000))
        meter.reset()
        assertNull(meter.next(9000, 100_000_000), "reset after pause")
    }

    // --- Roaming -----------------------------------------------------------------------------

    @Test fun roamingDetectedOnNormalizedBssidChangeOnly() {
        val detector = RoamingDetector()
        assertNull(detector.observe(1000, "AA:BB:CC:00:00:01", 5180))
        assertNull(detector.observe(2000, " aa:bb:cc:00:00:01 ", 5200), "same BSSID, other case/whitespace and frequency")
        val event = detector.observe(3000, "AA:BB:CC:00:00:02", 2437)
        assertEquals(RoamingEvent(3000, "aa:bb:cc:00:00:01", "aa:bb:cc:00:00:02", 5200, 2437, afterGap = false), event)
        assertEquals(listOf(event), detector.events)
    }

    @Test fun roamingIgnoresRedactedAndMarksGap() {
        val detector = RoamingDetector()
        assertNull(detector.observe(1000, null, null), "no baseline yet")
        assertNull(detector.observe(2000, "aa:aa:aa:aa:aa:01", 2412))
        assertNull(detector.observe(3000, WIFI_REDACTED_BSSID, 2412))
        assertNull(detector.observe(4000, null, null))
        val event = detector.observe(5000, "aa:aa:aa:aa:aa:02", 5180)
        assertTrue(event!!.afterGap)
        assertFalse(detector.observe(6000, "aa:aa:aa:aa:aa:03", 5180)!!.afterGap)
        detector.markGap()
        assertTrue(detector.observe(7000, "aa:aa:aa:aa:aa:04", 5180)!!.afterGap)
        assertNull(usableBssid(WIFI_REDACTED_BSSID))
        assertNull(usableBssid("  "))
    }

    @Test fun roamingIgnoresOutOfOrderSnapshots() {
        val detector = RoamingDetector()
        detector.observe(2000, "aa:aa:aa:aa:aa:01", 2412)
        assertNull(detector.observe(1000, "aa:aa:aa:aa:aa:02", 2412))
        assertNull(detector.observe(2000, "aa:aa:aa:aa:aa:02", 2412))
        assertTrue(detector.events.isEmpty())
    }

    @Test fun roamingEventsAreCappedKeepingNewest() {
        val detector = RoamingDetector()
        assertEquals(50, detector.maxEvents)
        for (i in 0..60) detector.observe(1000L + i, "aa:aa:aa:aa:aa:${(i % 2).toString().padStart(2, '0')}", 2412)
        assertEquals(50, detector.events.size)
        assertEquals(1060L, detector.events.last().elapsedMs)
        assertEquals(1011L, detector.events.first().elapsedMs)
        detector.clear()
        assertTrue(detector.events.isEmpty())
        assertNull(detector.observe(1, "aa:aa:aa:aa:aa:09", 2412), "baseline forgotten")
    }

    // --- Targets and scheduling --------------------------------------------------------------

    @Test fun internetTargetIsStrictIpv4() {
        assertEquals("192.0.2.1", validLiveInternetTarget(" 192.0.2.1 "))
        assertEquals("192.168.1.10", validLiveInternetTarget("192.168.1.10"))
        assertEquals("198.51.100.7", validLiveInternetTarget("198.51.100.7"))
        for (bad in listOf("", "one.one.one.one", "1.1.1", "01.1.1.1", "256.1.1.1", "::1",
            "0.0.0.0", "127.0.0.1", "224.0.0.1", "255.255.255.255", "240.0.0.1")) {
            assertNull(validLiveInternetTarget(bad), bad)
        }
        assertEquals(LIVE_DEFAULT_INTERNET_TARGET, validLiveInternetTarget(LIVE_DEFAULT_INTERNET_TARGET))
    }

    @Test fun dnsNameValidation() {
        assertEquals("one.one.one.one", validLiveDnsName(LIVE_DEFAULT_DNS_NAME))
        assertEquals("example.cz", validLiveDnsName(" Example.CZ. "))
        for (bad in listOf("", "localhost", "192.0.2.4", "-a.cz", "a-.cz", "a..cz", "a b.cz", "á.cz", "a.123",
            "${"a".repeat(64)}.cz", List(130) { "a" }.joinToString("."))) {
            assertNull(validLiveDnsName(bad), bad)
        }
    }

    @Test fun tickDelayIsFixedRateWithoutBursts() {
        assertEquals(1000, liveTickDelayMs(0, 0))
        assertEquals(700, liveTickDelayMs(0, 300))
        assertEquals(0, liveTickDelayMs(0, 1500), "overrun does not go negative")
        assertEquals(1000, liveTickDelayMs(1000, 0), "never more than one period")
    }
}
