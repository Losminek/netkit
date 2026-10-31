package cz.netkit.shared.ssh

import kotlin.test.*

class TerminalInputTest {
    @Test fun arrowsFollowDecckmAndControlModifiers() {
        val buffer = TerminalBuffer()
        assertEquals("\u001b[A", terminalKeySequence(TerminalKey.UP, buffer.snapshot().applicationCursorKeys))
        buffer.feed("\u001b[?1h")
        assertEquals("\u001bOA", terminalKeySequence(TerminalKey.UP, buffer.snapshot().applicationCursorKeys))
        assertEquals("\u001b[1;5D", terminalKeySequence(TerminalKey.LEFT, true, control = true))
        buffer.feed("\u001b[?1l")
        assertFalse(buffer.snapshot().applicationCursorKeys)
    }
    @Test fun extendedKeysAndTextModifiersEncodeExactBytes() {
        assertEquals("\u001b[2~", terminalKeySequence(TerminalKey.INSERT))
        assertEquals("\u001b[3~", terminalKeySequence(TerminalKey.DELETE))
        assertEquals("\u001b[Z", terminalKeySequence(TerminalKey.TAB, shift = true))
        assertEquals("\u001bOP", terminalKeySequence(TerminalKey.F1))
        assertEquals("\u001bOS", terminalKeySequence(TerminalKey.F4))
        assertEquals("\u001b[15~", terminalKeySequence(TerminalKey.F5))
        assertEquals("\u001b[24~", terminalKeySequence(TerminalKey.F12))
        assertEquals("\r", terminalKeySequence(TerminalKey.ENTER))
        assertEquals("\u007f", terminalKeySequence(TerminalKey.BACKSPACE))
        assertEquals("\u0003", terminalTextSequence("c", control = true))
        assertEquals("\u001bx", terminalTextSequence("x", alt = true))
        assertEquals("\u0000", terminalTextSequence(" ", control = true))
    }
    @Test fun compactHistoryBudgetIncludesWorstCaseAlternatingStyles() {
        val buffer = TerminalBuffer(240, 2)
        val line = (0 until 240).joinToString("") { "\u001b[${30 + it % 8}mX" }
        repeat(6000) { buffer.feed(line + "\r\n") }
        val snapshot = buffer.snapshot()
        assertTrue(snapshot.history.isNotEmpty())
        assertTrue(snapshot.history.size < 5000)
        assertTrue(buffer.historyBytes <= TerminalBuffer.MAX_HISTORY_BYTES)
        assertEquals(snapshot.history.sumOf { it.retainedBytes }, buffer.historyBytes)
        assertEquals(240, snapshot.history.first().runs.size)
        println("TERMINAL_MEMORY rows=${snapshot.history.size} columns=240 retainedBudget=${buffer.historyBytes} max=${TerminalBuffer.MAX_HISTORY_BYTES}")
        buffer.feed("\u001b[3J")
        assertEquals(0, buffer.historyBytes)
    }
    @Test fun savedSnapshotRowsRemainImmutableWhileBufferContinues() {
        val buffer = TerminalBuffer(20, 3, historyByteLimit = 2048)
        buffer.feed("old\r\nline\r\nthird\r\nlast")
        val saved = buffer.snapshot()
        val old = saved.history.first().text
        repeat(100) { buffer.feed("\r\nnext") }
        assertEquals(old, saved.history.first().text)
        assertTrue(buffer.historyBytes <= 2048)
    }
}
