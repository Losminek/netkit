package cz.netkit.app.lan

import android.system.ErrnoException
import android.system.OsConstants
import cz.netkit.shared.lan.LanResult
import cz.netkit.shared.lan.ProbeStatus
import cz.netkit.shared.lan.TcpProbeResult
import cz.netkit.shared.lan.portService
import cz.netkit.shared.lan.sanitizeLanText
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** Message text is never evidence of refusal: it can be translated or unrelated to errno. */
internal fun classifyConnectFailure(
    error: IOException,
    readErrno: (Throwable) -> Int? = { (it as? ErrnoException)?.errno },
    refusedErrno: Int = OsConstants.ECONNREFUSED,
    timeoutErrno: Int = OsConstants.ETIMEDOUT,
): ProbeStatus {
    // Bounded traversal also handles malformed cyclic exception chains.
    val causes = generateSequence<Throwable>(error) { it.cause }.take(16).toList()
    if (causes.any { it is SocketTimeoutException }) return ProbeStatus.TIMEOUT
    val errno = causes.firstNotNullOfOrNull(readErrno)
    return when {
        // Zero is not a failure (and mockable android.jar may use zero constants).
        errno == null || errno <= 0 -> ProbeStatus.ERROR
        refusedErrno > 0 && errno == refusedErrno -> ProbeStatus.REFUSED
        timeoutErrno > 0 && errno == timeoutErrno -> ProbeStatus.TIMEOUT
        else -> ProbeStatus.ERROR
    }
}

/** Called on an IO worker. Once connect succeeds, every banner failure still means OPEN. */
internal fun probeTcpSocket(
    socket: Socket, address: InetAddress, port: Int, timeoutMs: Int, banner: Boolean,
    bind: (Socket) -> Unit = {}, onConnected: () -> Unit = {},
    classifyFailure: (IOException) -> ProbeStatus = { classifyConnectFailure(it) },
): TcpProbeResult {
    val start = System.nanoTime()
    try {
        bind(socket)
    } catch (_: IOException) {
        // A local bind failure says nothing about whether the peer is alive.
        return TcpProbeResult(ProbeStatus.ERROR)
    }
    try {
        socket.connect(InetSocketAddress(address, port), timeoutMs)
    } catch (error: IOException) {
        return TcpProbeResult(classifyFailure(error))
    }
    val elapsed = (System.nanoTime() - start) / 1_000_000.0
    onConnected()
    var text = ""
    if (banner) {
        try {
            socket.soTimeout = 500
            val bytes = ByteArray(256)
            val count = socket.getInputStream().read(bytes)
            if (count > 0) text = sanitizeLanText(bytes.copyOf(count).toString(Charsets.UTF_8))
        } catch (_: Exception) { /* Any banner failure: connect already proved OPEN. */ }
    }
    return TcpProbeResult(ProbeStatus.OPEN, LanResult(address.hostAddress.orEmpty(), method = "TCP", milliseconds = elapsed,
        port = port, detail = listOf(portService(port), text).filter(String::isNotEmpty).joinToString(" ")))
}
