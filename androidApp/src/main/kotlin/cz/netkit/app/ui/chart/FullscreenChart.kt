package cz.netkit.app.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.sp
import cz.netkit.app.ui.statTileLines
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cz.netkit.app.BuildConfig
import cz.netkit.app.R
import cz.netkit.app.ui.theme.NetKitTheme
import cz.netkit.app.ui.theme.NetKitType
import cz.netkit.shared.chart.ChartEvent
import cz.netkit.shared.chart.ChartViewport
import cz.netkit.shared.chart.windowStats
import java.text.NumberFormat
import kotlin.math.ceil

data class ChartMetric(
    val key: String, val title: String, val subtitle: String, val lines: List<ChartLine>,
    val range: ClosedFloatingPointRange<Float>?, val ticks: List<Float>?, val unitLabel: String, val decimals: Int,
)

private val ChartViewportSaver = listSaver<ChartViewport, Any>(
    save = { listOf(it.windowSamples, it.zoom, it.panSamples) },
    restore = { ChartViewport(it[0] as Int, it[1] as Float, it[2] as Float) },
)

private fun samplesForWindow(seconds: Int, intervalMs: Long, capacity: Int): Int =
    ceil(seconds * 1000.0 / intervalMs).toInt().coerceIn(1, capacity)

/**
 * Full-screen chart. [slotTimes] are the monotonic times of the chart slots (oldest → newest,
 * `null` = empty slot, e.g. `LiveTimeline.slots`). With them, a window panned into history or
 * held by the scrub stays on the same samples while new ones arrive; without them it stays
 * anchored to the newest slot.
 */
@Composable
fun FullscreenChart(
    metrics: List<ChartMetric>, selectedKey: String, onSelect: (String) -> Unit,
    capacity: Int, sampleIntervalMs: Long, events: List<ChartEvent>, onClose: () -> Unit,
    slotTimes: List<Long?>? = null,
) {
    require(capacity > 0 && sampleIntervalMs > 0)
    val colors = NetKitTheme.colors
    var windowSeconds by rememberSaveable(capacity, sampleIntervalMs) { mutableStateOf(300) }
    var viewport by rememberSaveable(capacity, sampleIntervalMs, stateSaver = ChartViewportSaver) {
        mutableStateOf(ChartViewport(samplesForWindow(300, sampleIntervalMs, capacity)))
    }
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    // Newest slot time when [viewport] was set; null = the window follows the newest sample.
    var anchor by remember { mutableStateOf<Long?>(null) }
    // The scrub froze a live window; lifting the finger returns it to live.
    var scrubFroze by remember { mutableStateOf(false) }
    val metric = metrics.firstOrNull { it.key == selectedKey } ?: metrics.firstOrNull()
    val total = metric?.lines?.maxOfOrNull { it.values.size } ?: 0
    val currentTotal by rememberUpdatedState(total)
    val currentSlotTimes by rememberUpdatedState(slotTimes)
    /** [viewport] moved by the slots that arrived since [anchor], so it shows the same samples. */
    fun held(): ChartViewport = anchor?.let { viewport.panBy(slotsSince(currentSlotTimes, it).toFloat(), currentTotal) } ?: viewport
    /** Sets a viewport chosen by the user; a window in history (or under the scrub) is held. */
    fun commit(next: ChartViewport) {
        viewport = next
        if (next.panSamples > 0f) scrubFroze = false
        anchor = if (next.panSamples > 0f || scrubFroze) newestSlotTime(currentSlotTimes) else null
    }
    val shown = held()
    val visible = shown.visible(total)
    val configuration = LocalConfiguration.current
    val numberFormat = remember(configuration, metric?.decimals) {
        NumberFormat.getNumberInstance(configuration.locales[0]).apply {
            isGroupingUsed = false
            minimumFractionDigits = (metric?.decimals ?: 0).coerceIn(0, 6)
            maximumFractionDigits = minimumFractionDigits
        }
    }
    val zoomFormat = remember(configuration) {
        NumberFormat.getNumberInstance(configuration.locales[0]).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
    }
    val density = LocalDensity.current
    val leftInset = with(density) { chartLeftInset(true, fontScale).toPx() }
    val rightInset = with(density) { ChartRightInset.toPx() }
    val closeLabel = stringResource(R.string.chart_close)
    val zoomOutLabel = stringResource(R.string.chart_zoom_out)
    val zoomInLabel = stringResource(R.string.chart_zoom_in)
    val olderLabel = stringResource(R.string.chart_pan_older)
    val newerLabel = stringResource(R.string.chart_pan_newer)
    val noValue = stringResource(R.string.chart_missing)

    Dialog(onDismissRequest = onClose, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = true,
    )) {
        Box(Modifier.fillMaxSize().background(colors.surface2)
            .semantics { testTagsAsResourceId = BuildConfig.DEBUG }
            .windowInsetsPadding(WindowInsets.safeDrawing)) {
            LandscapeChartLayout {
                Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(40.dp).testTag("chart_close").clip(CircleShape).background(colors.surface3)
                            .clickable(role = Role.Button, onClickLabel = closeLabel, onClick = onClose)
                            .semantics { contentDescription = closeLabel }, contentAlignment = Alignment.Center) {
                            Text("×", style = NetKitType.value, color = colors.text)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(metric?.title ?: stringResource(R.string.chart_no_metrics), style = NetKitType.value, color = colors.text)
                            metric?.subtitle?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = NetKitType.valueSmall, color = colors.muted)
                            }
                        }
                        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            metrics.forEach { item ->
                                ChartPill(item.title, item.key == metric?.key, "chart_metric_${item.key}") {
                                    scrubFraction = null
                                    onSelect(item.key)
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(60, 300, 900).forEach { seconds ->
                            ChartPill(stringResource(R.string.chart_window_minutes, seconds / 60), windowSeconds == seconds,
                                "chart_window_$seconds") {
                                windowSeconds = seconds
                                scrubFroze = false
                                commit(ChartViewport(samplesForWindow(seconds, sampleIntervalMs, capacity)))
                                scrubFraction = null
                            }
                        }
                        ChartZoomButton("−", zoomOutLabel, shown.zoom > 1f) {
                            commit(held().zoomBy(1f / 1.5f, total))
                        }
                        Text(stringResource(R.string.chart_zoom_value, zoomFormat.format(shown.zoom)),
                            style = NetKitType.valueSmall, color = colors.text)
                        ChartZoomButton("+", zoomInLabel, shown.zoom < 10f) {
                            commit(held().zoomBy(1.5f, total))
                        }
                        // One-finger alternative to the two-finger pan: a quarter of the window per step.
                        val step = (shown.visibleSamples / 4f).coerceAtLeast(1f)
                        ChartZoomButton("‹", olderLabel, shown.panSamples < (total - shown.visibleSamples).coerceAtLeast(0), "chart_pan_older") {
                            commit(held().panBy(step, total))
                        }
                        ChartZoomButton("›", newerLabel, shown.panSamples > 0f, "chart_pan_newer") {
                            commit(held().panBy(-step, total))
                        }
                    }
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(Modifier.weight(1f).fillMaxSize(), shape = RoundedCornerShape(16.dp), color = colors.surface) {
                            // Both detectors live INSIDE the rotated layer. Compose maps pointers back to
                            // local plot coordinates through the layer's inverse matrix, so pinch, pan and
                            // scrub use the plot's own x axis in either rotation; no manual transform here.
                            // Initial pass observes contacts without consuming the transform detector's events.
                            val contacts = remember { intArrayOf(0) }
                            TimeSeriesChart(
                                lines = metric?.lines.orEmpty(), capacity = capacity, sampleIntervalMs = sampleIntervalMs,
                                range = metric?.range, ticks = metric?.ticks, unitLabel = metric?.unitLabel.orEmpty(),
                                description = stringResource(R.string.chart_description, metric?.title.orEmpty()),
                                events = events, viewport = shown, scrubFraction = scrubFraction, large = true,
                                modifier = Modifier.fillMaxSize().testTag("chart_plot")
                                    .pointerInput(leftInset, rightInset, metric?.key) {
                                        fun fraction(x: Float): Float = plotFraction(x, size.width.toFloat(), leftInset, rightInset)
                                        try {
                                            awaitEachGesture {
                                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                                contacts[0] = 1
                                                // Hold a live window while reading values, so the sample under the finger stays put.
                                                if (anchor == null && newestSlotTime(currentSlotTimes) != null) {
                                                    scrubFroze = true
                                                    anchor = newestSlotTime(currentSlotTimes)
                                                }
                                                scrubFraction = fraction(down.position.x)
                                                var transformed = false
                                                do {
                                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                                    contacts[0] = event.changes.count { it.pressed }
                                                    if (contacts[0] > 1) transformed = true
                                                    scrubFraction = if (!transformed && contacts[0] == 1)
                                                        fraction(event.changes.first { it.pressed }.position.x) else null
                                                } while (event.changes.any { it.pressed })
                                                if (scrubFroze) { scrubFroze = false; anchor = null }
                                            }
                                        } finally {
                                            contacts[0] = 0
                                            scrubFraction = null
                                            if (scrubFroze) { scrubFroze = false; anchor = null }
                                        }
                                    }
                                    .pointerInput(leftInset, rightInset, metric?.key) {
                                        detectTransformGestures { _, pan, zoom, _ ->
                                            if (contacts[0] >= 2) {
                                                scrubFraction = null
                                                val plot = plotWidth(size.width.toFloat(), leftInset, rightInset)
                                                val next = held().zoomBy(zoom, currentTotal)
                                                commit(next.panBy(pan.x / plot * (next.visibleSamples - 1), currentTotal))
                                            }
                                        }
                                    },
                            )
                        }
                        // Stats and events first: the column scrolls, and the hint is the least important part.
                        Column(Modifier.width(212.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            metric?.lines?.forEach { line ->
                                val stats = windowStats(line.values, visible)
                                Surface(shape = RoundedCornerShape(12.dp), color = colors.surface) {
                                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            SeriesDash(line.color, line.dashed)
                                            Text(line.label, Modifier.weight(1f), style = NetKitType.tag, color = colors.text)
                                            if (metric.unitLabel.isNotBlank()) Text(metric.unitLabel, style = NetKitType.valueSmall, color = colors.muted)
                                        }
                                        MinAvgMax(listOf(R.string.chart_min to stats?.min, R.string.chart_avg to stats?.avg,
                                            R.string.chart_max to stats?.max).map { (label, value) ->
                                            stringResource(label) to (value?.let(numberFormat::format) ?: noValue)
                                        })
                                    }
                                }
                            }
                            Text(stringResource(R.string.chart_events), style = NetKitType.valueSmall, color = colors.dim)
                            val windowEvents = events.filter { it.index in visible }.sortedByDescending { it.index }
                            if (windowEvents.isEmpty()) Text(stringResource(R.string.chart_no_events),
                                style = NetKitType.valueSmall, color = colors.muted)
                            windowEvents.forEach { event ->
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                                    Box(Modifier.padding(top = 5.dp).size(6.dp).clip(CircleShape).background(event.kind.color(colors)))
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(event.kind.labelResource()), style = NetKitType.valueSmall, color = colors.text)
                                        event.detail?.takeIf { it.isNotBlank() }?.let {
                                            Text(it, style = NetKitType.valueSmall, color = colors.muted)
                                        }
                                    }
                                    Text(relativeClock(elapsedSeconds(total - 1 - event.index, sampleIntervalMs)),
                                        style = NetKitType.valueSmall, color = colors.dim)
                                }
                            }
                            Surface(shape = RoundedCornerShape(12.dp), color = colors.surface) {
                                Text(stringResource(R.string.chart_hint), Modifier.fillMaxWidth().padding(12.dp),
                                    style = NetKitType.valueSmall, color = colors.muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Value text of the min / average / max cells (prototype `.mma b`: 13.5, bold, tabular). */
private val StatValue = NetKitType.value.copy(fontSize = 14.sp, lineHeight = 18.sp)

/**
 * Min / average / max side by side, each label above its value (prototype `.mma`). Columns share
 * the width equally; a value never gets less than its width, so long numbers wrap a cell to the
 * next line instead of being cut ([statTileLines]).
 */
@Composable
private fun MinAvgMax(cells: List<Pair<String, String>>) {
    val colors = NetKitTheme.colors
    Layout(
        content = {
            cells.forEach { (label, value) ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = NetKitType.valueSmall, color = colors.muted, maxLines = 1, softWrap = false)
                    Text(value, style = StatValue, color = colors.text, maxLines = 1, softWrap = false)
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val gap = 4.dp.roundToPx()
        val needs = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else needs.sum() + gap * (needs.size - 1).coerceAtLeast(0)
        val lines = statTileLines(needs, List(needs.size) { 1f }, width, gap)
        var y = 0
        val placed = lines.flatMap { line ->
            var x = 0
            val row = line.map { (index, w) -> Triple(measurables[index].measure(Constraints(minWidth = w, maxWidth = w)), x, y).also { x += w + gap } }
            y += row.maxOf { it.first.height } + gap
            row
        }
        layout(width, (y - gap).coerceAtLeast(0)) { placed.forEach { (p, px, py) -> p.place(px, py) } }
    }
}

/** Series key of the side cards: a 14 × 3 dash in the series colour, dashed like its line. */
@Composable
private fun SeriesDash(color: Color, dashed: Boolean) {
    Canvas(Modifier.size(width = 14.dp, height = 3.dp)) {
        drawLine(color, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), size.height,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null)
    }
}

/**
 * Rotation of the full-screen chart. On a portrait window the landscape content is turned
 * +90° (clockwise): its top edge lies along the screen's right edge, so it reads upright when
 * the phone is turned counter-clockwise, top of the phone to the left (`Surface.ROTATION_90`,
 * the usual landscape of video players). A landscape window is not rotated.
 */
internal fun fullscreenRotationDegrees(width: Int, height: Int): Float = if (height > width) PORTRAIT_ROTATION_DEGREES else 0f

internal const val PORTRAIT_ROTATION_DEGREES = 90f

/** Time of the newest timed slot, or null without slot times. */
internal fun newestSlotTime(slotTimes: List<Long?>?): Long? = slotTimes?.lastOrNull { it != null }

/**
 * Slots added after the slot at time [anchor]: how far a held window must pan to keep showing
 * the same samples. An anchor that left the history (or is unknown) counts the whole timeline,
 * so the pan clamps to the oldest end.
 */
internal fun slotsSince(slotTimes: List<Long?>?, anchor: Long): Int {
    if (slotTimes.isNullOrEmpty()) return 0
    for (index in slotTimes.indices.reversed()) {
        val time = slotTimes[index] ?: continue
        if (time == anchor) return slotTimes.lastIndex - index
        if (time < anchor) return slotTimes.lastIndex - index // anchor slot was replaced; nearest older one
    }
    return slotTimes.size
}

/** Plot width between the value axis ([leftInset], at most 40 % of the chart) and [rightInset]. */
internal fun plotWidth(width: Float, leftInset: Float, rightInset: Float): Float =
    (width - rightInset - leftInset.coerceAtMost(width * 0.4f)).coerceAtLeast(1f)

/** Scrub position as a fraction of the plot for an x in the chart's own (unrotated) coordinates. */
internal fun plotFraction(x: Float, width: Float, leftInset: Float, rightInset: Float): Float =
    ((x - leftInset.coerceAtMost(width * 0.4f)) / plotWidth(width, leftInset, rightInset)).coerceIn(0f, 1f)

/** Swap constraints BEFORE measuring, then rotate the measured child around its centre. */
@Composable
private fun LandscapeChartLayout(content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val rotation = fullscreenRotationDegrees(width, height)
        val child = measurables.single().measure(if (rotation != 0f) Constraints.fixed(height, width) else Constraints.fixed(width, height))
        layout(width, height) {
            child.placeWithLayer((width - child.width) / 2, (height - child.height) / 2) {
                rotationZ = rotation
            }
        }
    }
}

@Composable
private fun ChartPill(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val colors = NetKitTheme.colors
    Surface(shape = CircleShape, color = if (selected) colors.surface3 else colors.surface,
        border = BorderStroke(1.dp, if (selected) colors.accent else colors.borderSoft)) {
        Box(Modifier.testTag(tag).clip(CircleShape).selectable(selected, role = Role.Tab, onClick = onClick)
            .heightIn(min = 36.dp).padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text(label, style = NetKitType.valueSmall, color = if (selected) colors.accent else colors.muted)
        }
    }
}

@Composable
private fun ChartZoomButton(symbol: String, label: String, enabled: Boolean, tag: String? = null, onClick: () -> Unit) {
    val colors = NetKitTheme.colors
    Box(Modifier.size(36.dp).then(tag?.let { Modifier.testTag(it) } ?: Modifier).clip(CircleShape).background(colors.surface3)
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Text(symbol, style = NetKitType.value, color = if (enabled) colors.text else colors.dim)
    }
}
