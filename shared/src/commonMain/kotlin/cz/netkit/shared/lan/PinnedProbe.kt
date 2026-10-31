package cz.netkit.shared.lan

/** A confirmation authorizes this numeric address on this exact network, never another DNS answer. */
data class PinnedLanHost<N>(val network: N, val address: String)

suspend fun <N> pinLanHost(network: N, host: String, resolve: suspend (N, String) -> String): PinnedLanHost<N> {
    val address=resolve(network,host)
    require(ipv4Number(address) != null)
    return PinnedLanHost(network,address)
}

suspend fun <N,T> probePinnedHost(pinned: PinnedLanHost<N>, selectedNetwork: N?, probe: suspend (N,String) -> T): T {
    check(selectedNetwork == pinned.network) { "WIFI_LOST" }
    return probe(pinned.network,pinned.address)
}
