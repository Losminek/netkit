package cz.netkit.shared.ssh

/** Original bounded VT subset. No OSC commands, clipboard access, links, images or device execution. */
data class TerminalRun(val text: String, val foreground: Int = 7, val background: Int = 0, val bold: Boolean = false)
/** Immutable compact row. Materialize styled runs only when the renderer requests a visible row. */
class TerminalRow internal constructor(
    private val chars: CharArray, private val fg: IntArray, private val bg: IntArray, private val bold: BooleanArray,
) {
    val text: String get() = chars.concatToString()
    // Conservative retained-size accounting: payload is 11 bytes/cell; allowance covers array/object headers and references.
    val retainedBytes: Int get() = chars.size * 16 + 256
    val runs: List<TerminalRun> get() {
        val result = mutableListOf<TerminalRun>(); var from = 0
        while (from < chars.size) {
            var end = from + 1
            while (end < chars.size && fg[end] == fg[from] && bg[end] == bg[from] && bold[end] == bold[from]) end++
            result += TerminalRun(chars.concatToString(from, end), fg[from], bg[from], bold[from]); from = end
        }
        return result
    }
    override fun equals(other: Any?): Boolean = this === other || other is TerminalRow &&
        chars.contentEquals(other.chars) && fg.contentEquals(other.fg) && bg.contentEquals(other.bg) && bold.contentEquals(other.bold)
    override fun hashCode(): Int = 31 * chars.contentHashCode() + fg.contentHashCode() + bg.contentHashCode() + bold.contentHashCode()
}
data class TerminalSnapshot(val history: List<TerminalRow>, val screen: List<TerminalRow>, val cursorRow: Int,
    val cursorColumn: Int, val cursorVisible: Boolean, val applicationCursorKeys: Boolean = false)

class TerminalBuffer(columns: Int = 80, rows: Int = 24, private val historyLimit: Int = 5000,
    historyByteLimit: Int = MAX_HISTORY_BYTES) {
    private val historyByteLimit = historyByteLimit.coerceIn(0, MAX_HISTORY_BYTES)
    var historyBytes: Int = 0; private set
    companion object { const val MAX_HISTORY_BYTES = 8 * 1024 * 1024 }
    var columns = columns.coerceIn(10, 240); private set
    var rows = rows.coerceIn(2, 100); private set
    private class Line(size: Int) {
        val chars = CharArray(size) { ' ' }; val fg = IntArray(size) { 7 }; val bg = IntArray(size); val bold = BooleanArray(size)
        fun clear(from: Int, until: Int, foreground: Int, background: Int) { for (i in from until until) { chars[i] = ' '; fg[i] = foreground; bg[i] = background; bold[i] = false } }
        fun snapshot(): TerminalRow = TerminalRow(chars.copyOf(), fg.copyOf(), bg.copyOf(), bold.copyOf())
    }
    private var screen = MutableList(this.rows) { Line(this.columns) }
    private val history = ArrayDeque<TerminalRow>()
    private var primary: MutableList<Line>? = null
    private var primaryCursor = 0 to 0
    private var x = 0; private var y = 0; private var saved = 0 to 0
    private var foreground = 7; private var background = 0; private var bold = false; private var inverse = false
    private var state = 0; private val sequence = StringBuilder(); private var top = 0; private var bottom = this.rows - 1
    private var wrap = true; private var wrapPending = false; private var cursorVisible = true; private var applicationCursorKeys = false

    fun resize(columns: Int, rows: Int) {
        val w = columns.coerceIn(10, 240); val h = rows.coerceIn(2, 100)
        if (w == this.columns && h == this.rows) return
        resizeScreen(w, h, primary == null)
        this.columns = w; this.rows = h
    }
    private fun resizeScreen(w: Int, h: Int, useHistory: Boolean) {
        val oldHeight = screen.size
        // Shift only enough rows to keep the cursor visible. A prompt at the bottom stays there.
        val removed = (y - h + 1).coerceAtLeast(0).coerceAtMost(oldHeight)
        repeat(removed) {
            val line = screen.removeAt(0)
            if (useHistory) rememberLine(line)
        }
        y -= removed
        var restored = 0
        if (h > oldHeight && useHistory && y == oldHeight - 1) {
            restored = minOf(h - oldHeight, history.size)
            repeat(restored) { val row = history.removeLast(); historyBytes -= row.retainedBytes; screen.add(0, fromSnapshot(row)) }
            y += restored
        }
        screen = MutableList(h) { row -> Line(w).also { line ->
            if (row < screen.size) for (col in 0 until minOf(w, screen[row].chars.size)) {
                line.chars[col] = screen[row].chars[col]; line.fg[col] = screen[row].fg[col]
                line.bg[col] = screen[row].bg[col]; line.bold[col] = screen[row].bold[col]
            }
        } }
        x = x.coerceIn(0, w - 1); y = y.coerceIn(0, h - 1)
        saved = saved.first.coerceIn(0, w - 1) to (saved.second - removed + restored).coerceIn(0, h - 1)
        top = 0; bottom = h - 1; wrapPending = false
    }
    private fun fromSnapshot(row: TerminalRow): Line = Line(row.text.length).also { line ->
        var index = 0
        for (run in row.runs) for (char in run.text) {
            line.chars[index] = char; line.fg[index] = run.foreground
            line.bg[index] = run.background; line.bold[index] = run.bold; index++
        }
    }
    private fun rememberLine(line: Line) {
        if (historyLimit <= 0) return
        val row = line.snapshot()
        history.addLast(row); historyBytes += row.retainedBytes
        while (history.size > historyLimit || historyBytes > historyByteLimit) historyBytes -= history.removeFirst().retainedBytes
    }
    fun snapshot() = TerminalSnapshot(if (primary == null) history.toList() else emptyList(), screen.map { it.snapshot() }, y, x, cursorVisible, applicationCursorKeys)
    fun feed(text: String): String {
        val replies = StringBuilder()
        for (char in text) {
            when (state) {
                0 -> when (char) {
                    '\u001b' -> state = 1
                    '\r' -> { x = 0; wrapPending = false }
                    '\n', '\u000b', '\u000c' -> { linefeed(); wrapPending = false }
                    '\b' -> { x = (x - 1).coerceAtLeast(0); wrapPending = false }
                    '\t' -> { x = ((x / 8 + 1) * 8).coerceAtMost(columns - 1); wrapPending = false }
                    else -> if (char.code >= 32 && char.code !in 127..159) put(char)
                }
                1 -> { state = 0; when (char) {
                    '[' -> { state = 2; sequence.clear() }
                    ']', 'P', '^', '_' -> state = 3
                    '7' -> saved = x to y
                    '8' -> { x = saved.first.coerceIn(0, columns - 1); y = saved.second.coerceIn(0, rows - 1) }
                    'D' -> linefeed()
                    'M' -> if (y == top) scrollDown(1) else y = (y - 1).coerceAtLeast(0)
                    'E' -> { x = 0; linefeed() }
                    'c' -> { screen = MutableList(rows) { Line(columns) }; x = 0; y = 0; foreground = 7; background = 0; bold = false; inverse = false; top = 0; bottom = rows - 1; applicationCursorKeys = false }
                    '(', ')' -> state = 5 // Character set designation: consume, use Unicode text.
                } }
                2 -> if (char in '@'..'~') { csi(char, sequence.toString(), replies); state = 0; sequence.clear() }
                    else if (sequence.length < 128) sequence.append(char) else { state = 0; sequence.clear() }
                3 -> if (char == '\u0007') state = 0 else if (char == '\u001b') state = 4
                4 -> { state = if (char == '\\') 0 else 3 }
                5 -> state = 0
            }
        }
        return replies.toString()
    }
    private fun put(char: Char) {
        if (wrapPending && wrap) { x = 0; linefeed() }
        val line = screen[y]; line.chars[x] = char; line.fg[x] = if (inverse) background else foreground
        line.bg[x] = if (inverse) foreground else background; line.bold[x] = bold
        wrapPending = x == columns - 1
        if (!wrapPending) x++
    }
    private fun linefeed() { if (y == bottom) scrollUp(1) else y = (y + 1).coerceAtMost(rows - 1) }
    private fun scrollUp(count: Int) { repeat(count.coerceIn(0, rows)) {
        val removed = screen.removeAt(top)
        if (top == 0 && bottom == rows - 1 && primary == null && historyLimit > 0) {
            rememberLine(removed)
        }
        screen.add(bottom, Line(columns))
    } }
    private fun scrollDown(count: Int) { repeat(count.coerceIn(0, rows)) { screen.removeAt(bottom); screen.add(top, Line(columns)) } }
    private fun clear(row: Int, from: Int = 0, until: Int = columns) = screen[row].clear(from, until, foreground, background)
    private fun csi(command: Char, raw: String, replies: StringBuilder) {
        // Only the standard numeric grammar and the supported DEC private grammar are accepted.
        // Prefixes >, <, = and intermediate bytes must not accidentally become SGR parameters.
        if (raw.removePrefix("?").any { it !in '0'..'9' && it != ';' }) return
        val private = raw.startsWith('?')
        if (private && command != 'h' && command != 'l') return
        val args = raw.removePrefix("?").split(';').map { it.toIntOrNull() ?: 0 }
        fun arg(index: Int = 0, default: Int = 1) = (args.getOrNull(index) ?: default).let { if (it == 0) default else it }.coerceIn(0, 10000)
        if (command != 'm') wrapPending = false
        when (command) {
            'A' -> y = (y - arg()).coerceAtLeast(0)
            'B', 'e' -> y = (y + arg()).coerceAtMost(rows - 1)
            'C', 'a' -> x = (x + arg()).coerceAtMost(columns - 1)
            'D' -> x = (x - arg()).coerceAtLeast(0)
            'E' -> { y = (y + arg()).coerceAtMost(rows - 1); x = 0 }
            'F' -> { y = (y - arg()).coerceAtLeast(0); x = 0 }
            'G', '`' -> x = (arg() - 1).coerceIn(0, columns - 1)
            'd' -> y = (arg() - 1).coerceIn(0, rows - 1)
            'H', 'f' -> { y = (arg() - 1).coerceIn(0, rows - 1); x = (arg(1) - 1).coerceIn(0, columns - 1) }
            'J' -> when (args[0]) {
                0 -> { clear(y, x); for (row in y + 1 until rows) clear(row) }
                1 -> { for (row in 0 until y) clear(row); clear(y, 0, x + 1) }
                2 -> for (row in 0 until rows) clear(row)
                3 -> { history.clear(); historyBytes = 0 }
            }
            'K' -> when (args[0]) { 0 -> clear(y, x); 1 -> clear(y, 0, x + 1); 2 -> clear(y) }
            'X' -> clear(y, x, (x + arg()).coerceAtMost(columns))
            'P', '@' -> {
                val line = screen[y]; val count = arg().coerceAtMost(columns - x)
                val range = if (command == 'P') x until columns - count else (columns - 1 downTo x + count)
                for (i in range) { val source = if (command == 'P') i + count else i - count
                    line.chars[i] = line.chars[source]; line.fg[i] = line.fg[source]; line.bg[i] = line.bg[source]; line.bold[i] = line.bold[source] }
                if (command == 'P') clear(y, columns - count) else clear(y, x, x + count)
            }
            'L', 'M' -> if (y in top..bottom) repeat(arg().coerceAtMost(bottom - y + 1)) {
                if (command == 'L') { screen.removeAt(bottom); screen.add(y, Line(columns)) }
                else { screen.removeAt(y); screen.add(bottom, Line(columns)) }
            }
            'S' -> scrollUp(arg())
            'T' -> scrollDown(arg())
            'r' -> { val a = arg() - 1; val b = arg(1, rows) - 1; if (a in 0 until b && b < rows) { top = a; bottom = b; x = 0; y = 0 } }
            's' -> saved = x to y
            'u' -> { x = saved.first.coerceIn(0, columns - 1); y = saved.second.coerceIn(0, rows - 1) }
            'm' -> sgr(args)
            'n' -> if (!private) when (args[0]) { 5 -> replies.append("\u001b[0n"); 6 -> replies.append("\u001b[${y + 1};${x + 1}R") }
            'c' -> if (!private) replies.append("\u001b[?1;2c")
            'h', 'l' -> if (private) args.forEach { mode -> val on = command == 'h'; when (mode) {
                1 -> applicationCursorKeys = on
                7 -> wrap = on
                25 -> cursorVisible = on
                47, 1047, 1049 -> if (on && primary == null) { primary = screen; primaryCursor = x to y; screen = MutableList(rows) { Line(columns) }; x = 0; y = 0; top = 0; bottom = rows - 1 }
                    else if (!on && primary != null) { screen = primary!!; primary = null; x = primaryCursor.first; y = primaryCursor.second; resizeScreen(columns, rows, true) }
            } }
        }
    }
    private fun sgr(args: List<Int>) {
        var i = 0
        while (i < args.size) { val n = args[i]; when (n) {
            0 -> { foreground = 7; background = 0; bold = false; inverse = false }
            1 -> bold = true
            22 -> bold = false
            7 -> inverse = true
            27 -> inverse = false
            in 30..37 -> foreground = n - 30
            in 40..47 -> background = n - 40
            in 90..97 -> foreground = n - 90 + 8
            in 100..107 -> background = n - 100 + 8
            39 -> foreground = 7
            49 -> background = 0
            38, 48 -> if (args.getOrNull(i + 1) == 5 && args.getOrNull(i + 2) in 0..255) {
                if (n == 38) foreground = args[i + 2] else background = args[i + 2]; i += 2
            } else if (args.getOrNull(i + 1) == 2 && i + 4 < args.size) {
                val rgb = 0x1000000 or (args[i + 2].coerceIn(0, 255) shl 16) or (args[i + 3].coerceIn(0, 255) shl 8) or args[i + 4].coerceIn(0, 255)
                if (n == 38) foreground = rgb else background = rgb; i += 4
            }
        }; i++ }
    }
}
