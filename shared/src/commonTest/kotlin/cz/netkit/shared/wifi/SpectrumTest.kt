package cz.netkit.shared.wifi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpectrumTest {
    private fun ap(frequency: Int, rssi: Int = -60, id: Int = frequency % 256, width: Int? = 20, center: Int? = null,
                   utilization: Int? = null, current: Boolean = false, ssid: String? = "fixture"): AccessPoint =
        AccessPoint(ssid, "02:00:00:00:00:${id.toString(16).padStart(2, '0')}", rssi, frequency,
            centerFrequencyMhz = center, channel = channelForFrequency(frequency), band = bandForFrequency(frequency),
            widthMhz = width, security = WifiSecurity.WPA2, standard = "ax", ageMs = 0, locallyAdministered = true,
            isCurrent = current, channelUtilizationPercent = utilization)

    private fun state(vararg points: AccessPoint, updated: Long? = 1_000) =
        WifiAnalyzerState(points.toList(), lastUpdatedElapsedMs = updated)

    private fun Collection<ChannelHealthEntry>.of(channel: Int) = single { it.channel == channel }

    // --- axis -------------------------------------------------------------------------------

    @Test fun axis24CoversChannels1To13AndAdds14OnlyWhenPresent() {
        val axis = spectrumAxis(WifiBand.GHZ_2_4)!!
        assertEquals((1..13).toList(), axis.channels)
        assertEquals(2402.0, axis.minMhz)
        assertEquals(2482.0, axis.maxMhz)
        assertTrue(axis.ticks.all { it.labelled })
        assertEquals(2437.0, axis.ticks.single { it.channel == 6 }.frequencyMhz)
        assertTrue(axis.gaps.isEmpty())

        val withJapan = spectrumAxis(WifiBand.GHZ_2_4, listOf(ap(2484)))!!
        assertEquals(14, withJapan.channels.last())
        assertEquals(2494.0, withJapan.maxMhz)
        assertTrue(withJapan.gaps.isEmpty()) // 13 (2462–2482) and 14 (2474–2494) touch/overlap
    }

    @Test fun axis5KeepsRealGapsLinear() {
        val axis = spectrumAxis(WifiBand.GHZ_5)!!
        assertEquals(5170.0, axis.minMhz)
        assertEquals(5895.0, axis.maxMhz)
        assertEquals(36, axis.channels.first())
        assertEquals(177, axis.channels.last())
        assertFalse(68 in axis.channels)
        assertFalse(96 in axis.channels)
        assertEquals(listOf(36, 52, 100, 116, 132, 149, 165), axis.ticks.filter { it.labelled }.map { it.channel })
        // UNII-2A/2C gap (no channel 68–96) and the 5 MHz gap between 144 and 149.
        assertEquals(listOf(SpectrumGap(5330.0, 5490.0), SpectrumGap(5730.0, 5735.0)), axis.gaps)
        // Linear axis: the gap keeps its real 160 MHz instead of being one channel step.
        assertEquals((5500.0 - 5170.0) / 725.0, axis.fraction(5500.0), 1e-12)
        assertEquals((5490.0 - 5330.0) / 725.0, axis.fraction(5490.0) - axis.fraction(5330.0), 1e-12)
        assertEquals(0.0, axis.fraction(5000.0))
        assertEquals(1.0, axis.fraction(6000.0))
    }

    @Test fun axis6UsesStep4AndSpecialChannel2OnlyWhenPresent() {
        val axis = spectrumAxis(WifiBand.GHZ_6)!!
        assertEquals((1..233 step 4).toList(), axis.channels)
        assertEquals(5945.0, axis.minMhz)
        assertEquals(7125.0, axis.maxMhz)
        assertEquals(listOf(1, 33, 65, 97, 129, 161, 193, 225), axis.ticks.filter { it.labelled }.map { it.channel })
        assertTrue(axis.gaps.isEmpty())

        val withChannel2 = spectrumAxis(WifiBand.GHZ_6, listOf(ap(5935)))!!
        assertEquals(listOf(2, 1, 5), withChannel2.channels.take(3)) // ascending by frequency
        assertEquals(5925.0, withChannel2.minMhz)
        assertNull(spectrumAxis(WifiBand.UNKNOWN))
    }

    // --- spans / ordering --------------------------------------------------------------------

    @Test fun spanFollowsCentreFrequencyAndWidth() {
        fun shape(point: AccessPoint) = spectrumTraces(listOf(point), WifiBand.GHZ_5).single().shapes.single()
        val w20 = shape(ap(5180))
        assertEquals(5170.0 to 5190.0, w20.lowerMhz to w20.upperMhz)
        assertEquals(5172.0 to 5188.0, w20.plateauLowerMhz to w20.plateauUpperMhz)
        val w40 = shape(ap(5180, width = 40, center = 5190))
        assertEquals(5170.0 to 5210.0, w40.lowerMhz to w40.upperMhz)
        val w80 = shape(ap(5180, width = 80, center = 5210))
        assertEquals(5170.0 to 5250.0, w80.lowerMhz to w80.upperMhz)
        assertEquals(5178.0 to 5242.0, w80.plateauLowerMhz to w80.plateauUpperMhz)
        val w160 = shape(ap(5180, width = 160, center = 5250))
        assertEquals(5170.0 to 5330.0, w160.lowerMhz to w160.upperMhz)
        // Without centre fields the primary channel ± width/2 is used.
        val noCenter = shape(ap(5180, width = 40))
        assertEquals(5160.0 to 5200.0, noCenter.lowerMhz to noCenter.upperMhz)

        val plus = ap(5180, width = 160, center = 5210).copy(secondaryCenterFrequencyMhz = 5530, is80Plus80 = true)
        val trace = spectrumTraces(listOf(plus), WifiBand.GHZ_5).single()
        assertEquals(listOf(5170.0 to 5250.0, 5490.0 to 5570.0), trace.shapes.map { it.lowerMhz to it.upperMhz })
        assertEquals(36, trace.primaryChannel)
        assertEquals(-60, trace.peakDbm)
    }

    @Test fun tracesAreStrongestFirstWithStableKeyTieBreakAndNoCap() {
        val points = listOf(ap(2412, -70, id = 3), ap(2437, -50, id = 9), ap(2462, -70, id = 1),
            ap(5180, -30, id = 4), ap(2412, -60, id = 0x10).copy(frequencyMhz = 2477)) +
            (20..27).map { ap(2442, -80, id = it) }
        val traces = spectrumTraces(points, WifiBand.GHZ_2_4)
        assertEquals(listOf("bssid:02:00:00:00:00:09", "bssid:02:00:00:00:00:01", "bssid:02:00:00:00:00:03"),
            traces.take(3).map { it.key })
        assertEquals(11, traces.size) // invalid 2477 and the 5 GHz AP are excluded; no 6-AP display cap
        assertEquals(traces, spectrumTraces(points.reversed(), WifiBand.GHZ_2_4))
        assertEquals(bandSpectrum(points, WifiBand.GHZ_2_4)!!.traces, traces)
    }

    @Test fun bandCountsUseOnlyValidFrequencies() {
        val counts = wifiBandCounts(listOf(ap(2412), ap(2437), ap(5180), ap(5955), ap(5935), ap(5801, id = 1), ap(2477, id = 2)))
        assertEquals(WifiBandCounts(2, 1, 2), counts)
        assertEquals(5, counts.all)
        assertEquals(5, counts.count(null))
        assertEquals(1, counts.count(WifiBand.GHZ_5))
        assertEquals(0, counts.count(WifiBand.UNKNOWN))
    }

    // --- health ------------------------------------------------------------------------------

    @Test fun pressureAndUtilizationThresholdBoundaries() {
        assertEquals(ChannelHealth.EXCELLENT, pressureHealth(null))
        assertEquals(ChannelHealth.EXCELLENT, pressureHealth(-82.01))
        assertEquals(ChannelHealth.GOOD, pressureHealth(-82.0))
        assertEquals(ChannelHealth.GOOD, pressureHealth(-72.01))
        assertEquals(ChannelHealth.FAIR, pressureHealth(-72.0))
        assertEquals(ChannelHealth.FAIR, pressureHealth(-62.01))
        assertEquals(ChannelHealth.POOR, pressureHealth(-62.0))

        assertNull(utilizationHealth(null))
        assertEquals(ChannelHealth.EXCELLENT, utilizationHealth(19))
        assertEquals(ChannelHealth.GOOD, utilizationHealth(20))
        assertEquals(ChannelHealth.GOOD, utilizationHealth(39))
        assertEquals(ChannelHealth.FAIR, utilizationHealth(40))
        assertEquals(ChannelHealth.FAIR, utilizationHealth(69))
        assertEquals(ChannelHealth.POOR, utilizationHealth(70))
    }

    @Test fun healthFromRealOverlapHitsBoundariesExactly() {
        fun health(rssi: Int) = scoreChannelHealth(listOf(ap(2437, rssi)), WifiBand.GHZ_2_4).of(6)
        assertEquals(-82.0, health(-82).pressureDbm)
        assertEquals(ChannelHealth.GOOD, health(-82).health)
        assertEquals(ChannelHealth.EXCELLENT, health(-83).health)
        assertEquals(ChannelHealth.FAIR, health(-72).health)
        assertEquals(ChannelHealth.GOOD, health(-73).health)
        assertEquals(ChannelHealth.POOR, health(-62).health)
        assertEquals(ChannelHealth.FAIR, health(-63).health)
    }

    @Test fun adjacentOverlapCountsPartiallyAndTouchingSpansDoNot() {
        // Channel 3 (2412–2432) overlaps channel 1 (2402–2422) by half; channel 5 only touches it.
        val entries = scoreChannelHealth(listOf(ap(2422, -67, id = 1), ap(2432, -40, id = 2)), WifiBand.GHZ_2_4)
        val one = entries.of(1)
        assertEquals(1, one.overlappingAccessPoints)
        assertEquals(-70.01, one.pressureDbm) // 0.5 × 10^-6.7 mW
        assertEquals(ChannelHealth.FAIR, one.health)
        assertEquals(overlapScore(1, WifiBand.GHZ_2_4, listOf(ap(2422, -67, id = 1), ap(2432, -40, id = 2))), one.pressureMw)
        assertEquals(2, entries.of(4).overlappingAccessPoints)
        assertEquals(ChannelHealth.EXCELLENT, entries.of(13).health)
        assertNull(entries.of(13).pressureDbm)
        assertEquals(0, entries.of(13).overlappingAccessPoints)
    }

    @Test fun utilizationOnlyRaisesSeverity() {
        val busy = scoreChannelHealth(listOf(ap(5180, -90, utilization = 75)), WifiBand.GHZ_5).of(36)
        assertEquals(ChannelHealth.EXCELLENT, busy.pressureHealth)
        assertEquals(ChannelHealth.POOR, busy.utilizationHealth)
        assertEquals(ChannelHealth.POOR, busy.health)
        assertEquals(75, busy.maxUtilizationPercent)

        val idleButStrong = scoreChannelHealth(listOf(ap(5180, -50, utilization = 5)), WifiBand.GHZ_5).of(36)
        assertEquals(ChannelHealth.POOR, idleButStrong.health)
        assertEquals(ChannelHealth.EXCELLENT, idleButStrong.utilizationHealth)

        // Max over APs whose primary channel is 36; an AP on 40 does not lend its value to 36.
        val mixed = scoreChannelHealth(listOf(ap(5180, -95, id = 1, utilization = 10), ap(5180, -95, id = 2, utilization = 45),
            ap(5200, -95, id = 3, utilization = 90)), WifiBand.GHZ_5)
        assertEquals(45, mixed.of(36).maxUtilizationPercent)
        assertEquals(ChannelHealth.FAIR, mixed.of(36).health)
        assertEquals(90, mixed.of(40).maxUtilizationPercent)
        assertNull(mixed.of(44).maxUtilizationPercent)
        assertNull(mixed.of(44).utilizationHealth)
    }

    @Test fun reportExcludesOwnApFromPressureButKeepsItsUtilizationAndFlags() {
        val own = ap(5260, -40, id = 1, utilization = 30, current = true)
        val report = channelHealthReport(state(own, ap(5180, -95, id = 2)), WifiBand.GHZ_5, 2_000)
        assertIs<ChannelHealthReport.Ready>(report)
        val ch52 = report.channels.of(52)
        assertTrue(ch52.connectedHere)
        assertNull(ch52.pressureDbm)
        assertEquals(0, ch52.overlappingAccessPoints)
        assertEquals(30, ch52.maxUtilizationPercent)
        assertEquals(ChannelHealth.GOOD, ch52.health)
        assertTrue(ch52.mayRequireDfs)
        assertFalse(report.channels.of(36).mayRequireDfs)
        assertFalse(report.channels.of(36).connectedHere)
        assertEquals(spectrumAxis(WifiBand.GHZ_5)!!.channels, report.channels.map { it.channel })

        // Explicit "Moje AP" marking uses the same isOwnAccessPoint semantics as recommendations.
        val neighbour = ap(5180, -50, id = 3)
        val marked = withOwnAccessPoint(state(neighbour), neighbour.bssid, owned = true)
        val ready = channelHealthReport(marked, WifiBand.GHZ_5, 2_000) as ChannelHealthReport.Ready
        assertEquals(ChannelHealth.EXCELLENT, ready.channels.of(36).health)
        val unmarked = channelHealthReport(state(neighbour), WifiBand.GHZ_5, 2_000) as ChannelHealthReport.Ready
        assertEquals(ChannelHealth.POOR, unmarked.channels.of(36).health)
    }

    @Test fun staleOrUnknownDatasetProducesNoHealth() {
        val points = arrayOf(ap(2412, -50))
        val expired = channelHealthReport(state(*points, updated = 0), WifiBand.GHZ_2_4, WIFI_DATASET_EXPIRY_MS + 1)
        assertIs<ChannelHealthReport.Stale>(expired)
        assertEquals(WifiDatasetFreshness.EXPIRED, expired.age.freshness)
        assertIs<ChannelHealthReport.Stale>(channelHealthReport(state(*points, updated = null), WifiBand.GHZ_2_4, 10))
        assertIs<ChannelHealthReport.Stale>(channelHealthReport(state(*points, updated = 100), WifiBand.GHZ_2_4, 50))
        val aging = channelHealthReport(state(*points, updated = 0), WifiBand.GHZ_2_4, WIFI_DATASET_EXPIRY_MS)
        assertIs<ChannelHealthReport.Ready>(aging)
        assertEquals(WifiDatasetFreshness.AGING, aging.age.freshness)
        // Stale wins over "no data"; an empty band with a fresh dataset is NoData, not "excellent".
        assertIs<ChannelHealthReport.Stale>(channelHealthReport(state(updated = null), WifiBand.GHZ_6, 10))
        assertIs<ChannelHealthReport.NoData>(channelHealthReport(state(*points), WifiBand.GHZ_6, 2_000))
        assertIs<ChannelHealthReport.NoData>(channelHealthReport(state(*points), WifiBand.UNKNOWN, 2_000))
    }

    // --- BSS Load ----------------------------------------------------------------------------

    @Test fun bssLoadParsesLittleEndianFields() {
        val load = parseBssLoad(byteArrayOf(0x2A, 0x01, 0x80.toByte(), 0x10, 0x27))!!
        assertEquals(298, load.stationCount)
        assertEquals(128, load.channelUtilizationRaw)
        assertEquals(50, load.channelUtilizationPercent) // 50.196 → 50
        assertEquals(10_000, load.availableAdmissionCapacity)
        val max = parseBssLoad(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))!!
        assertEquals(65_535, max.stationCount)
        assertEquals(100, max.channelUtilizationPercent)
        assertEquals(65_535, max.availableAdmissionCapacity)
        assertEquals(0, parseBssLoad(ByteArray(5))!!.channelUtilizationPercent)
        assertEquals(0, parseBssLoad(byteArrayOf(0, 0, 1, 0, 0))!!.channelUtilizationPercent) // 0.39 → 0
        assertEquals(1, parseBssLoad(byteArrayOf(0, 0, 2, 0, 0))!!.channelUtilizationPercent) // 0.78 → 1
    }

    @Test fun bssLoadRejectsWrongLengths() {
        assertNull(parseBssLoad(ByteArray(0)))
        assertNull(parseBssLoad(ByteArray(4)))
        assertNull(parseBssLoad(ByteArray(6)))
        assertNull(parseBssLoad(ByteArray(WIFI_ELEMENT_MAX_BODY_LENGTH)))
    }

    @Test fun accessPointBssLoadFieldsDefaultToNull() {
        val plain = AccessPoint("a", "02:00:00:00:00:01", -50, 2412, channel = 1, band = WifiBand.GHZ_2_4, widthMhz = 20,
            security = WifiSecurity.WPA2, standard = "n", ageMs = 0, locallyAdministered = true)
        assertNull(plain.stationCount)
        assertNull(plain.channelUtilizationPercent)
    }
}
