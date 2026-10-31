package cz.netkit.shared.wifi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WifiUiPolicyTest {
    private fun point(channel: Int = 1, band: WifiBand = WifiBand.GHZ_2_4, id: Int = channel, ssid: String? = "fixture",
        rssi: Int = -50): AccessPoint {
        val frequency = if (band == WifiBand.GHZ_2_4) 2407 + channel * 5 else 5000 + channel * 5
        return AccessPoint(ssid, "02:00:00:00:00:${id.toString(16).padStart(2, '0')}", rssi, frequency,
            channel = channel, band = band, widthMhz = 20, security = WifiSecurity.WPA2,
            standard = "n", ageMs = 0, locallyAdministered = true)
    }
    private fun connected(ssid: String?) = CurrentWifi(ssid, "02:00:00:00:00:ff", -50, 2412, "n",
        null, null, null, null, emptyList(), WifiSecurity.WPA2)

    @Test fun equalScorePrefersNonDfsBeforeLowerChannelNumber() {
        val occupied = (36..48 step 4).map { point(it, WifiBand.GHZ_5) }
        val result = recommendChannels(occupied).single()
        assertEquals(149, result.channel)
        assertFalse(result.mayRequireDfs)
        assertEquals(0.0, result.score)
    }

    @Test fun lowerScoreStillWinsEvenWhenItRequiresDfs() {
        val occupied = ((36..48 step 4) + (149..177 step 4)).map { point(it, WifiBand.GHZ_5) }
        val result = recommendChannels(occupied).single()
        assertEquals(52, result.channel)
        assertTrue(result.mayRequireDfs)
        assertEquals(0.0, result.score)
    }

    @Test fun sameSsidExclusionRequiresExplicitChoiceAndWorksAcrossBands() {
        val first = point()
        val secondRadio = point(36, WifiBand.GHZ_5)
        val outsider = point(id = 2, ssid = "neighbour")
        val state = WifiAnalyzerState(listOf(first, secondRadio, outsider), current = connected("fixture"),
            lastUpdatedElapsedMs = 100)
        assertFalse(isOwnAccessPoint(first, state))
        assertFalse(isOwnAccessPoint(secondRadio, state))
        val excluded = state.copy(excludeConnectedSsid = true)
        assertTrue(isOwnAccessPoint(first, excluded))
        assertTrue(isOwnAccessPoint(secondRadio, excluded))
        assertFalse(isOwnAccessPoint(outsider, excluded))
        assertEquals(recommendChannels(listOf(outsider)), recommendDatasetChannels(excluded, 100))
        assertEquals(state.accessPoints, excluded.accessPoints)
    }

    @Test fun hiddenEmptyAndDifferentCaseSsidsAreNotGuessedToBeTheSameNetwork() {
        for (ssid in listOf(null, "", "   ")) {
            val state = WifiAnalyzerState(current = connected(ssid), excludeConnectedSsid = true)
            assertFalse(isOwnAccessPoint(point(ssid = ssid), state))
        }
        val state = WifiAnalyzerState(current = connected("Fixture"), excludeConnectedSsid = true)
        assertFalse(isOwnAccessPoint(point(ssid = "fixture"), state))
    }

    @Test fun manualOwnershipUsesBssidAndCanBeRemovedWithoutChangingHistorySelection() {
        val ap = point(id = 10)
        val state = WifiAnalyzerState(accessPoints = listOf(ap), selectedBssid = ap.bssid, historyBssid = ap.bssid,
            lastUpdatedElapsedMs = 100)
        val owned = withOwnAccessPoint(state, ap.bssid.uppercase() + " ", true)
        assertTrue(isOwnAccessPoint(ap, owned))
        assertTrue(recommendDatasetChannels(owned, 100).isEmpty())
        assertEquals(state.selectedBssid, owned.selectedBssid)
        assertEquals(state.historyBssid, owned.historyBssid)
        assertEquals(state.accessPoints, owned.accessPoints)
        assertEquals(state, withOwnAccessPoint(owned, ap.bssid, false))
        assertEquals(state, withOwnAccessPoint(state, " ", true))
    }

    @Test fun ownApPreferencesSurviveNewScansWithoutClaimingUnrelatedAps() {
        val ap = point()
        val other = point(id = 2)
        val state = withOwnAccessPoint(WifiAnalyzerState(excludeConnectedSsid = true), ap.bssid, true)
        val next = publishScanResult(state, listOf(other), true, 100)
        assertEquals(state.ownBssids, next.ownBssids)
        assertTrue(next.excludeConnectedSsid)
        assertFalse(isOwnAccessPoint(other, next))
        assertTrue(isOwnAccessPoint(other.copy(isCurrent = true), next))
    }

    @Test fun graphCapIsDeterministicAndDoesNotFilterScoringData() {
        val points = (1..10).map { point(channel = when (it) { 9 -> 6; 10 -> 11; else -> 1 }, id = it, rssi = -30 - it) }
        val displayed = chartAccessPoints(points, WifiBand.GHZ_2_4)
        assertEquals(WIFI_CHART_AP_LIMIT, displayed.size)
        assertEquals(points.take(6), displayed)
        assertEquals(displayed, chartAccessPoints(points.reversed(), WifiBand.GHZ_2_4))
        val state = WifiAnalyzerState(accessPoints = points, lastUpdatedElapsedMs = 100)
        assertEquals(recommendChannels(points), recommendDatasetChannels(state, 100))
        assertTrue(recommendDatasetChannels(state, 100).single().score > 0.0)
        assertEquals(0.0, recommendChannels(displayed).single().score)
        assertEquals(10, state.accessPoints.size)
        assertEquals(points.last().rssiDbm, state.accessPoints.last().rssiDbm)
    }

    @Test fun graphTieBreakUsesStableKeysAndSkipsWrongBandOrInvalidFrequency() {
        val points = listOf(point(id = 2), point(id = 1), point(36, WifiBand.GHZ_5), point(id = 3).copy(frequencyMhz = 2477))
        assertEquals(listOf(point(id = 1), point(id = 2)), chartAccessPoints(points, WifiBand.GHZ_2_4))
    }
}
