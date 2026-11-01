package cz.netkit.app.test

import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import cz.netkit.app.MainActivity
import cz.netkit.app.R
import cz.netkit.app.ssh.*
import cz.netkit.shared.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File

/** R1 regressions exercise production screens and ViewModel with an isolated real store. */
suspend fun sshProfilesR1Evidence(test: Instrumentation, app: M1TestApplication,
    screenshot: (String) -> Unit, marker: (String) -> Unit) {
    val profile = SshProfile("fixture", "Fixture", "192.0.2.10", username = "tester")
    val key = SshKeyInfo("metadata-only", "Other key", "public-fixture")
    val activity = test.startActivitySync(Intent(app, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }) as MainActivity
    lateinit var model: SshViewModel
    test.runOnMainSync { model = ViewModelProvider(activity)[SshViewModel::class.java] }
    val ui = SshEvidenceUi(test)
    val store = SshViewModel::class.java.getDeclaredField("store").apply { isAccessible = true }.get(model) as SshStore
    suspend fun data(value: SshData) {
        withContext(Dispatchers.IO) { store.update { value } }
        withTimeout(8000) { model.state.first { it.data == value && it.loaded && !it.busy } }
    }
    suspend fun prepare(): Boolean {
        val completed = CompletableDeferred<Boolean>()
        test.runOnMainSync { model.prepareExport { completed.complete(true) } }
        withTimeout(8000) { model.state.first { !it.busy } }
        return completed.isCompleted
    }
    try {
        withTimeout(8000) { model.state.first { it.loaded } }
        ui.clickTag("tab_ssh")
        // Invalid exports must never call the callback which opens CreateDocument.
        val duplicate = SshData(profiles = listOf(profile, profile.copy(id = "second")))
        data(duplicate)
        check(!prepare())
        check(model.state.value.exportFailure == SshProfileExportError.DuplicateNames(listOf("Fixture")))
        ui.findText(app.getString(R.string.ssh_export_duplicates, "Fixture"))
        // Also invoke the real menu: it stays in NetKit and displays the specific error.
        ui.clickTag("ssh_more"); ui.clickTag("ssh_export")
        ui.findText(app.getString(R.string.ssh_export_duplicates, "Fixture"))
        check(ui.all().none { it.packageName?.toString() == "com.google.android.documentsui" })
        screenshot("ssh-r1-export-duplicates.png")
        data(SshData(profiles = (1..70).map { profile.copy(id = "p$it", name = "p$it", startupCommand = "ž".repeat(4096)) }))
        check(!prepare())
        check(model.state.value.exportFailure == SshProfileExportError.TooLarge)
        ui.findText(app.getString(R.string.ssh_export_size))
        ui.clickTag("ssh_more"); ui.clickTag("ssh_export")
        ui.findText(app.getString(R.string.ssh_export_size))
        check(ui.all().none { it.packageName?.toString() == "com.google.android.documentsui" })
        screenshot("ssh-r1-export-size.png")
        data(SshData(profiles = listOf(profile)))
        check(prepare())
        data(duplicate) // Changing data while SAF is open must not invalidate the prepared snapshot.
        val output = File(app.filesDir, "m1-evidence/ssh-r1-export.json")
        test.runOnMainSync { model.export(Uri.fromFile(output)) }
        withTimeout(8000) { model.state.first { it.exported && !it.busy } }
        check(decodeSshProfileImport(output.readBytes()).profiles.single().alias == "Fixture")
        marker("SSH_R1:P2_1_EXPORT_PREFLIGHT_AND_SNAPSHOT:PASS")

        data(SshData(keys = listOf(key)))
        val v1 = File(app.filesDir, "r1-v1.json").apply {
            writeText("""{"format":"netkit-ssh-profiles","schema":1,"profiles":[{"alias":"Imported","host":"192.0.2.10","port":22,"username":"tester"}],"skipped":[]}""")
        }
        test.runOnMainSync { model.previewProfileImport(Uri.fromFile(v1)) }
        withTimeout(8000) { model.state.first { it.pendingProfileImport != null } }
        fun auth() = ui.all().firstOrNull { it.viewIdResourceName == "ssh_import_auth_Imported" }?.text?.toString()
        withTimeout(8000) { while (auth() != app.getString(R.string.ssh_key)) delay(50) }
        ui.clickText(app.getString(R.string.ssh_import_password), scroll = true)
        withTimeout(8000) { while (auth() != app.getString(R.string.ssh_password)) delay(50) }
        ui.clickText(key.name, scroll = true)
        withTimeout(8000) { while (auth() != app.getString(R.string.ssh_key)) delay(50) }
        ui.clickTag("ssh_import_confirm")
        withTimeout(8000) { model.state.first { !it.profileImportBusy && it.data.profiles.size == 1 } }
        check(model.state.value.data.profiles.single().let { it.authentication == SshAuthentication.KEY && it.keyRef == key.id })
        marker("SSH_R1:P2_2_PREVIEW_FOLLOWS_SELECTION:PASS")

        data(SshData(profiles = listOf(profile), keys = listOf(key)))
        val keyProfile = profile.copy(authentication = SshAuthentication.KEY)
        val v2 = File(app.filesDir, "r1-v2.json").apply { writeBytes(encodeSshProfileExport(SshData(profiles = listOf(keyProfile)))) }
        test.runOnMainSync { model.previewProfileImport(Uri.fromFile(v2)) }
        withTimeout(8000) { model.state.first { it.pendingProfileImport != null } }
        ui.findText(app.getString(R.string.ssh_select_profile_key), scroll = true)
        screenshot("ssh-r1-import-key-warning.png")
        test.runOnMainSync { model.cancelProfileImport() }
        marker("SSH_R1:P3_4_UPDATE_KEY_WARNING:PASS")

        data(SshData(profiles = listOf(keyProfile), keys = listOf(key)))
        // Accessible management labels carry the profile identity; monogram is captured for visual verification.
        fun hasDescription(node: android.view.accessibility.AccessibilityNodeInfo, description: String): Boolean =
            node.contentDescription?.toString() == description || (0 until node.childCount).any {
                node.getChild(it)?.let { child -> hasDescription(child, description) } == true
            }
        // Compose exposes the description below the tagged clickable wrapper in the unmerged tree.
        check(hasDescription(ui.await { it.viewIdResourceName == "ssh_edit_fixture" }, app.getString(R.string.ssh_edit_profile, "Fixture")))
        check(hasDescription(ui.await { it.viewIdResourceName == "ssh_delete_fixture" }, app.getString(R.string.ssh_delete_profile_action, "Fixture")))
        screenshot("ssh-r1-profile-actions.png")
        marker("SSH_R1:P3_7_NAMED_ACTIONS:PASS")
        test.runOnMainSync { model.connect(keyProfile) }
        ui.clickTag("ssh_missing_key_edit")
        ui.findText(app.getString(R.string.ssh_create_key), scroll = true)
        check(model.state.value.data.keys.isNotEmpty())
        ui.clickTag("ssh_editor_create_key")
        ui.findText(app.getString(R.string.ssh_generate), scroll = true)
        marker("SSH_R1:P3_6_CREATE_WITH_OTHER_KEY:PASS")

        // No network is reached: metadata-only key fails before DNS at encrypted-secret lookup.
        val confirmed = profile.copy(authentication = SshAuthentication.KEY, keyRef = key.id, startupCommand = "show version")
        data(SshData(profiles = listOf(confirmed), keys = listOf(key)))
        test.runOnMainSync { model.connect(confirmed) }
        val ended = withTimeout(8000) { model.state.first { it.sessions.any { s -> s.ended } } }.sessions.single()
        // This is the exact snapshot held by the reconnect dialog before a concurrent edit.
        val dialogProfile = model.reconnectProfile(ended.id)!!
        data(SshData(profiles = listOf(confirmed.copy(host = "198.51.100.1", startupCommand = "show status", keyRef = null)), keys = listOf(key)))
        test.runOnMainSync { model.reconnect(ended.id, dialogProfile) }
        withTimeout(8000) { model.state.first { it.sessions.single().ended && it.sessions.single() !== ended } }
        val sessionProfiles = SshViewModel::class.java.getDeclaredField("sessionProfiles").apply { isAccessible = true }.get(model) as Map<*, *>
        check(sessionProfiles[ended.id] == dialogProfile)
        check(model.state.value.keySelectionRequired == null)
        val previousSession = model.state.value.sessions.single()
        val connections = SshViewModel::class.java.getDeclaredField("connections").apply { isAccessible = true }
        val before = (connections.get(model) as Map<*, *>).toMap()
        data(SshData(keys = listOf(key)))
        test.runOnMainSync { model.reconnect(ended.id, dialogProfile) }
        check(model.state.value.profileUnavailable && model.state.value.keySelectionRequired == null)
        check(model.state.value.sessions.single() === previousSession)
        check((connections.get(model) as Map<*, *>).toMap() == before)
        check(ui.all().none { it.viewIdResourceName == "ssh_missing_key_edit" })
        check(model.state.value.data.profiles.isEmpty())
        marker("SSH_R1:P3_5_CONFIRMED_SNAPSHOT_AND_DELETION:PASS")
        test.runOnMainSync { model.closeAllSessions() }
    } catch (error: Throwable) {
        screenshot("ssh-r1-failure.png")
        File(app.filesDir, "m1-evidence/ssh-r1-tree.txt").writeText(ui.all().joinToString("\n") {
            "${it.viewIdResourceName}|${it.text}|${it.contentDescription}|enabled=${it.isEnabled}"
        })
        throw error
    } finally { test.runOnMainSync { activity.finish() } }
}

/** P3: the SAF result returns without the in-memory snapshot, plus dismissible warnings. */
suspend fun sshProfilesP3Evidence(test: Instrumentation, app: M1TestApplication,
    screenshot: (String) -> Unit, marker: (String) -> Unit) {
    val profile = SshProfile("fixture", "Fixture", "192.0.2.10", username = "tester")
    val activity = test.startActivitySync(Intent(app, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }) as MainActivity
    lateinit var model: SshViewModel
    test.runOnMainSync { model = ViewModelProvider(activity)[SshViewModel::class.java] }
    val ui = SshEvidenceUi(test)
    val store = SshViewModel::class.java.getDeclaredField("store").apply { isAccessible = true }.get(model) as SshStore
    suspend fun data(value: SshData) {
        withContext(Dispatchers.IO) { store.update { value } }
        withTimeout(8000) { model.state.first { it.data == value && it.loaded && !it.busy } }
    }
    suspend fun dismissWarning() {
        ui.clickText(app.getString(R.string.ssh_ok), scroll = true)
        withTimeout(8000) { model.state.first { it.exportFailure == null && !it.profileUnavailable } }
        withTimeout(8000) { while (ui.all().any { it.text?.toString() == app.getString(R.string.ssh_ok) }) delay(50) }
    }
    try {
        withTimeout(8000) { model.state.first { it.loaded } }
        ui.clickTag("tab_ssh")
        data(SshData(profiles = listOf(profile)))
        val ready = CompletableDeferred<Unit>()
        test.runOnMainSync { model.prepareExport { ready.complete(Unit) } }
        withTimeout(8000) { ready.await() }
        // Simulate the exact lost field after process death; keep the real persisted store and SAF result URI.
        SshViewModel::class.java.getDeclaredField("preparedExport").apply { isAccessible = true }.set(model, null)
        data(SshData(profiles = listOf(profile.copy(name = "Current"))))
        val output = File(app.filesDir, "m1-evidence/ssh-p3-export.json").apply { check(parentFile!!.isDirectory || parentFile!!.mkdirs()); writeBytes(byteArrayOf()) }
        test.runOnMainSync { model.export(Uri.fromFile(output)) }
        withTimeout(8000) { model.state.first { it.exported && !it.busy } }
        check(!model.state.value.error && model.state.value.exportFailure == null)
        check(decodeSshProfileImport(output.readBytes()).profiles.single().alias == "Current")
        marker("SSH_P3:LOST_SNAPSHOT_EXPORT:PASS")

        val invalid = listOf(
            SshData(profiles = listOf(profile, profile.copy(id = "second"))) to app.getString(R.string.ssh_export_duplicates, "Fixture"),
            SshData(profiles = (1..70).map { profile.copy(id = "p$it", name = "p$it", startupCommand = "ž".repeat(4096)) }) to app.getString(R.string.ssh_export_size))
        for ((value, message) in invalid) {
            data(value)
            val before = output.readBytes()
            test.runOnMainSync { model.export(Uri.fromFile(output)) }
            withTimeout(8000) { model.state.first { !it.busy && it.exportFailure != null } }
            check(!model.state.value.error && !model.state.value.exported)
            check(before.contentEquals(output.readBytes())) // No truncation on failed fallback validation.
            ui.findText(message)
            dismissWarning()
        }
        marker("SSH_P3:FALLBACK_ERRORS_AND_DISMISS:PASS")
        // Deleted/missing reconnect also has an actionable, dismissible error.
        data(SshData())
        test.runOnMainSync { model.reconnect("missing-session", profile) }
        ui.findText(app.getString(R.string.ssh_profile_unavailable))
        dismissWarning()
        marker("SSH_P3:PROFILE_WARNING_DISMISS:PASS")

        for (auth in listOf(SshAuthentication.KEY, SshAuthentication.INTERACTIVE)) {
            data(SshData(profiles = listOf(profile.copy(secretRef = "fixture-reference"))))
            val input = File(app.filesDir, "p3-auth.json").apply {
                writeBytes(encodeSshProfileExport(SshData(profiles = listOf(profile.copy(authentication = auth)))))
            }
            test.runOnMainSync { model.previewProfileImport(Uri.fromFile(input)) }
            withTimeout(8000) { model.state.first { it.pendingProfileImport != null } }
            ui.findText(app.getString(R.string.ssh_import_secret_moves), scroll = true)
            screenshot("ssh-p3-password-warning-${auth.name}.png")
            test.runOnMainSync { model.cancelProfileImport() }
            withTimeout(8000) { model.state.first { it.pendingProfileImport == null && !it.profileImportBusy } }
        }
        // Check both translations independent of the emulator language.
        for ((language, expected) in listOf("cs" to "Uložené heslo se odpojí a smaže.",
            "en" to "The saved password will be detached and deleted.")) {
            val config = android.content.res.Configuration(app.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(language)) }
            check(app.createConfigurationContext(config).getString(R.string.ssh_import_secret_moves) == expected)
        }
        marker("SSH_P3:AUTH_NEUTRAL_WARNING_CS_EN:PASS")
    } catch (error: Throwable) {
        screenshot("ssh-p3-failure.png")
        throw error
    } finally { test.runOnMainSync { activity.finish() } }
}
