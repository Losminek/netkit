package cz.netkit.app.speed

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import cz.netkit.app.R
import cz.netkit.app.ui.SegmentedTabs
import cz.netkit.app.ui.Spacing
import cz.netkit.app.wifi.live.SignalScreen
import cz.netkit.app.wifi.live.WifiLiveViewModel

/**
 * Signal tab: segment "Živě" (live telemetry) or "Rychlost" (speed test), same pattern as
 * Devices/Tools. This is the only segmented control of the tab; the live screen picks its chart
 * with a metric picker. Only the visible segment is composed, so the live 1 Hz ICMP/DNS probes are
 * stopped while a speed test runs (they would add load and skew the loaded latency), and leaving
 * the speed segment cancels a running test.
 */
@Composable
fun SignalHost(live: WifiLiveViewModel, speed: SpeedTestViewModel, modifier: Modifier = Modifier,
    updateCard: (@Composable () -> Unit)? = null, onOpenAbout: (() -> Unit)? = null) {
    var segment by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier) {
        SegmentedTabs(
            listOf(stringResource(R.string.wifi_signal_segment_live), stringResource(R.string.speed_segment_speed)),
            segment, { segment = it },
            Modifier.padding(horizontal = Spacing.normal, vertical = Spacing.small),
            testTags = listOf("signal_segment_live", "speed_segment"),
        )
        if (segment == 0) SignalScreen(live, Modifier.weight(1f), topCard = updateCard, onOpenAbout = onOpenAbout)
        else SpeedTestScreen(speed, Modifier.weight(1f))
    }
}
