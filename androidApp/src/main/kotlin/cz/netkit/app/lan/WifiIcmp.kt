package cz.netkit.app.lan

import android.net.Network
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import cz.netkit.shared.lan.PingReply
import cz.netkit.shared.lan.ipv4Number
import cz.netkit.shared.lan.matchesIcmpEchoReply
import java.io.IOException
import java.net.InetAddress
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class IcmpUnavailableException(cause: Throwable) : IOException("ICMP_UNAVAILABLE", cause)

/** Unprivileged IPv4 echo socket, explicitly bound before connect. Never falls back to another network. */
internal object WifiIcmp {
    suspend fun echo(network: Network, address: String, timeoutMs: Int): PingReply = withContext(Dispatchers.IO) {
        require(ipv4Number(address) != null && timeoutMs in 100..5000)
        currentCoroutineContext().ensureActive()
        val fd = try { Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM or OsConstants.SOCK_NONBLOCK or OsConstants.SOCK_CLOEXEC, OsConstants.IPPROTO_ICMP) }
            catch (error: ErrnoException) { throw IcmpUnavailableException(error) }
        try {
            try {
                network.bindSocket(fd)
                Os.connect(fd, InetAddress.getByAddress(address.split('.').map { it.toInt().toByte() }.toByteArray()), 0)
            } catch (error: Exception) { throw IcmpUnavailableException(error) }
            val request = ByteArray(24).also { SecureRandom().nextBytes(it) }
            request[0] = 8; request[1] = 0; request[2] = 0; request[3] = 0
            // Kernel ping sockets set the identifier and checksum; sequence + nonce identify our reply.
            val start = System.nanoTime()
            val deadline = start + timeoutMs * 1_000_000L
            try { Os.write(fd, request, 0, request.size) }
            catch (error: ErrnoException) { throw IcmpUnavailableException(error) }
            val reply = ByteArray(512)
            val pollFd = StructPollfd()
            pollFd.fd = fd
            pollFd.events = OsConstants.POLLIN.toShort()
            val pollFds = arrayOf(pollFd)
            while (true) {
                currentCoroutineContext().ensureActive()
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) break
                // poll wakes immediately on readiness; 10 ms bounds cancellation latency,
                // it does not impose a minimum delay on a successful echo reply.
                val waitMs = ((remainingNanos + 999_999L) / 1_000_000L).coerceAtMost(10).toInt()
                try {
                    pollFd.revents = 0
                    val ready = Os.poll(pollFds, waitMs)
                    currentCoroutineContext().ensureActive()
                    if (ready == 0) continue
                    val events = pollFd.revents.toInt()
                    if (events and OsConstants.POLLNVAL != 0) {
                        throw IcmpUnavailableException(IOException("ICMP_SOCKET_INVALID"))
                    }
                    if (events and (OsConstants.POLLIN or OsConstants.POLLERR) == 0) {
                        if (events and OsConstants.POLLHUP != 0) return@withContext PingReply(null)
                        continue
                    }
                    val count = Os.read(fd, reply, 0, reply.size)
                    val receivedAt = System.nanoTime()
                    if (receivedAt <= deadline && matchesIcmpEchoReply(request, reply, count)) {
                        return@withContext PingReply((receivedAt - start) / 1_000_000.0)
                    }
                } catch (error: ErrnoException) {
                    if (error.errno != OsConstants.EAGAIN && error.errno != OsConstants.EINTR) {
                        if (error.errno == OsConstants.ECONNREFUSED || error.errno == OsConstants.EHOSTUNREACH || error.errno == OsConstants.ENETUNREACH) return@withContext PingReply(null)
                        throw IcmpUnavailableException(error)
                    }
                }
            }
            PingReply(null)
        } finally { try { Os.close(fd) } catch (_: ErrnoException) { /* Already invalid. */ } }
    }
}
