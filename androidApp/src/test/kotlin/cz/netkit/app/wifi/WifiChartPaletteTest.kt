package cz.netkit.app.wifi

import cz.netkit.shared.wifi.WIFI_CHART_AP_LIMIT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WifiChartPaletteTest {
    @Test fun everySeriesAndAxisExceedsNonTextContrastInBothModes() {
        for (dark in listOf(false, true)) {
            val palette = wifiChartPalette(dark)
            assertEquals(WIFI_CHART_AP_LIMIT, palette.series.size)
            assertEquals(palette.series.size, palette.series.distinct().size)
            for (color in palette.series + palette.axis) {
                assertEquals(255, color ushr 24)
                assertTrue(chartContrast(color, palette.background) >= 3.0, "dark=$dark color=$color")
            }
        }
    }

    @Test fun contrastCalculationMatchesReferenceExtremesAndIsSymmetric() {
        assertEquals(21.0, chartContrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 1e-12)
        assertEquals(1.0, chartContrast(0xFF0072B2.toInt(), 0xFF0072B2.toInt()), 1e-12)
        assertEquals(4.968019855769922, chartContrast(0xFF0072B2.toInt(), 0xFFFAFAFA.toInt()), 1e-12)
        assertEquals(chartContrast(0xFF0072B2.toInt(), 0xFFFAFAFA.toInt()),
            chartContrast(0xFFFAFAFA.toInt(), 0xFF0072B2.toInt()), 1e-12)
    }
}
