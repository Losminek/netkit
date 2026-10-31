package cz.netkit.app.test

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityNodeInfo
import android.view.InputDevice
import android.view.MotionEvent
import cz.netkit.app.MainActivity
import cz.netkit.app.wol.NetKitApplication
import cz.netkit.app.wol.AndroidNetworkInfoProvider
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking

/**
 * JUnit-free end-to-end runner. Invoke with:
 * `am instrument -w -e phase write|read cz.netkit.app.test/cz.netkit.app.test.NetKitInstrumentation`.
 * The test intentionally sends three UDP packets to the emulator host listener
 * configured in the UI as 10.0.2.2:9009; it never selects a broadcast destination.
 */
class NetKitInstrumentation : Instrumentation() {
    private var runnerArguments: Bundle? = null
    private var runId = ""
    private val timeoutMs = 8_000L
    private val maxScrollSteps = 32
    private val mainName get() = "NetKit-$runId-main"
    private val deleteName get() = "NetKit-$runId-delete"
    private val readDeleteName get() = "NetKit-$runId-read-delete"
    private val mainMac = "AA:BB:CC:DD:EE:FF"
    private val targetAddress = "10.0.2.2"
    private val targetPort = "9009"

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        runnerArguments = arguments
        start()
    }

    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application {
        return if (className == NetKitApplication::class.java.name) {
            super.newApplication(cl, NetKitTestApplication::class.java.name, context)
        } else {
            super.newApplication(cl, className, context)
        }
    }

    override fun onStart() {
        val phase = runnerArguments?.getString("phase") ?: "write"
        try {
            // UiAutomation omits IME windows unless this accessibility flag is enabled.
            uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            require(phase in listOf("write", "read", "matrix")) { "unsupported phase" }
            runId = runnerArguments?.getString("runId").orEmpty()
            require(runId.matches(Regex("[a-f0-9]{32}"))) { "runId must be a 32-character lowercase UUID hex string" }
            NetKitTestApplication.currentRunId = runId
            status("INFO: isolated test store enabled runId=$runId")
            val intent = Intent(targetContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            val activity = startActivitySync(intent)
            check(activity != null) { "MainActivity did not start" }
            check(activity.application is NetKitTestApplication) { "isolated test Application substitution failed" }
            awaitTag("tab_wifi")
            when (phase) { "matrix" -> runImeMatrix(); "write" -> runWritePhase(); else -> runReadPhase() }
            if (phase == "read") captureScreenshot(NetKitTestApplication.SCREENSHOT_FILE)
            finish(Activity.RESULT_OK, Bundle().apply {
                putString("netkit.result", "PASS")
                putString("netkit.phase", phase)
                putString("netkit.runId", runId)
            })
        } catch (failure: Throwable) {
            val message = failure.message ?: failure.javaClass.simpleName
            status("FAIL: phase=$phase: $message")
            finish(Activity.RESULT_CANCELED, Bundle().apply {
                putString("netkit.result", "FAIL")
                putString("netkit.phase", phase)
                putString("netkit.runId", runId)
                putString("netkit.error", message)
            })
        } finally {
            (targetContext.applicationContext as? NetKitTestApplication)?.closeTestStore()
        }
    }

    /** One diagnostic matrix only. A completed matrix is not a passing WoL E2E. */
    private fun runImeMatrix() {
        tapTag("tab_wol"); tapListTag("wol_add")
        fun hideIme() {
            if (uiAutomation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) injectBackKey()
        }
        matrixTap("a", "device_name")
        hideIme()
        setField("device_name", "Matrix")
        matrixTap("b", "device_name")
        hideIme()
        setField("device_mac", "not-a-mac")
        tapFormTag("device_save")
        awaitFormCondition("invalid MAC for matrix") { root -> findNodes(root).any {
            nodeValue(it).contains(targetContext.getString(cz.netkit.app.R.string.invalid_mac))
        } }
        matrixTap("c", "device_name")
        hideIme()
        matrixTap("d", "device_mac")
        hideIme()
        matrixTap("e", "device_mac", leftThird = true)
        hideIme()
        tapFormTag("device_cancel")
        awaitTag("draft_discard_confirm"); tapTag("draft_discard_confirm")
        tapTag("tab_lan"); awaitTag("lan_segment_tools"); tapTag("lan_segment_tools")
        awaitCondition("LAN editable control") { root -> findNodes(root).any { it.isEditable && it.isVisibleToUser } }
        matrixTap("f", null)
        status("PASS: single WoL IME diagnostic matrix completed")
    }

    private fun matrixTap(step: String, tag: String?, leftThird: Boolean = false) {
        if (tag != null) awaitFormTag(tag)
        fun field(): AccessibilityNodeInfo? {
            if (android.os.Build.VERSION.SDK_INT >= 34) uiAutomation.clearCache()
            val root = liveRoot() ?: return null
            val scope = if (tag == null) root else findTag(root, tag) ?: return null
            return findNodes(scope).firstOrNull { it.isEditable && it.isVisibleToUser }
        }
        val before = field() ?: error("Matrix $step field absent")
        val bounds = Rect().also(before::getBoundsInScreen)
        check(!bounds.isEmpty)
        val x = if (leftThird) bounds.left + bounds.width() / 3 else bounds.centerX()
        uiAutomation.executeShellCommand("input tap $x ${bounds.centerY()}").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        val deadline = SystemClock.uptimeMillis() + 3000
        var focused = false
        var ime = false
        while (SystemClock.uptimeMillis() < deadline) {
            focused = field()?.isFocused == true
            ime = uiAutomation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            if (focused && ime) break
            SystemClock.sleep(100)
        }
        status("MATRIX:$step:tag=${tag ?: "LAN"}:focused=$focused:ime=$ime:bounds=$bounds:tap=$x,${bounds.centerY()}")
        captureDiagnosticScreenshot("matrix-$step.png")
        val app = targetContext.applicationContext as NetKitTestApplication
        val tree = liveRoot()?.let { root ->
            val node = findTag(root, "device_mac") ?: root
            findNodes(node).joinToString("\n") {
                "tag=${it.viewIdResourceName} class=${it.className} children=${it.childCount} " +
                    "enabled=${it.isEnabled} clickable=${it.isClickable} focused=${it.isFocused} " +
                    "visible=${it.isVisibleToUser} actions=${it.actionList.map { action -> action.id }} " +
                    "bounds=${Rect().also(it::getBoundsInScreen)}"
            }
        }.orEmpty()
        java.io.File(app.screenshotFile("matrix-$step.png", runId).parentFile, "matrix-$step-tree.txt").writeText(tree)
        if (!focused || !ime) {
            for ((name, command) in listOf("window" to "dumpsys window", "input-method" to "dumpsys input_method", "logcat" to "logcat -d -t 500")) {
                uiAutomation.executeShellCommand(command).use { descriptor ->
                    val bytes = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
                    java.io.File(app.screenshotFile("matrix-$step.png", runId).parentFile, "matrix-$step-$name.txt").writeBytes(bytes)
                }
            }
        }
    }

    private fun runWritePhase() {
        verifyFeatureTab("tab_wifi", cz.netkit.app.R.string.wifi_scan, "PASS: tab Wi-Fi feature controls")
        verifyFeatureTab("tab_lan", cz.netkit.app.R.string.lan_start, "PASS: tab LAN feature controls")
        verifyFeatureTab("tab_ssh", cz.netkit.app.R.string.ssh_add, "PASS: tab SSH feature controls")
        tapTag("tab_wol")
        awaitTag("wol_add")
        status("PASS: four tabs present and feature controls open")

        verifyInvalidMacAndBack()

        addDevice(
            name = mainName,
            mac = mainMac,
            note = "write phase persistence note",
            probeHost = "192.0.2.10",
            probePort = "22",
        )
        val mainId = findDeviceIdByName(mainName)
        status("PASS: primary device created id=$mainId")

        addDevice(
            name = deleteName,
            mac = "02:00:00:00:00:02",
            note = "temporary write record",
            probeHost = "",
            probePort = "",
        )
        val secondId = findDeviceIdByName(deleteName)
        tapListTag("wol_edit_$secondId")
        awaitFormTag("device_name")
        expectField("device_name", deleteName)
        expectField("device_mac", "02:00:00:00:00:02")
        expectField("device_note", "temporary write record")
        replaceField("device_note", "temporary write record edited")
        tapFormTag("device_save")
        awaitListTag("wol_device_$secondId")
        status("PASS: second device edited and saved")
        deleteDevice(deleteName)
        awaitListTag("wol_device_$mainId")
        status("PASS: second device deleted; primary record remains")

        val wifi = runBlocking { AndroidNetworkInfoProvider(targetContext).currentWifi() }
        check(wifi != null) { "AndroidNetworkInfoProvider.currentWifi() returned null on emulator" }
        val handleType = wifi.handle.javaClass.name
        status(
            "INFO: currentWifi transport=Wi-Fi ipv4=${wifi.ipv4 ?: "none"} " +
                "prefix=${wifi.prefixLength?.toString() ?: "none"} handleType=$handleType",
        )
        status("PASS: AndroidNetworkInfoProvider returned a Wi-Fi network before send")

        tapListTag("wol_send_$mainId")
        val sentStatus = targetContext.getString(cz.netkit.app.R.string.sent)
        awaitCondition("WoL status reports packet sent") { root ->
            findTag(root, "wol_status_$mainId")?.let { nodeValue(it).contains(sentStatus) } == true
        }
        status("PASS: UI reported packet sent for $targetAddress:$targetPort")
    }

    private fun runReadPhase() {
        tapTag("tab_wol")
        val mainId = findDeviceIdByName(mainName)
        tapListTag("wol_edit_$mainId")
        awaitFormTag("device_name")
        expectField("device_name", mainName)
        expectField("device_mac", mainMac)
        expectField("custom_target", targetAddress)
        expectField("device_port", targetPort)
        val customMode = awaitFormTag("target_mode_custom")
        check(customMode.isSelected || customMode.isChecked) { "custom target mode did not persist" }
        expectField("probe_host", "192.0.2.10")
        expectField("probe_port", "22")
        expectField("device_note", "write phase persistence note")
        status("PASS: persisted primary fields survived external force-stop and restart")

        replaceField("device_note", "read phase edited note")
        tapFormTag("device_save")
        awaitListTag("wol_device_$mainId")
        status("PASS: persisted primary record edited")

        addDevice(
            name = readDeleteName,
            mac = "02:00:00:00:00:03",
            note = "temporary read record",
            probeHost = "",
            probePort = "",
        )
        deleteDevice(readDeleteName)
        awaitListTag("wol_device_$mainId")
        tapListTag("wol_edit_$mainId")
        awaitFormTag("device_name")
        expectField("device_note", "read phase edited note")
        status("PASS: temporary record deleted; one edited primary record remains")
        tapFormTag("device_cancel")
        awaitListTag("wol_add")
        status("PASS: read phase left the primary record visible for screenshot")
    }

    private fun verifyInvalidMacAndBack() {
        val invalidDraftName = "NetKit-$runId-invalid"
        tapListTag("wol_add")
        awaitFormTag("device_name")
        setField("device_name", invalidDraftName)
        setField("device_mac", "not-a-mac")
        tapFormTag("device_save")
        val invalidMacText = targetContext.getString(cz.netkit.app.R.string.invalid_mac)
        awaitFormCondition("localized invalid MAC message") { root ->
            findNodes(root).any { nodeValue(it).contains(invalidMacText) }
        }
        check(findTag(liveRoot() ?: error("No UI after invalid MAC"), "device_form") != null) {
            "invalid MAC unexpectedly left the edit form"
        }
        status("PASS: invalid MAC is rejected in the UI")

        tapEditableField("device_mac")
        awaitCondition("editable field focused after real shell touch") { _ ->
            freshEditableBounds("device_mac")?.first?.let { it.isFocused && it.isVisibleToUser } == true
        }
        awaitCondition("software keyboard visible") { _ ->
            uiAutomation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        }
        uiAutomation.executeShellCommand("input text A").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        awaitCondition("keyboard text received by focused field") { _ ->
            freshEditableBounds("device_mac")?.first?.text?.toString()?.contains("A") == true
        }
        status("PASS: real keyboard input received by focused field")
        verifySaveVisibleAboveKeyboard()
        injectBackKey()
        awaitCondition("software keyboard hidden after Back") { _ ->
            uiAutomation.windows.none { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        }
        expectField("device_name", invalidDraftName)
        status("PASS: first Back hides keyboard and preserves draft")

        injectBackKey()
        awaitTag("draft_discard_confirm")
        tapTag("draft_discard_cancel")
        awaitFormTag("device_name")
        expectField("device_name", invalidDraftName)
        status("PASS: Back from dirty form prompts; keep editing preserves draft")

        injectBackKey()
        awaitTag("draft_discard_confirm")
        tapTag("draft_discard_confirm")
        awaitTag("wol_add")
        awaitText(targetContext.getString(cz.netkit.app.R.string.no_devices))
        status("PASS: explicit discard leaves isolated store empty")
    }

    private fun tapEditableField(tag: String) {
        awaitFormTag(tag)
        val (_, bounds) = awaitStableEditableBounds(tag)
        check(!bounds.isEmpty) { "$tag editable node has empty bounds: $bounds" }
        // Use the same shell input path as a real emulator keyboard/touch test (N5-210).
        // The merged editable bounds include supporting/error text below the input.
        // Tap the left upper third of the field, not the center of that combined box.
        val tapX = bounds.left + bounds.width() / 3
        val tapY = bounds.top + bounds.height() / 3
        uiAutomation.executeShellCommand("input tap $tapX $tapY").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        waitForIdle()
        val after = freshEditableBounds(tag)
        val current = after?.first
        status(
            "INFO: editable tap tag=$tag boundsBefore=$bounds boundsAfter=${after?.second} " +
                "focusedAfter=${current?.isFocused} windows=${windowsDiagnostic()}",
        )
        captureDiagnosticScreenshot(NetKitTestApplication.KEYBOARD_SCREENSHOT_FILE)
    }

    private fun awaitStableEditableBounds(tag: String): Pair<AccessibilityNodeInfo, Rect> {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var previous: Rect? = null
        var stableSamples = 0
        var latest: Pair<AccessibilityNodeInfo, Rect>? = null
        while (SystemClock.uptimeMillis() < deadline) {
            // A quiet event interval spans scrolling animations; every event restarts it.
            uiAutomation.waitForIdle(500, timeoutMs)
            val reading = freshEditableBounds(tag)
            if (reading == null || reading.second.isEmpty) {
                previous = null
                stableSamples = 0
                continue
            }
            latest = reading
            if (reading.second == previous) {
                stableSamples++
                if (stableSamples >= 3) {
                    status("INFO: stable editable bounds tag=$tag bounds=${reading.second} samples=$stableSamples")
                    return reading
                }
            } else {
                previous = Rect(reading.second)
                stableSamples = 1
            }
        }
        throw AssertionError(
            "Timed out waiting for stable bounds of $tag; latest=${latest?.second}; " +
                "tree=${activeTreeDiagnostic()}",
        )
    }

    private fun freshEditableBounds(tag: String): Pair<AccessibilityNodeInfo, Rect>? {
        if (android.os.Build.VERSION.SDK_INT >= 34) uiAutomation.clearCache()
        val root = liveRoot() ?: return null
        if (!root.refresh()) return null
        val taggedNode = findTag(root, tag) ?: return null
        val editable = findNodes(taggedNode).firstOrNull { it.isEditable && it.isVisibleToUser } ?: return null
        if (!editable.refresh()) return null
        return editable to Rect().also(editable::getBoundsInScreen)
    }

    private fun captureDiagnosticScreenshot(name: String) {
        val app = targetContext.applicationContext as? NetKitTestApplication ?: return
        val image = uiAutomation.takeScreenshot() ?: return
        try {
            val file = app.screenshotFile(name, runId)
            FileOutputStream(file).use { output -> image.compress(Bitmap.CompressFormat.PNG, 100, output) }
        } finally {
            image.recycle()
        }
    }

    private fun windowsDiagnostic(): String = uiAutomation.windows.joinToString(prefix = "[", postfix = "]") {
        val bounds = Rect()
        it.getBoundsInScreen(bounds)
        "type=${it.type},active=${it.isActive},focused=${it.isFocused},bounds=$bounds"
    }

    private fun injectBackKey() {
        val downTime = SystemClock.uptimeMillis()
        val down = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)
        val up = KeyEvent(downTime, downTime + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0)
        check(uiAutomation.injectInputEvent(down, true)) { "Back key down injection failed" }
        check(uiAutomation.injectInputEvent(up, true)) { "Back key up injection failed" }
        waitForIdle()
    }

    private fun verifySaveVisibleAboveKeyboard() {
        val save = awaitFormTag("device_save")
        val saveBounds = Rect()
        save.getBoundsInScreen(saveBounds)
        val ime = uiAutomation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?: error("Input method window disappeared before save visibility check")
        val imeBounds = Rect()
        ime.getBoundsInScreen(imeBounds)
        check(saveBounds.bottom <= imeBounds.top) {
            "save button is covered by keyboard: save=$saveBounds keyboard=$imeBounds"
        }
        status("PASS: form save button remains above visible keyboard")
        captureScreenshot(NetKitTestApplication.KEYBOARD_SCREENSHOT_FILE)
    }

    private fun captureScreenshot(name: String) {
        val app = targetContext.applicationContext as? NetKitTestApplication
            ?: error("isolated test Application is not active")
        val image = uiAutomation.takeScreenshot() ?: error("UiAutomation.takeScreenshot returned null")
        val file = app.screenshotFile(name, runId)
        check(file.parentFile?.isDirectory == true) { "screenshot directory does not exist" }
        try {
            FileOutputStream(file).use { output ->
                check(image.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG screenshot compression failed" }
            }
        } finally {
            image.recycle()
        }
        status("PASS: screenshot $name saved in isolated test directory")
    }

    private fun verifyFeatureTab(tabTag: String, control: Int, marker: String) {
        tapTag(tabTag)
        if (tabTag == "tab_lan") { awaitTag("lan_segment_tools"); tapTag("lan_segment_tools") }
        awaitText(targetContext.getString(control))
        status(marker)
    }

    private fun addDevice(
        name: String,
        mac: String,
        note: String,
        probeHost: String,
        probePort: String,
    ) {
        tapListTag("wol_add")
        awaitFormTag("device_name")
        setField("device_name", name)
        setField("device_mac", mac)
        tapFormTag("target_mode_custom")
        setField("custom_target", targetAddress)
        setField("device_port", targetPort)
        if (probeHost.isNotEmpty()) setField("probe_host", probeHost)
        if (probePort.isNotEmpty()) setField("probe_port", probePort)
        setField("device_note", note)
        tapFormTag("device_save")
        awaitTagPrefixInList("wol_edit_")
    }

    private fun deleteDevice(name: String) {
        val id = findDeviceIdByName(name)
        tapTag("device_delete_$id")
        awaitTag("device_delete_confirm")
        tapTag("device_delete_confirm")
        awaitListTag("wol_add")
        assertDeviceAbsentFromEntireList(id)
    }

    private fun expectField(tag: String, expected: String) {
        val value = nodeValue(awaitFormTag(tag))
        check(value == expected) { "field $tag expected '$expected', got '$value'" }
    }

    private fun setField(tag: String, value: String) {
        val taggedNode = awaitFormTag(tag)
        val node = findNodes(taggedNode).firstOrNull { it.isEditable && it.isVisibleToUser } ?: taggedNode
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            "ACTION_SET_TEXT failed for $tag"
        }
        try {
            awaitFormCondition("field $tag value '$value'") { root ->
                val currentTag = findTag(root, tag) ?: return@awaitFormCondition false
                val currentField = findNodes(currentTag).firstOrNull { it.isEditable && it.isVisibleToUser } ?: currentTag
                currentField.text?.toString() == value
            }
        } catch (failure: Throwable) {
            val tree = activeTreeDiagnostic()
            status("DIAG: setField tag=$tag expected=$value editable=${node.isEditable}; $tree")
            throw AssertionError("field $tag did not accept '$value'; $tree", failure)
        }
    }

    private fun replaceField(tag: String, value: String) = setField(tag, value)

    private fun tapTag(tag: String) {
        val node = awaitTag(tag)
        // Material navigation exposes the already selected destination as selected but
        // non-clickable. Its visible screen already satisfies a tap request for that tab.
        if (tag.startsWith("tab_") && node.isSelected) {
            waitForIdle()
            return
        }
        val actionable = findNodes(node).firstOrNull { it.isEnabled && it.isClickable }
            ?: generateSequence(node.parent) { it.parent }.firstOrNull { it.isEnabled && it.isClickable }
        check(actionable != null) { "$tag has no clickable accessibility node" }
        check(actionable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "click failed for $tag" }
        waitForIdle()
    }

    private fun tapListTag(tag: String) {
        val node = awaitListTag(tag)
        performClick(tag, node)
    }

    private fun tapFormTag(tag: String) {
        val node = awaitFormTag(tag)
        performClick(tag, node)
    }

    private fun performClick(tag: String, node: AccessibilityNodeInfo) {
        val actionable = findNodes(node).firstOrNull { it.isEnabled && it.isClickable }
            ?: generateSequence(node.parent) { it.parent }.firstOrNull { it.isEnabled && it.isClickable }
        check(actionable != null) { "$tag has no clickable accessibility node" }
        check(actionable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "click failed for $tag" }
        waitForIdle()
    }

    private fun awaitTag(tag: String): AccessibilityNodeInfo =
        awaitCondition("tag $tag") { root -> findTag(root, tag) != null }
            .let { findTag(it, tag)!! }

    private fun awaitListTag(tag: String): AccessibilityNodeInfo =
        awaitListCondition("list tag $tag") { root -> findTag(root, tag) != null }
            .let { findTag(it, tag)!! }

    private fun awaitFormTag(tag: String): AccessibilityNodeInfo =
        awaitFormCondition("form tag $tag") { root -> findTag(root, tag) != null }
            .let { findTag(it, tag)!! }

    private fun awaitTagPrefixInList(prefix: String): String {
        var result = ""
        awaitListCondition("tag prefix $prefix") { root ->
            result = findNodes(root).firstOrNull { it.viewIdResourceName?.startsWith(prefix) == true }
                ?.viewIdResourceName.orEmpty()
            result.isNotEmpty()
        }
        return result
    }

    private fun awaitText(text: String) {
        awaitCondition("text $text") { root -> findNodes(root).any { nodeValue(it).contains(text) } }
    }

    private fun findDeviceIdByName(name: String): String {
        var id = ""
        awaitListCondition("device card $name") { root ->
            val card = findNodes(root).firstOrNull { node ->
                val tag = node.viewIdResourceName
                tag != null && tag.startsWith("wol_device_") && subtreeText(node).contains(name)
            }
            id = card?.viewIdResourceName?.removePrefix("wol_device_").orEmpty()
            id.isNotEmpty()
        }
        return id
    }

    private fun awaitCondition(
        description: String,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            liveRoot()?.let { root -> if (predicate(root)) return root }
            SystemClock.sleep(150)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private fun awaitListCondition(
        description: String,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo = awaitScrollableCondition(description, form = false, predicate)

    private fun awaitFormCondition(
        description: String,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo = awaitScrollableCondition(description, form = true, predicate)

    private fun awaitScrollableCondition(
        description: String,
        form: Boolean,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var root = liveRoot()
            if (root != null && predicate(root)) return root
            val isExpectedScreen = if (form) {
                root?.let { findTag(it, "device_form") != null } == true
            } else {
                root?.let { findTag(it, "wol_add") != null } == true
            }
            if (!isExpectedScreen) {
                SystemClock.sleep(150)
                continue
            }

            var steps = 0
            while (steps < maxScrollSteps) {
                root = liveRoot() ?: break
                if (predicate(root)) return root
                if (!scroll(root, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, form)) break
                steps++
                waitForIdle()
            }
            steps = 0
            while (steps < maxScrollSteps) {
                root = liveRoot() ?: break
                if (predicate(root)) return root
                if (!scroll(root, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, form)) break
                steps++
                waitForIdle()
            }
            SystemClock.sleep(150)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private fun scroll(root: AccessibilityNodeInfo, action: Int, form: Boolean): Boolean {
        val taggedForm = if (form) findTag(root, "device_form") else null
        val scroller = taggedForm?.takeIf { it.isScrollable }
            ?: findNodes(root).firstOrNull { it.isScrollable }
            ?: return false
        return scroller.performAction(action)
    }

    private fun assertDeviceAbsentFromEntireList(id: String) {
        var backwardSteps = 0
        while (backwardSteps < maxScrollSteps) {
            val root = liveRoot() ?: error("No active window after delete")
            check(findTag(root, "wol_add") != null) { "device list is not visible after delete" }
            if (!scroll(root, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, form = false)) break
            backwardSteps++
            waitForIdle()
        }
        var forwardSteps = 0
        while (true) {
            val root = liveRoot() ?: error("No active window after delete")
            val remains = findTag(root, "wol_device_$id") != null
            check(!remains) { "deleted device id '$id' is still in the list" }
            if (!scroll(root, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, form = false)) return
            forwardSteps++
            check(forwardSteps <= maxScrollSteps) { "device list exceeded bounded deletion scan" }
            waitForIdle()
        }
    }

    private fun liveRoot(): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 34) uiAutomation.clearCache()
        return uiAutomation.rootInActiveWindow?.takeIf { it.refresh() }
    }

    private fun findTag(root: AccessibilityNodeInfo, tag: String): AccessibilityNodeInfo? =
        findNodes(root).firstOrNull { it.viewIdResourceName == tag && it.isVisibleToUser }

    private fun findNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            result += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        visit(root)
        return result
    }

    private fun nodeValue(node: AccessibilityNodeInfo): String =
        node.text?.toString() ?: node.contentDescription?.toString().orEmpty()

    private fun subtreeText(node: AccessibilityNodeInfo): String = buildString {
        val own = nodeValue(node)
        if (own.isNotEmpty()) append(own).append('\n')
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { append(subtreeText(it)) }
        }
    }

    private fun activeTreeDiagnostic(): String {
        val root = liveRoot() ?: return "activeWindow=null"
        return findNodes(root).take(120).joinToString(" | ") { node ->
            val tag = node.viewIdResourceName.orEmpty()
            val text = node.text?.toString().orEmpty().replace('\n', ' ').take(100)
            val description = node.contentDescription?.toString().orEmpty().replace('\n', ' ').take(80)
            "tag=$tag text=$text desc=$description class=${node.className} editable=${node.isEditable} focused=${node.isFocused}"
        }.take(8_000)
    }

    private fun waitForIdle() {
        uiAutomation.waitForIdle(100, timeoutMs)
    }

    private fun status(marker: String) {
        val bundle = Bundle().apply {
            putString("netkit.marker", marker)
            putString("stream", marker)
        }
        sendStatus(0, bundle)
    }
}
