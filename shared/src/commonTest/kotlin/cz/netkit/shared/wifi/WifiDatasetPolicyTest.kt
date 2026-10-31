package cz.netkit.shared.wifi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WifiDatasetPolicyTest {
    private val received = 1_000_000L
    private val point = AccessPoint("fixture", "02:00:00:00:00:01", -50, 2412,
        channel = 1, band = WifiBand.GHZ_2_4, widthMhz = 20, security = WifiSecurity.WPA2,
        standard = "n", ageMs = 1_000, seenAtElapsedMs = received - 1_000, locallyAdministered = true)
    private val dataset = WifiAnalyzerState(accessPoints = listOf(point), lastUpdatedElapsedMs = received,
        status = WifiScanStatus.READY, selectedBssid = point.bssid, historyBssid = point.bssid)

    @Test fun datasetBoundariesWarnAfterThirtySecondsAndExpireAfterFiveMinutes() {
        val cases = listOf(29_000L to WifiDatasetFreshness.FRESH, 30_000L to WifiDatasetFreshness.FRESH,
            31_000L to WifiDatasetFreshness.AGING, 299_000L to WifiDatasetFreshness.AGING,
            300_000L to WifiDatasetFreshness.AGING, 301_000L to WifiDatasetFreshness.EXPIRED)
        for ((age, expected) in cases) {
            val policy = wifiDatasetAge(received, received + age)
            assertEquals(expected, policy.freshness, "age=$age")
            assertEquals(age, policy.ageMs)
            assertEquals(age <= 300_000L, policy.allowsRecommendation)
            assertEquals(age <= 300_000L, recommendDatasetChannels(dataset, received + age).isNotEmpty())
        }
    }

    @Test fun missingNegativeAndFutureReferenceNeverCreateFreshData() {
        for (reference in listOf(null, -1L, received + 1)) {
            val policy = wifiDatasetAge(reference, received)
            assertEquals(WifiDatasetFreshness.UNKNOWN, policy.freshness)
            assertNull(policy.ageMs)
            assertFalse(policy.allowsRecommendation)
            assertTrue(recommendDatasetChannels(dataset.copy(lastUpdatedElapsedMs = reference), received).isEmpty())
        }
    }

    @Test fun receiptTimeDoesNotUseTheNewestAccessPointTimestamp() {
        val older = point.copy(seenAtElapsedMs = received - 90_000, ageMs = 90_000)
        val next = publishScanResult(WifiAnalyzerState(), listOf(older), true, received)
        assertEquals(received, next.lastUpdatedElapsedMs)
        assertEquals(31_000L, wifiDatasetAge(next.lastUpdatedElapsedMs, received + 31_000).ageMs)
        assertEquals(listOf(older), snapshotAccessPoints(next.accessPoints))
        assertTrue(recommendDatasetChannels(next, received + 299_000).isNotEmpty())
        assertTrue(recommendDatasetChannels(next, received + 301_000).isEmpty())
        assertEquals(listOf(older), snapshotAccessPoints(next.accessPoints))
    }

    @Test fun nextConfirmedScanReplacesMembershipAndRetainsSelection() {
        val replacement = point.copy(bssid = "02:00:00:00:00:02", frequencyMhz = 2462, channel = 11)
        val next = publishScanResult(dataset, listOf(replacement), true, received + 60_000)
        assertEquals(listOf(replacement), next.accessPoints)
        assertFalse(snapshotAccessPoints(next.accessPoints).contains(point))
        assertEquals(received + 60_000, next.lastUpdatedElapsedMs)
        assertEquals(dataset.selectedBssid, next.selectedBssid)
        assertEquals(dataset.historyBssid, next.historyBssid)
        assertNull(signalSampleForDataset(next, point.bssid, received + 60_000))
    }

    @Test fun emptyScanPreservesConfirmedSnapshotAndItsAge() {
        for (confirmed in listOf(false, true)) {
            val next = publishScanResult(dataset, emptyList(), confirmed, received + 301_000)
            assertEquals(dataset.accessPoints, next.accessPoints)
            assertEquals(dataset.lastUpdatedElapsedMs, next.lastUpdatedElapsedMs)
            assertEquals(WifiScanStatus.THROTTLED_OR_FAILED, next.status)
            assertTrue(recommendDatasetChannels(next, received + 301_000).isEmpty())
        }
    }

    @Test fun firstCacheRemainsVisibleWithoutInventingAConfirmedScanClock() {
        val cached = publishScanResult(WifiAnalyzerState(), listOf(point), false, received)
        assertEquals(listOf(point), snapshotAccessPoints(cached.accessPoints))
        assertNull(cached.lastUpdatedElapsedMs)
        assertEquals(WifiScanStatus.THROTTLED_OR_FAILED, cached.status)
        assertTrue(recommendDatasetChannels(cached, received).isEmpty())
        assertNull(signalSampleForDataset(cached, point.bssid, received))
    }

    @Test fun repeatedCacheOrInvalidReferenceCannotReplaceOrRejuvenateConfirmedSnapshot() {
        val unrelated = point.copy(bssid = "02:00:00:00:00:09", rssiDbm = -20)
        val attempts = listOf(false to received + 301_000, true to -1L, true to null)
        for ((confirmed, time) in attempts) {
            val next = publishScanResult(dataset, listOf(unrelated), confirmed, time)
            assertEquals(dataset.accessPoints, next.accessPoints)
            assertEquals(received, next.lastUpdatedElapsedMs)
            assertTrue(recommendDatasetChannels(next, received + 301_000).isEmpty())
        }
    }

    @Test fun agingDoesNotManufactureOrDeleteHistory() {
        val sample = signalSampleForDataset(dataset, point.bssid, received)!!
        val history = listOf(sample)
        assertEquals(sample, signalSampleForDataset(dataset, point.bssid, received + 31_000))
        assertEquals(history, appendSignalSample(history, signalSampleForDataset(dataset, point.bssid, received + 299_000)!!))
        assertNull(signalSampleForDataset(dataset, point.bssid, received + 301_000))
        assertEquals(point.bssid, dataset.selectedBssid)
        assertEquals(point.bssid, dataset.historyBssid)
        assertEquals(listOf(sample), history)
    }

    @Test fun historyRejectsUnknownFutureAndNegativeMeasurementTimestamps() {
        for (timestamp in listOf(null, -1L, received + 1)) {
            val invalid = dataset.copy(accessPoints = listOf(point.copy(seenAtElapsedMs = timestamp)))
            assertNull(signalSampleForDataset(invalid, point.bssid, received))
        }
        assertNull(signalSampleForDataset(dataset, null, received))
    }

    @Test fun datasetAgeDoesNotWeakenInvalidChannelOrOwnAccessPointExclusion() {
        assertTrue(recommendDatasetChannels(dataset.copy(accessPoints = listOf(point.copy(frequencyMhz = 2477))), received).isEmpty())
        assertTrue(recommendDatasetChannels(dataset.copy(accessPoints = listOf(point.copy(isCurrent = true))), received).isEmpty())
        assertTrue(recommendDatasetChannels(dataset.copy(accessPoints = emptyList()), received).isEmpty())
    }
}
