package cz.netkit.app.speed

import cz.netkit.shared.speed.SPEED_DIAL_LABELS
import cz.netkit.shared.speed.speedDialFraction
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeedDialTest {
    // 320 dp dial at scale 1: label ring 92, label boxes about 7 × 16 ("0") to 28 × 16 ("1000").
    private val labels = SPEED_DIAL_LABELS.map { value ->
        DialLabel(speedDialFraction(value.toDouble()).toFloat(), halfWidth = 3.5f * value.toString().length, halfHeight = 8f)
    }

    @Test fun needleKeepsHalfHeightAndClearanceInsideTheLabelRing() {
        val tip = speedNeedleRadius(92f, labels, clearance = 6f)
        assertTrue(tip <= 92f - 8f - 6f, "tip $tip")
        // "1000" lies on the horizontal; its half width (14) decides.
        assertEquals(92f - 14f - 6f, tip, 0.01f)
    }

    @Test fun noNeedlePositionTouchesAnyLabelBox() {
        val tip = speedNeedleRadius(92f, labels, clearance = 6f)
        for (step in 0..200) {
            val angle = PI * (1.0 + step / 200.0)
            labels.forEach { label ->
                val a = PI * (1.0 + label.fraction)
                val cx = 92 * cos(a); val cy = 92 * sin(a)
                // Distance from the tip to the label box (axis-aligned around its centre).
                val dx = (abs(tip * cos(angle) - cx) - label.halfWidth).coerceAtLeast(0.0)
                val dy = (abs(tip * sin(angle) - cy) - label.halfHeight).coerceAtLeast(0.0)
                assertTrue(hypot(dx, dy) >= 6.0 - 1e-3, "step $step label ${label.fraction}")
            }
        }
    }

    @Test fun emptyOrHugeLabelsNeverGiveNegativeLength() {
        assertEquals(86f, speedNeedleRadius(92f, emptyList(), 6f))
        assertEquals(0f, speedNeedleRadius(20f, listOf(DialLabel(0f, 50f, 50f)), 6f))
    }
}
