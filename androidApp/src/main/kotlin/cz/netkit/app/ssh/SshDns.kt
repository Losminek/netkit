package cz.netkit.app.ssh

import android.net.DnsResolver
import android.net.InetAddresses
import android.os.CancellationSignal
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** null network deliberately means Android default routing, including an active VPN. */
suspend fun resolveSshHost(host: String): List<InetAddress> {
    if (InetAddresses.isNumericAddress(host)) return listOf(InetAddresses.parseNumericAddress(host))
    return withTimeout(10000) {
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            DnsResolver.getInstance().query(null, host, DnsResolver.FLAG_EMPTY, { it.run() }, signal,
                object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                        if (!continuation.isActive) return
                        if (rcode == 0 && answer.isNotEmpty()) continuation.resume(answer.take(8))
                        else continuation.resumeWithException(UnknownHostException("SSH resolution failed"))
                    }
                    override fun onError(error: DnsResolver.DnsException) {
                        if (continuation.isActive) continuation.resumeWithException(UnknownHostException("SSH resolution failed").apply { initCause(error) })
                    }
                })
        }
    }
}
