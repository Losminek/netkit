package cz.netkit.shared.cellular

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CellularTest {
    private val u = CELL_UNAVAILABLE

    private fun lte(rsrp: Int = u, rsrq: Int = u, rssnr: Int = u, rssi: Int = u, level: Int = 0) =
        CellStrengthReading.Lte(rsrp, rsrq, rssnr, rssi, level)

    private fun nr(
        ssRsrp: Int = u, ssRsrq: Int = u, ssSinr: Int = u,
        csiRsrp: Int = u, csiRsrq: Int = u, csiSinr: Int = u, level: Int = 0,
    ) = CellStrengthReading.Nr(ssRsrp, ssRsrq, ssSinr, csiRsrp, csiRsrq, csiSinr, level)

    private fun signal(source: CellRat, rsrp: Int? = null, rssi: Int? = null) =
        CellSignal(rsrp, null, null, rssi, null, source)

    // --- Ranges and UNAVAILABLE -------------------------------------------------------------

    @Test fun unavailableAndOutOfRangeBecomeNull() {
        assertNull(cellValue(CELL_UNAVAILABLE, -140..-43))
        assertNull(cellValue(-141, -140..-43))
        assertNull(cellValue(-42, -140..-43))
        assertEquals(-140, cellValue(-140, -140..-43))
        assertEquals(-43, cellValue(-43, -140..-43))
        assertEquals(CELL_UNAVAILABLE, Int.MAX_VALUE, "mirror of CellInfo.UNAVAILABLE")
    }

    @Test fun lteRangesFollowAndroidDocs() {
        val s = cellSignalOf(lte(rsrp = -95, rsrq = -11, rssnr = 12, rssi = -70, level = 3))!!
        assertEquals(CellSignal(-95, -11, 12, -70, 3, CellRat.LTE), s)
        val edges = cellSignalOf(lte(rsrp = -140, rsrq = -34, rssnr = -20, rssi = -113, level = 0))!!
        assertEquals(CellSignal(-140, -34, -20, -113, 0, CellRat.LTE), edges)
        val upper = cellSignalOf(lte(rsrp = -43, rsrq = 3, rssnr = 30, rssi = -51, level = 4))!!
        assertEquals(CellSignal(-43, 3, 30, -51, 4, CellRat.LTE), upper)
        val outside = cellSignalOf(lte(rsrp = -42, rsrq = 4, rssnr = 31, rssi = -50, level = 5))
        assertNull(outside, "every value out of range = no reading")
        val partial = cellSignalOf(lte(rsrp = -150, rsrq = -12, level = 7))!!
        assertNull(partial.rsrpDbm)
        assertEquals(-12, partial.rsrqDb)
        assertNull(partial.level, "level outside 0..4")
        assertEquals(CellQuality.NONE, cellQuality(partial), "no RSRP = no quality")
    }

    @Test fun nrUsesSsWithCsiFallback() {
        val ss = cellSignalOf(nr(ssRsrp = -97, ssRsrq = -12, ssSinr = 8, csiRsrp = -80, csiRsrq = -5, csiSinr = 20, level = 2))!!
        assertEquals(CellSignal(-97, -12, 8, null, 2, CellRat.NR_SA), ss)
        val csi = cellSignalOf(nr(csiRsrp = -101, csiRsrq = -9, csiSinr = 4, level = 2), nrWithLteAnchor = true)!!
        assertEquals(CellSignal(-101, -9, 4, null, 2, CellRat.NR_NSA), csi)
        // SS ranges are wider than CSI: SS-RSRQ 20 dB is valid, CSI-RSRQ 0 dB is not.
        assertEquals(20, cellSignalOf(nr(ssRsrp = -90, ssRsrq = 20))!!.rsrqDb)
        assertNull(cellSignalOf(nr(ssRsrp = -90, csiRsrq = 0))!!.rsrqDb)
        assertEquals(40, cellSignalOf(nr(ssRsrp = -90, ssSinr = 40))!!.sinrDb)
        assertNull(cellSignalOf(nr(ssRsrp = -90, csiSinr = 24))!!.sinrDb)
        assertEquals(-156, cellSignalOf(nr(ssRsrp = -156))!!.rsrpDbm)
        assertEquals(-31, cellSignalOf(nr(ssRsrp = -31))!!.rsrpDbm)
        assertNull(cellSignalOf(nr(ssRsrp = -157)))
        assertNull(cellSignalOf(nr(ssRsrp = -30)))
        assertNull(cellSignalOf(nr()))
    }

    @Test fun threeGAndTwoGUseRssiField() {
        assertEquals(CellSignal(null, null, null, -90, 3, CellRat.UMTS), cellSignalOf(CellStrengthReading.Wcdma(-90, 3)))
        assertEquals(CellSignal(null, null, null, -120, 0, CellRat.UMTS), cellSignalOf(CellStrengthReading.Tdscdma(-120, 0)))
        assertNull(cellSignalOf(CellStrengthReading.Wcdma(-121, 1)))
        assertNull(cellSignalOf(CellStrengthReading.Wcdma(-23, 1)))
        assertEquals(CellSignal(null, null, null, -51, 4, CellRat.GSM), cellSignalOf(CellStrengthReading.Gsm(-51, 4)))
        assertNull(cellSignalOf(CellStrengthReading.Gsm(-114, 0)))
        assertNull(cellSignalOf(CellStrengthReading.Gsm(u, 0)))
    }

    // --- Selection ---------------------------------------------------------------------------

    @Test fun nsaReportPrefersNrLegAndMarksItNsa() {
        val report = listOf(lte(rsrp = -88, rsrq = -10, rssnr = 9), nr(ssRsrp = -97, ssRsrq = -11, ssSinr = 3))
        val s = selectCellSignal(report)!!
        assertEquals(-97, s.rsrpDbm)
        assertEquals(CellRat.NR_NSA, s.source)
        assertTrue(hasNrSignal(report))
    }

    @Test fun idleNrLegFallsBackToLte() {
        val report = listOf(nr(), lte(rsrp = -88, rsrq = -10))
        val s = selectCellSignal(report)!!
        assertEquals(CellRat.LTE, s.source)
        assertEquals(-88, s.rsrpDbm)
        assertFalse(hasNrSignal(report), "NR reading without values is no NR signal")
    }

    @Test fun selectionOrderAndEmptyReports() {
        assertEquals(CellRat.NR_SA, selectCellSignal(listOf(nr(ssRsrp = -105)))!!.source)
        assertEquals(CellRat.UMTS, selectCellSignal(listOf(CellStrengthReading.Gsm(-80, 4), CellStrengthReading.Wcdma(-100, 2)))!!.source)
        assertEquals(-85, selectCellSignal(listOf(lte(rsrp = -101), lte(rsrp = -85)))!!.rsrpDbm, "strongest of the same type")
        val onlyQuality = selectCellSignal(listOf(lte(rsrq = -12)))!!
        assertNull(onlyQuality.rsrpDbm)
        assertNull(selectCellSignal(emptyList()))
        assertNull(selectCellSignal(listOf(lte(), nr())))
    }

    // --- Quality thresholds --------------------------------------------------------------------

    @Test fun rsrpThresholds() {
        for (rat in listOf(CellRat.LTE, CellRat.NR_NSA, CellRat.NR_SA)) {
            assertEquals(CellQuality.EXCELLENT, cellQuality(signal(rat, rsrp = -60)))
            assertEquals(CellQuality.EXCELLENT, cellQuality(signal(rat, rsrp = -80)))
            assertEquals(CellQuality.GOOD, cellQuality(signal(rat, rsrp = -81)))
            assertEquals(CellQuality.GOOD, cellQuality(signal(rat, rsrp = -90)))
            assertEquals(CellQuality.FAIR, cellQuality(signal(rat, rsrp = -91)))
            assertEquals(CellQuality.FAIR, cellQuality(signal(rat, rsrp = -100)))
            assertEquals(CellQuality.POOR, cellQuality(signal(rat, rsrp = -101)))
            assertEquals(CellQuality.NONE, cellQuality(signal(rat, rssi = -60)), "LTE/NR quality only from RSRP")
        }
    }

    @Test fun threeGAndTwoGThresholds() {
        assertEquals(CellQuality.EXCELLENT, cellQuality(signal(CellRat.UMTS, rssi = -85)))
        assertEquals(CellQuality.GOOD, cellQuality(signal(CellRat.UMTS, rssi = -86)))
        assertEquals(CellQuality.GOOD, cellQuality(signal(CellRat.UMTS, rssi = -95)))
        assertEquals(CellQuality.FAIR, cellQuality(signal(CellRat.UMTS, rssi = -105)))
        assertEquals(CellQuality.POOR, cellQuality(signal(CellRat.UMTS, rssi = -106)))
        assertEquals(CellQuality.EXCELLENT, cellQuality(signal(CellRat.GSM, rssi = -89)))
        assertEquals(CellQuality.GOOD, cellQuality(signal(CellRat.GSM, rssi = -97)))
        assertEquals(CellQuality.FAIR, cellQuality(signal(CellRat.GSM, rssi = -103)))
        assertEquals(CellQuality.POOR, cellQuality(signal(CellRat.GSM, rssi = -104)))
        assertEquals(CellQuality.NONE, cellQuality(signal(CellRat.GSM, rsrp = -70)))
    }

    @Test fun noSignalIsNone() {
        assertEquals(CellQuality.NONE, cellQuality(null))
        assertNull(primaryDbm(null))
        assertEquals(CellQuality.FAIR, cellQuality(signal(CellRat.UNKNOWN, rsrp = -95)))
        assertEquals(CellQuality.GOOD, cellQuality(signal(CellRat.UNKNOWN, rssi = -95)))
        assertEquals(-95, primaryDbm(signal(CellRat.UNKNOWN, rssi = -95)))
    }

    // --- RAT ---------------------------------------------------------------------------------

    @Test fun networkTypeMapping() {
        val twoG = listOf(1, 2, 4, 7, 11, 16)
        val threeG = listOf(3, 5, 6, 8, 9, 10, 12, 14, 15, 17)
        for (t in twoG) assertEquals(CellRat.GSM, cellRatOfNetworkType(t), "type $t")
        for (t in threeG) assertEquals(CellRat.UMTS, cellRatOfNetworkType(t), "type $t")
        assertEquals(CellRat.LTE, cellRatOfNetworkType(13))
        assertEquals(CellRat.LTE, cellRatOfNetworkType(19), "hidden LTE_CA")
        assertEquals(CellRat.NR_SA, cellRatOfNetworkType(20))
        for (t in listOf(null, 0, 18, 21, -1, CELL_UNAVAILABLE)) assertEquals(CellRat.UNKNOWN, cellRatOfNetworkType(t), "type $t")
    }

    @Test fun resolveRatAllCombinations() {
        val types = listOf(null, CellNetworkType.UNKNOWN, CellNetworkType.GSM, CellNetworkType.HSPAP,
            CellNetworkType.LTE, CellNetworkType.LTE_CA, CellNetworkType.NR, CellNetworkType.IWLAN)
        val overrides = listOf(null, CellOverrideType.NONE, CellOverrideType.LTE_CA, CellOverrideType.LTE_ADVANCED_PRO,
            CellOverrideType.NR_NSA, CellOverrideType.NR_NSA_MMWAVE, CellOverrideType.NR_ADVANCED)
        val nsaOverrides = setOf(CellOverrideType.NR_NSA, CellOverrideType.NR_NSA_MMWAVE, CellOverrideType.NR_ADVANCED)
        for (type in types) for (override in overrides) for (nr in listOf(false, true)) {
            val expected = when (type) {
                CellNetworkType.NR -> CellRat.NR_SA
                CellNetworkType.LTE, CellNetworkType.LTE_CA -> if (override in nsaOverrides || nr) CellRat.NR_NSA else CellRat.LTE
                CellNetworkType.GSM -> CellRat.GSM
                CellNetworkType.HSPAP -> CellRat.UMTS
                else -> if (override in nsaOverrides) CellRat.NR_NSA else CellRat.UNKNOWN
            }
            assertEquals(expected, resolveRat(type, override, nr), "type=$type override=$override nr=$nr")
        }
    }

    @Test fun saOnlyWithNrDataType() {
        assertEquals(CellRat.NR_SA, resolveRat(CellNetworkType.NR, null, false))
        assertEquals(CellRat.NR_SA, resolveRat(CellNetworkType.NR, CellOverrideType.NR_ADVANCED, true))
        assertEquals(CellRat.UNKNOWN, resolveRat(null, null, true), "NR signal alone does not prove SA")
        assertEquals(CellRat.NR_NSA, resolveRat(CellNetworkType.LTE, CellOverrideType.NR_NSA, false), "O2 5G NSA")
    }

    @Test fun simRatFallsBackToSignalSourceButNeverToSa() {
        val lteSignal = signal(CellRat.LTE, rsrp = -90)
        assertEquals(CellRat.LTE, simRat(null, null, lteSignal, false))
        assertEquals(CellRat.NR_NSA, simRat(null, null, signal(CellRat.NR_NSA, rsrp = -95), true))
        assertEquals(CellRat.UNKNOWN, simRat(null, null, signal(CellRat.NR_SA, rsrp = -95), true))
        assertEquals(CellRat.UMTS, simRat(0, null, signal(CellRat.UMTS, rssi = -90), false))
        assertEquals(CellRat.UNKNOWN, simRat(null, null, null, false))
        assertEquals(CellRat.NR_SA, simRat(CellNetworkType.NR, null, lteSignal, false), "network type wins")
    }

    @Test fun operatorNameFallbacks() {
        assertEquals("O2 CZ", cellOperatorName(" O2 CZ ", "O2 Czech Republic", "O2"))
        assertEquals("O2 Czech Republic", cellOperatorName("", "O2 Czech Republic", "O2"))
        assertEquals("Test eSIM", cellOperatorName(null, "  ", "Test eSIM"))
        assertNull(cellOperatorName(null, "", " "))
    }
}
