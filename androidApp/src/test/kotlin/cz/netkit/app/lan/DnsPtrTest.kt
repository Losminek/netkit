package cz.netkit.app.lan

import kotlin.test.*

class DnsPtrTest {
    @Test fun readsCompressedPtrAndRejectsTruncationAndLoops() {
        // One question, one PTR answer; answer owner points back to question at offset 12.
        val question=byteArrayOf(1,49,1,48,1,48,2,49,48,7,105,110,45,97,100,100,114,4,97,114,112,97,0,0,12,0,1)
        val header=byteArrayOf(0,1,-127,-128,0,1,0,1,0,0,0,0)
        val name=byteArrayOf(3,110,97,115,5,108,111,99,97,108,0)
        val answer=byteArrayOf(-64,12,0,12,0,1,0,0,0,60,0,name.size.toByte())+name
        val packet=header+question+answer
        assertEquals("nas.local",dnsPtrName(packet))
        assertNull(dnsPtrName(packet.copyOf(packet.size-1)))
        assertNull(dnsPtrName(header+byteArrayOf(-64,12)))
        assertNull(dnsPtrName(byteArrayOf()))
        // The wire name is structurally valid but contains a newline in a label.
        val injected=packet.copyOf().also { it[header.size+question.size+12+2]=10 }
        assertNull(dnsPtrName(injected))
        val invalidLabel=packet.copyOf().also { it[header.size+question.size+12+1]='-'.code.toByte() }
        assertNull(dnsPtrName(invalidLabel))
    }
}
