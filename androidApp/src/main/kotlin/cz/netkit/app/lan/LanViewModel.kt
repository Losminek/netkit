package cz.netkit.app.lan

import android.app.Application
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.netkit.app.R
import cz.netkit.app.net.ActiveNetworkInfo
import cz.netkit.app.net.ActiveNetworkMonitor
import cz.netkit.app.net.netKindLabel
import cz.netkit.shared.lan.*
import cz.netkit.shared.net.NetKind
import cz.netkit.shared.net.ToolRouteDecision
import cz.netkit.shared.net.chooseToolRoute
import java.net.Inet4Address
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

enum class LanTool { DISCOVERY, PING, TRACE, PORTS, DNS, MDNS }
internal data class LanProbeSettings(val timeout: Int = 1000, val parallel: Int = 1, val error: Int? = null)
internal fun validatedProbeSettings(tool: LanTool, timeoutText: String, parallelText: String): LanProbeSettings {
    val timeout = if(tool in listOf(LanTool.DNS,LanTool.MDNS)) 1000 else
        timeoutText.toIntOrNull()?.takeIf { it in 100..5000 } ?: return LanProbeSettings(error=R.string.lan_invalid_timeout)
    val parallel = if(tool !in listOf(LanTool.DISCOVERY,LanTool.PORTS)) 1 else
        parallelText.toIntOrNull()?.takeIf { it in 1..64 } ?: return LanProbeSettings(error=R.string.lan_invalid_concurrency)
    return LanProbeSettings(timeout,parallel)
}
data class LanUiState(
    val runStatus: LanRunStatus = LanRunStatus.IDLE, val confirmationReasons: Set<TargetConfirmationReason> = emptySet(),
    val exportFormat: LanExport = LanExport.TEXT,
    val tool: LanTool = LanTool.DISCOVERY,
    val cidr: String = "", val host: String = "", val ports: String = "22,80,443,445,3389,8080",
    val timeout: String = "1000", val concurrency: String = "32", val serviceType: String = "_http._tcp.",
    val probeCounts: ProbeCounts = ProbeCounts(), val traceRouteWarning: Boolean = false,
    val readBanner: Boolean = false, val busy: Boolean = false, val progress: Int = 0, val total: Int = 0,
    val results: List<LanResult> = emptyList(), val samples: List<Double?> = emptyList(),
    val sent: Int = 0, val received: Int = 0, val error: Int? = null, val notice: Int? = null,
    val confirmation: Boolean = false, val confirmationTarget: String = "", val wifiAddress: String? = null,
    /** Network the next run of the visible tool would use (`null` = no usable network). */
    val predictedRoute: NetKind? = null,
    /** Network of the current or last run; cleared when the target or tool changes. */
    val runRoute: NetKind? = null,
) {
    val routeKind: NetKind? get() = runRoute ?: predictedRoute
}

/** A run that cannot start on the current networks; [resource] explains why. */
private class ToolRefused(val resource: Int) : Exception()
class LanViewModel(application: Application) : AndroidViewModel(application) {
    private val backend = LanBackend(application)
    private val mutable = MutableStateFlow(LanUiState())
    val state = mutable.asStateFlow()
    private var active: Job? = null
    private val generation = AtomicInteger()
    /**
     * [wifi] is set for the Wi-Fi-only tools (discovery, mDNS); [route] for DNS, ping, traceroute and
     * ports, where [target] pins the numeric address to that route.
     */
    private data class PreparedRequest(val snapshot: LanUiState, val wifi: LanWifi?, val route: ToolRoute?, val timeout: Int,
        val parallel: Int, val subnet: Ipv4Subnet?, val ports: List<Int>, val target: PinnedLanHost<ToolRoute>?, val generation: Int, val traceRouteWarning: Boolean = false)
    private val pending = AtomicReference<PreparedRequest?>()
    private var networkSnapshot: LanWifi? = null
    @Volatile private var activeSnapshot: ActiveNetworkInfo? = null
    private var networkInitialized = false
    private var pendingExport: Pair<LanExport, List<LanResult>>? = null
    init {
        viewModelScope.launch {
            try {
                backend.observeWifi().collect { wifi -> networkChanged(wifi) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { fail(R.string.lan_no_wifi) }
        }
        viewModelScope.launch {
            ActiveNetworkMonitor(application.getSystemService(ConnectivityManager::class.java)).observe().collect { active ->
                activeSnapshot = active
                predictRoute()
            }
        }
    }
    /** Updates the "measured over" tag; hostnames are resolved only at start, so they show the active network. */
    private fun predictRoute() {
        val wifi = networkSnapshot
        val active = activeSnapshot
        mutable.update { current ->
            val host = current.host.trim()
            val kind = when (current.tool) {
                // Wi-Fi-only tools always name Wi-Fi; the screen marks it when Wi-Fi is missing.
                LanTool.DISCOVERY, LanTool.MDNS -> NetKind.WIFI
                LanTool.DNS -> if (wifi != null) NetKind.WIFI else active?.kind
                else -> if (ipv4Number(host) != null) (routeFor(host, wifi, active) as? RouteChoice.Selected)?.route?.kind ?: active?.kind
                    else active?.kind ?: if (wifi != null) NetKind.WIFI else null
            }
            current.copy(predictedRoute = kind)
        }
    }
    private sealed interface RouteChoice {
        data class Selected(val route: ToolRoute) : RouteChoice
        data class Refused(val resource: Int) : RouteChoice
    }
    /** See [chooseToolRoute]: public targets over the active network, local ones over Wi-Fi or VPN. */
    private fun routeFor(address: String, wifi: LanWifi?, active: ActiveNetworkInfo?): RouteChoice =
        when (chooseToolRoute(address, wifi != null, active?.kind, active?.viaVpn == true, wifi?.subnet?.contains(address) == true)) {
            ToolRouteDecision.WIFI -> wifi?.let { RouteChoice.Selected(ToolRoute.wifi(it)) } ?: RouteChoice.Refused(R.string.lan_no_network)
            ToolRouteDecision.ACTIVE -> active?.let { RouteChoice.Selected(ToolRoute.active(it, wifi)) } ?: RouteChoice.Refused(R.string.lan_no_network)
            ToolRouteDecision.LOCAL_NEEDS_WIFI_OR_VPN -> RouteChoice.Refused(R.string.lan_local_needs_wifi)
            ToolRouteDecision.NO_NETWORK -> RouteChoice.Refused(R.string.lan_no_network)
        }
    /** DNS lookups keep using the Wi-Fi resolver when there is one, otherwise the active network. */
    private fun dnsRoute(wifi: LanWifi?, active: ActiveNetworkInfo?): ToolRoute? =
        wifi?.let(ToolRoute::wifi) ?: active?.let { ToolRoute.active(it, null) }
    private fun failure(error: Exception): Int = when (error.message) {
        "WIFI_LOST" -> R.string.lan_no_wifi
        "NETWORK_LOST" -> R.string.lan_network_changed
        else -> R.string.lan_operation_failed
    }
    private fun networkChanged(wifi: LanWifi?) {
        if (networkInitialized && networkSnapshot == wifi) return
        if (!networkInitialized) {
            networkInitialized = true
            networkSnapshot = wifi
            mutable.update { old -> old.copy(wifiAddress=wifi?.address,
                cidr=old.cidr.ifEmpty { wifi?.let { selected -> Ipv4Subnet.parse("${selected.address}/${maxOf(selected.prefix,24)}").toString() }.orEmpty() }) }
            predictRoute()
            return
        }
        networkSnapshot = wifi
        cancelActive()
        mutable.update { it.copy(wifiAddress=wifi?.address, cidr=wifi?.let { selected -> Ipv4Subnet.parse("${selected.address}/${maxOf(selected.prefix,24)}").toString() }.orEmpty()) }
        predictRoute()
    }
    fun refreshNetwork() {
        viewModelScope.launch {
            val wifi = withContext(Dispatchers.IO) { backend.wifi() }
            networkChanged(wifi)
        }
    }
    fun tool(tool: LanTool) { cancelActive(); mutable.update { it.copy(tool=tool,runStatus=LanRunStatus.IDLE,results=emptyList(),samples=emptyList(),error=null,notice=null,confirmation=false,progress=0,total=0,runRoute=null) }; predictRoute() }
    fun cidr(value: String) { pending.set(null); mutable.update { it.copy(cidr=value,confirmation=false) } }
    fun host(value: String) { pending.set(null); mutable.update { it.copy(host=value,confirmation=false,runRoute=if(it.busy) it.runRoute else null) }; predictRoute() }
    fun ports(value: String) { pending.set(null); mutable.update { it.copy(ports=value,confirmation=false) } }
    fun timeout(value: String) { pending.set(null); mutable.update { it.copy(timeout=value,confirmation=false) } }
    fun concurrency(value: String) { pending.set(null); mutable.update { it.copy(concurrency=value,confirmation=false) } }
    fun serviceType(value: String) { pending.set(null); mutable.update { it.copy(serviceType=value,confirmation=false) } }
    fun banner(value: Boolean) { pending.set(null); mutable.update { it.copy(readBanner=value,confirmation=false) } }
    fun preset() { ports(commonPorts.joinToString(",")) }
    fun dismissConfirmation() { pending.set(null); mutable.update { it.copy(confirmation=false) } }
    fun selectHost(address: String) { host(address); tool(LanTool.PING) }
    fun cancelActive() { pending.set(null); generation.incrementAndGet(); active?.cancel(); active=null; mutable.update { it.copy(busy=false,confirmation=false,runStatus=if(it.busy) LanRunStatus.CANCELLED else it.runStatus) } }
    private fun fail(resource: Int) { mutable.update { it.copy(error=resource,busy=false,confirmation=false,runStatus=LanRunStatus.FAILED) } }
    private suspend fun publish(runId: Int, change: (LanUiState)->LanUiState) = withContext(Dispatchers.Main.immediate) {
        currentCoroutineContext().ensureActive()
        if(generation.get() == runId) mutable.update(change)
    }
    private suspend fun finishRun(runId: Int) = withContext(NonCancellable+Dispatchers.Main.immediate) {
        if(generation.get() == runId) mutable.update { it.copy(busy=false,runStatus=if(it.runStatus == LanRunStatus.RUNNING) LanRunStatus.FINISHED else it.runStatus) }
    }
    private suspend fun failFor(runId: Int,resource: Int) = publish(runId) { it.copy(error=resource,busy=false,confirmation=false,runStatus=LanRunStatus.FAILED) }
    fun exportFailed() { mutable.update { it.copy(error=R.string.lan_export_failed) } }
    fun exportIpcFailed() { mutable.update { it.copy(error=R.string.lan_export_ipc_failed) } }
    fun exportFormat(value: LanExport) { mutable.update { it.copy(exportFormat=value) } }
    fun prepareExport() { pendingExport = state.value.exportFormat to state.value.results.toList() }
    fun cancelExport() { pendingExport = null }
    fun saveExport(uri: Uri?) {
        val snapshot = pendingExport
        pendingExport = null
        if (uri == null) return
        viewModelScope.launch(Dispatchers.IO) {
            var saved = false
            try {
                requireNotNull(snapshot)
                val bytes = lanExportFileBytes(exportLan(snapshot.second, snapshot.first))
                runInterruptible {
                    getApplication<Application>().contentResolver.openOutputStream(uri,"wt")?.use { it.write(bytes) }
                        ?: error("Export output unavailable")
                }
                saved = true
                withContext(Dispatchers.Main.immediate) { mutable.update { it.copy(notice=R.string.lan_export_saved,error=null) } }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { withContext(Dispatchers.Main.immediate) { exportFailed() } }
            finally {
                if (!saved) withContext(NonCancellable + Dispatchers.IO) {
                    // The URI was just created by CreateDocument; never delete an existing user document.
                    try { DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, uri) }
                    catch (_: Exception) { /* Provider may not support deletion; failure remains visible. */ }
                }
            }
        }
    }

    fun start(confirmed: Boolean = false) {
        if (state.value.busy) return
        if (confirmed) { acceptPending(); return }
        pending.set(null)
        val snapshot = state.value
        val settings = validatedProbeSettings(snapshot.tool,snapshot.timeout,snapshot.concurrency)
        settings.error?.let { return fail(it) }
        val timeout=settings.timeout
        val parallel=settings.parallel
        val subnet = if (snapshot.tool == LanTool.DISCOVERY) Ipv4Subnet.parse(snapshot.cidr) ?: return fail(R.string.lan_invalid_cidr) else null
        if (subnet != null && subnet.hostCount > 4096) return fail(R.string.lan_host_limit)
        val portList = if (snapshot.tool == LanTool.PORTS) parsePorts(snapshot.ports) ?: return fail(R.string.lan_invalid_ports) else emptyList()
        val host = snapshot.host.trim()
        if (snapshot.tool in listOf(LanTool.PING,LanTool.TRACE,LanTool.PORTS,LanTool.DNS) &&
            !validLanHost(host)) return fail(R.string.lan_invalid_host)
        if (snapshot.tool == LanTool.MDNS && !Regex("_[A-Za-z0-9-]{1,62}\\._(?:tcp|udp)\\.?").matches(snapshot.serviceType)) return fail(R.string.lan_invalid_service)
        // Hostnames are resolved before the final address confirmation below; no probe yet.
        val runGeneration = generation.incrementAndGet()
        mutable.update { it.copy(busy=true,runStatus=LanRunStatus.RUNNING,error=null,notice=null,confirmation=false,results=emptyList(),samples=emptyList(),sent=0,received=0,progress=0,total=0,probeCounts=ProbeCounts(),traceRouteWarning=false,runRoute=null) }
        active = viewModelScope.launch(Dispatchers.IO) {
            try {
                val wifi = backend.wifi()
                val activeNetwork = backend.activeNetwork()
                currentCoroutineContext().ensureActive()
                publish(runGeneration) { it.copy(wifiAddress=wifi?.address) }
                val wifiOnly = snapshot.tool in listOf(LanTool.DISCOVERY,LanTool.MDNS)
                if (wifiOnly && wifi == null) { failFor(runGeneration,R.string.lan_no_wifi); return@launch }
                val target = if (snapshot.tool in listOf(LanTool.PING,LanTool.TRACE,LanTool.PORTS)) {
                    val address = resolveTarget(host,wifi,activeNetwork)
                    when (val choice = routeFor(address,wifi,activeNetwork)) {
                        is RouteChoice.Selected -> PinnedLanHost(choice.route,address)
                        is RouteChoice.Refused -> throw ToolRefused(choice.resource)
                    }
                } else null
                val route = when (snapshot.tool) {
                    LanTool.DNS -> dnsRoute(wifi,activeNetwork) ?: throw ToolRefused(R.string.lan_no_network)
                    else -> target?.network
                }
                currentCoroutineContext().ensureActive()
                route?.let { selected -> publish(runGeneration) { it.copy(runRoute=selected.kind) } }
                val routeWarning = snapshot.tool == LanTool.TRACE && route != null && backend.traceNeedsConfirmation(route)
                val request = PreparedRequest(snapshot,if (wifiOnly) wifi else null,route,timeout,parallel,subnet,portList,target,runGeneration,routeWarning)
                val reasons = when {
                    subnet != null -> targetConfirmationReasons(subnet,wifi?.subnet,portList.size)
                    target != null -> targetConfirmationReasons(Ipv4Subnet.parse("${target.address}/32")!!,target.network.wifi?.subnet,portList.size)
                        // Without Wi-Fi there is no own subnet to be outside of; the address class still counts.
                        .let { found -> if (target.network.wifi == null) found - TargetConfirmationReason.OUTSIDE_WIFI else found }
                    else -> emptySet()
                }
                if (routeWarning || reasons.isNotEmpty()) {
                    withContext(Dispatchers.Main.immediate) {
                        currentCoroutineContext().ensureActive()
                        if(generation.get() == runGeneration) {
                            pending.set(request)
                            mutable.update { it.copy(busy=false,runStatus=LanRunStatus.IDLE,confirmation=true,confirmationReasons=reasons,confirmationTarget=confirmationDescription(request),traceRouteWarning=routeWarning) }
                        }
                    }
                    return@launch
                }
                execute(request)
            } catch (error: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); failFor(runGeneration,R.string.lan_operation_timeout)
            } catch (error: CancellationException) { throw error
            } catch (error: ToolRefused) { currentCoroutineContext().ensureActive(); failFor(runGeneration,error.resource)
            } catch (error: UnsupportedOperationException) { currentCoroutineContext().ensureActive(); failFor(runGeneration,R.string.lan_mdns_unsupported)
            } catch (error: Exception) { currentCoroutineContext().ensureActive(); failFor(runGeneration,failure(error))
            } finally { finishRun(runGeneration) }
        }
    }
    /**
     * Numeric IPv4 as is. A hostname is resolved on Wi-Fi first (local names), then on the active
     * network (public names when the Wi-Fi resolver fails, e.g. Wi-Fi without internet).
     */
    private suspend fun resolveTarget(host: String, wifi: LanWifi?, active: ActiveNetworkInfo?): String {
        if (ipv4Number(host) != null) return host
        val routes = listOfNotNull(wifi?.let(ToolRoute::wifi), active?.let { ToolRoute.active(it,wifi) }).distinct()
        if (routes.isEmpty()) throw ToolRefused(R.string.lan_no_network)
        var last: Exception? = null
        for (route in routes) {
            try {
                return backend.resolve(route,host).firstOrNull { it is Inet4Address }?.hostAddress ?: throw IllegalArgumentException("IPV4")
            } catch (error: TimeoutCancellationException) { last = error
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { last = error }
        }
        throw last!!
    }
    private fun confirmationDescription(request: PreparedRequest): String {
        val app = getApplication<Application>()
        val wifi = request.wifi ?: request.route?.wifi
        val network = when {
            wifi != null -> "${app.getString(R.string.net_kind_wifi)} ${wifi.address}/${wifi.prefix}"
            request.route != null -> listOfNotNull(app.getString(netKindLabel(request.route.kind)),request.route.localAddress).joinToString(" ")
            else -> app.getString(R.string.lan_unavailable)
        }
        return app.getString(R.string.lan_confirmation_details,request.subnet?.toString() ?: request.snapshot.host.trim(),
            request.target?.address ?: request.subnet?.toString().orEmpty(),network,
            request.subnet?.hostCount ?: 1L,request.ports.size)
    }
    private fun acceptPending() {
        val request=pending.getAndSet(null) ?: return
        if (request.generation != generation.get()) { dismissConfirmation(); return }
        val runGeneration=generation.incrementAndGet()
        mutable.update { it.copy(busy=true,runStatus=LanRunStatus.RUNNING,confirmation=false,error=null) }
        active=viewModelScope.launch(Dispatchers.IO) {
            try {
                val wifi=backend.wifi()
                val activeNetwork=backend.activeNetwork()
                currentCoroutineContext().ensureActive()
                // The confirmation covers this exact network; any change refuses the run.
                if (request.wifi != null && wifi != request.wifi) throw java.io.IOException("WIFI_LOST")
                val current = when {
                    request.target != null -> (routeFor(request.target.address,wifi,activeNetwork) as? RouteChoice.Selected)?.route
                    request.route != null -> dnsRoute(wifi,activeNetwork)
                    else -> null
                }
                if (current != request.route) throw java.io.IOException("NETWORK_LOST")
                if (request.snapshot.tool == LanTool.TRACE && request.route != null && backend.traceNeedsConfirmation(request.route) && !request.traceRouteWarning) throw java.io.IOException("ROUTE_CHANGED")
                execute(request.copy(generation=runGeneration))
            } catch (error: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); failFor(runGeneration,R.string.lan_operation_timeout)
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { currentCoroutineContext().ensureActive(); failFor(runGeneration,failure(error))
            } finally { finishRun(runGeneration) }
        }
    }
    private suspend fun execute(request: PreparedRequest) {
        val runId=request.generation
        val snapshot=request.snapshot
        request.wifi?.let(backend::checkWifi)
        request.route?.let(backend::checkRoute)
        if (snapshot.tool == LanTool.TRACE && request.route != null && backend.traceNeedsConfirmation(request.route) && !request.traceRouteWarning) throw java.io.IOException("ROUTE_CHANGED")
        when(snapshot.tool) {
            LanTool.DISCOVERY -> discover(request.wifi!!,request.subnet!!,request.timeout,request.parallel,runId)
            LanTool.MDNS -> {
                val wifi=request.wifi!!
                withTimeoutOrNull(12_000) {
                    backend.services(wifi,snapshot.serviceType).collect { result ->
                        backend.checkWifi(wifi)
                        publish(runId) { old -> old.copy(results=(old.results.filterNot { it.name == result.name && it.detail == result.detail }+result).take(128),progress=old.progress+1) }
                    }
                }
                publish(runId) { it.copy(notice=R.string.lan_mdns_finished) }
            }
            LanTool.DNS -> {
                val route=request.route!!
                for (address in backend.resolve(route,snapshot.host.trim())) {
                    val ip=address.hostAddress.orEmpty()
                    append(LanResult(ip,if (address is Inet4Address) reverseOrEmpty(route,ip) else "","DNS"),runId)
                }
            }
            else -> {
                val target=request.target!!
                val current=(routeFor(target.address,backend.wifi(),backend.activeNetwork()) as? RouteChoice.Selected)?.route
                if (current != target.network) throw java.io.IOException("NETWORK_LOST")
                probePinnedHost(target,current) { selected,ip ->
                    when(snapshot.tool) {
                        LanTool.PING -> ping(selected,ip,request.timeout,runId)
                        LanTool.TRACE -> trace(selected,ip,request.timeout,runId,request.traceRouteWarning)
                        LanTool.PORTS -> scanPorts(selected,ip,request.ports,request.timeout,request.parallel,snapshot.readBanner,runId)
                        else -> Unit
                    }
                }
            }
        }
    }
    private suspend fun append(result: LanResult,runId: Int) { publish(runId) { it.copy(results=it.results+result,progress=it.progress+1) } }
    private suspend fun reverseOrEmpty(wifi: LanWifi,ip: String): String = try { backend.reverse(wifi,ip)
    } catch (error: CancellationException) { currentCoroutineContext().ensureActive(); ""
    } catch (_: Exception) { "" }
    private suspend fun reverseOrEmpty(route: ToolRoute,ip: String): String = try { backend.reverse(route,ip)
    } catch (error: CancellationException) { currentCoroutineContext().ensureActive(); ""
    } catch (_: Exception) { "" }
    private suspend fun discover(wifi: LanWifi,range: Ipv4Subnet,timeout: Int,parallel: Int,runId: Int) {
        val hosts=range.hosts().filterNot { it == wifi.address }
        publish(runId) { it.copy(total=hosts.size) }
        probeBounded(hosts,parallel) { ip ->
            backend.checkWifi(wifi)
            val reply = try { backend.ping(wifi,ip,timeout) }
                catch (error: CancellationException) { currentCoroutineContext().ensureActive(); PingReply(null) }
                catch (_: IcmpUnavailableException) { publish(runId) { it.copy(notice=R.string.lan_icmp_unavailable) }; PingReply(null) }
            val result = reply.milliseconds?.let { LanResult(ip,method="ICMP",milliseconds=it) } ?: coroutineScope {
                // Four bounded probes run concurrently; the IO dispatcher caps total blocking sockets at 64.
                val probes = probeDiscoveryFallback { port -> backend.tcpProbe(wifi,ip,port,minOf(timeout,400)) }
                publish(runId) { old -> old.copy(probeCounts=probes.fold(old.probeCounts) { counts,probe -> counts.add(probe.status) }) }
                probes.firstOrNull { it.status == ProbeStatus.OPEN }?.result
                    ?: if (probes.any { it.status == ProbeStatus.REFUSED }) LanResult(ip,method="TCP-RST") else null
            }
            if (result != null) {
                val found=result.copy(name=reverseOrEmpty(wifi,ip))
                publish(runId) { it.copy(results=(it.results+found).sortedBy { row -> ipv4Number(row.address) }) }
            }
            publish(runId) { it.copy(progress=it.progress+1) }
        }
    }
    private suspend fun ping(route: ToolRoute,ip: String,timeout: Int,runId: Int) {
        val start = System.nanoTime()
        while (currentCoroutineContext().isActive) {
            backend.checkRoute(route)
            val reply = try { backend.ping(route,ip,timeout) }
                catch (_: TimeoutCancellationException) { PingReply(null) }
                catch (_: IcmpUnavailableException) { failFor(runId,R.string.lan_icmp_unavailable); return }
            val elapsed=(System.nanoTime()-start)/1_000_000
            publish(runId) { old -> old.copy(samples=(old.samples+reply.milliseconds).takeLast(600),
                sent=old.sent+1,received=old.received+if(reply.milliseconds != null) 1 else 0,
                results=appendPingResult(old.results,ip,old.sent.toLong()+1,elapsed,reply.milliseconds),progress=old.sent+1) }
            delay(1000)
        }
    }
    private suspend fun trace(route: ToolRoute,ip: String,timeout: Int,runId: Int,routeWarning: Boolean) {
        publish(runId) { it.copy(total=30) }
        var knownHop=false
        for (ttl in 1..30) {
            backend.checkRoute(route)
            if (backend.traceNeedsConfirmation(route) && !routeWarning) throw java.io.IOException("ROUTE_CHANGED")
            val reply = try { backend.ping(route,ip,timeout,ttl) } catch (_: TimeoutCancellationException) { PingReply(null) }
            currentCoroutineContext().ensureActive()
            val address=if (reply.milliseconds != null) ip else reply.hop?.takeUnless { it == route.localAddress }.orEmpty()
            if (address.isNotEmpty()) knownHop=true
            val measureHop = address.isNotEmpty() && address != ip
            val milliseconds = if (measureHop) {
                val echoTimeout = minOf(timeout,1000)
                val samples = mutableListOf<Double?>()
                repeat(3) {
                    currentCoroutineContext().ensureActive()
                    samples += try {
                        withTimeoutOrNull(echoTimeout.toLong()) { backend.ping(route,address,echoTimeout).milliseconds }
                    } catch (_: IcmpUnavailableException) { null }
                }
                // Direct echo RTT on the same network, not the duration of the TTL-exceeded probe.
                pingStatistics(samples).minimum
            } else reply.milliseconds
            append(LanResult(address,method=if (measureHop) "TTL+ICMP" else "TTL",milliseconds=milliseconds,detail=ttl.toString(),routeMayBeOutsideWifi=routeWarning),runId)
            publish(runId) { it.copy(progress=ttl) }
            if (reply.milliseconds != null) return
            if (ttl == 5 && !knownHop) { publish(runId) { it.copy(notice=R.string.lan_trace_limited) }; return }
        }
        publish(runId) { it.copy(notice=R.string.lan_trace_limited) }
    }
    override fun onCleared() { cancelActive(); super.onCleared() }

    private suspend fun scanPorts(route: ToolRoute,ip: String,ports: List<Int>,timeout: Int,parallel: Int,banner: Boolean,runId: Int) {
        publish(runId) { it.copy(total=ports.size) }
        probeBounded(ports,parallel) { port ->
                val probe = backend.tcpProbe(route,ip,port,timeout,banner)
                publish(runId) { it.copy(probeCounts=it.probeCounts.add(probe.status)) }
                val result=probe.result
                if (result != null) publish(runId) { it.copy(results=(it.results+result).sortedBy { row -> row.port }) }
                publish(runId) { it.copy(progress=it.progress+1) }
        }
    }
}
