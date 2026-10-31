package cz.netkit.app.ui

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import cz.netkit.app.ui.motion.HANGING_MINUS_GAP_EM
import cz.netkit.app.ui.motion.MINUS_SIGN
import cz.netkit.app.ui.motion.hangingMinusOffset
import cz.netkit.app.ui.motion.MotionToken
import cz.netkit.app.ui.motion.formatSigned
import cz.netkit.app.ui.motion.netKitTween
import cz.netkit.app.ui.motion.numberFormat
import cz.netkit.app.ui.motion.reduceMotionFor
import java.util.Locale
import kotlin.test.*

class DesignSystemTest {
    private val cs = Locale.forLanguageTag("cs-CZ")

    @Test fun gaugeRangesMapDbmToTheArc() {
        // Wi-Fi keeps the M3 scale −90…−30 dBm.
        assertEquals(0f, gaugeFraction(-95.0, GaugeRange.WIFI))
        assertEquals(0.5f, gaugeFraction(-60.0, GaugeRange.WIFI))
        assertEquals(1f, gaugeFraction(-20.0, GaugeRange.WIFI))
        // Mobile RSRP −140…−44 dBm: −92 is the middle, −97 (O2 example) a little below.
        assertEquals(0f, gaugeFraction(-140.0, GaugeRange.CELLULAR))
        assertEquals(0.5f, gaugeFraction(-92.0, GaugeRange.CELLULAR))
        assertEquals(1f, gaugeFraction(-44.0, GaugeRange.CELLULAR))
        assertTrue(gaugeFraction(-97.0, GaugeRange.CELLULAR)!! in 0.44f..0.45f)
        assertNull(gaugeFraction(null, GaugeRange.CELLULAR))
        assertNull(gaugeFraction(Double.NaN, GaugeRange.WIFI))
        assertNull(gaugeFraction(-60.0, -30f..-30f))
    }

    @Test fun animatorScaleZeroMeansReducedMotion() {
        assertTrue(reduceMotionFor(0f))
        assertFalse(reduceMotionFor(0.5f))
        assertFalse(reduceMotionFor(1f))
    }

    @Test fun reducedMotionSnapsAndTokensKeepTheirDurations() {
        assertIs<SnapSpec<Float>>(netKitTween<Float>(MotionToken.GAUGE, reduceMotion = true))
        val tween = assertIs<TweenSpec<Float>>(netKitTween<Float>(MotionToken.BASE, reduceMotion = false))
        assertEquals(320, tween.durationMillis)
        assertEquals(listOf(180, 320, 600, 800), MotionToken.entries.map { it.durationMillis })
    }

    @Test fun numbersUseLocaleDecimalsAndTypographicMinus() {
        assertEquals("$MINUS_SIGN" + "67", formatSigned(-67.0, numberFormat(cs, 0, false)).toString())
        assertEquals("12,5", formatSigned(12.46, numberFormat(cs, 1, false)).toString())
        assertEquals("12.5", formatSigned(12.46, numberFormat(Locale.US, 1, false)).toString())
        assertEquals("1200", formatSigned(1200.0, numberFormat(cs, 0, false)).toString())
    }

    @Test fun valueRoundingToZeroHasNoMinus() {
        val signed = formatSigned(-0.4, numberFormat(cs, 0, false))
        assertFalse(signed.negative)
        assertEquals("0", signed.digits)
        val hanging = formatSigned(-71.6, numberFormat(cs, 0, false))
        assertTrue(hanging.negative)
        assertEquals("72", hanging.digits)
    }

    @Test fun nonAsciiDigitLocalesKeepTheMinus() {
        val arabic = Locale.forLanguageTag("ar-EG-u-nu-arab")
        val signed = formatSigned(-65.0, numberFormat(arabic, 0, false))
        assertTrue(signed.digits.none { it in '0'..'9' }, "precondition: Arabic-Indic digits, got ${signed.digits}")
        assertTrue(signed.negative)
        assertTrue(signed.toString().startsWith(MINUS_SIGN))
        assertFalse(formatSigned(-0.4, numberFormat(arabic, 0, false)).negative)
        val persian = formatSigned(-0.06, numberFormat(Locale.forLanguageTag("fa-IR-u-nu-arabext"), 1, false))
        assertTrue(persian.negative, "−0.06 rounds to −0.1 (HALF_UP)")
        assertFalse(formatSigned(-0.04, numberFormat(cs, 1, false)).negative)
        assertTrue(formatSigned(Double.NEGATIVE_INFINITY, numberFormat(cs, 0, false)).negative)
        assertFalse(formatSigned(Double.NaN, numberFormat(cs, 0, false)).negative)
    }

    @Test fun statTilesShareByWeightWhenEverythingFits() {
        val lines = statTileLines(needs = listOf(60, 80, 40), weights = listOf(1f, 1.45f, 0.8f), width = 340, gap = 8)
        assertEquals(1, lines.size)
        val widths = lines.single().map { it.second }
        assertEquals(340 - 16, widths.sum())
        assertTrue(widths[1] > widths[0] && widths[0] > widths[2], "weights 1 / 1.45 / 0.8 order: $widths")
    }

    @Test fun statTileNeverGetsLessThanItsContent() {
        // The weighted share of the last tile (0.8) would be 79 px; its content needs 110.
        val line = statTileLines(listOf(60, 80, 110), listOf(1f, 1.45f, 0.8f), 340, 8).single()
        assertTrue(line[2].second >= 110, "$line")
        assertEquals(340 - 16, line.sumOf { it.second })
        line.forEachIndexed { i, (_, w) -> assertTrue(w >= listOf(60, 80, 110)[i]) }
    }

    @Test fun statTilesWrapInsteadOfEllipsizing() {
        val lines = statTileLines(listOf(120, 150, 100), listOf(1f, 1.45f, 0.8f), 300, 8)
        assertEquals(listOf(listOf(0, 1), listOf(2)), lines.map { line -> line.map { it.first } })
        assertEquals(300 - 8, lines[0].sumOf { it.second })
        assertEquals(300, lines[1].single().second)
    }

    @Test fun oversizedSingleTileTakesTheWholeRow() {
        val lines = statTileLines(listOf(400), listOf(1f), 300, 8)
        assertEquals(listOf(listOf(0 to 300)), lines)
    }

    @Test fun unitHasNoLineBreakOpportunities() {
        assertEquals("Mb/\u2060s", unbreakableUnit("Mb/s"))
        assertEquals("Mbit/\u2060s", unbreakableUnit("Mbit/s"))
        assertEquals("/\u2060\u00A02\u00A0Mb/\u2060s", unbreakableUnit("/ 2 Mb/s"))
        assertEquals("dBm", unbreakableUnit("dBm"))
        // No joiner after a trailing slash or hyphen: nothing follows that could be broken off.
        assertEquals("a/", unbreakableUnit("a/"))
    }

    @Test fun headerWordsKeepHyphenatedCompoundsTogether() {
        assertEquals(listOf("Wake-on-LAN"), headerWords("Wake-on-LAN"))
        assertEquals(listOf("Moje", "Wi-Fi", "doma"), headerWords("Moje  Wi-Fi doma"))
        assertEquals(listOf("Síť\u00A0doma"), headerWords("Síť\u00A0doma"))
        assertEquals(emptyList(), headerWords(""))
    }

    @Test fun hangingMinusGapIsMeasuredBetweenInk() {
        // Digits ink starts 3 px after their origin, minus ink ends 18 px after its origin, 4 px gap:
        // the minus origin goes 19 px left of the digits' origin, so the ink gap is exactly 4 px.
        val offset = hangingMinusOffset(digitsInkLeft = 3f, minusInkRight = 18f, gapPx = 4f)
        assertEquals(-19f, offset)
        assertEquals(4f, 3f - (offset + 18f))
        assertEquals(0.04f, HANGING_MINUS_GAP_EM)
    }
}
