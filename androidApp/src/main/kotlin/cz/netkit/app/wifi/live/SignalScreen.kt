package cz.netkit.app.wifi.live

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.netkit.app.BuildConfig
import cz.netkit.app.R
import cz.netkit.app.net.netKindLabel
import cz.netkit.app.overview.CellularCompactCard
import cz.netkit.app.overview.CellularHeroPanel
import cz.netkit.app.overview.ConnectionPathCard
import cz.netkit.app.overview.cellQualityText
import cz.netkit.app.overview.operatorText
import cz.netkit.app.overview.ratText
import cz.netkit.app.overview.signed
import cz.netkit.app.ui.Eyebrow
import cz.netkit.app.ui.GaugeRange
import cz.netkit.app.ui.InfoNote
import cz.netkit.app.ui.LiveBadge
import cz.netkit.app.ui.MetricOption
import cz.netkit.app.ui.MetricPicker
import cz.netkit.app.ui.NetKitIcons
import cz.netkit.app.ui.NeutralButton
import cz.netkit.app.ui.Panel
import cz.netkit.app.ui.PrimaryButton
import cz.netkit.app.ui.ScreenHeader
import cz.netkit.app.ui.SectionCard
import cz.netkit.app.ui.SignalGauge
import cz.netkit.app.ui.Spacing
import cz.netkit.app.ui.StatTile
import cz.netkit.app.ui.StatTiles
import cz.netkit.app.ui.StatusBanner
import cz.netkit.app.ui.StatusKind
import cz.netkit.app.ui.Tag
import cz.netkit.app.ui.TonalButton
import cz.netkit.app.ui.Tone
import cz.netkit.app.ui.chart.ChartLine
import cz.netkit.app.ui.chart.ChartMetric
import cz.netkit.app.ui.chart.FullscreenChart
import cz.netkit.app.ui.chart.TimeSeriesChart
import cz.netkit.app.ui.motion.AnimatedNumber
import cz.netkit.app.ui.motion.MINUS_SIGN
import cz.netkit.app.ui.motion.numberFormat
import cz.netkit.app.lan.HeaderIconButton
import cz.netkit.app.ui.theme.NetKitShape
import cz.netkit.app.ui.theme.NetKitTheme
import cz.netkit.app.ui.theme.NetKitType
import cz.netkit.app.ui.tone
import cz.netkit.shared.cellular.CellRat
import cz.netkit.shared.cellular.cellQuality
import cz.netkit.shared.cellular.primaryDbm
import cz.netkit.shared.chart.ChartEvent
import cz.netkit.shared.chart.ChartEventKind
import cz.netkit.shared.chart.ChartViewport
import cz.netkit.shared.net.NetKind
import cz.netkit.shared.overview.DnsResolverNote
import cz.netkit.shared.overview.OverviewMode
import cz.netkit.shared.overview.RoamingChange
import cz.netkit.shared.overview.overviewMode
import cz.netkit.shared.overview.primarySim
import cz.netkit.shared.overview.roamingChange
import cz.netkit.shared.wifi.validLiveDnsName
import cz.netkit.shared.wifi.validLiveInternetTarget
import cz.netkit.shared.wifi.LIVE_DEFAULT_INTERNET_TARGET
import cz.netkit.shared.wifi.LIVE_SERIES_DEFAULT_CAPACITY
import cz.netkit.shared.wifi.LiveSeries
import cz.netkit.shared.wifi.LiveTimeline
import cz.netkit.shared.wifi.RoamingEvent
import cz.netkit.shared.wifi.SignalQuality
import cz.netkit.shared.wifi.WifiBand
import cz.netkit.shared.wifi.WifiSecurity
import cz.netkit.shared.wifi.shortBssid
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

private const val ROAMING_VISIBLE = 5
/** Y ticks of the mobile signal chart on [GaugeRange.CELLULAR]. */
private val CELLULAR_TICKS = listOf(-140f, -120f, -100f, -80f, -60f)
/** Widest share of a latency row the value may take; the label keeps the rest minus the gap. */
private const val LIVE_ROW_VALUE_SHARE = 0.55f
/** Default chart window (docs/design.md §6): 5 minutes, always filling the width. */
private const val CHART_WINDOW_SECONDS = 300
/** Access point info sits next to the gauge only from this content width (prototype `heroLayout`). */
private val HERO_SIDE_BY_SIDE_MIN = 320.dp

/** The three charts; [key] is shared with [FullscreenChart] (`chart_metric_<key>` tags). */
private enum class LiveMetric(val key: String) { SIGNAL("signal"), TRAFFIC("traffic"), LATENCY("latency") }

/**
 * Overview ("Přehled", M3 layout of the prototype `vSignal`, extended in M4 A3): header with the
 * connection type, path card (provider → AP → phone), the main signal card (Wi-Fi gauge, or the
 * mobile RSRP gauge without Wi-Fi) with a compact mobile card under the Wi-Fi one, one chart
 * chosen by the metric picker and expandable to full screen, latency on the active network,
 * roaming history and one explanation note.
 * Sampling runs only while this screen is composed AND the app is resumed
 * ([LifecycleResumeEffect] → [WifiLiveViewModel.setActive]).
 */
@Composable
fun SignalScreen(viewModel: WifiLiveViewModel, modifier: Modifier = Modifier, topCard: (@Composable () -> Unit)? = null,
    onOpenAbout: (() -> Unit)? = null) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.setActive(true)
        onPauseOrDispose { viewModel.setActive(false) }
    }
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var permissionBlocked by rememberSaveable { mutableStateOf(false) }
    var settingsFailed by rememberSaveable { mutableStateOf(false) }
    var metricIndex by rememberSaveable { mutableIntStateOf(0) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var targetDialog by rememberSaveable { mutableStateOf(false) }
    // O-4: one system dialog on request; after a denial the card shows a note and never asks by itself.
    var phoneDenied by rememberSaveable { mutableStateOf(false) }
    val phoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        phoneDenied = !granted
        if (granted) viewModel.restartCellular()
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        // Denied without rationale = "don't ask again": the system dialog will not show again.
        permissionBlocked = !granted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
    }
    fun openSettings(intent: Intent) {
        settingsFailed = try {
            context.startActivity(intent)
            false
        } catch (_: ActivityNotFoundException) {
            true
        }
    }
    val mode = overviewMode(state.network?.kind, state.link != null, state.hasTelephony)
    val wifiMode = mode == OverviewMode.WIFI || mode == OverviewMode.NO_TELEPHONY
    val issues = remember(state.issues, wifiMode, mode) {
        state.issues.filter { issue ->
            wifiMode || issue !in NON_WIFI_HIDDEN_ISSUES && (issue != WifiLiveIssue.PERMISSION_MISSING || mode == OverviewMode.CELLULAR)
        }.sortedBy { it.ordinal }
    }
    val metric = LiveMetric.entries[metricIndex.coerceIn(0, LiveMetric.entries.lastIndex)]
    val events = remember(mode, state.chartEvents, state.cellularChartEvents) { chartEventsFor(mode, state) }
    val requestPhone = { phoneLauncher.launch(Manifest.permission.READ_PHONE_STATE) }

    LazyColumn(
        modifier.fillMaxSize().testTag("signal_screen"),
        contentPadding = PaddingValues(start = Spacing.normal, end = Spacing.normal, bottom = Spacing.normal),
        verticalArrangement = Arrangement.spacedBy(Spacing.normal),
    ) {
        item(key = "header") { Header(state, mode, onOpenAbout) }
        // Update card (D14) right under the header; it is the only card the user can close.
        topCard?.let { card -> item(key = "update") { card() } }
        items(issues, key = { "issue_${it.name}" }) { issue ->
            IssueBlock(
                issue = issue,
                cellularMode = mode == OverviewMode.CELLULAR,
                permissionBlocked = permissionBlocked,
                onRequestPermission = {
                    permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                },
                onAppSettings = {
                    openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)))
                },
                onLocationSettings = { openSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
            )
        }
        if (settingsFailed) item(key = "settings_failed") {
            StatusBanner(stringResource(R.string.wifi_signal_settings_failed), StatusKind.ERROR)
        }
        item(key = "path") {
            ConnectionPathCard(state, mode, apDetail = apDetail(state.link), onRefresh = viewModel::refreshPublicIp,
                onLookupChange = viewModel::setPublicIpLookup)
        }
        when (mode) {
            OverviewMode.WIFI -> {
                item(key = "hero") { HeroPanel(state) }
                // A phone without any SIM on Wi-Fi: the mobile card would only say "Bez SIM".
                if (state.hasTelephony && state.cellular?.noSim != true) item(key = "cellular") {
                    CellularCompactCard(state, phoneDenied, requestPhone)
                }
            }
            OverviewMode.CELLULAR -> item(key = "cellular_hero") { CellularHeroPanel(state, phoneDenied, requestPhone) }
            OverviewMode.ETHERNET -> if (state.hasTelephony) item(key = "cellular") { CellularCompactCard(state, phoneDenied, requestPhone) }
            OverviewMode.NO_TELEPHONY -> item(key = "hero") { HeroPanel(state) }
        }
        item(key = "chart") {
            ChartPanel(state, mode, metric, events, onSelect = { metricIndex = it.ordinal }, onExpand = { expanded = true })
        }
        item(key = "latency") { LatencyPanel(state, gatewayRow = wifiMode, onChangeTarget = { targetDialog = true }) }
        if (state.roamingEvents.isNotEmpty()) item(key = "roaming") { RoamingPanel(state.roamingEvents) }
        item(key = "note") {
            InfoNote(stringResource(R.string.wifi_signal_sources_summary), stringResource(R.string.wifi_signal_sources_detail))
        }
    }

    if (expanded) {
        val metrics = LiveMetric.entries.map { chartMetric(state, mode, it) }
        FullscreenChart(
            metrics = metrics, selectedKey = metric.key,
            onSelect = { key -> LiveMetric.entries.firstOrNull { it.key == key }?.let { metricIndex = it.ordinal } },
            capacity = LIVE_SERIES_DEFAULT_CAPACITY, sampleIntervalMs = state.sampleIntervalMs,
            events = events, onClose = { expanded = false }, slotTimes = state.timeline.slots,
        )
    }
    if (targetDialog) {
        TargetDialog(
            current = state.internetLatency.target ?: LIVE_DEFAULT_INTERNET_TARGET,
            currentDnsName = state.dns.name,
            onDismiss = { targetDialog = false },
            onSubmit = { target, dnsName ->
                // Validate both before applying either, so a bad name does not half-apply the dialog.
                val targetOk = validLiveInternetTarget(target) != null
                val nameOk = validLiveDnsName(dnsName) != null
                if (targetOk && nameOk) { viewModel.setInternetTarget(target); viewModel.setDnsName(dnsName); targetDialog = false }
                targetOk to nameOk
            },
        )
    }
}

// --- Header and issues -------------------------------------------------------------------------

/** Wi-Fi issues that mean nothing without Wi-Fi (mobile data or ethernet): the card shows its own states. */
private val NON_WIFI_HIDDEN_ISSUES = setOf(WifiLiveIssue.NO_WIFI, WifiLiveIssue.NO_GATEWAY, WifiLiveIssue.LOCATION_DISABLED,
    WifiLiveIssue.LINK_MONITOR_FAILED)

/**
 * Title and subtitle by connection type: Wi-Fi "SSID / Připojeno · 5 GHz · kanál 100", mobile data
 * "Mobilní data / O2 · 5G NSA", "Ethernet / Připojeno", VPN "VPN / přes <underlying>" (Wi-Fi SSID
 * or operator, when known), no network "Bez připojení".
 */
@Composable
private fun Header(state: WifiLiveState, mode: OverviewMode, onOpenAbout: (() -> Unit)?) {
    val link = state.link
    val kind = state.network?.kind
    val wifiDetail = link?.let {
        listOfNotNull(bandText(it.band), it.channel?.let { channel -> stringResource(R.string.wifi_signal_channel, channel) })
    }.orEmpty()
    val sim = primarySim(state.cellular?.sims.orEmpty())
    val cellDetail = listOfNotNull(sim?.let { operatorText(it) }, sim?.rat?.takeIf { it != CellRat.UNKNOWN }?.let { stringResource(ratText(it)) })
    val title: String
    val subtitle: String?
    when {
        kind == NetKind.VPN -> {
            title = stringResource(R.string.net_kind_vpn)
            val underlying = when (mode) {
                OverviewMode.WIFI -> listOf(networkName(link)) + wifiDetail
                OverviewMode.CELLULAR -> cellDetail.ifEmpty { listOf(stringResource(R.string.net_kind_cellular)) }
                else -> emptyList()
            }
            subtitle = underlying.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.overview_via, it.joinToString(" · ")) }
        }
        mode == OverviewMode.WIFI || mode == OverviewMode.NO_TELEPHONY -> {
            title = networkName(link)
            subtitle = link?.let { (listOf(stringResource(R.string.wifi_signal_connected_state)) + wifiDetail).joinToString(" · ") }
        }
        kind == NetKind.ETHERNET -> {
            title = stringResource(R.string.net_kind_ethernet)
            subtitle = stringResource(R.string.wifi_signal_connected_state)
        }
        kind == NetKind.CELLULAR -> {
            title = stringResource(R.string.net_kind_cellular)
            subtitle = cellDetail.joinToString(" · ").ifEmpty { null }
        }
        else -> {
            title = stringResource(if (kind == null) R.string.net_kind_none else netKindLabel(kind))
            subtitle = if (state.cellular?.airplaneMode == true) stringResource(R.string.cellular_airplane)
                else cellDetail.joinToString(" · ").ifEmpty { null }
        }
    }
    ScreenHeader(title, subtitle, trailing = {
        if (state.running) LiveBadge(stringResource(R.string.wifi_signal_live, (state.sampleIntervalMs / 1_000L).toInt().coerceAtLeast(1)))
        else Tag(stringResource(R.string.wifi_signal_paused))
        onOpenAbout?.let { HeaderMenu(it) }
    })
}

/** The one header action of Přehled: a menu with "O aplikaci" (zadání 21); no extra tab. */
@Composable
private fun HeaderMenu(onOpenAbout: () -> Unit) {
    val colors = NetKitTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        HeaderIconButton(NetKitIcons.More, stringResource(R.string.overview_more), { open = true }, Modifier.testTag("overview_menu"))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = NetKitShape.nested,
            containerColor = colors.surface2, tonalElevation = 0.dp,
            modifier = Modifier.semantics { testTagsAsResourceId = BuildConfig.DEBUG }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.about_title), style = NetKitType.body, color = colors.text) },
                onClick = { open = false; onOpenAbout() },
                leadingIcon = { Icon(NetKitIcons.Info, null, Modifier.size(18.dp), tint = colors.muted) },
                modifier = Modifier.testTag("overview_menu_about"),
            )
        }
    }
}

@Composable
private fun networkName(link: LiveLink?): String = when {
    link == null -> stringResource(R.string.wifi_signal_not_connected)
    link.ssid.isNullOrEmpty() -> stringResource(R.string.wifi_signal_hidden_ssid)
    else -> link.ssid
}

/** "5 GHz · kanál 100" under the access point node. */
@Composable
private fun apDetail(link: LiveLink?): String? = link?.let {
    listOfNotNull(bandText(it.band), it.channel?.let { channel -> stringResource(R.string.wifi_signal_channel, channel) })
        .joinToString(" · ").ifEmpty { null }
}

@Composable
private fun IssueBlock(
    issue: WifiLiveIssue,
    cellularMode: Boolean,
    permissionBlocked: Boolean,
    onRequestPermission: () -> Unit,
    onAppSettings: () -> Unit,
    onLocationSettings: () -> Unit,
) {
    // Without Wi-Fi the location permission matters for the operator name only.
    val text = when {
        issue == WifiLiveIssue.PERMISSION_MISSING && cellularMode -> stringResource(R.string.cellular_location_note)
        issue == WifiLiveIssue.PERMISSION_MISSING -> stringResource(R.string.wifi_signal_issue_permission)
        else -> stringResource(wifiLiveIssueText(issue))
    }
    val kind = when (issue) {
        WifiLiveIssue.LINK_MONITOR_FAILED -> StatusKind.ERROR
        WifiLiveIssue.NO_WIFI, WifiLiveIssue.PERMISSION_MISSING, WifiLiveIssue.LOCATION_DISABLED,
        WifiLiveIssue.ICMP_UNAVAILABLE -> StatusKind.WARNING
        WifiLiveIssue.NO_GATEWAY, WifiLiveIssue.TRAFFIC_STATS_UNAVAILABLE -> StatusKind.INFO
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        StatusBanner(text, kind)
        when (issue) {
            WifiLiveIssue.PERMISSION_MISSING -> TonalButton(
                stringResource(if (permissionBlocked) R.string.wifi_signal_open_app_settings else R.string.wifi_signal_allow_location),
                onClick = if (permissionBlocked) onAppSettings else onRequestPermission, small = true,
            )
            WifiLiveIssue.LOCATION_DISABLED ->
                TonalButton(stringResource(R.string.wifi_signal_open_location_settings), onClick = onLocationSettings, small = true)
            else -> Unit
        }
    }
}

// --- Hero --------------------------------------------------------------------------------------

@Composable
private fun HeroPanel(state: WifiLiveState) {
    val link = state.link
    val rssi = link?.rssiDbm
    val dash = stringResource(R.string.wifi_signal_no_value)
    Panel {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gauge = @Composable {
                SignalGauge(
                    fraction = null, range = GaugeRange.WIFI,
                    value = rssi?.toString() ?: dash,
                    caption = "",
                    tone = qualityTone(state.quality),
                    dbm = rssi?.toDouble(),
                    qualityLabel = stringResource(signalQualityText(state.quality)),
                    unit = stringResource(R.string.wifi_signal_unit_dbm),
                )
            }
            if (maxWidth < HERO_SIDE_BY_SIDE_MIN) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
                    gauge()
                    AccessPointInfo(link, centered = true, Modifier.fillMaxWidth())
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.normal)) {
                    gauge()
                    AccessPointInfo(link, centered = false, Modifier.weight(1f))
                }
            }
        }
        StatTiles(listOf(
            StatTile(stringResource(R.string.wifi_signal_phy), link?.rxLinkMbps?.toString() ?: dash,
                unit = stringResource(R.string.wifi_signal_phy_tx_unit, link?.txLinkMbps?.toString() ?: dash)),
            StatTile(stringResource(R.string.wifi_signal_ipv4), link?.ipv4Address ?: dash, weight = 1.45f,
                monospace = link?.ipv4Address != null),
            StatTile(stringResource(R.string.wifi_signal_gateway), link?.gatewayIpv4 ?: dash, weight = 1.45f,
                monospace = link?.gatewayIpv4 != null),
        ))
    }
}

@Composable
private fun AccessPointInfo(link: LiveLink?, centered: Boolean, modifier: Modifier = Modifier) {
    val colors = NetKitTheme.colors
    val align = if (centered) TextAlign.Center else TextAlign.Start
    Column(modifier, horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(Spacing.tiny)) {
        Eyebrow(stringResource(R.string.wifi_signal_access_point))
        val bssid = link?.bssid
        if (bssid != null) Text(bssid.uppercase(Locale.ROOT), style = NetKitType.address, color = colors.text, textAlign = align)
        else Text(stringResource(R.string.wifi_signal_bssid_unknown), style = NetKitType.body, color = colors.muted, textAlign = align)
        val standard = link?.wifiStandard?.let { standardText(it, link.band) }
        val security = link?.security?.takeUnless { it == WifiSecurity.UNKNOWN }?.let { stringResource(securityText(it)) }
        if (standard != null || security != null) {
            FlowRow(Modifier.padding(top = Spacing.tiny),
                horizontalArrangement = Arrangement.spacedBy(Spacing.small, if (centered) Alignment.CenterHorizontally else Alignment.Start),
                verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                standard?.let { Tag(it, Tone.ACCENT, dot = true) }
                security?.let { Tag(it) }
            }
        }
    }
}

@Composable
private fun bandText(band: WifiBand?): String? = when (band) {
    WifiBand.GHZ_2_4 -> stringResource(R.string.wifi_signal_band_24)
    WifiBand.GHZ_5 -> stringResource(R.string.wifi_signal_band_5)
    WifiBand.GHZ_6 -> stringResource(R.string.wifi_signal_band_6)
    WifiBand.UNKNOWN, null -> null
}

@Composable
private fun standardText(standard: LiveWifiStandard, band: WifiBand): String = when (standard) {
    LiveWifiStandard.LEGACY -> stringResource(R.string.wifi_signal_standard_legacy)
    LiveWifiStandard.N -> stringResource(R.string.wifi_signal_standard, "4")
    LiveWifiStandard.AC -> stringResource(R.string.wifi_signal_standard, "5")
    LiveWifiStandard.AX -> stringResource(R.string.wifi_signal_standard, if (band == WifiBand.GHZ_6) "6E" else "6")
    LiveWifiStandard.BE -> stringResource(R.string.wifi_signal_standard, "7")
    LiveWifiStandard.AD -> stringResource(R.string.wifi_signal_standard_ad)
}

private fun securityText(security: WifiSecurity): Int = when (security) {
    WifiSecurity.OPEN -> R.string.wifi_security_open
    WifiSecurity.WEP -> R.string.wifi_security_wep
    WifiSecurity.WPA -> R.string.wifi_security_wpa
    WifiSecurity.WPA2 -> R.string.wifi_security_wpa2
    WifiSecurity.WPA3 -> R.string.wifi_security_wpa3
    WifiSecurity.WPA2_WPA3 -> R.string.wifi_security_wpa2_wpa3
    WifiSecurity.OWE_TRANSITION -> R.string.wifi_security_owe_transition
    WifiSecurity.OWE -> R.string.wifi_security_owe
    WifiSecurity.UNKNOWN -> R.string.wifi_security_unknown
}

// --- Chart -------------------------------------------------------------------------------------

/**
 * Chart events of [mode]: Wi-Fi roaming, channel changes and packet loss on Wi-Fi; without Wi-Fi
 * the mobile events (standard change, data SIM switch) and packet loss of the internet target.
 */
private fun chartEventsFor(mode: OverviewMode, state: WifiLiveState): List<ChartEvent> =
    if (mode == OverviewMode.CELLULAR) (state.cellularChartEvents + state.chartEvents.filter { it.kind == ChartEventKind.PACKET_LOSS })
        .sortedBy { it.index }
    else state.chartEvents

@Composable
private fun ChartPanel(state: WifiLiveState, mode: OverviewMode, metric: LiveMetric, events: List<ChartEvent>,
    onSelect: (LiveMetric) -> Unit, onExpand: () -> Unit) {
    val colors = NetKitTheme.colors
    val internet = state.internetLatency
    val signalNow = if (mode == OverviewMode.CELLULAR) primaryDbm(primarySim(state.cellular?.sims.orEmpty())?.signal)?.toDouble()
        else state.link?.rssiDbm?.toDouble()
    Panel {
        MetricPicker(
            options = listOf(
                MetricOption(stringResource(R.string.wifi_signal_metric_signal), signalNow, 0,
                    stringResource(R.string.wifi_signal_unit_dbm), colors.accent, testTag = "signal_metric_signal"),
                MetricOption(stringResource(R.string.wifi_signal_series_rx), state.deviceRxMbps.stats.last, 1,
                    stringResource(R.string.wifi_signal_unit_mbps), colors.info, testTag = "signal_metric_traffic"),
                MetricOption(stringResource(R.string.wifi_signal_latency_internet),
                    internet.series.stats.last.takeIf { internet.status == LiveProbeStatus.OK }, 1,
                    stringResource(R.string.wifi_signal_unit_ms), colors.info, testTag = "signal_metric_latency"),
            ),
            selected = metric.ordinal, onSelect = { onSelect(LiveMetric.entries[it]) },
        )
        val chart = chartMetric(state, mode, metric)
        val viewport = remember(state.sampleIntervalMs) {
            ChartViewport((CHART_WINDOW_SECONDS * 1_000L / state.sampleIntervalMs).toInt().coerceAtLeast(1))
        }
        TimeSeriesChart(
            lines = chart.lines, capacity = LIVE_SERIES_DEFAULT_CAPACITY, sampleIntervalMs = state.sampleIntervalMs,
            range = chart.range, ticks = chart.ticks, unitLabel = chart.unitLabel,
            description = chartDescription(state, mode, metric), events = events, viewport = viewport,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Legend(chart.lines, Modifier.weight(1f))
            NeutralButton(stringResource(R.string.wifi_signal_expand), onClick = onExpand, icon = NetKitIcons.Expand, small = true,
                modifier = Modifier.testTag("signal_chart_expand"))
        }
    }
}

/** Series key under the chart: a 14 dp stroke (dashed like the line) and the series name. */
@Composable
private fun Legend(lines: List<ChartLine>, modifier: Modifier = Modifier) {
    val colors = NetKitTheme.colors
    FlowRow(modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.tiny)) {
        lines.forEach { line ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Canvas(Modifier.size(width = 14.dp, height = 4.dp)) {
                    val y = size.height / 2
                    drawLine(line.color, Offset(0f, y), Offset(size.width, y), strokeWidth = 3.dp.toPx(),
                        pathEffect = if (line.dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null)
                }
                Text(line.label, style = NetKitType.label.copy(fontWeight = FontWeight.SemiBold), color = colors.muted, maxLines = 1)
            }
        }
    }
}

/**
 * Lines of [metric] on the common [WifiLiveState.timeline]; shared by the card and the full-screen
 * chart. Without Wi-Fi the signal metric is the mobile RSRP on the −140…−44 dBm scale.
 */
@Composable
private fun chartMetric(state: WifiLiveState, mode: OverviewMode, metric: LiveMetric): ChartMetric {
    val colors = NetKitTheme.colors
    val timeline = state.timeline
    val subtitle = fullscreenSubtitle(state, mode)
    return when (metric) {
        LiveMetric.SIGNAL -> if (mode == OverviewMode.CELLULAR) ChartMetric(
            metric.key, stringResource(R.string.wifi_signal_metric_signal), subtitle,
            listOf(ChartLine(stringResource(R.string.cellular_rsrp), rememberAligned(timeline, state.cellularSignalDbm), colors.accent)),
            range = GaugeRange.CELLULAR, ticks = CELLULAR_TICKS,
            unitLabel = stringResource(R.string.wifi_signal_unit_dbm), decimals = 0,
        ) else ChartMetric(
            metric.key, stringResource(R.string.wifi_signal_metric_signal), subtitle,
            listOf(ChartLine(stringResource(R.string.wifi_signal_series_rssi), rememberAligned(timeline, state.signalDbm), colors.accent)),
            range = GaugeRange.WIFI, ticks = listOf(-90f, -70f, -50f, -30f),
            unitLabel = stringResource(R.string.wifi_signal_unit_dbm), decimals = 0,
        )
        LiveMetric.TRAFFIC -> ChartMetric(
            metric.key, stringResource(R.string.wifi_signal_metric_traffic), subtitle,
            listOf(
                ChartLine(stringResource(R.string.wifi_signal_series_rx), rememberAligned(timeline, state.deviceRxMbps), colors.info),
                ChartLine(stringResource(R.string.wifi_signal_series_tx), rememberAligned(timeline, state.deviceTxMbps), colors.violet,
                    dashed = true),
            ),
            range = null, ticks = null, unitLabel = stringResource(R.string.wifi_signal_unit_mbps), decimals = 1,
        )
        LiveMetric.LATENCY -> ChartMetric(
            metric.key, stringResource(R.string.wifi_signal_metric_latency), subtitle,
            listOfNotNull(
                ChartLine(stringResource(R.string.wifi_signal_latency_internet),
                    rememberAligned(timeline, state.internetLatency.series), colors.info),
                // The gateway is measured on Wi-Fi only.
                if (mode == OverviewMode.WIFI || mode == OverviewMode.NO_TELEPHONY) ChartLine(stringResource(R.string.wifi_signal_latency_gateway),
                    rememberAligned(timeline, state.gatewayLatency.series), colors.accent, fill = false) else null,
            ),
            range = null, ticks = null, unitLabel = stringResource(R.string.wifi_signal_unit_ms), decimals = 1,
        )
    }
}

@Composable
private fun rememberAligned(timeline: LiveTimeline, series: LiveSeries): List<Float?> =
    remember(timeline, series) { timeline.alignedValues(series.samples) }

/** "SSID · k. 100" (Wi-Fi) or "O2 · 5G NSA" (mobile) under the full-screen title. */
@Composable
private fun fullscreenSubtitle(state: WifiLiveState, mode: OverviewMode): String {
    if (mode == OverviewMode.CELLULAR) {
        val sim = primarySim(state.cellular?.sims.orEmpty()) ?: return stringResource(R.string.cellular_title)
        return listOfNotNull(operatorText(sim), sim.rat.takeIf { it != CellRat.UNKNOWN }?.let { stringResource(ratText(it)) })
            .joinToString(" · ")
    }
    val link = state.link
    val name = networkName(link)
    val channel = link?.channel ?: return name
    return stringResource(R.string.wifi_signal_fullscreen_subtitle, name, channel)
}

@Composable
private fun chartDescription(state: WifiLiveState, mode: OverviewMode, metric: LiveMetric): String {
    val dash = stringResource(R.string.wifi_signal_no_value)
    val minutes = CHART_WINDOW_SECONDS / 60
    val format = rememberDecimalFormat(1)
    fun text(value: Double?) = value?.let { format.format(it) } ?: dash
    return when (metric) {
        LiveMetric.SIGNAL -> if (mode == OverviewMode.CELLULAR) {
            val signal = primarySim(state.cellular?.sims.orEmpty())?.signal
            stringResource(R.string.cellular_chart_signal, minutes, primaryDbm(signal)?.let(::signed) ?: dash,
                stringResource(cellQualityText(cellQuality(signal))))
        } else stringResource(R.string.wifi_signal_chart_signal, minutes,
            state.link?.rssiDbm?.toString()?.replace('-', MINUS_SIGN) ?: dash, stringResource(signalQualityText(state.quality)))
        LiveMetric.TRAFFIC -> stringResource(R.string.wifi_signal_chart_throughput, minutes,
            text(state.deviceRxMbps.stats.last), text(state.deviceTxMbps.stats.last))
        LiveMetric.LATENCY -> stringResource(R.string.wifi_signal_chart_latency, minutes,
            text(state.gatewayLatency.series.stats.last), text(state.internetLatency.series.stats.last))
    }
}

// --- Latency -----------------------------------------------------------------------------------

/**
 * Latency on the active network: gateway (Wi-Fi only, [gatewayRow]), internet target, DNS with
 * the resolver (O-5), packet loss and jitter. Notes (address, name) sit on a second line under
 * the label in mono `dim`.
 */
@Composable
private fun LatencyPanel(state: WifiLiveState, gatewayRow: Boolean, onChangeTarget: () -> Unit) {
    val gateway = state.gatewayLatency
    val internet = state.internetLatency
    val dns = state.dns
    val unitMs = stringResource(R.string.wifi_signal_unit_ms)
    val pair = rememberPairFormat()
    SectionCard(title = stringResource(R.string.wifi_signal_metric_latency), trailing = {
        TonalButton(stringResource(R.string.wifi_signal_change_target), onClick = onChangeTarget, small = true,
            modifier = Modifier.testTag("signal_change_target"))
    }) {
        Column(Modifier.fillMaxWidth()) {
            if (gatewayRow) ProbeRow(stringResource(R.string.wifi_signal_latency_gateway), gateway, unitMs, showDivider = false)
            ProbeRow(stringResource(R.string.wifi_signal_latency_internet), internet, unitMs, showDivider = gatewayRow)
            val dnsMs = dns.lastMs.takeIf { dns.status == LiveDnsStatus.OK }
            LiveRow(stringResource(R.string.wifi_signal_dns_label), listOfNotNull(resolverNote(dns.resolver), monoNote(dns.name)),
                value = dnsMs, unit = unitMs, status = if (dnsMs == null) dnsStatusText(dns) else null)
            val gatewayStats = gateway.series.stats
            val internetStats = internet.series.stats
            val targets = textNote(stringResource(if (gatewayRow) R.string.wifi_signal_pair_targets else R.string.wifi_signal_internet_target))
            val loss = if (gatewayRow) listOfNotNull(gatewayStats.lossPercent, internetStats.lossPercent).maxOrNull() else internetStats.lossPercent
            LiveRow(stringResource(R.string.wifi_signal_packet_loss), listOf(targets), value = null, unit = null,
                status = if (gatewayRow) pair(gatewayStats.lossPercent, internetStats.lossPercent, "%")
                    else pair.single(internetStats.lossPercent, "%"),
                statusTone = lossTone(loss))
            LiveRow(stringResource(R.string.wifi_signal_jitter), listOf(targets), value = null, unit = null,
                status = if (gatewayRow) pair(gatewayStats.jitter, internetStats.jitter, unitMs) else pair.single(internetStats.jitter, unitMs))
        }
    }
}

@Composable
private fun ProbeRow(label: String, latency: LiveLatency, unit: String, showDivider: Boolean = true) {
    val value = latency.series.stats.last.takeIf { latency.status == LiveProbeStatus.OK }
    val status = if (value == null) {
        liveProbeStatusText(latency.status)?.let { stringResource(it) } ?: stringResource(R.string.wifi_signal_no_value)
    } else null
    LiveRow(label, listOfNotNull(latency.target?.let(::monoNote)), value, unit, status,
        if (latency.status == LiveProbeStatus.LOST) Tone.ERR else Tone.NEUTRAL, showDivider)
}

/** "192.168.0.1" (mono) or "Soukromé DNS · dns.example" (host in mono); `null` when unknown. */
@Composable
private fun resolverNote(note: DnsResolverNote): AnnotatedString? = when (note) {
    is DnsResolverNote.Resolver -> monoNote(note.address)
    is DnsResolverNote.PrivateDns -> {
        val label = stringResource(R.string.overview_dns_private_auto)
        val mono = monoSpan()
        buildAnnotatedString {
            append(label)
            note.host?.let { host -> append(" · "); withStyle(mono) { append(host) } }
        }
    }
    DnsResolverNote.Unknown -> null
}

/** Mono span at 0.92 × the note size (machine values inside a sans note). */
private fun monoSpan(): SpanStyle {
    val mono = NetKitType.addressFor(NetKitType.label)
    return SpanStyle(fontFamily = mono.fontFamily, fontSize = mono.fontSize, fontWeight = mono.fontWeight, letterSpacing = mono.letterSpacing)
}

private fun monoNote(text: String): AnnotatedString = buildAnnotatedString { withStyle(monoSpan()) { append(text) } }

private fun textNote(text: String): AnnotatedString = AnnotatedString(text)

/**
 * Key/value row like [cz.netkit.app.ui.KeyValueRow] (9 dp above and below, `divider` on top):
 * the label on the first line and its [notes] (address, resolver, queried name) each on its own
 * line under it in `dim` (machine values mono). The animated value (or [status]) sits on the
 * right, baseline-aligned with the label, at most [LIVE_ROW_VALUE_SHARE] of the width; the 12 dp
 * gap to the label column is kept at any width and font scale.
 */
@Composable
private fun LiveRow(label: String, notes: List<AnnotatedString>, value: Double?, unit: String?, status: String?,
    statusTone: Tone = Tone.NEUTRAL, showDivider: Boolean = true) {
    val colors = NetKitTheme.colors
    val divider = colors.divider
    val valueStyle = NetKitType.body.copy(fontWeight = FontWeight.SemiBold)
    Layout(
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = NetKitType.body, color = colors.muted)
                notes.forEach { Text(it, style = NetKitType.label, color = colors.dim) }
            }
            if (status != null || value == null) {
                Text(status ?: stringResource(R.string.wifi_signal_no_value), style = valueStyle,
                    color = if (statusTone == Tone.NEUTRAL) colors.text else colors.tone(statusTone).foreground, textAlign = TextAlign.End)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.tiny)) {
                    AnimatedNumber(value, 1, valueStyle, colors.text, Modifier.alignByBaseline())
                    unit?.let { Text(it, Modifier.alignByBaseline(), style = valueStyle, color = colors.text, maxLines = 1) }
                }
            }
        },
        modifier = Modifier.fillMaxWidth()
            .then(if (showDivider) Modifier.drawBehind { drawLine(divider, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) } else Modifier)
            .padding(top = if (showDivider) 9.dp else 0.dp, bottom = 9.dp)
            .semantics(mergeDescendants = true) {},
    ) { measurables, constraints ->
        val gap = Spacing.medium.roundToPx()
        val width = constraints.maxWidth
        val valueP = measurables[1].measure(Constraints(maxWidth = (width * LIVE_ROW_VALUE_SHARE).roundToInt()))
        val labelP = measurables[0].measure(Constraints(maxWidth = (width - valueP.width - gap).coerceAtLeast(0)))
        val lb = labelP[FirstBaseline].takeIf { it != androidx.compose.ui.layout.AlignmentLine.Unspecified } ?: 0
        val vb = valueP[FirstBaseline].takeIf { it != androidx.compose.ui.layout.AlignmentLine.Unspecified } ?: 0
        val base = max(lb, vb)
        val height = max(base - lb + labelP.height, base - vb + valueP.height).coerceAtLeast(constraints.minHeight)
        layout(width, height) {
            labelP.place(0, base - lb)
            valueP.place(width - valueP.width, base - vb)
        }
    }
}

/** "0 / 0,3 %": the gateway and internet value of one statistic; [single] for the internet value alone. */
private class PairFormat(private val dash: String, private val one: NumberFormat, private val whole: NumberFormat) {
    private fun text(v: Double?) = v?.let { if (it == 0.0 || it >= 10.0) whole.format(it) else one.format(it) } ?: dash
    operator fun invoke(a: Double?, b: Double?, unit: String) = "${text(a)} / ${text(b)} $unit"
    fun single(a: Double?, unit: String) = "${text(a)} $unit"
}

@Composable
private fun rememberPairFormat(): PairFormat {
    val dash = stringResource(R.string.wifi_signal_no_value)
    val one = rememberDecimalFormat(1)
    val whole = rememberDecimalFormat(0)
    return remember(dash, one, whole) { PairFormat(dash, one, whole) }
}

@Composable
private fun rememberDecimalFormat(decimals: Int): NumberFormat {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale, decimals) { numberFormat(locale, decimals, grouping = false) }
}

private fun lossTone(percent: Double?): Tone = when {
    percent == null -> Tone.NEUTRAL
    percent == 0.0 -> Tone.OK
    percent <= 2.0 -> Tone.WARN
    else -> Tone.ERR
}

@Composable
private fun dnsStatusText(dns: LiveDns): String = when (val res = liveDnsStatusText(dns.status)) {
    null -> stringResource(R.string.wifi_signal_no_value)
    R.string.wifi_live_dns_rcode -> stringResource(res, dns.lastRcode ?: 0)
    else -> stringResource(res)
}

// --- Roaming -----------------------------------------------------------------------------------

@Composable
private fun RoamingPanel(events: List<RoamingEvent>) {
    // Events carry only monotonic time; recomposition happens with every 1 Hz state update.
    val now = SystemClock.elapsedRealtime()
    SectionCard(title = stringResource(R.string.wifi_signal_roaming)) {
        Column(Modifier.fillMaxWidth()) {
            events.takeLast(ROAMING_VISIBLE).asReversed().forEachIndexed { index, event -> RoamingRow(event, now, showDivider = index > 0) }
        }
    }
}

/**
 * One roam: "před 12 s" with the band or channel change and "po výpadku" as tags on the left
 * (they wrap under the time when narrow), the two short BSSIDs in mono on the right, one line.
 */
@Composable
private fun RoamingRow(event: RoamingEvent, now: Long, showDivider: Boolean) {
    val colors = NetKitTheme.colors
    val divider = colors.divider
    val change = when (val c = roamingChange(event.fromFrequencyMhz, event.toFrequencyMhz)) {
        is RoamingChange.Band -> stringResource(R.string.wifi_signal_roaming_band, bandShortText(c.from), bandShortText(c.to))
        is RoamingChange.Channel -> stringResource(R.string.wifi_signal_roaming_channel, c.from, c.to)
        null -> null
    }
    Row(Modifier.fillMaxWidth()
        .then(if (showDivider) Modifier.drawBehind { drawLine(divider, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) } else Modifier)
        .padding(top = if (showDivider) 9.dp else 0.dp, bottom = 9.dp)
        .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium), verticalAlignment = Alignment.Top) {
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            verticalArrangement = Arrangement.spacedBy(Spacing.tiny)) {
            Text(agoText(now - event.elapsedMs), style = NetKitType.body, color = colors.muted)
            change?.let { Tag(it) }
            if (event.afterGap) Tag(stringResource(R.string.wifi_signal_roaming_gap), Tone.WARN)
        }
        Text(stringResource(R.string.wifi_signal_roaming_bssids, shortBssid(event.fromBssid), shortBssid(event.toBssid)),
            style = NetKitType.address, color = colors.text, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun bandShortText(band: WifiBand): String = when (band) {
    WifiBand.GHZ_2_4 -> stringResource(R.string.wifi_signal_band_24_short)
    WifiBand.GHZ_5 -> stringResource(R.string.wifi_signal_band_5_short)
    WifiBand.GHZ_6 -> stringResource(R.string.wifi_signal_band_6_short)
    WifiBand.UNKNOWN -> stringResource(R.string.wifi_signal_no_value)
}

@Composable
private fun agoText(deltaMs: Long): String {
    val seconds = (deltaMs.coerceAtLeast(0L) / 1_000L).toInt()
    val amount = when {
        seconds < 60 -> stringResource(R.string.wifi_signal_ago_seconds, seconds)
        seconds < 3_600 -> stringResource(R.string.wifi_signal_ago_minutes, seconds / 60)
        else -> stringResource(R.string.wifi_signal_ago_hours, seconds / 3_600)
    }
    return stringResource(R.string.wifi_signal_ago, amount)
}

// --- Internet target and DNS name dialog -------------------------------------------------------

/** [onSubmit] gets the IPv4 target and the DNS name and returns whether each is valid. */
@Composable
private fun TargetDialog(current: String, currentDnsName: String, onDismiss: () -> Unit,
    onSubmit: (String, String) -> Pair<Boolean, Boolean>) {
    val colors = NetKitTheme.colors
    var text by rememberSaveable { mutableStateOf(current) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    var dnsName by rememberSaveable { mutableStateOf(currentDnsName) }
    var dnsInvalid by rememberSaveable { mutableStateOf(false) }
    val submit = { onSubmit(text, dnsName).let { (targetOk, nameOk) -> invalid = !targetOk; dnsInvalid = !nameOk } }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.semantics { testTagsAsResourceId = BuildConfig.DEBUG },
        containerColor = colors.surface,
        title = { Text(stringResource(R.string.wifi_signal_target_title), style = NetKitType.panelTitle, color = colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; invalid = false },
                    modifier = Modifier.fillMaxWidth().testTag("signal_target_input"),
                    label = { Text(stringResource(R.string.wifi_signal_target_label)) },
                    singleLine = true,
                    isError = invalid,
                    supportingText = if (invalid) {
                        { Text(stringResource(liveInternetTargetInvalidText())) }
                    } else null,
                    textStyle = NetKitType.addressFor(NetKitType.value),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                OutlinedTextField(
                    value = dnsName,
                    onValueChange = { dnsName = it; dnsInvalid = false },
                    modifier = Modifier.fillMaxWidth().testTag("signal_dns_name_input"),
                    label = { Text(stringResource(R.string.wifi_signal_dns_name_label)) },
                    singleLine = true,
                    isError = dnsInvalid,
                    supportingText = if (dnsInvalid) {
                        { Text(stringResource(liveDnsNameInvalidText())) }
                    } else null,
                    textStyle = NetKitType.addressFor(NetKitType.value),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                Text(stringResource(R.string.wifi_signal_target_hint), style = NetKitType.label, color = colors.muted)
            }
        },
        confirmButton = { PrimaryButton(stringResource(R.string.wifi_signal_save), onClick = submit, small = true) },
        dismissButton = { NeutralButton(stringResource(R.string.wifi_signal_cancel), onClick = onDismiss, small = true) },
    )
}

// --- Helpers -----------------------------------------------------------------------------------

/** Gauge colour per docs/design.md §5: ok / accent / warn / err. */
private fun qualityTone(quality: SignalQuality): Tone = when (quality) {
    SignalQuality.EXCELLENT -> Tone.OK
    SignalQuality.GOOD -> Tone.ACCENT
    SignalQuality.FAIR -> Tone.WARN
    SignalQuality.POOR -> Tone.ERR
    SignalQuality.UNKNOWN -> Tone.NEUTRAL
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
