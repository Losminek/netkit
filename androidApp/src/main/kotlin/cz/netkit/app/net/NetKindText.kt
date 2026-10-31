package cz.netkit.app.net

import androidx.annotation.StringRes
import cz.netkit.app.R
import cz.netkit.shared.net.NetKind

/** Short label of a network type ("Wi-Fi", "Mobilní data", "VPN"); one source for tools, speed and SSH. */
@StringRes
internal fun netKindLabel(kind: NetKind): Int = when (kind) {
    NetKind.WIFI -> R.string.net_kind_wifi
    NetKind.CELLULAR -> R.string.net_kind_cellular
    NetKind.ETHERNET -> R.string.net_kind_ethernet
    NetKind.VPN -> R.string.net_kind_vpn
    NetKind.OTHER -> R.string.net_kind_other
}
