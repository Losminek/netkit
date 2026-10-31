package cz.netkit.shared.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One bounded FIFO for all application PTY writes; resize retains only the latest pending size. */
class TerminalWritePump(private val scope: CoroutineScope) {
    private sealed interface Write {
        data class Text(val value: String) : Write
        data class Resize(val columns: Int, val rows: Int) : Write
    }
    private val queue = Channel<Write>(32)
    private var pendingResize: Job? = null
    fun send(text: String): Boolean = text.length <= 16384 && queue.trySend(Write.Text(text)).isSuccess
    fun resize(columns: Int, rows: Int) {
        pendingResize?.cancel()
        pendingResize = scope.launch {
            delay(100)
            try { queue.send(Write.Resize(columns.coerceIn(10, 240), rows.coerceIn(2, 100))) }
            catch (e: CancellationException) { throw e }
            catch (_: ClosedSendChannelException) { /* Closed session has no further writes. */ }
        }
    }
    suspend fun consume(text: suspend (String) -> Unit, resize: suspend (Int, Int) -> Unit) {
        for (command in queue) when (command) {
            is Write.Text -> text(command.value)
            is Write.Resize -> resize(command.columns, command.rows)
        }
    }
    fun close() {
        pendingResize?.cancel(); queue.close()
        // Drop queued text references as soon as a session closes (they may contain terminal passwords).
        while (queue.tryReceive().isSuccess) { }
    }
}
