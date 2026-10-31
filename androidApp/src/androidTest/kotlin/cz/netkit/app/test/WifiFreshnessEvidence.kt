package cz.netkit.app.test

import android.app.Instrumentation
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
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
import cz.netkit.app.wifi.WifiDatasetRecommendations
import cz.netkit.app.wifi.WifiOccupancyChart
import cz.netkit.shared.wifi.*
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** UI-only evidence with synthetic inputs; does not change a ViewModel, clock, permission or radio. */
suspend fun captureWifiFreshnessEvidence(
    instrumentation: Instrumentation,
    activity: ComponentActivity,
    screenshot: (String) -> Unit,
    marker: (String) -> Unit,
) {
    val received = 1_000_000L
    val ap = AccessPoint("NetKit sample", "02:00:00:00:00:01", -50, 2412,
        channel = 1, band = WifiBand.GHZ_2_4, widthMhz = 20, security = WifiSecurity.WPA2,
        standard = "n", ageMs = 1_000, seenAtElapsedMs = received - 1_000, locallyAdministered = true)
    val baseline = WifiAnalyzerState(accessPoints = listOf(ap), lastUpdatedElapsedMs = received,
        status = WifiScanStatus.READY)
    data class Sample(val name: String, val age: Long, val confirmed: Boolean = true)
    val samples = listOf(Sample("fresh", 29_000), Sample("aging", 31_000),
        Sample("within-five-minutes", 299_000), Sample("expired", 301_000), Sample("cache", 1_000, false))
    val context = instrumentation.targetContext
    for (sample in samples) {
        val state = if (sample.confirmed) baseline else publishScanResult(WifiAnalyzerState(), listOf(ap), false, received)
        val now = received + sample.age
        val requests = AtomicInteger()
        val identity = "SYNTHETIC UI · ${sample.name}"
        instrumentation.runOnMainSync {
            activity.setContent {
                NetKitTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(Spacing.normal),
                            verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                            Text(identity, style = MaterialTheme.typography.labelSmall)
                            WifiDatasetRecommendations(state, now, 20, {}, { requests.incrementAndGet() })
                            WifiOccupancyChart(snapshotAccessPoints(state.accessPoints), WifiBand.GHZ_2_4)
                        }
                    }
                }
            }
        }
        instrumentation.waitForIdleSync()
        awaitWifiText(instrumentation, identity)
        val ageText = if (sample.confirmed) context.getString(R.string.wifi_last_scan_age, sample.age / 1000)
            else context.getString(R.string.wifi_dataset_unknown)
        awaitWifiText(instrumentation, ageText)
        val recommendations = recommendDatasetChannels(state, now)
        // Use an independent, unexpired baseline to detect an incorrectly retained recommendation.
        val recommendation = recommendDatasetChannels(baseline, received).single()
        val detail = context.getString(R.string.wifi_recommendation_detail,
            context.getString(R.string.wifi_band_24), recommendation.channel, recommendation.widthMhz,
            String.format(Locale.getDefault(), "%.3e", recommendation.score))
        if (sample.name in listOf("fresh", "aging", "within-five-minutes")) {
            check(recommendations.isNotEmpty())
            awaitWifiText(instrumentation, detail)
            if (sample.age > 30_000) awaitWifiText(instrumentation, context.getString(R.string.wifi_dataset_aging))
        } else {
            check(recommendations.isEmpty())
            check(findWifiText(instrumentation, detail) == null) { "Expired or unconfirmed recommendation is visible" }
            awaitWifiText(instrumentation, context.getString(if (sample.confirmed)
                R.string.wifi_dataset_expired else R.string.wifi_dataset_unconfirmed))
            var button = awaitWifiText(instrumentation, context.getString(R.string.wifi_scan))
            while (!button.isClickable) button = button.parent ?: error("Scan action is not clickable")
            check(button.isVisibleToUser && button.isEnabled)
            check(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            withTimeout(3_000) { while (requests.get() != 1) delay(25) }
        }
        // AP membership is not erased by age, including the expired and cache cases.
        check(snapshotAccessPoints(state.accessPoints) == listOf(ap))
        instrumentation.uiAutomation.waitForIdle(150, 3_000)
        screenshot("wifi-freshness-${sample.name}.png")
        marker("WIFI:FRESHNESS:${sample.name}:UI_VERIFIED")
    }
}

private suspend fun awaitWifiText(instrumentation: Instrumentation, text: String): AccessibilityNodeInfo =
    withTimeout(5_000) {
        var found = findWifiText(instrumentation, text)
        while (found == null) { delay(50); found = findWifiText(instrumentation, text) }
        found
    }

private fun findWifiText(instrumentation: Instrumentation, text: String): AccessibilityNodeInfo? {
    fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && node.text?.toString() == text) return node
        for (index in 0 until node.childCount) node.getChild(index)?.let { find(it)?.let { result -> return result } }
        return null
    }
    return instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
}

/** Separate synthetic seven-AP fixture for light/dark palette screenshots; not RF evidence. */
suspend fun captureWifiChartEvidence(
    instrumentation: Instrumentation,
    activity: ComponentActivity,
    screenshot: (String) -> Unit,
    marker: (String) -> Unit,
) {
    val points = listOf(1, 2, 4, 6, 8, 11, 13).mapIndexed { index, channel ->
        AccessPoint("Sample ${index + 1}", "02:00:00:00:00:0${index + 1}", -35 - index * 7,
            2407 + channel * 5, channel = channel, band = WifiBand.GHZ_2_4, widthMhz = 20,
            security = WifiSecurity.WPA2, standard = "n", ageMs = 0, locallyAdministered = true)
    }
    val identity = "SYNTHETIC UI · six graph styles"
    instrumentation.runOnMainSync {
        activity.setContent {
            NetKitTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(Spacing.normal),
                        verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                        Text(identity, style = MaterialTheme.typography.labelSmall)
                        WifiOccupancyChart(points, WifiBand.GHZ_2_4)
                    }
                }
            }
        }
    }
    instrumentation.waitForIdleSync()
    awaitWifiText(instrumentation, identity)
    awaitWifiText(instrumentation, instrumentation.targetContext.getString(R.string.wifi_graph_limit, 6, 1))
    check(chartAccessPoints(points, WifiBand.GHZ_2_4).size == 6)
    instrumentation.uiAutomation.waitForIdle(150, 3_000)
    screenshot("wifi-chart-six-styles.png")
    marker("WIFI:CHART:SYNTHETIC_SIX_STYLES:UI_VERIFIED")
}
