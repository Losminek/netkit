package cz.netkit.app.lan

import cz.netkit.app.R
import kotlin.test.*

class LanSettingsTest {
    @Test fun hiddenProbeFieldsNeverBlockDnsOrMdns() {
        for(tool in listOf(LanTool.DNS,LanTool.MDNS)) {
            assertNull(validatedProbeSettings(tool,"invalid","invalid").error)
        }
    }
    @Test fun pingAndTraceValidateOnlyVisibleTimeout() {
        for(tool in listOf(LanTool.PING,LanTool.TRACE)) {
            assertNull(validatedProbeSettings(tool,"1000","invalid").error)
            assertEquals(R.string.lan_invalid_timeout,validatedProbeSettings(tool,"0","32").error)
        }
    }
    @Test fun scannerValidatesBothFieldsAndBoundaries() {
        for(tool in listOf(LanTool.DISCOVERY,LanTool.PORTS)) {
            assertNull(validatedProbeSettings(tool,"100","1").error)
            assertNull(validatedProbeSettings(tool,"5000","64").error)
            assertEquals(R.string.lan_invalid_timeout,validatedProbeSettings(tool,"5001","64").error)
            assertEquals(R.string.lan_invalid_concurrency,validatedProbeSettings(tool,"1000","65").error)
        }
    }
}
