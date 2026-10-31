package cz.netkit.app.wol

import android.content.Context
import cz.netkit.app.lan.LanBackend
import cz.netkit.shared.lan.ProbeStatus
import cz.netkit.shared.wol.WakeProber
import cz.netkit.shared.wol.WakeProbeStatus
import cz.netkit.shared.wol.WifiNetworkHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.net.Inet4Address

/** DNS, TCP and ICMP all use the exact Wi-Fi handle selected before the magic packets. */
class AndroidWakeProber(context: Context) : WakeProber {
    private val backend = LanBackend(context)
    override suspend fun probe(network: WifiNetworkHandle, host: String, port: Int?): WakeProbeStatus = try { withTimeout(4500) {
        val selected = (network as? AndroidWifiHandle)?.network ?: return@withTimeout WakeProbeStatus.UNAVAILABLE
        val wifi = backend.wifi()?.takeIf { it.network == selected } ?: return@withTimeout WakeProbeStatus.UNAVAILABLE
        try {
            val address = backend.resolve(wifi, host).firstOrNull { it is Inet4Address }?.hostAddress
                ?: return@withTimeout WakeProbeStatus.UNAVAILABLE
            if (port == null) {
                if (backend.ping(wifi, address, 1500).milliseconds != null) WakeProbeStatus.RESPONDS else WakeProbeStatus.NO_RESPONSE
            } else when (backend.tcpProbe(wifi, address, port, 1500).status) {
                ProbeStatus.OPEN, ProbeStatus.REFUSED -> WakeProbeStatus.RESPONDS
                ProbeStatus.TIMEOUT -> WakeProbeStatus.NO_RESPONSE
                ProbeStatus.ERROR -> WakeProbeStatus.UNAVAILABLE
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { WakeProbeStatus.UNAVAILABLE }
    } } catch (e: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        WakeProbeStatus.UNAVAILABLE
    }
}
