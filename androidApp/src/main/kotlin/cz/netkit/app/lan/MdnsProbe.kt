package cz.netkit.app.lan

import cz.netkit.shared.lan.DNS_MAX_DATAGRAM_BYTES
import cz.netkit.shared.lan.Ipv4Subnet
import cz.netkit.shared.lan.MDNS_IPV4_GROUP
import cz.netkit.shared.lan.MDNS_PORT
import cz.netkit.shared.lan.MdnsFindings
import cz.netkit.shared.lan.interpretMdnsResponse
import cz.netkit.shared.lan.mdnsReverseQueries
import cz.netkit.shared.lan.mdnsServicesQuery
import cz.netkit.shared.lan.parseDnsMessage
import java.io.IOException
import java.io.UncheckedIOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Numeric IPv4 → InetAddress without any DNS lookup. */
internal fun ipv4Address(address: String): InetAddress =
    InetAddress.getByAddress(address.split('.').map { it.toInt().toByte() }.toByteArray())

/**
 * mDNS names and service types for hosts already found alive (RFC 6762 6.7 legacy unicast).
 *
 * One socket on an ephemeral port, bound to the selected Wi-Fi `Network` and with
 * IP_MULTICAST_IF = the Wi-Fi interface, sends reverse PTR questions (≤ 32 per datagram) and
 * one DNS-SD service enumeration to 224.0.0.251:5353, all twice (t = 0 and 1 s). Responders
 * answer by unicast to the source port, so no group membership and no MulticastLock are
 * needed. The window is [WINDOW_MS]; at most [MAX_DATAGRAMS] replies are read, only from port
 * 5353 and from inside [range]. Any socket failure means "no mDNS data", never an error.
 */
internal object MdnsProbe {
    const val WINDOW_MS = 2_500L
    private const val RESEND_AFTER_MS = 1_000L
    private const val MAX_DATAGRAMS = 256
    /** RFC 6762 11: mDNS packets are sent with IP TTL 255; 224.0.0.251 is link-local and never routed. */
    private const val MDNS_TTL = 255
    private val random = SecureRandom()

    suspend fun identify(wifi: LanWifi, range: Ipv4Subnet, hosts: List<String>): MdnsFindings = withContext(Dispatchers.IO) {
        val targets = hosts.filter(range::contains)
        if (targets.isEmpty()) return@withContext MdnsFindings()
        val ids = HashSet<Int>()
        fun nextId(): Int { while (true) { val id = 1 + random.nextInt(65535); if (ids.add(id)) return id } }
        val queries = mdnsReverseQueries(targets, ::nextId).map { it.second } + mdnsServicesQuery(nextId())
        val group = InetSocketAddress(ipv4Address(MDNS_IPV4_GROUP), MDNS_PORT)
        suspendCancellableCoroutine { cont ->
            var findings = MdnsFindings()
            // Creation inside the error handling: a failure only empties this probe, never SSDP.
            val socket = try { MulticastSocket(null as SocketAddress?) } catch (_: IOException) {
                cont.resume(findings); return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation { socket.close() }
            try {
                socket.bind(InetSocketAddress(0))
                wifi.network.bindSocket(socket)
                NetworkInterface.getByName(wifi.interfaceName)?.let { socket.networkInterface = it }
                socket.timeToLive = MDNS_TTL
                fun sendAll() = queries.forEach { socket.send(DatagramPacket(it, it.size, group)) }
                val started = System.nanoTime()
                val resendAt = started + RESEND_AFTER_MS * 1_000_000L
                val deadline = started + WINDOW_MS * 1_000_000L
                sendAll()
                var resent = false
                var datagrams = 0
                // One extra byte detects datagrams above the accepted size.
                val buffer = ByteArray(DNS_MAX_DATAGRAM_BYTES + 1)
                while (cont.isActive && datagrams < MAX_DATAGRAMS) {
                    val now = System.nanoTime()
                    if (now >= deadline) break
                    if (!resent && now >= resendAt) { sendAll(); resent = true }
                    val waitUntil = if (resent) deadline else resendAt
                    socket.soTimeout = ((waitUntil - now) / 1_000_000L).toInt().coerceAtLeast(1)
                    val packet = DatagramPacket(buffer, buffer.size)
                    try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                    datagrams++
                    val source = (packet.address as? Inet4Address)?.hostAddress ?: continue
                    if (packet.port != MDNS_PORT || packet.length > DNS_MAX_DATAGRAM_BYTES || !range.contains(source)) continue
                    val message = parseDnsMessage(buffer, packet.length) ?: continue
                    findings = findings.plus(interpretMdnsResponse(message, source, range, ids))
                }
                if (cont.isActive) cont.resume(findings)
            } catch (_: IOException) {
                if (cont.isActive) cont.resume(findings)
            } catch (_: UncheckedIOException) {
                if (cont.isActive) cont.resume(findings)
            } finally { socket.close() }
        }
    }
}
