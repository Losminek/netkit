package cz.netkit.app.test

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import cz.netkit.app.wifi.WifiViewModel
import cz.netkit.shared.wifi.WifiScanStatus
import cz.netkit.shared.json.JSON_MAX_DEPTH
import cz.netkit.shared.json.jsonNestingDepth
import cz.netkit.shared.speed.parseNdt7Measurement
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.text.InputType
import android.graphics.Rect
import android.accessibilityservice.AccessibilityServiceInfo
import cz.netkit.app.ssh.PrivateTerminalEditText
import cz.netkit.app.ssh.DirectTerminalInput
import cz.netkit.app.ssh.SshViewModel
import cz.netkit.shared.ssh.SshBackgroundPolicy
import cz.netkit.app.MainActivity
import cz.netkit.app.R
import cz.netkit.app.lan.LanBackend
import cz.netkit.app.lan.LanViewModel
import cz.netkit.app.lan.LanTool
import java.net.ServerSocket
import java.net.InetAddress
import cz.netkit.app.ssh.AndroidSecretStore
import cz.netkit.app.ssh.JschConnection
import cz.netkit.app.ssh.SshCrypto
import cz.netkit.app.ssh.SshInteraction
import cz.netkit.app.ssh.SshStore
import cz.netkit.app.ssh.readBytesBounded
import cz.netkit.app.wol.AndroidDeviceStore
import cz.netkit.app.wol.NetKitApplication
import cz.netkit.shared.ssh.SshAuthentication
import cz.netkit.shared.ssh.SshProfile
import cz.netkit.shared.ssh.TrustedHostKey
import cz.netkit.shared.wol.WolDeviceStore
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*

/** Separate JUnit-free runner: explicit phases, private fixtures and stores, emulator host only. */
class M1Instrumentation : Instrumentation() {
    private var arguments = Bundle()
    private var runId=""
    private var phase=""
    private var outcome="PASS"
    private var checkpoint="SETUP"
    private var failureType=""
    private lateinit var app: M1TestApplication
    override fun onCreate(arguments: Bundle?) {
        this.arguments=arguments ?: Bundle()
        runId=this.arguments.getString("runId").orEmpty()
        M1TestApplication.runId=runId
        super.onCreate(arguments)
        start()
    }
    override fun newApplication(cl: ClassLoader,className: String,context: Context): Application =
        super.newApplication(cl,if (className == NetKitApplication::class.java.name) M1TestApplication::class.java.name else className,context)
    override fun onStart() {
        uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        phase=arguments.getString("phase").orEmpty()
        try {
            check(runId.matches(Regex("[a-f0-9]{32}"))) { "Invalid runId" }
            check(phase in listOf("ui","lan","ssh","wifi","wifi-denied","ssh-ui","ssh-rotated","lan-confirm","ssh-lifecycle","ssh-legacy","lan-discovery","lan-icmp-rtt","wifi-freshness","ssh-reconnect","json-limits","ssh-key-editor","ssh-profiles-v2","ssh-profiles-r1","ssh-profiles-p3")) { "Invalid phase" }
            app=targetContext.applicationContext as M1TestApplication
            marker("PHASE:$phase:START")
            runBlocking {
                withTimeout(if (phase == "ssh-lifecycle") 380_000L else 115_000L) {
                    when(phase) { "ssh-profiles-p3" -> sshProfilesP3Evidence(this@M1Instrumentation, app, ::screenshot, ::marker); "ssh-profiles-r1" -> sshProfilesR1Evidence(this@M1Instrumentation, app, ::screenshot, ::marker); "ssh-profiles-v2" -> sshProfilesV2Evidence(this@M1Instrumentation, app, ::screenshot, ::marker); "ssh-key-editor" -> sshKeyEditorEvidence(this@M1Instrumentation, app, arguments.getString("stage") ?: "2", arguments.getString("order") != "import-first", ::screenshot, ::marker); "json-limits" -> jsonLimitsPhase(); "ssh-reconnect" -> sshUiPhase(reconnect = true); "wifi-freshness" -> wifiFreshnessPhase(); "ui" -> uiPhase(); "lan" -> lanPhase(); "lan-icmp-rtt" -> lanIcmpRttPhase(); "lan-confirm" -> lanConfirmPhase(); "lan-discovery" -> lanDiscoveryPhase(); "ssh" -> sshPhase(); "ssh-ui" -> sshUiPhase(); "ssh-lifecycle" -> sshUiPhase(lifecycle = true); "ssh-rotated" -> sshRotatedPhase(); "ssh-legacy" -> sshLegacyPhase(); "wifi" -> wifiPhase(false); "wifi-denied" -> wifiPhase(true) }
                }
            }
        } catch (failure: Throwable) {
            // Exception class/step only: provider/library messages may include credentials or key data.
            if (phase == "ssh-key-editor" || phase == "ssh-profiles-v2" || phase == "ssh-profiles-r1" || phase == "ssh-profiles-p3") {
                marker("SSH_TEST_STACK:" + failure.stackTrace.take(12).joinToString(";"))
                try { screenshot("ssh-test-failure.png") } catch (_: Exception) { }
            }
            failureType=failure.javaClass.simpleName
            outcome="FAIL"
        } finally {
            if (::app.isInitialized) {
                try { app.closeStore() } catch (_: Throwable) { outcome="FAIL" }
                try { if (phase != "ssh-key-editor" || arguments.getString("stage") != "1" || outcome != "PASS") app.cleanupUiSecrets() } catch (_: Throwable) { outcome="FAIL" }
            }
        }
        // Cleanup is part of the verdict and must finish before Instrumentation can stop the process.
        marker("PHASE:$phase:$outcome")
        finish(if(outcome == "FAIL") Activity.RESULT_CANCELED else Activity.RESULT_OK,Bundle().apply {
            putString("netkit.result",outcome); putString("netkit.phase",phase); putString("netkit.runId",runId)
            if(outcome == "FAIL") { putString("netkit.failureType",failureType); putString("netkit.failureStep",checkpoint) }
            if(outcome == "SKIP") putString("netkit.skipReason","THROTTLED_CONFIRMED")
        })
    }
    /** Real ART main-thread stack, not an Android unit test running on the desktop JVM. */
    private fun jsonLimitsPhase() {
        checkpoint = "JSON_LIMITS_MAIN_THREAD"
        var failure: Throwable? = null
        runOnMainSync {
            try {
                check(android.os.Looper.myLooper() === android.os.Looper.getMainLooper())
                for (objects in listOf(false, true)) {
                    val open = if (objects) "{\"x\":" else "["
                    val close = if (objects) "}" else "]"
                    val text = open.repeat(JSON_MAX_DEPTH) + "0" + close.repeat(JSON_MAX_DEPTH)
                    check(jsonNestingDepth(text) == JSON_MAX_DEPTH)
                    var node = Json.parseToJsonElement(text)
                    repeat(JSON_MAX_DEPTH) {
                        node = if (objects) (node as JsonObject).getValue("x") else (node as JsonArray).single()
                    }
                    check(node == JsonPrimitive(0))
                    val extension = open.repeat(JSON_MAX_DEPTH - 1) + "0" + close.repeat(JSON_MAX_DEPTH - 1)
                    val measurement = """{"AppInfo":{"NumBytes":10},"future":$extension}"""
                    check(jsonNestingDepth(measurement) == JSON_MAX_DEPTH)
                    check(parseNdt7Measurement(measurement)?.appInfo?.numBytes == 10.0)
                }
            } catch (error: Throwable) {
                // Test-only: report even a StackOverflowError on the instrumentation thread.
                failure = error
            }
        }
        failure?.let { throw it }
        marker("JSON:MAX_DEPTH:$JSON_MAX_DEPTH:MAIN_THREAD:PASS")
    }
    private suspend fun wifiFreshnessPhase() {
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }) as androidx.activity.ComponentActivity
        try {
            captureWifiFreshnessEvidence(this, activity, ::screenshot, ::marker)
            captureWifiChartEvidence(this, activity, ::screenshot, ::marker)
        }
        finally { runOnMainSync { activity.finish() } }
    }
    private suspend fun uiPhase() {
        val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        try {
            check(activity.application === app)
            awaitNode { it.viewIdResourceName == "tab_wifi" }
            for ((tag,control) in listOf("tab_signal" to R.string.tab_signal,"tab_wifi" to R.string.wifi_scan,"tab_lan" to R.string.lan_start,
                "tab_ssh" to R.string.ssh_add,"tab_wol" to R.string.add_device)) {
                checkpoint="UI_CLICK_$tag"
                clickTag(tag)
                marker("UI:STEP:$tag:CLICKED")
                navDiagnostics(tag)
                // M2: the former LAN tab opens on Devices; the M1 tools live behind the Tools segment.
                if(tag == "tab_lan") { awaitNode { it.viewIdResourceName == "lan_segment_tools" }; clickTag("lan_segment_tools") }
                checkpoint="UI_CONTROL_$tag"
                if(tag == "tab_wol") awaitNode { it.viewIdResourceName == "wol_add" }
                else if(tag == "tab_signal") awaitNode { it.viewIdResourceName == "signal_screen" }
                else awaitNode { it.text?.toString() == targetContext.getString(control) && (it.isClickable || it.parent?.isClickable == true) }
                marker("UI:STEP:$tag:CONTROL_FOUND")
                navDiagnostics(tag)
                checkpoint="UI_SELECTED_$tag"
                awaitNode { it.viewIdResourceName == tag && it.isSelected }
                marker("UI:STEP:$tag:SELECTED")
                uiAutomation.waitForIdle(200,3000)
                delay(400) // Navigation color/selection animation must settle before visual evidence.
                val name="ui-${tag.removePrefix("tab_")}.png"
                screenshot(name)
                marker("UI:$tag:CONTROLS:PASS")
            }
        } catch (failure: Throwable) {
            navDiagnostics("failure")
            try { screenshot("ui-failure.png") } catch (_: Throwable) { marker("UI:FAIL_SCREENSHOT:UNAVAILABLE") }
            throw failure
        } finally {
            withContext(NonCancellable) {
                runOnMainSync { activity.finish() }
                withTimeout(3000) { while (!activity.isDestroyed) delay(50) }
            }
        }
    }
    private fun navDiagnostics(step: String) {
        val root=freshRoot()
        val states=root?.let { nodes(it).filter { node -> node.viewIdResourceName in listOf("tab_signal","tab_wifi","tab_lan","tab_ssh","tab_wol") }
            .joinToString(";") { node -> "${node.viewIdResourceName}:selected=${node.isSelected},clickable=${node.isClickable},visible=${node.isVisibleToUser}" } } ?: "NO_ROOT"
        marker("UI:DIAG:$step:$states")
    }
    private suspend fun wifiPhase(denied: Boolean) {
        val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        try {
        check(activity.application === app)
        withTimeout(8000) { while (!activity.hasWindowFocus()) delay(50) }
        marker("WIFI:FOREGROUND_ACTIVITY:PASS")
        val fine=ContextCompat.checkSelfPermission(app,Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse=ContextCompat.checkSelfPermission(app,Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if(denied) check(!fine && !coarse) else {
            check(fine && coarse)
            check(app.getSystemService(LocationManager::class.java).isLocationEnabled)
        }
        val owner=object : ViewModelStoreOwner { override val viewModelStore=ViewModelStore() }
        lateinit var model: WifiViewModel
        runOnMainSync { model=ViewModelProvider(owner,ViewModelProvider.AndroidViewModelFactory(app))[WifiViewModel::class.java] }
        try {
            runOnMainSync { model.scan() } // The same explicit manual action used by the UI.
            val snapshot=withTimeout(17000) {
                model.state.first { it.status !in listOf(WifiScanStatus.IDLE,WifiScanStatus.SCANNING) }
            }
            if(denied) {
                check(snapshot.status == WifiScanStatus.PERMISSION_REQUIRED && snapshot.accessPoints.isEmpty() && snapshot.lastUpdatedElapsedMs == null)
                marker("WIFI:DENIED_GUARD:PASS")
            } else {
                runOnMainSync { model.refreshCurrent() }
                val current=withTimeout(8000) { model.state.first { it.current != null } }.current!!
                check(!current.ipAddress.isNullOrBlank())
                marker("WIFI:CURRENT_CONNECTION:PASS")
                when {
                    snapshot.status == WifiScanStatus.READY && snapshot.accessPoints.isNotEmpty() -> {
                        check(snapshot.lastUpdatedElapsedMs != null)
                        check(snapshot.accessPoints.all { it.bssid.isNotBlank() && it.frequencyMhz > 0 })
                        marker("WIFI:VIRTUAL_AP_RESULTS:PASS")
                    }
                    snapshot.status in listOf(WifiScanStatus.THROTTLED_OR_FAILED,WifiScanStatus.READY) -> {
                        // startScan=false also means driver/error conditions: no proof of throttling, hence FAIL.
                        error("No fresh results without independently confirmed throttling")
                    }
                    else -> error("Unexpected Wi-Fi status")
                }
            }
        } finally { runOnMainSync { model.cancelActive(); owner.viewModelStore.clear() } }
        } finally {
            withContext(NonCancellable) {
                runOnMainSync { activity.finish() }
                withTimeout(3000) { while (!activity.isDestroyed) delay(50) }
            }
        }
    }
    private suspend fun awaitNode(predicate: (AccessibilityNodeInfo)->Boolean): AccessibilityNodeInfo = withTimeout(8000) {
        while(true) {
            val root=freshRoot()
            val found=root?.let { nodes(it).firstOrNull { node -> node.isVisibleToUser && unobscuredByIme(node) && predicate(node) } }
            if(found != null) return@withTimeout found
            delay(50)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
    private fun unobscuredByIme(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty) return false
        return uiAutomation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }.none { window ->
            val ime = Rect().also(window::getBoundsInScreen)
            Rect.intersects(bounds, ime)
        }
    }
    /** New queries must not reuse stale selected/enabled/value nodes from an earlier Compose tree. */
    private fun freshRoot(): AccessibilityNodeInfo? {
        if(Build.VERSION.SDK_INT >= 34) uiAutomation.clearCache()
        val root=uiAutomation.rootInActiveWindow ?: return null
        if(Build.VERSION.SDK_INT < 34) {
            if(!root.refresh()) return null
            fun refreshChildren(node: AccessibilityNodeInfo) {
                for(index in 0 until node.childCount) {
                    val child=node.getChild(index) ?: continue
                    if(child.refresh()) refreshChildren(child)
                }
            }
            refreshChildren(root)
        }
        return root
    }
    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo) { add(node); for(index in 0 until node.childCount) node.getChild(index)?.let(::visit) }
        visit(root)
    }
    private suspend fun clickTag(tag: String) {
        val node=awaitNode { it.viewIdResourceName == tag }
        if(node.isSelected) return
        val clickable=nodes(node).firstOrNull { it.isClickable && it.isEnabled && it.isVisibleToUser && unobscuredByIme(it) }
            ?: generateSequence(node.parent) { it.parent }.firstOrNull { it.isClickable && it.isEnabled }
        check(clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        delay(150)
    }
    private fun screenshot(name: String) {
        val target=File(app.filesDir,"m1-evidence/$name")
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        val image=uiAutomation.takeScreenshot() ?: error("Screenshot failed")
        try { target.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG,100,it)) } } finally { image.recycle() }
        marker("SCREENSHOT:files/m1-data/$runId/m1-evidence/$name")
    }
    private fun fixture(): JsonObject {
        val source=File(app.originalFilesDir,"m1-fixture.json")
        val bytes=source.inputStream().use { it.readBytesBounded(1_048_576) }
        return Json.parseToJsonElement(bytes.decodeToString()).jsonObject.also { json ->
            val server=json.getValue("server").jsonObject
            check(server.getValue("emulatorHost").jsonPrimitive.content == "10.0.2.2")
            check(server.getValue("tcpBannerPort").jsonPrimitive.int == 18081)
            check(server.getValue("tcpClosedPort").jsonPrimitive.int == 18082)
            check(server.getValue("tcpSilentPort").jsonPrimitive.int == 18083)
            check(server.getValue("sshPort").jsonPrimitive.int == 22222)
        }
    }
    private suspend fun lanPhase() = withContext(Dispatchers.IO) {
        fixture()
        val backend=LanBackend(app)
        val wifi=backend.wifi() ?: error("Wi-Fi unavailable")
        marker("LAN:WIFI:interface=${wifi.interfaceName};ipv4=${wifi.address};prefix=${wifi.prefix};network=${wifi.network}")
        checkpoint="LAN_PING_HOST"
        check(backend.ping(wifi,"10.0.2.2",1000).milliseconds != null)
        marker("LAN:PING_HOST_REPLY:PASS")
        val open=backend.tcp(wifi,"10.0.2.2",18081,1000,true)
        check(open != null && open.port == 18081 && open.detail.contains("NETKIT_TCP"))
        marker("LAN:TCP_OPEN_BANNER:PASS")
        val refused = backend.tcpProbe(wifi,"10.0.2.2",18082,1000)
        check(refused.status == cz.netkit.shared.lan.ProbeStatus.REFUSED && refused.result == null)
        marker("LAN:TCP_REFUSED_ERRNO:PASS")
        marker("LAN:TCP_CLOSED:PASS")
        coroutineScope {
            val connected=CompletableDeferred<Unit>()
            val probe=launch { backend.tcp(wifi,"10.0.2.2",18083,5000,true) { connected.complete(Unit) } }
            withTimeout(2000) { connected.await() }
            check(probe.isActive)
            marker("LAN:CANCEL_CONNECTED:PASS")
            val started=SystemClock.elapsedRealtime()
            probe.cancelAndJoin()
            val elapsed=SystemClock.elapsedRealtime()-started
            check(elapsed < 1000)
            marker("LAN:CANCEL_UNDER_1S:PASS")
        }
    }
    private suspend fun lanIcmpRttPhase() = withContext(Dispatchers.IO) {
        val backend = LanBackend(app)
        val wifi = backend.wifi() ?: error("Wi-Fi unavailable")
        val samples = mutableListOf<Double>()
        marker("LAN_ICMP_RTT:METHOD:host=10.0.2.2;clock=nanoTime;timeoutMs=1000;count=20")
        repeat(20) { index ->
            checkpoint = "LAN_ICMP_RTT_SAMPLE_${index + 1}"
            val milliseconds = backend.ping(wifi, "10.0.2.2", 1000).milliseconds
            check(milliseconds != null && milliseconds.isFinite() && milliseconds >= 0)
            samples += milliseconds
            marker("LAN_ICMP_RTT:SAMPLE:index=${index + 1};milliseconds=$milliseconds")
            delay(25) // Space requests; this delay is outside the measured echo interval.
        }
        marker("LAN_ICMP_RTT:SUMMARY:count=${samples.size};min=${samples.min()};avg=${samples.average()};max=${samples.max()}")
        marker("LAN_ICMP_RTT:20_REPLIES:PASS")
    }
    /** Regression: accepting a pinned request must publish results under its new execution generation. */
    private suspend fun lanConfirmPhase() = coroutineScope {
        val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        val owner=object : ViewModelStoreOwner { override val viewModelStore=ViewModelStore() }
        var server: ServerSocket?=null
        var listener: Job?=null
        try {
            withTimeout(8000) { while (!activity.hasWindowFocus()) delay(50) }
            val socket=withContext(Dispatchers.IO) { ServerSocket(0,1,InetAddress.getByAddress(byteArrayOf(127,0,0,1))) }
            server=socket
            val accepted=CompletableDeferred<Unit>()
            listener=launch(Dispatchers.IO) {
                try { runInterruptible { socket.accept().use { accepted.complete(Unit) } } }
                catch(error: CancellationException) { throw error }
                catch(error: Exception) { if(!socket.isClosed) throw error }
            }
            lateinit var model: LanViewModel
            runOnMainSync {
                model=ViewModelProvider(owner,ViewModelProvider.AndroidViewModelFactory(app))[LanViewModel::class.java]
                model.tool(LanTool.PORTS)
                model.host("127.0.0.1")
                model.ports(socket.localPort.toString())
                model.timeout("1000")
                model.start()
            }
            checkpoint="LAN_CONFIRM_PENDING"
            val pending=withTimeout(8000) { model.state.first { it.confirmation && !it.busy || it.error != null } }
            check(pending.confirmation && pending.error == null && pending.results.isEmpty() && !accepted.isCompleted)
            marker("LAN_CONFIRM:PINNED_PENDING:PASS")
            runOnMainSync { model.host("127.0.0.2"); model.start(true) }
            delay(200)
            check(!model.state.value.busy && !model.state.value.confirmation && !accepted.isCompleted)
            marker("LAN_CONFIRM:STALE_REJECTED:PASS")
            runOnMainSync { model.host("127.0.0.1"); model.start() }
            withTimeout(8000) { model.state.first { it.confirmation } }
            checkpoint="LAN_CONFIRM_ACCEPTED_RESULTS"
            runOnMainSync { model.start(true) }
            val completed=withTimeout(8000) { model.state.first { !it.busy && !it.confirmation } }
            check(completed.error == null && completed.total == 1 && completed.progress == 1)
            check(completed.results.single().let { it.address == "127.0.0.1" && it.port == socket.localPort })
            withTimeout(1000) { accepted.await() }
            marker("LAN_CONFIRM:ACTUAL_RESULT_PUBLISHED:PASS")
        } finally {
            withContext(NonCancellable) {
                runOnMainSync { owner.viewModelStore.clear(); activity.finish() }
                server?.close()
                listener?.cancelAndJoin()
                withTimeout(3000) { while (!activity.isDestroyed) delay(50) }
            }
        }
    }
    private suspend fun lanDiscoveryPhase() {
        val owner=object:ViewModelStoreOwner { override val viewModelStore=ViewModelStore() }
        lateinit var model:LanViewModel
        try {
            runOnMainSync {
                model=ViewModelProvider(owner,ViewModelProvider.AndroidViewModelFactory(app))[LanViewModel::class.java]
                model.tool(LanTool.DISCOVERY); model.cidr("127.0.0.0/24")
                model.timeout("1000"); model.concurrency("32"); model.start()
            }
            withTimeout(8000) { model.state.first { it.confirmation || it.error!=null } }
            check(model.state.value.confirmation)
            val start=SystemClock.elapsedRealtime()
            runOnMainSync { model.start(true) }
            val result=withTimeout(30000) { model.state.first { it.runStatus in listOf(cz.netkit.shared.lan.LanRunStatus.FINISHED,cz.netkit.shared.lan.LanRunStatus.FAILED) } }
            val elapsed=SystemClock.elapsedRealtime()-start
            marker("LAN_DISCOVERY:loopback_24:elapsed_ms=$elapsed;progress=${result.progress};total=${result.total}")
            check(result.runStatus==cz.netkit.shared.lan.LanRunStatus.FINISHED && result.progress==254 && result.total==254)
            check(elapsed<=20000)
            marker("LAN_DISCOVERY:LOOPBACK_24_UNDER_20S:PASS")
        } finally { runOnMainSync { owner.viewModelStore.clear() } }
    }
    private suspend fun sshUiPhase(lifecycle: Boolean = false, reconnect: Boolean = false) {
        val config=withContext(Dispatchers.IO) { fixture() }
        val server=config.getValue("server").jsonObject
        val client=config.getValue("client").jsonObject
        val profile=SshProfile("m1-ui-profile","M1 UI fixture","10.0.2.2",22222,
            server.getValue("username").jsonPrimitive.content,secretRef="ui-password", startupCommand=if (reconnect) "netkit-check" else "")
        val seedScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        checkpoint="SSH_UI_SEED"
        try {
            withContext(Dispatchers.IO) {
                val credentialBytes=client.getValue("password").jsonPrimitive.content.encodeToByteArray()
                try { app.createSshSecretStore().put("ui-password",credentialBytes) } finally { credentialBytes.fill(0) }
                val file=File(app.filesDir,"datastore/ssh.json")
                check(file.canonicalPath.startsWith(app.filesDir.canonicalPath+File.separator))
                val store=SshStore(app,seedScope,file)
                store.update { cz.netkit.shared.ssh.SshData(profiles=listOf(profile)) }
            }
        } finally { withContext(NonCancellable) { seedScope.coroutineContext[Job]!!.cancelAndJoin() } }
        val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        try {
            check(activity.application === app)
            checkpoint="SSH_UI_PROFILE"
            clickTag("tab_ssh")
            awaitNode { it.viewIdResourceName == "ssh_connect_${profile.id}" && it.isEnabled }
            marker("SSH_UI:PROFILE_VISIBLE:PASS")
            if (reconnect) {
                // Start while another tab is visible to verify the cross-tab pending prompt banner.
                clickTag("tab_wifi")
                runOnMainSync { ViewModelProvider(activity as MainActivity)[SshViewModel::class.java].connect(profile) }
                awaitNode { it.viewIdResourceName == "ssh_pending_open" }
                screenshot("ssh-pending-prompt.png")
                clickTag("ssh_pending_open")
                marker("SSH_UI:PENDING_PROMPT_BANNER:PASS")
            } else {
                clickTag("ssh_connect_${profile.id}")
                awaitNode { it.viewIdResourceName == "ssh_confirm" }
                clickTag("ssh_confirm")
            }
            checkpoint="SSH_UI_TOFU"
            awaitNode { it.viewIdResourceName == "ssh_trust" }
            val expectedFingerprint=server.getValue("hostKeyFingerprint").jsonPrimitive.content
            awaitNode { it.text?.toString()?.contains(expectedFingerprint) == true }
            clickTag("ssh_trust")
            marker("SSH_UI:TOFU_CLICKED")
            checkpoint="SSH_UI_TERMINAL_READY"
            awaitNode { it.text?.toString()?.contains("NETKIT_FIXTURE_READY") == true }
            lateinit var model: SshViewModel
            runOnMainSync { model = ViewModelProvider(activity as MainActivity)[SshViewModel::class.java] }
            withTimeout(5000) { model.state.first { snapshot ->
                snapshot.data.hostKeys.any { it.fingerprint == expectedFingerprint } && snapshot.sessions.any { it.connected }
            } }
            marker("SSH_UI:HOST_TRUSTED:PASS")
            marker("SSH_UI:TERMINAL_READY:PASS")
            checkpoint="SSH_UI_PRIVACY"
            verifySshPrivacy(activity)
            checkpoint="SSH_UI_KEYBOARD_INPUT"
            typeSshDirect(activity, "netkit-check")
            checkpoint="SSH_UI_COMMAND_RESULT"
            awaitNode { it.text?.toString()?.contains("NETKIT_TEST_OK") == true }
            marker("SSH_UI:COMMAND_RESULT:PASS")
            screenshot("ssh-ui-terminal.png")
            if (lifecycle) verifySshBackgroundLifecycle(activity, model)
            if (reconnect) verifySshReconnect(model)
            checkpoint="SSH_UI_CLOSE"
            clickTag("ssh_close")
            clickTag("ssh_confirm")
            awaitNode { it.viewIdResourceName == "ssh_connect_${profile.id}" && it.isEnabled }
            marker("SSH_UI:CLOSED:PASS")
        } catch (failure: Throwable) {
            try { screenshot("ssh-ui-failure.png") } catch (_: Throwable) { marker("SSH_UI:FAIL_SCREENSHOT:UNAVAILABLE") }
            throw failure
        } finally {
            withContext(NonCancellable) {
                runOnMainSync { activity.finish() }
                withTimeout(3000) { while (!activity.isDestroyed) delay(50) }
            }
        }
    }
    private fun shellInput(command: String) {
        // Fixed commands or numeric coordinates only; never credentials or server-controlled values.
        uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val bytes = input.readBytesBounded(65536)
                check(bytes.isEmpty()) { "Unexpected shell input output" }
            }
        }
    }
    private fun directInput(activity: Activity): DirectTerminalInput {
        fun find(view: View): DirectTerminalInput? {
            if (view is DirectTerminalInput) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        return find(activity.window.decorView) ?: error("Direct terminal input view missing")
    }
    private suspend fun verifySshReconnect(model: SshViewModel) {
        checkpoint = "SSH_RECONNECT_END_OLD"
        runOnMainSync { model.cancelActive() }
        val ended = withTimeout(5000) { model.state.first { it.sessions.singleOrNull()?.ended == true } }.sessions.single()
        val retained = ended.terminal
        if (uiAutomation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) shellInput("input keyevent KEYCODE_BACK")
        suspend fun reconnectButton() {
            val node = awaitNode { it.text?.toString() == targetContext.getString(R.string.ssh_reconnect) }
            val button = if (node.isClickable) node else node.parent
            check(button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            awaitNode { it.viewIdResourceName == "ssh_confirm" }
            awaitNode { it.text?.toString()?.contains("netkit-check") == true }
        }
        reconnectButton()
        checkpoint = "SSH_RECONNECT_CANCEL"
        val cancel = awaitNode { it.text?.toString() == targetContext.getString(R.string.ssh_cancel) }
        check((if (cancel.isClickable) cancel else cancel.parent)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        check(model.state.value.sessions.single().let { it.id == ended.id && it.ended && it.terminal == retained })
        marker("SSH_UI:RECONNECT_CANCEL_RETAINS_BUFFER:PASS")
        reconnectButton()
        screenshot("ssh-reconnect-confirm.png")
        clickTag("ssh_confirm")
        checkpoint = "SSH_RECONNECT_CONNECTED"
        withTimeout(12000) { model.state.first { it.sessions.singleOrNull()?.let { session -> session.id == ended.id && session.connected && !session.ended } == true } }
        awaitNode { it.text?.toString()?.contains("NETKIT_TEST_OK") == true }
        marker("SSH_UI:RECONNECT_CONFIRMED:PASS")
    }
    private suspend fun verifySshPrivacy(activity: Activity) {
        runOnMainSync {
            check(activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            check(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0)
            val editor = EditorInfo()
            directInput(activity).onCreateInputConnection(editor)
            check(editor.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
            check(editor.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
            check(editor.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT == 0)
            check(editor.inputType and InputType.TYPE_MASK_VARIATION == InputType.TYPE_TEXT_VARIATION_PASSWORD)
        }
        marker("SSH_UI:IME_PRIVACY_FLAGS:PASS")
        screenshot("ssh-secure-off.png")
        checkpoint = "SSH_UI_PRIVACY_OPEN_MENU_ON"
        clickTag("ssh_options")
        checkpoint = "SSH_UI_PRIVACY_CLICK_SECURE_ON"
        clickTag("ssh_secure_screen")
        checkpoint = "SSH_UI_PRIVACY_AWAIT_SECURE_ON"
        withTimeout(3000) {
            while (true) {
                var secure = false
                runOnMainSync { secure = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 }
                if (secure) break
                delay(50)
            }
        }
        try { screenshot("ssh-secure-on.png") }
        catch (_: IllegalStateException) { marker("SSH_UI:SECURE_SCREENSHOT:BLOCKED") }
        marker("SSH_UI:FLAG_SECURE_ON:PASS")
        checkpoint = "SSH_UI_PRIVACY_OPEN_MENU_OFF"
        clickTag("ssh_options")
        checkpoint = "SSH_UI_PRIVACY_CLICK_SECURE_OFF"
        clickTag("ssh_secure_screen")
        checkpoint = "SSH_UI_PRIVACY_AWAIT_SECURE_OFF"
        withTimeout(3000) {
            while (true) {
                var secure = true
                runOnMainSync { secure = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 }
                if (!secure) break
                delay(50)
            }
        }
        marker("SSH_UI:FLAG_SECURE_OFF:PASS")
    }
    private suspend fun typeSshDirect(activity: Activity, text: String) {
        check(text == "netkit-check") // Only this nonsecret, fixed fixture command may enter the shell argv.
        uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        awaitNode { it.viewIdResourceName == "cz.netkit.app:id/ssh_direct_input" && it.isEnabled && it.isVisibleToUser }
        val bounds = Rect()
        runOnMainSync { check(directInput(activity).getGlobalVisibleRect(bounds)); check(directInput(activity).isShown) }
        check(!bounds.isEmpty)
        checkpoint = "SSH_UI_DIRECT_TOUCH"
        shellInput("input tap ${bounds.centerX()} ${bounds.centerY()}")
        checkpoint = "SSH_UI_DIRECT_FOCUS_IME"
        try {
            withTimeout(5000) {
                while (true) {
                    var focused = false
                    runOnMainSync { focused = directInput(activity).hasFocus() }
                    val ime = uiAutomation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                    if (focused && ime != null) break
                    delay(50)
                }
            }
        } catch (failure: Throwable) {
            var focused = false; var shown = false
            runOnMainSync { focused = directInput(activity).hasFocus(); shown = directInput(activity).isShown }
            marker("SSH_UI:INPUT_DIAG:focused=$focused;shown=$shown;ime=${uiAutomation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }}")
            throw failure
        }
        val focusedNode = awaitNode { it.isEditable && it.isFocused && it.isVisibleToUser }
        val inputBounds = Rect().also(focusedNode::getBoundsInScreen)
        val keyboard = uiAutomation.windows.first { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val keyboardBounds = Rect().also(keyboard::getBoundsInScreen)
        check(!Rect.intersects(inputBounds, keyboardBounds)) { "Terminal input covered by IME" }
        marker("SSH_UI:INPUT_FOCUSED_IME_VISIBLE:PASS")
        screenshot("ssh-ime-visible.png")
        checkpoint = "SSH_UI_DIRECT_TYPE"
        shellInput("input text netkit-check")
        // No local editable text buffer: require the actual SSH fixture echo before Enter.
        awaitNode { it.text?.toString()?.contains("fixture> netkit-check") == true }
        marker("SSH_UI:INPUT_TYPED_FROM_KEYBOARD:PASS")
        marker("SSH_UI:DIRECT_REMOTE_ECHO:PASS")
        shellInput("input keyevent KEYCODE_ENTER")
        awaitNode { it.text?.toString()?.contains("NETKIT_TEST_OK") == true }
        // Focus/visible IME and remote result already checked; dismiss to reach session actions.
        shellInput("input keyevent KEYCODE_BACK")
        withTimeout(3000) { while (uiAutomation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) delay(50) }
    }
    private suspend fun verifySshBackgroundLifecycle(activity: Activity, model: SshViewModel) {
        val sessionId = model.state.value.sessions.single { it.connected }.id
        fun markerCount(): Int = model.state.value.sessions.first { it.id == sessionId }.terminal.let {
            (it.history + it.screen).sumOf { row -> Regex("NETKIT_TEST_OK").findAll(row.text).count() }
        }
        suspend fun home() {
            shellInput("input keyevent KEYCODE_HOME")
            withTimeout(5000) { while (activity.hasWindowFocus()) delay(50) }
        }
        suspend fun returnToSession() {
            runOnMainSync {
                activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                })
            }
            withTimeout(5000) { while (!activity.hasWindowFocus()) delay(50) }
        }
        checkpoint = "SSH_LIFECYCLE_HOME_SHORT"
        home(); delay(2000); returnToSession()
        check(model.state.value.sessions.first { it.id == sessionId }.connected)
        val count = markerCount()
        runOnMainSync { model.send(sessionId, "netkit-check\r") }
        withTimeout(5000) { while (markerCount() <= count) delay(50) }
        marker("SSH_LIFECYCLE:HOME_RETURN_CONNECTED:PASS")
        checkpoint = "SSH_LIFECYCLE_BACKGROUND_TIMEOUT"
        home()
        delay(SshBackgroundPolicy.BACKGROUND_GRACE_MILLIS + 1500L)
        withTimeout(5000) { model.state.first { state -> state.sessions.any { it.id == sessionId && it.ended && !it.connected } } }
        marker("SSH_LIFECYCLE:BACKGROUND_EXPIRED:PASS")
        returnToSession()
    }
    private suspend fun sshPhase() = withContext(Dispatchers.IO) {
        val config=fixture()
        val server=config.getValue("server").jsonObject
        val client=config.getValue("client").jsonObject
        val username=server.getValue("username").jsonPrimitive.content
        val credentialValue=client.getValue("password").jsonPrimitive.content
        check(username.length in 1..128 && credentialValue.isNotEmpty())
        val namespace="test-$runId"
        val testScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val secrets=AndroidSecretStore(app,namespace)
        val connections=mutableListOf<JschConnection>()
        val directory=File(app.filesDir,"ssh-instrumentation").apply { check(isDirectory || mkdirs()) }
        try {
            verifySecretStore(secrets,namespace)
            fun profile(auth: SshAuthentication,secretRef: String?=null,keyRef: String?=null) =
                SshProfile(UUID.randomUUID().toString(),"M1 fixture","10.0.2.2",22222,username,auth,secretRef,keyRef)
            suspend fun store(name: String): SshStore = SshStore(app,testScope,File(directory,"$name.json"))
            val knownKey=TrustedHostKey("[10.0.2.2]:22222",server.getValue("hostKeyAlgorithm").jsonPrimitive.content,
                server.getValue("hostKeyPublicKey").jsonPrimitive.content,server.getValue("hostKeyFingerprint").jsonPrimitive.content)
            suspend fun attempt(name: String,p: SshProfile,accept: Boolean,credential: String,known: TrustedHostKey?=null): Attempt {
                val saved=store(name)
                if(known != null) saved.trustNew(known)
                val result=Attempt(saved)
                val connection=JschConnection(p,saved,secrets,object : SshInteraction {
                    override suspend fun confirmHost(offered: TrustedHostKey,previous: TrustedHostKey?): Boolean {
                        result.hostPrompts.incrementAndGet()
                        result.changed.set(previous != null)
                        return accept
                    }
                    override suspend fun authenticate(name: String,instruction: String,prompts: List<String>,echo: List<Boolean>): List<String> {
                        result.authPrompts.incrementAndGet()
                        return prompts.map { credential }
                    }
                },testScope,onText={ text -> result.output.update { (it+text).takeLast(65536) } },
                    onConnected={ result.connected.complete(Unit) },onEnd={ failed -> result.ended.complete(failed) })
                result.connection=connection; connections+=connection; connection.start()
                return result
            }
            val accepted=attempt("password",profile(SshAuthentication.PASSWORD),true,credentialValue)
            withTimeout(12000) { accepted.connected.await() }
            withTimeout(5000) { accepted.output.first { it.contains("NETKIT_FIXTURE_READY") } }
            check(accepted.hostPrompts.get() == 1 && accepted.authPrompts.get() > 0)
            check(accepted.store.read().hostKeys.single().publicKey == knownKey.publicKey)
            marker("SSH:PASSWORD_TOFU:PASS")
            check(accepted.connection.send("netkit-check\r"))
            withTimeout(5000) { accepted.output.first { it.contains("NETKIT_TEST_OK") } }
            marker("SSH:SHELL_MARKER:PASS")
            check(accepted.connection.send("\u0003"))
            withTimeout(5000) { accepted.output.first { it.contains("NETKIT_INTERRUPT_OK") } }
            marker("SSH:CTRL_C:PASS")
            accepted.connection.resize(91,33)
            withTimeout(5000) { accepted.output.first { it.contains("NETKIT_RESIZE_OK 91x33") } }
            marker("SSH:RESIZE:PASS")
            accepted.connection.close()
            withTimeout(3000) { accepted.ended.await() }
            marker("SSH:CLOSE:PASS")

            val wrong=attempt("wrong",profile(SshAuthentication.PASSWORD),true,"wrong-"+UUID.randomUUID(),knownKey)
            check(withTimeout(12000) { wrong.ended.await() })
            check(!wrong.connected.isCompleted && wrong.authPrompts.get() > 0)
            marker("SSH:WRONG_PASSWORD_REJECTED:PASS")

            val rejected=attempt("unknown-reject",profile(SshAuthentication.PASSWORD),false,credentialValue)
            check(withTimeout(10000) { rejected.ended.await() })
            check(!rejected.connected.isCompleted && rejected.hostPrompts.get() == 1 && rejected.authPrompts.get() == 0)
            check(rejected.store.read().hostKeys.isEmpty())
            marker("SSH:UNKNOWN_REJECTED_BEFORE_AUTH:PASS")

            val bytes=Base64.getDecoder().decode(knownKey.publicKey)
            bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte()
            val changedKey=knownKey.copy(publicKey=Base64.getEncoder().encodeToString(bytes),fingerprint=SshCrypto.fingerprint(bytes))
            val changed=attempt("changed",profile(SshAuthentication.PASSWORD),true,credentialValue,changedKey)
            check(withTimeout(10000) { changed.ended.await() })
            check(!changed.connected.isCompleted && changed.changed.get() && changed.authPrompts.get() == 0)
            check(changed.store.read().hostKeys.single() == changedKey)
            marker("SSH:CHANGED_REJECTED_DESPITE_ACCEPT:PASS")

            val raw=File(app.originalFilesDir,"m1-client-key").inputStream().use { it.readBytesBounded(262144) }
            try {
                val imported=SshCrypto.import(raw,byteArrayOf()).first
                try { secrets.put("client-key",imported) } finally { imported.fill(0) }
            } finally { raw.fill(0) }
            val key=attempt("key",profile(SshAuthentication.KEY,keyRef="client-key"),true,credentialValue,knownKey)
            withTimeout(12000) { key.connected.await() }
            withTimeout(5000) { key.output.first { it.contains("NETKIT_FIXTURE_READY") } }
            check(key.hostPrompts.get() == 0 && key.authPrompts.get() == 0)
            key.connection.close(); withTimeout(3000) { key.ended.await() }
            marker("SSH:ED25519_KEY_AUTH:PASS")

            val interactive=attempt("interactive",profile(SshAuthentication.INTERACTIVE),true,credentialValue,knownKey)
            withTimeout(12000) { interactive.connected.await() }
            check(interactive.authPrompts.get() > 0)
            interactive.connection.close(); withTimeout(3000) { interactive.ended.await() }
            marker("SSH:KEYBOARD_INTERACTIVE:PASS")
        } finally {
            connections.forEach { it.close() }
            withContext(NonCancellable) {
                testScope.coroutineContext[Job]!!.cancelAndJoin()
                val encryptedDirectory=File(app.noBackupFilesDir,"$namespace-secrets")
                try {
                    check(!encryptedDirectory.exists() || encryptedDirectory.deleteRecursively())
                    check(!directory.exists() || directory.deleteRecursively())
                } finally {
                    KeyStore.getInstance("AndroidKeyStore").apply {
                        load(null); deleteEntry("netkit.$namespace.aes.v1"); check(!containsAlias("netkit.$namespace.aes.v1"))
                    }
                }
            }
        }
    }
    private suspend fun sshLegacyPhase() = withContext(Dispatchers.IO) {
        val server = fixture().getValue("server").jsonObject
        val namespace = "test-legacy-$runId"
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val directory = File(app.filesDir, "ssh-legacy").apply { check(isDirectory || mkdirs()) }
        var connection: JschConnection? = null
        try {
            val profile = SshProfile("legacy-profile", "Legacy fixture", "10.0.2.2", 22222,
                server.getValue("username").jsonPrimitive.content)
            val store = SshStore(app, testScope, File(directory, "trust.json"))
            val promptCount = AtomicInteger()
            val connected = CompletableDeferred<Unit>()
            val ended = CompletableDeferred<Boolean>()
            val failure = CompletableDeferred<cz.netkit.shared.ssh.SshFailure>()
            checkpoint = "SSH_LEGACY_HANDSHAKE"
            connection = JschConnection(profile, store, AndroidSecretStore(app, namespace), object : SshInteraction {
                override suspend fun confirmHost(offered: TrustedHostKey, previous: TrustedHostKey?): Boolean {
                    promptCount.incrementAndGet(); return false
                }
                override suspend fun authenticate(name: String, instruction: String, prompts: List<String>, echo: List<Boolean>): List<String>? {
                    promptCount.incrementAndGet()
                    return null
                }
            }, testScope, onText = {}, onConnected = { connected.complete(Unit) },
                onEnd = { ended.complete(it) }, onFailure = { failure.complete(it) })
            connection.start()
            check(withTimeout(12000) { ended.await() })
            check(withTimeout(1000) { failure.await() } == cz.netkit.shared.ssh.SshFailure.ALGORITHMS)
            check(!connected.isCompleted && promptCount.get() == 0)
            check(store.read().hostKeys.isEmpty())
            marker("SSH:LEGACY_ALGORITHMS_REJECTED:PASS")
        } finally {
            connection?.close()
            withContext(NonCancellable) {
                testScope.coroutineContext[Job]!!.cancelAndJoin()
                check(!directory.exists() || directory.deleteRecursively())
                val encrypted = File(app.noBackupFilesDir, "$namespace-secrets")
                try { check(!encrypted.exists() || encrypted.deleteRecursively()) }
                finally { KeyStore.getInstance("AndroidKeyStore").apply {
                    load(null); deleteEntry("netkit.$namespace.aes.v1"); check(!containsAlias("netkit.$namespace.aes.v1"))
                } }
            }
        }
    }
    private suspend fun sshRotatedPhase() = withContext(Dispatchers.IO) {
        val config=fixture()
        val server=config.getValue("server").jsonObject
        val namespace="test-rotated-$runId"
        val testScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val directory=File(app.filesDir,"ssh-rotated").apply { check(isDirectory || mkdirs()) }
        var connection: JschConnection?=null
        try {
            val profile=SshProfile("rotated-profile","Rotated fixture","10.0.2.2",22222,
                server.getValue("username").jsonPrimitive.content)
            val primary=TrustedHostKey(profile.endpoint,server.getValue("hostKeyAlgorithm").jsonPrimitive.content,
                server.getValue("hostKeyPublicKey").jsonPrimitive.content,server.getValue("hostKeyFingerprint").jsonPrimitive.content)
            val expectedFingerprint=server.getValue("rotatedHostKeyFingerprint").jsonPrimitive.content
            val expectedPublicKey=server.getValue("rotatedHostKeyPublicKey").jsonPrimitive.content
            check(expectedFingerprint != primary.fingerprint && expectedPublicKey != primary.publicKey)
            val store=SshStore(app,testScope,File(directory,"known-primary.json"))
            store.trustNew(primary)
            val hostPrompts=AtomicInteger()
            val authPrompts=AtomicInteger()
            val connected=CompletableDeferred<Unit>()
            val ended=CompletableDeferred<Boolean>()
            val observed=CompletableDeferred<Pair<TrustedHostKey,TrustedHostKey?>>()
            checkpoint="SSH_ROTATED_HANDSHAKE"
            connection=JschConnection(profile,store,AndroidSecretStore(app,namespace),object : SshInteraction {
                override suspend fun confirmHost(offered: TrustedHostKey,previous: TrustedHostKey?): Boolean {
                    hostPrompts.incrementAndGet()
                    observed.complete(offered to previous)
                    return true // Even explicit approval must not bypass the changed-key policy.
                }
                override suspend fun authenticate(name: String,instruction: String,prompts: List<String>,echo: List<Boolean>): List<String>? {
                    authPrompts.incrementAndGet()
                    return null
                }
            },testScope,onText={},onConnected={ connected.complete(Unit) },onEnd={ failed -> ended.complete(failed) })
            connection.start()
            check(withTimeout(12000) { ended.await() })
            check(hostPrompts.get() == 1 && authPrompts.get() == 0 && !connected.isCompleted)
            val (offered,previous)=withTimeout(1000) { observed.await() }
            check(previous == primary && offered.endpoint == primary.endpoint)
            check(offered.fingerprint == expectedFingerprint && offered.publicKey == expectedPublicKey)
            check(store.read().hostKeys.single() == primary)
            marker("SSH:SERVER_ROTATION_REJECTED:PASS")
        } finally {
            connection?.close()
            withContext(NonCancellable) {
                testScope.coroutineContext[Job]!!.cancelAndJoin()
                try { check(!directory.exists() || directory.deleteRecursively()) }
                finally {
                    val encryptedDirectory=File(app.noBackupFilesDir,"$namespace-secrets")
                    try { check(!encryptedDirectory.exists() || encryptedDirectory.deleteRecursively()) }
                    finally { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("netkit.$namespace.aes.v1"); check(!containsAlias("netkit.$namespace.aes.v1")) } }
                }
            }
        }
    }
    private suspend fun verifySecretStore(secrets: AndroidSecretStore,namespace: String) {
        val secret=("only-test-"+UUID.randomUUID()).encodeToByteArray()
        try {
            secrets.put("roundtrip",secret)
            val read=secrets.get("roundtrip") ?: error("Missing ciphertext")
            try { check(read.contentEquals(secret)) } finally { read.fill(0) }
            val file=File(app.noBackupFilesDir,"$namespace-secrets/roundtrip.bin")
            val encrypted=file.readBytes()
            check(!encrypted.toString(Charsets.ISO_8859_1).contains(secret.toString(Charsets.ISO_8859_1)))
            marker("SSH:KEYSTORE_ROUNDTRIP_CIPHERTEXT:PASS")
            encrypted[encrypted.lastIndex]=(encrypted.last().toInt() xor 1).toByte()
            file.writeBytes(encrypted)
            var failed=false
            try { secrets.get("roundtrip") } catch (error: CancellationException) { throw error } catch (_: Exception) { failed=true }
            check(failed)
            secrets.delete("roundtrip")
            marker("SSH:TAMPER_FAILS_CLOSED:PASS")
        } finally { secret.fill(0) }
    }
    private class Attempt(val store: SshStore) {
        lateinit var connection: JschConnection
        val output=MutableStateFlow("")
        val connected=CompletableDeferred<Unit>()
        val ended=CompletableDeferred<Boolean>()
        val hostPrompts=AtomicInteger()
        val authPrompts=AtomicInteger()
        val changed=java.util.concurrent.atomic.AtomicBoolean()
    }
    private fun marker(value: String) { sendStatus(0,Bundle().apply { putString("netkit.marker",value); putString("stream",value) }) }
}

/** All app-visible stores are isolated; fixture ingress uses only the unmodified original root files path. */
class M1TestApplication : NetKitApplication() {
    private var wolStore: AndroidDeviceStore?=null
    private val bootstrapId="bootstrap-"+UUID.randomUUID().toString()
    val originalFilesDir: File get()=super.getFilesDir()
    private fun isolated(base: File): File {
        // Framework/content-provider initialization may read files before Instrumentation.onCreate.
        val id=runId.takeIf { it.matches(Regex("[a-f0-9]{32}")) } ?: bootstrapId
        return File(base,"m1-data/$id").apply { check(isDirectory || mkdirs()) }
    }
    override fun getFilesDir(): File=isolated(super.getFilesDir())
    override fun getNoBackupFilesDir(): File=isolated(super.getNoBackupFilesDir())
    override fun createDeviceStore(): WolDeviceStore=AndroidDeviceStore(this).also { wolStore=it }
    override fun createSshSecretStore(): AndroidSecretStore {
        check(runId.matches(Regex("[a-f0-9]{32}")))
        return AndroidSecretStore(this,"test-ui-$runId")
    }
    fun cleanupUiSecrets() {
        check(runId.matches(Regex("[a-f0-9]{32}")))
        val namespace="test-ui-$runId"
        val directory=File(noBackupFilesDir,"$namespace-secrets")
        try { check(!directory.exists() || directory.deleteRecursively()) }
        finally {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null); deleteEntry("netkit.$namespace.aes.v1"); check(!containsAlias("netkit.$namespace.aes.v1"))
            }
        }
    }
    fun closeStore() { wolStore?.let { runBlocking { it.close() } }; wolStore=null }
    companion object { @Volatile var runId="" }
}
