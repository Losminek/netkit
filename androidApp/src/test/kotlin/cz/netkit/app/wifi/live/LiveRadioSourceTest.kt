package cz.netkit.app.wifi.live

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveRadioSourceTest {
    private val ap1 = "AA:BB:CC:00:00:01"
    private val ap2 = "aa:bb:cc:00:00:02"

    @Test fun belowApi31OnlyLegacyIsUsed() {
        assertEquals(LiveRadioSource.LEGACY, selectLiveRadioSource(30, false, null, true, ap1))
        assertEquals(LiveRadioSource.LEGACY, selectLiveRadioSource(29, true, ap1, true, ap2), "callback info is ignored below 31")
        assertEquals(LiveRadioSource.NONE, selectLiveRadioSource(29, false, null, false, null))
    }

    @Test fun api31UsesLegacyRadioOnlyForTheSameAp() {
        assertEquals(LiveRadioSource.LEGACY, selectLiveRadioSource(35, true, ap1, true, ap1.lowercase()))
        assertEquals(LiveRadioSource.CALLBACK, selectLiveRadioSource(35, true, ap1, true, ap2), "roam in progress")
        assertEquals(LiveRadioSource.CALLBACK, selectLiveRadioSource(35, true, ap1, false, null))
    }

    @Test fun api31RedactedIdentitiesNeverMatch() {
        val redacted = "02:00:00:00:00:00"
        assertEquals(LiveRadioSource.CALLBACK, selectLiveRadioSource(33, true, redacted, true, redacted))
        assertEquals(LiveRadioSource.CALLBACK, selectLiveRadioSource(33, true, null, true, null))
        assertEquals(LiveRadioSource.LEGACY, selectLiveRadioSource(33, false, null, true, redacted), "fallback without callback info")
    }

    @Test fun ssidAndLinkRateNormalization() {
        assertEquals("Home", liveSsid("\"Home\""))
        assertEquals("Home", liveSsid(" Home "))
        assertNull(liveSsid("<unknown ssid>"))
        assertNull(liveSsid("\"\""))
        assertNull(liveSsid(null))
        assertNull(liveLinkRate(-1))
        assertNull(liveLinkRate(0))
        assertEquals(866, liveLinkRate(866))
    }
}
