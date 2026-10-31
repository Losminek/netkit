package cz.netkit.shared.ssh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A UI response belongs to one request, never whichever prompt happens to be current. */
class PromptReplyGate<T> {
    private data class Pending<T>(val requestId: String, val answer: CompletableDeferred<T>)
    private val mutex = Mutex()
    private var pending: Pending<T>? = null

    suspend fun open(requestId: String): Deferred<T> = mutex.withLock {
        require(requestId.isNotBlank())
        check(pending == null)
        val request = Pending(requestId, CompletableDeferred<T>())
        pending = request
        request.answer
    }

    suspend fun answer(requestId: String, value: T): Boolean = mutex.withLock {
        val request = pending ?: return@withLock false
        if (request.requestId != requestId) return@withLock false
        request.answer.complete(value)
    }

    suspend fun close(requestId: String) = mutex.withLock {
        val request = pending
        if (request?.requestId == requestId) {
            pending = null
            request.answer.cancel()
        }
    }
}
