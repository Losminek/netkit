@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package cz.netkit.shared.ssh

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlin.test.*

class TerminalWritePumpTest {
    @Test fun animatedResizeIsCoalescedAndSharesTextWriterOrder() = runTest {
        val writes = mutableListOf<String>()
        val pump = TerminalWritePump(this)
        val consumer = launch { pump.consume({ writes += it }, { w, h -> writes += "$w/$h" }) }
        assertTrue(pump.send("first"))
        pump.resize(80, 24)
        runCurrent(); advanceTimeBy(60)
        pump.resize(90, 12)
        assertTrue(pump.send("second"))
        runCurrent(); advanceTimeBy(99); runCurrent()
        assertEquals(listOf("first", "second"), writes)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("first", "second", "90/12"), writes)
        assertTrue(pump.send("third")); runCurrent()
        assertEquals(listOf("first", "second", "90/12", "third"), writes)
        pump.close(); consumer.join()
        assertFalse(pump.send("after close"))
    }
    @Test fun queueIsBoundedAndCloseCancelsPendingResize() = runTest {
        val pump = TerminalWritePump(this)
        repeat(32) { assertTrue(pump.send("x")) }
        assertFalse(pump.send("overflow"))
        assertFalse(pump.send("x".repeat(16385)))
        pump.resize(80, 10); pump.close(); advanceUntilIdle()
        var rows = 0
        pump.consume({}, { _, _ -> rows++ })
        assertEquals(0, rows)
    }
}
