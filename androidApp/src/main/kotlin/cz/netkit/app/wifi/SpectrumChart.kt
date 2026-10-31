package cz.netkit.app.wifi

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.netkit.app.R
import cz.netkit.app.ui.SectionCard
import cz.netkit.app.ui.Spacing
import cz.netkit.app.ui.Tone
import cz.netkit.app.ui.theme.NetKitColors
import cz.netkit.app.ui.theme.NetKitTheme
import cz.netkit.app.ui.theme.NetKitType
import cz.netkit.shared.wifi.BandSpectrum
import cz.netkit.shared.wifi.ChannelHealth
import cz.netkit.shared.wifi.ChannelHealthEntry
import cz.netkit.shared.wifi.SpectrumAxis
import cz.netkit.shared.wifi.SpectrumTrace
import cz.netkit.shared.wifi.WIFI_CHART_AP_LIMIT
import kotlin.math.floor

/** Visible dBm range of the spectrum chart; peaks outside are clamped to the edge. */
private const val SPECTRUM_FLOOR_DBM = -95
private const val SPECTRUM_CEILING_DBM = -30
private val SPECTRUM_DBM_TICKS = listOf(-50, -70, -90)

/**
 * Traces drawn with the six-colour palette: the connected AP first (so it always gets the
 * accent colour), then the strongest others up to [WIFI_CHART_AP_LIMIT]. Colour and marker
 * shape follow the position in this list; the AP list reuses the same colours.
 */
internal fun spectrumDrawnTraces(traces: List<SpectrumTrace>): List<SpectrumTrace> {
    val connected = traces.firstOrNull { it.accessPoint.isCurrent }
    return (listOfNotNull(connected) + traces.filterNot { it === connected }).take(WIFI_CHART_AP_LIMIT)
}

/** 20 MHz channels of the axis lying fully inside each shape (bonded span), e.g. 36–48 for 80 MHz. */
private fun spannedChannels(trace: SpectrumTrace, axis: SpectrumAxis): List<List<Int>> = trace.shapes.map { shape ->
    axis.ticks.filter { it.frequencyMhz - 10.0 >= shape.lowerMhz - 0.5 && it.frequencyMhz + 10.0 <= shape.upperMhz + 0.5 }
        .map { it.channel }
}

/** Channel summary for screen readers: primary channel, bonded span when wider than 20 MHz, width. */
@Composable private fun spectrumChannelText(trace: SpectrumTrace, axis: SpectrumAxis): String {
    val ap = trace.accessPoint
    val span = spannedChannels(trace, axis).filter { it.size > 1 }.joinToString(" + ") { "${it.first()}–${it.last()}" }
    val width = ap.widthMhz?.let {
        if (ap.is80Plus80) stringResource(R.string.wifi_width_80_plus_80) else stringResource(R.string.wifi_width_mhz, it)
    }
    return listOfNotNull(stringResource(R.string.wifi_spectrum_channel_short, trace.primaryChannel),
        span.ifBlank { null }, width).joinToString(" · ")
}

/**
 * Spectrum card of one band (prototype `vSpectrum`): AP trapezoids on a real-frequency axis
 * drawn directly on the card, band gaps in `surface2`, `divider` grid, `dim` labels, the
 * connected AP with a bolder outline and fill. Legend = marker swatch + SSID.
 * Visualisation of scan results, not an RF measurement (explained in the screen's InfoNote).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun SpectrumPanel(spectrum: BandSpectrum, bandName: String, drawn: List<SpectrumTrace>) {
    val colors = NetKitTheme.colors
    val palette = wifiChartPalette(colors)
    val axis = spectrum.axis
    val connectedIndex = drawn.indexOfFirst { it.accessPoint.isCurrent }
    val hidden = spectrum.traces.size - drawn.size
    val hiddenName = stringResource(R.string.wifi_hidden_network)
    val traceLines = drawn.mapIndexed { index, trace ->
        val line = stringResource(R.string.wifi_spectrum_trace_description, index + 1, trace.accessPoint.ssid ?: hiddenName,
            spectrumChannelText(trace, axis), trace.peakDbm)
        if (trace.accessPoint.isCurrent) stringResource(R.string.wifi_spectrum_trace_connected, line) else line
    }
    val description = (listOf(stringResource(R.string.wifi_spectrum_description, bandName, spectrum.traces.size, drawn.size,
        SPECTRUM_FLOOR_DBM, SPECTRUM_CEILING_DBM)) + traceLines).joinToString("\n")
    SectionCard(stringResource(R.string.wifi_spectrum_chart_title),
        meta = stringResource(R.string.wifi_spectrum_chart_meta, spectrum.traces.size)) {
        SpectrumCanvas(axis, drawn, connectedIndex, palette,
            Modifier.fillMaxWidth().height(200.dp).semantics { contentDescription = description })
        if (drawn.isEmpty()) Text(stringResource(R.string.wifi_no_band_data), style = NetKitType.label, color = colors.muted)
        else FlowRow(Modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            drawn.forEachIndexed { index, trace ->
                val connected = trace.accessPoint.isCurrent
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MarkerGlyph(index, Color(palette.series[index]))
                    val name = trace.accessPoint.ssid ?: hiddenName
                    Text(if (connected) stringResource(R.string.wifi_spectrum_trace_connected, name) else name,
                        style = if (connected) NetKitType.label.copy(fontWeight = FontWeight.SemiBold) else NetKitType.label,
                        color = if (connected) colors.text else colors.muted)
                }
            }
            if (hidden > 0) Text(stringResource(R.string.wifi_spectrum_more, hidden), style = NetKitType.label, color = colors.dim)
        }
    }
}

@Composable private fun MarkerGlyph(index: Int, color: Color) {
    Canvas(Modifier.size(12.dp)) { drawWifiMarker(index, color, Offset(size.width / 2, size.height / 2), 4.dp.toPx()) }
}

@Composable private fun SpectrumCanvas(
    axis: SpectrumAxis, traces: List<SpectrumTrace>, connectedIndex: Int, palette: WifiChartPalette, modifier: Modifier,
) {
    val colors = NetKitTheme.colors
    val measurer = rememberTextMeasurer()
    // docs/design.md §6: axis labels `dim` 11.
    val labelStyle = NetKitType.label.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.dim)
    val tickColor = Color(palette.axis)
    Canvas(modifier) {
        val yLabels = SPECTRUM_DBM_TICKS.associateWith { measurer.measure(it.toString(), labelStyle) }
        val sample = measurer.measure("000", labelStyle)
        val left = (yLabels.values.maxOf { it.size.width }).toFloat() + 8.dp.toPx()
        val top = 8.dp.toPx()
        val right = size.width
        val bottom = size.height - sample.size.height - 8.dp.toPx()
        val plotWidth = (right - left).coerceAtLeast(1f)
        val plotHeight = (bottom - top).coerceAtLeast(1f)
        fun x(mhz: Double) = left + (axis.fraction(mhz) * plotWidth).toFloat()
        fun y(dbm: Int) = top + (SPECTRUM_CEILING_DBM - dbm.coerceIn(SPECTRUM_FLOOR_DBM, SPECTRUM_CEILING_DBM)).toFloat() /
            (SPECTRUM_CEILING_DBM - SPECTRUM_FLOOR_DBM) * plotHeight

        // Frequency ranges without any listed channel keep their real width and are shaded.
        axis.gaps.forEach { gap ->
            drawRect(colors.surface2, Offset(x(gap.lowerMhz), top), Size(x(gap.upperMhz) - x(gap.lowerMhz), plotHeight))
        }
        SPECTRUM_DBM_TICKS.forEach { dbm ->
            val ty = y(dbm)
            drawLine(colors.divider, Offset(left, ty), Offset(right, ty), 1.dp.toPx())
            val label = yLabels.getValue(dbm)
            drawText(label, topLeft = Offset(left - 8.dp.toPx() - label.size.width, ty - label.size.height / 2f))
        }
        drawLine(colors.divider, Offset(left, bottom), Offset(right, bottom), 1.dp.toPx())

        clipRect(left, top, right, bottom) {
            // Weakest first so stronger traces stay on top; the connected AP is drawn last.
            traces.indices.sortedWith(compareBy<Int> { it == connectedIndex }.thenBy { traces[it].peakDbm }).forEach { index ->
                val trace = traces[index]
                val color = Color(palette.series[index])
                val connected = index == connectedIndex
                val peak = y(trace.peakDbm)
                val floor = bottom + 1.dp.toPx()
                trace.shapes.forEach { shape ->
                    fun Path.trapezoid() = apply {
                        moveTo(x(shape.lowerMhz), floor)
                        lineTo(x(shape.plateauLowerMhz), peak)
                        lineTo(x(shape.plateauUpperMhz), peak)
                        lineTo(x(shape.upperMhz), floor)
                    }
                    drawPath(Path().trapezoid().apply { close() }, color.copy(alpha = if (connected) .22f else .13f))
                    drawPath(Path().trapezoid(), color,
                        style = Stroke(if (connected) 2.6.dp.toPx() else 1.5.dp.toPx(), join = StrokeJoin.Round))
                    drawWifiMarker(index, color, Offset(x(shape.centerMhz), peak), if (connected) 4.dp.toPx() else 3.dp.toPx())
                }
            }
        }

        var lastLabelEnd = Float.NEGATIVE_INFINITY
        axis.ticks.forEach { tick ->
            val tx = x(tick.frequencyMhz)
            drawLine(tickColor, Offset(tx, bottom), Offset(tx, bottom + (if (tick.labelled) 4 else 2).dp.toPx()), 1.dp.toPx())
            if (!tick.labelled) return@forEach
            val label = measurer.measure(tick.channel.toString(), labelStyle)
            val lx = (tx - label.size.width / 2f).coerceIn(0f, size.width - label.size.width)
            if (lx < lastLabelEnd + 4.dp.toPx()) return@forEach
            drawText(label, topLeft = Offset(lx, bottom + 6.dp.toPx()))
            lastLabelEnd = lx + label.size.width
        }
    }
}

/** The shape accompanies each colour so identity never relies on hue alone. */
internal fun DrawScope.drawWifiMarker(index: Int, color: Color, center: Offset, radius: Float) {
    val left = center.x - radius
    val right = center.x + radius
    val top = center.y - radius
    val bottom = center.y + radius
    when (index) {
        0 -> drawCircle(color, radius, center)
        1 -> drawRect(color, Offset(left, top), Size(2 * radius, 2 * radius))
        2 -> drawPath(Path().apply { moveTo(center.x, top); lineTo(right, bottom); lineTo(left, bottom); close() }, color)
        3 -> drawPath(Path().apply { moveTo(center.x, top); lineTo(right, center.y); lineTo(center.x, bottom); lineTo(left, center.y); close() }, color)
        4 -> { drawLine(color, Offset(left, center.y), Offset(right, center.y), 2.5.dp.toPx())
            drawLine(color, Offset(center.x, top), Offset(center.x, bottom), 2.5.dp.toPx()) }
        else -> { drawLine(color, Offset(left, top), Offset(right, bottom), 2.5.dp.toPx())
            drawLine(color, Offset(left, bottom), Offset(right, top), 2.5.dp.toPx()) }
    }
}

// --- Channel health heat strip (docs/design.md §5) ----------------------------------------------

private val HEAT_CELL_HEIGHT = 44.dp
private val HEAT_CELL_GAP = 3.dp
private val HEAT_CELL_MIN_WIDTH = 8.dp
/** Room above the cells for the connected-channel dot and the selection outline. */
private val HEAT_TOP = 8.dp

/** Cell colour on the ok → warn → err scale; GOOD sits between ok and warn. */
internal fun heatColor(colors: NetKitColors, health: ChannelHealth): Color = when (health) {
    ChannelHealth.EXCELLENT -> colors.ok
    ChannelHealth.GOOD -> lerp(colors.ok, colors.warn, .45f)
    ChannelHealth.FAIR -> colors.warn
    ChannelHealth.POOR -> colors.err
}

internal fun healthTone(health: ChannelHealth): Tone = when (health) {
    ChannelHealth.EXCELLENT, ChannelHealth.GOOD -> Tone.OK
    ChannelHealth.FAIR -> Tone.WARN
    ChannelHealth.POOR -> Tone.ERR
}

@Composable internal fun healthLabel(health: ChannelHealth): String = stringResource(when (health) {
    ChannelHealth.EXCELLENT -> R.string.wifi_health_excellent
    ChannelHealth.GOOD -> R.string.wifi_health_good
    ChannelHealth.FAIR -> R.string.wifi_health_fair
    ChannelHealth.POOR -> R.string.wifi_health_poor
})

/** Channel entries in axis order with `null` for a band gap (> 20 MHz between neighbouring channels). */
internal fun heatCells(channels: List<ChannelHealthEntry>): List<ChannelHealthEntry?> = buildList {
    channels.forEachIndexed { index, entry ->
        if (index > 0 && entry.centerFrequencyMhz - channels[index - 1].centerFrequencyMhz > 20.5) add(null)
        add(entry)
    }
}

/**
 * Heat strip: one cell per 20 MHz channel (height 44, radius 6, gap 3) coloured by health,
 * band gaps as dashed outlines, a dot above the connected channel and a `text` outline around
 * the selected one. Tap or drag selects a channel; each cell is also a selectable node for
 * screen readers. Below it the [labelled] channel numbers. Scrolls horizontally with a fade
 * when the cells would be narrower than 8 dp (6 GHz).
 */
@Composable internal fun HeatStrip(cells: List<ChannelHealthEntry?>, labelled: Set<Int>, selectedChannel: Int?, onSelect: (Int) -> Unit) {
    val colors = NetKitTheme.colors
    val select by rememberUpdatedState(onSelect)
    val measurer = rememberTextMeasurer()
    val labelStyle = NetKitType.label.copy(fontSize = 10.5.sp, color = colors.dim)
    val selectLabel = stringResource(R.string.wifi_spectrum_health_details)
    val cellDescriptions = cells.map { entry ->
        entry?.let {
            val text = stringResource(R.string.wifi_health_channel, it.channel, healthLabel(it.health))
            if (it.connectedHere) stringResource(R.string.wifi_spectrum_trace_connected, text) else text
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val n = cells.size.coerceAtLeast(1)
        val needed = HEAT_CELL_MIN_WIDTH * n + HEAT_CELL_GAP * (n - 1)
        val scrolls = needed > maxWidth
        val stripWidth = if (scrolls) needed else maxWidth
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.then(if (scrolls) Modifier.horizontalScroll(scroll) else Modifier).width(stripWidth)) {
                Box(Modifier.fillMaxWidth().height(HEAT_TOP + HEAT_CELL_HEIGHT + 2.dp)
                    .pointerInput(cells) {
                        val gap = HEAT_CELL_GAP.toPx()
                        fun pick(x: Float) {
                            val cell = (size.width - gap * (cells.size - 1)) / cells.size
                            val index = floor(x / (cell + gap)).toInt().coerceIn(0, cells.lastIndex)
                            cells[index]?.let { select(it.channel) }
                        }
                        detectTapGestures { pick(it.x) }
                    }
                    .then(if (scrolls) Modifier else Modifier.pointerInput(cells) {
                        val gap = HEAT_CELL_GAP.toPx()
                        detectHorizontalDragGestures { change, _ ->
                            val cell = (size.width - gap * (cells.size - 1)) / cells.size
                            val index = floor(change.position.x / (cell + gap)).toInt().coerceIn(0, cells.lastIndex)
                            cells[index]?.let { select(it.channel) }
                        }
                    })) {
                    Canvas(Modifier.matchParentSize()) {
                        val gap = HEAT_CELL_GAP.toPx()
                        val cell = (size.width - gap * (cells.size - 1)) / cells.size
                        val top = HEAT_TOP.toPx()
                        val height = HEAT_CELL_HEIGHT.toPx()
                        val radius = CornerRadius(minOf(6.dp.toPx(), cell / 2))
                        val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
                        cells.forEachIndexed { index, entry ->
                            val x = index * (cell + gap)
                            if (entry == null) {
                                val inset = .5.dp.toPx()
                                drawRoundRect(colors.border, Offset(x + inset, top + inset), Size(cell - 2 * inset, height - 2 * inset),
                                    radius, style = Stroke(1.dp.toPx(), pathEffect = dash))
                            } else {
                                drawRoundRect(heatColor(colors, entry.health).copy(alpha = .9f), Offset(x, top), Size(cell, height), radius)
                                if (entry.connectedHere) drawCircle(colors.accent, 2.5.dp.toPx(), Offset(x + cell / 2, top - 5.dp.toPx()))
                            }
                        }
                        val chosen = cells.indexOfFirst { it != null && it.channel == selectedChannel }
                        if (chosen >= 0) {
                            val x = chosen * (cell + gap)
                            val out = 2.dp.toPx()
                            drawRoundRect(colors.text, Offset(x - out, top - out), Size(cell + 2 * out, height + 2 * out),
                                CornerRadius(radius.x + out), style = Stroke(2.dp.toPx()))
                        }
                    }
                    // Accessibility layer only; touches go to the strip's pointer handlers above.
                    Row(Modifier.matchParentSize().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(HEAT_CELL_GAP)) {
                        cells.forEachIndexed { index, entry ->
                            val description = cellDescriptions[index]
                            Spacer(Modifier.weight(1f).fillMaxHeight().then(
                                if (entry == null || description == null) Modifier.clearAndSetSemantics { }
                                else Modifier.semantics {
                                    contentDescription = description
                                    role = Role.Tab
                                    selected = entry.channel == selectedChannel
                                    onClick(selectLabel) { select(entry.channel); true }
                                }))
                        }
                    }
                }
                Canvas(Modifier.fillMaxWidth().height(16.dp).clearAndSetSemantics { }) {
                    val gap = HEAT_CELL_GAP.toPx()
                    val cell = (size.width - gap * (cells.size - 1)) / cells.size
                    var lastEnd = Float.NEGATIVE_INFINITY
                    cells.forEachIndexed { index, entry ->
                        if (entry == null || entry.channel !in labelled) return@forEachIndexed
                        val label = measurer.measure(entry.channel.toString(), labelStyle)
                        val center = index * (cell + gap) + cell / 2
                        val lx = (center - label.size.width / 2f).coerceIn(0f, (size.width - label.size.width).coerceAtLeast(0f))
                        if (lx < lastEnd + 2.dp.toPx()) return@forEachIndexed
                        drawText(label, topLeft = Offset(lx, 2.dp.toPx()))
                        lastEnd = lx + label.size.width
                    }
                }
            }
            if (scrolls) ScrollFade(scroll, colors.surface)
        }
    }
}

/** Scale "volný → rušený" under the heat strip. */
@Composable internal fun HeatScale() {
    val colors = NetKitTheme.colors
    Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Text(stringResource(R.string.wifi_health_scale_free), style = NetKitType.label, color = colors.muted)
        Box(Modifier.weight(1f).height(6.dp).background(Brush.horizontalGradient(listOf(colors.ok, colors.warn, colors.err)),
            RoundedCornerShape(3.dp)))
        Text(stringResource(R.string.wifi_health_scale_busy), style = NetKitType.label, color = colors.muted)
    }
}

/** Edge fades over a horizontally scrolling row, shown only where more content is hidden. */
@Composable internal fun BoxScope.ScrollFade(scroll: ScrollState, background: Color, width: Dp = 24.dp) {
    Box(Modifier.matchParentSize()) {
        if (scroll.canScrollBackward) Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(width)
            .background(Brush.horizontalGradient(listOf(background, background.copy(alpha = 0f)))))
        if (scroll.canScrollForward) Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(width)
            .background(Brush.horizontalGradient(listOf(background.copy(alpha = 0f), background))))
    }
}
