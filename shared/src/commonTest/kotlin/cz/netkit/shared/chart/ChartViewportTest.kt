package cz.netkit.shared.chart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChartViewportTest {
    @Test fun shortAndEmptyHistory() {
        val viewport = ChartViewport(60)
        assertEquals(0..11, viewport.visible(12))
        assertEquals(60, viewport.visibleSamples)
        assertTrue(viewport.visible(0).isEmpty())
        assertTrue(viewport.visible(-1).isEmpty())
        assertEquals(0f, viewport.panBy(100f, 12).panSamples)
        assertEquals(0..0, ChartViewport(1).visible(1))
        assertFailsWith<IllegalArgumentException> { ChartViewport(0) }
    }

    @Test fun panStopsAtBothHistoryEdges() {
        val oldest = ChartViewport(60).panBy(1000f, 300)
        assertEquals(240f, oldest.panSamples)
        assertEquals(0..59, oldest.visible(300))
        assertEquals(240..299, oldest.panBy(-1000f, 300).visible(300))
        assertEquals(235..294, ChartViewport(60).panBy(5f, 300).visible(300))
        assertEquals(0..59, ChartViewport(60, panSamples = 999f).visible(300))
    }

    @Test fun zoomClampsAndReclampsPanWhenZoomingOut() {
        val zoomed = ChartViewport(60).zoomBy(100f, 300).panBy(1000f, 300)
        assertEquals(10f, zoomed.zoom)
        assertEquals(6, zoomed.visibleSamples)
        assertEquals(294f, zoomed.panSamples)
        assertEquals(0..5, zoomed.visible(300))
        val out = zoomed.zoomBy(0.001f, 300)
        assertEquals(1f, out.zoom)
        assertEquals(240f, out.panSamples)
        assertEquals(0..59, out.visible(300))
        assertEquals(0f, zoomed.panBy(0f, 2).panSamples)
        assertEquals(2, ChartViewport(15, zoom = 10f).visibleSamples)
        assertEquals(1f, zoomed.zoomBy(0f, 300).zoom)
        assertEquals(1f, zoomed.zoomBy(-1f, 300).zoom)
    }

    @Test fun fractionalPanAccumulatesWithoutDrift() {
        val viewport = ChartViewport(60).panBy(0.2f, 300).panBy(0.2f, 300).panBy(0.2f, 300)
        assertEquals(239..298, viewport.visible(300))
        assertEquals(0.6f, viewport.panSamples, 0.0001f)
    }

    @Test fun cursorEdgesAndEmptyRange() {
        assertEquals(20, nearestIndex(-1f, 20..40))
        assertEquals(20, nearestIndex(0f, 20..40))
        assertEquals(30, nearestIndex(0.5f, 20..40))
        assertEquals(40, nearestIndex(1f, 20..40))
        assertEquals(40, nearestIndex(2f, 20..40))
        assertEquals(7, nearestIndex(0.5f, 7..7))
        assertEquals(-1, nearestIndex(0.5f, IntRange.EMPTY))
        // The timeline can contain empty slots to the left of sample zero.
        assertEquals(-48, nearestIndex(0f, -48..11))
        assertEquals(11, nearestIndex(1f, -48..11))
    }

    @Test fun statisticsUseOnlyAvailableValuesInWindow() {
        val values = listOf(100f, null, 2f, 4f, 9f, null)
        assertEquals(WindowStats(2f, 3f, 4f), windowStats(values, 1..3))
        assertNull(windowStats(values, IntRange.EMPTY))
        assertNull(windowStats(values, 1..1))
        assertNull(windowStats(values, 9..20))
        assertNull(windowStats(emptyList(), 0..10))
        assertEquals(WindowStats(100f, 100f, 100f), windowStats(values, -10..0))
        assertNull(windowStats(listOf(Float.NaN, Float.POSITIVE_INFINITY), 0..1))
    }

    @Test fun niceMaximumBoundaries() {
        assertEquals(1f, niceMax(0f))
        assertEquals(1f, niceMax(-50f))
        assertEquals(1f, niceMax(1f))
        assertEquals(2f, niceMax(2f))
        assertEquals(2.5f, niceMax(2.4f))
        assertEquals(2.5f, niceMax(2.5f))
        assertEquals(5f, niceMax(2.6f))
        assertEquals(1000f, niceMax(999f))
        assertEquals(0.1f, niceMax(0.1f))
        assertEquals(0.25f, niceMax(0.24f))
        assertEquals(listOf(0f, 2.5f, 5f, 7.5f, 10f), autoTicks(10f))
    }

    @Test fun invalidGestureInputsDoNotPoisonViewport() {
        val viewport = ChartViewport(60, Float.NaN, Float.NaN).zoomBy(Float.NaN, 300)
        assertEquals(ChartViewport(60), viewport)
        assertEquals(ChartViewport(60), viewport.panBy(Float.NaN, 300))
        assertEquals(10f, viewport.zoomBy(Float.POSITIVE_INFINITY, 300).zoom)
        assertEquals(240f, viewport.panBy(Float.POSITIVE_INFINITY, 300).panSamples)
    }
}
