package cz.netkit.app.wifi.live

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.TrafficStats
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.netkit.app.cellular.CellularMonitor
import cz.netkit.app.cellular.cellularPermissionNeeded
import cz.netkit.app.lan.IcmpUnavailableException
import cz.netkit.app.lan.WifiIcmp
import cz.netkit.app.net.ActiveNetworkInfo
import cz.netkit.app.net.ActiveNetworkMonitor
import cz.netkit.app.publicip.PublicIpRepository
import cz.netkit.shared.cellular.CellularState
import cz.netkit.shared.cellular.primaryDbm
import cz.netkit.shared.chart.ChartEvent
import cz.netkit.shared.net.NetKind
import cz.netkit.shared.overview.CellularEventLog
import cz.netkit.shared.overview.DnsResolverNote
import cz.netkit.shared.overview.dnsResolverNote
import cz.netkit.shared.overview.primarySim
import cz.netkit.shared.overview.publicIpNetworkKey
import cz.netkit.shared.publicip.PublicIpInfo
import cz.netkit.shared.wifi.ByteRateMeter
import cz.netkit.shared.wifi.LIVE_DEFAULT_DNS_NAME
import cz.netkit.shared.wifi.LIVE_DEFAULT_INTERNET_TARGET
import cz.netkit.shared.wifi.LIVE_SAMPLE_INTERVAL_MS
import cz.netkit.shared.wifi.LIVE_SERIES_DEFAULT_CAPACITY
import cz.netkit.shared.wifi.LiveEventLog
import cz.netkit.shared.wifi.LiveProbe
import cz.netkit.shared.wifi.LiveRingBuffer
import cz.netkit.shared.wifi.LiveSeries
import cz.netkit.shared.wifi.LiveTimeline
import cz.netkit.shared.wifi.RoamingDetector
import cz.netkit.shared.wifi.SignalQuality
import cz.netkit.shared.wifi.WifiBand
import cz.netkit.shared.wifi.WifiSecurity
import cz.netkit.shared.wifi.bandForFrequency
import cz.netkit.shared.wifi.channelForFrequency
import cz.netkit.shared.wifi.liveTickDelayMs
import cz.netkit.shared.wifi.signalQuality
import cz.netkit.shared.wifi.usableBssid
import cz.netkit.shared.wifi.validLiveDnsName
import cz.netkit.shared.wifi.validLiveInternetTarget
import cz.netkit.shared.wifi.validRssiDbm
import java.net.InetAddress
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** How long the first tick waits for the network callback before reporting NO_WIFI. */
private const val MONITOR_SETTLE_MS = 1_500L
/** Outer guard around one ICMP echo (the echo itself honours [LIVE_ECHO_TIMEOUT_MS]). */
private const val ECHO_GUARD_MS = LIVE_ECHO_TIMEOUT_MS + 500L
/**
 * A network change is looked up only after it settled this long: the SSID or the data SIM of the
 * cache key arrive a moment after the network, and a quick hand-over is one lookup, not two.
 */
private const val PUBLIC_IP_SETTLE_MS = 800L

/**
 * Live telemetry of the Overview screen ("Přehled", M2 Wi-Fi telemetry extended in M4 A3),
 * modelled after WiFiman's Signal / Throughput / Latency tabs. Bind UI to [state]; see
 * [WifiLiveState] for every field.
 *
 * Lifecycle contract for the UI:
 * - Call `setActive(true)` while the screen is visible AND the app is in the foreground
 *   (e.g. from a `LifecycleResumeEffect`/`DisposableEffect` on `ON_START`/`ON_RESUME`), and
 *   `setActive(false)` on `ON_STOP` or when leaving the screen. `false` cancels all loops
 *   immediately (in-flight ICMP/DNS are cancelled); nothing runs in background, there is no
 *   foreground service and no manifest registration.
 * - Data is kept while inactive; `clear()` empties all series, roaming and chart events.
 *   History: 15 min (900 samples) per 1 Hz series, memory only.
 *
 * What is sampled while active (all on `Dispatchers.IO` for blocking calls, all children of
 * [viewModelScope]):
 * - 1 Hz connected-link reading (RSSI, RX/TX link rate, frequency, SSID/BSSID) from one
 *   long-lived `NetworkCallback` (no `startScan`, no scan throttling);
 * - 1 Hz device-wide `TrafficStats` byte counters → Mb/s;
 * - 1 Hz ICMP echo to the Wi-Fi IPv4 default gateway (bound to the Wi-Fi `Network`) and to the
 *   internet target (default `1.1.1.1`, bound to the active network: Wi-Fi, mobile data, VPN),
 *   timeout 1 s, ≤ 1 in flight per target;
 * - every 10 s a DNS A lookup (cache bypassed) on the active network, timeout 2 s;
 * - the active network ([ActiveNetworkMonitor]) and the mobile signal ([CellularMonitor]); the
 *   RSRP of the data SIM is sampled into the 1 Hz timeline;
 * - the public IP and provider ([PublicIpRepository]) when the screen opens and when the active
 *   network changes, unless the user switched the lookup off.
 *
 * Without `ACCESS_FINE_LOCATION` sampling still runs, but SSID/BSSID are redacted and roaming is
 * not detected. Without `READ_PHONE_STATE` only the data SIM is visible (decision O-4).
 */
class WifiLiveViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val connectivity = application.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = application.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val monitor = WifiLinkMonitor(connectivity)
    private val activeMonitor = ActiveNetworkMonitor(connectivity)
    private val cellularMonitor = CellularMonitor(application)
    private val publicIpRepository = PublicIpRepository(application)
    private val hasTelephony = application.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    private val direct = Executor { it.run() }

    private val _state = MutableStateFlow(WifiLiveState())
    /** Live telemetry state; updated a few times per second while active. */
    val state: StateFlow<WifiLiveState> = _state.asStateFlow()

    // Everything below is confined to the main thread (viewModelScope's dispatcher).
    private val signal = TrackedSeries()
    private val rxLink = TrackedSeries()
    private val txLink = TrackedSeries()
    private val deviceRx = TrackedSeries()
    private val deviceTx = TrackedSeries()
    private val gateway = TrackedSeries()
    private val internet = TrackedSeries()
    private val dnsSeries = TrackedSeries(LIVE_DNS_SERIES_CAPACITY)
    private val cellSignal = TrackedSeries()
    private val rxMeter = ByteRateMeter()
    private val txMeter = ByteRateMeter()
    private val roaming = RoamingDetector()
    private val events = LiveEventLog()
    private val cellEvents = CellularEventLog()
    private var timelineSource: LiveSeries? = null
    private var timeline = LiveTimeline.EMPTY

    private var session: Job? = null
    /** Bumped by [clear]; a loop result started before it is discarded. */
    private var clearGeneration = 0
    private var snapshot: WifiLinkSnapshot? = null
    private var linkKnown = false
    private var monitorFailed = false
    private var link: LiveLink? = null
    private var permissionMissing = false
    private var locationDisabled = false
    private var trafficUnavailable = false

    private var gatewayTarget: String? = null
    private var gatewayStatus = LiveProbeStatus.IDLE
    private var internetTarget = LIVE_DEFAULT_INTERNET_TARGET
    private var internetGeneration = 0
    private var internetStatus = LiveProbeStatus.IDLE
    private var dnsName = LIVE_DEFAULT_DNS_NAME
    private var dnsGeneration = 0
    private var dnsLast = LiveDns(name = LIVE_DEFAULT_DNS_NAME)

    private var activeNetwork: ActiveNetworkInfo? = null
    private var activeKnown = false
    private var activeHandle: Long? = null
    private var cellularJob: Job? = null
    private var cellular: CellularState? = null
    private var cellularPermission = false

    private var publicIpEnabled = true
    private var publicIpSettingLoaded = false
    private var publicIpLoading = false
    private var publicIpInfo: PublicIpInfo? = null
    /** Network key the shown [publicIpInfo] belongs to. */
    private var publicIpKey: String? = null
    private var publicIpJob: Job? = null
    /** Bumped by every lookup request; only the newest one may change the public IP state. */
    private var publicIpGeneration = 0

    init {
        viewModelScope.launch {
            publicIpEnabled = withContext(Dispatchers.IO) { publicIpRepository.isLookupEnabled() }
            publicIpSettingLoaded = true
            publish()
            requestPublicIp(force = false, settle = false)
        }
    }

    /**
     * Starts (`true`) or stops (`false`) all sampling. Idempotent. Stopping cancels every loop
     * and the network callback immediately; collected data stays in [state].
     */
    fun setActive(active: Boolean) {
        if (!active) {
            session?.cancel()
            session = null
            cellularJob?.cancel()
            cellularJob = null
            publicIpJob?.cancel()
            publicIpJob = null
            publicIpGeneration++
            publicIpLoading = false
            snapshot = null
            linkKnown = false
            activeKnown = false
            publish()
            return
        }
        if (session?.isActive == true) return
        // A pause is a gap: no rate across it, a later BSSID change is not a seamless roam.
        rxMeter.reset(); txMeter.reset(); roaming.markGap()
        monitorFailed = false
        val settled = CompletableDeferred<Unit>()
        session = viewModelScope.launch {
            launch {
                try {
                    monitor.observe().collect {
                        snapshot = it
                        if (it != null) { linkKnown = true; settled.complete(Unit) }
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    snapshot = null; monitorFailed = true; linkKnown = true
                    settled.complete(Unit)
                    publish()
                }
            }
            launch {
                activeMonitor.observe().collect { info ->
                    activeNetwork = info
                    activeKnown = true
                    val handle = info?.network?.networkHandle
                    if (handle != activeHandle) {
                        activeHandle = handle
                        requestPublicIp(force = false, settle = true)
                    }
                    publish()
                }
            }
            withTimeoutOrNull(MONITOR_SETTLE_MS) { settled.await() }
            linkKnown = true
            launch { linkLoop() }
            launch { latencyLoop(LiveProbe.GATEWAY) }
            launch { latencyLoop(LiveProbe.INTERNET) }
            launch { dnsLoop() }
        }
        startCellular()
        requestPublicIp(force = false, settle = true)
        publish()
    }

    /**
     * Collects the mobile signal again, e.g. after the READ_PHONE_STATE dialog: the monitor picks a
     * granted permission up only on its next event, a fresh collection reads it at once.
     */
    fun restartCellular() {
        if (session?.isActive != true) return
        cellularJob?.cancel()
        cellularJob = null
        startCellular()
    }

    private fun startCellular() {
        if (cellularJob?.isActive == true) return
        cellularJob = viewModelScope.launch {
            try {
                cellularMonitor.observe().collect { value ->
                    cellular = value
                    cellularPermission = withContext(Dispatchers.IO) { cellularPermissionNeeded(app) }
                    publish()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Best effort: without telephony data the card shows its empty states.
                cellular = null
                publish()
            }
        }
    }

    /** Switches the public IP lookup on or off (stored); off stops a running lookup and forgets the result. */
    fun setPublicIpLookup(enabled: Boolean) {
        if (enabled == publicIpEnabled && publicIpSettingLoaded) return
        publicIpEnabled = enabled
        publicIpSettingLoaded = true
        viewModelScope.launch(Dispatchers.IO) { publicIpRepository.setLookupEnabled(enabled) }
        if (!enabled) {
            publicIpJob?.cancel()
            publicIpJob = null
            publicIpGeneration++
            publicIpLoading = false
            publicIpInfo = null
            publicIpKey = null
        } else {
            requestPublicIp(force = false, settle = false)
        }
        publish()
    }

    /** "Obnovit": looks the public IP up again on the current network, ignoring the cache. */
    fun refreshPublicIp() = requestPublicIp(force = true, settle = false)

    /** Cache key of the current active network (type + SSID / data subscription, else the network handle). */
    private fun currentNetworkKey(): String? {
        val info = activeNetwork ?: return null
        val ssid = link?.ssid.takeIf { info.kind == NetKind.WIFI && snapshot?.network == info.network }
        val subscription = cellular?.sims?.firstOrNull { it.isDataSim }?.subscriptionId.takeIf { info.kind == NetKind.CELLULAR }
        return publicIpNetworkKey(info.kind, ssid, subscription, info.network.networkHandle)
    }

    private fun requestPublicIp(force: Boolean, settle: Boolean) {
        if (!publicIpEnabled || !publicIpSettingLoaded || session?.isActive != true) return
        publicIpJob?.cancel()
        val generation = ++publicIpGeneration
        // While the network settles the card says "zjišťuji…", not "nedostupná".
        publicIpLoading = true
        publicIpJob = viewModelScope.launch {
            if (settle) delay(PUBLIC_IP_SETTLE_MS)
            val info = activeNetwork
            val key = currentNetworkKey()
            if (key != publicIpKey) { publicIpInfo = null; publicIpKey = key }
            if (info == null) { publicIpLoading = false; publish(); return@launch }
            if (!force) publicIpRepository.cached(key)?.let { cached ->
                publicIpInfo = cached
                publicIpLoading = false
                publish()
                return@launch
            }
            publicIpLoading = true
            publish()
            val result = try {
                publicIpRepository.fetch(info.network, key)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }
            if (generation == publicIpGeneration) {
                publicIpInfo = result
                publicIpLoading = false
                publish()
            }
        }
    }

    /**
     * Sets the internet ICMP target. Accepts only a numeric IPv4 (no DNS); loopback,
     * `0.0.0.0/8` and multicast/reserved are rejected — show [liveInternetTargetInvalidText]
     * on `false`. A changed target clears the internet latency series.
     */
    fun setInternetTarget(ip: String): Boolean {
        val target = validLiveInternetTarget(ip) ?: return false
        if (target != internetTarget) {
            internetTarget = target
            internetGeneration++
            internet.clear()
            internetStatus = LiveProbeStatus.IDLE
            publish()
        }
        return true
    }

    /**
     * Sets the DNS probe name (hostname, A record). Returns `false` for an invalid name — show
     * [liveDnsNameInvalidText]. A changed name clears the DNS series.
     */
    fun setDnsName(name: String): Boolean {
        val valid = validLiveDnsName(name) ?: return false
        if (valid != dnsName) {
            dnsName = valid
            dnsGeneration++
            dnsSeries.clear()
            dnsLast = LiveDns(name = valid)
            publish()
        }
        return true
    }

    /** Empties all series, statistics, roaming and chart events; sampling state and targets stay. */
    fun clear() {
        listOf(signal, rxLink, txLink, deviceRx, deviceTx, gateway, internet, dnsSeries, cellSignal).forEach { it.clear() }
        rxMeter.reset(); txMeter.reset(); roaming.clear(); events.clear(); cellEvents.clear()
        gatewayStatus = LiveProbeStatus.IDLE
        internetStatus = LiveProbeStatus.IDLE
        clearGeneration++
        internetGeneration++
        dnsGeneration++
        dnsLast = LiveDns(name = dnsName)
        publish()
    }

    override fun onCleared() {
        session?.cancel()
        session = null
        cellularJob?.cancel()
        publicIpJob?.cancel()
        super.onCleared()
    }

    // --- Loops (run on the main dispatcher; blocking work hops to IO) ---------------------------

    private suspend fun linkLoop() {
        while (true) {
            val started = SystemClock.elapsedRealtime()
            val current = snapshot
            val generation = clearGeneration
            val reading = withContext(Dispatchers.IO) { readLink(current, started) }
            if (generation == clearGeneration) applyLink(started, reading)
            publish()
            delay(liveTickDelayMs(started, SystemClock.elapsedRealtime()))
        }
    }

    /**
     * One latency target at 1 Hz. The gateway is the Wi-Fi default gateway on the Wi-Fi network
     * (no Wi-Fi = [LiveProbeStatus.NO_WIFI]); the internet target runs on the active network
     * (none = [LiveProbeStatus.NO_NETWORK]).
     */
    private suspend fun latencyLoop(probe: LiveProbe) {
        val gatewayProbe = probe == LiveProbe.GATEWAY
        while (true) {
            val started = SystemClock.elapsedRealtime()
            val wifi = snapshot
            val network = if (gatewayProbe) wifi?.network else activeNetwork?.network
            val target = if (gatewayProbe) wifi?.gatewayIpv4 else internetTarget
            val generation = if (gatewayProbe) clearGeneration else internetGeneration
            if (gatewayProbe && target != gatewayTarget && target != null) {
                // Another gateway is another target: do not mix its RTT into the old series.
                gatewayTarget = target
                gateway.clear()
            }
            val missing = if (gatewayProbe) LiveProbeStatus.NO_WIFI else LiveProbeStatus.NO_NETWORK
            val status: LiveProbeStatus
            val value: Double?
            when {
                network == null -> { status = missing; value = null }
                target == null -> { status = LiveProbeStatus.NO_TARGET; value = null }
                else -> {
                    val result = echo(network, target)
                    // A bind failure right after the network vanished is a lost network, not missing ICMP support.
                    val now = if (gatewayProbe) snapshot?.network else activeNetwork?.network
                    status = if (result.status == LiveProbeStatus.ICMP_UNAVAILABLE && now != network) missing else result.status
                    value = result.milliseconds
                }
            }
            val series = if (gatewayProbe) gateway else internet
            if (generation == (if (gatewayProbe) clearGeneration else internetGeneration)) {
                if (status == LiveProbeStatus.OK || status == LiveProbeStatus.LOST || status == missing) {
                    series.add(started, value)
                }
                if (gatewayProbe) gatewayStatus = status else internetStatus = status
                events.observeProbe(probe, started, target, lost = status == LiveProbeStatus.LOST)
                publish()
            }
            delay(liveTickDelayMs(started, SystemClock.elapsedRealtime()))
        }
    }

    private suspend fun dnsLoop() {
        while (true) {
            val started = SystemClock.elapsedRealtime()
            val current = activeNetwork
            val name = dnsName
            val generation = dnsGeneration
            val outcome = if (current == null) DnsOutcome(LiveDnsStatus.NO_NETWORK) else queryDns(current.network, name)
            if (generation == dnsGeneration) {
                dnsSeries.add(started, outcome.milliseconds)
                dnsLast = LiveDns(name = name, lastMs = outcome.milliseconds, lastElapsedMs = started,
                    status = outcome.status, lastRcode = outcome.rcode)
                publish()
            }
            delay(liveTickDelayMs(started, SystemClock.elapsedRealtime(), LIVE_DNS_INTERVAL_MS))
        }
    }

    // --- Platform reads -----------------------------------------------------------------------

    private class LinkReading(
        val link: LiveLink?,
        val rxBytes: Long,
        val txBytes: Long,
        val permissionMissing: Boolean,
        val locationDisabled: Boolean,
    )

    /** Blocking: binder calls to WifiManager/LocationManager and TrafficStats. */
    private fun readLink(current: WifiLinkSnapshot?, elapsedMs: Long): LinkReading {
        val permissionMissing = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        val locationDisabled = try {
            !app.getSystemService(LocationManager::class.java).isLocationEnabled
        } catch (_: Exception) { false }
        val link = current?.let { snap ->
            try { liveLink(snap, elapsedMs) } catch (_: Exception) { null }
        }
        return LinkReading(link, TrafficStats.getTotalRxBytes(), TrafficStats.getTotalTxBytes(), permissionMissing, locationDisabled)
    }

    @Suppress("DEPRECATION") // connectionInfo: only source below API 31, fresher radio values above (see selectLiveRadioSource).
    private fun liveLink(snap: WifiLinkSnapshot, elapsedMs: Long): LiveLink {
        val callbackInfo = snap.info
        val legacy: WifiInfo? = try { wifiManager.connectionInfo } catch (_: Exception) { null }
        val radio = when (selectLiveRadioSource(Build.VERSION.SDK_INT, callbackInfo != null, callbackInfo?.bssid,
            legacy != null, legacy?.bssid)) {
            LiveRadioSource.CALLBACK -> callbackInfo
            LiveRadioSource.LEGACY -> legacy
            LiveRadioSource.NONE -> null
        }
        // Identity: API 31+ from the location-aware callback, below 31 from connectionInfo.
        val identity = callbackInfo ?: legacy
        val frequency = radio?.frequency?.takeIf { it > 0 }
        return LiveLink(
            elapsedMs = elapsedMs,
            ssid = liveSsid(identity?.ssid),
            bssid = usableBssid(identity?.bssid),
            rssiDbm = validRssiDbm(radio?.rssi),
            rxLinkMbps = radio?.let { liveLinkRate(it.rxLinkSpeedMbps) },
            txLinkMbps = radio?.let { liveLinkRate(it.txLinkSpeedMbps) },
            frequencyMhz = frequency,
            channel = frequency?.let(::channelForFrequency),
            band = frequency?.let(::bandForFrequency) ?: WifiBand.UNKNOWN,
            ipv4Address = snap.ipv4Address,
            gatewayIpv4 = snap.gatewayIpv4,
            wifiStandard = (radio ?: identity)?.let(::liveWifiStandard),
            security = (radio ?: identity)?.let(::liveSecurity),
        )
    }

    private fun liveWifiStandard(info: WifiInfo): LiveWifiStandard? {
        if (Build.VERSION.SDK_INT < 30) return null
        return when (info.wifiStandard) {
            ScanResult.WIFI_STANDARD_LEGACY -> LiveWifiStandard.LEGACY
            ScanResult.WIFI_STANDARD_11N -> LiveWifiStandard.N
            ScanResult.WIFI_STANDARD_11AC -> LiveWifiStandard.AC
            ScanResult.WIFI_STANDARD_11AX -> LiveWifiStandard.AX
            else -> when {
                Build.VERSION.SDK_INT >= 33 && info.wifiStandard == ScanResult.WIFI_STANDARD_11BE -> LiveWifiStandard.BE
                Build.VERSION.SDK_INT >= 31 && info.wifiStandard == ScanResult.WIFI_STANDARD_11AD -> LiveWifiStandard.AD
                else -> null
            }
        }
    }

    /** Same mapping as the Wi-Fi analyzer (`WifiViewModel.currentFrom`). */
    private fun liveSecurity(info: WifiInfo): WifiSecurity? {
        if (Build.VERSION.SDK_INT < 31) return null
        return when (info.currentSecurityType) {
            WifiInfo.SECURITY_TYPE_OPEN -> WifiSecurity.OPEN
            WifiInfo.SECURITY_TYPE_WEP -> WifiSecurity.WEP
            WifiInfo.SECURITY_TYPE_PSK, WifiInfo.SECURITY_TYPE_EAP -> WifiSecurity.WPA2
            WifiInfo.SECURITY_TYPE_SAE, WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE,
            WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT -> WifiSecurity.WPA3
            WifiInfo.SECURITY_TYPE_OWE -> WifiSecurity.OWE
            else -> null
        }
    }

    private fun applyLink(started: Long, reading: LinkReading) {
        link = reading.link
        permissionMissing = reading.permissionMissing
        locationDisabled = reading.locationDisabled
        signal.add(started, reading.link?.rssiDbm?.toDouble())
        rxLink.add(started, reading.link?.rxLinkMbps?.toDouble())
        txLink.add(started, reading.link?.txLinkMbps?.toDouble())
        trafficUnavailable = reading.rxBytes < 0 || reading.txBytes < 0
        addRate(deviceRx, rxMeter, started, reading.rxBytes)
        addRate(deviceTx, txMeter, started, reading.txBytes)
        val roam = if (reading.link != null) roaming.observe(started, reading.link.bssid, reading.link.frequencyMhz)
        else roaming.observe(started, null, null)
        events.observeLink(started, reading.link?.bssid, reading.link?.channel, roam)
        // The mobile gauge's SIM, sampled on the same 1 Hz timeline (the modem reports every few seconds).
        val sims = cellular?.sims.orEmpty()
        cellSignal.add(started, primaryDbm(primarySim(sims)?.signal)?.toDouble())
        cellEvents.observe(started, sims.firstOrNull { it.isDataSim })
    }

    /** No sample for the first reading after start/reset; a counter reset mid-run is a `null` gap. */
    private fun addRate(series: TrackedSeries, meter: ByteRateMeter, started: Long, bytes: Long) {
        val primed = meter.hasBaseline
        val rate = meter.next(started, bytes)
        if (primed || rate != null) series.add(started, rate)
    }

    private class EchoResult(val status: LiveProbeStatus, val milliseconds: Double?)

    private suspend fun echo(network: Network, target: String): EchoResult = try {
        val reply = withTimeoutOrNull(ECHO_GUARD_MS) { WifiIcmp.echo(network, target, LIVE_ECHO_TIMEOUT_MS) }
        val ms = reply?.milliseconds
        EchoResult(if (ms != null) LiveProbeStatus.OK else LiveProbeStatus.LOST, ms)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: IcmpUnavailableException) {
        EchoResult(LiveProbeStatus.ICMP_UNAVAILABLE, null)
    } catch (_: Exception) {
        EchoResult(LiveProbeStatus.LOST, null)
    }

    private class DnsOutcome(val status: LiveDnsStatus, val milliseconds: Double? = null, val rcode: Int? = null)

    /** A-record lookup on [network] (the active one), resolver cache bypassed; cancellable via CancellationSignal. */
    private suspend fun queryDns(network: Network, name: String): DnsOutcome =
        withTimeoutOrNull(LIVE_DNS_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                val startedNanos = SystemClock.elapsedRealtimeNanos()
                try {
                    DnsResolver.getInstance().query(network, name, DnsResolver.TYPE_A, DnsResolver.FLAG_NO_CACHE_LOOKUP,
                        direct, signal, object : DnsResolver.Callback<List<InetAddress>> {
                            override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                                val ms = (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000.0
                                if (cont.isActive) cont.resume(
                                    if (rcode == 0) DnsOutcome(LiveDnsStatus.OK, ms)
                                    else DnsOutcome(LiveDnsStatus.ERROR_RCODE, rcode = rcode))
                            }
                            override fun onError(error: DnsResolver.DnsException) {
                                if (cont.isActive) cont.resume(DnsOutcome(LiveDnsStatus.FAILED))
                            }
                        })
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(DnsOutcome(LiveDnsStatus.FAILED))
                }
            }
        } ?: DnsOutcome(LiveDnsStatus.TIMEOUT)

    // --- Publication --------------------------------------------------------------------------

    private fun publish() {
        // The key can change without a new network (the SSID or the data SIM arrive late): look up again.
        if (publicIpEnabled && publicIpSettingLoaded && session?.isActive == true && publicIpJob?.isActive != true &&
            currentNetworkKey() != publicIpKey) requestPublicIp(force = false, settle = false)
        val current = link?.takeIf { snapshot != null || !linkKnown }
        val noWifi = linkKnown && snapshot == null && !monitorFailed
        val issues = buildSet {
            if (noWifi) add(WifiLiveIssue.NO_WIFI)
            if (monitorFailed) add(WifiLiveIssue.LINK_MONITOR_FAILED)
            if (permissionMissing) add(WifiLiveIssue.PERMISSION_MISSING)
            if (locationDisabled) add(WifiLiveIssue.LOCATION_DISABLED)
            if (gatewayStatus == LiveProbeStatus.ICMP_UNAVAILABLE || internetStatus == LiveProbeStatus.ICMP_UNAVAILABLE)
                add(WifiLiveIssue.ICMP_UNAVAILABLE)
            if (gatewayStatus == LiveProbeStatus.NO_TARGET && !noWifi) add(WifiLiveIssue.NO_GATEWAY)
            if (trafficUnavailable) add(WifiLiveIssue.TRAFFIC_STATS_UNAVAILABLE)
        }
        val signalSeries = signal.series()
        if (signalSeries !== timelineSource) {
            timeline = LiveTimeline.of(signalSeries.samples, LIVE_SAMPLE_INTERVAL_MS)
            timelineSource = signalSeries
        }
        val oldest = signalSeries.samples.firstOrNull()?.elapsedMs
        events.prune(oldest)
        cellEvents.prune(oldest)
        val chartEvents: List<ChartEvent> = timeline.chartEvents(events.events)
        val network = activeNetwork
        val dnsResolver = network?.let { dnsResolverNote(it.dnsServers, it.privateDnsActive, it.privateDnsServerName) }
            ?: DnsResolverNote.Unknown
        _state.value = WifiLiveState(
            running = session?.isActive == true,
            link = current,
            quality = current?.let { signalQuality(it.rssiDbm) } ?: SignalQuality.UNKNOWN,
            signalDbm = signalSeries,
            rxLinkMbps = rxLink.series(),
            txLinkMbps = txLink.series(),
            deviceRxMbps = deviceRx.series(),
            deviceTxMbps = deviceTx.series(),
            gatewayLatency = LiveLatency(gatewayTarget.takeUnless { gatewayStatus == LiveProbeStatus.NO_TARGET },
                gateway.series(), gatewayStatus),
            internetLatency = LiveLatency(internetTarget, internet.series(), internetStatus),
            dns = dnsLast.copy(series = dnsSeries.series(), resolver = dnsResolver),
            roamingEvents = roaming.events,
            timeline = timeline,
            chartEvents = chartEvents,
            issues = issues,
            sampleIntervalMs = LIVE_SAMPLE_INTERVAL_MS,
            network = network?.let {
                OverviewNetwork(it.kind, it.metered, it.viaVpn, it.interfaceName, it.localIpv4, it.localIpv6, it.network.networkHandle)
            },
            networkKnown = activeKnown,
            cellular = cellular,
            hasTelephony = hasTelephony,
            cellularPermissionNeeded = cellularPermission,
            cellularSignalDbm = cellSignal.series(),
            cellularChartEvents = timeline.chartEvents(cellEvents.events),
            publicIp = PublicIpUiState(
                enabled = publicIpEnabled, settingLoaded = publicIpSettingLoaded, loading = publicIpLoading,
                info = publicIpInfo.takeIf { publicIpKey != null && publicIpKey == currentNetworkKey() },
            ),
        )
    }

    /** Ring buffer with a cached immutable [LiveSeries]; rebuilt only after a change. */
    private class TrackedSeries(capacity: Int = LIVE_SERIES_DEFAULT_CAPACITY) {
        private val buffer = LiveRingBuffer(capacity)
        private var cached: LiveSeries? = null
        fun add(elapsedMs: Long, value: Double?) { if (buffer.add(elapsedMs, value)) cached = null }
        fun clear() { buffer.clear(); cached = null }
        fun series(): LiveSeries = cached ?: LiveSeries.of(buffer).also { cached = it }
    }
}
