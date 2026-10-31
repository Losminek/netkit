package cz.netkit.app.wifi.live

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Build
import cz.netkit.shared.lan.ipv4Number
import cz.netkit.shared.wifi.preferIpv4Address
import java.net.Inet4Address
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/** Latest paired capabilities + link properties of the connected (non-VPN) Wi-Fi network. */
internal data class WifiLinkSnapshot(
    val network: Network,
    val capabilities: NetworkCapabilities,
    val links: LinkProperties,
) {
    /** API 31+: location-aware `WifiInfo` delivered with `FLAG_INCLUDE_LOCATION_INFO`; `null` below 31. */
    val info: WifiInfo? get() = if (Build.VERSION.SDK_INT >= 31) capabilities.transportInfo as? WifiInfo else null

    /** IPv4 default gateway from the Wi-Fi default route, as in `WifiViewModel.currentFrom`. */
    val gatewayIpv4: String? get() = preferIpv4Address(links.routes.filter { it.isDefaultRoute }.mapNotNull { it.gateway?.hostAddress })
        ?.takeIf { ipv4Number(it) != null }

    val ipv4Address: String? get() = links.linkAddresses.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
}

/**
 * One long-lived `NetworkCallback` for the Wi-Fi transport (instead of registering every tick).
 * Same request/flags as `WifiViewModel.readCurrent`: API 31+ uses `FLAG_INCLUDE_LOCATION_INFO`
 * so `WifiInfo` keeps SSID/BSSID when the app holds location permission.
 *
 * The flow emits the selected snapshot (or `null` when no Wi-Fi network has both capabilities
 * and link properties) after every callback; it registers on collection and unregisters when
 * the collector is cancelled. Registration failure is thrown to the collector.
 */
internal class WifiLinkMonitor(private val connectivity: ConnectivityManager) {
    fun observe(): Flow<WifiLinkSnapshot?> = callbackFlow {
        val lock = Any()
        val capabilitiesByNetwork = linkedMapOf<Network, NetworkCapabilities>()
        val linksByNetwork = linkedMapOf<Network, LinkProperties>()
        fun publish() {
            val selected = synchronized(lock) {
                capabilitiesByNetwork.entries.firstNotNullOfOrNull { (network, caps) ->
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        linksByNetwork[network]?.let { WifiLinkSnapshot(network, caps, it) }
                    } else null
                }
            }
            trySend(selected)
        }
        fun updateCapabilities(network: Network, caps: NetworkCapabilities) {
            synchronized(lock) { capabilitiesByNetwork[network] = caps }
            publish()
        }
        fun updateLinks(network: Network, links: LinkProperties) {
            synchronized(lock) { linksByNetwork[network] = links }
            publish()
        }
        fun remove(network: Network) {
            synchronized(lock) {
                capabilitiesByNetwork.remove(network)
                linksByNetwork.remove(network)
            }
            publish()
        }
        val callback = if (Build.VERSION.SDK_INT >= 31) {
            object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
                    updateCapabilities(network, networkCapabilities)
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                    updateLinks(network, linkProperties)
                override fun onLost(network: Network) = remove(network)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
                    updateCapabilities(network, networkCapabilities)
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                    updateLinks(network, linkProperties)
                override fun onLost(network: Network) = remove(network)
            }
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        connectivity.registerNetworkCallback(request, callback)
        awaitClose { runCatching { connectivity.unregisterNetworkCallback(callback) } }
    }.conflate()
}
