package cz.netkit.app.test

import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.ViewModelProvider
import cz.netkit.app.MainActivity
import cz.netkit.app.R
import cz.netkit.app.ssh.*
import cz.netkit.app.ui.Spacing
import cz.netkit.app.ui.theme.NetKitTheme
import cz.netkit.shared.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
suspend fun sshProfilesV2Evidence(test: Instrumentation, app: M1TestApplication,
    screenshot: (String) -> Unit, marker: (String) -> Unit) {
    val profiles = listOf(
        SshProfile("a", "Alpha", "192.0.2.1", username = "tester", authentication = SshAuthentication.KEY),
        SshProfile("b", "Beta", "192.0.2.2", username = "tester", authentication = SshAuthentication.KEY, keyRef = "absent"),
        SshProfile("c", "Gamma", "192.0.2.3", username = "tester", authentication = SshAuthentication.KEY, keyRef = "metadata-only"))
    val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try { SshStore(app, seedScope).update { SshData(profiles = profiles, keys = listOf(SshKeyInfo("metadata-only", "Other key", "public-fixture"))) } }
    finally { seedScope.coroutineContext[Job]!!.cancelAndJoin() }
    val ui = SshEvidenceUi(test)
    fun start() = test.startActivitySync(Intent(app, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }) as MainActivity
    var activity = start()
    lateinit var model: SshViewModel
    fun bind() { test.runOnMainSync { model = ViewModelProvider(activity)[SshViewModel::class.java] } }
    bind()
    try {
        withTimeout(8000) { model.state.first { it.loaded } }
        ui.clickTag("tab_ssh")
        check(!ui.await { it.viewIdResourceName == "ssh_up_a" }.isEnabled)
        check(!ui.await { it.viewIdResourceName == "ssh_down_c" }.isEnabled)
        ui.clickTag("ssh_down_a")
        withTimeout(8000) { model.state.first { !it.busy && it.data.profiles.map { p -> p.id } == listOf("b", "a", "c") } }
        screenshot("ssh-profile-order.png")
        test.runOnMainSync { activity.finish() }
        withTimeout(8000) { while (!activity.isDestroyed) delay(50) }
        activity = start(); bind()
        withTimeout(8000) { model.state.first { it.loaded } }
        check(model.state.value.data.profiles.map { it.id } == listOf("b", "a", "c"))
        ui.clickTag("tab_ssh")
        val output = File(app.filesDir, "m1-evidence/ssh-v2-export.json")
        val ready = CompletableDeferred<Unit>()
        test.runOnMainSync { model.prepareExport { ready.complete(Unit) } }
        withTimeout(8000) { ready.await() }
        test.runOnMainSync { model.export(Uri.fromFile(output)) }
        withTimeout(8000) { model.state.first { it.exported && !it.busy } }
        check(decodeSshProfileImport(output.readBytes()).profiles.map { it.alias } == listOf("Beta", "Alpha", "Gamma"))
        marker("SSH_V2:MOVE_RESTART_EXPORT:PASS")
        val saved = CompletableDeferred<Unit>()
        test.runOnMainSync { model.saveProfile(profiles[0], "", false) { saved.complete(Unit) } }
        withTimeout(8000) { saved.await() }
        check(model.state.value.data.profiles.map { it.id } == listOf("b", "a", "c"))
        marker("SSH_V2:EDIT_PRESERVES_ORDER:PASS")
        // Both missing and dangling references must stop before any transport is added.
        val connectionField = SshViewModel::class.java.getDeclaredField("connections").apply { isAccessible = true }
        fun transportCount() = (connectionField.get(model) as Map<*, *>).size
        for (profile in profiles.take(2)) {
            test.runOnMainSync { model.connect(profile) }
            withTimeout(8000) { model.state.first { it.keySelectionRequired?.id == profile.id } }
            check(model.state.value.sessions.isEmpty() && transportCount() == 0)
            marker("SSH_V2:GUARD_${profile.id}:NO_TRANSPORT")
            ui.findText(app.getString(R.string.ssh_select_profile_key), scroll = true)
            test.runOnMainSync { model.dismissKeySelection() }
        }
        test.runOnMainSync { model.connect(profiles[0]) }
        ui.clickTag("ssh_missing_key_edit")
        ui.findText(app.getString(R.string.ssh_select_profile_key), scroll = true)
        marker("SSH_V2:EDITOR_WARNING_VISIBLE")
        // There is a different key on the device: the editor still explicitly requests selection.
        ui.findText(app.getString(R.string.ssh_save), scroll = true)
        check(!ui.await { it.viewIdResourceName == "ssh_profile_save" }.isEnabled)
        screenshot("ssh-missing-key-editor.png")
        marker("SSH_V2:CONNECT_AND_EDITOR_MISSING_KEY:PASS")
        // End a metadata-only KEY attempt before DNS (no encrypted key exists), then remove its key.
        test.runOnMainSync { model.dismissKeySelection(); model.connect(profiles[2]) }
        val ended = withTimeout(8000) { model.state.first { it.sessions.any { s -> s.ended } } }.sessions.single()
        val keyRemoved = CompletableDeferred<Unit>()
        test.runOnMainSync { model.saveProfile(profiles[2].copy(keyRef = null), "", false) { keyRemoved.complete(Unit) } }
        withTimeout(8000) { keyRemoved.await() }
        test.runOnMainSync { model.deleteKey("metadata-only") }
        withTimeout(8000) { model.state.first { !it.busy && it.data.keys.isEmpty() } }
        val field = SshViewModel::class.java.getDeclaredField("connections").apply { isAccessible = true }
        val before = (field.get(model) as Map<*, *>).toMap()
        test.runOnMainSync { model.reconnect(ended.id, model.reconnectProfile(ended.id)!!) }
        withTimeout(8000) { model.state.first { it.keySelectionRequired?.id == "c" } }
        check((field.get(model) as Map<*, *>).toMap() == before)
        check(model.state.value.sessions.single() === ended)
        marker("SSH_V2:RECONNECT_NO_TRANSPORT:PASS")
        ui.clickTag("ssh_missing_key_edit")
        ui.findText(app.getString(R.string.ssh_create_key), scroll = true)
        ui.clickTag("ssh_editor_create_key")
        ui.await { it.text?.toString() == app.getString(R.string.ssh_generate) }
        ui.click(ui.await { it.contentDescription?.toString() == app.getString(R.string.ssh_back) })
        ui.findText(app.getString(R.string.ssh_select_profile_key), scroll = true)
        marker("SSH_V2:EMPTY_EDITOR_CREATE_KEY_AND_RETURN:PASS")
        test.runOnMainSync { model.dismissKeySelection(); model.closeAllSessions() }
        // Reset only the Activity UI; persisted profiles remain and all sessions are closed.
        test.runOnMainSync { activity.finish() }
        withTimeout(8000) { while (!activity.isDestroyed) delay(50) }
        activity = start(); bind()
        withTimeout(8000) { model.state.first { it.loaded } }
        ui.clickTag("tab_ssh")
        val changed = profiles[0].copy(host = "198.51.100.1", port = 2222, username = "other",
            authentication = SshAuthentication.INTERACTIVE, startupCommand = "show version", keepaliveSeconds = 60)
        val input = File(app.filesDir, "incoming-v2.json").apply { writeBytes(encodeSshProfileExport(SshData(profiles = listOf(changed)))) }
        test.runOnMainSync { model.previewProfileImport(Uri.fromFile(input)) }
        withTimeout(8000) { model.state.first { it.pendingProfileImport != null } }
        for ((label, old, new) in listOf(
            Triple(R.string.ssh_host, "192.0.2.1", "198.51.100.1"), Triple(R.string.ssh_port, "22", "2222"),
            Triple(R.string.ssh_username, "tester", "other"),
            Triple(R.string.ssh_authentication_label, app.getString(R.string.ssh_key), app.getString(R.string.ssh_interactive)),
            Triple(R.string.ssh_startup_command, app.getString(R.string.ssh_none), "show version"),
            Triple(R.string.ssh_keepalive, "30", "60"))) {
            val text = app.getString(R.string.ssh_import_change, app.getString(label), old, new)
            ui.await { it.text?.toString()?.contains(text) == true }
        }
        ui.await { it.text?.toString()?.contains(app.getString(R.string.ssh_import_startup_warning)) == true }
        screenshot("ssh-import-v2-changes.png")
        marker("SSH_V2:ALL_CHANGED_FIELDS_AND_STARTUP_WARNING:PASS")
        test.runOnMainSync { model.cancelProfileImport() }
        // The same production row rendered with busy=true must disable both arrows.
        test.runOnMainSync {
            activity.setContent { NetKitTheme {
                Column(Modifier.safeDrawingPadding().padding(Spacing.normal).semantics { testTagsAsResourceId = true }) {
                    ProfileRow(profiles[1], false, false, true, false, false, {}, {}, {}, { error("Busy move") })
                }
            } }
        }
        check(!ui.await { it.viewIdResourceName == "ssh_up_b" }.isEnabled)
        check(!ui.await { it.viewIdResourceName == "ssh_down_b" }.isEnabled)
        marker("SSH_V2:BUSY_ARROWS_DISABLED:PASS")
    } catch (error: Throwable) {
        screenshot("ssh-v2-failure.png")
        File(app.filesDir, "m1-evidence/ssh-v2-failure-tree.txt").writeText(ui.all().joinToString("\n") {
            "${it.viewIdResourceName}|${it.text}|enabled=${it.isEnabled}|visible=${it.isVisibleToUser}"
        })
        throw error
    } finally { test.runOnMainSync { activity.finish() } }
}
