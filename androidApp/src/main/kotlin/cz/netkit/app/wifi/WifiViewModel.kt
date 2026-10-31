package cz.netkit.app.wifi

import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.edit
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.netkit.shared.wifi.snapshotAccessPoints
import cz.netkit.shared.wifi.wifiDatasetAge
import cz.netkit.shared.wifi.signalSampleForDataset
import cz.netkit.shared.wifi.withOwnAccessPoint
import cz.netkit.shared.wifi.appendSignalSample
import cz.netkit.shared.wifi.normalizeBssid
import cz.netkit.shared.wifi.preferIpv4Address
import cz.netkit.shared.wifi.publishScanResult
import cz.netkit.shared.wifi.toggleWifiSelection
import cz.netkit.shared.wifi.wifiAccessIssue
import cz.netkit.shared.wifi.WifiAccessIssue
import cz.netkit.shared.wifi.AccessPoint
import cz.netkit.shared.wifi.CurrentWifi
import cz.netkit.shared.wifi.SignalSample
import cz.netkit.shared.wifi.WifiAnalyzerState
import cz.netkit.shared.wifi.WifiScanStatus
import cz.netkit.shared.wifi.WifiSecurity
import cz.netkit.shared.wifi.bandForFrequency
import cz.netkit.shared.wifi.channelForFrequency
import cz.netkit.shared.wifi.deduplicateAccessPoints
import cz.netkit.shared.wifi.classifySecurity
import cz.netkit.shared.wifi.isLocallyAdministered
import cz.netkit.shared.wifi.BSS_LOAD_ELEMENT_ID
import cz.netkit.shared.wifi.BssLoad
import cz.netkit.shared.wifi.WIFI_ELEMENT_MAX_BODY_LENGTH
import cz.netkit.shared.wifi.parseBssLoad
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import java.util.concurrent.atomic.AtomicBoolean

// Android returns this literal when SSID access is unavailable. Keep the sentinel local so
// the minSdk 29 implementation does not reference WifiManager.UNKNOWN_SSID (API 30+).
private const val UNKNOWN_SSID_REDACTION = "<unknown ssid>"

class WifiViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val wifi = application.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val preferences = application.getSharedPreferences("wifi-ui", Context.MODE_PRIVATE)
    private val connectivity = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _state = MutableStateFlow(WifiAnalyzerState())
    val state: StateFlow<WifiAnalyzerState> = _state.asStateFlow()
    private val _history = MutableStateFlow<List<SignalSample>>(emptyList())
    val history: StateFlow<List<SignalSample>> = _history.asStateFlow()
    private var scanJob: Job? = null
    private var currentJob: Job? = null
    private var selectedBssid: String? = null


    init {
        refreshCurrent()
        viewModelScope.launch {
            if (!withContext(Dispatchers.IO) { hasScanPermission() }) {
                _state.update { it.copy(status = WifiScanStatus.PERMISSION_REQUIRED) }
            }
        }
    }

    fun hasScanPermission(): Boolean = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun isLocationEnabled(): Boolean = try {
        val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        manager.isLocationEnabled
    } catch (_: Exception) { false }

    fun permissionWasRequested(): Boolean = preferences.getBoolean("location-requested", false)
    fun markPermissionRequested() { preferences.edit { putBoolean("location-requested", true) } }

    fun accessIssue(requestedBefore: Boolean, showRationale: Boolean): WifiAccessIssue = wifiAccessIssue(
        fine = hasScanPermission(),
        coarse = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED,
        requestedBefore = requestedBefore, showRationale = showRationale,
        locationEnabled = isLocationEnabled(), wifiEnabled = wifi.isWifiEnabled,
    )

    fun refreshCurrent() {
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            val current = withContext(Dispatchers.IO) {
                if (hasScanPermission()) readCurrent() else null
            }
            _state.update { old -> old.copy(current = current, accessPoints = markPoints(old.accessPoints, current?.bssid)) }
        }
    }

    private fun markPoints(points: List<AccessPoint>, currentBssid: String?): List<AccessPoint> = points.map {
        it.copy(isCurrent = normalizeBssid(currentBssid)?.let { current -> normalizeBssid(it.bssid) == current } == true,
            isSelected = selectedBssid != null && normalizeBssid(it.bssid) == selectedBssid)
    }

    fun scan() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            try {
                val accessStatus = withContext(Dispatchers.IO) {
                    when {
                        !hasScanPermission() -> WifiScanStatus.PERMISSION_REQUIRED
                        !isLocationEnabled() -> WifiScanStatus.LOCATION_DISABLED
                        !wifi.isWifiEnabled -> WifiScanStatus.WIFI_DISABLED
                        else -> null
                    }
                }
                if (accessStatus != null) {
                    _state.update { it.copy(status = accessStatus) }
                    return@launch
                }
                _state.update { it.copy(status = WifiScanStatus.SCANNING) }
                val snapshot = withContext(Dispatchers.IO) {
                    val scanRequestedAtElapsedMs = SystemClock.elapsedRealtime()
                    val accepted = withTimeoutOrNull(12_000L) { requestScan() } ?: false
                    val results = wifi.scanResults
                    val now = SystemClock.elapsedRealtime()
                    val mapped = deduplicateAccessPoints(results.map { it.toPoint(now) }).sortedWith(compareByDescending<AccessPoint> { it.rssiDbm }.thenBy { it.ssid })
                    ScanSnapshot(mapped, accepted && results.any {
                        val seenAtMs = it.timestamp / 1000L
                        seenAtMs >= scanRequestedAtElapsedMs && seenAtMs <= now && now - seenAtMs < 15_000L
                    }, now)
                }
                // Results appear before the up-to-four-second identity lookup finishes.
                _state.update { old -> publishScanResult(old, markPoints(snapshot.points, old.current?.bssid),
                    snapshot.fresh, snapshot.receivedElapsedMs) }
                if (snapshot.fresh) appendHistory()
                refreshCurrent()

            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: SecurityException) {
                _state.update { it.copy(status = WifiScanStatus.PERMISSION_REQUIRED) }
            } catch (_: Exception) {
                _state.update { it.copy(status = WifiScanStatus.ERROR) }
            }
        }
    }

    fun excludeConnectedSsid(enabled: Boolean) { _state.update { it.copy(excludeConnectedSsid = enabled) } }

    fun markOwnAccessPoint(bssid: String, owned: Boolean) { _state.update { withOwnAccessPoint(it, bssid, owned) } }

    fun selectNetwork(bssid: String) {
        val next = toggleWifiSelection(selectedBssid, bssid)
        if (next == selectedBssid) return // An anonymous observation has no stable radio identity.
        selectedBssid = next
        if (next != null && next != _state.value.historyBssid) _history.value = emptyList()
        _state.update { old -> old.copy(selectedBssid = next,
            historyBssid = next ?: old.historyBssid,
            accessPoints = markPoints(old.accessPoints, old.current?.bssid)) }
        if (next != null) appendHistory()
    }

    fun cancelActive() {
        if (scanJob?.isActive == true) {
            scanJob?.cancel()
            _state.update { if (it.status == WifiScanStatus.SCANNING) it.copy(status = WifiScanStatus.CANCELLED) else it }
        }
        scanJob = null
    }

    private suspend fun requestScan(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return suspendCancellableCoroutine { continuation ->
                val callback = object : WifiManager.ScanResultsCallback() {
                    override fun onScanResultsAvailable() {
                        if (continuation.isActive) continuation.resume(true)
                        runCatching { wifi.unregisterScanResultsCallback(this) }
                    }
                }
                try {
                    wifi.registerScanResultsCallback(app.mainExecutor, callback)
                    continuation.invokeOnCancellation { runCatching { wifi.unregisterScanResultsCallback(callback) } }
                    val started = wifi.startScan()
                    if (!started && continuation.isActive) {
                        wifi.unregisterScanResultsCallback(callback)
                        continuation.resume(false)
                    }
                } catch (e: Exception) {
                    runCatching { wifi.unregisterScanResultsCallback(callback) }
                    if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                }
            }
        }
        return suspendCancellableCoroutine { continuation ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION && continuation.isActive) {
                        runCatching { app.unregisterReceiver(this) }
                        continuation.resume(intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false))
                    }
                }
            }
            try {
                val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                else @Suppress("DEPRECATION") app.registerReceiver(receiver, filter)
                continuation.invokeOnCancellation { runCatching { app.unregisterReceiver(receiver) } }
                val started = wifi.startScan()
                if (!started && continuation.isActive) {
                    runCatching { app.unregisterReceiver(receiver) }
                    continuation.resume(false)
                }
            } catch (e: Exception) {
                runCatching { app.unregisterReceiver(receiver) }
                if (continuation.isActive) continuation.resumeWith(Result.failure(e))
            }
        }
    }

    private fun ScanResult.toPoint(now: Long): AccessPoint {
        val seenAt = (timestamp / 1000L).takeIf { it > 0 && it <= now }
        val age = seenAt?.let { now - it } ?: Long.MAX_VALUE
        val capabilities = capabilities.orEmpty()
        val width = when (channelWidth) {
            ScanResult.CHANNEL_WIDTH_20MHZ -> 20
            ScanResult.CHANNEL_WIDTH_40MHZ -> 40
            ScanResult.CHANNEL_WIDTH_80MHZ -> 80
            ScanResult.CHANNEL_WIDTH_160MHZ, ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 160
            else -> null // 320 MHz constant is API 33; handled without referencing it on API 29.
        }.let { if (Build.VERSION.SDK_INT >= 33 && channelWidth == ScanResult.CHANNEL_WIDTH_320MHZ) 320 else it }
        val standard = if (Build.VERSION.SDK_INT >= 30) when (wifiStandard) {
            ScanResult.WIFI_STANDARD_LEGACY -> "a/b/g"
            ScanResult.WIFI_STANDARD_11N -> "n"
            ScanResult.WIFI_STANDARD_11AC -> "ac"
            ScanResult.WIFI_STANDARD_11AX -> "ax"
            else -> when {
                Build.VERSION.SDK_INT >= 33 && wifiStandard == ScanResult.WIFI_STANDARD_11BE -> "be"
                Build.VERSION.SDK_INT >= 31 && wifiStandard == ScanResult.WIFI_STANDARD_11AD -> "ad"
                else -> null
            }
        } else null
        val safeSsid = SSID?.takeUnless { it.isBlank() || it == UNKNOWN_SSID_REDACTION }
        val bssLoad = bssLoadElement()
        return AccessPoint(
            ssid = safeSsid,
            bssid = normalizeBssid(BSSID).orEmpty(),
            rssiDbm = level,
            frequencyMhz = frequency,
            centerFrequencyMhz = centerFreq0.takeIf { it > 0 },
            channel = channelForFrequency(frequency),
            band = bandForFrequency(frequency),
            widthMhz = width,
            secondaryCenterFrequencyMhz = centerFreq1.takeIf {
                channelWidth == ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ && it > 0
            },
            is80Plus80 = channelWidth == ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ,
            security = classifySecurity(capabilities),
            standard = standard,
            ageMs = age,
            seenAtElapsedMs = seenAt,
            locallyAdministered = isLocallyAdministered(BSSID.orEmpty()),
            stationCount = bssLoad?.stationCount,
            channelUtilizationPercent = bssLoad?.channelUtilizationPercent,
        )
    }

    /** BSS Load (IE 11) from the beacon/probe response; API 30+ only, absent/malformed → null. */
    private fun ScanResult.bssLoadElement(): BssLoad? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val body = runCatching {
            val element = informationElements.firstOrNull { it.id == BSS_LOAD_ELEMENT_ID } ?: return null
            // duplicate(): independent position, the framework buffer is not consumed.
            val buffer = element.bytes.duplicate()
            if (buffer.remaining() > WIFI_ELEMENT_MAX_BODY_LENGTH) return null
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        }.getOrNull() ?: return null
        return parseBssLoad(body)
    }

    private suspend fun readCurrent(dataset: WifiAnalyzerState = _state.value): CurrentWifi? =
        withTimeoutOrNull(4_000L) {
            suspendCancellableCoroutine { continuation ->
                val lock = Any()
                val capabilitiesByNetwork = linkedMapOf<Network, NetworkCapabilities>()
                val linksByNetwork = linkedMapOf<Network, LinkProperties>()
                val registered = AtomicBoolean(false)
                val unregisterRequested = AtomicBoolean(false)
                val unregistered = AtomicBoolean(false)
                val completionClaimed = AtomicBoolean(false)
                lateinit var callback: ConnectivityManager.NetworkCallback
                fun unregister() {
                    unregisterRequested.set(true)
                    if (registered.get() && unregistered.compareAndSet(false, true)) {
                        runCatching { connectivity.unregisterNetworkCallback(callback) }
                    }
                }
                fun tryComplete() {
                    val pair = synchronized(lock) {
                        capabilitiesByNetwork.entries.firstNotNullOfOrNull { (network, caps) ->
                            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                                linksByNetwork[network]?.let { Triple(network, caps, it) }
                            } else null
                        }
                    } ?: return
                    val caps = pair.second
                    val info = caps.transportInfo as? WifiInfo
                        ?: (if (Build.VERSION.SDK_INT < 31) wifi.connectionInfo else null)
                        ?: return
                    val current = try { currentFrom(info, pair.third, dataset) } catch (_: Exception) { null }
                    if (continuation.isActive && completionClaimed.compareAndSet(false, true)) {
                        unregister()
                        continuation.resume(current)
                    }
                }
                fun updateCapabilities(network: Network, caps: NetworkCapabilities) {
                    synchronized(lock) { capabilitiesByNetwork[network] = caps }
                    tryComplete()
                }
                fun updateLinks(network: Network, links: LinkProperties) {
                    synchronized(lock) { linksByNetwork[network] = links }
                    tryComplete()
                }
                fun removeNetwork(network: Network) {
                    synchronized(lock) {
                        capabilitiesByNetwork.remove(network)
                        linksByNetwork.remove(network)
                    }
                    tryComplete()
                }
                callback = if (Build.VERSION.SDK_INT >= 31) {
                    object : ConnectivityManager.NetworkCallback(
                        ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO,
                    ) {
                        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                            updateCapabilities(network, networkCapabilities)
                        }
                        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                            updateLinks(network, linkProperties)
                        }
                        override fun onLost(network: Network) = removeNetwork(network)
                    }
                } else {
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                            updateCapabilities(network, networkCapabilities)
                        }
                        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                            updateLinks(network, linkProperties)
                        }
                        override fun onLost(network: Network) = removeNetwork(network)
                    }
                }
                continuation.invokeOnCancellation { unregister() }
                try {
                    val request = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .build()
                    connectivity.registerNetworkCallback(request, callback)
                    registered.set(true)
                    if (unregisterRequested.get() || !continuation.isActive) unregister()
                } catch (_: Exception) {
                    registered.set(true)
                    unregister()
                    if (continuation.isActive && completionClaimed.compareAndSet(false, true)) continuation.resume(null)
                }
            }
        }

    @Suppress("DEPRECATION")
    private fun currentFrom(info: WifiInfo, links: LinkProperties, dataset: WifiAnalyzerState): CurrentWifi {
        val points = dataset.accessPoints
        val gateway = preferIpv4Address(links.routes.filter { it.isDefaultRoute }.mapNotNull { it.gateway?.hostAddress })
        val ssid = info.ssid?.trim('"')?.takeUnless { it.isBlank() || it == UNKNOWN_SSID_REDACTION }
        val bssid = info.bssid?.takeUnless { it == "02:00:00:00:00:00" }
        val security = if (Build.VERSION.SDK_INT >= 31) when (info.currentSecurityType) {
            WifiInfo.SECURITY_TYPE_OPEN -> WifiSecurity.OPEN
            WifiInfo.SECURITY_TYPE_WEP -> WifiSecurity.WEP
            WifiInfo.SECURITY_TYPE_PSK, WifiInfo.SECURITY_TYPE_EAP -> WifiSecurity.WPA2
            WifiInfo.SECURITY_TYPE_SAE, WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE,
            WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT -> WifiSecurity.WPA3
            WifiInfo.SECURITY_TYPE_OWE -> WifiSecurity.OWE
            else -> null
        } else points.firstOrNull { !bssid.isNullOrBlank() && it.bssid.equals(bssid, true) }?.security
        return CurrentWifi(ssid, bssid, info.rssi.takeIf { it != -127 }, info.frequency.takeIf { it > 0 },
            if (Build.VERSION.SDK_INT >= 30) when (info.wifiStandard) {
                ScanResult.WIFI_STANDARD_LEGACY -> "a/b/g"
                ScanResult.WIFI_STANDARD_11N -> "n"
                ScanResult.WIFI_STANDARD_11AC -> "ac"
                ScanResult.WIFI_STANDARD_11AX -> "ax"
                else -> when {
                    Build.VERSION.SDK_INT >= 33 && info.wifiStandard == ScanResult.WIFI_STANDARD_11BE -> "be"
                    Build.VERSION.SDK_INT >= 31 && info.wifiStandard == ScanResult.WIFI_STANDARD_11AD -> "ad"
                    else -> null
                }
            } else null, info.rxLinkSpeedMbps.takeIf { it > 0 }, info.txLinkSpeedMbps.takeIf { it > 0 },
            links.linkAddresses.firstOrNull { it.address is java.net.Inet4Address }?.address?.hostAddress,
            gateway, links.dnsServers.mapNotNull { it.hostAddress }, security,
            snapshotAccessPoints(points).takeIf { wifiDatasetAge(dataset.lastUpdatedElapsedMs, SystemClock.elapsedRealtime()).allowsRecommendation }?.firstOrNull {
                normalizeBssid(it.bssid) == normalizeBssid(bssid) && !bssid.isNullOrBlank() && it.frequencyMhz == info.frequency
            }?.widthMhz)
    }

    private fun appendHistory() {
        val sample = signalSampleForDataset(_state.value, selectedBssid, SystemClock.elapsedRealtime()) ?: return
        _history.update { appendSignalSample(it, sample) }
    }

    private data class ScanSnapshot(
        val points: List<AccessPoint>, val fresh: Boolean, val receivedElapsedMs: Long,
    )
}
