package cz.netkit.app.speed

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.netkit.app.BuildConfig
import cz.netkit.app.R
import cz.netkit.app.net.netKindLabel
import cz.netkit.app.overview.ratText
import cz.netkit.app.overview.signed
import cz.netkit.shared.cellular.CellRat
import cz.netkit.app.ui.InfoNote
import cz.netkit.app.ui.KeyValue
import cz.netkit.app.ui.KeyValueRow
import cz.netkit.app.ui.KeyValueRows
import cz.netkit.app.ui.LiveBadge
import cz.netkit.app.ui.NeutralButton
import cz.netkit.app.ui.Panel
import cz.netkit.app.ui.PrimaryButton
import cz.netkit.app.ui.ScreenHeader
import cz.netkit.app.ui.SectionCard
import cz.netkit.app.ui.Spacing
import cz.netkit.app.ui.StatusBanner
import cz.netkit.app.ui.StatusKind
import cz.netkit.app.ui.Tag
import cz.netkit.app.ui.Tone
import cz.netkit.app.ui.chart.ChartLine
import cz.netkit.app.ui.chart.TimeSeriesChart
import cz.netkit.app.ui.motion.MotionToken
import cz.netkit.app.ui.motion.SPIN_MS
import cz.netkit.app.ui.motion.rememberLoopingAllowed
import cz.netkit.app.ui.motion.rememberNetKitTween
import cz.netkit.app.ui.theme.NetKitShape
import cz.netkit.app.ui.theme.NetKitTheme
import cz.netkit.app.ui.theme.NetKitType
import cz.netkit.app.ui.tone
import cz.netkit.shared.net.NetKind
import cz.netkit.shared.speed.BufferbloatGrade
import cz.netkit.shared.speed.LatencyStats
import cz.netkit.shared.speed.LibreSpeedUrlError
import cz.netkit.shared.speed.SPEED_DOWNLOAD_STREAMS
import cz.netkit.shared.speed.SPEED_HISTORY_MAX
import cz.netkit.shared.speed.SPEED_SAMPLE_MS
import cz.netkit.shared.speed.SPEED_UPLOAD_STREAMS
import cz.netkit.shared.speed.SpeedEngine
import cz.netkit.shared.speed.SpeedRecord
import cz.netkit.shared.speed.bufferbloatGrade
import cz.netkit.shared.speed.latencyIncreaseMs
import cz.netkit.shared.wifi.WifiBand
import cz.netkit.shared.wifi.bandForFrequency
import cz.netkit.shared.wifi.channelForFrequency
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Speed test screen (segment "Rychlost" of the Signal tab). A running test is cancelled when the
 * screen pauses or leaves composition ([LifecycleResumeEffect] → [SpeedTestViewModel.setActive]),
 * except for an activity recreation caused by a configuration change (rotation).
 *
 * Layout (docs/design.md, prototype `vSpeed`): measurement card (dial, value below it, three phase
 * tiles, one action), result cards after a test, server choice, history and one [InfoNote].
 */
@Composable
fun SpeedTestScreen(viewModel: SpeedTestViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        viewModel.setActive(true)
        // A rotation recreates the activity; the ViewModel and its run survive it.
        onPauseOrDispose { if (context.findActivity()?.isChangingConfigurations != true) viewModel.setActive(false) }
    }
    var customDialog by rememberSaveable { mutableStateOf(false) }
    val finished = !state.running && state.engine != null

    LazyColumn(
        modifier.fillMaxSize().testTag("speed_screen"),
        contentPadding = PaddingValues(Spacing.normal),
        verticalArrangement = Arrangement.spacedBy(Spacing.normal),
    ) {
        item(key = "header") {
            val live: (@Composable RowScope.() -> Unit)? = if (state.running) {
                { LiveBadge(stringResource(R.string.speed_live), Modifier.testTag("speed_live")) }
            } else null
            val network = stringResource(state.networkKind?.let(::netKindLabel) ?: R.string.net_kind_none)
            ScreenHeader(stringResource(R.string.speed_title), stringResource(R.string.speed_subtitle_network, network), trailing = live)
        }
        item(key = "measure") { MeasurementCard(state, onStart = viewModel::start, onStop = viewModel::stop) }
        if (state.storeCorrupt) item(key = "store") { StatusBanner(stringResource(R.string.speed_store_failed), StatusKind.WARNING) }
        state.failure?.let { failure ->
            item(key = "failure") {
                Column(Modifier.testTag("speed_error")) { StatusBanner(failureText(failure, state.failureCode), StatusKind.ERROR) }
            }
        }
        if (state.streamsDegraded) item(key = "streams") {
            Column(Modifier.testTag("speed_streams_warning")) {
                StatusBanner(stringResource(R.string.speed_streams_degraded,
                    streamsText(state.downloadStreams), streamsText(state.uploadStreams)), StatusKind.WARNING)
            }
        }
        if (state.phase == SpeedPhase.CANCELLED) item(key = "cancelled") {
            StatusBanner(stringResource(R.string.speed_cancelled_note), StatusKind.INFO)
        }
        if (finished && (state.idle != null || state.loadedDownload != null || state.loadedUpload != null)) {
            item(key = "latency") { LatencyCard(state) }
        }
        if (finished && (state.downloadPoints.isNotEmpty() || state.uploadPoints.isNotEmpty())) {
            item(key = "trace") { TraceCard(state) }
        }
        if (finished && state.engine == SpeedEngine.NDT7 && (state.ndt7Download != null || state.ndt7Upload != null)) {
            item(key = "ndt7") { Ndt7Card(state) }
        }
        // A test refused before it started (e.g. no network) has no context worth a card of dashes.
        if (finished && (state.wifi != null || state.measuredKind != null || state.serverHost != null || state.ispText != null)) {
            item(key = "context") { ContextCard(state) }
        }
        item(key = "servers") {
            ServerCard(state, onSelect = viewModel::select, onAdd = { customDialog = true }, onRemove = viewModel::removeCustomServer)
        }
        item(key = "history") { HistoryCard(state.history, enabled = !state.running, onClear = viewModel::clearHistory) }
        item(key = "note") { InfoNote(stringResource(R.string.speed_note_summary), stringResource(R.string.speed_note_detail)) }
    }

    if (state.consentPending) {
        NetKitDialog(
            tag = "speed_consent",
            onDismiss = viewModel::dismissNdt7Notice,
            title = stringResource(R.string.speed_consent_title),
            confirm = { PrimaryButton(stringResource(R.string.speed_consent_accept), viewModel::acceptNdt7Notice,
                Modifier.testTag("speed_consent_accept")) },
            dismiss = { NeutralButton(stringResource(R.string.speed_consent_decline), viewModel::dismissNdt7Notice,
                Modifier.testTag("speed_consent_decline")) },
        ) { Text(stringResource(R.string.speed_consent_text), style = NetKitType.body, color = NetKitTheme.colors.muted) }
    }
    state.meteredPrompt?.let { prompt -> MeteredDialog(prompt, onAccept = viewModel::acceptMetered, onDismiss = viewModel::dismissMetered) }
    if (customDialog) {
        CustomServerDialog(onDismiss = { customDialog = false }, onSubmit = { text ->
            viewModel.setCustomServer(text).also { if (it == null) customDialog = false }
        })
    }
}

// --- Measurement card ---------------------------------------------------------------------------

@Composable
private fun phaseColor(phase: SpeedPhase): Color {
    val colors = NetKitTheme.colors
    return when (phase) {
        SpeedPhase.IDLE, SpeedPhase.CANCELLED -> colors.dim
        SpeedPhase.PREPARING, SpeedPhase.LOCATING, SpeedPhase.LATENCY -> colors.accent
        SpeedPhase.DOWNLOAD, SpeedPhase.DONE -> colors.info
        SpeedPhase.UPLOAD -> colors.violet
        SpeedPhase.FAILED -> colors.err
    }
}

@StringRes
private fun phaseLabel(phase: SpeedPhase): Int = when (phase) {
    SpeedPhase.IDLE -> R.string.speed_phase_idle
    SpeedPhase.PREPARING -> R.string.speed_phase_preparing
    SpeedPhase.LOCATING -> R.string.speed_phase_locating
    SpeedPhase.LATENCY -> R.string.speed_phase_latency
    SpeedPhase.DOWNLOAD -> R.string.speed_phase_download
    SpeedPhase.UPLOAD -> R.string.speed_phase_upload
    SpeedPhase.DONE -> R.string.speed_phase_done
    SpeedPhase.FAILED -> R.string.speed_phase_failed
    SpeedPhase.CANCELLED -> R.string.speed_phase_cancelled
}

@Composable
private fun MeasurementCard(state: SpeedUiState, onStart: () -> Unit, onStop: () -> Unit) {
    val colors = NetKitTheme.colors
    val phase = state.phase
    // Live value of the running direction; after a finished test the final download (prototype "Hotovo · stahování").
    val shown: Double? = when (phase) {
        SpeedPhase.DOWNLOAD, SpeedPhase.UPLOAD -> state.liveMbps
        SpeedPhase.DONE -> state.download?.mbps
        else -> null
    }
    val color = phaseColor(phase)
    val unit = stringResource(R.string.speed_unit_mbps)
    Panel(Modifier.testTag("speed_result")) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            SpeedDial(
                mbps = (shown ?: 0.0).toFloat(),
                active = state.running,
                color = if (phase == SpeedPhase.FAILED) colors.dim else color,
                description = stringResource(R.string.speed_dial_description, shown?.let(::mbpsText) ?: stringResource(R.string.speed_no_value)),
            )
            SpeedReadout(shown?.toFloat(), unit, stringResource(phaseLabel(phase)), color, Modifier.testTag("speed_phase"))
        }
        PhaseTiles(state)
        if (state.running) {
            NeutralButton(stringResource(R.string.speed_stop), onStop, Modifier.fillMaxWidth().testTag("speed_stop"))
        } else {
            PrimaryButton(
                stringResource(if (phase == SpeedPhase.IDLE) R.string.speed_start else R.string.speed_restart), onStart,
                Modifier.fillMaxWidth().testTag("speed_start"), enabled = state.loaded && state.selectedChoice != null,
            )
        }
    }
}

private class PhaseTileData(val label: String, val value: String?, val unit: String, val color: Color, val active: Boolean,
    val progress: Float?, val tag: String?)

/** Latency / download / upload in a row of three; stacked as label–value rows on a narrow card or large font. */
@Composable
private fun PhaseTiles(state: SpeedUiState) {
    val colors = NetKitTheme.colors
    val ms = stringResource(R.string.speed_unit_ms)
    val mbps = stringResource(R.string.speed_unit_mbps)
    val tiles = listOf(
        PhaseTileData(stringResource(R.string.speed_tile_latency), state.idle?.medianMs?.let(::msText), ms, colors.accent,
            state.phase == SpeedPhase.LATENCY, null, null),
        PhaseTileData(stringResource(R.string.speed_download), state.download?.mbps?.let(::mbpsText), mbps, colors.info,
            state.phase == SpeedPhase.DOWNLOAD, state.phaseProgress, "speed_download"),
        PhaseTileData(stringResource(R.string.speed_upload), state.upload?.mbps?.let(::mbpsText), mbps, colors.violet,
            state.phase == SpeedPhase.UPLOAD, state.phaseProgress, "speed_upload"),
    )
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Three labels like "Odesílání" need ~280 dp at font scale 1.0; below that the row would break words.
        if (maxWidth >= 280.dp * fontScale) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                tiles.forEach { PhaseTile(it, stacked = false, Modifier.weight(1f).fillMaxHeight()) }
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                tiles.forEach { PhaseTile(it, stacked = true, Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun PhaseTile(tile: PhaseTileData, stacked: Boolean, modifier: Modifier) {
    val colors = NetKitTheme.colors
    val value = buildAnnotatedString {
        append(tile.value ?: stringResource(R.string.speed_no_value))
        append(' ')
        withStyle(SpanStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = colors.muted, letterSpacing = 0.sp)) { append(tile.unit) }
    }
    Box(modifier.clip(NetKitShape.nested).background(colors.surface2)
        .then(if (tile.tag != null) Modifier.testTag(tile.tag) else Modifier).semantics(mergeDescendants = true) {}) {
        val padding = Modifier.padding(Spacing.medium)
        if (stacked) {
            Row(padding.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                Text(tile.label, Modifier.weight(1f), style = NetKitType.tag, color = colors.muted)
                Text(value, style = NetKitType.metric, color = colors.text)
            }
        } else {
            Column(padding, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tile.label, style = NetKitType.tag, color = colors.muted)
                Text(value, style = NetKitType.metric, color = colors.text)
            }
        }
        if (tile.active) PhaseProgressLine(tile.progress, tile.color, Modifier.align(Alignment.BottomStart))
    }
}

/** 2 dp line at the bottom of the active tile; without a known progress (latency) a sweeping segment. */
@Composable
private fun PhaseProgressLine(progress: Float?, color: Color, modifier: Modifier) {
    val line = modifier.fillMaxWidth().height(2.dp)
    if (progress != null) {
        val shown by animateFloatAsState(progress.coerceIn(0f, 1f), rememberNetKitTween(MotionToken.FAST), label = "phase-progress")
        Box(line.drawBehind { drawRect(color, size = Size(size.width * shown, size.height)) })
    } else if (rememberLoopingAllowed()) {
        val sweep by rememberInfiniteTransition(label = "phase-sweep").animateFloat(0f, 1f,
            infiniteRepeatable(tween(SPIN_MS, easing = LinearEasing)), label = "phase-sweep-offset")
        Box(line.drawBehind {
            val width = size.width * 0.3f
            drawRect(color, topLeft = Offset((size.width + width) * sweep - width, 0f), size = Size(width, size.height))
        })
    } else {
        Box(line.drawBehind { drawRect(color.copy(alpha = color.alpha * 0.5f)) })
    }
}

// --- Latency under load -------------------------------------------------------------------------

@Composable
private fun LatencyCard(state: SpeedUiState) {
    val colors = NetKitTheme.colors
    val ndt7 = state.engine == SpeedEngine.NDT7
    val dash = stringResource(R.string.speed_no_value)
    val idle = state.idle
    val worst = listOfNotNull(latencyIncreaseMs(idle, state.loadedDownload), latencyIncreaseMs(idle, state.loadedUpload)).maxOrNull()
    val grade = worst?.let(::bufferbloatGrade)
    val gradeDescription = grade?.let { stringResource(R.string.speed_bufferbloat, gradeText(it)) }
    val idleMedian = idle?.medianMs
    val idleJitter = idle?.jitterMs
    val idleValue = when {
        idleMedian == null -> dash
        ndt7 || idleJitter == null -> "${msText(idleMedian)} ${stringResource(R.string.speed_unit_ms)}"
        else -> stringResource(R.string.speed_latency_value, msText(idleMedian), msText(idleJitter))
    }
    val loss = idle?.takeIf { it.lost > 0 }?.let {
        KeyValue(stringResource(R.string.speed_latency_loss_label), stringResource(R.string.speed_latency_loss, it.lost, it.sent), Tone.WARN)
    }
    val download = KeyValue(stringResource(if (ndt7) R.string.speed_latency_download_server else R.string.speed_latency_download),
        loadedText(idle, state.loadedDownload), loadedTone(idle, state.loadedDownload))
    val upload = if (ndt7) null else KeyValue(stringResource(R.string.speed_latency_upload),
        loadedText(idle, state.loadedUpload), loadedTone(idle, state.loadedUpload))
    val gradeLetter: (@Composable RowScope.() -> Unit)? = if (grade != null) {
        {
            Text(gradeText(grade), Modifier.clearAndSetSemantics { contentDescription = gradeDescription.orEmpty() },
                style = NetKitType.metricLarge.copy(fontWeight = FontWeight.ExtraBold), color = colors.tone(gradeTone(grade)).foreground)
        }
    } else null
    SectionCard(stringResource(R.string.speed_latency_section), trailing = gradeLetter) {
        KeyValueRows(listOfNotNull(
            KeyValue(stringResource(if (ndt7) R.string.speed_latency_idle_server else R.string.speed_latency_idle), idleValue),
            loss, download, upload,
        ))
    }
}

@Composable
private fun loadedText(idle: LatencyStats?, loaded: LatencyStats?): String {
    val median = loaded?.medianMs ?: return stringResource(R.string.speed_no_value)
    val increase = latencyIncreaseMs(idle, loaded) ?: return "${msText(median)} ${stringResource(R.string.speed_unit_ms)}"
    return stringResource(R.string.speed_latency_loaded_value, msText(median), msText(increase))
}

private fun loadedTone(idle: LatencyStats?, loaded: LatencyStats?): Tone =
    latencyIncreaseMs(idle, loaded)?.let { gradeTone(bufferbloatGrade(it)) } ?: Tone.NEUTRAL

private fun gradeTone(grade: BufferbloatGrade): Tone = when (grade) {
    BufferbloatGrade.A_PLUS, BufferbloatGrade.A -> Tone.OK
    BufferbloatGrade.B, BufferbloatGrade.C -> Tone.WARN
    BufferbloatGrade.D, BufferbloatGrade.F -> Tone.ERR
}

private fun gradeText(grade: BufferbloatGrade): String = when (grade) {
    BufferbloatGrade.A_PLUS -> "A+"
    else -> grade.name
}

// --- Throughput trace ---------------------------------------------------------------------------

/**
 * Both directions over their own 10–15 s phase, overlaid from the phase start. The window is
 * exactly the longer series, so the data fills the full width (docs/design.md §6).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TraceCard(state: SpeedUiState) {
    val colors = NetKitTheme.colors
    val dash = stringResource(R.string.speed_no_value)
    val unit = stringResource(R.string.speed_unit_mbps)
    val down = state.downloadPoints.map { it?.toFloat() }
    val up = state.uploadPoints.map { it?.toFloat() }
    val downLabel = stringResource(R.string.speed_download)
    val upLabel = stringResource(R.string.speed_upload)
    SectionCard(stringResource(R.string.speed_chart_section), meta = unit) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.normal), verticalArrangement = Arrangement.spacedBy(Spacing.tiny)) {
            if (down.isNotEmpty()) LegendItem(stringResource(R.string.speed_legend_download,
                state.download?.peakMbps?.let(::mbpsText) ?: dash), colors.info, dashed = false)
            if (up.isNotEmpty()) LegendItem(stringResource(R.string.speed_legend_upload,
                state.upload?.peakMbps?.let(::mbpsText) ?: dash), colors.violet, dashed = true)
        }
        TimeSeriesChart(
            lines = listOfNotNull(
                down.takeIf { it.isNotEmpty() }?.let { ChartLine(downLabel, it, colors.info) },
                up.takeIf { it.isNotEmpty() }?.let { ChartLine(upLabel, it, colors.violet, dashed = true) },
            ),
            capacity = maxOf(down.size, up.size, 2),
            sampleIntervalMs = SPEED_SAMPLE_MS,
            range = null,
            ticks = null,
            unitLabel = unit,
            description = stringResource(R.string.speed_chart_description,
                state.download?.mbps?.let(::mbpsText) ?: dash, state.upload?.mbps?.let(::mbpsText) ?: dash),
            modifier = Modifier.testTag("speed_chart"),
            height = 140.dp,
        )
    }
}

@Composable
private fun LegendItem(text: String, color: Color, dashed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Canvas(Modifier.size(width = 16.dp, height = 2.dp)) {
            drawLine(color, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), size.height,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null)
        }
        Text(text, style = NetKitType.label, color = NetKitTheme.colors.muted)
    }
}

// --- NDT7 server view ---------------------------------------------------------------------------

@Composable
private fun Ndt7Card(state: SpeedUiState) {
    val dash = stringResource(R.string.speed_no_value)
    @Composable fun mbps(v: Double?) = v?.let { stringResource(R.string.speed_mbps_value, mbpsText(it)) } ?: dash
    val down = state.ndt7Download
    val up = state.ndt7Upload
    SectionCard(stringResource(R.string.speed_server_view)) {
        KeyValueRows(listOfNotNull(
            KeyValue(stringResource(R.string.speed_server_app_download), mbps(down?.appMbps)),
            KeyValue(stringResource(R.string.speed_server_tcp_download), mbps(down?.tcpMbps)),
            KeyValue(stringResource(R.string.speed_server_retrans),
                down?.retransPercent?.let { stringResource(R.string.speed_percent_value, decimal(it, if (it < 10) 2 else 1)) } ?: dash),
            down?.bbrMbps?.let { KeyValue(stringResource(R.string.speed_server_bbr), mbps(it)) },
            KeyValue(stringResource(R.string.speed_server_app_upload), mbps(up?.appMbps)),
        ))
    }
}

// --- Context ------------------------------------------------------------------------------------

@Composable
private fun ContextCard(state: SpeedUiState) {
    val dash = stringResource(R.string.speed_no_value)
    val wifi = state.wifi
    val linkDuring = if (state.linkRxDuringMbps != null || state.linkTxDuringMbps != null) {
        KeyValue(stringResource(R.string.speed_context_link_during), stringResource(R.string.speed_context_link_value,
            state.linkRxDuringMbps?.toString() ?: dash, state.linkTxDuringMbps?.toString() ?: dash))
    } else null
    // Wi-Fi keeps its radio rows; mobile data, ethernet or VPN show the network type instead.
    val onWifi = wifi != null || state.measuredKind == NetKind.WIFI
    val wifiRows = if (onWifi) listOf(
        KeyValue(stringResource(R.string.speed_context_ssid), wifi?.ssid ?: stringResource(R.string.speed_context_hidden_ssid)),
        KeyValue(stringResource(R.string.speed_context_band), bandText(wifi?.frequencyMhz) ?: dash),
        KeyValue(stringResource(R.string.speed_context_rssi),
            wifi?.rssiDbm?.let { stringResource(R.string.speed_context_rssi_value, it.toString().replace('-', '−')) } ?: dash),
        KeyValue(stringResource(R.string.speed_context_link), stringResource(R.string.speed_context_link_value,
            wifi?.rxLinkMbps?.toString() ?: dash, wifi?.txLinkMbps?.toString() ?: dash)),
    ) else listOfNotNull(
        state.measuredKind?.let { KeyValue(stringResource(R.string.speed_context_network), stringResource(netKindLabel(it))) },
        // Mobile data: operator, standard and signal of the data SIM at the start.
        state.cellular?.let { cell ->
            KeyValue(stringResource(R.string.speed_context_operator), cell.operatorName ?: stringResource(R.string.cellular_operator_unknown))
        },
        state.cellular?.takeIf { it.rat != CellRat.UNKNOWN }?.let { cell ->
            KeyValue(stringResource(R.string.speed_context_standard), stringResource(ratText(cell.rat)))
        },
        state.cellular?.signalDbm?.let { dbm ->
            KeyValue(stringResource(R.string.speed_context_signal), stringResource(R.string.speed_context_rssi_value, signed(dbm)))
        },
    )
    SectionCard(stringResource(R.string.speed_context_section)) {
        KeyValueRows(listOfNotNull(
            KeyValue(stringResource(R.string.speed_context_engine), stringResource(
                if (state.engine == SpeedEngine.NDT7) R.string.speed_engine_ndt7 else R.string.speed_engine_librespeed)),
            KeyValue(stringResource(R.string.speed_context_server), listOfNotNull(state.serverLabel,
                state.serverHost?.takeIf { it != state.serverLabel }).joinToString("\n").ifEmpty { dash }),
            state.ispText?.let { KeyValue(stringResource(R.string.speed_context_isp), it, monospace = true) },
        ) + wifiRows + listOfNotNull(
            linkDuring,
            state.startedAtMs?.let { KeyValue(stringResource(R.string.speed_context_time), timeText(it)) },
        ))
    }
}

@Composable
private fun bandText(frequencyMhz: Int?): String? {
    val frequency = frequencyMhz ?: return null
    val band = when (bandForFrequency(frequency)) {
        WifiBand.GHZ_2_4 -> stringResource(R.string.wifi_signal_band_24)
        WifiBand.GHZ_5 -> stringResource(R.string.wifi_signal_band_5)
        WifiBand.GHZ_6 -> stringResource(R.string.wifi_signal_band_6)
        WifiBand.UNKNOWN -> return "$frequency MHz"
    }
    return channelForFrequency(frequency)?.let { stringResource(R.string.wifi_signal_band_channel, band, it) } ?: band
}

// --- Servers ------------------------------------------------------------------------------------

@Composable
private fun ServerCard(state: SpeedUiState, onSelect: (String) -> Unit, onAdd: () -> Unit, onRemove: () -> Unit) {
    SectionCard(stringResource(R.string.speed_server_section)) {
        val selectedId = state.selectedChoice?.id
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            state.choices.forEachIndexed { index, choice ->
                ServerOption(
                    title = choice.name,
                    subtitle = when (choice.engine) {
                        SpeedEngine.LIBRESPEED -> stringResource(R.string.speed_server_librespeed,
                            SPEED_DOWNLOAD_STREAMS.toString(), SPEED_UPLOAD_STREAMS.toString())
                        SpeedEngine.NDT7 -> stringResource(R.string.speed_server_ndt7)
                    },
                    custom = choice.custom,
                    selected = choice.id == selectedId,
                    enabled = !state.running,
                    onClick = { onSelect(choice.id) },
                    modifier = Modifier.testTag("speed_server_$index"),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            TextButton(onClick = onAdd, enabled = !state.running, modifier = Modifier.heightIn(min = 48.dp).testTag("speed_server_add")) {
                Text(stringResource(R.string.speed_server_add))
            }
            if (state.choices.any { it.custom }) {
                TextButton(onClick = onRemove, enabled = !state.running, modifier = Modifier.heightIn(min = 48.dp).testTag("speed_server_remove")) {
                    Text(stringResource(R.string.speed_server_remove))
                }
            }
        }
    }
}

/** Selectable server card (prototype `.srv`): `surface2`, selected = accent border 55 % and 8 % tint, radio dot. */
@Composable
private fun ServerOption(title: String, subtitle: String, custom: Boolean, selected: Boolean, enabled: Boolean, onClick: () -> Unit,
    modifier: Modifier) {
    val colors = NetKitTheme.colors
    val shape = NetKitShape.nested
    val border by animateColorAsState(if (selected) colors.accent.copy(alpha = 0.55f) else colors.surface2,
        rememberNetKitTween(MotionToken.FAST), label = "server-border")
    val fill by animateColorAsState(if (selected) colors.accent.copy(alpha = 0.08f).compositeOver(colors.surface2) else colors.surface2,
        rememberNetKitTween(MotionToken.FAST), label = "server-fill")
    val radio by animateColorAsState(if (selected) colors.accent else colors.dim, rememberNetKitTween(MotionToken.FAST), label = "server-radio")
    Row(
        modifier.fillMaxWidth().clip(shape).background(fill).border(1.dp, border, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = Spacing.normal, vertical = Spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Text(title, Modifier.weight(1f, fill = false), style = NetKitType.panelTitle, color = if (enabled) colors.text else colors.muted)
                if (custom) Tag(stringResource(R.string.speed_server_custom))
            }
            Text(subtitle, style = NetKitType.label, color = colors.muted)
        }
        Box(Modifier.size(18.dp).border(2.dp, radio, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(radio))
        }
    }
}

// --- History ------------------------------------------------------------------------------------

@Composable
private fun HistoryCard(history: List<SpeedRecord>, enabled: Boolean, onClear: () -> Unit) {
    val dash = stringResource(R.string.speed_no_value)
    val partial = stringResource(R.string.speed_history_partial)
    SectionCard(stringResource(R.string.speed_history_section),
        meta = if (history.isEmpty()) null else stringResource(R.string.speed_history_meta, SPEED_HISTORY_MAX)) {
        if (history.isEmpty()) {
            Text(stringResource(R.string.speed_history_empty), style = NetKitType.body, color = NetKitTheme.colors.muted)
        } else {
            Column(Modifier.fillMaxWidth()) {
                history.forEachIndexed { index, record ->
                    val label = listOfNotNull(historyTime(record.timestampMs), record.serverName, stringResource(netKindLabel(record.measuredKind)),
                        partial.takeIf { record.partial })
                        .joinToString(" · ")
                    val value = stringResource(R.string.speed_history_row, record.downloadMbps?.let(::mbpsText) ?: dash,
                        record.uploadMbps?.let(::mbpsText) ?: dash)
                    Box(Modifier.testTag("speed_history_$index").semantics(mergeDescendants = true) {}) {
                        KeyValueRow(label, value, if (record.partial) Tone.WARN else Tone.NEUTRAL, showDivider = index > 0)
                    }
                }
            }
            TextButton(onClick = onClear, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("speed_history_clear")) {
                Text(stringResource(R.string.speed_history_clear))
            }
        }
    }
}

/** "dnes 17:33", "včera 21:10", otherwise the short date and time. */
@Composable
private fun historyTime(epochMs: Long): String {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val (daysAgo, time, full) = remember(epochMs, locale) {
        val date = Date(epochMs)
        Triple(daysBefore(epochMs, System.currentTimeMillis()), DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(date),
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(date))
    }
    return when (daysAgo) {
        0 -> stringResource(R.string.speed_history_today, time)
        1 -> stringResource(R.string.speed_history_yesterday, time)
        else -> full
    }
}

/** Calendar days between [epochMs] and [nowMs] in the local time zone; -1 for a future timestamp. */
private fun daysBefore(epochMs: Long, nowMs: Long): Int {
    fun dayStart(ms: Long) = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val record = dayStart(epochMs)
    val today = dayStart(nowMs)
    if (record.after(today)) return -1
    if (!record.before(today)) return 0
    today.add(Calendar.DAY_OF_YEAR, -1)
    return if (!record.before(today)) 1 else 2
}

// --- Dialogs ------------------------------------------------------------------------------------

/** AlertDialog on NetKit tokens: `surface` card (radius 16), `panelTitle`, primary + neutral action. */
@Composable
private fun NetKitDialog(tag: String, onDismiss: () -> Unit, title: String, confirm: @Composable () -> Unit,
    dismiss: @Composable () -> Unit, content: @Composable () -> Unit) {
    val colors = NetKitTheme.colors
    AlertDialog(
        modifier = Modifier.testTag(tag).semantics { testTagsAsResourceId = BuildConfig.DEBUG },
        onDismissRequest = onDismiss,
        title = { Text(title, style = NetKitType.panelTitle) },
        text = content,
        confirmButton = confirm,
        dismissButton = dismiss,
        shape = NetKitShape.card,
        containerColor = colors.surface,
        titleContentColor = colors.text,
        textContentColor = colors.muted,
    )
}

@Composable
private fun CustomServerDialog(onDismiss: () -> Unit, onSubmit: (String) -> LibreSpeedUrlError?) {
    val colors = NetKitTheme.colors
    var text by rememberSaveable { mutableStateOf("https://") }
    var error by rememberSaveable { mutableStateOf<LibreSpeedUrlError?>(null) }
    val submit = { error = onSubmit(text) }
    NetKitDialog(
        tag = "speed_custom_dialog",
        onDismiss = onDismiss,
        title = stringResource(R.string.speed_custom_title),
        confirm = { PrimaryButton(stringResource(R.string.speed_custom_save), submit, Modifier.testTag("speed_custom_save")) },
        dismiss = { NeutralButton(stringResource(R.string.speed_custom_cancel), onDismiss) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; error = null },
                modifier = Modifier.fillMaxWidth().testTag("speed_custom_url"),
                label = { Text(stringResource(R.string.speed_custom_label)) },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { e -> { Text(stringResource(urlErrorText(e))) } },
                textStyle = NetKitType.body,
                shape = NetKitShape.nested,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.text, unfocusedTextColor = colors.text,
                    focusedContainerColor = colors.surface2, unfocusedContainerColor = colors.surface2, errorContainerColor = colors.surface2,
                    focusedBorderColor = colors.accent, unfocusedBorderColor = colors.surface2, errorBorderColor = colors.err,
                    focusedLabelColor = colors.accent, unfocusedLabelColor = colors.muted, errorLabelColor = colors.err,
                    cursorColor = colors.accent, errorSupportingTextColor = colors.err,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            Text(stringResource(R.string.speed_custom_hint), style = NetKitType.label, color = colors.muted)
        }
    }
}

/** O-3: before a full test on a metered network, the estimated data and an explicit start. */
@Composable
private fun MeteredDialog(prompt: MeteredPrompt, onAccept: () -> Unit, onDismiss: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val megabytes = remember(prompt.estimate.megabytes, locale) { NumberFormat.getIntegerInstance(locale).format(prompt.estimate.megabytes) }
    NetKitDialog(
        tag = "speed_metered",
        onDismiss = onDismiss,
        title = stringResource(R.string.speed_metered_title),
        confirm = { PrimaryButton(stringResource(R.string.speed_metered_accept), onAccept, Modifier.testTag("speed_metered_accept")) },
        dismiss = { NeutralButton(stringResource(R.string.speed_metered_decline), onDismiss, Modifier.testTag("speed_metered_decline")) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Text(stringResource(if (prompt.kind == NetKind.CELLULAR) R.string.speed_metered_cellular else R.string.speed_metered_other, megabytes),
                Modifier.testTag("speed_metered_estimate"), style = NetKitType.body, color = NetKitTheme.colors.text)
            Text(stringResource(if (prompt.estimate.fromHistory) R.string.speed_metered_from_history else R.string.speed_metered_default),
                style = NetKitType.label, color = NetKitTheme.colors.muted)
        }
    }
}

@StringRes
private fun urlErrorText(error: LibreSpeedUrlError): Int = when (error) {
    LibreSpeedUrlError.EMPTY -> R.string.speed_url_error_empty
    LibreSpeedUrlError.TOO_LONG -> R.string.speed_url_error_too_long
    LibreSpeedUrlError.NOT_HTTPS -> R.string.speed_url_error_not_https
    LibreSpeedUrlError.CREDENTIALS -> R.string.speed_url_error_credentials
    LibreSpeedUrlError.QUERY_OR_FRAGMENT -> R.string.speed_url_error_query
    LibreSpeedUrlError.BAD_HOST -> R.string.speed_url_error_host
    LibreSpeedUrlError.BAD_PORT -> R.string.speed_url_error_port
    LibreSpeedUrlError.BAD_PATH -> R.string.speed_url_error_path
}

@Composable
private fun failureText(kind: SpeedFailureKind, code: Int?): String = when (kind) {
    SpeedFailureKind.NO_NETWORK -> stringResource(R.string.speed_error_no_network)
    SpeedFailureKind.NETWORK_LOST -> stringResource(R.string.speed_error_network_lost)
    SpeedFailureKind.DNS -> stringResource(R.string.speed_error_dns)
    SpeedFailureKind.CONNECT -> stringResource(R.string.speed_error_connect)
    SpeedFailureKind.TLS -> stringResource(R.string.speed_error_tls)
    SpeedFailureKind.TIMEOUT -> stringResource(R.string.speed_error_timeout)
    SpeedFailureKind.HTTP_STATUS -> stringResource(R.string.speed_error_http, code ?: 0)
    SpeedFailureKind.PROTOCOL -> stringResource(R.string.speed_error_protocol)
    SpeedFailureKind.NOT_LIBRESPEED -> stringResource(R.string.speed_error_not_librespeed)
    SpeedFailureKind.NO_CAPACITY -> stringResource(R.string.speed_error_no_capacity)
    SpeedFailureKind.NO_SERVER -> stringResource(R.string.speed_error_no_server)
    SpeedFailureKind.NO_DATA -> stringResource(R.string.speed_error_no_data)
}

// --- Formatting ---------------------------------------------------------------------------------

/** "4/6"; dash when the direction did not run. */
@Composable
private fun streamsText(streams: DirectionStreams?): String =
    streams?.let { "${it.working}/${it.planned}" } ?: stringResource(R.string.speed_no_value)

/** ≥ 100 Mbit/s without decimals, ≥ 10 with one, below with two. */
internal fun mbpsText(value: Double): String = decimal(value, when {
    value >= 100.0 -> 0
    value >= 10.0 -> 1
    else -> 2
})

private fun msText(value: Double): String = decimal(value, if (value < 10.0) 1 else 0)

private fun decimal(value: Double, digits: Int): String = String.format(Locale.getDefault(), "%.${digits}f", value)

@Composable
private fun timeText(epochMs: Long): String = remember(epochMs) {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault()).format(Date(epochMs))
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
