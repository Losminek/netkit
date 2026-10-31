package cz.netkit.shared.net

/** Which network a ping, traceroute or port check to one numeric target uses. */
enum class ToolRouteDecision {
    /** The non-VPN Wi-Fi, socket bound to it (the local network). */
    WIFI,
    /** The active (default) network: Wi-Fi, mobile data, ethernet or VPN. */
    ACTIVE,
    /** A local address with neither Wi-Fi nor VPN (or ethernet): mobile data cannot reach it. */
    LOCAL_NEEDS_WIFI_OR_VPN,
    /** No usable network at all. */
    NO_NETWORK,
}

/**
 * Network choice for a tool run to [target] (numeric IPv4/IPv6).
 *
 * - Public target: the active network; Wi-Fi only when there is no active network but Wi-Fi exists.
 * - Local target (see [isPrivateOrLocalTarget]): Wi-Fi when available, unless the target lies
 *   outside the Wi-Fi subnet and a VPN is active (then the VPN, which typically routes such
 *   ranges). Without Wi-Fi the active network only when it is a VPN or ethernet (a local link);
 *   otherwise [ToolRouteDecision.LOCAL_NEEDS_WIFI_OR_VPN].
 * - Loopback is the phone itself and works on any network.
 */
fun chooseToolRoute(
    target: String,
    wifiAvailable: Boolean,
    activeKind: NetKind?,
    viaVpn: Boolean,
    targetInWifiSubnet: Boolean = false,
): ToolRouteDecision {
    val vpn = viaVpn || activeKind == NetKind.VPN
    if (isLoopbackTarget(target)) return when {
        wifiAvailable -> ToolRouteDecision.WIFI
        activeKind != null -> ToolRouteDecision.ACTIVE
        else -> ToolRouteDecision.NO_NETWORK
    }
    if (!isPrivateOrLocalTarget(target)) return when {
        activeKind != null -> ToolRouteDecision.ACTIVE
        wifiAvailable -> ToolRouteDecision.WIFI
        else -> ToolRouteDecision.NO_NETWORK
    }
    return when {
        wifiAvailable && (targetInWifiSubnet || !vpn) -> ToolRouteDecision.WIFI
        vpn && activeKind != null -> ToolRouteDecision.ACTIVE
        wifiAvailable -> ToolRouteDecision.WIFI
        activeKind == NetKind.ETHERNET -> ToolRouteDecision.ACTIVE
        activeKind == null -> ToolRouteDecision.NO_NETWORK
        else -> ToolRouteDecision.LOCAL_NEEDS_WIFI_OR_VPN
    }
}

/**
 * SSH hint before connecting: the target is a local literal (not loopback), the active network is
 * mobile data or another non-local transport and no VPN runs. Only a hint; a VPN outside the
 * Android API (split tunnel on the router) may still reach the target.
 */
fun localTargetNeedsVpn(host: String, activeKind: NetKind?, viaVpn: Boolean): Boolean =
    isPrivateOrLocalTarget(host) && !isLoopbackTarget(host) && !viaVpn &&
        (activeKind == NetKind.CELLULAR || activeKind == NetKind.OTHER)
