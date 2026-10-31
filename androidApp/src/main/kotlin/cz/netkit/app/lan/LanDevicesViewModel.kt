package cz.netkit.app.lan

import android.app.Application
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.netkit.app.R
import cz.netkit.shared.lan.DiscoveryConsent
import cz.netkit.shared.lan.Ipv4Subnet
import cz.netkit.shared.lan.LanAliveMethod
import cz.netkit.shared.lan.LanDevice
import cz.netkit.shared.lan.LanDeviceName
import cz.netkit.shared.lan.LanDeviceObservation
import cz.netkit.shared.lan.LanNameSource
import cz.netkit.shared.lan.LanNetworkIdentity
import cz.netkit.shared.lan.LanRunStatus
import cz.netkit.shared.lan.MDNS_MAX_HOSTS
import cz.netkit.shared.lan.MdnsFindings
import cz.netkit.shared.lan.OpenPort
import cz.netkit.shared.lan.PingReply
import cz.netkit.shared.lan.PingStatistics
import cz.netkit.shared.lan.ProbeCounts
import cz.netkit.shared.lan.ProbeStatus
import cz.netkit.shared.lan.commonPorts
import cz.netkit.shared.lan.finishLanDiscovery
import cz.netkit.shared.lan.lanConsentIsPersistable
import cz.netkit.shared.lan.lanDeviceName
import cz.netkit.shared.lan.lanDevicesRange
import cz.netkit.shared.lan.lanUpnpInfo
import cz.netkit.shared.lan.pingStatistics
import cz.netkit.shared.lan.probeBounded
import cz.netkit.shared.lan.probeDiscoveryFallback
import cz.netkit.shared.lan.recordLanDeviceReply
import cz.netkit.shared.lan.sanitizeLanText
import cz.netkit.shared.lan.shouldAutoDiscover
import cz.netkit.shared.lan.targetConfirmationReasons
import cz.netkit.shared.lan.updateLanDeviceNames
import cz.netkit.shared.lan.updateLanDeviceServices
import cz.netkit.shared.lan.updateLanDeviceUpnp
import cz.netkit.shared.lan.upnpDescriptionTarget
import cz.netkit.shared.lan.upsertLanDevice
import cz.netkit.shared.lan.withOpenPorts
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Typed errors; the UI shows [message]. Never carries exception text. */
enum class LanDevicesError(@param:StringRes @get:StringRes val message: Int) {
    NO_WIFI(R.string.lan_devices_error_no_wifi),
    NETWORK_CHANGED(R.string.lan_devices_error_network_changed),
    RANGE_NOT_ALLOWED(R.string.lan_devices_error_range),
    UNKNOWN_DEVICE(R.string.lan_devices_error_unknown_device),
    ICMP_UNAVAILABLE(R.string.lan_devices_error_icmp),
    CONSENT_STORAGE(R.string.lan_devices_error_storage),
    FAILED(R.string.lan_devices_error_failed),
}

/**
 * The current Wi-Fi as the Devices screen sees it.
 * @property range CIDR that discovery probes (own subnet narrowed to at most /24), or null when
 *   discovery is not allowed here (e.g. non-private address); then only [LanDevicesError.RANGE_NOT_ALLOWED].
 * @property ssid sanitized SSID for the consent question only; null when Android redacts it
 *   (no location permission). Never logged or stored.
 */
data class LanDevicesNetwork(val address: String, val prefix: Int, val range: String?, val gateway: String?, val ssid: String?)

/** Discovery progress; [automatic] distinguishes consent-driven runs from [LanDevicesViewModel.refresh]. */
data class LanDeviceScan(
    val status: LanRunStatus = LanRunStatus.IDLE, val automatic: Boolean = false,
    val done: Int = 0, val total: Int = 0, val lastCompletedElapsedMs: Long? = null,
) { val running: Boolean get() = status == LanRunStatus.RUNNING }

/** Live ping in the device detail: [target] samples, one per second; null sample = timeout. */
data class LanDevicePing(val address: String, val status: LanRunStatus, val samples: List<Double?> = emptyList(), val target: Int = DEVICE_PING_SAMPLES) {
    /** Loss % and min/avg/max over the samples so far. */
    val statistics: PingStatistics get() = pingStatistics(samples)
}

/** Quick TCP scan of the preset ports; open ports go into [LanDevice.openPorts]. */
data class LanDevicePortScan(val address: String, val status: LanRunStatus, val done: Int = 0, val total: Int = 0, val counts: ProbeCounts = ProbeCounts())

/**
 * Whole state of the Devices screen (single source of truth, survives rotation).
 *
 * - [loadingNetwork]: Wi-Fi identity (gateway, SSID, consent) is being read; actions are ignored.
 * - [network] null and not loading: no usable Wi-Fi ([error] = NO_WIFI).
 * - [consent]: UNKNOWN → show "Je to vaše síť? Automaticky vyhledávat zařízení" with
 *   [LanDevicesViewModel.allowAutoDiscovery] / [LanDevicesViewModel.denyAutoDiscovery]
 *   ([askConsent] is the ready-made condition). The manual refresh button is always available.
 * - [consentRemembered]: false while ALLOWED means it lasts only until the ViewModel is cleared
 *   (SSID unknown or storage failed); the UI may explain why (string `lan_devices_consent_session`).
 * - [devices]: sorted gateway first then by IPv4; includes the phone itself (`isSelf`); stale
 *   devices (not seen in the last completed scan) are kept for 30 minutes.
 * - [selectedAddress]/[selectedDevice], [ping]: device detail; [portScan]: latest quick port scan.
 * - [icmpUnavailable]: discovery fell back to TCP only (ICMP socket refused by OS/VPN).
 * - [identification]: mDNS + SSDP/UPnP pass that follows every *completed* discovery
 *   (≈3–5 s); results arrive into [devices] (`names` MDNS, `mdnsServices`, `upnp`).
 *   [identificationHosts] are the addresses queried by that pass: only for them does a
 *   FINISHED status mean "measured"; a new discovery resets both to IDLE / empty.
 */
data class LanDevicesState(
    val active: Boolean = false,
    val loadingNetwork: Boolean = true,
    val network: LanDevicesNetwork? = null,
    val consent: DiscoveryConsent = DiscoveryConsent.UNKNOWN,
    val consentRemembered: Boolean = false,
    val scan: LanDeviceScan = LanDeviceScan(),
    val devices: List<LanDevice> = emptyList(),
    val selectedAddress: String? = null,
    val ping: LanDevicePing? = null,
    val portScan: LanDevicePortScan? = null,
    val icmpUnavailable: Boolean = false,
    val identification: LanRunStatus = LanRunStatus.IDLE,
    val identificationHosts: Set<String> = emptySet(),
    val error: LanDevicesError? = null,
) {
    val selectedDevice: LanDevice? get() = selectedAddress?.let { address -> devices.firstOrNull { it.address == address } }
    val askConsent: Boolean get() = network?.range != null && consent == DiscoveryConsent.UNKNOWN
}

const val DEVICE_PING_SAMPLES = 10
private const val DISCOVERY_PARALLEL = 32
private const val DISCOVERY_TIMEOUT_MS = 1_000
private const val FALLBACK_TCP_TIMEOUT_MS = 400
private const val NETBIOS_PARALLEL = 8
private const val PORT_PARALLEL = 16
private const val PORT_TIMEOUT_MS = 1_000
private const val DEVICE_PING_TIMEOUT_MS = 1_000
private const val DEVICE_PING_INTERVAL_MS = 1_000L
private const val UPNP_FETCH_PARALLEL = 8

/**
 * Proactive device list (WiFiman "Discovery" style). Everything runs only on the current
 * non-VPN Wi-Fi via [LanBackend]; nothing is probed outside [LanDevicesNetwork.range].
 *
 * API: [setActive] (screen visible), [allowAutoDiscovery], [denyAutoDiscovery],
 * [resetAutoDiscoveryConsent], [refresh] (manual, ignores consent and rate limit), [openDevice]
 * (starts 10× ping; reopening restarts it), [closeDevice] (stops ping), [scanPorts],
 * [cancel] (all work), [cancelIdentification], [dismissError]. `setActive(false)`, a Wi-Fi change and [onCleared]
 * cancel all running work.
 *
 * Identification (mDNS names/services, SSDP + UPnP description) starts only after a completed
 * discovery in the same range — automatic runs therefore only with consent ALLOWED, manual
 * runs on the user's explicit refresh — and only for live hosts of that run. It is cancelled
 * with the discovery, by [cancel], `setActive(false)`, a Wi-Fi change and, for automatic runs,
 * by withdrawing consent. All sockets are bound to the Wi-Fi `Network`; never mobile data.
 */
class LanDevicesViewModel(application: Application) : AndroidViewModel(application) {
    private val backend = LanBackend(application)
    private val reader = LanDeviceNetworkReader(application)
    private val store = LanDeviceConsentStore(application)
    /** Serializes consent writes in call order. */
    private val storage = Dispatchers.IO.limitedParallelism(1)
    private val mutable = MutableStateFlow(LanDevicesState())
    val state: StateFlow<LanDevicesState> = mutable.asStateFlow()

    /** Main-thread only. A runId increment invalidates every pending publication of the old job. */
    private class Slot { var job: Job? = null; var runId = 0 }
    private val discovery = Slot()
    private val ping = Slot()
    private val ports = Slot()
    private val identify = Slot()
    /** Whether the running identification belongs to an automatic (consent-driven) discovery. */
    private var identifyAutomatic = false

    private data class Current(val wifi: LanWifi, val identity: LanNetworkIdentity, val range: Ipv4Subnet?, val key: String?)
    private var current: Current? = null

    init {
        viewModelScope.launch {
            try { backend.observeWifi().collect { wifi -> onWifi(wifi) } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { onWifi(null) }
        }
    }

    private suspend fun onWifi(wifi: LanWifi?) {
        if (wifi != null && wifi == current?.wifi) return
        cancelAll()
        current = null
        if (wifi == null) {
            mutable.update { LanDevicesState(active = it.active, loadingNetwork = false, error = LanDevicesError.NO_WIFI) }
            return
        }
        mutable.update { LanDevicesState(active = it.active, loadingNetwork = true) }
        val loaded = withContext(Dispatchers.IO) { load(wifi) }
        current = loaded.first
        val c = loaded.first
        mutable.update {
            it.copy(loadingNetwork = false,
                network = LanDevicesNetwork(wifi.address, wifi.prefix, c.range?.toString(), c.identity.gateway,
                    c.identity.ssid?.let { ssid -> sanitizeLanText(ssid, 64) }),
                consent = loaded.second, consentRemembered = loaded.second != DiscoveryConsent.UNKNOWN && c.key != null &&
                    lanConsentIsPersistable(loaded.second, c.identity),
                scan = LanDeviceScan(lastCompletedElapsedMs = loaded.third),
                error = when { c.range == null -> LanDevicesError.RANGE_NOT_ALLOWED; c.key == null -> LanDevicesError.CONSENT_STORAGE; else -> null })
        }
        maybeAutoDiscover()
    }

    private suspend fun load(wifi: LanWifi): Triple<Current, DiscoveryConsent, Long?> {
        val facts = try { reader.read(wifi) } catch (error: CancellationException) { throw error } catch (_: Exception) { LanDeviceNetworkFacts(null, null) }
        val identity = LanNetworkIdentity(facts.ssid, facts.gateway, wifi.subnet)
        val key = try { withContext(storage) { store.key(identity) } } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
        val consent = key?.let { runCatching { store.consent(it, wifi.network.networkHandle) }.getOrNull() } ?: DiscoveryConsent.UNKNOWN
        val last = key?.let { runCatching { store.lastDiscovery(it) }.getOrNull() }
        return Triple(Current(wifi, identity, lanDevicesRange(wifi.address, wifi.prefix), key), consent, last)
    }

    fun setActive(active: Boolean) {
        if (state.value.active == active) return
        mutable.update { it.copy(active = active) }
        if (active) maybeAutoDiscover() else cancelAll()
    }

    fun allowAutoDiscovery() = setConsent(DiscoveryConsent.ALLOWED)
    fun denyAutoDiscovery() = setConsent(DiscoveryConsent.DENIED)
    fun resetAutoDiscoveryConsent() = setConsent(DiscoveryConsent.UNKNOWN)

    private fun setConsent(consent: DiscoveryConsent) {
        val c = current ?: return
        val persistable = lanConsentIsPersistable(consent, c.identity)
        mutable.update { it.copy(consent = consent, consentRemembered = false) }
        if (consent != DiscoveryConsent.ALLOWED && state.value.scan.let { it.running && it.automatic }) cancelDiscovery()
        if (consent != DiscoveryConsent.ALLOWED && identifyAutomatic) cancelIdentification()
        viewModelScope.launch {
            val written = c.key?.let { key ->
                withContext(storage) { runCatching { store.setConsent(key, consent, persistable, c.wifi.network.networkHandle) }.getOrDefault(false) }
            } ?: false
            if (current !== c || state.value.consent != consent) return@launch
            mutable.update { it.copy(consentRemembered = written && persistable,
                error = if (!written) LanDevicesError.CONSENT_STORAGE else it.error.takeUnless { error -> error == LanDevicesError.CONSENT_STORAGE }) }
        }
        maybeAutoDiscover()
    }

    /** Manual discovery: allowed with any consent state, still only inside [LanDevicesNetwork.range]. Ignored while running. */
    fun refresh() {
        val snapshot = state.value
        if (snapshot.loadingNetwork || snapshot.scan.running) return
        val c = current ?: return fail(LanDevicesError.NO_WIFI)
        if (c.range == null) return fail(LanDevicesError.RANGE_NOT_ALLOWED)
        startDiscovery(c, c.range, automatic = false)
    }

    private var autoStart: Pair<String?, Long>? = null

    private fun maybeAutoDiscover() {
        val c = current ?: return
        val range = c.range ?: return
        val snapshot = state.value
        if (!snapshot.active || snapshot.scan.running) return
        // The 5-minute limit counts automatic starts too, so tab switching cannot restart /24 sweeps.
        val lastStart = autoStart?.takeIf { it.first == c.key }?.second
        val reference = listOfNotNull(snapshot.scan.lastCompletedElapsedMs, lastStart).maxOrNull()
        if (!shouldAutoDiscover(snapshot.consent, reference, now())) return
        autoStart = c.key to now()
        startDiscovery(c, range, automatic = true)
    }

    private fun startDiscovery(c: Current, range: Ipv4Subnet, automatic: Boolean) {
        cancelDiscovery()
        cancelIdentification()
        val runId = discovery.runId
        val wifi = c.wifi
        val gateway = c.identity.gateway
        // lanDevicesRange guarantees at most /24; the explicit maximum keeps that invariant local.
        val hosts = range.hosts(maximum = 254).filterNot { it == wifi.address }
        val started = now()
        mutable.update {
            it.copy(scan = it.scan.copy(status = LanRunStatus.RUNNING, automatic = automatic, done = 0, total = hosts.size),
                error = null, icmpUnavailable = false, identification = LanRunStatus.IDLE, identificationHosts = emptySet(),
                devices = upsertLanDevice(it.devices, LanDeviceObservation(wifi.address, LanAliveMethod.SELF), started, gateway, wifi.address))
        }
        discovery.job = viewModelScope.launch(Dispatchers.IO) {
            var outcome = LanRunStatus.CANCELLED
            try {
                backend.checkWifi(wifi)
                val icmpUnavailable = AtomicBoolean(false)
                val names = Semaphore(NETBIOS_PARALLEL)
                probeBounded(hosts, DISCOVERY_PARALLEL) { ip ->
                    backend.checkWifi(wifi)
                    val alive = probeAlive(wifi, ip, icmpUnavailable, runId)
                    if (alive != null) {
                        publish(discovery, runId) { it.copy(devices = upsertLanDevice(it.devices, alive, now(), gateway, wifi.address)) }
                        val (found, group) = resolveNames(wifi, ip, names)
                        if (found.isNotEmpty() || group != null) publish(discovery, runId) { it.copy(devices = updateLanDeviceNames(it.devices, ip, found, group)) }
                    }
                    publish(discovery, runId) { it.copy(scan = it.scan.copy(done = it.scan.done + 1)) }
                }
                val finished = now()
                publish(discovery, runId) {
                    it.copy(devices = finishLanDiscovery(it.devices, started, finished),
                        scan = it.scan.copy(status = LanRunStatus.FINISHED, lastCompletedElapsedMs = finished))
                }
                outcome = LanRunStatus.FINISHED
                withContext(Dispatchers.Main.immediate) { if (discovery.runId == runId) startIdentification(c, automatic) }
                c.key?.let { key -> withContext(storage) { runCatching { store.markDiscovery(key, finished) } } }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                outcome = LanRunStatus.FAILED
                currentCoroutineContext().ensureActive()
                publish(discovery, runId) { it.copy(error = classify(error)) }
            } finally {
                finish(discovery, runId) { if (it.scan.running) it.copy(scan = it.scan.copy(status = outcome)) else it }
            }
        }
    }

    /** Same liveness rules as the LAN tool: ICMP, else TCP 80/443/22/445 (OPEN or RST). */
    private suspend fun probeAlive(wifi: LanWifi, ip: String, icmpUnavailable: AtomicBoolean, runId: Int): LanDeviceObservation? {
        if (!icmpUnavailable.get()) {
            val reply = try { backend.ping(wifi, ip, DISCOVERY_TIMEOUT_MS) }
                catch (_: IcmpUnavailableException) {
                    if (icmpUnavailable.compareAndSet(false, true)) publish(discovery, runId) { it.copy(icmpUnavailable = true) }
                    PingReply(null)
                }
                catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); PingReply(null) }
            reply.milliseconds?.let { return LanDeviceObservation(ip, LanAliveMethod.ICMP, it) }
        }
        val probes = probeDiscoveryFallback { port -> backend.tcpProbe(wifi, ip, port, FALLBACK_TCP_TIMEOUT_MS) }
        probes.firstOrNull { it.status == ProbeStatus.OPEN }?.let { return LanDeviceObservation(ip, LanAliveMethod.TCP, it.result?.milliseconds) }
        return if (probes.any { it.status == ProbeStatus.REFUSED }) LanDeviceObservation(ip, LanAliveMethod.TCP_RST) else null
    }

    /** PTR (existing backend, 1.5 s) in parallel with NetBIOS (≤ 800 ms, at most [NETBIOS_PARALLEL] at once). */
    private suspend fun resolveNames(wifi: LanWifi, ip: String, limiter: Semaphore): Pair<List<LanDeviceName>, String?> = coroutineScope {
        val ptr = async {
            val name = try { backend.reverse(wifi, ip) }
                catch (error: CancellationException) { currentCoroutineContext().ensureActive(); "" }
                catch (_: Exception) { "" }
            lanDeviceName(LanNameSource.PTR, name)
        }
        val netBios = limiter.withPermit {
            try { NetBios.query(wifi, ip) }
            catch (error: CancellationException) { currentCoroutineContext().ensureActive(); null }
            catch (_: Exception) { null }
        }
        listOfNotNull(lanDeviceName(LanNameSource.NETBIOS, netBios?.name), ptr.await()) to netBios?.group
    }

    /**
     * mDNS (≈2.5 s) in parallel with SSDP (≈3 s) + at most [UPNP_FETCH_PARALLEL] description
     * fetches of 2 s each. Hosts = live, non-stale devices of the current range except the phone.
     */
    private fun startIdentification(c: Current, automatic: Boolean) {
        cancelIdentification()
        val range = c.range ?: return
        val snapshot = state.value
        if (current !== c || !snapshot.active || (automatic && snapshot.consent != DiscoveryConsent.ALLOWED)) return
        val hosts = snapshot.devices.filter { !it.isSelf && !it.stale && range.contains(it.address) }.map { it.address }.take(MDNS_MAX_HOSTS)
        if (hosts.isEmpty()) return
        val runId = identify.runId
        identifyAutomatic = automatic
        mutable.update { it.copy(identification = LanRunStatus.RUNNING, identificationHosts = hosts.toSet()) }
        identify.job = viewModelScope.launch(Dispatchers.IO) {
            var outcome = LanRunStatus.CANCELLED
            try {
                backend.checkWifi(c.wifi)
                coroutineScope {
                    launch {
                        val found = MdnsProbe.identify(c.wifi, range, hosts)
                        publish(identify, runId) { it.copy(devices = withMdns(it.devices, found)) }
                    }
                    launch { identifyUpnp(c.wifi, range, hosts.toSet(), runId) }
                }
                outcome = LanRunStatus.FINISHED
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                outcome = LanRunStatus.FAILED
                currentCoroutineContext().ensureActive()
                // Identification is best effort; only a lost Wi-Fi is worth an error banner.
                if (classify(error) == LanDevicesError.NETWORK_CHANGED) publish(identify, runId) { it.copy(error = LanDevicesError.NETWORK_CHANGED) }
            } finally {
                finish(identify, runId) { if (it.identification == LanRunStatus.RUNNING) it.copy(identification = outcome) else it }
            }
        }
    }

    private fun withMdns(devices: List<LanDevice>, found: MdnsFindings): List<LanDevice> {
        var updated = devices
        found.names.forEach { (address, name) ->
            lanDeviceName(LanNameSource.MDNS, name.name)?.let { updated = updateLanDeviceNames(updated, address, listOf(it)) }
        }
        found.services.forEach { (address, types) -> updated = updateLanDeviceServices(updated, address, types) }
        return updated
    }

    /** Description only for a LOCATION accepted by [upnpDescriptionTarget]; otherwise just the SSDP reply. */
    private suspend fun identifyUpnp(wifi: LanWifi, range: Ipv4Subnet, hosts: Set<String>, runId: Int) {
        val replies = UpnpProbe.search(wifi, range, hosts).toList()
        probeBounded(replies, UPNP_FETCH_PARALLEL) { (address, reply) ->
            val target = reply.location?.let { upnpDescriptionTarget(it, address, range) }
            val description = target?.let { backend.checkWifi(wifi); UpnpProbe.describe(wifi, it) }
            val info = lanUpnpInfo(reply, description, target, range)
            publish(identify, runId) { it.copy(devices = updateLanDeviceUpnp(it.devices, address, info)) }
        }
    }

    fun openDevice(address: String) {
        val c = current ?: return fail(LanDevicesError.NO_WIFI)
        val device = state.value.devices.firstOrNull { it.address == address }
        if (device == null || c.range?.contains(address) != true) return fail(LanDevicesError.UNKNOWN_DEVICE)
        cancelPing()
        if (device.isSelf) { mutable.update { it.copy(selectedAddress = address, ping = null) }; return }
        val runId = ping.runId
        mutable.update { it.copy(selectedAddress = address, ping = LanDevicePing(address, LanRunStatus.RUNNING)) }
        ping.job = viewModelScope.launch(Dispatchers.IO) {
            var outcome = LanRunStatus.CANCELLED
            try {
                repeat(DEVICE_PING_SAMPLES) { index ->
                    val started = now()
                    backend.checkWifi(c.wifi)
                    val reply = try { backend.ping(c.wifi, address, DEVICE_PING_TIMEOUT_MS) }
                        catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); PingReply(null) }
                    val at = now()
                    publish(ping, runId) { s ->
                        s.copy(ping = s.ping?.copy(samples = s.ping.samples + reply.milliseconds),
                            devices = reply.milliseconds?.let { rtt -> recordLanDeviceReply(s.devices, address, rtt, at) } ?: s.devices)
                    }
                    if (index < DEVICE_PING_SAMPLES - 1) delay((DEVICE_PING_INTERVAL_MS - (now() - started)).coerceAtLeast(0))
                }
                outcome = LanRunStatus.FINISHED
            } catch (error: CancellationException) { throw error
            } catch (_: IcmpUnavailableException) {
                outcome = LanRunStatus.FAILED
                publish(ping, runId) { it.copy(icmpUnavailable = true, error = LanDevicesError.ICMP_UNAVAILABLE) }
            } catch (error: Exception) {
                outcome = LanRunStatus.FAILED
                currentCoroutineContext().ensureActive()
                publish(ping, runId) { it.copy(error = classify(error)) }
            } finally {
                finish(ping, runId) { s -> s.ping?.takeIf { it.status == LanRunStatus.RUNNING }?.let { s.copy(ping = it.copy(status = outcome)) } ?: s }
            }
        }
    }

    fun closeDevice() {
        cancelPing()
        mutable.update { it.copy(selectedAddress = null, ping = null) }
    }

    /** Preset ports ([commonPorts]) on one listed device; replaces its previous port list. */
    fun scanPorts(address: String) {
        val c = current ?: return fail(LanDevicesError.NO_WIFI)
        val device = state.value.devices.firstOrNull { it.address == address }
        if (device == null || c.range?.contains(address) != true) return fail(LanDevicesError.UNKNOWN_DEVICE)
        val target = Ipv4Subnet.parse("$address/32") ?: return fail(LanDevicesError.UNKNOWN_DEVICE)
        if (targetConfirmationReasons(target, c.wifi.subnet, commonPorts.size).isNotEmpty()) return fail(LanDevicesError.RANGE_NOT_ALLOWED)
        cancelPorts()
        val runId = ports.runId
        val portList = commonPorts
        mutable.update {
            it.copy(portScan = LanDevicePortScan(address, LanRunStatus.RUNNING, total = portList.size),
                devices = withOpenPorts(it.devices, address, emptyList(), null), error = null)
        }
        ports.job = viewModelScope.launch(Dispatchers.IO) {
            var outcome = LanRunStatus.CANCELLED
            try {
                probeBounded(portList, PORT_PARALLEL) { port ->
                    backend.checkWifi(c.wifi)
                    val probe = backend.tcpProbe(c.wifi, address, port, PORT_TIMEOUT_MS)
                    publish(ports, runId) { s ->
                        val devices = if (probe.status == ProbeStatus.OPEN) withOpenPorts(s.devices, address, portsOf(s, address) + OpenPort(port), null) else s.devices
                        s.copy(devices = devices, portScan = s.portScan?.copy(done = s.portScan.done + 1, counts = s.portScan.counts.add(probe.status)))
                    }
                }
                val finished = now()
                publish(ports, runId) { s ->
                    s.copy(devices = withOpenPorts(s.devices, address, portsOf(s, address), finished),
                        portScan = s.portScan?.copy(status = LanRunStatus.FINISHED))
                }
                outcome = LanRunStatus.FINISHED
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                outcome = LanRunStatus.FAILED
                currentCoroutineContext().ensureActive()
                publish(ports, runId) { it.copy(error = classify(error)) }
            } finally {
                finish(ports, runId) { s -> s.portScan?.takeIf { it.status == LanRunStatus.RUNNING }?.let { s.copy(portScan = it.copy(status = outcome)) } ?: s }
            }
        }
    }

    /** Cancels discovery, ping and port scan; results gathered so far stay visible. */
    fun cancel() = cancelAll()

    /** Stops only the port scan of the open device; its ping and any discovery keep running. */
    fun cancelPortScan() = cancelPorts()

    fun dismissError() { mutable.update { it.copy(error = null) } }

    override fun onCleared() { cancelAll(); super.onCleared() }

    private fun portsOf(state: LanDevicesState, address: String): List<OpenPort> =
        state.devices.firstOrNull { it.address == address }?.openPorts.orEmpty()

    private fun cancelAll() { cancelDiscovery(); cancelIdentification(); cancelPing(); cancelPorts() }

    private fun cancelDiscovery() {
        stop(discovery)
        mutable.update { if (it.scan.running) it.copy(scan = it.scan.copy(status = LanRunStatus.CANCELLED)) else it }
    }
    /** Stops only the mDNS/SSDP identification; a port scan or ping in the detail keeps running. */
    fun cancelIdentification() {
        stop(identify)
        identifyAutomatic = false
        mutable.update { if (it.identification == LanRunStatus.RUNNING) it.copy(identification = LanRunStatus.CANCELLED) else it }
    }
    private fun cancelPing() {
        stop(ping)
        mutable.update { s -> s.ping?.takeIf { it.status == LanRunStatus.RUNNING }?.let { s.copy(ping = it.copy(status = LanRunStatus.CANCELLED)) } ?: s }
    }
    private fun cancelPorts() {
        stop(ports)
        mutable.update { s -> s.portScan?.takeIf { it.status == LanRunStatus.RUNNING }?.let { s.copy(portScan = it.copy(status = LanRunStatus.CANCELLED)) } ?: s }
    }
    private fun stop(slot: Slot) { slot.runId++; slot.job?.cancel(); slot.job = null }

    private fun fail(error: LanDevicesError) { mutable.update { it.copy(error = error) } }

    /** WIFI_LOST is LanBackend's own sentinel; everything else is a generic failure. */
    private fun classify(error: Exception): LanDevicesError =
        if (error is IOException && error.message == "WIFI_LOST") LanDevicesError.NETWORK_CHANGED else LanDevicesError.FAILED

    private suspend fun publish(slot: Slot, runId: Int, change: (LanDevicesState) -> LanDevicesState) = withContext(Dispatchers.Main.immediate) {
        currentCoroutineContext().ensureActive()
        if (slot.runId == runId) mutable.update(change)
    }

    private suspend fun finish(slot: Slot, runId: Int, change: (LanDevicesState) -> LanDevicesState) = withContext(NonCancellable + Dispatchers.Main.immediate) {
        if (slot.runId == runId) { mutable.update(change); slot.job = null }
    }

    private fun now(): Long = SystemClock.elapsedRealtime()
}
