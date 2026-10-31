package cz.netkit.shared.ssh

import kotlin.test.*

class TerminalCompositionTest {
    @Test fun utf16AndCodePointDeletionNeverSplitSupplementaryCharacters() {
        for (codePoints in listOf(false, true)) {
            val events = mutableListOf<TerminalEdit>()
            val model = TerminalComposition(events::add)
            model.set("a\uD83D\uDE00")
            assertTrue(model.delete(1, 0, codePoints))
            assertEquals("a", model.text); assertTrue(events.isEmpty())
            model.finish(); assertEquals(listOf<TerminalEdit>(TerminalEdit.Text("a")), events)
        }
    }
    @Test fun excessDeletionReachesServerInRequestedUnits() {
        val events = mutableListOf<TerminalEdit>(); val model = TerminalComposition(events::add)
        model.set("\uD83D\uDE00"); model.delete(3, 1, false)
        assertEquals(listOf<TerminalEdit>(TerminalEdit.Key(TerminalKey.BACKSPACE), TerminalEdit.Key(TerminalKey.DELETE)), events)
        events.clear(); model.set("\uD83D\uDE00"); model.delete(3, 0, true)
        assertEquals(List<TerminalEdit>(2) { TerminalEdit.Key(TerminalKey.BACKSPACE) }, events)
    }
    @Test fun invalidCodePointRangeDoesNotDeleteAnything() {
        val events = mutableListOf<TerminalEdit>(); val model = TerminalComposition(events::add)
        model.set("\uD800x"); assertFalse(model.delete(2, 1, true))
        assertEquals("\uD800x", model.text); assertTrue(events.isEmpty())
        assertFalse(model.delete(-1, 0, false)); assertTrue(events.isEmpty())
    }
    @Test fun emptyCommitCancelsAndFinishOrToolbarActionEmitsOnlyOnceInOrder() {
        val events = mutableListOf<TerminalEdit>(); val model = TerminalComposition(events::add)
        model.set("cancelled"); model.commit(""); model.finish(); assertTrue(events.isEmpty())
        model.set("command")
        model.beforeAction { events += TerminalEdit.Key(TerminalKey.ENTER) }
        model.finish()
        assertEquals(listOf<TerminalEdit>(TerminalEdit.Text("command"), TerminalEdit.Key(TerminalKey.ENTER)), events)
        events.clear(); model.set("before")
        model.beforeAction { events += TerminalEdit.Text(terminalTextSequence("c", control = true)) }
        assertEquals(listOf<TerminalEdit>(TerminalEdit.Text("before"), TerminalEdit.Text("\u0003")), events)
    }
    @Test fun boundsPreservePairAndCapDeletionWork() {
        val events = mutableListOf<TerminalEdit>(); val model = TerminalComposition(events::add)
        model.set("x".repeat(16383) + "\uD83D\uDE00")
        assertEquals(16383, model.text.length)
        model.clear(); model.delete(Int.MAX_VALUE, Int.MAX_VALUE, true)
        assertEquals(512, events.size)
    }
    @Test fun newlineCommitFlushesButCompositionFlushNeverInventsAnEnter() {
        val events = mutableListOf<TerminalEdit>(); val model = TerminalComposition(events::add)
        model.set("command"); model.commit("\n")
        assertEquals(listOf<TerminalEdit>(TerminalEdit.Text("command"), TerminalEdit.Key(TerminalKey.ENTER)), events)
        events.clear(); model.set("\n"); model.finish()
        assertEquals(listOf<TerminalEdit>(TerminalEdit.Text("\n")), events) // Adapter requires paste confirmation for control text.
    }
    @Test fun rightAltUsesLayoutCharacterWithoutTerminalPrefixForCzechSymbols() {
        for (symbol in listOf("@", "\\", "|", "{", "}")) {
            var preserved = false
            val bytes = terminalHardwareText(control = true, leftAlt = false, rightAlt = true) {
                preserved = it; if (it) symbol else "wrong"
            }
            assertTrue(preserved); assertEquals(symbol, bytes.encoded)
        }
        assertEquals("\u001bx", terminalHardwareText(false, true, false) { assertFalse(it); "x" }.encoded)
        assertEquals("\u0003", terminalHardwareText(true, false, false) { "c" }.encoded)
    }
}
