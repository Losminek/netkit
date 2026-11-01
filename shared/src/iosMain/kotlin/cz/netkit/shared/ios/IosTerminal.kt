package cz.netkit.shared.ios

import cz.netkit.shared.ssh.*

/** Swift serializes all terminal calls on its main actor; no terminal contents are persisted. */
class IosTerminal(columns: Int, rows: Int) {
    private val buffer = TerminalBuffer(columns, rows)
    private val pending = StringBuilder()
    private var overflow = false
    private val composition = TerminalComposition { edit ->
        val encoded = when (edit) {
            is TerminalEdit.Text -> edit.value
            is TerminalEdit.Key -> terminalKeySequence(edit.value, buffer.snapshot().applicationCursorKeys)
        }
        if (pending.length + encoded.length > TerminalComposition.MAX_TEXT) overflow = true
        else if (!overflow) pending.append(encoded)
    }

    fun feed(text: String): String = buffer.feed(text)
    fun snapshot(): TerminalSnapshot = buffer.snapshot()
    fun resize(columns: Int, rows: Int) { buffer.resize(columns, rows) }
    fun keySequence(key: TerminalKey, control: Boolean, alt: Boolean, shift: Boolean): String =
        terminalKeySequence(key, buffer.snapshot().applicationCursorKeys, control, alt, shift)
    fun textSequence(text: String, control: Boolean, alt: Boolean): String = terminalTextSequence(text, control, alt)
    fun safePaste(text: String): String = safeTerminalPaste(text)
    fun pasteNeedsConfirmation(text: String): Boolean = cz.netkit.shared.ssh.pasteNeedsConfirmation(text)
    fun composeSet(value: String) { composition.set(value) }
    fun composeCommit(value: String) { composition.commit(value) }
    fun composeFinish() { composition.finish() }
    fun composeClear() { composition.clear(); pending.clear(); overflow = false }
    fun composeDelete(before: Int, after: Int, codePoints: Boolean): Boolean = composition.delete(before, after, codePoints)

    /** Drain immediately after each editing action. Overflow never returns truncated terminal commands. */
    fun drainInput(): String? {
        val result = if (overflow) null else pending.toString()
        pending.clear(); overflow = false
        return result
    }
}

/** Match the shared monotonic background grace period; Swift supplies uptime including sleep. */
class IosSshLifecycle(nowMillis: () -> Long) {
    private val policy = SshBackgroundPolicy(nowMillis)
    fun background() { policy.background() }
    fun foreground(): Boolean = policy.foreground()
    fun expired(): Boolean = policy.expired()
    fun remainingMillis(): Long? = policy.remainingMillis()
    fun promptTimeoutMillis(): Long = SshPromptPolicy.PROMPT_TIMEOUT_MILLIS
    fun handshakeTimeoutMillis(): Long = SshPromptPolicy.HANDSHAKE_TIMEOUT_MILLIS
}
