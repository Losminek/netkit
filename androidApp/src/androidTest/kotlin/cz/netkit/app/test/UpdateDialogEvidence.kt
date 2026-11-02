package cz.netkit.app.test

import android.app.Instrumentation
import android.content.Intent
import android.os.LocaleList
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.netkit.app.MainActivity
import cz.netkit.app.R
import cz.netkit.app.ui.theme.NetKitTheme
import cz.netkit.app.update.*
import java.io.File
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Production startup via the existing private-file fixture; no update/network request leaves ART. */
@OptIn(ExperimentalComposeUiApi::class)
suspend fun updateDialogEvidence(test: Instrumentation, app: M1TestApplication, language: String,
    marker: (String) -> Unit) {
    require(language in setOf("cs", "en"))
    val prefs = app.getSharedPreferences("update", 0)
    val before = prefs.all.toMap()
    val localeManager = app.getSystemService(android.app.LocaleManager::class.java)
    val beforeLocales = localeManager.applicationLocales
    val fixture = File(app.filesDir, UPDATE_FIXTURE_FILE)
    var activity: MainActivity? = null
    try {
        prefs.edit().clear().putBoolean("enabled", true).commit()
        fixture.writeText("""{"schema":1,"versionName":"9.9.9","versionCode":90909,"minSdk":29,"pageUrl":"https://www.daniel.losak.cz/netkit","notes":{"cs":"NetKit-Test česká poznámka","en":"NetKit-Test English notes"}}""")
        test.runOnMainSync {
            localeManager.applicationLocales = LocaleList(Locale.forLanguageTag(language))
        }
        activity = test.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var model: UpdateViewModel
        test.runOnMainSync { model = ViewModelProvider(activity!!)[UpdateViewModel::class.java] }
        val ui = SshEvidenceUi(test)
        withTimeout(8000) { model.state.first { it.dialog != null && !it.checking } }
        marker("UPDATE_DIALOG:STARTUP_READY")
        val info = model.state.value.dialog!!
        ui.findText(if (language == "cs") "NetKit-Test česká poznámka" else "NetKit-Test English notes")
        ui.findText(app.getString(R.string.update_available_title, "9.9.9"))
        marker("UPDATE_DIALOG:NOTES_VISIBLE")
        ui.clickTag("update_dialog_close")
        withTimeout(3000) { model.state.first { it.dialog == null } }
        check(model.state.value.card == info)
        val lastCheck = model.state.value.lastCheckMs
        val recreation = test.addMonitor(MainActivity::class.java.name, null, false)
        test.runOnMainSync { activity!!.recreate() }
        activity = test.waitForMonitorWithTimeout(recreation, 8000) as? MainActivity ?: error("No recreated activity")
        test.removeMonitor(recreation)
        test.runOnMainSync {
            check(ViewModelProvider(activity!!)[UpdateViewModel::class.java] === model)
            model.onBackground(); model.onForeground()
        }
        check(model.state.value.dialog == null && model.state.value.lastCheckMs == lastCheck)
        marker("UPDATE_DIALOG:$language:CLOSE_ROTATION_FOREGROUND:PASS")

        // Render the same production dialog with the valid fixture for each remaining action.
        fun render() = test.runOnMainSync {
            val visible = mutableStateOf(true)
            activity!!.setContent {
                NetKitTheme {
                    val state by model.state.collectAsStateWithLifecycle()
                    Column(Modifier.semantics { testTagsAsResourceId = true }) {
                        state.card?.let { UpdateCard(it, onDismiss = { model.dismiss(it) }) }
                    }
                    if (visible.value && state.card != null) UpdateDialog(info, {
                        model.closeDialog(); visible.value = false
                    }) { model.dismiss(info) }
                }
            }
        }
        var opened: Intent? = null
        val intentMonitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW) return null
                opened = intent
                return Instrumentation.ActivityResult(0, null)
            }
        }
        test.addMonitor(intentMonitor)
        try {
            render()
            ui.clickTag("update_dialog_download")
            withTimeout(3000) { while (opened == null) delay(25) }
            check(opened!!.action == Intent.ACTION_VIEW)
            check(opened!!.dataString == localizedUpdateUrl(info.openUrl, language))
            check(model.state.value.dialogClosed)
            withTimeout(3000) { while (ui.all().any { it.viewIdResourceName == "update_dialog_download" }) delay(50) }
        } finally { test.removeMonitor(intentMonitor) }
        marker("UPDATE_DIALOG:$language:DOWNLOAD_INTENT:PASS")
        render()
        ui.clickTag("update_dialog_dismiss")
        withTimeout(3000) { model.state.first { it.card == null && it.dialog == null } }
        withTimeout(3000) { while (prefs.getInt("dismissed_version_code", -1) != info.versionCode) delay(25) }
        check(ui.all().none { it.viewIdResourceName == "update_card" && it.isVisibleToUser })
        marker("UPDATE_DIALOG:$language:DISMISS_CARD:PASS")

        // A later foreground check refreshes a newer card after 24 h, never the startup dialog.
        fixture.writeText(fixture.readText().replace("9.9.9", "10.0.0").replace("90909", "100000"))
        prefs.edit().putLong("last_check_ms", System.currentTimeMillis() - 86_400_001L).commit()
        test.runOnMainSync { model.onBackground(); model.onForeground() }
        withTimeout(8000) { model.state.first { it.info?.versionCode == 100000 && !it.checking } }
        check(model.state.value.card?.versionCode == 100000)
        check(model.state.value.dialog == null && !model.state.value.startupDialogReady)
        marker("UPDATE_DIALOG:$language:FOREGROUND_CARD_ONLY:PASS")
    } finally {
        test.runOnMainSync { activity?.finish() }
        test.runOnMainSync { localeManager.applicationLocales = beforeLocales }
        fixture.delete()
        prefs.edit().clear().apply {
            before.forEach { (key, value) -> when (value) {
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is String -> putString(key, value)
            } }
        }.commit()
    }
}
