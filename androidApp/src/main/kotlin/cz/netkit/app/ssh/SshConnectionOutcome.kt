package cz.netkit.app.ssh

import cz.netkit.shared.ssh.SshFailure
import cz.netkit.shared.ssh.TerminalWritePump
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

/** First causal error wins, even if closing the transport then causes cancellation or a read error. */
class SshConnectionOutcome(private val notify: (SshFailure) -> Unit) {
    private val firstFailure = AtomicReference<SshFailure?>()
    val failed: Boolean get() = firstFailure.get() != null
    fun record(failure: SshFailure) { if (firstFailure.compareAndSet(null, failure)) notify(failure) }
}

suspend fun consumeSshWrites(pump: TerminalWritePump, text: suspend (String) -> Unit,
    resize: suspend (Int, Int) -> Unit, onWriteFailure: () -> Unit) {
    try { pump.consume(text, resize) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { onWriteFailure() }
}
