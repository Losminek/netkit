package cz.netkit.app.wifi

import kotlin.test.*

class WifiScanWindowTest {
    @Test fun fourAcceptedRequestsAndSlidingExpiry() {
        var now = 0L
        val window = WifiScanWindow { now }
        assertEquals(WifiScanBudget(4, 0), window.budget())
        for (index in 0..3) {
            now = index * 10_000L
            window.accepted()
            assertEquals(3 - index, window.budget().remaining)
        }
        assertEquals(WifiScanBudget(0, 90), window.budget())
        now = 119_001
        assertEquals(WifiScanBudget(0, 1), window.budget())
        now = 120_000
        assertEquals(WifiScanBudget(1, 0), window.budget())
        now = 140_000
        assertEquals(WifiScanBudget(3, 0), window.budget())
        now = 150_000
        assertEquals(WifiScanBudget(4, 0), window.budget())
    }

    @Test fun budgetDoesNotReserveOrBlockRequests() {
        var now = 1000L
        val window = WifiScanWindow { now }
        repeat(4) { window.accepted() }
        repeat(10) { assertEquals(WifiScanBudget(0, 120), window.budget()) }
        now = 2000
        window.accepted() // Android may have throttling disabled; a fifth success is allowed.
        assertEquals(WifiScanBudget(0, 119), window.budget())
        now = 121_000
        assertEquals(WifiScanBudget(3, 0), window.budget())
    }

    @Test fun exactBoundaryAndCeilingSeconds() {
        var now = 5000L
        val window = WifiScanWindow { now }
        repeat(4) { window.accepted() }
        now = 5001
        assertEquals(120, window.budget().waitSeconds)
        now = 124_999
        assertEquals(1, window.budget().waitSeconds)
        now = 125_000
        assertEquals(WifiScanBudget(4, 0), window.budget())
    }
}
