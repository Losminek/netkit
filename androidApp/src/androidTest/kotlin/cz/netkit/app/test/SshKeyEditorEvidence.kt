package cz.netkit.app.test

import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import cz.netkit.app.MainActivity
import cz.netkit.app.R
import cz.netkit.app.ssh.SshViewModel
import cz.netkit.shared.ssh.SshAuthentication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File

/** Same test is run against the unmodified 157543b production code and the updated branch. */
suspend fun sshKeyEditorEvidence(
    test: Instrumentation, app: M1TestApplication, stage: String, keyFirst: Boolean,
    screenshot: (String) -> Unit, marker: (String) -> Unit,
) {
    val activity = test.startActivitySync(Intent(app, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }) as MainActivity
    lateinit var model: SshViewModel
    test.runOnMainSync { model = ViewModelProvider(activity)[SshViewModel::class.java] }
    withTimeout(8000) { model.state.first { it.loaded } }
    val ui = SshEvidenceUi(test)
    try {
        ui.clickTag("tab_ssh")
        suspend fun generateKey() {
            marker("KEY_EDITOR:OPEN_KEYS")
            ui.clickTag("ssh_keys")
            marker("KEY_EDITOR:KEY_NAME")
            val input = ui.await { it.isEditable }
            check(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Fixture Ed25519")
            }))
            ui.await { it.isEditable && it.text?.toString() == "Fixture Ed25519" }
            marker("KEY_EDITOR:GENERATE_CLICK")
            ui.clickText(app.getString(R.string.ssh_generate))
            marker("KEY_EDITOR:GENERATE_WAIT")
            withTimeout(15000) { model.state.first { !it.busy && it.data.keys.size == 1 } }
            marker("KEY_EDITOR:GENERATED:${model.state.value.data.keys.size}")
            ui.click(ui.await { it.contentDescription?.toString() == app.getString(R.string.ssh_back) })
            ui.clickTag("ssh_confirm") // Discard the already-used key-name field, not the saved key.
        }
        suspend fun importProfile() {
            val file = File(app.filesDir, "profile-v1.json").apply {
                writeText("""{"format":"netkit-ssh-profiles","schema":1,"profiles":[{"alias":"Fixture","host":"192.0.2.10","port":22,"username":"tester"}],"skipped":[]}""")
            }
            marker("KEY_EDITOR:IMPORT_PREVIEW")
            withTimeout(8000) { model.state.first { !it.profileImportBlockedByDialog && !it.busy } }
            test.runOnMainSync { model.previewProfileImport(Uri.fromFile(file)) }
            withTimeout(8000) { model.state.first { it.pendingProfileImport != null } }
            if (model.state.value.data.keys.isNotEmpty()) ui.clickText(app.getString(R.string.ssh_import_password), scroll = true)
            ui.clickTag("ssh_import_confirm")
            withTimeout(8000) { model.state.first { !it.profileImportBusy && it.data.profiles.size == 1 } }
            check(model.state.value.data.profiles.single().authentication == SshAuthentication.PASSWORD)
            check(model.state.value.data.profiles.single().keyRef == null)
            marker("KEY_EDITOR:IMPORTED_PASSWORD_WITHOUT_KEY")
        }
        if (stage == "all") {
            if (keyFirst) { generateKey(); importProfile() } else { importProfile(); generateKey() }
        } else if ((stage == "1") == keyFirst) generateKey() else importProfile()
        if (stage == "1") { marker("KEY_EDITOR:STAGE1:PASS"); return }
        check(model.state.value.data.keys.size == 1)
        check(model.state.value.data.profiles.single().keyRef == null)
        ui.clickTag("tab_ssh")
        // Original UI uses a profile menu; updated UI has a direct management button.
        val direct = ui.all().find { it.viewIdResourceName == "ssh_edit_${model.state.value.data.profiles.single().id}" }
        if (direct != null) ui.click(direct) else {
            ui.clickText("Fixture")
            ui.clickText(app.getString(R.string.ssh_edit))
        }
        ui.clickText(app.getString(R.string.ssh_key), scroll = true)
        delay(500)
        screenshot("ssh-key-editor-${if (keyFirst) "key-first" else "import-first"}.png")
        val dump = ui.all().joinToString("\n") { node ->
            val rect = Rect(); node.getBoundsInScreen(rect)
            "${node.className}|${node.text}|${node.contentDescription}|selected=${node.isSelected}|checked=${node.isChecked}|enabled=${node.isEnabled}|visible=${node.isVisibleToUser}|$rect"
        }
        File(app.filesDir, "m1-evidence/key-editor-tree.txt").writeText(dump)
        marker("KEY_EDITOR:BEFORE_SELECTION:keys=${model.state.value.data.keys.size};loaded=${model.state.value.loaded};busy=${model.state.value.busy}")
        ui.clickText("Fixture Ed25519", scroll = true)
        ui.clickText(app.getString(R.string.ssh_save), scroll = true)
        withTimeout(8000) { model.state.first {
            !it.busy && it.data.profiles.single().authentication == SshAuthentication.KEY &&
                it.data.profiles.single().keyRef == it.data.keys.single().id
        } }
        marker("KEY_EDITOR:SELECT_AND_SAVE:PASS")
    } catch (error: Throwable) {
        screenshot("ssh-key-editor-failure.png")
        marker("KEY_EDITOR:FAILURE_STATE:keys=${model.state.value.data.keys.size};busy=${model.state.value.busy};error=${model.state.value.error}")
        throw error
    } finally { test.runOnMainSync { activity.finish() } }
}

internal class SshEvidenceUi(private val test: Instrumentation) {
    fun all(): List<AccessibilityNodeInfo> {
        // Same cache discipline as M1Instrumentation.freshRoot: refresh() alone retains stale Compose children on API 34+.
        if (android.os.Build.VERSION.SDK_INT >= 34) test.uiAutomation.clearCache()
        val root = test.uiAutomation.rootInActiveWindow ?: return emptyList()
        root.refresh()
        return buildList {
            fun visit(n: AccessibilityNodeInfo) { n.refresh(); add(n); for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
            visit(root)
        }
    }
    suspend fun await(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo = withTimeout(8000) {
        while (true) { all().firstOrNull(predicate)?.let { return@withTimeout it }; delay(100) }
        error("unreachable")
    }
    suspend fun click(node: AccessibilityNodeInfo) {
        try { test.uiAutomation.waitForIdle(200, 2000) } catch (_: java.util.concurrent.TimeoutException) { }
        val fresh = all().firstOrNull {
            if (!node.viewIdResourceName.isNullOrEmpty()) it.viewIdResourceName == node.viewIdResourceName
            else if (!node.text.isNullOrEmpty()) it.text == node.text
            else it.contentDescription == node.contentDescription && !node.contentDescription.isNullOrEmpty()
        } ?: node
        val action = generateSequence(fresh) { it.parent }.firstOrNull { it.isClickable && it.isEnabled }
        if (action?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { delay(250); return }
        val bounds = Rect(); fresh.getBoundsInScreen(bounds)
        val now = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { check(test.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        delay(250)
    }
    suspend fun clickTag(tag: String) {
        val node = await { it.viewIdResourceName == tag }
        if (!node.isSelected) click(node)
    }
    private fun visibleText(text: String): AccessibilityNodeInfo? {
        val nodes = all()
        val navigationTop = nodes.firstOrNull { it.viewIdResourceName == "tab_ssh" }?.let {
            Rect().apply { it.getBoundsInScreen(this) }.top
        } ?: test.targetContext.resources.displayMetrics.heightPixels
        return nodes.firstOrNull { node ->
            val bounds = Rect(); node.getBoundsInScreen(bounds)
            node.text?.toString() == text && node.isVisibleToUser && bounds.width() > 0 &&
                bounds.height() > 0 && bounds.top >= 0 && bounds.bottom <= navigationTop
        }
    }
    suspend fun findText(text: String, scroll: Boolean = false): AccessibilityNodeInfo {
        if (scroll) repeat(10) {
            visibleText(text)?.let { return it }
            all().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            delay(250)
        }
        val node = withTimeout(8000) {
            var found = visibleText(text)
            while (found == null) { delay(100); found = visibleText(text) }
            found
        }
        return node
    }
    suspend fun clickText(text: String, scroll: Boolean = false) = click(findText(text, scroll))
}
