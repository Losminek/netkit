package cz.netkit.shared

import cz.netkit.shared.wol.directedBroadcast
import cz.netkit.shared.wol.parseIpv4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Runs on Kotlin/Native without a socket or platform service. */
class IosDomainSmokeTest {
    @Test
    fun ipv4ParserAndBroadcastRunOnNative() {
        val address = assertNotNull(parseIpv4("192.0.2.42"))
        assertEquals("192.0.2.42", address.toString())
        assertEquals("192.0.2.255", directedBroadcast(address, 24)?.toString())
        assertNull(parseIpv4("192.0.2.256"))
        assertNull(directedBroadcast(address, 32))
    }
}
