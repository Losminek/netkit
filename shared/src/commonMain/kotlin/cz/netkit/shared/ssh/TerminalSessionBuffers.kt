package cz.netkit.shared.ssh

/** One tab's displayed grid and pending reconnect grid. The platform serializes access to this holder. */
class TerminalSessionBuffers {
    var visible = TerminalBuffer()
        private set
    private var pending: TerminalBuffer? = null

    fun beginConnection(): TerminalBuffer = TerminalBuffer(visible.columns, visible.rows).also { pending = it }
    fun connected(buffer: TerminalBuffer) {
        if (pending === buffer) { visible = buffer; pending = null }
    }
    fun ended(buffer: TerminalBuffer) { if (pending === buffer) pending = null }
    fun resize(columns: Int, rows: Int) {
        visible.resize(columns, rows)
        pending?.resize(columns, rows)
    }
}
