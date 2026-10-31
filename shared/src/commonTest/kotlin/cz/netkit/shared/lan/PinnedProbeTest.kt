package cz.netkit.shared.lan

import kotlin.test.*
import kotlinx.coroutines.test.runTest

class PinnedProbeTest {
    @Test fun changedDnsAnswerCannotChangeConfirmedTarget() = runTest {
        var dnsAnswer="192.0.2.10"; var resolutions=0
        val pinned=pinLanHost("wifi-A","router.example") { _,_ -> resolutions++; dnsAnswer }
        // The user confirms the displayed first answer; a later answer must never be consulted.
        dnsAnswer="192.0.2.20"
        val probes=mutableListOf<String>()
        probePinnedHost(pinned,"wifi-A") { _,ip -> probes+=ip }
        assertEquals(1,resolutions)
        assertEquals(listOf("192.0.2.10"),probes)
        assertFailsWith<IllegalStateException> { probePinnedHost(pinned,"wifi-B") { _,ip -> probes+=ip } }
        assertEquals(listOf("192.0.2.10"),probes)
    }
}
