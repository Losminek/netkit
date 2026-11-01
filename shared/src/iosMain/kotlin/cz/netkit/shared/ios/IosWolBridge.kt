package cz.netkit.shared.ios

import cz.netkit.shared.wol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** Idempotent cancellation; Swift marshals cancellation onto its transport queue and closes the socket. */
interface IosCancellation { fun cancel() }

data class IosWifiSnapshot(val token: String, val ipv4: String?, val prefixLength: Int?)
data class IosWolLoadResult(val devices: List<WolDevice>, val failure: StoreFailure?)

/** Main-thread calls. A changed network token must fail with NETWORK_LOST, never fall back to cellular. */
interface IosWolPlatform {
    fun currentWifi(): IosWifiSnapshot?
    fun sendUdp(networkToken: String, host: String, port: Int, payload: ByteArray, completion: (SendFailure?) -> Unit): IosCancellation
    fun probe(networkToken: String, host: String, port: Int?, completion: (WakeProbeStatus) -> Unit): IosCancellation
}

/** Persistent Swift storage reports failure explicitly; it must never replace unreadable data by an empty list. */
interface IosWolPersistence {
    fun load(): IosWolLoadResult
    fun save(devices: List<WolDevice>): Boolean
}

private data class IosWifiHandle(val token: String) : WifiNetworkHandle

/** Owns a MainScope and the existing WolController, rather than reimplementing its send/state machine in Swift. */
class IosWolBridge internal constructor(
    platform: IosWolPlatform,
    persistence: IosWolPersistence,
    nowMillis: () -> Long,
    newId: () -> String,
    private val scope: CoroutineScope,
) {
    constructor(platform: IosWolPlatform, persistence: IosWolPersistence, nowMillis: () -> Long, newId: () -> String) :
        this(platform, persistence, nowMillis, newId, MainScope())

    val controller = WolController(
        store = object : WolDeviceStore {
            override suspend fun load(): List<WolDevice> {
                val result = persistence.load()
                result.failure?.let { throw StoreReadException(it) }
                return result.devices
            }
            override suspend fun save(devices: List<WolDevice>) {
                if (!persistence.save(devices)) throw StoreReadException(StoreFailure.IO)
            }
        },
        networkInfo = object : NetworkInfoProvider {
            override suspend fun currentWifi(): WifiNetworkInfo? = platform.currentWifi()?.takeIf { it.token.isNotBlank() }?.let {
                WifiNetworkInfo(IosWifiHandle(it.token), it.ipv4, it.prefixLength)
            }
        },
        sender = object : PacketSender {
            override suspend fun sendUdp(network: WifiNetworkHandle, host: String, port: Int, payload: ByteArray) {
                send(platform, (network as IosWifiHandle).token, host, port, payload)
            }
        },
        prober = object : WakeProber {
            override suspend fun probe(network: WifiNetworkHandle, host: String, port: Int?): WakeProbeStatus =
                probe(platform, (network as IosWifiHandle).token, host, port)
        },
        scope = scope, nowMillis = nowMillis, newId = newId,
    )

    fun currentState(): WolUiState = controller.state.value

    fun observe(onState: (WolUiState) -> Unit): IosCancellation {
        val job = scope.launch { controller.state.collect { onState(it) } }
        return object : IosCancellation { override fun cancel() { job.cancel() } }
    }

    fun draft(id: String?, name: String, mac: String, targetMode: TargetMode, customTarget: String, port: Int): WolDeviceDraft =
        WolDeviceDraft(id = id, name = name, mac = mac, targetMode = targetMode, customTarget = customTarget, port = port.toString())

    fun saveDraft(draft: WolDeviceDraft): Boolean {
        if (draft.id == null) controller.add() else controller.edit(requireNotNull(draft.id))
        controller.updateDraft(draft)
        if (controller.state.value.draft != draft || !validateDevice(draft).isValid) return false
        controller.save()
        return true
    }

    fun close() { controller.cancelWake(); scope.cancel() }
}

@OptIn(InternalCoroutinesApi::class)
private suspend fun send(platform: IosWolPlatform, token: String, host: String, port: Int, payload: ByteArray) =
    suspendCancellableCoroutine<Unit> { continuation ->
        var operation: IosCancellation? = null
        continuation.invokeOnCancellation { operation?.cancel() }
        if (!continuation.isActive) return@suspendCancellableCoroutine
        operation = platform.sendUdp(token, host, port, payload) { failure ->
            val resumed = if (failure == null) continuation.tryResume(Unit)
                else continuation.tryResumeWithException(PacketSendException(failure))
            if (resumed != null) continuation.completeResume(resumed)
        }
        // Also covers cancellation or synchronous completion before sendUdp returned its handle.
        if (!continuation.isActive) operation.cancel()
    }

@OptIn(InternalCoroutinesApi::class)
private suspend fun probe(platform: IosWolPlatform, token: String, host: String, port: Int?): WakeProbeStatus =
    suspendCancellableCoroutine { continuation ->
        var operation: IosCancellation? = null
        continuation.invokeOnCancellation { operation?.cancel() }
        if (!continuation.isActive) return@suspendCancellableCoroutine
        operation = platform.probe(token, host, port) { result ->
            val resumed = continuation.tryResume(result)
            if (resumed != null) continuation.completeResume(resumed)
        }
        if (!continuation.isActive) operation.cancel()
    }
