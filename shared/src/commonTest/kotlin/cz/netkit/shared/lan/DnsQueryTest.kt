package cz.netkit.shared.lan

import kotlin.test.*

class DnsQueryTest {
    @Test fun ptrQueryContainsReversedNameAndExactInPtrQuestion() {
        val packet=ipv4PtrQuery("10.0.0.1",0x1234)
        assertEquals(listOf(0x12,0x34,1,0,0,1,0,0,0,0,0,0),packet.take(12).map { it.toInt() and 255 })
        assertContentEquals(byteArrayOf(1,49,1,48,1,48,2,49,48,7,105,110,45,97,100,100,114,4,97,114,112,97,0,0,12,0,1),packet.copyOfRange(12,packet.size))
        assertFailsWith<IllegalArgumentException> { ipv4PtrQuery("bad",1) }
        assertFailsWith<IllegalArgumentException> { ipv4PtrQuery("10.2.3.4",65536) }
    }
}
