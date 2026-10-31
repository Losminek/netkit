package cz.netkit.app.lan

import cz.netkit.shared.lan.HttpTarget
import cz.netkit.shared.lan.Ipv4Subnet
import cz.netkit.shared.lan.SSDP_IPV4_GROUP
import cz.netkit.shared.lan.SSDP_MAX_RESPONSE_BYTES
import cz.netkit.shared.lan.SSDP_MX_SECONDS
import cz.netkit.shared.lan.SSDP_PORT
import cz.netkit.shared.lan.SsdpResponse
import cz.netkit.shared.lan.UPNP_MAX_RESPONSE_BYTES
import cz.netkit.shared.lan.UpnpDescription
import cz.netkit.shared.lan.httpResponseComplete
import cz.netkit.shared.lan.parseHttpResponseBody
import cz.netkit.shared.lan.parseSsdpResponse
import cz.netkit.shared.lan.parseUpnpDescription
import cz.netkit.shared.lan.ssdpMSearch
import cz.netkit.shared.lan.upnpDescriptionRequest
import java.io.IOException
import java.io.UncheckedIOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketTimeoutException
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * SSDP discovery and UPnP device description fetch for hosts already found alive.
 *
 * [search]: one socket on an ephemeral port bound to the Wi-Fi `Network` sends
 * `M-SEARCH upnp:rootdevice` (MX 2, IP TTL 1 → never leaves the link) to
 * 239.255.255.250:1900 twice; replies come by unicast to that port (no MulticastLock). Only
 * replies from [hosts] inside the range are kept, the first per responder.
 *
 * [describe]: hand-written HTTP/1.0 GET over a raw socket from `Network.socketFactory`
 * (the app's cleartext-forbidding network security config stays unchanged; see
 * docs/bezpecnost.md). 2 s total for connect + request + response, at most 64 KiB, no
 * redirects, no keep-alive; reading stops once the framed response is complete. Any failure
 * (including socket creation) means "no description" for that device only. The caller limits
 * parallelism and validates the target first.
 */
internal object UpnpProbe {
    /** MX + 1 s margin for late replies. */
    const val WINDOW_MS = (SSDP_MX_SECONDS + 1) * 1_000L
    const val FETCH_TIMEOUT_MS = 2_000L
    private const val RESEND_AFTER_MS = 500L
    private const val MAX_DATAGRAMS = 256
    private const val SSDP_TTL = 1

    suspend fun search(wifi: LanWifi, range: Ipv4Subnet, hosts: Set<String>): Map<String, SsdpResponse> = withContext(Dispatchers.IO) {
        if (hosts.isEmpty()) return@withContext emptyMap()
        val request = ssdpMSearch()
        val group = InetSocketAddress(ipv4Address(SSDP_IPV4_GROUP), SSDP_PORT)
        suspendCancellableCoroutine { cont ->
            val found = LinkedHashMap<String, SsdpResponse>()
            // Creation inside the error handling: a failure only empties this probe, never mDNS.
            val socket = try { MulticastSocket(null as SocketAddress?) } catch (_: IOException) {
                cont.resume(emptyMap()); return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation { socket.close() }
            try {
                socket.bind(InetSocketAddress(0))
                wifi.network.bindSocket(socket)
                NetworkInterface.getByName(wifi.interfaceName)?.let { socket.networkInterface = it }
                socket.timeToLive = SSDP_TTL
                val started = System.nanoTime()
                val resendAt = started + RESEND_AFTER_MS * 1_000_000L
                val deadline = started + WINDOW_MS * 1_000_000L
                socket.send(DatagramPacket(request, request.size, group))
                var resent = false
                var datagrams = 0
                val buffer = ByteArray(SSDP_MAX_RESPONSE_BYTES + 1)
                while (cont.isActive && datagrams < MAX_DATAGRAMS && found.size < hosts.size) {
                    val now = System.nanoTime()
                    if (now >= deadline) break
                    if (!resent && now >= resendAt) { socket.send(DatagramPacket(request, request.size, group)); resent = true }
                    val waitUntil = if (resent) deadline else resendAt
                    socket.soTimeout = ((waitUntil - now) / 1_000_000L).toInt().coerceAtLeast(1)
                    val packet = DatagramPacket(buffer, buffer.size)
                    try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                    datagrams++
                    val source = (packet.address as? Inet4Address)?.hostAddress ?: continue
                    if (source !in hosts || !range.contains(source) || source in found) continue
                    if (packet.length > SSDP_MAX_RESPONSE_BYTES) continue
                    parseSsdpResponse(buffer, packet.length)?.let { found[source] = it }
                }
                if (cont.isActive) cont.resume(found.toMap())
            } catch (_: IOException) {
                if (cont.isActive) cont.resume(found.toMap())
            } catch (_: UncheckedIOException) {
                if (cont.isActive) cont.resume(found.toMap())
            } finally { socket.close() }
        }
    }

    suspend fun describe(wifi: LanWifi, target: HttpTarget): UpnpDescription? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                // A failure to create the socket drops only this device's description.
                val socket = try { wifi.network.socketFactory.createSocket() } catch (_: IOException) {
                    cont.resume(null); return@suspendCancellableCoroutine
                }
                cont.invokeOnCancellation { try { socket.close() } catch (_: IOException) { } }
                try {
                    val deadline = System.nanoTime() + FETCH_TIMEOUT_MS * 1_000_000L
                    fun remainingMs(): Int = ((deadline - System.nanoTime()) / 1_000_000L).toInt().also { if (it <= 0) throw SocketTimeoutException() }
                    socket.connect(InetSocketAddress(ipv4Address(target.host), target.port), remainingMs())
                    socket.soTimeout = remainingMs()
                    socket.getOutputStream().apply { write(upnpDescriptionRequest(target)); flush() }
                    val input = socket.getInputStream()
                    // One extra byte detects responses above the cap.
                    val buffer = ByteArray(UPNP_MAX_RESPONSE_BYTES + 1)
                    var size = 0
                    // Stop as soon as Content-Length / the last chunk is in; without framing read to EOF.
                    // A server that neither frames nor closes runs into the 2 s limit and the
                    // partial response is dropped (it could be cut at any byte).
                    while (size < buffer.size && !httpResponseComplete(buffer, minOf(size, UPNP_MAX_RESPONSE_BYTES))) {
                        socket.soTimeout = remainingMs()
                        val read = input.read(buffer, size, buffer.size - size)
                        if (read < 0) break
                        size += read
                    }
                    val body = if (size > UPNP_MAX_RESPONSE_BYTES) null else parseHttpResponseBody(buffer, size)
                    // UPnP Device Architecture requires UTF-8 descriptions.
                    val description = body?.decodeToString()?.let(::parseUpnpDescription)
                    if (cont.isActive) cont.resume(description)
                } catch (_: IOException) {
                    if (cont.isActive) cont.resume(null)
                } catch (_: UncheckedIOException) {
                    if (cont.isActive) cont.resume(null)
                } finally { try { socket.close() } catch (_: IOException) { } }
            }
        }
    }
}
