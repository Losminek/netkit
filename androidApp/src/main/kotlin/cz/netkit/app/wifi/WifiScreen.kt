package cz.netkit.app.wifi

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.ColumnScope
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import cz.netkit.app.R
import cz.netkit.app.ui.*
import cz.netkit.app.ui.motion.MotionToken
import cz.netkit.app.ui.motion.rememberNetKitTween
import cz.netkit.app.ui.theme.NetKitShape
import cz.netkit.app.ui.theme.NetKitTheme
import cz.netkit.app.ui.theme.NetKitType
import cz.netkit.shared.wifi.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

private const val TAB_NETWORKS = 0
private const val TAB_HEALTH = 1

/**
 * "Spektrum" (prototype `vSpectrum`): header with the scan action, band chips, spectrum card of
 * one band and one segment — the AP list (tap expands a row) or the channel-health heat strip
 * with recommendations. Permission flow, scan throttling, dataset freshness and own-AP
 * semantics are unchanged from the M1/M2 analyzer.
 */
@Composable
fun WifiScreen(viewModel: WifiViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    // null = automatic: the connected AP's band, else 2.4 GHz.
    var selectedBand by rememberSaveable { mutableStateOf<WifiBand?>(null) }
    var sort by rememberSaveable { mutableStateOf(WifiSort.SIGNAL) }
    var query by rememberSaveable { mutableStateOf("") }
    var candidateWidth by rememberSaveable { mutableIntStateOf(20) }
    var tab by rememberSaveable { mutableIntStateOf(TAB_NETWORKS) }
    var permissionRequested by rememberSaveable { mutableStateOf(viewModel.permissionWasRequested()) }
    var elapsedNow by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val context = LocalContext.current
    val activity = remember(context) { context.activity() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var issue by remember { mutableStateOf(viewModel.accessIssue(permissionRequested, false)) }
    var settingsFailure by rememberSaveable { mutableStateOf(false) }
    fun refreshIssue() {
        issue = viewModel.accessIssue(permissionRequested,
            activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.ACCESS_FINE_LOCATION) } == true)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRequested = true
        refreshIssue()
        viewModel.refreshCurrent()
        if (issue == WifiAccessIssue.NONE) viewModel.scan()
    }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            refreshIssue()
            viewModel.refreshCurrent()
            while (isActive) {
                elapsedNow = SystemClock.elapsedRealtime()
                delay(1_000L)
            }
        }
    }
    val counts = remember(state.accessPoints) { wifiBandCounts(state.accessPoints) }
    val connectedBand = (state.accessPoints.firstOrNull { it.isCurrent }?.band
        ?: state.current?.frequencyMhz?.let(::bandForFrequency))?.takeIf { it != WifiBand.UNKNOWN }
    val band = selectedBand ?: connectedBand ?: WifiBand.GHZ_2_4
    val visible = remember(state.accessPoints, band, sort, query) { sortedAccessPoints(state.accessPoints, band, sort, query) }
    val spectrum = remember(state.accessPoints, band) { bandSpectrum(state.accessPoints, band) }
    val drawn = remember(spectrum) { spectrum?.let { spectrumDrawnTraces(it.traces) }.orEmpty() }
    val palette = wifiChartPalette(NetKitTheme.colors)
    val seriesByKey = remember(drawn, palette) { drawn.mapIndexed { index, trace -> trace.key to Color(palette.series[index]) }.toMap() }
    val datasetAge = wifiDatasetAge(state.lastUpdatedElapsedMs, elapsedNow)
    fun openSettings(action: String, appPage: Boolean = false) {
        settingsFailure = runCatching {
            context.startActivity(Intent(action).apply { if (appPage) data = "package:${context.packageName}".toUri() })
        }.isFailure
    }
    fun requestPermission() {
        permissionRequested = true
        viewModel.markPermissionRequested()
        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    fun onScanAction() {
        refreshIssue()
        when (issue) {
            WifiAccessIssue.PERMISSION_REQUIRED, WifiAccessIssue.PRECISE_REQUIRED -> requestPermission()
            WifiAccessIssue.PERMANENTLY_DENIED -> openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true)
            WifiAccessIssue.LOCATION_DISABLED -> openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            WifiAccessIssue.WIFI_DISABLED -> openSettings(Settings.ACTION_WIFI_SETTINGS)
            WifiAccessIssue.NONE -> viewModel.scan()
        }
    }
    val scanning = state.status == WifiScanStatus.SCANNING
    val notice = scanNotice(state, datasetAge)
    // No list-wide spacing: the networks card is split into one lazy item per row, and those
    // segments must touch. Every other item gets the 16 dp gap above itself ([gap]).
    val gap = Modifier.padding(top = Spacing.normal)
    LazyColumn(modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(Spacing.normal)) {
        item {
            ScreenHeader(stringResource(R.string.wifi_spectrum_title), headerSubtitle(counts.all, datasetAge, scanning)) {
                PrimaryButton(stringResource(R.string.wifi_scan), ::onScanAction, Modifier.testTag("wifi_scan_action"),
                    icon = NetKitIcons.Refresh, enabled = !scanning, small = true)
            }
        }
        if (issue != WifiAccessIssue.NONE || settingsFailure || notice != null) item {
            Column(gap, verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                if (issue != WifiAccessIssue.NONE) {
                    StatusBanner(stringResource(when (issue) {
                        WifiAccessIssue.PRECISE_REQUIRED -> R.string.wifi_precise_required
                        WifiAccessIssue.PERMANENTLY_DENIED -> R.string.wifi_permission_permanent
                        WifiAccessIssue.LOCATION_DISABLED -> R.string.wifi_location_disabled
                        WifiAccessIssue.WIFI_DISABLED -> R.string.wifi_disabled
                        else -> R.string.wifi_permission_required
                    }), StatusKind.WARNING)
                    AccessActions(issue, ::requestPermission,
                        { openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) },
                        { openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS) },
                        { openSettings(Settings.ACTION_WIFI_SETTINGS) })
                }
                if (settingsFailure) StatusBanner(stringResource(R.string.wifi_settings_failed), StatusKind.ERROR)
                notice?.let { (text, kind) -> StatusBanner(stringResource(text), kind) }
            }
        }
        // The connected AP's band is the automatic choice: picking it again returns to automatic
        // selection (follows the AP when it moves band), any other band is a manual pick.
        item { Box(gap) { BandChips(band, connectedBand, counts) { picked -> selectedBand = picked.takeUnless { it == (connectedBand ?: WifiBand.GHZ_2_4) } } } }
        spectrum?.let { value -> item { Box(gap) { SpectrumPanel(value, bandLabel(band), drawn) } } }
        item {
            Box(gap) {
                SegmentedTabs(listOf(stringResource(R.string.wifi_spectrum_tab_networks), stringResource(R.string.wifi_channel_health_title)),
                    tab, { tab = it }, testTags = listOf("wifi_tab_networks", "wifi_tab_health"))
            }
        }
        if (tab == TAB_NETWORKS) {
            state.current?.let { current ->
                item { Box(gap) { CurrentConnectionRow(if (datasetAge.allowsRecommendation) current else current.copy(widthMhz = null)) } }
            }
            networksCard(visible, seriesByKey, state, history, elapsedNow, sort, { sort = it }, query, { query = it },
                viewModel::excludeConnectedSsid, viewModel::markOwnAccessPoint, viewModel::selectNetwork)
        } else {
            item {
                Box(gap) {
                    ChannelHealthSection(state, band, spectrum?.axis, elapsedNow, datasetAge.freshness, candidateWidth,
                        { candidateWidth = it }, ::onScanAction, scanEnabled = !scanning, onRecommendationScan = viewModel::scan)
                }
            }
        }
        item { Box(gap) { ScreenInfoNote() } }
    }
}

/** "18 sítí · sken před 20 s"; while scanning the age part says so. */
@Composable private fun headerSubtitle(networks: Int, age: WifiDatasetAge, scanning: Boolean): String {
    val seconds = age.ageMs?.div(1000)
    val freshness = when {
        scanning -> stringResource(R.string.wifi_spectrum_age_scanning)
        seconds == null -> stringResource(if (networks > 0) R.string.wifi_spectrum_age_unconfirmed else R.string.wifi_spectrum_age_none)
        seconds < 120 -> stringResource(R.string.wifi_spectrum_age_seconds, seconds.toInt())
        else -> stringResource(R.string.wifi_spectrum_age_minutes, (seconds / 60).toInt())
    }
    return if (networks > 0) pluralStringResource(R.plurals.wifi_spectrum_networks, networks, networks) + " · " + freshness
        else freshness
}

/**
 * At most one compact status banner: a scan that was not confirmed, cancelled or failed, or a
 * dataset that is stale (older than 5 min or never confirmed). Fresh and aging data need no
 * banner — the header subtitle shows the age.
 */
private fun scanNotice(state: WifiAnalyzerState, age: WifiDatasetAge): Pair<Int, StatusKind>? = when {
    state.status == WifiScanStatus.SCANNING -> null
    state.status == WifiScanStatus.THROTTLED_OR_FAILED -> R.string.wifi_scan_stale to StatusKind.WARNING
    state.status == WifiScanStatus.CANCELLED -> R.string.wifi_scan_cancelled to StatusKind.WARNING
    state.status == WifiScanStatus.ERROR -> R.string.wifi_scan_failure to StatusKind.ERROR
    state.accessPoints.isEmpty() -> null
    age.freshness == WifiDatasetFreshness.EXPIRED -> R.string.wifi_spectrum_stale_expired to StatusKind.WARNING
    age.freshness == WifiDatasetFreshness.UNKNOWN -> R.string.wifi_spectrum_stale_unconfirmed to StatusKind.WARNING
    else -> null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun AccessActions(issue: WifiAccessIssue, requestPermission: () -> Unit, appSettings: () -> Unit,
    locationSettings: () -> Unit, wifiSettings: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        when (issue) {
            WifiAccessIssue.PERMISSION_REQUIRED, WifiAccessIssue.PRECISE_REQUIRED ->
                PrimaryButton(stringResource(R.string.wifi_spectrum_allow_location), requestPermission, small = true)
            WifiAccessIssue.WIFI_DISABLED -> PrimaryButton(stringResource(R.string.wifi_spectrum_wifi_settings), wifiSettings, small = true)
            else -> Unit
        }
        NeutralButton(stringResource(R.string.wifi_app_settings), appSettings, small = true)
        NeutralButton(stringResource(R.string.wifi_location_settings), locationSettings, small = true)
    }
}

/**
 * Band selector (prototype `.chips`): `surface2` track, selected chip on `surface`. Each chip
 * gets an equal share of the width, or its own width when that does not fit; then the row
 * scrolls horizontally with an edge fade instead of clipping a label. The band of the connected
 * AP ([connected], the automatic choice) carries an accent dot and says so to accessibility.
 */
@Composable private fun BandChips(selected: WifiBand, connected: WifiBand?, counts: WifiBandCounts, onSelect: (WifiBand) -> Unit) {
    val colors = NetKitTheme.colors
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxWidth().clip(NetKitShape.nested).background(colors.surface2)) {
        val viewport = maxWidth
        Row(Modifier.horizontalScroll(scroll).selectableGroup().padding(4.dp)) {
            FillRow(minWidth = viewport - 8.dp, gap = 4.dp) {
                listOf(WifiBand.GHZ_2_4, WifiBand.GHZ_5, WifiBand.GHZ_6).forEach { band ->
                    val on = band == selected
                    val fill by animateColorAsState(if (on) colors.surface else colors.surface2, rememberNetKitTween(MotionToken.FAST), label = "band-fill")
                    val ink by animateColorAsState(if (on) colors.text else colors.muted, rememberNetKitTween(MotionToken.FAST), label = "band-ink")
                    val auto = band == connected
                    val autoLabel = stringResource(R.string.wifi_band_connected_auto)
                    Row(Modifier.heightIn(min = 40.dp).clip(NetKitShape.segmentThumb).background(fill)
                        .selectable(selected = on, role = Role.Tab) { onSelect(band) }
                        .testTag("wifi_band_${band.name.lowercase()}").padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        if (auto) Box(Modifier.padding(end = 6.dp).size(6.dp).clip(CircleShape).background(colors.accent)
                            .semantics { contentDescription = autoLabel })
                        Text(bandLabel(band), style = NetKitType.body.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                            color = ink, maxLines = 1, softWrap = false)
                        Text(counts.count(band).toString(), Modifier.padding(start = 4.dp),
                            style = NetKitType.tag, color = ink.copy(alpha = .7f), maxLines = 1, softWrap = false)
                    }
                }
            }
        }
        ScrollFade(scroll, colors.surface2)
    }
}

/**
 * Lays children out in one row: equal shares of at least [minWidth] when every child fits its
 * share, otherwise the free space is split on top of each child's own width; when even that does
 * not fit, children keep their own width (the caller scrolls).
 */
@Composable private fun FillRow(minWidth: Dp, gap: Dp, content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val n = measurables.size.coerceAtLeast(1)
        val free = (minWidth.roundToPx() - gapPx * (n - 1)).coerceAtLeast(0)
        val needs = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val widths = when {
            needs.sum() > free -> needs
            needs.all { it <= free / n } -> List(n) { index -> free / n + if (index == n - 1) free % n else 0 }
            else -> { val extra = (free - needs.sum()) / n; needs.map { it + extra } }
        }
        val placeables = measurables.mapIndexed { index, measurable ->
            measurable.measure(Constraints.fixedWidth(widths[index]).copy(minHeight = constraints.minHeight))
        }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(widths.sum() + gapPx * (n - 1), height) {
            var x = 0
            placeables.forEach { it.place(x, (height - it.height) / 2); x += it.width + gapPx }
        }
    }
}

/** Small selectable pill (sort, width): `accentSoft`/`accent` when on, `surface2`/`muted` otherwise. */
@Composable private fun SmallChip(text: String, on: Boolean, onClick: () -> Unit) {
    val colors = NetKitTheme.colors
    val fill by animateColorAsState(if (on) colors.accentSoft else colors.surface2, rememberNetKitTween(MotionToken.FAST), label = "chip-fill")
    val ink by animateColorAsState(if (on) colors.accent else colors.muted, rememberNetKitTween(MotionToken.FAST), label = "chip-ink")
    Box(Modifier.heightIn(min = 36.dp).clip(NetKitShape.pill).background(fill)
        .selectable(selected = on, role = Role.RadioButton, onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center) {
        Text(text, style = NetKitType.tag.copy(fontSize = 13.sp), color = ink, maxLines = 1, softWrap = false)
    }
}

@Composable private fun WifiCheckboxRow(checked: Boolean, onChecked: (Boolean) -> Unit, label: String, enabled: Boolean = true) {
    val colors = NetKitTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled,
        role = Role.Checkbox, onValueChange = onChecked), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, colors = CheckboxDefaults.colors(
            checkedColor = colors.accent, uncheckedColor = colors.dim, checkmarkColor = colors.accentInk))
        Text(label, Modifier.weight(1f).padding(start = Spacing.medium), style = NetKitType.body, color = colors.text)
    }
}

/** RSSI ink as in the prototype list: strong ok, usable text, weak muted. */
@Composable private fun rssiInk(rssiDbm: Int): Color = with(NetKitTheme.colors) {
    when {
        rssiDbm > -60 -> ok
        rssiDbm > -72 -> text
        else -> muted
    }
}

@Composable private fun sortedByLabel(sort: WifiSort) = stringResource(when (sort) {
    WifiSort.SIGNAL -> R.string.wifi_spectrum_sorted_signal
    WifiSort.NAME -> R.string.wifi_spectrum_sorted_name
    WifiSort.CHANNEL -> R.string.wifi_spectrum_sorted_channel
})

/**
 * "Sítě" card as lazy items, so a long scan does not compose every row at once: a header segment
 * (title, filter toggle, filters, empty state) and one segment per AP row with a stable key. The
 * segments touch and only the first and last are rounded, so it still reads as one card. Unlike
 * [Panel], the segments draw no light-theme shadow (it would show at every seam).
 */
private fun LazyListScope.networksCard(
    visible: List<AccessPoint>, seriesByKey: Map<String, Color>, state: WifiAnalyzerState, history: List<SignalSample>,
    elapsedNow: Long, sort: WifiSort, onSort: (WifiSort) -> Unit, query: String, onQuery: (String) -> Unit,
    onExcludeSsid: (Boolean) -> Unit, onOwn: (String, Boolean) -> Unit, onSelect: (String) -> Unit,
) {
    item(key = "networks_header") {
        CardSegment(first = true, last = visible.isEmpty(), Modifier.padding(top = Spacing.normal)) {
            NetworksHeader(state, sort, onSort, query, onQuery, onExcludeSsid)
            if (visible.isEmpty()) EmptyState(stringResource(if (query.isNotBlank()) R.string.wifi_no_filter_results else R.string.wifi_no_results))
        }
    }
    val keys = uniqueRowKeys(visible.map(::accessPointKey))
    itemsIndexed(visible, key = { index, _ -> keys[index] }) { index, point ->
        CardSegment(first = false, last = index == visible.lastIndex) {
            AccessPointRow(point, seriesByKey[accessPointKey(point)], showDivider = index > 0, state = state,
                history = history, elapsedNow = elapsedNow, onOwn = { onOwn(point.bssid, it) },
                onSelect = { onSelect(point.bssid) })
        }
    }
}

/** Lazy keys from AP keys; a repeated key (should not happen after deduplication) gets a suffix. */
internal fun uniqueRowKeys(keys: List<String>): List<String> {
    val seen = HashMap<String, Int>()
    return keys.map { key ->
        val count = seen.getOrElse(key) { 0 }
        seen[key] = count + 1
        if (count == 0) "ap:$key" else "ap:$key#$count"
    }
}

/** One piece of a card split over lazy items: `surface`, card padding at the sides, radius 16 on the outer ends. */
@Composable private fun CardSegment(first: Boolean, last: Boolean, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = NetKitTheme.colors
    val radius = 16.dp
    val shape = RoundedCornerShape(topStart = if (first) radius else 0.dp, topEnd = if (first) radius else 0.dp,
        bottomStart = if (last) radius else 0.dp, bottomEnd = if (last) radius else 0.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(colors.surface)
        .padding(start = Spacing.card, end = Spacing.card, top = if (first) Spacing.card else 0.dp,
            // Between the header and the first row the card keeps its inner gap.
            bottom = if (last) Spacing.card else if (first) Spacing.cardGap else 0.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.cardGap), content = content)
}

/** Header of the "Sítě" card: sort as meta and a "Filtr" toggle with sort chips, SSID filter and same-SSID exclusion. */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun NetworksHeader(state: WifiAnalyzerState, sort: WifiSort, onSort: (WifiSort) -> Unit, query: String,
    onQuery: (String) -> Unit, onExcludeSsid: (Boolean) -> Unit) {
    val colors = NetKitTheme.colors
    var filtersOpen by rememberSaveable { mutableStateOf(query.isNotBlank()) }
    val chevron by animateFloatAsState(if (filtersOpen) 90f else 0f, rememberNetKitTween(MotionToken.FAST), label = "filter-chevron")
    val filtering = query.isNotBlank() || state.excludeConnectedSsid
    Column(Modifier.animateContentSize(rememberNetKitTween(MotionToken.BASE)), verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.wifi_spectrum_tab_networks), style = NetKitType.panelTitle, color = colors.text)
                Text(sortedByLabel(sort), style = NetKitType.label, color = colors.muted)
            }
            Row(Modifier.heightIn(min = 48.dp).clip(NetKitShape.buttonSmall)
                .clickable(role = Role.Button) { filtersOpen = !filtersOpen }.padding(horizontal = Spacing.small),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (filtering) Box(Modifier.size(6.dp).clip(NetKitShape.pill).background(colors.accent))
                Text(stringResource(R.string.wifi_spectrum_filter), style = NetKitType.tag.copy(fontSize = 13.sp),
                    color = if (filtering) colors.accent else colors.muted)
                Icon(NetKitIcons.ChevronRight, null, Modifier.size(16.dp).rotate(chevron), tint = colors.dim)
            }
        }
        if (filtersOpen) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalArrangement = Arrangement.spacedBy(Spacing.small),
                modifier = Modifier.selectableGroup()) {
                WifiSort.entries.forEach { value -> SmallChip(sortLabel(value), sort == value) { onSort(value) } }
            }
            OutlinedTextField(query, onQuery, placeholder = { Text(stringResource(R.string.wifi_ssid_filter), style = NetKitType.body) },
                modifier = Modifier.fillMaxWidth(), singleLine = true, textStyle = NetKitType.body, shape = NetKitShape.nested,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = colors.surface2, unfocusedContainerColor = colors.surface2,
                    focusedBorderColor = colors.accent, unfocusedBorderColor = Color.Transparent,
                    focusedTextColor = colors.text, unfocusedTextColor = colors.text, cursorColor = colors.accent,
                    focusedPlaceholderColor = colors.dim, unfocusedPlaceholderColor = colors.dim))
            WifiCheckboxRow(state.excludeConnectedSsid, onExcludeSsid, stringResource(R.string.wifi_exclude_connected_ssid))
        }
    }
}

/**
 * Compact AP row: series swatch | SSID (+ "připojeno") and "k. 7 · 40 MHz · WPA2" | RSSI.
 * Tapping a row with a BSSID selects it for the RSSI history (one at a time) and expands its
 * details; rows without a BSSID expand locally.
 */
@Composable private fun AccessPointRow(ap: AccessPoint, series: Color?, showDivider: Boolean, state: WifiAnalyzerState,
    history: List<SignalSample>, elapsedNow: Long, onOwn: (Boolean) -> Unit, onSelect: () -> Unit) {
    val colors = NetKitTheme.colors
    val divider = colors.divider
    var localExpanded by rememberSaveable { mutableStateOf(false) }
    val selectable = ap.bssid.isNotBlank()
    val expanded = if (selectable) ap.isSelected else localExpanded
    val subtitle = listOfNotNull(
        ap.channel?.let { stringResource(R.string.wifi_spectrum_channel_short, it) },
        ap.widthMhz?.let { if (ap.is80Plus80) stringResource(R.string.wifi_width_80_plus_80_short) else stringResource(R.string.wifi_width_mhz, it) },
        shortSecurityLabel(ap.security),
    ).joinToString(" · ")
    Column(Modifier.fillMaxWidth()
        .then(if (showDivider) Modifier.drawBehind { drawLine(divider, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) } else Modifier)
        .animateContentSize(rememberNetKitTween(MotionToken.BASE))) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(onClickLabel = stringResource(R.string.wifi_spectrum_network_details), role = Role.Button) {
                if (selectable) onSelect() else localExpanded = !localExpanded
            }
            .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
            Box(Modifier.size(width = 10.dp, height = 34.dp).clip(RoundedCornerShape(4.dp)).background(series ?: colors.surface3))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    Text(ap.ssid ?: stringResource(R.string.wifi_hidden_network), Modifier.weight(1f, fill = false),
                        style = NetKitType.body.copy(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold), color = colors.text)
                    if (ap.isCurrent) Tag(stringResource(R.string.wifi_spectrum_connected), Tone.ACCENT, dot = true)
                }
                if (subtitle.isNotEmpty()) Text(subtitle, style = NetKitType.label, color = colors.muted)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(ap.rssiDbm.toString(), Modifier.alignByBaseline(), style = NetKitType.value, color = rssiInk(ap.rssiDbm))
                Text(stringResource(R.string.wifi_spectrum_dbm_unit), Modifier.alignByBaseline().padding(start = 2.dp),
                    style = NetKitType.unit, color = colors.muted)
            }
        }
        if (expanded) AccessPointDetails(ap, state, history.takeIf { normalizeBssid(ap.bssid) == state.historyBssid }.orEmpty(),
            elapsedNow, onOwn)
    }
}

@Composable private fun AccessPointDetails(ap: AccessPoint, state: WifiAnalyzerState, history: List<SignalSample>,
    elapsedNow: Long, onOwn: (Boolean) -> Unit) {
    val colors = NetKitTheme.colors
    val unknown = stringResource(R.string.wifi_unknown)
    val excluded = isOwnAccessPoint(ap, state)
    val manuallyOwned = normalizeBssid(ap.bssid)?.let { it in state.ownBssids } == true
    val center = ap.centerFrequencyMhz?.let { first ->
        ap.secondaryCenterFrequencyMhz?.let { second -> stringResource(R.string.wifi_centers_mhz, first, second) }
            ?: stringResource(R.string.wifi_width_mhz, first)
    }
    val load = listOfNotNull(ap.stationCount?.let { stringResource(R.string.wifi_spectrum_stations, it) },
        ap.channelUtilizationPercent?.let { stringResource(R.string.wifi_spectrum_utilization, it) }).joinToString(" · ")
    Column(Modifier.fillMaxWidth().padding(start = 22.dp, bottom = Spacing.medium), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        KeyValueRows(listOfNotNull(
            KeyValue(stringResource(R.string.wifi_bssid_label), ap.bssid.ifBlank { unknown }, monospace = true),
            KeyValue(stringResource(R.string.wifi_label_frequency), stringResource(R.string.wifi_width_mhz, ap.frequencyMhz)),
            center?.let { KeyValue(stringResource(R.string.wifi_label_center), it) },
            KeyValue(stringResource(R.string.wifi_label_width), ap.widthMhz?.let {
                if (ap.is80Plus80) stringResource(R.string.wifi_width_80_plus_80) else stringResource(R.string.wifi_width_mhz, it)
            } ?: unknown),
            KeyValue(stringResource(R.string.wifi_label_standard), ap.standard?.let { stringResource(R.string.wifi_standard_value, it) }
                ?: stringResource(R.string.wifi_standard_unknown)),
            KeyValue(stringResource(R.string.wifi_label_security), securityLabel(ap.security)),
            KeyValue(stringResource(R.string.wifi_label_age), accessPointAgeMs(ap, elapsedNow).takeUnless { it == Long.MAX_VALUE }
                ?.let { stringResource(R.string.wifi_age_seconds, it / 1000) } ?: unknown),
            load.takeIf { it.isNotEmpty() }?.let { KeyValue(stringResource(R.string.wifi_label_bss_load), it) },
        ))
        if (ap.bssid.isNotBlank() && ap.isSelected) RssiHistory(history)
        Text(stringResource(if (ap.locallyAdministered) R.string.wifi_vendor_local else R.string.wifi_vendor_unavailable),
            style = NetKitType.label, color = colors.dim)
        if (excluded) Text(stringResource(R.string.wifi_ap_excluded), style = NetKitType.label, color = colors.dim)
        WifiCheckboxRow(manuallyOwned, onOwn, stringResource(R.string.wifi_own_ap), enabled = ap.bssid.isNotBlank())
    }
}

/** RSSI of the selected AP over the confirmed scans since it was selected (−90…−30 dBm). */
@Composable private fun RssiHistory(values: List<SignalSample>) {
    val colors = NetKitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.wifi_history_title, values.size), style = NetKitType.label, color = colors.muted)
        if (values.size < 2) Text(stringResource(R.string.wifi_history_select), style = NetKitType.label, color = colors.dim)
        else {
            val description = stringResource(R.string.wifi_history_values, values.joinToString { it.rssiDbm.toString() })
            val start = values.first().elapsedMs
            val duration = (values.last().elapsedMs - start).coerceAtLeast(1)
            Canvas(Modifier.fillMaxWidth().height(56.dp).semantics { contentDescription = description }) {
                fun y(dbm: Int) = size.height * (-30 - dbm.coerceIn(-90, -30)) / 60f
                listOf(-50, -70, -90).forEach { drawLine(colors.divider, Offset(0f, y(it)), Offset(size.width, y(it)), 1.dp.toPx()) }
                val points = values.map { Offset((it.elapsedMs - start).toFloat() / duration * size.width, y(it.rssiDbm)) }
                points.zipWithNext().forEach { (a, b) -> drawLine(colors.accent, a, b, 2.2.dp.toPx()) }
                drawCircle(colors.accent, 3.6.dp.toPx(), points.last())
                drawCircle(colors.surface, 1.6.dp.toPx(), points.last())
            }
        }
    }
}

/** One compact row; tap expands the connection details (identity, radio, IP, DNS). */
@Composable private fun CurrentConnectionRow(current: CurrentWifi) {
    val colors = NetKitTheme.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (expanded) 90f else 0f, rememberNetKitTween(MotionToken.FAST), label = "connection-chevron")
    val unknown = stringResource(R.string.wifi_unknown)
    val channel = current.frequencyMhz?.let(::channelForFrequency)
    Panel(padding = Spacing.normal) {
        Column(Modifier.animateContentSize(rememberNetKitTween(MotionToken.BASE))) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(NetKitShape.nested)
                .clickable(onClickLabel = stringResource(R.string.wifi_spectrum_connection_details), role = Role.Button) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                Box(Modifier.size(40.dp).clip(NetKitShape.nested).background(colors.surface2), contentAlignment = Alignment.Center) {
                    Icon(NetKitIcons.Router, null, Modifier.size(20.dp), tint = colors.accent)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(current.ssid ?: stringResource(R.string.wifi_hidden_network), style = NetKitType.panelTitle, color = colors.text)
                    Text(listOfNotNull(stringResource(R.string.wifi_current_connection),
                        channel?.let { stringResource(R.string.wifi_spectrum_channel_short, it) },
                        current.rssiDbm?.let { stringResource(R.string.wifi_dbm_value, it) }).joinToString(" · "),
                        style = NetKitType.label, color = colors.muted)
                }
                Icon(NetKitIcons.ChevronRight, null, Modifier.size(18.dp).rotate(chevron), tint = colors.dim)
            }
            if (expanded) KeyValueRows(listOf(
                KeyValue(stringResource(R.string.wifi_bssid_label), current.bssid ?: unknown, monospace = true),
                KeyValue(stringResource(R.string.wifi_label_signal), current.rssiDbm?.let { stringResource(R.string.wifi_dbm_value, it) } ?: unknown),
                KeyValue(stringResource(R.string.wifi_label_frequency), current.frequencyMhz?.let { stringResource(R.string.wifi_width_mhz, it) } ?: unknown),
                KeyValue(stringResource(R.string.wifi_label_channel), listOfNotNull(current.frequencyMhz?.let { bandLabel(bandForFrequency(it)) },
                    channel?.toString()).joinToString(" · ").ifBlank { unknown }),
                KeyValue(stringResource(R.string.wifi_label_width), current.widthMhz?.let { stringResource(R.string.wifi_width_mhz, it) } ?: unknown),
                KeyValue(stringResource(R.string.wifi_label_standard), current.standard?.let { stringResource(R.string.wifi_standard_value, it) }
                    ?: stringResource(R.string.wifi_standard_unknown)),
                KeyValue(stringResource(R.string.wifi_label_rates), stringResource(R.string.wifi_rates_value,
                    current.rxMbps?.toString() ?: unknown, current.txMbps?.toString() ?: unknown)),
                KeyValue(stringResource(R.string.wifi_ipv4_label), current.ipAddress ?: unknown, monospace = true),
                KeyValue(stringResource(R.string.wifi_gateway_label), current.gateway ?: unknown, monospace = true),
                KeyValue(stringResource(R.string.wifi_dns_label), current.dnsServers.joinToString().ifBlank { unknown }, monospace = true),
                KeyValue(stringResource(R.string.wifi_label_security), current.security?.let { securityLabel(it) }
                    ?: stringResource(R.string.wifi_security_unknown)),
            ), Modifier.padding(top = Spacing.medium))
        }
    }
}

/**
 * Channel health of [band] (age policy and own-AP exclusion in [channelHealthReport]) as the
 * heat strip, followed by the width-aware recommendations.
 */
@Composable private fun ChannelHealthSection(state: WifiAnalyzerState, band: WifiBand, axis: SpectrumAxis?, elapsedNow: Long,
    freshness: WifiDatasetFreshness, candidateWidth: Int, onWidth: (Int) -> Unit, onScan: () -> Unit, scanEnabled: Boolean,
    onRecommendationScan: () -> Unit) {
    // Recomputed on dataset/band change and on a freshness transition, not on every clock tick.
    val report = remember(state, band, freshness) { channelHealthReport(state, band, elapsedNow) }
    val recommended = remember(state, band, freshness) {
        recommendDatasetChannels(state, elapsedNow, 20).firstOrNull { it.band == band }?.channel
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.normal)) {
        when (report) {
            is ChannelHealthReport.Ready -> HeatStripCard(report, state, band, axis, recommended)
            is ChannelHealthReport.Stale -> SectionCard(stringResource(R.string.wifi_channel_health_title)) {
                EmptyState(stringResource(R.string.wifi_health_stale)) {
                    PrimaryButton(stringResource(R.string.wifi_scan), onScan, Modifier.testTag("wifi_health_scan"), enabled = scanEnabled, small = true)
                }
            }
            is ChannelHealthReport.NoData -> SectionCard(stringResource(R.string.wifi_channel_health_title)) {
                EmptyState(stringResource(R.string.wifi_health_no_data))
            }
        }
        if (report !is ChannelHealthReport.Stale) WifiDatasetRecommendations(state, elapsedNow, candidateWidth, onWidth, onRecommendationScan)
    }
}

@Composable private fun HeatStripCard(report: ChannelHealthReport.Ready, state: WifiAnalyzerState, band: WifiBand, axis: SpectrumAxis?,
    recommended: Int?) {
    val cells = remember(report) { heatCells(report.channels) }
    val labelled = remember(axis, band) {
        axis?.ticks.orEmpty().filter { it.labelled && (band != WifiBand.GHZ_2_4 || it.channel % 2 == 1) }.map { it.channel }.toSet()
    }
    val connected = report.channels.firstOrNull { it.connectedHere }?.channel
    var picked by rememberSaveable(band) { mutableStateOf<Int?>(null) }
    val selected = (picked ?: connected ?: recommended)?.takeIf { channel -> report.channels.any { it.channel == channel } }
    SectionCard(stringResource(R.string.wifi_channel_health_title),
        meta = recommended?.let { stringResource(R.string.wifi_health_recommended, it) }) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HeatStrip(cells, labelled, selected) { picked = it }
            HeatScale()
        }
        report.channels.firstOrNull { it.channel == selected }?.let { entry -> ChannelDetail(entry, state, band) }
    }
}

/** Detail of the selected channel under the strip: DFS, neighbours, overlap, BSS Load and APs with RSSI. */
@Composable private fun ChannelDetail(entry: ChannelHealthEntry, state: WifiAnalyzerState, band: WifiBand) {
    val aps = remember(state, band, entry.channel) {
        val lower = entry.centerFrequencyMhz - 10.0
        val upper = entry.centerFrequencyMhz + 10.0
        snapshotAccessPoints(state.accessPoints).filter { ap ->
            ap.band == band && occupiedFrequencySegments(ap).any { minOf(upper, it.upperMhz) - maxOf(lower, it.lowerMhz) > 0.0 }
        }.sortedByDescending { it.rssiDbm }
    }
    val hiddenName = stringResource(R.string.wifi_hidden_network)
    val excludedTag = stringResource(R.string.wifi_spectrum_excluded_chip)
    KeyValueRows(listOf(
        KeyValue(stringResource(if (entry.mayRequireDfs) R.string.wifi_health_channel_dfs else R.string.wifi_health_channel_title, entry.channel),
            if (entry.overlappingAccessPoints > 0) stringResource(R.string.wifi_health_in_range, entry.overlappingAccessPoints)
            else stringResource(R.string.wifi_health_no_neighbours)),
        KeyValue(stringResource(R.string.wifi_health_state), healthLabel(entry.health), healthTone(entry.health)),
        KeyValue(stringResource(R.string.wifi_health_pressure_label), entry.pressureDbm?.let {
            stringResource(R.string.wifi_health_pressure, String.format(Locale.getDefault(), "%.1f", it))
        } ?: stringResource(R.string.wifi_health_pressure_none)),
        KeyValue(stringResource(R.string.wifi_health_utilization_label), entry.maxUtilizationPercent?.let {
            stringResource(R.string.wifi_health_utilization, it)
        } ?: stringResource(R.string.wifi_health_utilization_unknown)),
    ) + aps.map { ap ->
        val name = ap.ssid ?: hiddenName
        KeyValue(if (isOwnAccessPoint(ap, state)) "$name · $excludedTag" else name, stringResource(R.string.wifi_dbm_value, ap.rssiDbm))
    })
}

/** The screen's single explanation: method, limits and own-AP semantics, collapsed. */
@Composable private fun ScreenInfoNote() {
    InfoNote(stringResource(R.string.wifi_spectrum_note_summary), listOf(
        stringResource(R.string.wifi_spectrum_snapshot),
        stringResource(R.string.wifi_health_disclaimer),
        stringResource(R.string.wifi_own_ap_detail),
        stringResource(R.string.wifi_recommendation_limits),
        stringResource(R.string.wifi_scan_limit_note),
    ).joinToString("\n\n"))
}

@Composable internal fun WifiOccupancyChart(points: List<AccessPoint>, filter: WifiBand?) {
    val colors = NetKitTheme.colors
    val palette = wifiChartPalette(colors)
    val axisColor = Color(palette.axis)
    SectionCard(stringResource(R.string.wifi_occupancy_title)) {
        Text(stringResource(R.string.wifi_graph_snapshot), style = NetKitType.label, color = colors.muted)
        val bands = if (filter == null) listOf(WifiBand.GHZ_2_4, WifiBand.GHZ_5, WifiBand.GHZ_6) else listOf(filter)
        bands.forEach { band ->
            val total = points.count { it.band == band }
            val networks = chartAccessPoints(points, band)
            val description = stringResource(R.string.wifi_occupancy_band, bandLabel(band), total)
            Text(description, style = NetKitType.body.copy(fontWeight = FontWeight.SemiBold), color = colors.text)
            if (networks.isEmpty()) Text(stringResource(R.string.wifi_no_band_data), style = NetKitType.label, color = colors.muted)
            else {
                if (total > networks.size) Text(stringResource(R.string.wifi_graph_limit, networks.size, total - networks.size),
                    style = NetKitType.label, color = colors.muted)
                val labels = networks.mapIndexed { index, ap -> stringResource(R.string.wifi_graph_legend_numbered, index + 1,
                    ap.ssid ?: stringResource(R.string.wifi_hidden_network),
                    ap.bssid.ifBlank { stringResource(R.string.wifi_unknown) }, ap.rssiDbm) }
                val accessibleDescription = description + "\n" + labels.joinToString("\n")
                val range = when (band) {
                    WifiBand.GHZ_2_4 -> 2400.0 to 2500.0
                    WifiBand.GHZ_5 -> 5150.0 to 5900.0
                    WifiBand.GHZ_6 -> 5925.0 to 7125.0
                    WifiBand.UNKNOWN -> 0.0 to 1.0
                }
                Row(Modifier.fillMaxWidth()) {
                    RssiAxis()
                    Canvas(Modifier.weight(1f).height(128.dp).semantics { contentDescription = accessibleDescription }) {
                        val inset = 8.dp.toPx()
                        val plotWidth = (size.width - 2 * inset).coerceAtLeast(1f)
                        val plotHeight = (size.height - 2 * inset).coerceAtLeast(1f)
                        val bottom = size.height - inset
                        fun x(frequency: Double) = inset + ((frequency - range.first) / (range.second - range.first) * plotWidth).toFloat().coerceIn(0f, plotWidth)
                        drawLine(axisColor, Offset(inset, bottom), Offset(size.width - inset, bottom), 1.dp.toPx())
                        drawLine(axisColor, Offset(inset, inset), Offset(inset, bottom), 1.dp.toPx())
                        networks.forEachIndexed { index, ap ->
                            val color = Color(palette.series[index])
                            occupiedFrequencySegments(ap).forEach { segment ->
                                val peakY = bottom - ((ap.rssiDbm + 100).coerceIn(0, 70) / 70f) * plotHeight
                                val peak = Offset(x((segment.lowerMhz + segment.upperMhz) / 2), peakY)
                                drawLine(color, Offset(x(segment.lowerMhz), bottom), peak, 2.dp.toPx())
                                drawLine(color, peak, Offset(x(segment.upperMhz), bottom), 2.dp.toPx())
                                drawWifiMarker(index, color, peak, 4.dp.toPx())
                            }
                        }
                    }
                }
                AxisLabels(range.first.toInt().toString(), ((range.first + range.second) / 2).toInt().toString(), range.second.toInt().toString())
                Text(stringResource(R.string.wifi_frequency_axis), style = NetKitType.label, color = colors.dim)
                networks.forEachIndexed { index, _ ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(16.dp).clearAndSetSemantics { }) {
                            drawWifiMarker(index, Color(palette.series[index]), Offset(size.width / 2, size.height / 2), 4.dp.toPx())
                        }
                        Text(labels[index], Modifier.weight(1f), style = NetKitType.label, color = colors.text)
                    }
                }
            }
        }
    }
}

@Composable private fun RssiAxis() {
    Column(Modifier.height(128.dp).padding(end = Spacing.small), verticalArrangement = Arrangement.SpaceBetween) {
        listOf(-30, -65, -100).forEach { Text(stringResource(R.string.wifi_dbm_value, it), style = NetKitType.label, color = NetKitTheme.colors.dim) }
    }
}

@Composable private fun AxisLabels(start: String, middle: String, end: String) {
    Row(Modifier.fillMaxWidth()) {
        // Same measured label as the widest Y-axis value; no fixed pixel/font assumptions.
        Text(stringResource(R.string.wifi_dbm_value, -100), Modifier.padding(end = Spacing.small).alpha(0f).clearAndSetSemantics { },
            style = NetKitType.label)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(start, style = NetKitType.label, color = NetKitTheme.colors.dim)
            Text(middle, style = NetKitType.label, color = NetKitTheme.colors.dim)
            Text(end, style = NetKitType.label, color = NetKitTheme.colors.dim)
        }
    }
}

/** Stateless production UI, also rendered by instrumentation with explicit sample data. */
@Composable internal fun WifiDatasetNotice(age: WifiDatasetAge) {
    val colors = NetKitTheme.colors
    Text(age.ageMs?.let { stringResource(R.string.wifi_last_scan_age, it / 1000) }
        ?: stringResource(R.string.wifi_dataset_unknown), style = NetKitType.label, color = colors.dim)
    when (age.freshness) {
        WifiDatasetFreshness.AGING -> Text(stringResource(R.string.wifi_dataset_aging), style = NetKitType.label, color = colors.warn)
        WifiDatasetFreshness.EXPIRED -> Text(stringResource(R.string.wifi_dataset_expired), style = NetKitType.label, color = colors.warn)
        else -> Unit
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun WifiDatasetRecommendations(
    state: WifiAnalyzerState, elapsedNow: Long, width: Int, onWidth: (Int) -> Unit, onScan: () -> Unit,
) {
    val colors = NetKitTheme.colors
    val age = wifiDatasetAge(state.lastUpdatedElapsedMs, elapsedNow)
    val recommendations = remember(state, age.freshness, width) {
        recommendDatasetChannels(state, elapsedNow, width)
    }
    SectionCard(stringResource(R.string.wifi_recommendations_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Text(stringResource(R.string.wifi_recommendation_width), style = NetKitType.label, color = colors.muted)
            FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                listOf(20, 40, 80, 160).forEach { value -> SmallChip(stringResource(R.string.wifi_width_mhz, value), value == width) { onWidth(value) } }
            }
        }
        if (age.allowsRecommendation) {
            if (recommendations.isEmpty()) Text(stringResource(R.string.wifi_no_recommendation), style = NetKitType.body, color = colors.muted)
            else Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                recommendations.forEach { recommendation ->
                    Text(stringResource(R.string.wifi_recommendation_detail, bandLabel(recommendation.band), recommendation.channel,
                        recommendation.widthMhz, String.format(Locale.getDefault(), "%.3e", recommendation.score)),
                        style = NetKitType.body, color = colors.text)
                    if (recommendation.mayRequireDfs) Text(stringResource(R.string.wifi_dfs_warning), style = NetKitType.label, color = colors.warn)
                }
            }
        } else {
            if (age.freshness == WifiDatasetFreshness.UNKNOWN) Text(stringResource(R.string.wifi_dataset_unconfirmed),
                style = NetKitType.body, color = colors.muted)
            PrimaryButton(stringResource(R.string.wifi_scan), onScan, enabled = state.status != WifiScanStatus.SCANNING, small = true)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { WifiDatasetNotice(age) }
    }
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable internal fun bandLabel(band: WifiBand) = stringResource(when (band) {
    WifiBand.GHZ_2_4 -> R.string.wifi_band_24
    WifiBand.GHZ_5 -> R.string.wifi_band_5
    WifiBand.GHZ_6 -> R.string.wifi_band_6
    WifiBand.UNKNOWN -> R.string.wifi_band_unknown
})
@Composable private fun sortLabel(sort: WifiSort) = stringResource(when (sort) {
    WifiSort.SIGNAL -> R.string.wifi_sort_signal
    WifiSort.NAME -> R.string.wifi_sort_name
    WifiSort.CHANNEL -> R.string.wifi_sort_channel
})
@Composable private fun securityLabel(security: WifiSecurity) = stringResource(when (security) {
    WifiSecurity.OPEN -> R.string.wifi_security_open
    WifiSecurity.WEP -> R.string.wifi_security_wep
    WifiSecurity.WPA -> R.string.wifi_security_wpa
    WifiSecurity.WPA2 -> R.string.wifi_security_wpa2
    WifiSecurity.WPA3 -> R.string.wifi_security_wpa3
    WifiSecurity.WPA2_WPA3 -> R.string.wifi_security_wpa2_wpa3
    WifiSecurity.OWE_TRANSITION -> R.string.wifi_security_owe_transition
    WifiSecurity.OWE -> R.string.wifi_security_owe
    WifiSecurity.UNKNOWN -> R.string.wifi_security_unknown
})
/** Short form for the collapsed row subtitle; null when unknown (the expanded row says so). */
@Composable private fun shortSecurityLabel(security: WifiSecurity): String? = when (security) {
    WifiSecurity.WPA2_WPA3 -> stringResource(R.string.wifi_security_wpa2_wpa3_short)
    WifiSecurity.OWE_TRANSITION -> stringResource(R.string.wifi_security_owe_transition_short)
    WifiSecurity.UNKNOWN -> null
    else -> securityLabel(security)
}
