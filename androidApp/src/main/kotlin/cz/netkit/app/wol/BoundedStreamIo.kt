package cz.netkit.app.wol

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** Cancellation closes an acquired stream and cancels opening through the provider's CancellationSignal. */
internal class StreamSizeLimitException : IllegalArgumentException("Stream size limit")

internal object BoundedStreamIo {
    private suspend fun <S : Closeable,T> transfer(open: () -> S, cancelOpen: () -> Unit,
        timeoutMs: Long, operation: (S, () -> Boolean) -> T): T = withTimeout(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            val activeStream = AtomicReference<S?>()
            fun close() { try { activeStream.getAndSet(null)?.close() } catch (_: Exception) { /* Best effort on cancellation. */ } }
            continuation.invokeOnCancellation { try { cancelOpen() } finally { close() } }
            Dispatchers.IO.dispatch(EmptyCoroutineContext) {
                try {
                    if (!continuation.isActive) return@dispatch
                    val stream = open()
                    activeStream.set(stream)
                    if (!continuation.isActive) return@dispatch
                    val result = operation(stream) { continuation.isActive }
                    stream.close()
                    activeStream.compareAndSet(stream,null)
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                finally { close() }
            }
        }
    }
    suspend fun read(open: () -> InputStream, cancelOpen: () -> Unit, limit: Int, timeoutMs: Long = 15000): ByteArray =
        transfer(open,cancelOpen,timeoutMs) { stream, active ->
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (active()) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > limit) throw StreamSizeLimitException()
                output.write(buffer,0,count)
            }
            output.toByteArray()
        }
    suspend fun write(open: () -> OutputStream, cancelOpen: () -> Unit, bytes: ByteArray, timeoutMs: Long = 15000): Unit =
        transfer(open,cancelOpen,timeoutMs) { stream, active ->
            var offset=0
            while (offset < bytes.size && active()) {
                val count=minOf(8192,bytes.size-offset); stream.write(bytes,offset,count); offset+=count
            }
            stream.flush()
        }
}
