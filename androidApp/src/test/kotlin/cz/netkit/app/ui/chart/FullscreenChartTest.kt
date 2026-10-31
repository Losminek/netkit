package cz.netkit.app.ui.chart

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

class FullscreenChartTest {
    /** Compose `rotationZ` in screen coordinates (y down): positive degrees turn clockwise. */
    private fun rotate(x: Double, y: Double, degrees: Float): Pair<Int, Int> {
        val a = degrees * PI / 180.0
        return (x * cos(a) - y * sin(a)).roundToInt() to (x * sin(a) + y * cos(a)).roundToInt()
    }

    @Test fun portraitWindowTurnsContentTopToTheScreenRightEdge() {
        val degrees = fullscreenRotationDegrees(width = 1080, height = 1920)
        // Content "up" (0, -1) must point to the screen's right edge (+x): with the phone turned
        // counter-clockwise (top of the phone to the left) that edge is physically on top.
        assertEquals(1 to 0, rotate(0.0, -1.0, degrees))
        // Content "right" (time axis towards now) points down the portrait screen, i.e. to the
        // physical right after the counter-clockwise turn.
        assertEquals(0 to 1, rotate(1.0, 0.0, degrees))
    }

    @Test fun landscapeWindowIsNotRotated() {
        assertEquals(0f, fullscreenRotationDegrees(width = 1920, height = 1080))
        assertEquals(0f, fullscreenRotationDegrees(width = 1000, height = 1000))
    }

    @Test fun scrubFractionUsesThePlotBetweenBothAxes() {
        // 1000 px chart, 100 px value axis, 20 px right inset: plot spans 100..980.
        assertEquals(880f, plotWidth(1000f, 100f, 20f))
        assertEquals(0f, plotFraction(100f, 1000f, 100f, 20f))
        assertEquals(0.5f, plotFraction(540f, 1000f, 100f, 20f))
        assertEquals(1f, plotFraction(980f, 1000f, 100f, 20f))
        // Outside the plot clamps instead of extrapolating.
        assertEquals(0f, plotFraction(10f, 1000f, 100f, 20f))
        assertEquals(1f, plotFraction(999f, 1000f, 100f, 20f))
    }

    @Test fun wideValueAxisIsCappedAtFortyPercent() {
        assertEquals(230f, plotWidth(400f, 300f, 10f))
        assertEquals(1f, plotWidth(10f, 100f, 100f)) // never zero
    }

    @Test fun heldWindowPansByTheSlotsAddedSinceItsAnchor() {
        val slots = listOf<Long?>(1000, 2000, 3000, null, null, 6000, 7000)
        assertEquals(7000L, newestSlotTime(slots))
        assertEquals(0, slotsSince(slots, 7000))
        assertEquals(4, slotsSince(slots, 3000)) // two empty slots and two samples arrived after it
        // Buffer at capacity: the same total, but every slot moved one left. A window held at
        // newest = 7000 must pan by the one new slot to keep showing the same samples.
        val shifted = listOf<Long?>(2000, 3000, null, null, 6000, 7000, 8000)
        assertEquals(1, slotsSince(shifted, 7000))
        assertEquals(5, slotsSince(shifted, 3000))
        // Anchor evicted from the history: whole timeline, the pan clamps to the oldest end.
        assertEquals(7, slotsSince(shifted, 500))
        assertEquals(0, slotsSince(null, 3000))
        assertEquals(null, newestSlotTime(listOf(null, null)))
    }
}
