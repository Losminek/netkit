package cz.netkit.shared.speed

import cz.netkit.shared.net.NetKind
import kotlin.test.*

class SpeedDataEstimateTest {
    private fun record(kind: NetKind?, down: Double?, up: Double? = null, ts: Long = 1) =
        SpeedRecord(ts, SpeedEngine.LIBRESPEED, "zelina", downloadMbps = down, uploadMbps = up, networkKind = kind)

    @Test fun withoutHistoryIsConservative() {
        assertEquals(SpeedDataEstimate(SPEED_DATA_FALLBACK_MB, false), estimateSpeedTestData(SpeedEngine.LIBRESPEED, emptyList(), NetKind.CELLULAR))
        assertEquals(200, SPEED_DATA_FALLBACK_MB)
    }

    @Test fun usesNewestResultOfTheSameKind() {
        // 100 Mbit/s down and 20 up for 10 s each: 125 + 25 = 150 MB, × 1.2 = 180 MB.
        val history = listOf(record(NetKind.WIFI, 900.0, 900.0, 3), record(NetKind.CELLULAR, 100.0, 20.0, 2), record(NetKind.CELLULAR, 5.0, 1.0, 1))
        assertEquals(SpeedDataEstimate(180, true), estimateSpeedTestData(SpeedEngine.LIBRESPEED, history, NetKind.CELLULAR))
    }

    @Test fun ndt7UsesTheLongerDownloadCap() {
        // 100 × 15 s + 20 × 10 s = 1700 Mbit = 212.5 MB, × 1.2 = 255 → 260 MB.
        val history = listOf(record(NetKind.CELLULAR, 100.0, 20.0))
        assertEquals(260, estimateSpeedTestData(SpeedEngine.NDT7, history, NetKind.CELLULAR).megabytes)
    }

    @Test fun missingUploadCountsAsDownloadAndSmallValuesRoundUp() {
        // 40 × 10 + 40 × 10 = 800 Mbit = 100 MB, × 1.2 = 120 MB.
        assertEquals(120, estimateSpeedTestData(SpeedEngine.LIBRESPEED, listOf(record(NetKind.CELLULAR, 40.0)), NetKind.CELLULAR).megabytes)
        assertEquals(10, estimateSpeedTestData(SpeedEngine.LIBRESPEED, listOf(record(NetKind.CELLULAR, 0.5, 0.1)), NetKind.CELLULAR).megabytes)
    }

    @Test fun oldRecordsCountAsWifiAndFailedOnesAreSkipped() {
        val history = listOf(record(NetKind.CELLULAR, null), record(null, 80.0, 80.0))
        assertEquals(SpeedDataEstimate(200, false), estimateSpeedTestData(SpeedEngine.LIBRESPEED, history, NetKind.CELLULAR))
        // 80 × 10 × 2 = 1600 Mbit = 200 MB, × 1.2 = 240 MB.
        assertEquals(SpeedDataEstimate(240, true), estimateSpeedTestData(SpeedEngine.LIBRESPEED, history, NetKind.WIFI))
    }
}
