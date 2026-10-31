package cz.netkit.shared.net

import kotlinx.serialization.Serializable

/**
 * Transport of the network a measurement runs on. [VPN] wins over the underlying transport,
 * because traffic of the app enters the tunnel. Used by tools, speed test history and Přehled.
 */
@Serializable
enum class NetKind { WIFI, CELLULAR, ETHERNET, VPN, OTHER }
