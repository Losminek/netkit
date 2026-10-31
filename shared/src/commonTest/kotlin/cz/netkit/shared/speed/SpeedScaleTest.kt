package cz.netkit.shared.speed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeedScaleTest {
    @Test fun zeroIsAtStart() {
        assertEquals(0.0, speedDialFraction(0.0))
    }

    @Test fun maximumIsAtEnd() {
        assertEquals(1.0, speedDialFraction(1000.0))
    }

    @Test fun overflowSaturates() {
        assertEquals(1.0, speedDialFraction(1500.0))
        assertEquals(1.0, speedDialFraction(Double.POSITIVE_INFINITY))
    }

    @Test fun invalidReadingsAreAtStart() {
        for (value in listOf(Double.NaN, -1.0, Double.NEGATIVE_INFINITY)) {
            assertEquals(0.0, speedDialFraction(value))
        }
    }

    @Test fun logarithmicMidpointUsesCustomMaximum() {
        // log10(10) / log10(100) = 1/2; distinguishes the scale from linear mapping.
        assertEquals(0.5, speedDialFraction(9.0, maxMbps = 99.0), 1e-12)
        assertEquals(1.0, speedDialFraction(99.0, maxMbps = 99.0))
        assertEquals(1.0, speedDialFraction(100.0, maxMbps = 99.0))
    }

    @Test fun invalidRangesAreAtStart() {
        for (maximum in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(0.0, speedDialFraction(10.0, maximum))
        }
    }

    @Test fun scaleIsStrictlyIncreasingWithinRange() {
        val fractions = (0..1000).map { speedDialFraction(it.toDouble()) }
        assertTrue(fractions.all { it in 0.0..1.0 })
        assertTrue(fractions.zipWithNext().all { (left, right) -> left < right })
    }

    @Test fun labelsMatchTheDial() {
        assertEquals(listOf(0, 10, 50, 100, 250, 500, 1000), SPEED_DIAL_LABELS)
    }
}
