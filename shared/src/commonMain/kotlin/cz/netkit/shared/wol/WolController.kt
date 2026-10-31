package cz.netkit.shared.wol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class WolController(
    private val store: WolDeviceStore,
    private val networkInfo: NetworkInfoProvider,
    private val sender: PacketSender,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val newId: () -> String,
    private val sendTimeoutMillis: Long = 5_000,
    private val prober: WakeProber? = null,
    private val monotonicMillis: () -> Long = wakeMonotonicClock(),
    private val nameComparator: Comparator<String> = naturalOrder(),
) {
    private val mutableState = MutableStateFlow(WolUiState())
    val state: StateFlow<WolUiState> = mutableState.asStateFlow()
    private val storageMutex = Mutex()
    private var loadJob: Job? = null
    private var wakeJob: Job? = null
    private var wakeGeneration = 0L
    private var probeJob: Job? = null
    private var probeGeneration = 0L
    private var probeDevice: WolDevice? = null

    init { retryLoad() }

    fun retryLoad() {
        if (loadJob?.isActive == true || (!state.value.loading && !state.value.loadFailed)) return
        mutableState.update { it.copy(loading = true, loadFailed = false, loadFailure = null, message = null) }
        loadJob = scope.launch {
            storageMutex.withLock {
                try {
                    val devices = store.load()
                    require(validateStoredDevices(devices))
                    mutableState.update { it.copy(devices = devices, loading = false, loadFailed = false, loadFailure = null, recoveryConfirmationPending = false, archivePath = store.recoveryArchivePath ?: it.archivePath) }
                } catch (e: CancellationException) { throw e
                } catch (error: Exception) {
                    mutableState.update { it.copy(loading = false, loadFailed = true, loadFailure = (error as? StoreReadException)?.kind ?: StoreFailure.IO, archivePath = (error as? StoreReadException)?.archivePath ?: it.archivePath, message = WolMessage.LOAD_FAILED) }
                }
            }
        }
    }

    fun requestRecovery() {
        if (state.value.loadFailed && state.value.loadFailure != StoreFailure.IO && !state.value.recovering)
            mutableState.update { it.copy(recoveryConfirmationPending = true) }
    }
    fun cancelRecovery() { mutableState.update { it.copy(recoveryConfirmationPending = false) } }
    fun confirmRecovery() {
        if (!state.value.recoveryConfirmationPending || state.value.recovering) return
        mutableState.update { it.copy(recoveryConfirmationPending = false, recovering = true) }
        scope.launch {
            try {
                storageMutex.withLock {
                    val result = store.recover()
                    mutableState.update { it.copy(devices = result.devices, loadFailed = false, loading = false,
                        loadFailure = null, message = null, archivePath = result.archivePath) }
                }
            } catch (e: CancellationException) { throw e
            } catch (error: Exception) { mutableState.update { it.copy(loadFailed = true,
                loadFailure = (error as? StoreReadException)?.kind ?: StoreFailure.IO,
                message = WolMessage.LOAD_FAILED, archivePath = (error as? StoreReadException)?.archivePath ?: it.archivePath) }
            }
        }.invokeOnCompletion { mutableState.update { it.copy(recovering = false) } }
    }
    private fun original(draft: WolDeviceDraft) = state.value.devices.find { it.id == draft.id }
    private fun canEdit() = !state.value.loading && !state.value.loadFailed && !state.value.saving && !state.value.recovering && state.value.sendingDeviceId == null
    fun add() { if (canEdit()) mutableState.update { it.copy(draft = WolDeviceDraft(), errors = emptySet()) } }
    fun edit(id: String) {
        if (!canEdit()) return
        val device = state.value.devices.find { it.id == id } ?: return
        mutableState.update { it.copy(draft = device.toDraft(), errors = emptySet()) }
    }
    fun updateDraft(draft: WolDeviceDraft) {
        if (canEdit() && state.value.draft != null && draft.id == state.value.draft?.id)
            mutableState.update { it.copy(draft = draft, errors = validateDevice(draft, original(draft)).errors) }
    }
    fun cancelEdit() { if (!state.value.saving) mutableState.update { it.copy(draft = null, errors = emptySet()) } }
    fun cancelDraft() = cancelEdit()
    fun clearMessage() { mutableState.update { it.copy(message = null) } }

    fun save() {
        if (!canEdit()) return
        val draft = state.value.draft ?: return
        val errors = validateDevice(draft, original(draft)).errors
        mutableState.update { it.copy(errors = errors) }
        if (errors.isNotEmpty()) return
        mutate {
            val old = draft.id?.let { id -> state.value.devices.find { it.id == id } }
            require(draft.id == null || old != null)
            val time = nowMillis()
            val id = old?.id ?: newId().also { value -> require(value.isNotBlank() && state.value.devices.none { it.id == value }) }
            val device = WolDevice(id, draft.name.trim(), parseMac(draft.mac)!!.toString(), draft.targetMode,
                draft.customTarget.trim().ifEmpty { null }, draft.port.toInt(), draft.probeHost.trim().ifEmpty { null },
                draft.probePort.ifEmpty { null }?.toInt(), draft.note.trim().ifEmpty { null }, old?.createdAt ?: time, time,
                draft.repeatCount.toInt(), draft.repeatIntervalMs.toInt(), draft.probeEnabled, draft.probeDelaySeconds.toInt())
            if (old == null) state.value.devices + device else state.value.devices.map { if (it.id == id) device else it }
        }
    }

    fun delete(id: String) {
        if (!canEdit() || state.value.devices.none { it.id == id }) return
        mutate { state.value.devices.filterNot { it.id == id } }
    }

    fun sortByName() { if (canEdit()) mutate { state.value.devices.sortedWith { first, second -> nameComparator.compare(first.name, second.name).takeIf { it != 0 } ?: first.id.compareTo(second.id) } } }
    fun move(id: String, delta: Int) {
        if (!canEdit() || delta !in listOf(-1, 1)) return
        val index = state.value.devices.indexOfFirst { it.id == id }
        val target = index + delta
        if (index < 0 || target !in state.value.devices.indices) return
        mutate { state.value.devices.toMutableList().apply { add(target, removeAt(index)) } }
    }
    fun previewImport(devices: List<WolDevice>, skippedCount: Int = 0, secureOnDropped: Int = 0): Boolean {
        require(skippedCount >= 0 && secureOnDropped >= 0)
        require(validateImportedDevices(devices))
        if (!canEdit() || state.value.draft != null) return false
        mutableState.update { it.copy(pendingImport = devices, skippedCount = skippedCount, secureOnDropped = secureOnDropped) }
        return true
    }
    fun cancelImport() { mutableState.update { it.copy(pendingImport = null, skippedCount = 0, secureOnDropped = 0) } }
    fun confirmImport() {
        if (!canEdit()) return
        val incoming = state.value.pendingImport ?: return
        mutate { mergeWolDevices(state.value.devices, incoming) }
    }

    private fun mutate(transform: () -> List<WolDevice>) {
        // Reserve immediately, before launch dispatches, so rapid UI events cannot race.
        mutableState.update { it.copy(saving = true, message = null) }
        scope.launch {
            try {
                storageMutex.withLock {
                    val updated = transform()
                    val previous = state.value.devices.associateBy { it.id }
                    val unchanged = updated.filter { previous[it.id] == it }.map { it.id }.toSet()
                    store.save(updated)
                    if (probeDevice?.id?.let { it !in unchanged } == true) cancelProbe(publishCancelled = false)
                    mutableState.update { it.copy(devices = updated, draft = null, errors = emptySet(), pendingImport = null, skippedCount = 0, secureOnDropped = 0,
                        outcomes = it.outcomes.filterKeys { id -> id in unchanged }, probes = it.probes.filterKeys { id -> id in unchanged }) }
                }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { mutableState.update { it.copy(message = WolMessage.SAVE_FAILED) }
            }
        }.invokeOnCompletion { mutableState.update { it.copy(saving = false) } }
    }

    fun wake(id: String) {
        if (state.value.loading || state.value.loadFailed || state.value.saving || state.value.recovering || state.value.sendingDeviceId != null) return
        val device = state.value.devices.find { it.id == id } ?: return
        cancelProbe()
        val generation = ++wakeGeneration
        mutableState.update { it.copy(sendingDeviceId = id, message = null, outcomes = it.outcomes - id, probes = it.probes - id) }
        wakeJob = scope.launch {
            try {
                var selectedNetwork: WifiNetworkInfo? = null
                var alreadyResponded = false
                val completed = withTimeoutOrNull(sendTimeoutMillis + (device.repeatCount - 1) * device.repeatIntervalMs.toLong() + (if (device.probeEnabled) WAKE_PRECHECK_TIMEOUT_MS else 0)) {
                    val network = networkInfo.currentWifi()
                    if (network == null) {
                        outcome(id, SendFailure.NO_WIFI)
                        return@withTimeoutOrNull
                    }
                    selectedNetwork = network
                    if (device.probeEnabled) {
                        alreadyResponded = wakeProbeAttempt(WAKE_PRECHECK_TIMEOUT_MS) {
                            prober?.probe(network.handle, requireNotNull(device.probeHost), device.probePort)
                                ?: WakeProbeStatus.UNAVAILABLE
                        } == WakeProbeStatus.RESPONDS
                    }
                    val host = when (device.targetMode) {
                        TargetMode.LIMITED_BROADCAST -> "255.255.255.255"
                        TargetMode.CUSTOM -> requireNotNull(device.customTarget)
                        TargetMode.AUTO_BROADCAST -> {
                            val ip = network.ipv4?.let(::parseIpv4)
                            val broadcast = if (ip != null) network.prefixLength?.let { directedBroadcast(ip, it) } else null
                            if (broadcast == null) {
                                outcome(id, SendFailure.NO_BROADCAST)
                                return@withTimeoutOrNull
                            }
                            broadcast.toString()
                        }
                    }
                    repeat(device.repeatCount) { attempt ->
                        if (attempt > 0) delay(device.repeatIntervalMs.toLong())
                        sender.sendUdp(network.handle, host, device.port, magicPacket(requireNotNull(parseMac(device.mac))))
                    }
                    outcome(id, null)
                }
                if (completed == null) outcome(id, SendFailure.TIMEOUT)
                if (state.value.outcomes[id]?.sent == true && device.probeEnabled) {
                    if (alreadyResponded) mutableState.update { it.copy(probes = it.probes + (id to WakeProbeStatus.ALREADY_RESPONDED)) }
                    else startProbe(device, requireNotNull(selectedNetwork).handle)
                }
            } catch (e: CancellationException) {
                if (state.value.outcomes[id]?.sent == true) mutableState.update { it.copy(probes = it.probes + (id to WakeProbeStatus.CANCELLED)) }
                else outcome(id, SendFailure.CANCELLED)
                throw e
            } catch (e: PacketSendException) { outcome(id, e.reason)
            } catch (_: Exception) { outcome(id, SendFailure.IO)
            }
        }.also { job -> job.invokeOnCompletion { cause ->
            if (cause is CancellationException && state.value.outcomes[id]?.sent != true && state.value.outcomes[id]?.reason != SendFailure.CANCELLED) outcome(id, SendFailure.CANCELLED)
            if (generation == wakeGeneration) mutableState.update { it.copy(sendingDeviceId = null) }
        } }
    }
    private fun outcome(id: String, reason: SendFailure?) {
        val result = WolSendOutcome(reason == null, reason, nowMillis())
        mutableState.update { it.copy(outcomes = it.outcomes + (id to result), message = when (reason) {
            null -> WolMessage.SENT
            SendFailure.NO_WIFI -> WolMessage.NO_WIFI
            SendFailure.NO_BROADCAST -> WolMessage.NO_BROADCAST
            SendFailure.CANCELLED -> null
            else -> WolMessage.SEND_FAILED
        }) }
    }
    private fun startProbe(device: WolDevice, network: WifiNetworkHandle) {
        val generation = ++probeGeneration
        probeDevice = device
        mutableState.update { it.copy(probingDeviceId = device.id, probes = it.probes + (device.id to WakeProbeStatus.WAITING)) }
        probeJob = scope.launch {
            val result = pollWakeResponse(device.probeDelaySeconds * 1000L, monotonicMillis) {
                prober?.probe(network, requireNotNull(device.probeHost), device.probePort) ?: WakeProbeStatus.UNAVAILABLE
            }
            if (generation == probeGeneration && state.value.devices.any { it == device }) {
                mutableState.update { it.copy(probes = it.probes + (device.id to result)) }
            }
        }.also { job -> job.invokeOnCompletion {
            if (generation == probeGeneration) {
                probeDevice = null
                mutableState.update { it.copy(probingDeviceId = null) }
            }
        } }
    }
    fun cancelProbe(publishCancelled: Boolean = true) {
        val snapshot = probeDevice
        ++probeGeneration
        probeDevice = null
        probeJob?.cancel()
        probeJob = null
        mutableState.update { state -> state.copy(probingDeviceId = null,
            probes = if (publishCancelled && snapshot != null && state.devices.any { it == snapshot })
                state.probes + (snapshot.id to WakeProbeStatus.CANCELLED) else state.probes) }
    }
    fun cancelWake() { wakeJob?.cancel(); cancelProbe() }
}
