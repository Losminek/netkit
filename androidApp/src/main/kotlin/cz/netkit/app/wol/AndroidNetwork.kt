package cz.netkit.app.wol

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.os.CancellationSignal
import cz.netkit.shared.wol.NetworkInfoProvider
import cz.netkit.shared.wol.PacketSender
import cz.netkit.shared.wol.WifiNetworkHandle
import cz.netkit.shared.wol.WifiNetworkInfo
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal data class AndroidWifiHandle(val network: Network) : WifiNetworkHandle

class AndroidNetworkInfoProvider(context: Context) : NetworkInfoProvider {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    @Suppress("DEPRECATION") // Available Wi-Fi may differ from the default cellular network.
    override suspend fun currentWifi(): WifiNetworkInfo? = withContext(Dispatchers.IO) {
        val networks = (listOfNotNull(manager.activeNetwork) + manager.allNetworks.toList()).distinct()
        val choices = networks.map { network ->
            val capabilities = manager.getNetworkCapabilities(network)
            NetworkChoice(network, capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
        }
        val network = selectWifi(choices) ?: return@withContext null
        val address = manager.getLinkProperties(network)?.linkAddresses?.firstOrNull { it.address is Inet4Address }
        WifiNetworkInfo(AndroidWifiHandle(network), address?.address?.hostAddress, address?.prefixLength)
    }
}

class AndroidPacketSender(context: Context) : PacketSender by BoundPacketSender(AndroidUdpPlatform(context))

private class AndroidUdpPlatform(context: Context) : UdpPlatform {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    override fun isWifi(network: WifiNetworkHandle): Boolean {
        val capabilities = manager.getNetworkCapabilities(network.androidNetwork()) ?: return false
        return selectWifi(listOf(NetworkChoice(network,
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)))) != null
    }

    override suspend fun resolve(network: WifiNetworkHandle, host: String): InetAddress = suspendCancellableCoroutine { cont ->
        val cancellation = CancellationSignal()
        cont.invokeOnCancellation { cancellation.cancel() }
        DnsResolver.getInstance().query(
            network.androidNetwork(), host, DnsResolver.TYPE_A, DnsResolver.FLAG_EMPTY,
            Executor { it.run() }, cancellation,
            object : DnsResolver.Callback<List<InetAddress>> {
                override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                    try { cont.resume(dnsIpv4Answer(answer, rcode))
                    } catch (error: IOException) { cont.resumeWithException(error) }
                }
                override fun onError(error: DnsResolver.DnsException) { cont.resumeWithException(error) }
            },
        )
    }

    override fun openSocket(): UdpSocket = object : UdpSocket {
        private val socket = DatagramSocket()
        override fun enableBroadcast() { socket.broadcast = true }
        override fun bind(network: WifiNetworkHandle) { network.androidNetwork().bindSocket(socket) }
        override fun send(target: InetAddress, port: Int, payload: ByteArray) {
            socket.send(DatagramPacket(payload, payload.size, target, port))
        }
        override fun close() { socket.close() }
    }
}
private fun WifiNetworkHandle.androidNetwork(): Network =
    (this as? AndroidWifiHandle)?.network ?: throw IOException("Invalid Wi-Fi handle")
