package cz.netkit.shared.wifi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WifiRevisionTest {
    private fun point(bssid: String = "02:00:00:00:00:01", ssid: String = "fixture", rssi: Int = -50,
                      timestamp: Long? = null, frequency: Int = 2412) = AccessPoint(
        ssid = ssid, bssid = bssid, rssiDbm = rssi, frequencyMhz = frequency,
        channel = channelForFrequency(frequency), band = bandForFrequency(frequency), widthMhz = 20,
        security = WifiSecurity.WPA2, standard = "n", ageMs = 0, seenAtElapsedMs = timestamp,
        locallyAdministered = true,
    )

    @Test fun noRecommendationWithoutObservedBand() {
        assertTrue(recommendChannels(emptyList()).isEmpty())
        assertEquals(listOf(WifiBand.GHZ_2_4), recommendChannels(listOf(point())).map { it.band })
        assertTrue(recommendChannels(listOf(point(frequency = 0))).isEmpty())
    }

    @Test fun transitionSecurityNeverClaimsExclusiveEncryption() {
        assertEquals(WifiSecurity.WPA2_WPA3, classifySecurity("[RSN-PSK+SAE-CCMP][ESS]"))
        assertEquals(WifiSecurity.WPA2_WPA3, classifySecurity("[WPA2-PSK-CCMP][RSN-SAE-CCMP][ESS]"))
        assertEquals(WifiSecurity.WPA3, classifySecurity("[RSN-SAE-CCMP][ESS]"))
        assertEquals(WifiSecurity.OWE_TRANSITION, classifySecurity("[OWE_TRANSITION][ESS]"))
        assertEquals(WifiSecurity.OWE, classifySecurity("[RSN-OWE-CCMP][ESS]"))
    }

    @Test fun invalidFrequencyEdgesDoNotProducePhantomChannels() {
        listOf(-1, 0, 2400, 2477, 2500, 4900, 5925, 5940, 7125).forEach { assertNull(channelForFrequency(it), "$it MHz") }
        assertEquals(14, channelForFrequency(2484))
        assertEquals(233, channelForFrequency(7115))
        assertEquals(13, channelForFrequency(2472))
        assertEquals(2, channelForFrequency(5935))
    }

    @Test fun duplicateBssidUsesNewestTimestampBeforeSignal() {
        val older = point(rssi = -30, timestamp = 100)
        val newer = point(bssid = "02:00:00:00:00:01 ", rssi = -80, timestamp = 200)
        assertEquals(listOf(newer), deduplicateAccessPoints(listOf(older, newer)))
        assertEquals(listOf(newer), deduplicateAccessPoints(listOf(newer, older)))
        val upper = point(bssid = "02:AA:BB:CC:DD:EE", timestamp = 200)
        val lower = upper.copy(bssid = "02:aa:bb:cc:dd:ee", rssiDbm = -40)
        assertEquals(listOf(lower), deduplicateAccessPoints(listOf(upper, lower)))
    }

    @Test fun missingTimestampsPreferStrongestSignal() {
        val weak = point(rssi = -80)
        val strong = point(rssi = -30)
        assertEquals(listOf(strong), deduplicateAccessPoints(listOf(weak, strong)))
    }

    @Test fun anonymousRecordsHaveDistinctStableKeysWithoutPretendingIdentity() {
        assertNull(normalizeBssid(null))
        assertNull(normalizeBssid("  "))
        val first = point(bssid = "", ssid = "one")
        val duplicate = first.copy(rssiDbm = -30)
        val second = point(bssid = "", ssid = "two")
        val points = deduplicateAccessPoints(listOf(first, duplicate, second))
        assertEquals(2, points.size)
        assertEquals(points.size, points.map(::accessPointKey).toSet().size)
        assertEquals(accessPointKey(first), accessPointKey(duplicate))
        assertEquals(points.map(::accessPointKey).toSet(),
            deduplicateAccessPoints(listOf(second, duplicate, first)).map(::accessPointKey).toSet())
    }

    @Test fun recommendationExcludesOwnAccessPointsButDoesNotExpireIndividualMembers() {
        val own = point().copy(isCurrent = true)
        assertTrue(recommendChannels(listOf(own)).isEmpty())
        assertEquals(1, recommendChannels(listOf(point().copy(ageMs = 30_001))).size)
        assertEquals(1, recommendChannels(listOf(point().copy(ageMs = 30_000))).size)
        val neighbour = point(bssid = "02:00:00:00:00:02", frequency = 2462)
        assertEquals(recommendChannels(listOf(neighbour)), recommendChannels(listOf(own, neighbour)))
    }

    @Test fun ageDiagnosticsRejectFutureTimeWithoutRemovingSnapshotMembers() {
        val sample = point(timestamp = 1000)
        assertEquals(30_000L, accessPointAgeMs(sample, 31_000))
        assertEquals(30_001L, accessPointAgeMs(sample, 31_001))
        assertEquals(Long.MAX_VALUE, accessPointAgeMs(sample, 999))
        assertEquals(listOf(sample), snapshotAccessPoints(listOf(sample)))
        assertTrue(snapshotAccessPoints(listOf(point(frequency = 2477))).isEmpty())
    }

    @Test fun candidatesCarryWidthAndDfsAndUseNonOverlapping24GhzPlan() {
        assertEquals(listOf(1, 6, 11), recommendationChannels(WifiBand.GHZ_2_4, 20))
        assertTrue(recommendationChannels(WifiBand.GHZ_2_4, 40).isEmpty())
        assertEquals(listOf(50, 114, 163), recommendationChannels(WifiBand.GHZ_5, 160))
        assertEquals(listOf(15, 47, 79, 111, 143, 175, 207), recommendationChannels(WifiBand.GHZ_6, 160))
        assertTrue(candidateMayRequireDfs(WifiBand.GHZ_5, 50, 160))
        assertTrue(candidateMayRequireDfs(WifiBand.GHZ_5, 52, 20))
        assertEquals(false, candidateMayRequireDfs(WifiBand.GHZ_5, 36, 20))
        assertEquals(false, candidateMayRequireDfs(WifiBand.GHZ_6, 15, 160))
        val result = recommendChannels(listOf(point(frequency = 5180)), 80).single()
        assertEquals(80, result.widthMhz)
        assertTrue(result.channel in recommendationChannels(WifiBand.GHZ_5, 80))
    }

    @Test fun candidateOverlapUsesActualBondedWidth() {
        val ap = point(frequency = 5180, rssi = -40)
        assertEquals(0.000025, overlapScore(42, WifiBand.GHZ_5, listOf(ap), 80), 1e-12)
        assertEquals(0.0001, overlapScore(36, WifiBand.GHZ_5, listOf(ap), 20), 1e-12)
    }

    @Test fun scanPublicationDoesNotWaitForIdentityOrErasePriorCache() {
        val previous = WifiAnalyzerState(accessPoints = listOf(point()), lastUpdatedElapsedMs = 10,
            selectedBssid = "fixture", status = WifiScanStatus.SCANNING)
        val empty = publishScanResult(previous, emptyList(), false, null)
        assertEquals(previous.accessPoints, empty.accessPoints)
        assertEquals(10L, empty.lastUpdatedElapsedMs)
        assertEquals(WifiScanStatus.THROTTLED_OR_FAILED, empty.status)
        val fresh = point(ssid = "new", timestamp = 20)
        val updated = publishScanResult(previous, listOf(fresh), true, 20)
        assertEquals(listOf(fresh), updated.accessPoints)
        assertEquals(WifiScanStatus.READY, updated.status)
        assertEquals(20L, updated.lastUpdatedElapsedMs)
        assertEquals(previous.current, updated.current)
    }

    @Test fun ipv4GatewayIsPreferredRegardlessOfRouteOrder() {
        assertEquals("192.0.2.1", preferIpv4Address(listOf("fe80::1", "192.0.2.1")))
        assertEquals("192.0.2.1", preferIpv4Address(listOf("192.0.2.1", "fe80::1")))
        assertEquals("fe80::1", preferIpv4Address(listOf("fe80::1")))
        assertNull(preferIpv4Address(emptyList()))
    }

    @Test fun nullableSsidIsNotLocalizedAndFiltersCaseInsensitively() {
        val hidden = point().copy(ssid = null)
        val named = point(ssid = "FixtureLab", bssid = "02:00:00:00:00:02")
        assertEquals(listOf(named), sortedAccessPoints(listOf(hidden, named), null, WifiSort.NAME, "fixture"))
        assertEquals(listOf(hidden, named), sortedAccessPoints(listOf(named, hidden), null, WifiSort.NAME))
        assertTrue(accessPointKey(hidden.copy(bssid = "")).startsWith("anonymous:"))
    }

    @Test fun historyKeepsRepeatedSelectionAndMonotonicSamplesBounded() {
        val address = "02:00:00:00:00:01"
        assertEquals(address, toggleWifiSelection(null, address))
        assertNull(toggleWifiSelection(address, address))
        assertEquals(address, toggleWifiSelection(address, ""))
        val original = listOf(SignalSample(10, -50))
        assertEquals(original, appendSignalSample(original, SignalSample(10, -60)))
        assertEquals(original, appendSignalSample(original, SignalSample(9, -60)))
        val history = (1..100).fold(emptyList<SignalSample>()) { old, index -> appendSignalSample(old, SignalSample(index.toLong(), -50)) }
        assertEquals(90, history.size)
        assertEquals(11L, history.first().elapsedMs)
    }

    @Test fun permissionStatesHaveDistinctRecoveryPaths() {
        assertEquals(WifiAccessIssue.PERMISSION_REQUIRED, wifiAccessIssue(false, false, false, false, true, true))
        assertEquals(WifiAccessIssue.PERMISSION_REQUIRED, wifiAccessIssue(false, false, true, true, true, true))
        assertEquals(WifiAccessIssue.PERMANENTLY_DENIED, wifiAccessIssue(false, false, true, false, true, true))
        assertEquals(WifiAccessIssue.PRECISE_REQUIRED, wifiAccessIssue(false, true, true, false, true, true))
        assertEquals(WifiAccessIssue.LOCATION_DISABLED, wifiAccessIssue(true, true, true, false, false, true))
        assertEquals(WifiAccessIssue.WIFI_DISABLED, wifiAccessIssue(true, true, true, false, true, false))
        assertEquals(WifiAccessIssue.NONE, wifiAccessIssue(true, true, true, false, true, true))
    }

    @Test fun openNetworkWhitelistContainsOnlyNonEncryptionCapabilities() {
        assertEquals(WifiSecurity.OPEN, classifySecurity("[ESS][WPS][UTF-8][WMM]"))
        assertEquals(WifiSecurity.OPEN, classifySecurity("[ESS][WPS-PBC]"))
        assertEquals(WifiSecurity.UNKNOWN, classifySecurity("[ESS][WAPI-PSK-SMS4]"))
        assertEquals(WifiSecurity.UNKNOWN, classifySecurity("[ESS][UNRECOGNIZED-SECURITY]"))
        assertEquals(WifiSecurity.WPA2, classifySecurity("[ESS][WPS][RSN-PSK-CCMP]"))
    }
}
