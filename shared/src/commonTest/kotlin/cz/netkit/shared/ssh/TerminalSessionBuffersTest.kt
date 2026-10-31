package cz.netkit.shared.ssh

import kotlin.test.*

class TerminalSessionBuffersTest {
    @Test fun reconnectKeepsCurrentGridAndOutputUntilConnectionSucceeds() {
        val tab = TerminalSessionBuffers()
        tab.resize(40, 15); tab.visible.feed("old output")
        val pending = tab.beginConnection()
        assertEquals(40, pending.columns); assertEquals(15, pending.rows)
        assertTrue(tab.visible.snapshot().screen.first().text.startsWith("old output"))
        tab.connected(pending)
        assertSame(pending, tab.visible)
        assertEquals(40, tab.visible.columns); assertEquals(15, tab.visible.rows)
    }
    @Test fun geometryChangesDuringHandshakeReachPendingGridAndFailureKeepsOutput() {
        val tab = TerminalSessionBuffers(); tab.resize(40, 15); tab.visible.feed("old output")
        val first = tab.beginConnection(); tab.resize(60, 10)
        assertEquals(60, first.columns); assertEquals(10, first.rows)
        tab.ended(first)
        assertTrue(tab.visible.snapshot().screen.first().text.startsWith("old output"))
        val second = tab.beginConnection(); tab.resize(50, 12)
        tab.connected(first) // A superseded connection cannot replace the visible grid.
        assertNotSame(first, tab.visible)
        tab.connected(second)
        assertEquals(50, tab.visible.columns); assertEquals(12, tab.visible.rows)
    }
}
