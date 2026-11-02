package cz.netkit.app.wifi

import android.net.wifi.WifiManager
import kotlin.test.*

class ForegroundScanObserverTest {
    @Test fun foregroundOnlyAndLateCallbacksIgnored() {
        val callbacks = mutableListOf<(Boolean) -> Unit>()
        var registrations = 0
        var removals = 0
        var publications = 0
        val observer = ForegroundScanObserver({ callback ->
            registrations++; callbacks += callback
            ({ removals++ })
        }, { publications++ })
        assertFalse(observer.active)
        observer.setActive(true); observer.setActive(true)
        assertEquals(1, registrations)
        callbacks[0](false)
        assertEquals(0, publications, "EXTRA_RESULTS_UPDATED=false must not publish")
        callbacks[0](true)
        assertEquals(1, publications)
        observer.setActive(false); observer.setActive(false)
        assertFalse(observer.active)
        assertEquals(1, removals)
        callbacks[0](true)
        assertEquals(1, publications)
        observer.setActive(true)
        callbacks[0](true) // A queued event from the old registration stays invalid after re-entry.
        assertEquals(1, publications)
        callbacks[1](true)
        assertEquals(2, publications)
        observer.setActive(false)
        assertEquals(registrations, removals)
    }

    @Test fun cachedEmptyFutureAndDuplicateSamplesCannotBecomeNew() {
        assertNull(newPassiveScanTime(emptyList(), 2000, 1000))
        assertNull(newPassiveScanTime(listOf(0, 900, 1000, 2100), 2000, 1000))
        assertEquals(1900, newPassiveScanTime(listOf(900, 1500, 1900, 2100), 2000, 1000))
        assertNull(newPassiveScanTime(listOf(1900), 2500, 1900))
        assertEquals(2400, newPassiveScanTime(listOf(1900, 2400), 2500, 1900))
    }

    @Test fun api29BroadcastRequiresMatchingActionAndUpdatedExtra() {
        assertFalse(isUpdatedWifiScan(null, true))
        assertFalse(isUpdatedWifiScan("unrelated.action", true))
        assertFalse(isUpdatedWifiScan(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION, false))
        assertTrue(isUpdatedWifiScan(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION, true))
    }

}
