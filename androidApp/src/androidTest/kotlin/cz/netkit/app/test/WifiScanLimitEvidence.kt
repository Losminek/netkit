package cz.netkit.app.test

import android.accessibilityservice.AccessibilityService
import android.app.Instrumentation
import android.app.LocaleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.LocaleList
import android.provider.Settings
import androidx.lifecycle.ViewModelProvider
import cz.netkit.app.MainActivity
import cz.netkit.app.R
import cz.netkit.app.wifi.*
import cz.netkit.shared.wifi.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

/** Production screen and Android lifecycle; synthetic failed scan data, no startScan call. */
suspend fun wifiScanLimitEvidence(test: Instrumentation, app: M1TestApplication, language: String,
    marker: (String) -> Unit) {
    require(language in setOf("cs", "en"))
    val prefs = app.getSharedPreferences("update", 0)
    val enabledBefore = prefs.getBoolean("enabled", true)
    val localeManager = app.getSystemService(LocaleManager::class.java)
    val localesBefore = localeManager.applicationLocales
    prefs.edit().putBoolean("enabled", false).commit()
    var activity: MainActivity? = null
    try {
        test.runOnMainSync { localeManager.applicationLocales = LocaleList(Locale.forLanguageTag(language)) }
        activity = test.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var model: WifiViewModel
        test.runOnMainSync { model = ViewModelProvider(activity!!)[WifiViewModel::class.java] }
        val observer = WifiViewModel::class.java.getDeclaredField("passiveObserver").apply { isAccessible = true }
            .get(model) as ForegroundScanObserver
        val ui = SshEvidenceUi(test)
        suspend fun active(expected: Boolean) = withTimeout(5000) { while (observer.active != expected) delay(25) }
        check(!observer.active)
        ui.clickTag("tab_wifi"); active(true)
        ui.clickTag("tab_wol"); active(false)
        ui.clickTag("tab_wifi"); active(true)
        check(test.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
        active(false)
        marker("WIFI_SCAN:$language:LEAVE_AND_ONSTOP_UNREGISTER:PASS")
        // Bring the same task back, as a real foreground transition.
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        active(true)
        val scanJobField = WifiViewModel::class.java.getDeclaredField("scanJob").apply { isAccessible = true }
        val passiveJobField = WifiViewModel::class.java.getDeclaredField("passiveJob").apply { isAccessible = true }
        val activeScan = Job()
        test.runOnMainSync {
            val previousPassive = passiveJobField.get(model)
            scanJobField.set(model, activeScan)
            try {
                WifiViewModel::class.java.getDeclaredMethod("readPassiveResults").apply { isAccessible = true }.invoke(model)
                check(passiveJobField.get(model) === previousPassive)
            } finally { activeScan.cancel(); scanJobField.set(model, null) }
        }
        marker("WIFI_SCAN:$language:PASSIVE_SKIPS_USER_SCAN:PASS")
        val window = WifiViewModel::class.java.getDeclaredField("scanWindow").apply { isAccessible = true }.get(model) as WifiScanWindow
        @Suppress("UNCHECKED_CAST")
        val flow = WifiViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(model) as MutableStateFlow<WifiAnalyzerState>
        test.runOnMainSync {
            repeat(4) { window.accepted() }
            flow.value = publishScanResult(flow.value, emptyList(), false, android.os.SystemClock.elapsedRealtime())
        }
        ui.findText(app.getString(R.string.wifi_scan_limit_help))
        ui.findText(app.getString(R.string.wifi_scan_enable_developer))
        val budget = ui.await { it.viewIdResourceName == "wifi_scan_budget" && it.text?.contains(if (language == "cs") "asi" else "about") == true }
        check(budget.isVisibleToUser)
        val scan = ui.await { it.viewIdResourceName == "wifi_scan_action" }
        check(scan.isEnabled) { "Zero estimated budget must not disable scan" }
        var opened: Intent? = null
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action !in setOf(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, Settings.ACTION_DEVICE_INFO_SETTINGS)) return null
                opened = intent
                return Instrumentation.ActivityResult(0, null)
            }
        }
        test.addMonitor(monitor)
        try {
            ui.clickTag("wifi_scan_developer")
            withTimeout(3000) { while (opened == null) delay(25) }
        } finally { test.removeMonitor(monitor) }
        marker("WIFI_SCAN:$language:HELP_AND_ENABLED_SCAN_AND_INTENT:PASS")

        // OEM/visibility fallback paths without launching or changing real settings.
        val actions = mutableListOf<String?>()
        check(openWifiDeveloperSettings(app, developmentEnabled = 1, launch = { actions += it.action }))
        check(actions == listOf(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        actions.clear()
        check(openWifiDeveloperSettings(app, developmentEnabled = 0, launch = { actions += it.action }))
        check(actions == listOf(Settings.ACTION_DEVICE_INFO_SETTINGS))
        actions.clear()
        check(openWifiDeveloperSettings(app, developmentEnabled = 1, launch = {
            actions += it.action
            if (it.action == Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) throw ActivityNotFoundException()
        }))
        check(actions == listOf(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, Settings.ACTION_DEVICE_INFO_SETTINGS))
        actions.clear()
        check(!openWifiDeveloperSettings(app, developmentEnabled = 1, launch = { actions += it.action; throw ActivityNotFoundException() }))
        check(actions == listOf(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, Settings.ACTION_DEVICE_INFO_SETTINGS))
        marker("WIFI_SCAN:$language:SETTINGS_FALLBACK:PASS")
    } finally {
        test.runOnMainSync { activity?.finish(); localeManager.applicationLocales = localesBefore }
        prefs.edit().putBoolean("enabled", enabledBefore).commit()
    }
}
