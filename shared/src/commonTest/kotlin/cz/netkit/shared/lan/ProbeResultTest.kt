package cz.netkit.shared.lan

import kotlin.test.*

class ProbeResultTest {
    @Test fun countersPreserveEachOutcome() {
        assertEquals(ProbeCounts(1,1,1,1), ProbeStatus.entries.fold(ProbeCounts()) { counts,status -> counts.add(status) })
    }
    @Test fun traceConsentComparesDefaultNetworkIdentity() {
        assertFalse(requiresTraceRouteConfirmation("wifi-A", "wifi-A"))
        assertTrue(requiresTraceRouteConfirmation("wifi-A", "VPN"))
        assertTrue(requiresTraceRouteConfirmation("wifi-A", "mobile"))
        assertTrue(requiresTraceRouteConfirmation("wifi-A", null))
    }
}
