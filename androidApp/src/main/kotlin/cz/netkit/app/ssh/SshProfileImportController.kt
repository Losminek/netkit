package cz.netkit.app.ssh

import cz.netkit.app.wol.StreamSizeLimitException
import cz.netkit.shared.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SshProfileImportError {
    data class Document(val reason: SshProfileImportFailure) : SshProfileImportError
    enum class Operation : SshProfileImportError { BLOCKED, IO, TIMEOUT, KEY_UNAVAILABLE }
}

data class PendingSshProfileImport(val incoming: SshProfileImport, val plan: SshImportPlan)
data class SshProfileImportState(
    val pendingProfileImport: PendingSshProfileImport? = null,
    val busy: Boolean = false,
    val failure: SshProfileImportError? = null,
)

internal fun SshUiState.blocksProfileImport(): Boolean =
    !loaded || storeFailed || busy || profileImportBlockedByDialog ||
        hostChallenge != null || authChallenge != null || keySelectionRequired != null || sessions.any { !it.ended }

/** Main-thread API shared by the ViewModel and JVM tests; store mutations use its operations mutex. */
internal class SshProfileImportController(
    private val scope: CoroutineScope,
    private val operations: Mutex,
    private val read: suspend () -> SshData,
    private val update: suspend ((SshData) -> SshData) -> SshData,
    private val blocked: () -> Boolean,
    private val generateId: () -> String,
    private val deleteSecret: suspend (String) -> Unit,
    private val worker: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableState = MutableStateFlow(SshProfileImportState())
    val state = mutableState.asStateFlow()
    val active: Boolean get() = state.value.busy || state.value.pendingProfileImport != null
    private var job: Job? = null
    private var committing = false

    fun preview(readBytes: suspend () -> ByteArray) {
        if (active) return
        if (blocked()) { mutableState.value = SshProfileImportState(failure = SshProfileImportError.Operation.BLOCKED); return }
        mutableState.value = SshProfileImportState(busy = true)
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                operations.withLock {
                    if (blocked()) { fail(SshProfileImportError.Operation.BLOCKED); return@withLock }
                    val incoming = withContext(worker) { decodeSshProfileImport(readBytes()) }
                    val data = read()
                    val plan = withContext(worker) { planSshProfileImport(data, incoming, null, generateId) }
                    if (blocked()) fail(SshProfileImportError.Operation.BLOCKED)
                    else mutableState.value = SshProfileImportState(pendingProfileImport = PendingSshProfileImport(incoming, plan))
                }
            } catch (error: Exception) { handle(error) }
            finally { mutableState.value = mutableState.value.copy(busy = false) }
        }
    }

    fun confirm(keyRef: String?) {
        if (state.value.busy) return
        val pending = state.value.pendingProfileImport ?: return
        if (blocked()) { fail(SshProfileImportError.Operation.BLOCKED); return }
        // Confirmation starts one atomic transaction. Cancellation is offered only before this point.
        committing = true
        mutableState.value = state.value.copy(busy = true, failure = null)
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                operations.withLock {
                    if (blocked()) { fail(SshProfileImportError.Operation.BLOCKED); return@withLock }
                    withContext(worker) {
                        update { current ->
                            if (blocked()) throw ImportBlockedException()
                            applySshImportPlan(current, pending.plan) // Check preview freshness and capacity inside the transaction.
                            if (keyRef != null && current.keys.none { it.id == keyRef }) throw ImportKeyUnavailableException()
                            val ids = pending.plan.newProfiles.map { it.after.id }.iterator()
                            val selected = planSshProfileImport(current, pending.incoming, keyRef) { ids.next() }
                            applySshImportPlan(current, selected)
                        }
                        for (ref in pending.plan.detachedSecretRefs) {
                            try { deleteSecret(ref) }
                            catch (_: Exception) {
                                currentCoroutineContext().ensureActive()
                                // Data is committed: an orphaned secret is safe without a profile reference.
                                // Cleanup failure must not turn a successful import into an error.
                            }
                        }
                    }
                    mutableState.value = SshProfileImportState()
                }
            } catch (error: Exception) { handle(error) }
            finally { committing = false; mutableState.value = mutableState.value.copy(busy = false) }
        }
    }

    fun cancel() {
        if (committing) return
        // Keep busy until the cancelled worker exits: its finally must not clear a newer transfer.
        job?.cancel()
        mutableState.value = SshProfileImportState(busy = state.value.busy)
    }

    private fun fail(error: SshProfileImportError) {
        mutableState.value = SshProfileImportState(busy = state.value.busy, failure = error)
    }
    private suspend fun handle(error: Exception) {
        when (error) {
            is TimeoutCancellationException -> { currentCoroutineContext().ensureActive(); fail(SshProfileImportError.Operation.TIMEOUT) }
            is CancellationException -> throw error
            is SshProfileImportException -> fail(SshProfileImportError.Document(error.reason))
            is StreamSizeLimitException -> fail(SshProfileImportError.Document(SshProfileImportFailure.SIZE))
            is ImportBlockedException -> fail(SshProfileImportError.Operation.BLOCKED)
            is ImportKeyUnavailableException -> fail(SshProfileImportError.Operation.KEY_UNAVAILABLE)
            else -> fail(SshProfileImportError.Operation.IO)
        }
    }
    private class ImportBlockedException : IllegalStateException()
    private class ImportKeyUnavailableException : IllegalStateException()
}
