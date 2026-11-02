package cz.netkit.app.wifi

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import cz.netkit.shared.wifi.WifiScanStatus
import cz.netkit.app.R
import cz.netkit.app.ui.NeutralButton
import cz.netkit.app.ui.Spacing
import cz.netkit.app.ui.theme.NetKitTheme
import cz.netkit.app.ui.theme.NetKitType

@Composable
internal fun WifiScanBudgetNotice(budget: WifiScanBudget, modifier: Modifier = Modifier) {
    Text(if (budget.remaining > 0) stringResource(R.string.wifi_scan_remaining, budget.remaining)
        else stringResource(R.string.wifi_scan_wait_estimate, budget.waitSeconds),
        modifier.testTag("wifi_scan_budget"), style = NetKitType.label, color = NetKitTheme.colors.muted)
}

/** Visible only after a rejected/unconfirmed user scan; never changes a system setting. */
@Composable
internal fun WifiScanThrottleNotice(status: WifiScanStatus) {
    if (status == WifiScanStatus.THROTTLED_OR_FAILED) WifiScanLimitHelp()
}

@Composable
private fun WifiScanLimitHelp() {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Text(stringResource(R.string.wifi_scan_limit_help), style = NetKitType.label, color = NetKitTheme.colors.muted)
        Text(stringResource(R.string.wifi_scan_enable_developer), style = NetKitType.label, color = NetKitTheme.colors.muted)
        NeutralButton(stringResource(R.string.wifi_scan_open_developer), {
            failed = !openWifiDeveloperSettings(context)
        }, Modifier.testTag("wifi_scan_developer"), small = true)
        if (failed) Text(stringResource(R.string.wifi_settings_failed), style = NetKitType.label, color = NetKitTheme.colors.warn)
    }
}

/** Read the global toggle: a resolvable OEM activity may still silently close when disabled. */
internal fun wifiDeveloperSettingsActions(developmentEnabled: Int): List<String> =
    if (developmentEnabled == 0) listOf(Settings.ACTION_DEVICE_INFO_SETTINGS)
    else listOf(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, Settings.ACTION_DEVICE_INFO_SETTINGS)

/** startActivity handles resolution without a package-visibility query; exceptions are a second guard. */
internal fun openWifiDeveloperSettings(
    context: Context,
    launch: (Intent) -> Unit = context::startActivity,
    developmentEnabled: Int = Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0),
): Boolean {
    for (action in wifiDeveloperSettingsActions(developmentEnabled)) {
        val intent = Intent(action)
        try {
            launch(intent)
            return true
        } catch (_: ActivityNotFoundException) { /* Try the documented fallback. */ }
        catch (_: SecurityException) { /* OEM settings activity may be inaccessible. */ }
    }
    return false
}
