package cz.netkit.shared.ssh

import kotlin.test.*

class TerminalBufferTest {
    @Test fun clearScreenThenImeRoundTripDoesNotResurrectHistory() {
        val terminal = TerminalBuffer(20, 30)
        terminal.feed((1..60).joinToString("\r\n") { "old$it" })
        val history = terminal.snapshot().history
        terminal.feed("\u001b[2J\u001b[Hprompt>")
        terminal.resize(20, 10); terminal.resize(20, 30)
        assertEquals(history, terminal.snapshot().history)
        assertEquals(0, terminal.snapshot().cursorRow)
        assertTrue(terminal.snapshot().screen.first().text.startsWith("prompt>"))
        assertTrue(terminal.snapshot().screen.drop(1).all { it.text.isBlank() })
    }
    @Test fun growMovesSavedCursorWithRestoredRows() {
        val terminal = TerminalBuffer(20, 10)
        terminal.feed((1..30).joinToString("\r\n") { "line$it" })
        terminal.feed("\u001b[4;5H\u001b7\u001b[10;1H")
        terminal.resize(20, 30)
        terminal.feed("\u001b8")
        assertEquals(23, terminal.snapshot().cursorRow)
        assertEquals(4, terminal.snapshot().cursorColumn)
    }
    @Test fun handlesSplitAnsiCursorAndEraseWithoutDisplayingControls() {
        val terminal = TerminalBuffer(10, 3)
        terminal.feed("hello\u001b["); terminal.feed("2;3Hworld")
        assertEquals("hello     ", terminal.snapshot().screen[0].text)
        assertEquals("  world   ", terminal.snapshot().screen[1].text)
        terminal.feed("\u001b[2;4H\u001b[K")
        assertEquals("  w       ", terminal.snapshot().screen[1].text)
        terminal.feed("\u001b[2J")
        assertTrue(terminal.snapshot().screen.all { it.text.isBlank() })
    }
    @Test fun scrollbackIsBoundedAndAlternateScreenPreservesPrimary() {
        val terminal = TerminalBuffer(10, 2, 3)
        terminal.feed("first\r\nsecond")
        val primary = terminal.snapshot().screen
        terminal.feed("\u001b[?1049hTOP\r\nother\r\nlast\u001b[?1049l")
        assertEquals(primary, terminal.snapshot().screen)
        assertTrue(terminal.snapshot().history.isEmpty())
        repeat(20) { terminal.feed("\r\nline$it") }
        assertEquals(3, terminal.snapshot().history.size)
        assertTrue(terminal.snapshot().screen.last().text.startsWith("line19"))
    }
    @Test fun supports256ColorsAndSafeDiscardOfOscClipboard() {
        val terminal = TerminalBuffer(10, 2)
        terminal.feed("\u001b[38;5;196mred\u001b[0m\u001b]52;c;secret\u0007ok")
        assertEquals("redok     ", terminal.snapshot().screen[0].text)
        assertEquals(196, terminal.snapshot().screen[0].runs.first().foreground)
        assertEquals(7, terminal.snapshot().screen[0].runs.last().foreground)
        terminal.feed("\u001b[6n").let { assertEquals("\u001b[1;6R", it) }
    }
    @Test fun wrapHappensOnlyWhenNextPrintableCharacterArrives() {
        val terminal = TerminalBuffer(10, 2)
        terminal.feed("1234567890\r\nnext")
        assertEquals("1234567890", terminal.snapshot().screen[0].text)
        assertEquals("next      ", terminal.snapshot().screen[1].text)
        assertTrue(terminal.snapshot().history.isEmpty())
    }
    @Test fun resizeBoundsMemoryAndPreservesText() {
        val terminal = TerminalBuffer(10, 3)
        terminal.feed("abc\u001b[99999999;99999999H!")
        terminal.resize(100000, 100000)
        assertEquals(240, terminal.columns); assertEquals(100, terminal.rows)
        assertTrue(terminal.snapshot().screen[0].text.startsWith("abc"))
        terminal.resize(0, 0)
        assertEquals(10, terminal.columns); assertEquals(2, terminal.rows)
    }
    @Test fun insertDeleteAndScrollRegionsDoNotAffectOutsideRows() {
        val terminal = TerminalBuffer(10, 4)
        terminal.feed("header\r\nabcdef\r\nrow3\r\nfooter\u001b[2;3r\u001b[2;3H\u001b[2P")
        assertEquals("abef      ", terminal.snapshot().screen[1].text)
        terminal.feed("\u001b[3;1H\n")
        assertTrue(terminal.snapshot().screen.first().text.startsWith("header"))
        assertTrue(terminal.snapshot().screen.last().text.startsWith("footer"))
    }
    @Test fun shrinkKeepsBottomPromptAndGrowRestoresHistory() {
        val terminal = TerminalBuffer(20, 30)
        terminal.feed((1..29).joinToString("\r\n") { "line$it" } + "\r\nprompt>")
        val before = terminal.snapshot()
        terminal.resize(20, 10)
        assertEquals(20, terminal.snapshot().history.size)
        assertEquals(9, terminal.snapshot().cursorRow)
        assertTrue(terminal.snapshot().screen.last().text.startsWith("prompt>"))
        assertEquals(before.screen.take(20), terminal.snapshot().history)
        terminal.resize(20, 30)
        assertEquals(before, terminal.snapshot())
    }
    @Test fun alternateResizeDoesNotConsumeOrAddPrimaryHistory() {
        val terminal = TerminalBuffer(20, 30)
        terminal.feed((1..29).joinToString("\r\n") { "line$it" } + "\r\nprompt>")
        val primary = terminal.snapshot()
        terminal.feed("\u001b[?1049h\u001b[30;1Halt>")
        terminal.resize(20, 10)
        assertTrue(terminal.snapshot().history.isEmpty())
        assertEquals(9, terminal.snapshot().cursorRow)
        assertTrue(terminal.snapshot().screen.last().text.startsWith("alt>"))
        terminal.resize(20, 30)
        terminal.feed("\u001b[?1049l")
        assertEquals(primary, terminal.snapshot())
    }
    @Test fun resizeClampsCursorAndResetsScrollRegion() {
        val terminal = TerminalBuffer(20, 30)
        terminal.feed("\u001b[10;20r\u001b[999;999H!")
        terminal.resize(10, 10)
        assertEquals(9, terminal.snapshot().cursorRow)
        assertEquals(9, terminal.snapshot().cursorColumn)
        terminal.feed("\n")
        assertEquals(9, terminal.snapshot().cursorRow)
        assertEquals(21, terminal.snapshot().history.size)
    }
    @Test fun unsupportedCsiPrefixesAndIntermediateBytesDoNotChangeAttributes() {
        val terminal = TerminalBuffer(20, 3)
        terminal.feed("\u001b[31mA\u001b[>4;2mB\u001b[<0mC\u001b[=0mD\u001b[0 mE")
        assertEquals("ABCDE", terminal.snapshot().screen.first().text.trimEnd())
        assertEquals(1, terminal.snapshot().screen.first().runs.first().foreground)
        assertEquals("ABCDE", terminal.snapshot().screen.first().runs.first().text)
    }
    @Test fun alternateSnapshotHidesExistingHistoryAndRestoresItAfterResize() {
        val terminal = TerminalBuffer(20, 10)
        terminal.feed((1..20).joinToString("\r\n") { "line$it" })
        val before = terminal.snapshot()
        assertEquals(10, before.history.size)
        terminal.feed("\u001b[?1049hALT")
        assertTrue(terminal.snapshot().history.isEmpty())
        terminal.resize(20, 4)
        assertTrue(terminal.snapshot().history.isEmpty())
        terminal.resize(20, 10)
        assertTrue(terminal.snapshot().history.isEmpty())
        terminal.feed("\u001b[?1049l")
        assertEquals(before, terminal.snapshot())
    }
}
