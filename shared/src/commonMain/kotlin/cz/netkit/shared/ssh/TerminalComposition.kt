package cz.netkit.shared.ssh

sealed interface TerminalEdit {
    data class Text(val value: String) : TerminalEdit
    data class Key(val value: TerminalKey) : TerminalEdit
}

/** Private, transient composing state. No surrounding editor history or saved state. */
class TerminalComposition(private val emit: (TerminalEdit) -> Unit) {
    var text: String = ""
        private set

    fun set(value: String) { text = bounded(value) }
    fun commit(value: String) {
        if (value == "\n" || value == "\r") {
            beforeAction { emit(TerminalEdit.Key(TerminalKey.ENTER)) }
            return
        }
        text = ""
        bounded(value).takeIf { it.isNotEmpty() }?.let { emit(TerminalEdit.Text(it)) }
    }
    fun finish() { val value = text; text = ""; if (value.isNotEmpty()) emit(TerminalEdit.Text(value)) }
    fun clear() { text = "" }
    fun beforeAction(action: () -> Unit) { finish(); action() }

    /** UTF-16 lengths extend to the start of a pair rather than leaving an orphan surrogate. */
    fun delete(before: Int, after: Int, codePoints: Boolean): Boolean {
        if (before < 0 || after < 0) return false
        var index = text.length
        var remaining = before.coerceAtMost(MAX_DELETE)
        while (index > 0 && remaining > 0) {
            val last = text[index - 1]
            val pair = last.isLowSurrogate() && index >= 2 && text[index - 2].isHighSurrogate()
            if (codePoints && (last.isHighSurrogate() || last.isLowSurrogate() && !pair)) return false
            val width = if (pair) 2 else 1
            index -= width
            remaining = (remaining - if (codePoints) 1 else width).coerceAtLeast(0)
        }
        text = text.substring(0, index)
        repeat(remaining) { emit(TerminalEdit.Key(TerminalKey.BACKSPACE)) }
        repeat(after.coerceAtMost(MAX_DELETE)) { emit(TerminalEdit.Key(TerminalKey.DELETE)) }
        return true
    }

    private fun bounded(value: String): String {
        var end = minOf(value.length, MAX_TEXT)
        if (end < value.length && end > 0 && value[end - 1].isHighSurrogate() && value[end].isLowSurrogate()) end--
        return value.substring(0, end)
    }
    companion object { const val MAX_TEXT = 16384; const val MAX_DELETE = 256 }
}

data class TerminalHardwareText(val text: String, val control: Boolean, val alt: Boolean) {
    val encoded: String get() = terminalTextSequence(text, control, alt)
}

/** The right Alt is a layout modifier (AltGr), never a terminal Meta/Control prefix. */
fun terminalHardwareText(control: Boolean, leftAlt: Boolean, rightAlt: Boolean,
    unicode: (preserveLayoutModifiers: Boolean) -> String): TerminalHardwareText =
    TerminalHardwareText(unicode(rightAlt), control && !rightAlt, leftAlt && !rightAlt)
