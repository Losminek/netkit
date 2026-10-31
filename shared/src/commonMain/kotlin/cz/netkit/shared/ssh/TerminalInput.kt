package cz.netkit.shared.ssh

enum class TerminalKey {
    ENTER, BACKSPACE, ESCAPE, TAB, UP, DOWN, LEFT, RIGHT, HOME, END, PAGE_UP, PAGE_DOWN,
    INSERT, DELETE, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
}

/** Xterm key encoding for the supported subset. No platform keycodes in shared code. */
fun terminalKeySequence(key: TerminalKey, applicationCursor: Boolean = false,
    control: Boolean = false, alt: Boolean = false, shift: Boolean = false): String {
    val modifier = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (control) 4 else 0)
    fun cursor(final: Char) = if (modifier != 1) "\u001b[1;$modifier$final" else "\u001b${if (applicationCursor) "O" else "["}$final"
    fun numbered(number: Int) = if (modifier == 1) "\u001b[$number~" else "\u001b[$number;$modifier~"
    val text = when (key) {
        TerminalKey.ENTER -> "\r"
        TerminalKey.BACKSPACE -> "\u007f"
        TerminalKey.ESCAPE -> "\u001b"
        TerminalKey.TAB -> if (shift) "\u001b[Z" else "\t"
        TerminalKey.UP -> return cursor('A')
        TerminalKey.DOWN -> return cursor('B')
        TerminalKey.LEFT -> return cursor('D')
        TerminalKey.RIGHT -> return cursor('C')
        TerminalKey.HOME -> return cursor('H')
        TerminalKey.END -> return cursor('F')
        TerminalKey.PAGE_UP -> return numbered(5)
        TerminalKey.PAGE_DOWN -> return numbered(6)
        TerminalKey.INSERT -> return numbered(2)
        TerminalKey.DELETE -> return numbered(3)
        TerminalKey.F1, TerminalKey.F2, TerminalKey.F3, TerminalKey.F4 -> {
            val final = ('P'.code + key.ordinal - TerminalKey.F1.ordinal).toChar()
            return if (modifier == 1) "\u001bO$final" else "\u001b[1;$modifier$final"
        }
        else -> return numbered(listOf(15, 17, 18, 19, 20, 21, 23, 24)[key.ordinal - TerminalKey.F5.ordinal])
    }
    return (if (alt) "\u001b" else "") + text
}

fun terminalTextSequence(text: String, control: Boolean = false, alt: Boolean = false): String {
    val transformed = if (control && text.length == 1) {
        val char = text.single().uppercaseChar()
        when {
            char in '@'..'_' -> (char.code - 64).toChar().toString()
            char == ' ' || char == '2' -> "\u0000"
            char == '?' || char == '8' -> "\u007f"
            else -> text
        }
    } else text
    return (if (alt) "\u001b" else "") + transformed
}
