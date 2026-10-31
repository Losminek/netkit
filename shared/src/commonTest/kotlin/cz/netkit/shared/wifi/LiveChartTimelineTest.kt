package cz.netkit.shared.wifi

import cz.netkit.shared.chart.ChartEvent
import cz.netkit.shared.chart.ChartEventKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveChartTimelineTest {
    private fun master(vararg times: Long) = times.map { LiveSample(it, -50.0) }
    private fun samples(vararg pairs: Pair<Long, Double?>) = pairs.map { LiveSample(it.first, it.second) }

    // --- Timeline ----------------------------------------------------------------------------

    @Test fun timelineHasOneSlotPerMasterSampleAndMarksPauses() {
        val timeline = LiveTimeline.of(master(1000, 2000, 3010, 9000, 10000))
        // 3010 → 9000 is six intervals: five missed ticks become five empty slots.
        assertEquals(listOf(1000L, 2000L, 3010L, null, null, null, null, null, 9000L, 10000L), timeline.slots)
        assertEquals(0, LiveTimeline.of(emptyList()).size)
    }

    @Test fun alignedValuesPairSamplesOfAnotherLoopByTime() {
        val timeline = LiveTimeline.of(master(1000, 2000, 3000, 4000))
        // Latency loop runs 400 ms after the link loop; the third echo was lost.
        val aligned = timeline.alignedValues(samples(1400L to 5.0, 2400L to 6.0, 3400L to null, 4400L to 8.0))
        assertEquals(listOf(5f, 6f, null, 8f), aligned)
    }

    @Test fun alignedValuesLeaveUnsampledSlotsEmpty() {
        val timeline = LiveTimeline.of(master(1000, 2000, 3000, 4000, 5000, 6000))
        // Series started late (e.g. target changed) and stopped two ticks before the end.
        val aligned = timeline.alignedValues(samples(3000L to 1.0, 4000L to 2.0))
        assertEquals(listOf(null, null, 1f, 2f, null, null), aligned)
    }

    @Test fun singleSlotMissedByPhaseDriftRepeatsThePreviousValue() {
        val timeline = LiveTimeline.of(master(1000, 2000, 3000, 4000))
        // Phase drifted past the tolerance: 1700 pairs with 1000, 2800 is closer to 3000.
        val aligned = timeline.alignedValues(samples(1700L to 1.0, 2800L to 3.0, 3800L to 4.0))
        assertEquals(listOf(1f, 1f, 3f, 4f), aligned)
    }

    @Test fun newestSlotHoldsThePreviousValueWhileItsSampleIsInFlight() {
        val timeline = LiveTimeline.of(master(1000, 2000, 3000))
        assertEquals(listOf(1f, 2f, 2f), timeline.alignedValues(samples(1000L to 1.0, 2000L to 2.0)))
        // A lost previous sample is not turned into a value.
        assertEquals(listOf(1f, null, null), timeline.alignedValues(samples(1000L to 1.0, 2000L to null)))
    }

    @Test fun gapsOfTwoSlotsAndPausesAreNotBridged() {
        val timeline = LiveTimeline.of(master(1000, 2000, 3000, 4000, 5000))
        assertEquals(listOf(1f, null, null, 4f, 5f),
            timeline.alignedValues(samples(1000L to 1.0, 4000L to 4.0, 5000L to 5.0)))
        val paused = LiveTimeline.of(master(1000, 2000, 9000, 10000))
        assertEquals(listOf(1f, 2f, null, null, null, null, null, null, 9f, 10f),
            paused.alignedValues(samples(1000L to 1.0, 2000L to 2.0, 9000L to 9.0, 10000L to 10.0)))
    }

    @Test fun eventsLandOnTheNearestSlotAndDropOutsideTheHistory() {
        val timeline = LiveTimeline.of(master(1000, 2000, 3000, 9000))
        val events = listOf(
            LiveEvent(-2000, ChartEventKind.ROAMING), // older than the history
            LiveEvent(1400, ChartEventKind.PACKET_LOSS, "192.0.2.1"),
            LiveEvent(2600, ChartEventKind.CHANNEL_CHANGE, "36 → 100"),
            LiveEvent(6000, ChartEventKind.ROAMING), // inside the pause, far from any slot
            LiveEvent(9500, ChartEventKind.PACKET_LOSS), // after the newest tick, still in tolerance
        )
        assertEquals(listOf(
            ChartEvent(0, ChartEventKind.PACKET_LOSS, "192.0.2.1"),
            ChartEvent(2, ChartEventKind.CHANNEL_CHANGE, "36 → 100"),
            ChartEvent(8, ChartEventKind.PACKET_LOSS), // 3 samples + 5 empty slots of the pause
        ), timeline.chartEvents(events))
        assertEquals(emptyList(), LiveTimeline.EMPTY.chartEvents(events))
    }

    @Test fun pauseOfNIntervalsLeavesNMinusOneEmptySlots() {
        listOf(2, 3, 10, 300).forEach { n ->
            val timeline = LiveTimeline.of(master(1000, 2000, 2000 + n * 1000L, 3000 + n * 1000L))
            assertEquals(n - 1, timeline.slots.count { it == null }, "pause of $n intervals")
            assertEquals(4 + n - 1, timeline.size)
        }
        // Phase drift up to 1.5 intervals is a normal tick, not a pause.
        assertEquals(0, LiveTimeline.of(master(1000, 2500, 3500)).slots.count { it == null })
        assertEquals(1, LiveTimeline.of(master(1000, 2600, 3600)).slots.count { it == null })
    }

    @Test fun indicesStayUniformInTimeAcrossAPause() {
        // 5 min of samples, a 10 min pause, then 1 min of samples (1 Hz).
        val before = (1..300).map { it * 1000L }
        val after = (901..960).map { it * 1000L }
        val timeline = LiveTimeline.of(master(*(before + after).toLongArray()))
        val newest = 960_000L
        // Every timed slot sits (size − 1 − index) intervals before the newest sample.
        timeline.slots.forEachIndexed { index, time ->
            if (time != null) assertEquals(newest - time, (timeline.size - 1 - index) * 1000L, "slot $index")
        }
        val events = listOf(LiveEvent(120_000, ChartEventKind.ROAMING), LiveEvent(930_000, ChartEventKind.PACKET_LOSS))
        val ages = timeline.chartEvents(events).map { (timeline.size - 1 - it.index) * 1000L }
        assertEquals(listOf(newest - 120_000, newest - 930_000), ages, "event times keep their real age")
    }

    @Test fun pauseLongerThanTheHistoryIsCappedAndDropsOlderSamples() {
        val timeline = LiveTimeline.of(master(1000, 2000, 3000, 100_000_000, 100_001_000), maxSlots = 10)
        assertEquals(10, timeline.size)
        assertEquals(listOf<Long?>(null, null, null, null, null, null, null, null, 100_000_000, 100_001_000), timeline.slots)
        assertEquals(emptyList(), timeline.chartEvents(listOf(LiveEvent(2000, ChartEventKind.ROAMING))))
        // Without a pause the cap keeps the newest samples.
        assertEquals(listOf<Long?>(3000, 4000), LiveTimeline.of(master(1000, 2000, 3000, 4000), maxSlots = 2).slots)
        assertEquals(100, LiveTimeline.pauseSlots(Long.MAX_VALUE / 2, 1000, 100))
    }

    @Test fun eventIndicesShiftAndDropAsOldSamplesAreEvicted() {
        val buffer = LiveRingBuffer(3)
        val log = LiveEventLog()
        listOf(1000L, 2000L, 3000L).forEach { buffer.add(it, -50.0) }
        log.observeProbe(LiveProbe.INTERNET, 2100, "192.0.2.1", lost = true)
        fun indices(): List<Int> {
            val history = buffer.snapshot()
            log.prune(history.firstOrNull()?.elapsedMs)
            return LiveTimeline.of(history).chartEvents(log.events).map { it.index }
        }
        assertEquals(listOf(1), indices())
        buffer.add(4000, -50.0)
        assertEquals(listOf(0), indices(), "oldest sample evicted: event moves left")
        buffer.add(5000, -50.0)
        assertEquals(emptyList(), indices(), "its sample left the buffer: event is dropped")
        assertTrue(log.events.isEmpty(), "pruned from the log too")
    }

    // --- Event log ---------------------------------------------------------------------------

    @Test fun roamingBecomesAnEventWithoutAChannelChange() {
        val detector = RoamingDetector()
        val log = LiveEventLog()
        fun link(t: Long, bssid: String, frequency: Int, channel: Int) =
            log.observeLink(t, bssid, channel, detector.observe(t, bssid, frequency))
        link(1000, "aa:bb:cc:00:ed:bd", 5180, 36)
        link(2000, "aa:bb:cc:00:12:34", 5500, 100)
        assertEquals(listOf(LiveEvent(2000, ChartEventKind.ROAMING, "…:ED:BD → …:12:34")), log.events)
    }

    @Test fun channelChangeOfTheSameApIsAnEvent() {
        val log = LiveEventLog()
        log.observeLink(1000, "aa:bb:cc:00:00:01", 36, null)
        log.observeLink(2000, "aa:bb:cc:00:00:01", 36, null)
        log.observeLink(3000, null, null, null) // link lost
        log.observeLink(4000, "AA:BB:CC:00:00:01", 100, null) // reconnect after DFS move
        assertEquals(listOf(LiveEvent(4000, ChartEventKind.CHANNEL_CHANGE, "36 → 100")), log.events)
    }

    @Test fun channelChangeWithRedactedBssidIsStillReported() {
        val log = LiveEventLog()
        log.observeLink(1000, WIFI_REDACTED_BSSID, 1, null)
        log.observeLink(2000, WIFI_REDACTED_BSSID, 6, null)
        assertEquals(listOf(LiveEvent(2000, ChartEventKind.CHANNEL_CHANGE, "1 → 6")), log.events)
    }

    @Test fun packetLossRunsAreCoalescedPerTarget() {
        val log = LiveEventLog()
        log.observeProbe(LiveProbe.INTERNET, 1000, "192.0.2.1", lost = true)
        log.observeProbe(LiveProbe.GATEWAY, 1100, "192.168.0.1", lost = true)
        log.observeProbe(LiveProbe.INTERNET, 2000, "192.0.2.1", lost = true)
        log.observeProbe(LiveProbe.INTERNET, 3000, "192.0.2.1", lost = true)
        log.observeProbe(LiveProbe.GATEWAY, 2100, "192.168.0.1", lost = false)
        log.observeProbe(LiveProbe.INTERNET, 4000, "192.0.2.1", lost = false)
        log.observeProbe(LiveProbe.INTERNET, 5000, "192.0.2.1", lost = true)
        assertEquals(listOf(
            LiveEvent(1000, ChartEventKind.PACKET_LOSS, "192.0.2.1 ×3"),
            LiveEvent(1100, ChartEventKind.PACKET_LOSS, "192.168.0.1"),
            LiveEvent(5000, ChartEventKind.PACKET_LOSS, "192.0.2.1"),
        ), log.events)
    }

    @Test fun eventLogIsCappedAndClearable() {
        val log = LiveEventLog(maxEvents = 2)
        listOf(1, 6, 11).forEachIndexed { i, ch -> log.observeLink(1000L * (i + 1), null, ch, null) }
        log.observeLink(4000, null, 1, null)
        assertEquals(listOf("6 → 11", "11 → 1"), log.events.map { it.detail })
        log.clear()
        assertTrue(log.events.isEmpty())
        log.observeLink(5000, null, 6, null)
        assertTrue(log.events.isEmpty(), "baseline forgotten: first reading after clear is no change")
    }
}
