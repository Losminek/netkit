package cz.netkit.app.wol

import cz.netkit.shared.wol.PacketSendException
import cz.netkit.shared.wol.SendFailure
import cz.netkit.shared.wol.PacketSender
import cz.netkit.shared.wol.WifiNetworkHandle
import cz.netkit.shared.wol.parseIpv4
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class NetworkChoice<T>(val handle: T, val wifi: Boolean, val vpn: Boolean)

/** Preserve input order (active network first), never select VPN or mobile-only transport. */
internal fun <T> selectWifi(candidates: List<NetworkChoice<T>>): T? =
    candidates.firstOrNull { it.wifi && !it.vpn }?.handle

/** Numeric IPv4 only. Hostnames are resolved separately through the selected network. */
internal fun ipv4Literal(host: String): InetAddress? = parseIpv4(host)?.let {
    InetAddress.getByAddress(it.toString().split('.').map(String::toInt).map(Int::toByte).toByteArray())
}

internal fun dnsIpv4Answer(answer: List<InetAddress>, rcode: Int): InetAddress =
    answer.firstOrNull { it is Inet4Address && rcode == 0 }
        ?: throw IOException("No IPv4 DNS answer")

internal interface UdpSocket : Closeable {
    fun enableBroadcast()
    fun bind(network: WifiNetworkHandle)
    fun send(target: InetAddress, port: Int, payload: ByteArray)
}
internal interface UdpPlatform {
    fun isWifi(network: WifiNetworkHandle): Boolean
    suspend fun resolve(network: WifiNetworkHandle, host: String): InetAddress
    fun openSocket(): UdpSocket
}

/** Testable orchestration; the Android adapter alone owns binder/DNS/socket API calls. */
internal class BoundPacketSender(
    private val platform: UdpPlatform,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMillis: Long = 5_000,
) : PacketSender {
    override suspend fun sendUdp(network: WifiNetworkHandle, host: String, port: Int, payload: ByteArray) {
        require(port in 1..65535)
        try {
            val completed = withTimeoutOrNull(timeoutMillis) {
                withContext(io) {
                    if (!platform.isWifi(network)) throw PacketSendException(SendFailure.NETWORK_LOST)
                    val target = ipv4Literal(host) ?: try {
                        platform.resolve(network, host)
                    } catch (error: CancellationException) { throw error
                    } catch (error: Exception) { throw PacketSendException(SendFailure.DNS, error) }
                    send(network, target, port, payload)
                }
                true
            }
            if (completed != true) throw PacketSendException(SendFailure.TIMEOUT)
        } catch (error: IOException) { throw PacketSendException(SendFailure.IO, error) }
    }

    private suspend fun send(network: WifiNetworkHandle, target: InetAddress, port: Int, payload: ByteArray): Unit =
        suspendCancellableCoroutine { cont ->
            val current = AtomicReference<UdpSocket?>()
            cont.invokeOnCancellation { current.get()?.close() }
            io.dispatch(EmptyCoroutineContext) {
                try {
                    if (cont.isActive) {
                        platform.openSocket().use { socket ->
                            current.set(socket)
                            if (!cont.isActive) return@use
                            if (!platform.isWifi(network)) throw PacketSendException(SendFailure.NETWORK_LOST)
                            socket.enableBroadcast()
                            socket.bind(network)
                            if (!cont.isActive) return@use
                            socket.send(target, port, payload)
                        }
                    }
                    cont.resume(Unit)
                } catch (error: Exception) { cont.resumeWithException(error)
                } finally { current.getAndSet(null)?.close() }
            }
        }
}
