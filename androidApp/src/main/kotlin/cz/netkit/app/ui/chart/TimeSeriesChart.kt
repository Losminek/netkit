package cz.netkit.app.ui.chart

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cz.netkit.app.R
import cz.netkit.app.ui.theme.NetKitColors
import cz.netkit.app.ui.theme.NetKitTheme
import cz.netkit.app.ui.theme.NetKitType
import cz.netkit.shared.chart.ChartEvent
import cz.netkit.shared.chart.ChartEventKind
import cz.netkit.shared.chart.ChartViewport
import cz.netkit.shared.chart.autoTicks
import cz.netkit.shared.chart.nearestIndex
import cz.netkit.shared.chart.niceMax
import cz.netkit.shared.chart.windowStats
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** All series and events use the same buffer indices. Missing samples must be represented by null. */
data class ChartLine(
    val label: String,
    val values: List<Float?>,
    val color: Color,
    val dashed: Boolean = false,
    val fill: Boolean = !dashed,
)

// Shared with the gesture surface: fractions are relative to the plot, excluding both axes.
internal val ChartRightInset = 10.dp
internal fun chartLeftInset(large: Boolean, fontScale: Float): Dp =
    (if (large) 56.dp else 48.dp) * fontScale.coerceAtLeast(1f)

internal fun ChartEventKind.color(colors: NetKitColors): Color = when (this) {
    ChartEventKind.ROAMING -> colors.info
    ChartEventKind.PACKET_LOSS -> colors.err
    ChartEventKind.CHANNEL_CHANGE -> colors.warn
    ChartEventKind.RAT_CHANGE -> colors.warn
    ChartEventKind.DATA_SIM_SWITCH -> colors.info
}

internal fun ChartEventKind.labelResource(): Int = when (this) {
    ChartEventKind.ROAMING -> R.string.chart_event_roaming
    ChartEventKind.PACKET_LOSS -> R.string.chart_event_packet_loss
    ChartEventKind.CHANNEL_CHANGE -> R.string.chart_event_channel_change
    ChartEventKind.RAT_CHANGE -> R.string.chart_event_rat_change
    ChartEventKind.DATA_SIM_SWITCH -> R.string.chart_event_data_sim
}

internal fun elapsedSeconds(samples: Int, intervalMs: Long): Long =
    (samples.coerceAtLeast(0).toDouble() * intervalMs.coerceAtLeast(1) / 1000.0).toLong()

/** Negative elapsed mm:ss, relative to the newest buffer sample, including while panned. */
internal fun relativeClock(seconds: Long): String =
    "−${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

/**
 * Text layouts of axis and event labels, kept across recompositions (every scrub move recomposes
 * the chart and rebuilds its draw cache). Bounded: cleared when it grows past [LIMIT] entries.
 */
private class LabelLayouts(private val measurer: TextMeasurer) {
    private data class Key(val text: String, val style: TextStyle, val maxWidth: Int, val maxLines: Int)
    private val cache = HashMap<Key, TextLayoutResult>()

    fun get(text: String, style: TextStyle, maxWidth: Int = Constraints.Infinity, maxLines: Int = Int.MAX_VALUE): TextLayoutResult {
        if (cache.size > LIMIT) cache.clear()
        return cache.getOrPut(Key(text, style, maxWidth, maxLines)) {
            measurer.measure(text, style, maxLines = maxLines, constraints = Constraints(maxWidth = maxWidth))
        }
    }

    private companion object { const val LIMIT = 128 }
}

@Composable
fun TimeSeriesChart(
    lines: List<ChartLine>, capacity: Int, sampleIntervalMs: Long,
    range: ClosedFloatingPointRange<Float>?, ticks: List<Float>?, unitLabel: String, description: String,
    events: List<ChartEvent> = emptyList(), viewport: ChartViewport = ChartViewport(capacity),
    scrubFraction: Float? = null, large: Boolean = false,
    modifier: Modifier = Modifier, height: Dp = 190.dp,
) {
    require(capacity > 0 && sampleIntervalMs > 0)
    val colors = NetKitTheme.colors
    val resources = LocalContext.current.resources
    val configuration = LocalConfiguration.current
    val fontScale = LocalDensity.current.fontScale
    val numberFormat = remember(configuration) {
        NumberFormat.getNumberInstance(configuration.locales[0]).apply {
            isGroupingUsed = false
            maximumFractionDigits = 1
        }
    }
    val measurer = rememberTextMeasurer()
    // Axis and event labels rarely change; a scrub move only changes the bubble, so reuse them.
    val labelLayouts = remember(measurer, LocalDensity.current) { LabelLayouts(measurer) }
    val textStyle = NetKitType.valueSmall
    val paths = remember(lines.size) { List(lines.size) { Path() to Path() } }
    val total = lines.maxOfOrNull { it.values.size } ?: 0
    val normalized = viewport.panBy(0f, total)
    val visible = normalized.visible(total)
    val slots = normalized.visibleSamples
    val end = if (visible.isEmpty()) 0 else visible.last
    val start = end - slots + 1 // Deliberately negative when the buffer hasn't filled the window yet.
    val selected = scrubFraction?.let { nearestIndex(it, start..end) }
    var peak = 0f
    if (range == null) lines.forEach { line ->
        peak = maxOf(peak, windowStats(line.values, visible)?.max ?: 0f)
    }
    val low = range?.start?.takeIf { it.isFinite() } ?: 0f
    val high = range?.endInclusive?.takeIf { it.isFinite() && it > low }
        ?: niceMax((peak.toDouble() * 1.15).coerceAtMost(Float.MAX_VALUE.toDouble()).toFloat())
            .takeIf { it.isFinite() && it > low } ?: (low + 1f)
    val axisTicks = ticks ?: autoTicks(high - low).map { it + low }
    val now = resources.getString(R.string.chart_now)
    val missing = resources.getString(R.string.chart_missing)
    val tooltip = selected?.let { index ->
        buildAnnotatedString {
            withStyle(SpanStyle(color = colors.muted)) {
                append(if (total > 0 && index == total - 1) now else relativeClock(elapsedSeconds(total - 1 - index, sampleIntervalMs)))
            }
            lines.forEach { line ->
                append('\n')
                withStyle(SpanStyle(color = line.color)) {
                    append(line.label)
                    append("  ")
                    append(line.values.getOrNull(index)?.takeIf { it.isFinite() }?.let(numberFormat::format) ?: missing)
                    if (unitLabel.isNotEmpty()) { append(' '); append(unitLabel) }
                }
            }
            val proximity = maxOf(1, slots / 150)
            events.forEach { event ->
                if (event.index in visible && abs(event.index.toLong() - index) <= proximity) {
                    append('\n')
                    withStyle(SpanStyle(color = event.kind.color(colors))) {
                        append(resources.getString(event.kind.labelResource()))
                        event.detail?.takeIf { it.isNotBlank() }?.let { append(": "); append(it) }
                    }
                }
            }
        }
    }

    Box(modifier.fillMaxWidth().height(height).clearAndSetSemantics { contentDescription = description }
        .drawWithCache {
            val left = chartLeftInset(large, fontScale).toPx().coerceAtMost(size.width * 0.4f)
            val right = (size.width - ChartRightInset.toPx()).coerceAtLeast(left + 1f)
            val top = 12.dp.toPx()
            val bottom = (size.height - 26.dp.toPx() * fontScale).coerceAtLeast(top + 1f)
            val width = right - left
            fun x(index: Int): Float = if (slots == 1) right else left + (index - start).toFloat() / (slots - 1) * width
            fun y(value: Float): Float = bottom - ((value.coerceIn(low, high) - low) / (high - low)) * (bottom - top)
            val tickLabels = axisTicks.filter { it.isFinite() && it in low..high }.map { tick ->
                y(tick) to labelLayouts.get(numberFormat.format(tick), textStyle.copy(color = colors.dim),
                    maxWidth = (left - 6.dp.toPx()).roundToInt().coerceAtLeast(1))
            }
            val durationSeconds = (slots - 1).toDouble() * sampleIntervalMs / 1000.0
            val stepSeconds = when {
                durationSeconds <= 60 -> 15L
                durationSeconds <= 300 -> 60L
                durationSeconds <= 900 -> 180L
                else -> ceil(durationSeconds / 4).toLong().coerceAtLeast(1)
            }
            val rightSeconds = elapsedSeconds(total.coerceAtLeast(1) - 1 - end, sampleIntervalMs)
            // Anchor to real elapsed time, not the right edge of a panned window.
            val timeAges = buildList {
                add(rightSeconds)
                var next = (rightSeconds / stepSeconds + 1) * stepSeconds
                val oldest = rightSeconds.toDouble() + durationSeconds
                while (next > 0 && next <= oldest && size < 6) {
                    add(next)
                    if (Long.MAX_VALUE - next < stepSeconds) break
                    next += stepSeconds
                }
            }
            val timeLabels = timeAges.map { seconds ->
                val label = when {
                    seconds == 0L -> now
                    seconds >= 60 && seconds % 60 == 0L -> resources.getString(R.string.chart_minutes_ago, seconds / 60)
                    seconds >= 60 -> relativeClock(seconds)
                    else -> resources.getString(R.string.chart_seconds_ago, seconds)
                }
                val xx = right - ((seconds - rightSeconds) / durationSeconds.coerceAtLeast(0.001)).toFloat() * width
                xx to labelLayouts.get(label, textStyle.copy(color = colors.dim))
            }.distinctBy { it.first }
            val eventLabels = events.filter { it.index in visible }.map { event ->
                event to labelLayouts.get(resources.getString(event.kind.labelResource()),
                    textStyle.copy(color = event.kind.color(colors)), maxLines = 1,
                    maxWidth = (width - 16.dp.toPx()).roundToInt().coerceAtLeast(1))
            }
            val strokes = lines.map { line ->
                Stroke((if (line.dashed) 1.8.dp else 2.2.dp).toPx(), cap = StrokeCap.Round,
                    pathEffect = if (line.dashed) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null)
            }
            val brushes = lines.map { line ->
                Brush.verticalGradient(listOf(line.color.copy(alpha = 0.25f), line.color.copy(alpha = 0f)), top, bottom)
            }
            val eventDash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
            lines.forEachIndexed { lineIndex, line ->
                val (strokePath, fillPath) = paths[lineIndex]
                strokePath.rewind()
                fillPath.rewind()
                var firstX: Float? = null
                var lastX = 0f
                fun closeFill() {
                    firstX?.let { first ->
                        fillPath.lineTo(lastX, bottom)
                        fillPath.lineTo(first, bottom)
                        fillPath.close()
                    }
                    firstX = null
                }
                for (index in visible) {
                    val value = line.values.getOrNull(index)?.takeIf { it.isFinite() }
                    if (value == null) { closeFill(); continue }
                    val xx = x(index)
                    val yy = y(value)
                    if (firstX == null) {
                        strokePath.moveTo(xx, yy)
                        fillPath.moveTo(xx, yy)
                        firstX = xx
                    } else {
                        strokePath.lineTo(xx, yy)
                        fillPath.lineTo(xx, yy)
                    }
                    lastX = xx
                }
                closeFill()
            }
            val bubblePadding = 8.dp.toPx()
            val bubble = tooltip?.let {
                measurer.measure(it, textStyle, constraints = Constraints(
                    maxWidth = (width - 2 * bubblePadding).roundToInt().coerceAtLeast(1),
                    maxHeight = (bottom - top - 2 * bubblePadding).roundToInt().coerceAtLeast(1)))
            }
            onDrawBehind {
                tickLabels.forEach { (yy, label) ->
                    drawLine(colors.borderSoft, Offset(left, yy), Offset(right, yy), 1.dp.toPx())
                    drawText(label, topLeft = Offset((left - label.size.width - 6.dp.toPx()).coerceAtLeast(0f), yy - label.size.height / 2f))
                }
                // Suppress colliding time labels on narrow plots / large font scales.
                var lastLabelLeft = Float.POSITIVE_INFINITY
                timeLabels.forEach { (xx, label) ->
                    val labelX = (xx - label.size.width / 2f).coerceIn(left, (right - label.size.width).coerceAtLeast(left))
                    if (labelX + label.size.width + 6.dp.toPx() <= lastLabelLeft) {
                        drawText(label, topLeft = Offset(labelX, bottom + 6.dp.toPx()))
                        lastLabelLeft = labelX
                    }
                }
                clipRect(left - 4.dp.toPx(), top - 4.dp.toPx(), right + 4.dp.toPx(), bottom + 4.dp.toPx()) {
                    eventLabels.forEach { (event, _) ->
                        drawLine(event.kind.color(colors), Offset(x(event.index), top), Offset(x(event.index), bottom),
                            1.dp.toPx(), pathEffect = eventDash)
                    }
                    lines.forEachIndexed { index, line ->
                        if (line.fill) drawPath(paths[index].second, brushes[index])
                        drawPath(paths[index].first, line.color, style = strokes[index])
                        if (normalized.panSamples == 0f && total > 0) {
                            line.values.getOrNull(total - 1)?.takeIf { it.isFinite() }?.let { value ->
                                drawCircle(line.color, 3.6.dp.toPx(), Offset(x(total - 1), y(value)))
                                drawCircle(colors.surface, 1.5.dp.toPx(), Offset(x(total - 1), y(value)))
                            }
                        }
                    }
                    selected?.let { index ->
                        val xx = x(index)
                        drawLine(colors.text.copy(alpha = 0.5f), Offset(xx, top), Offset(xx, bottom), 1.dp.toPx())
                        lines.forEach { line ->
                            line.values.getOrNull(index)?.takeIf { it.isFinite() }?.let { value ->
                                drawCircle(line.color, 4.dp.toPx(), Offset(xx, y(value)))
                            }
                        }
                    }
                }
                eventLabels.forEach { (event, label) ->
                    val xx = x(event.index)
                    val color = event.kind.color(colors)
                    drawCircle(color, 3.dp.toPx(), Offset(xx, top + 3.dp.toPx()))
                    if (large) {
                        val pillSize = Size(label.size.width + 12.dp.toPx(), label.size.height + 4.dp.toPx())
                        val pillX = (xx + 5.dp.toPx()).coerceIn(left, (right - pillSize.width).coerceAtLeast(left))
                        val corner = CornerRadius(pillSize.height / 2)
                        drawRoundRect(colors.surface, Offset(pillX, top + 9.dp.toPx()), pillSize, corner)
                        drawRoundRect(color, Offset(pillX, top + 9.dp.toPx()), pillSize, corner, style = Stroke(1.dp.toPx()))
                        drawText(label, topLeft = Offset(pillX + 6.dp.toPx(), top + 11.dp.toPx()))
                    }
                }
                if (bubble != null) {
                    val bubbleSize = Size((bubble.size.width + 2 * bubblePadding).coerceAtMost(width),
                        (bubble.size.height + 2 * bubblePadding).coerceAtMost(bottom - top))
                    val preferred = x(selected) + 12.dp.toPx()
                    val bubbleX = (if (preferred + bubbleSize.width <= right) preferred else x(selected) - 12.dp.toPx() - bubbleSize.width)
                        .coerceIn(left, (right - bubbleSize.width).coerceAtLeast(left))
                    val origin = Offset(bubbleX, top)
                    drawRoundRect(colors.surface2, origin, bubbleSize, CornerRadius(10.dp.toPx()))
                    drawRoundRect(colors.borderSoft, origin, bubbleSize, CornerRadius(10.dp.toPx()), style = Stroke(1.dp.toPx()))
                    clipRect(bubbleX, top, bubbleX + bubbleSize.width, top + bubbleSize.height) {
                        drawText(bubble, topLeft = origin + Offset(bubblePadding, bubblePadding))
                    }
                }
            }
        })
}
