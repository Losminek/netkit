package cz.netkit.app.lan

import cz.netkit.shared.lan.NETBIOS_MAX_RESPONSE_BYTES
import cz.netkit.shared.lan.NETBIOS_NAME_SERVICE_PORT
import cz.netkit.shared.lan.NetBiosInfo
import cz.netkit.shared.lan.ipv4Number
import cz.netkit.shared.lan.netBiosNodeStatusRequest
import cz.netkit.shared.lan.parseNetBiosNodeStatus
import java.io.IOException
import java.io.UncheckedIOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Unicast NBSTAT query (UDP 137) to one host already found alive. The socket is bound to the
 * selected Wi-Fi `Network` before connect; there is no fallback to another network. Any
 * failure (bind, ICMP port unreachable, timeout, malformed reply) means "no NetBIOS name".
 */
internal object NetBios {
    const val TIMEOUT_MS = 800
    private val random = SecureRandom()

    suspend fun query(wifi: LanWifi, address: String, timeoutMs: Int = TIMEOUT_MS): NetBiosInfo? = withContext(Dispatchers.IO) {
        require(ipv4Number(address) != null && timeoutMs in 100..TIMEOUT_MS)
        val transactionId = random.nextInt(65536)
        val request = netBiosNodeStatusRequest(transactionId)
        val target = InetAddress.getByAddress(address.split('.').map { it.toInt().toByte() }.toByteArray())
        suspendCancellableCoroutine { cont ->
            val socket = DatagramSocket()
            cont.invokeOnCancellation { socket.close() }
            try {
                wifi.network.bindSocket(socket)
                socket.connect(target, NETBIOS_NAME_SERVICE_PORT)
                socket.send(DatagramPacket(request, request.size))
                val deadline = System.nanoTime() + timeoutMs * 1_000_000L
                // One extra byte detects datagrams above the accepted size.
                val buffer = ByteArray(NETBIOS_MAX_RESPONSE_BYTES + 1)
                var result: NetBiosInfo? = null
                while (cont.isActive && result == null) {
                    val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
                    if (remainingMs <= 0) break
                    socket.soTimeout = remainingMs.toInt().coerceAtLeast(1)
                    val packet = DatagramPacket(buffer, buffer.size)
                    try { socket.receive(packet) } catch (_: SocketTimeoutException) { break }
                    // A connected socket only delivers from target:137; checked again defensively.
                    if (packet.address != target || packet.port != NETBIOS_NAME_SERVICE_PORT) continue
                    if (packet.length > NETBIOS_MAX_RESPONSE_BYTES) continue
                    result = parseNetBiosNodeStatus(buffer, transactionId, packet.length)
                }
                if (cont.isActive) cont.resume(result)
            } catch (_: IOException) {
                if (cont.isActive) cont.resume(null)
            } catch (_: UncheckedIOException) {
                // DatagramSocket.connect may report a socket error unchecked.
                if (cont.isActive) cont.resume(null)
            } finally { socket.close() }
        }
    }
}
