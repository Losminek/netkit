package cz.netkit.app.speed

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.netkit.app.BuildConfig
import cz.netkit.app.cellular.CellularMonitor
import cz.netkit.app.net.ActiveNetworkInfo
import cz.netkit.app.net.ActiveNetworkMonitor
import cz.netkit.app.net.readActiveNetworkInfo
import cz.netkit.app.wifi.live.WifiLinkMonitor
import cz.netkit.app.wifi.live.WifiLinkSnapshot
import cz.netkit.shared.speed.LIBRESPEED_PRESETS
import cz.netkit.shared.speed.LatencyStats
import cz.netkit.shared.speed.LibreSpeedServer
import cz.netkit.shared.speed.LibreSpeedUrlCheck
import cz.netkit.shared.speed.LibreSpeedUrlError
import cz.netkit.shared.speed.Ndt7Measurement
import cz.netkit.shared.speed.Ndt7ServerView
import cz.netkit.shared.speed.Ndt7Target
import cz.netkit.shared.speed.SPEED_DOWNLOAD_STREAMS
import cz.netkit.shared.speed.SPEED_IDLE_PINGS
import cz.netkit.shared.speed.SPEED_LOADED_PING_INTERVAL_MS
import cz.netkit.shared.speed.SPEED_PHASE_MS
import cz.netkit.shared.speed.SPEED_SAMPLE_MS
import cz.netkit.shared.speed.SPEED_UPLOAD_STREAMS
import cz.netkit.shared.speed.SpeedDataEstimate
import cz.netkit.shared.speed.SpeedEngine
import cz.netkit.shared.speed.SpeedRecord
import cz.netkit.shared.speed.SpeedStoreData
import cz.netkit.shared.speed.ThroughputSummary
import cz.netkit.shared.speed.ThroughputTrace
import cz.netkit.shared.speed.chartPoints
import cz.netkit.shared.speed.checkLibreSpeedUrl
import cz.netkit.shared.speed.detectWarmupMs
import cz.netkit.shared.speed.estimateSpeedTestData
import cz.netkit.shared.speed.latencyStats
import cz.netkit.shared.speed.median
import cz.netkit.shared.speed.ndt7ServerView
import cz.netkit.shared.speed.rateSeriesMbps
import cz.netkit.shared.speed.summarizeThroughput
import cz.netkit.shared.speed.withRecord
import cz.netkit.shared.cellular.CellRat
import cz.netkit.shared.cellular.CellularState
import cz.netkit.shared.cellular.primaryDbm
import cz.netkit.shared.net.NetKind
import java.io.File
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** How long the test waits for the default-network callback before refusing with NO_NETWORK. */
private const val NETWORK_SETTLE_MS = 2_000L
/** How long a Wi-Fi run waits for the Wi-Fi radio snapshot; without it the context is just "Wi-Fi". */
private const val WIFI_SETTLE_MS = 2_000L
/** How long a mobile-data run waits for the data SIM (operator, standard); without it the context is just "Mobilní data". */
private const val CELLULAR_SETTLE_MS = 2_000L
/** Spec: ≤ 10 text messages/s, so ~150 per 15 s direction; a flooding server cannot grow memory past this. */
private const val NDT7_MESSAGES_MAX = 500

/**
 * A selectable server: LibreSpeed (preset or custom base URL) or M-Lab NDT7 (located per run).
 * [id] is stable across list changes (a custom server loading later does not shift the selection).
 */
data class SpeedServerChoice(val engine: SpeedEngine, val name: String, val baseUrl: String?, val custom: Boolean = false) {
    val id: String get() = if (engine == SpeedEngine.NDT7) NDT7_CHOICE_ID else "librespeed:$baseUrl"
}

const val NDT7_CHOICE_ID = "ndt7"

enum class SpeedPhase { IDLE, PREPARING, LOCATING, LATENCY, DOWNLOAD, UPLOAD, DONE, FAILED, CANCELLED }

/**
 * Complete state of the speed-test screen.
 *
 * @property downloadPoints / [uploadPoints] chart points (Mbit/s, 1 s moving window, 250 ms slots).
 * @property download / [upload] final client-side results after warm-up trimming.
 * @property idle unloaded latency (LibreSpeed: HTTP RTT to `empty.php`; NDT7: server TCP MinRTT).
 * @property loadedDownload / [loadedUpload] latency while the direction was loaded (after warm-up).
 * @property ndt7Download / [ndt7Upload] the server's own view (NDT7 only).
 * @property ispText getIP.php summary = public IP (LibreSpeed, no ISP lookup); shown only, never stored or logged.
 * @property linkRxDuringMbps / [linkTxDuringMbps] median PHY rate during the direction (Wi-Fi only).
 * @property networkKind active network now (subtitle); [measuredKind] network of the shown run.
 * @property cellular data SIM at the start of a run on mobile data (operator, standard, signal).
 * @property meteredPrompt pending confirmation before a test on a metered network (O-3).
 * @property failure why the run stopped or a direction failed; [failureCode] = HTTP status.
 */
data class SpeedUiState(
    val choices: List<SpeedServerChoice> = emptyList(),
    val selectedId: String? = null,
    /** The store (consent, custom server, history) has been read; starting is gated on it. */
    val loaded: Boolean = false,
    val phase: SpeedPhase = SpeedPhase.IDLE,
    val phaseProgress: Float? = null,
    val liveMbps: Double? = null,
    val downloadPoints: List<Double?> = emptyList(),
    val uploadPoints: List<Double?> = emptyList(),
    val idle: LatencyStats? = null,
    val loadedDownload: LatencyStats? = null,
    val loadedUpload: LatencyStats? = null,
    val download: ThroughputSummary? = null,
    val upload: ThroughputSummary? = null,
    val ndt7Download: Ndt7ServerView? = null,
    val ndt7Upload: Ndt7ServerView? = null,
    val engine: SpeedEngine? = null,
    val serverLabel: String? = null,
    val serverHost: String? = null,
    val ispText: String? = null,
    val wifi: SpeedWifiContext? = null,
    val cellular: SpeedCellularContext? = null,
    val linkRxDuringMbps: Int? = null,
    val linkTxDuringMbps: Int? = null,
    val startedAtMs: Long? = null,
    val failure: SpeedFailureKind? = null,
    val failureCode: Int? = null,
    /** LibreSpeed streams that worked to the end per direction; fewer than planned = incomplete result. */
    val downloadStreams: DirectionStreams? = null,
    val uploadStreams: DirectionStreams? = null,
    val consentPending: Boolean = false,
    val networkKind: NetKind? = null,
    val measuredKind: NetKind? = null,
    val meteredPrompt: MeteredPrompt? = null,
    val history: List<SpeedRecord> = emptyList(),
    val storeCorrupt: Boolean = false,
) {
    val running: Boolean get() = phase in setOf(SpeedPhase.PREPARING, SpeedPhase.LOCATING, SpeedPhase.LATENCY, SpeedPhase.DOWNLOAD, SpeedPhase.UPLOAD)
    val selectedChoice: SpeedServerChoice? get() = choices.firstOrNull { it.id == selectedId } ?: choices.firstOrNull()
    /** Some parallel LibreSpeed streams gave up: the value is lower-bound and the record is partial. */
    val streamsDegraded: Boolean get() = listOfNotNull(downloadStreams, uploadStreams).any { it.working < it.planned }
}

/** Data SIM at the start of a test on mobile data; any field may be unknown. */
data class SpeedCellularContext(val operatorName: String?, val rat: CellRat, val signalDbm: Int?)

/** Data estimate shown before a full test on a metered network of [kind]; [network] is the one confirmed. */
data class MeteredPrompt(val estimate: SpeedDataEstimate, val kind: NetKind, internal val network: Network)

/**
 * Speed test (M2): own LibreSpeed-protocol client and NDT7 (M-Lab). See docs/funkce/speedtest.md.
 *
 * Lifecycle: the screen calls `setActive(false)` on pause/dispose, which cancels a running test
 * (every socket is closed via [CallRegistry]); nothing runs in background. All traffic is bound to
 * the active (default) `Network` (sockets + DNS): Wi-Fi, mobile data, ethernet or VPN (M4). On a
 * metered network the user confirms the estimated data first; losing or replacing the network
 * mid-test ends the run as NETWORK_LOST.
 */
class SpeedTestViewModel(application: Application) : AndroidViewModel(application) {
    private val connectivity = application.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = application.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val store = SpeedStore(File(application.noBackupFilesDir, "speedtest.json"))
    private val userAgent = "NetKit/${BuildConfig.VERSION_NAME} (Android)"
    private val baseClient by lazy { speedHttpClient() }

    private val _state = MutableStateFlow(SpeedUiState(choices = choices(null)))
    val state: StateFlow<SpeedUiState> = _state.asStateFlow()

    private var data = SpeedStoreData()
    private var run: Job? = null
    private val monitor = ActiveNetworkMonitor(connectivity)
    @Volatile private var active: ActiveNetworkInfo? = null

    init {
        viewModelScope.launch {
            monitor.observe().collect { info ->
                active = info
                _state.update { state ->
                    // A changed network invalidates a pending data confirmation.
                    val prompt = state.meteredPrompt?.takeIf { info != null && it.network == info.network && info.metered }
                    state.copy(networkKind = info?.kind, meteredPrompt = prompt)
                }
            }
        }
        viewModelScope.launch {
            val read = store.read()
            data = read.data
            _state.update { it.copy(choices = choices(data.customUrl), history = data.records, storeCorrupt = read.corrupt, loaded = true) }
        }
    }

    private fun choices(custom: String?): List<SpeedServerChoice> = buildList {
        LIBRESPEED_PRESETS.forEach { add(SpeedServerChoice(SpeedEngine.LIBRESPEED, it.name, it.baseUrl)) }
        custom?.let { url ->
            (checkLibreSpeedUrl(url) as? LibreSpeedUrlCheck.Valid)?.server
                ?.takeIf { s -> LIBRESPEED_PRESETS.none { it.baseUrl == s.baseUrl } }
                ?.let { add(SpeedServerChoice(SpeedEngine.LIBRESPEED, it.name, it.baseUrl, custom = true)) }
        }
        add(SpeedServerChoice(SpeedEngine.NDT7, "M-Lab NDT7", null))
    }

    /** `false` (screen paused/left) cancels a running test immediately. */
    fun setActive(active: Boolean) {
        if (!active) stop()
    }

    fun select(id: String) {
        if (_state.value.running) return
        _state.update { state -> if (state.choices.any { it.id == id }) state.copy(selectedId = id) else state }
    }

    /** Validates, stores and selects a custom LibreSpeed base URL; returns the error or `null`. */
    fun setCustomServer(input: String): LibreSpeedUrlError? {
        val server = when (val check = checkLibreSpeedUrl(input)) {
            is LibreSpeedUrlCheck.Invalid -> return check.error
            is LibreSpeedUrlCheck.Valid -> check.server
        }
        if (_state.value.running) return null
        val list = choices(server.baseUrl)
        val id = list.firstOrNull { it.baseUrl == server.baseUrl }?.id
        _state.update { it.copy(choices = list, selectedId = id) }
        persist { it.copy(customUrl = server.baseUrl) }
        return null
    }

    fun removeCustomServer() {
        if (_state.value.running) return
        _state.update { state ->
            val list = choices(null)
            state.copy(choices = list, selectedId = state.selectedId?.takeIf { id -> list.any { it.id == id } })
        }
        persist { it.copy(customUrl = null) }
    }

    fun start() = start(confirmedMetered = null)

    private fun start(confirmedMetered: Network?) {
        val current = _state.value
        // Before the store is read the consent and custom server are unknown.
        if (current.running || !current.loaded) return
        val choice = current.selectedChoice ?: return
        if (choice.engine == SpeedEngine.NDT7 && data.ndt7ConsentMs == null) {
            _state.update { it.copy(consentPending = true) }
            return
        }
        // O-3: always the full test, but on a metered network only after an explicit confirmation.
        val network = active ?: connectivity.readActiveNetworkInfo()
        if (network != null && network.metered && network.network != confirmedMetered) {
            val estimate = estimateSpeedTestData(choice.engine, data.records, network.kind)
            _state.update { it.copy(meteredPrompt = MeteredPrompt(estimate, network.kind, network.network)) }
            return
        }
        launchRun(choice, confirmedMetered)
    }

    /** The user accepted the data estimate for this network: run the full test once. */
    fun acceptMetered() {
        val prompt = _state.value.meteredPrompt ?: return
        _state.update { it.copy(meteredPrompt = null) }
        start(confirmedMetered = prompt.network)
    }

    fun dismissMetered() = _state.update { it.copy(meteredPrompt = null) }

    /** The user read the M-Lab open-data notice: remember it and start. */
    fun acceptNdt7Notice() {
        val now = System.currentTimeMillis()
        data = data.copy(ndt7ConsentMs = now)
        persist { it.copy(ndt7ConsentMs = now) }
        _state.update { it.copy(consentPending = false) }
        start()
    }

    fun dismissNdt7Notice() = _state.update { it.copy(consentPending = false) }

    fun stop() {
        run?.cancel()
    }

    fun clearHistory() {
        if (_state.value.running) return
        _state.update { it.copy(history = emptyList()) }
        persist { it.copy(records = emptyList()) }
    }

    override fun onCleared() {
        run?.cancel()
        super.onCleared()
    }

    private fun persist(transform: (SpeedStoreData) -> SpeedStoreData) {
        viewModelScope.launch {
            try {
                data = store.update(transform)
                _state.update { it.copy(history = data.records, storeCorrupt = false) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                _state.update { it.copy(storeCorrupt = true) }
            }
        }
    }

    // --- Run --------------------------------------------------------------------------------

    private class RunFailure(val kind: SpeedFailureKind, val code: Int?) : Exception()

    private fun launchRun(choice: SpeedServerChoice, confirmedMetered: Network?) {
        _state.update {
            it.copy(phase = SpeedPhase.PREPARING, phaseProgress = null, liveMbps = null, downloadPoints = emptyList(),
                uploadPoints = emptyList(), idle = null, loadedDownload = null, loadedUpload = null, download = null,
                upload = null, ndt7Download = null, ndt7Upload = null, engine = choice.engine,
                serverLabel = if (choice.engine == SpeedEngine.LIBRESPEED) choice.name else null,
                serverHost = null, ispText = null, wifi = null, cellular = null, measuredKind = null, linkRxDuringMbps = null, linkTxDuringMbps = null,
                startedAtMs = System.currentTimeMillis(), failure = null, failureCode = null,
                downloadStreams = null, uploadStreams = null)
        }
        run = viewModelScope.launch {
            var completed = false
            try {
                val network = withTimeoutOrNull(NETWORK_SETTLE_MS) { monitor.observe().filterNotNull().first() }
                    ?: throw RunFailure(SpeedFailureKind.NO_NETWORK, null)
                // The data confirmation was for one network; a switch to another metered one needs a new one.
                if (network.metered && network.network != confirmedMetered) throw RunFailure(SpeedFailureKind.NETWORK_LOST, null)
                _state.update { it.copy(measuredKind = network.kind) }
                // Radio context only on a plain Wi-Fi default (not through a VPN tunnel).
                val snapshot = if (network.kind == NetKind.WIFI) withTimeoutOrNull(WIFI_SETTLE_MS) {
                    WifiLinkMonitor(connectivity).observe().filterNotNull().first { it.network == network.network }
                } else null
                if (snapshot != null) {
                    val wifi = withContext(Dispatchers.IO) { readSpeedWifiContext(snapshot, wifiManager) }
                    _state.update { it.copy(wifi = wifi) }
                }
                // Operator and standard of the data SIM (point 9 of M4 A3), only on plain mobile data.
                // The first state lists SIMs before the signal and display-info callbacks arrive; wait for
                // them within the limit and fall back to the latest state seen.
                val cellularState = if (network.kind == NetKind.CELLULAR) {
                    var latest: CellularState? = null
                    withTimeoutOrNull(CELLULAR_SETTLE_MS) {
                        CellularMonitor(getApplication()).observe().onEach { latest = it }.first { state ->
                            state.sims.any { it.isDataSim && it.signal != null && it.rat != CellRat.UNKNOWN }
                        }
                    } ?: latest
                } else null
                cellularState?.sims?.firstOrNull { it.isDataSim }?.let { sim ->
                    _state.update { it.copy(cellular = SpeedCellularContext(sim.operatorName, sim.rat, primaryDbm(sim.signal))) }
                }
                val client = baseClient.boundTo(network.network)
                coroutineScope {
                    // Losing the measured network mid-test is reported as such, not as a socket error.
                    val watcher = launch { watchNetwork(network.network, network.metered, this@coroutineScope) }
                    try {
                        when (choice.engine) {
                            SpeedEngine.LIBRESPEED -> runLibreSpeed(LibreSpeedServer(choice.name, choice.baseUrl!!, hostOf(choice.baseUrl)),
                                client, snapshot)
                            SpeedEngine.NDT7 -> runNdt7(client, snapshot)
                        }
                    } finally {
                        watcher.cancel()
                    }
                }
                completed = true
                _state.update { it.copy(phase = SpeedPhase.DONE, phaseProgress = null, liveMbps = null) }
            } catch (failure: RunFailure) {
                _state.update { it.copy(phase = SpeedPhase.FAILED, failure = failure.kind, failureCode = failure.code, phaseProgress = null, liveMbps = null) }
            } catch (failure: SpeedTestFailure) {
                val kind = if (networkLost) SpeedFailureKind.NETWORK_LOST else failure.kind
                _state.update { it.copy(phase = SpeedPhase.FAILED, failure = kind, failureCode = failure.httpCode, phaseProgress = null, liveMbps = null) }
            } catch (cancel: CancellationException) {
                val lost = networkLost
                _state.update {
                    if (lost) it.copy(phase = SpeedPhase.FAILED, failure = SpeedFailureKind.NETWORK_LOST, phaseProgress = null, liveMbps = null)
                    else it.copy(phase = SpeedPhase.CANCELLED, phaseProgress = null, liveMbps = null)
                }
                if (!lost) throw cancel
            } catch (unexpected: Exception) {
                // E.g. IllegalArgumentException from OkHttp for a malformed URL: a failed test, never a crash.
                _state.update { it.copy(phase = SpeedPhase.FAILED, failure = SpeedFailureKind.PROTOCOL, phaseProgress = null, liveMbps = null) }
            } finally {
                networkLost = false
                // Do not keep idle keep-alive connections to the test server after the run.
                viewModelScope.launch(Dispatchers.IO) { baseClient.connectionPool.evictAll() }
            }
            saveRecord(partial = !completed)
        }
    }

    @Volatile private var networkLost = false

    /**
     * Cancels [scope] when the measured network stops being the default one (lost, or replaced by
     * Wi-Fi, mobile data or a VPN), including a change between choosing the network and this
     * callback's first value. The same network turning metered (e.g. a VPN moving from Wi-Fi to
     * mobile data) also stops a run that started without the data confirmation.
     */
    private suspend fun watchNetwork(measured: Network, startedMetered: Boolean, scope: CoroutineScope) {
        monitor.observe().collect { current ->
            if (current?.network != measured || (current.metered && !startedMetered)) {
                networkLost = true
                scope.coroutineContext[Job]?.cancel()
            }
        }
    }

    private fun hostOf(baseUrl: String): String = baseUrl.removePrefix("https://").substringBefore('/')

    /** Wi-Fi radio samples (1 Hz) while a direction runs; nothing without Wi-Fi. */
    private suspend fun sampleLink(snapshot: WifiLinkSnapshot?, rx: Boolean, out: MutableList<Int>) {
        if (snapshot == null) return
        while (true) {
            val reading = withContext(Dispatchers.IO) { readSpeedWifiContext(snapshot, wifiManager) }
            (if (rx) reading.rxLinkMbps else reading.txLinkMbps)?.let(out::add)
            delay(1_000)
        }
    }

    /** Samples [meter] every [SPEED_SAMPLE_MS] into [trace] and publishes the chart. */
    private suspend fun sampleDirection(meter: DirectionMeter, trace: ThroughputTrace, download: Boolean, durationMs: Long) {
        while (true) {
            val started = meter.startedAtMs
            if (started >= 0) {
                val finished = meter.finishedAtMs
                val now = if (finished >= 0) finished else monotonicMs()
                trace.add(now - started, meter.bytes.get())
                publishDirection(trace, download, durationMs)
                if (finished >= 0) return
            }
            delay(SPEED_SAMPLE_MS)
        }
    }

    private fun publishDirection(trace: ThroughputTrace, download: Boolean, durationMs: Long) {
        val samples = trace.samples
        val points = chartPoints(samples)
        val live = rateSeriesMbps(samples).lastOrNull()
        val progress = samples.lastOrNull()?.let { (it.elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f) }
        _state.update {
            if (download) it.copy(downloadPoints = points, liveMbps = live, phaseProgress = progress)
            else it.copy(uploadPoints = points, liveMbps = live, phaseProgress = progress)
        }
    }

    /** Runs one direction with sampling; returns the trace (final sample included). */
    private suspend fun measure(
        snapshot: WifiLinkSnapshot?,
        download: Boolean,
        durationMs: Long,
        body: suspend (DirectionMeter) -> Unit,
    ): Pair<ThroughputTrace, Int?> = coroutineScope {
        val meter = DirectionMeter()
        val trace = ThroughputTrace()
        val link = Collections.synchronizedList(ArrayList<Int>())
        val sampler = launch { sampleDirection(meter, trace, download, durationMs) }
        val linkSampler = launch { sampleLink(snapshot, rx = download, out = link) }
        try {
            body(meter)
        } finally {
            linkSampler.cancel()
        }
        withTimeoutOrNull(2 * SPEED_SAMPLE_MS) { sampler.join() }
        sampler.cancel()
        meter.finishedAtMs.takeIf { it >= 0 }?.let { trace.add(it - meter.startedAtMs, meter.bytes.get()) }
        publishDirection(trace, download, durationMs)
        trace to median(link.map { it.toDouble() })?.toInt()
    }

    private suspend fun runLibreSpeed(server: LibreSpeedServer, client: okhttp3.OkHttpClient, snapshot: WifiLinkSnapshot?) {
        val ls = LibreSpeedClient(client, server.baseUrl, userAgent)
        try {
            runLibreSpeed(ls, server, snapshot)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { ls.release() }
        }
    }

    private suspend fun runLibreSpeed(ls: LibreSpeedClient, server: LibreSpeedServer, snapshot: WifiLinkSnapshot?) = coroutineScope {
        _state.update { it.copy(phase = SpeedPhase.LATENCY, serverHost = server.host) }
        val idle = ls.idleLatency(SPEED_IDLE_PINGS, gapMs = 100)
        _state.update { it.copy(idle = latencyStats(idle)) }
        // After the idle probes, so the extra request does not disturb the unloaded latency.
        val isp = async { ls.ipInfo() }
        isp.await()?.let { info -> _state.update { it.copy(ispText = info.summary) } }

        _state.update { it.copy(phase = SpeedPhase.DOWNLOAD, phaseProgress = 0f) }
        val downLoaded = Collections.synchronizedList(ArrayList<Pair<Long, Double?>>())
        val (downTrace, rxLink) = measure(snapshot, download = true, durationMs = SPEED_PHASE_MS) { meter ->
            val streams = ls.download(SPEED_DOWNLOAD_STREAMS, SPEED_PHASE_MS, meter) { registry, start ->
                ls.loadedProbeLoop(registry, start, SPEED_LOADED_PING_INTERVAL_MS) { t, ms -> downLoaded.add(t to ms) }
            }
            _state.update { it.copy(downloadStreams = streams) }
        }
        val downWarmup = detectWarmupMs(downTrace.samples)
        _state.update {
            it.copy(download = summarizeThroughput(downTrace.samples), linkRxDuringMbps = rxLink,
                loadedDownload = loadedStats(downLoaded, downWarmup))
        }

        _state.update { it.copy(phase = SpeedPhase.UPLOAD, phaseProgress = 0f, liveMbps = null) }
        val upLoaded = Collections.synchronizedList(ArrayList<Pair<Long, Double?>>())
        val (upTrace, txLink) = measure(snapshot, download = false, durationMs = SPEED_PHASE_MS) { meter ->
            val streams = ls.upload(SPEED_UPLOAD_STREAMS, SPEED_PHASE_MS, meter) { registry, start ->
                ls.loadedProbeLoop(registry, start, SPEED_LOADED_PING_INTERVAL_MS) { t, ms -> upLoaded.add(t to ms) }
            }
            _state.update { it.copy(uploadStreams = streams) }
        }
        val upWarmup = detectWarmupMs(upTrace.samples)
        _state.update {
            it.copy(upload = summarizeThroughput(upTrace.samples), linkTxDuringMbps = txLink,
                loadedUpload = loadedStats(upLoaded, upWarmup))
        }
    }

    private fun loadedStats(samples: List<Pair<Long, Double?>>, warmupMs: Long): LatencyStats? {
        val kept = synchronized(samples) { samples.filter { it.first >= warmupMs }.map { it.second } }
        return if (kept.isEmpty()) null else latencyStats(kept)
    }

    private suspend fun runNdt7(client: okhttp3.OkHttpClient, snapshot: WifiLinkSnapshot?) {
        val ndt = Ndt7Client(client, userAgent, BuildConfig.VERSION_NAME)
        _state.update { it.copy(phase = SpeedPhase.LOCATING) }
        val targets = ndt.locate()
        fun show(target: Ndt7Target) {
            val label = listOfNotNull(target.machine.substringBefore('.'), listOfNotNull(target.city, target.country)
                .joinToString(", ").ifEmpty { null }).joinToString(" · ")
            _state.update { it.copy(serverLabel = label, serverHost = target.host) }
        }
        show(targets.first())
        var target = targets.first()

        _state.update { it.copy(phase = SpeedPhase.DOWNLOAD, phaseProgress = 0f) }
        val downMessages = Collections.synchronizedList(ArrayList<Pair<Long, Ndt7Measurement>>())
        val (downTrace, rxLink) = measure(snapshot, download = true, durationMs = SPEED_PHASE_MS) { meter ->
            // A server that refuses the handshake is replaced by the next located one.
            target = ndt.firstReachable(targets, meter) { candidate ->
                show(candidate)
                ndt.download(candidate.downloadUrl, meter) { t, m ->
                    if (downMessages.size < NDT7_MESSAGES_MAX) downMessages.add(t to m) else downMessages[downMessages.size - 1] = t to m
                }
            }.first
        }
        val downWarmup = detectWarmupMs(downTrace.samples)
        val downLast = synchronized(downMessages) { downMessages.lastOrNull()?.second }
        val downView = downLast?.let { ndt7ServerView(it, download = true) }
        val rtts = synchronized(downMessages) {
            downMessages.filter { it.first >= downWarmup }.mapNotNull { it.second.tcpInfo?.rttUs?.takeIf { v -> v.isFinite() && v >= 0 }?.div(1_000.0) }
        }
        val minRtt = downView?.minRttMs
        _state.update {
            it.copy(download = summarizeThroughput(downTrace.samples), ndt7Download = downView, linkRxDuringMbps = rxLink,
                idle = minRtt?.let { v -> latencyStats(listOf(v)) },
                loadedDownload = if (rtts.isEmpty()) null else latencyStats(rtts))
        }

        _state.update { it.copy(phase = SpeedPhase.UPLOAD, phaseProgress = 0f, liveMbps = null) }
        val upMessages = Collections.synchronizedList(ArrayList<Pair<Long, Ndt7Measurement>>())
        val (upTrace, txLink) = measure(snapshot, download = false, durationMs = SPEED_PHASE_MS) { meter ->
            ndt.firstReachable(listOf(target) + targets.filter { it != target }, meter) { candidate ->
                show(candidate)
                ndt.upload(candidate.uploadUrl, meter) { t, m ->
                    if (upMessages.size < NDT7_MESSAGES_MAX) upMessages.add(t to m) else upMessages[upMessages.size - 1] = t to m
                }
            }
        }
        val upLast = synchronized(upMessages) { upMessages.lastOrNull()?.second }
        _state.update {
            it.copy(upload = summarizeThroughput(upTrace.samples), ndt7Upload = upLast?.let { m -> ndt7ServerView(m, download = false) },
                linkTxDuringMbps = txLink)
        }
    }

    private fun saveRecord(partial: Boolean) {
        val s = _state.value
        val download = s.download ?: return
        if (s.phase == SpeedPhase.CANCELLED) return
        val record = SpeedRecord(
            timestampMs = s.startedAtMs ?: System.currentTimeMillis(),
            engine = s.engine ?: return,
            serverName = (s.serverLabel ?: s.serverHost ?: "?").take(128),
            downloadMbps = download.mbps,
            uploadMbps = s.upload?.mbps,
            idleLatencyMs = s.idle?.medianMs,
            idleJitterMs = s.idle?.jitterMs,
            loadedDownloadMs = s.loadedDownload?.medianMs,
            loadedUploadMs = s.loadedUpload?.medianMs,
            serverDownloadMbps = s.ndt7Download?.appMbps,
            serverUploadMbps = s.ndt7Upload?.appMbps,
            retransPercent = s.ndt7Download?.retransPercent?.coerceIn(0.0, 100.0),
            ssid = s.wifi?.ssid?.take(128),
            frequencyMhz = s.wifi?.frequencyMhz,
            rssiDbm = s.wifi?.rssiDbm,
            rxLinkMbps = s.wifi?.rxLinkMbps,
            txLinkMbps = s.wifi?.txLinkMbps,
            partial = partial || s.upload == null || s.streamsDegraded,
            downloadStreams = s.downloadStreams?.working,
            uploadStreams = s.uploadStreams?.working,
            plannedDownloadStreams = s.downloadStreams?.planned,
            plannedUploadStreams = s.uploadStreams?.planned,
            networkKind = s.measuredKind,
        )
        persist { it.withRecord(record) }
    }
}
