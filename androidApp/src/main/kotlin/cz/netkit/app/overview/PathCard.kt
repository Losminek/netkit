package cz.netkit.app.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import cz.netkit.app.ui.Spacing
import cz.netkit.app.ui.Tag
import cz.netkit.app.ui.Tone
import cz.netkit.app.ui.motion.rememberBreathing
import cz.netkit.app.ui.theme.NetKitTheme
import cz.netkit.app.ui.theme.NetKitType

/** Icon tile of a node: 40 dp circle on `surface2`; the connecting line runs through its centre. */
private val NodeIcon = 40.dp
/** Gap between side-by-side node columns and between stacked node rows. */
private val NodeGap = Spacing.small
/** Font scale from which the nodes always stack (docs/design.md §4, large text). */
private const val STACK_FONT_SCALE = 1.3f

/** One text line under a node title: a machine value in mono ([mono]) or a dim sans hint. */
@Immutable
internal data class PathLine(val text: String, val mono: Boolean, val dim: Boolean = false)

/**
 * One node of the path card. [lines] are never ellipsized; [tag] is an optional state tag
 * ("za NAT operátora", "VPN"). [testTag] goes on the node container.
 */
@Immutable
internal data class PathNode(
    val icon: ImageVector,
    val title: String,
    val lines: List<PathLine>,
    val tag: Pair<String, Tone>? = null,
    val testTag: String,
)

/**
 * Path card body (docs/design.md §5 "Karta cesty"): provider → access point → this phone, as
 * icon tiles joined by a line. Side by side while every machine value fits its column on one
 * line; on a narrow display, with font scale ≥ 1.3 or with a long value (IPv6) the nodes stack
 * with the line running down the icons. Values wrap, never ellipsize.
 *
 * [live]: the line breathes softly ([rememberBreathing], off with reduced motion or when paused).
 * [firstLinkBroken]: the provider is not reached (lookup off, unavailable, no network); the first
 * segment is dashed and not animated.
 */
@Composable
internal fun PathNodes(nodes: List<PathNode>, live: Boolean, firstLinkBroken: Boolean, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val count = nodes.size.coerceAtLeast(1)
        val columnPx = with(density) { ((maxWidth - NodeGap * (count - 1)) / count).toPx() }
        val stacked = density.fontScale >= STACK_FONT_SCALE || nodes.any { node ->
            node.lines.any { line ->
                measurer.measure(line.text, lineStyle(line), maxLines = 1, softWrap = false).size.width > columnPx
            } || node.tag?.let { (text, _) -> measurer.measure(text, NetKitType.tag, softWrap = false).size.width +
                with(density) { 18.dp.toPx() } > columnPx } == true
        }
        if (stacked) StackedNodes(nodes, live, firstLinkBroken) else SideBySideNodes(nodes, live, firstLinkBroken)
    }
}

private fun lineStyle(line: PathLine): TextStyle =
    if (line.mono) NetKitType.addressFor(NetKitType.label) else NetKitType.label

@Composable
private fun SideBySideNodes(nodes: List<PathNode>, live: Boolean, firstLinkBroken: Boolean) {
    val colors = NetKitTheme.colors
    val breathing = rememberBreathing()
    Row(
        Modifier.fillMaxWidth().drawBehind {
            val n = nodes.size
            if (n < 2) return@drawBehind
            val gap = NodeGap.toPx()
            val column = (size.width - gap * (n - 1)) / n
            val y = NodeIcon.toPx() / 2
            for (i in 0 until n - 1) {
                val start = Offset(i * (column + gap) + column / 2, y)
                val end = Offset((i + 1) * (column + gap) + column / 2, y)
                pathSegment(start, end, colors.surface3, colors.accent, broken = i == 0 && firstLinkBroken,
                    live = live, breathing = breathing.value)
            }
        },
        horizontalArrangement = Arrangement.spacedBy(NodeGap),
    ) {
        nodes.forEach { node ->
            Column(Modifier.weight(1f).testTag(node.testTag).semantics(mergeDescendants = true) {},
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.tiny)) {
                NodeIconTile(node.icon)
                NodeTexts(node, centered = true)
            }
        }
    }
}

@Composable
private fun StackedNodes(nodes: List<PathNode>, live: Boolean, firstLinkBroken: Boolean) {
    val colors = NetKitTheme.colors
    val breathing = rememberBreathing()
    // Icon centres of the rows, written by the layout and read when drawing (no recomposition).
    val centres = remember { FloatArray(8) }
    val placed = remember { mutableFloatStateOf(0f) }
    Layout(
        content = {
            nodes.forEach { node ->
                Row(Modifier.fillMaxWidth().testTag(node.testTag).semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.spacedBy(Spacing.medium), verticalAlignment = Alignment.Top) {
                    NodeIconTile(node.icon)
                    Box(Modifier.weight(1f)) { NodeTexts(node, centered = false) }
                }
            }
        },
        modifier = Modifier.fillMaxWidth().drawBehind {
            // Reading the state ties this drawing to the layout pass that wrote the centres.
            if (placed.floatValue < 0f) return@drawBehind
            val n = nodes.size.coerceAtMost(centres.size)
            val x = NodeIcon.toPx() / 2
            for (i in 0 until n - 1) {
                pathSegment(Offset(x, centres[i]), Offset(x, centres[i + 1]), colors.surface3, colors.accent,
                    broken = i == 0 && firstLinkBroken, live = live, breathing = breathing.value)
            }
        },
    ) { measurables, constraints ->
        val gap = (NodeGap * 2).roundToPx()
        val width = constraints.maxWidth
        val placeables = measurables.map { it.measure(Constraints(maxWidth = width)) }
        var y = 0
        val tops = placeables.map { p -> y.also { y += p.height + gap } }
        val height = (y - gap).coerceAtLeast(0)
        val half = (NodeIcon / 2).toPx()
        tops.forEachIndexed { i, top -> if (i < centres.size) centres[i] = top + half }
        placed.floatValue = height.toFloat()
        layout(width, height) { placeables.forEachIndexed { i, p -> p.place(0, tops[i]) } }
    }
}

/** One segment of the line: `surface3` track, accent overlay breathing 35–75 % when [live]; dashed when [broken]. */
private fun DrawScope.pathSegment(start: Offset, end: Offset, track: Color, accent: Color, broken: Boolean, live: Boolean, breathing: Float) {
    val stroke = 2.dp.toPx()
    if (broken) {
        drawLine(track, start, end, stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
        return
    }
    drawLine(track, start, end, stroke)
    if (live) drawLine(accent.copy(alpha = 0.35f + 0.4f * breathing), start, end, stroke)
}

@Composable
private fun NodeIconTile(icon: ImageVector) {
    val colors = NetKitTheme.colors
    Box(Modifier.size(NodeIcon).clip(CircleShape).background(colors.surface2), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(20.dp), tint = colors.muted)
    }
}

@Composable
private fun NodeTexts(node: PathNode, centered: Boolean) {
    val colors = NetKitTheme.colors
    val align = if (centered) TextAlign.Center else TextAlign.Start
    Column(horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(node.title, style = NetKitType.body.copy(fontWeight = FontWeight.SemiBold), color = colors.text, textAlign = align)
        node.lines.forEach { line ->
            Text(line.text, style = lineStyle(line), color = if (line.dim) colors.dim else if (line.mono) colors.text else colors.muted,
                textAlign = align)
        }
        node.tag?.let { (text, tone) -> Tag(text, tone, dot = tone != Tone.NEUTRAL) }
    }
}
