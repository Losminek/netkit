package cz.netkit.app.net

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import cz.netkit.shared.net.NetKind
import java.net.Inet4Address
import java.net.Inet6Address
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The app's default network as Android routes it (Wi-Fi, mobile data, ethernet or the VPN that
 * covers the app). [metered] = `NET_CAPABILITY_NOT_METERED` missing (mobile data, hotspot marked
 * as metered). [localIpv6] is the first non-link-local IPv6 address. [dnsServers] are numeric
 * addresses; with [privateDnsActive] the queries go to [privateDnsServerName] over TLS instead
 * (the name is `null` in opportunistic mode).
 */
internal data class ActiveNetworkInfo(
    val network: Network,
    val kind: NetKind,
    val metered: Boolean,
    val interfaceName: String?,
    val localIpv4: String?,
    val localIpv6: String?,
    val dnsServers: List<String>,
    val privateDnsActive: Boolean,
    val privateDnsServerName: String?,
    val viaVpn: Boolean,
)

/**
 * Watches the default network through `registerDefaultNetworkCallback`. The flow emits an
 * [ActiveNetworkInfo] once both capabilities and link properties of the current default are
 * known, emits again on every change of them, and emits `null` when there is no default network.
 * While the default switches from one network to another, the previous value stays until the new
 * network is complete, so a hand-over is one change, not a transient `null`.
 */
internal class ActiveNetworkMonitor(private val connectivity: ConnectivityManager) {
    fun observe(): Flow<ActiveNetworkInfo?> = callbackFlow {
        val lock = Any()
        var current: Network? = null
        var capabilities: NetworkCapabilities? = null
        var links: LinkProperties? = null
        fun publish(lost: Boolean = false) {
            val info = synchronized(lock) {
                val network = current
                val caps = capabilities
                val properties = links
                if (network != null && caps != null && properties != null) activeNetworkInfo(network, caps, properties) else null
            }
            if (info != null || lost) trySend(info)
        }
        fun select(network: Network) {
            if (current != network) { current = network; capabilities = null; links = null }
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(lock) { select(network) }
                publish()
            }
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                synchronized(lock) { select(network); capabilities = networkCapabilities }
                publish()
            }
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                synchronized(lock) { select(network); links = linkProperties }
                publish()
            }
            override fun onLost(network: Network) {
                val wasCurrent = synchronized(lock) {
                    (current == network).also { if (it) { current = null; capabilities = null; links = null } }
                }
                if (wasCurrent) publish(lost = true)
            }
        }
        // Without a default network no callback arrives; say so instead of staying silent.
        if (connectivity.activeNetwork == null) trySend(null)
        try {
            connectivity.registerDefaultNetworkCallback(callback)
        } catch (_: RuntimeException) {
            // SecurityException / TooManyRequestsException: report "no network", never crash a screen.
            trySend(null)
            close()
            return@callbackFlow
        }
        awaitClose { runCatching { connectivity.unregisterNetworkCallback(callback) } }
    }.conflate().distinctUntilChanged()
}

/** Synchronous snapshot of the default network; `null` without one. Blocking binder calls. */
internal fun ConnectivityManager.readActiveNetworkInfo(): ActiveNetworkInfo? {
    val network = activeNetwork ?: return null
    val capabilities = getNetworkCapabilities(network) ?: return null
    val links = getLinkProperties(network) ?: return null
    return activeNetworkInfo(network, capabilities, links)
}

internal fun activeNetworkInfo(network: Network, capabilities: NetworkCapabilities, links: LinkProperties): ActiveNetworkInfo {
    val vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    val kind = when {
        vpn -> NetKind.VPN
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetKind.WIFI
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetKind.CELLULAR
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetKind.ETHERNET
        else -> NetKind.OTHER
    }
    val addresses = links.linkAddresses.map { it.address }
    return ActiveNetworkInfo(
        network = network,
        kind = kind,
        metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        interfaceName = links.interfaceName,
        localIpv4 = addresses.firstOrNull { it is Inet4Address }?.hostAddress,
        localIpv6 = addresses.firstOrNull { it is Inet6Address && !it.isLinkLocalAddress }?.hostAddress?.substringBefore('%'),
        dnsServers = links.dnsServers.mapNotNull { it.hostAddress?.substringBefore('%') },
        privateDnsActive = links.isPrivateDnsActive,
        privateDnsServerName = links.privateDnsServerName,
        viaVpn = vpn,
    )
}
