package cz.netkit.app.test

import android.app.Instrumentation
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import cz.netkit.app.R
import cz.netkit.app.ui.Spacing
import cz.netkit.app.ui.theme.NetKitTheme
import cz.netkit.app.wifi.AccessPointRow
import cz.netkit.shared.wifi.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * UI only: the vendor line under the SSID for a known prefix, no line for a locally
 * administered BSSID, and the detail texts in the current locale. Synthetic OUI list and BSSIDs.
 */
suspend fun captureWifiVendorEvidence(
    instrumentation: Instrumentation,
    activity: ComponentActivity,
    screenshot: (String) -> Unit,
    marker: (String) -> Unit,
) {
    val database = OuiDatabase.parse("F40000\t24\tExample Vendor Synthetic\n")
    fun point(ssid: String, bssid: String, selected: Boolean) = AccessPoint(ssid, bssid, -50, 2412, channel = 1,
        band = WifiBand.GHZ_2_4, widthMhz = 20, security = WifiSecurity.WPA2, standard = "n", ageMs = 0,
        locallyAdministered = isLocallyAdministered(bssid), isSelected = selected, vendor = database.vendorFor(bssid))
    val context = instrumentation.targetContext
    val identity = "SYNTHETIC UI · OUI vendor"
    val known = point("Known OUI", "F4:00:00:00:00:01", false)
    val local = point("Local BSSID", "F6:00:00:00:00:02", false)
    val unknown = point("Unknown OUI", "F4:00:09:00:00:03", false)
    check(known.vendor == "Example Vendor Synthetic" && local.vendor == null && unknown.vendor == null)
    fun show(label: String, points: List<AccessPoint>) {
        val state = WifiAnalyzerState(accessPoints = points, status = WifiScanStatus.READY)
        instrumentation.runOnMainSync {
            activity.setContent {
                NetKitTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(Spacing.normal)) {
                            Text("$identity · $label", style = MaterialTheme.typography.labelSmall)
                            for ((index, ap) in points.withIndex()) {
                                AccessPointRow(ap, null, showDivider = index > 0, state = state, history = emptyList(),
                                    elapsedNow = 0, onOwn = {}, onSelect = {})
                            }
                        }
                    }
                }
            }
        }
        instrumentation.waitForIdleSync()
    }

    // List: three collapsed rows; the vendor line sits directly under the first SSID only.
    show("list", listOf(known, local, unknown))
    awaitVendorText(instrumentation, "$identity · list")
    val ssid = vendorBounds(awaitVendorText(instrumentation, "Known OUI"))
    val vendor = vendorBounds(awaitVendorText(instrumentation, "Example Vendor Synthetic"))
    check(vendor.top >= ssid.bottom - 1 && vendor.left == ssid.left) { "vendor line is not directly under the SSID: $ssid / $vendor" }
    val localSsid = vendorBounds(awaitVendorText(instrumentation, "Local BSSID"))
    check(vendor.bottom <= localSsid.top) { "vendor line belongs to the first row" }
    // Exactly one vendor line among three rows: local and unknown reserve no line.
    check(countVendorTexts(instrumentation, "Example Vendor Synthetic") == 1)
    check(findVendorText(instrumentation, context.getString(R.string.wifi_vendor_unknown)) == null)
    instrumentation.uiAutomation.waitForIdle(150, 3_000)
    screenshot("wifi-vendor-list.png")
    marker("WIFI:VENDOR:list:UI_VERIFIED")

    // Detail: one expanded row at a time, so each detail text is on screen.
    for ((name, ap, expected) in listOf(
        Triple("known", known, context.getString(R.string.wifi_vendor, "Example Vendor Synthetic")),
        Triple("local", local, context.getString(R.string.wifi_vendor_local)),
        Triple("unknown", unknown, context.getString(R.string.wifi_vendor_unknown)),
    )) {
        show("detail $name", listOf(ap.copy(isSelected = true)))
        awaitVendorText(instrumentation, "$identity · detail $name")
        awaitVendorText(instrumentation, expected)
        instrumentation.uiAutomation.waitForIdle(150, 3_000)
        screenshot("wifi-vendor-detail-$name.png")
    }
    marker("WIFI:VENDOR:detail:UI_VERIFIED")
}

private fun vendorBounds(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)

private suspend fun awaitVendorText(instrumentation: Instrumentation, text: String): AccessibilityNodeInfo =
    withTimeout(5_000) {
        var found = findVendorText(instrumentation, text)
        while (found == null) { delay(50); found = findVendorText(instrumentation, text) }
        found
    }

private fun vendorTextNodes(instrumentation: Instrumentation, text: String): List<AccessibilityNodeInfo> = buildList {
    fun visit(node: AccessibilityNodeInfo) {
        if (node.isVisibleToUser && node.text?.toString() == text) add(node)
        for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
    }
    instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
}

private fun findVendorText(instrumentation: Instrumentation, text: String) = vendorTextNodes(instrumentation, text).firstOrNull()
private fun countVendorTexts(instrumentation: Instrumentation, text: String) = vendorTextNodes(instrumentation, text).size
