package cz.netkit.shared.wol

import kotlinx.serialization.Serializable

@Serializable
enum class TargetMode { AUTO_BROADCAST, LIMITED_BROADCAST, CUSTOM }

@Serializable
data class WolDevice(
    val id: String,
    val name: String,
    val mac: String,
    val targetMode: TargetMode,
    val customTarget: String? = null,
    val port: Int = 9,
    val probeHost: String? = null,
    val probePort: Int? = null,
    val note: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val repeatCount: Int = 3,
    val repeatIntervalMs: Int = 250,
    val probeEnabled: Boolean = false,
    val probeDelaySeconds: Int = 20,
)

interface WifiNetworkHandle

data class WifiNetworkInfo(val handle: WifiNetworkHandle, val ipv4: String?, val prefixLength: Int?)
interface NetworkInfoProvider { suspend fun currentWifi(): WifiNetworkInfo? }
interface PacketSender {
    suspend fun sendUdp(network: WifiNetworkHandle, host: String, port: Int, payload: ByteArray)
}
enum class WakeProbeStatus { WAITING, RESPONDS, ALREADY_RESPONDED, NO_RESPONSE, UNAVAILABLE, CANCELLED }
interface WakeProber {
    suspend fun probe(network: WifiNetworkHandle, host: String, port: Int?): WakeProbeStatus
}
interface WolDeviceStore {
    val recoveryArchivePath: String? get() = null
    suspend fun load(): List<WolDevice>
    suspend fun save(devices: List<WolDevice>)
    suspend fun recover(): StoreRecovery = throw UnsupportedOperationException("Recovery unavailable")
}
enum class StoreFailure { IO, CORRUPT, UNSUPPORTED_SCHEMA }
class StoreReadException(val kind: StoreFailure, val schema: Int? = null, cause: Throwable? = null, val archivePath: String? = null) : Exception(kind.name, cause)
data class StoreRecovery(val archivePath: String?, val devices: List<WolDevice>)
enum class SendFailure { DNS, TIMEOUT, NETWORK_LOST, IO, CANCELLED, NO_WIFI, NO_BROADCAST }
class PacketSendException(val reason: SendFailure, cause: Throwable? = null) : Exception(reason.name, cause)
data class WolSendOutcome(val sent: Boolean, val reason: SendFailure?, val timestamp: Long)


data class WolDeviceDraft(
    val id: String? = null,
    val name: String = "",
    val mac: String = "",
    val targetMode: TargetMode = TargetMode.AUTO_BROADCAST,
    val customTarget: String = "",
    val port: String = "9",
    val probeHost: String = "",
    val probePort: String = "",
    val note: String = "",
    val repeatCount: String = "3",
    val repeatIntervalMs: String = "250",
    val probeEnabled: Boolean = false,
    val probeDelaySeconds: String = "20",
)

enum class DeviceField { NAME, MAC, CUSTOM_TARGET, PORT, PROBE_HOST, PROBE_PORT, NOTE, REPEAT_COUNT, REPEAT_INTERVAL, PROBE_DELAY }
data class DeviceValidation(val errors: Set<DeviceField>) { val isValid: Boolean get() = errors.isEmpty() }
enum class WolMessage { LOAD_FAILED, SAVE_FAILED, SEND_FAILED, NO_WIFI, NO_BROADCAST, SENT }
data class WolUiState(
    val devices: List<WolDevice> = emptyList(),
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val draft: WolDeviceDraft? = null,
    val errors: Set<DeviceField> = emptySet(),
    val saving: Boolean = false,
    val sendingDeviceId: String? = null,
    val probingDeviceId: String? = null,
    val message: WolMessage? = null,
    val loadFailure: StoreFailure? = null,
    val recoveryConfirmationPending: Boolean = false,
    val recovering: Boolean = false,
    val archivePath: String? = null,
    val outcomes: Map<String, WolSendOutcome> = emptyMap(),
    val probes: Map<String, WakeProbeStatus> = emptyMap(),
    val pendingImport: List<WolDevice>? = null,
    val skippedCount: Int = 0,
    val secureOnDropped: Int = 0,

)
