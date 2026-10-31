package cz.netkit.app.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.LinkProperties
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.CancellationSignal
import cz.netkit.app.net.ActiveNetworkInfo
import cz.netkit.app.net.readActiveNetworkInfo
import cz.netkit.shared.lan.*
import cz.netkit.shared.net.NetKind
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal data class LanWifi(val network: Network, val address: String, val prefix: Int, val interfaceName: String) {
    val subnet: Ipv4Subnet get() = Ipv4Subnet.parse("$address/$prefix")!!
}
/**
 * Network of one tool run (ping, traceroute, port check, DNS). [wifi] is set when the run uses the
 * non-VPN Wi-Fi (also when that Wi-Fi is the active network); otherwise the run uses the active
 * network ([kind] mobile data, ethernet, VPN). Equality is the identity of the route: a confirmed
 * request runs only while the same route is still selected.
 */
/** [followsDefault]: chosen as the active network, so the run stops once another network becomes the default. */
internal data class ToolRoute(val network: Network, val kind: NetKind, val interfaceName: String?, val localAddress: String?, val wifi: LanWifi?,
    val followsDefault: Boolean = false) {
    companion object {
        fun wifi(wifi: LanWifi) = ToolRoute(wifi.network, NetKind.WIFI, wifi.interfaceName, wifi.address, wifi)
        fun active(active: ActiveNetworkInfo, wifi: LanWifi?): ToolRoute =
            if (wifi != null && wifi.network == active.network) wifi(wifi).copy(followsDefault = true)
            else ToolRoute(active.network, active.kind, active.interfaceName, active.localIpv4, null, followsDefault = true)
    }
}

internal class LanBackend(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val direct = Executor { it.run() }
    private val probes = Dispatchers.IO.limitedParallelism(64)

    @Suppress("DEPRECATION")
    fun wifi(): LanWifi? {
        return (listOfNotNull(connectivity.activeNetwork) + connectivity.allNetworks.toList()).distinct().firstNotNullOfOrNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true || capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) != true) return@firstNotNullOfOrNull null
            val link = connectivity.getLinkProperties(network) ?: return@firstNotNullOfOrNull null
            val address = link.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return@firstNotNullOfOrNull null
            LanWifi(network, address.address.hostAddress ?: return@firstNotNullOfOrNull null, address.prefixLength, link.interfaceName ?: return@firstNotNullOfOrNull null)
        }
    }
    fun observeWifi() = callbackFlow<Unit> {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(Unit) }
            override fun onLost(network: Network) { trySend(Unit) }
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) { trySend(Unit) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { trySend(Unit) }
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        connectivity.registerNetworkCallback(request, callback)
        trySend(Unit)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.map { withContext(Dispatchers.IO) { wifi() } }.distinctUntilChanged()

    fun checkWifi(wifi: LanWifi) {
        val caps = connectivity.getNetworkCapabilities(wifi.network)
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) != true) throw IOException("WIFI_LOST")
        val link = connectivity.getLinkProperties(wifi.network)
        if (link?.interfaceName != wifi.interfaceName || link.linkAddresses.none { it.address.hostAddress == wifi.address }) throw IOException("WIFI_LOST")
    }
    fun traceNeedsConfirmation(wifi: LanWifi): Boolean = requiresTraceRouteConfirmation(wifi.network, connectivity.activeNetwork)
    fun traceNeedsConfirmation(route: ToolRoute): Boolean = requiresTraceRouteConfirmation(route.network, connectivity.activeNetwork)

    /** Default network snapshot (blocking binder calls). */
    fun activeNetwork(): ActiveNetworkInfo? = connectivity.readActiveNetworkInfo()

    /**
     * A Wi-Fi route is checked like [checkWifi]. An active-network route must still be the default
     * network with the same interface; a hand-over (mobile data → Wi-Fi, VPN up/down) stops the run.
     */
    fun checkRoute(route: ToolRoute) {
        route.wifi?.let { wifi ->
            checkWifi(wifi)
            // A public target picked over the default Wi-Fi must not keep running outside a VPN brought up later.
            if (route.followsDefault && connectivity.activeNetwork != route.network) throw IOException("NETWORK_LOST")
            return
        }
        if (connectivity.activeNetwork != route.network) throw IOException("NETWORK_LOST")
        val link = connectivity.getLinkProperties(route.network) ?: throw IOException("NETWORK_LOST")
        if (link.interfaceName != route.interfaceName) throw IOException("NETWORK_LOST")
    }

    /** Forward lookup on [route]'s network, A and AAAA, without falling back to another network. */
    suspend fun resolve(route: ToolRoute, host: String): List<InetAddress> = withTimeout(4000) {
        checkRoute(route)
        ipv4Number(host)?.let { return@withTimeout listOf(numericAddress(host)) }
        require(validLanHost(host))
        lookup(route.network, host)
    }
    suspend fun reverse(route: ToolRoute, address: String): String {
        // An active-network route over Wi-Fi keeps checkRoute(), so a later VPN still stops the run.
        route.wifi?.takeUnless { route.followsDefault }?.let { return reverse(it, address) }
        return withTimeout(1500) {
            require(ipv4Number(address) != null)
            if (!shouldQueryLanPtr(address)) return@withTimeout ""
            checkRoute(route)
            ptr(route.network, address)
        }
    }
    suspend fun ping(route: ToolRoute, address: String, timeoutMs: Int, ttl: Int? = null): PingReply {
        route.wifi?.takeUnless { route.followsDefault }?.let { return ping(it, address, timeoutMs, ttl) }
        require(ipv4Number(address) != null)
        checkRoute(route)
        if (ttl == null) return WifiIcmp.echo(route.network, address, timeoutMs)
        return processPing(route.interfaceName, address, timeoutMs, ttl) { checkRoute(route) }
    }
    suspend fun tcpProbe(route: ToolRoute, address: String, port: Int, timeoutMs: Int, banner: Boolean = false): TcpProbeResult {
        route.wifi?.takeUnless { route.followsDefault }?.let { return tcpProbe(it, address, port, timeoutMs, banner) }
        return boundTcpProbe(route.network, address, port, timeoutMs, banner, {}) { checkRoute(route) }
    }

    suspend fun resolve(wifi: LanWifi, host: String): List<InetAddress> = withTimeout(4000) {
        checkWifi(wifi)
        ipv4Number(host)?.let { return@withTimeout listOf(numericAddress(host)) }
        require(validLanHost(host))
        lookup(wifi.network, host)
    }
    private suspend fun lookup(network: Network, host: String): List<InetAddress> = suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }
        DnsResolver.getInstance().query(network, host, DnsResolver.FLAG_EMPTY, direct, signal,
            object : DnsResolver.Callback<List<InetAddress>> {
                override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                    if (!cont.isActive) return
                    if (rcode == 0 && answer.isNotEmpty()) cont.resume(answer) else cont.resumeWithException(IOException("DNS"))
                }
                override fun onError(error: DnsResolver.DnsException) { if (cont.isActive) cont.resumeWithException(error) }
            })
    }
    suspend fun reverse(wifi: LanWifi, address: String): String = withTimeout(1500) {
        require(ipv4Number(address) != null)
        if (!shouldQueryLanPtr(address)) return@withTimeout ""
        checkWifi(wifi)
        ptr(wifi.network, address)
    }
    private suspend fun ptr(network: Network, address: String): String = suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }
        DnsResolver.getInstance().rawQuery(network, ipv4PtrQuery(address,java.security.SecureRandom().nextInt(65536)), DnsResolver.FLAG_EMPTY, direct, signal,
            object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(answer: ByteArray, rcode: Int) { if (cont.isActive) cont.resume(if (rcode == 0) dnsPtrName(answer).orEmpty() else "") }
                override fun onError(error: DnsResolver.DnsException) { if (cont.isActive) cont.resume("") }
            })
    }
    suspend fun ping(wifi: LanWifi, address: String, timeoutMs: Int, ttl: Int? = null): PingReply {
        require(ipv4Number(address) != null)
        checkWifi(wifi)
        if (ttl == null) return WifiIcmp.echo(wifi.network, address, timeoutMs)
        return processPing(wifi.interfaceName, address, timeoutMs, ttl) { checkWifi(wifi) }
    }
    /**
     * TTL probe through the system ping process. It cannot be bound to a `Network`; `-I` pins the
     * interface. Without an interface name the process follows the default network.
     */
    private suspend fun processPing(interfaceName: String?, address: String, timeoutMs: Int, ttl: Int, check: () -> Unit): PingReply {
        return withTimeout(timeoutMs.toLong() + 800) {
            suspendCancellableCoroutine { cont ->
                val process = java.util.concurrent.atomic.AtomicReference<Process?>()
                cont.invokeOnCancellation { process.get()?.destroyForcibly() }
                Dispatchers.IO.dispatch(EmptyCoroutineContext) {
                    try {
                        if (!cont.isActive) return@dispatch
                        check()
                        val args = mutableListOf("/system/bin/ping", "-n")
                        if (interfaceName != null) args.addAll(listOf("-I", interfaceName))
                        args.addAll(listOf("-c", "1", "-W", ((timeoutMs + 999) / 1000).toString()))
                        args.addAll(listOf("-t", ttl.coerceIn(1,30).toString()))
                        args += address
                        val child = ProcessBuilder(args).redirectErrorStream(true).start()
                        process.set(child)
                        if (!cont.isActive) { child.destroyForcibly(); return@dispatch }
                        val finished = child.waitFor(timeoutMs.toLong() + 300, TimeUnit.MILLISECONDS)
                        if (!finished) child.destroyForcibly()
                        val output = if (finished) child.inputStream.bufferedReader().use { it.readText().take(8192) } else ""
                        if (output.contains("Operation not permitted",true) || output.contains("Permission denied",true) ||
                            output.contains("invalid option",true) || output.contains("unknown option",true)) throw IOException("PING_UNSUPPORTED")
                        if (cont.isActive) cont.resume(parsePingOutput(output, if (finished) child.exitValue() else -1))
                    } catch (error: Exception) { if (cont.isActive) cont.resumeWithException(error)
                    } finally { process.getAndSet(null)?.destroyForcibly() }
                }
            }
        }
    }
    /** Compatibility for callers interested only in open ports. */
    suspend fun tcp(wifi: LanWifi, address: String, port: Int, timeoutMs: Int, banner: Boolean = false, onConnected: () -> Unit = {}): LanResult? =
        tcpProbe(wifi, address, port, timeoutMs, banner, onConnected).result

    suspend fun tcpProbe(wifi: LanWifi, address: String, port: Int, timeoutMs: Int, banner: Boolean = false, onConnected: () -> Unit = {}): TcpProbeResult =
        boundTcpProbe(wifi.network, address, port, timeoutMs, banner, onConnected) { checkWifi(wifi) }

    private suspend fun boundTcpProbe(network: Network, address: String, port: Int, timeoutMs: Int, banner: Boolean,
        onConnected: () -> Unit, check: () -> Unit): TcpProbeResult =
        withContext(probes) {
            require(ipv4Number(address) != null && port in 1..65535)
            check()
            suspendCancellableCoroutine { cont ->
                val socket = Socket()
                cont.invokeOnCancellation { try { socket.close() } catch (_: IOException) { } }
                try {
                    if (cont.isActive) {
                        val result = probeTcpSocket(socket, numericAddress(address), port, timeoutMs, banner, network::bindSocket, onConnected)
                        if (cont.isActive) cont.resume(result)
                    }
                } finally { try { socket.close() } catch (_: IOException) { /* A completed connect remains OPEN. */ } }
            }
        }
    /** Older APIs cannot guarantee both network scoping and cancellation of service resolution. */
    fun services(wifi: LanWifi, type: String) = callbackFlow<LanResult> {
        if (Build.VERSION.SDK_INT < 34) throw UnsupportedOperationException("NSD_API")
        require(Regex("_[A-Za-z0-9-]{1,62}\\._(?:tcp|udp)\\.?").matches(type))
        checkWifi(wifi)
        val callbacks = mutableMapOf<String,NsdManager.ServiceInfoCallback>()
        val lock = Any()
        var stopped = false
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) { }
            override fun onDiscoveryStopped(serviceType: String) { }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close(IOException("NSD:$errorCode")) }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { close(IOException("NSD:$errorCode")) }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) { }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                synchronized(lock) {
                    if (stopped || callbacks.size >= 128 || callbacks.containsKey(serviceInfo.serviceName)) return
                    val callback = object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) { }
                        override fun onServiceUpdated(info: NsdServiceInfo) {
                            trySend(LanResult((info.hostAddresses.firstOrNull { it is Inet4Address } ?: info.hostAddresses.firstOrNull())?.hostAddress.orEmpty(),sanitizeLanText(info.serviceName),"mDNS",port=info.port,detail=sanitizeLanText(info.serviceType)))
                        }
                        override fun onServiceLost() { }
                        override fun onServiceInfoCallbackUnregistered() { }
                    }
                    try {
                        checkWifi(wifi)
                        serviceInfo.network = wifi.network
                        callbacks[serviceInfo.serviceName] = callback
                        nsd.registerServiceInfoCallback(serviceInfo,direct,callback)
                    } catch (error: Exception) { close(error) }
                }
            }
        }
        val watcher = launch {
            observeWifi().collect { selected ->
                if (selected != wifi) close(IOException("WIFI_LOST"))
            }
        }
        try { nsd.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,wifi.network,direct,listener) }
        catch (error: Exception) { watcher.cancel(); throw error }
        awaitClose {
            watcher.cancel()
            synchronized(lock) {
                stopped = true
                try { nsd.stopServiceDiscovery(listener) } catch (_: IllegalArgumentException) { /* Already stopped. */ }
                callbacks.values.forEach { callback ->
                    try { nsd.unregisterServiceInfoCallback(callback) } catch (_: IllegalArgumentException) { /* Registration failed. */ }
                }
                callbacks.clear()
            }
        }
    }

}
private fun numericAddress(address: String): InetAddress = InetAddress.getByAddress(address.split('.').map { it.toInt().toByte() }.toByteArray())

/** Bounded RFC1035 compression parser; only PTR answer records are returned. */
internal fun dnsPtrName(packet: ByteArray): String? = runCatching {
    fun u16(index: Int): Int = ((packet[index].toInt() and 255) shl 8) or (packet[index+1].toInt() and 255)
    fun name(start: Int): Pair<String,Int> {
        var cursor = start; var next = -1; var hops = 0
        val labels = mutableListOf<String>()
        while (true) {
            require(cursor in packet.indices && hops++ < 128)
            val size = packet[cursor].toInt() and 255
            if (size == 0) { if (next < 0) next = cursor+1; break }
            if (size and 0xc0 == 0xc0) {
                require(cursor+1 < packet.size)
                if (next < 0) next = cursor+2
                cursor = ((size and 0x3f) shl 8) or (packet[cursor+1].toInt() and 255)
            } else {
                require(size <= 63 && cursor+size < packet.size)
                labels += packet.copyOfRange(cursor+1,cursor+1+size).toString(Charsets.US_ASCII)
                cursor += size+1
            }
        }
        require(labels.sumOf { it.length+1 } <= 254)
        return labels.joinToString(".") to next
    }
    require(packet.size >= 12 && u16(4) <= 16 && u16(6) <= 128)
    var cursor = 12
    repeat(u16(4)) { cursor = name(cursor).second+4; require(cursor <= packet.size) }
    repeat(u16(6)) {
        cursor = name(cursor).second
        require(cursor+10 <= packet.size)
        val type = u16(cursor); val length = u16(cursor+8); cursor += 10
        require(cursor+length <= packet.size)
        if (type == 12) {
            require(length > 0)
            val parsed=name(cursor)
            require(parsed.second <= cursor+length)
            return@runCatching safePtrName(parsed.first)
        }
        cursor += length
    }
    null
}.getOrNull()
