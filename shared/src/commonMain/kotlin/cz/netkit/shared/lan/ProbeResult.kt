package cz.netkit.shared.lan

import kotlinx.coroutines.async

enum class ProbeStatus { OPEN, REFUSED, TIMEOUT, ERROR }
data class TcpProbeResult(val status: ProbeStatus, val result: LanResult? = null)
data class ProbeCounts(val open: Int = 0, val refused: Int = 0, val timeout: Int = 0, val error: Int = 0) {
    fun add(status: ProbeStatus): ProbeCounts = when (status) {
        ProbeStatus.OPEN -> copy(open = open + 1)
        ProbeStatus.REFUSED -> copy(refused = refused + 1)
        ProbeStatus.TIMEOUT -> copy(timeout = timeout + 1)
        ProbeStatus.ERROR -> copy(error = error + 1)
    }
}
/** A child ping process cannot be bound to Network; a different default requires consent. */
fun <N> requiresTraceRouteConfirmation(selectedWifi: N, defaultNetwork: N?): Boolean = selectedWifi != defaultNetwork

/** Four discovery fallbacks per host, concurrent; the platform backend separately bounds active sockets. */
suspend fun probeDiscoveryFallback(probe: suspend (Int) -> TcpProbeResult): List<TcpProbeResult> = kotlinx.coroutines.coroutineScope {
    listOf(80,443,22,445).map { port -> async { probe(port) } }.let { jobs -> jobs.map { it.await() } }
}
