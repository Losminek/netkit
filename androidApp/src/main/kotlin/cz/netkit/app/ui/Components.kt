package cz.netkit.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.netkit.app.ui.motion.AnimatedNumber
import cz.netkit.app.ui.motion.HangingSignText
import cz.netkit.app.ui.motion.LocalReduceMotion
import cz.netkit.app.ui.motion.MINUS_SIGN
import cz.netkit.app.ui.motion.MotionToken
import cz.netkit.app.ui.motion.SignedNumber
import cz.netkit.app.ui.motion.animatedNumberAsState
import cz.netkit.app.ui.motion.formatSigned
import cz.netkit.app.ui.motion.netKitTween
import cz.netkit.app.ui.motion.numberFormat
import cz.netkit.app.ui.motion.pulse
import cz.netkit.app.ui.motion.rememberNetKitTween
import cz.netkit.app.ui.theme.NetKitColors
import cz.netkit.app.ui.theme.NetKitShape
import cz.netkit.app.ui.theme.NetKitTheme
import cz.netkit.app.ui.theme.NetKitType
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.text.NumberFormat
import kotlin.math.max
import kotlin.math.roundToInt

enum class StatusKind { INFO, WARNING, ERROR }

/** Spacing grid of docs/design.md §4 (multiples of 4). */
object Spacing {
    val tiny = 4.dp; val small = 8.dp; val medium = 12.dp; val normal = 16.dp; val large = 24.dp
    /** Inner padding of a card. */
    val card = 20.dp
    /** Gap between blocks inside a card. */
    val cardGap = 14.dp
}

/** Semantic tone of tags, metrics and banners; ACCENT marks the selected/primary state, not a status. */
enum class Tone { NEUTRAL, ACCENT, INFO, OK, WARN, ERR }

data class ToneColors(val foreground: Color, val fill: Color)
fun NetKitColors.tone(tone: Tone): ToneColors = when (tone) {
    Tone.NEUTRAL -> ToneColors(muted, surface2)
    Tone.ACCENT -> ToneColors(accent, accentSoft)
    Tone.INFO -> ToneColors(info, infoSoft)
    Tone.OK -> ToneColors(ok, okSoft)
    Tone.WARN -> ToneColors(warn, warnSoft)
    Tone.ERR -> ToneColors(err, errSoft)
}
private fun StatusKind.tone() = when (this) { StatusKind.INFO -> Tone.INFO; StatusKind.WARNING -> Tone.WARN; StatusKind.ERROR -> Tone.ERR }

/** Navy shadow ink of the light theme (prototype `rgba(12,45,65,.06)`); Android scales its alpha. */
private val LightShadowInk = Color(0xFF0C2D41)

// --- Header and containers ---------------------------------------------------------------------

/**
 * Screen title row (min. 52 dp): title and subtitle on the left, at most one action on the right
 * (a small primary button or a 40 × 40 icon button on `surface2`). When the longest word of the
 * title and the action do not fit side by side, the action moves below the title, so the title
 * never breaks inside a word.
 */
@Composable fun ScreenHeader(title: String, subtitle: String? = null, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val colors = NetKitTheme.colors
    AdaptiveHeader(title, titleGap = 2.dp, subtitle = subtitle?.let { { Text(it, style = NetKitType.subtitle, color = colors.muted) } },
        trailing = trailing)
}

/**
 * Layout shared by [ScreenHeader] and screen-specific headers. A hyphenated compound
 * ("Wake-on-LAN") counts as one word: the title column is never narrower than its widest word
 * while the action stays beside it; otherwise the action row is placed below, aligned to the end.
 */
@Composable internal fun AdaptiveHeader(
    title: String, titleGap: Dp, subtitle: (@Composable () -> Unit)?, trailing: (@Composable RowScope.() -> Unit)?,
) {
    val colors = NetKitTheme.colors
    val measurer = rememberTextMeasurer()
    val style = NetKitType.screenTitle
    val words = remember(title) { headerWords(title) }
    Layout(
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(titleGap)) {
                Text(title, style = style, color = colors.text)
                subtitle?.invoke()
            }
            if (trailing != null) Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.medium), content = trailing)
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(top = Spacing.tiny),
    ) { measurables, constraints ->
        val gap = Spacing.medium.roundToPx()
        val action = measurables.getOrNull(1)?.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val longestWord = words.maxOfOrNull { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width } ?: 0
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth
            else measurables[0].maxIntrinsicWidth(Constraints.Infinity) + (action?.let { it.width + gap } ?: 0)
        val stacked = action != null && longestWord + gap + action.width > width
        val textWidth = if (action != null && !stacked) width - gap - action.width else width
        val text = measurables[0].measure(Constraints(maxWidth = textWidth.coerceAtLeast(0)))
        if (action == null || !stacked) {
            val height = max(text.height, action?.height ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(width, height) {
                text.place(0, (height - text.height) / 2)
                action?.let { it.place(width - it.width, (height - it.height) / 2) }
            }
        } else {
            val below = Spacing.small.roundToPx()
            val height = (text.height + below + action.height).coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(width, height) {
                text.place(0, 0)
                action.place(width - action.width, text.height + below)
            }
        }
    }
}

/** Words of a title split only at spaces; hyphens and non-breaking spaces keep a word together. */
internal fun headerWords(title: String): List<String> = title.split(' ', '\n', '\t').filter { it.isNotEmpty() }

/** Sentence-case caption above a value (`label` style). Kept under the pre-M3 name; no uppercase. */
@Composable fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = NetKitTheme.colors.muted) {
    Text(text, modifier, style = NetKitType.label, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/**
 * Flat card: `surface`, radius 16, padding 20, gap 14, no border. The light theme adds a 1 dp
 * navy shadow so white cards separate from the light background.
 */
@Composable fun Panel(modifier: Modifier = Modifier, padding: Dp = Spacing.card, onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    val colors = NetKitTheme.colors
    val shape = NetKitShape.card
    Surface(
        modifier = modifier.fillMaxWidth()
            .then(if (colors.isDark) Modifier else Modifier.shadow(1.dp, shape, clip = false, ambientColor = LightShadowInk, spotColor = LightShadowInk))
            .then(if (onClick != null) Modifier.clip(shape).clickable(onClick = onClick) else Modifier),
        shape = shape, color = colors.surface, contentColor = colors.text,
    ) { Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(Spacing.cardGap), content = content) }
}

/** Card with a `panelTitle` heading, an optional muted [meta] text and trailing content. */
@Composable fun SectionCard(title: String? = null, trailing: (@Composable RowScope.() -> Unit)? = null, meta: String? = null,
    content: @Composable ColumnScope.() -> Unit) {
    val colors = NetKitTheme.colors
    Panel {
        if (title != null || trailing != null || meta != null) Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Text(title.orEmpty(), Modifier.weight(1f), style = NetKitType.panelTitle, color = colors.text)
            meta?.let { Text(it, style = NetKitType.label, color = colors.muted, maxLines = 1) }
            trailing?.invoke(this)
        }
        content()
    }
}

/** One collapsed explanation per screen: dim info icon, 13 sp muted text, animated expansion. */
@Composable fun InfoNote(summary: String, detail: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val colors = NetKitTheme.colors
    val chevron by animateFloatAsState(if (expanded) 90f else 0f, rememberNetKitTween(MotionToken.FAST), label = "info-chevron")
    val text = NetKitType.body.copy(fontSize = 13.sp, lineHeight = 18.sp)
    Column(Modifier.fillMaxWidth().clip(NetKitShape.nested).clickable(role = Role.Button) { expanded = !expanded }
        .heightIn(min = 48.dp).padding(vertical = Spacing.small).animateContentSize(rememberNetKitTween(MotionToken.BASE)),
        verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Icon(NetKitIcons.Info, null, Modifier.padding(top = 1.dp).size(16.dp), tint = colors.dim)
            Text(summary, Modifier.weight(1f), style = text, color = colors.muted)
            Icon(NetKitIcons.ChevronRight, null, Modifier.size(16.dp).rotate(chevron), tint = colors.dim)
        }
        if (expanded) Text(detail, Modifier.padding(start = 24.dp), style = text, color = colors.muted)
    }
}

@Composable fun StatusBanner(text: String, kind: StatusKind = StatusKind.INFO) {
    val tone = NetKitTheme.colors.tone(kind.tone())
    Row(Modifier.fillMaxWidth().clip(NetKitShape.nested).background(tone.fill).padding(horizontal = 14.dp, vertical = Spacing.medium),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 6.dp).size(7.dp).clip(CircleShape).background(tone.foreground))
        Text(text, Modifier.weight(1f), style = NetKitType.body, color = NetKitTheme.colors.text)
    }
}

/** Empty list: icon 32 in a `surface2` circle, a short sentence and an optional primary [action] below. */
@Composable fun EmptyState(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null) {
    val colors = NetKitTheme.colors
    Column(modifier.fillMaxWidth().padding(vertical = Spacing.large, horizontal = Spacing.normal),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        icon?.let {
            Box(Modifier.size(64.dp).clip(CircleShape).background(colors.surface2), contentAlignment = Alignment.Center) {
                Icon(it, null, Modifier.size(32.dp), tint = colors.dim)
            }
        }
        Text(text, style = NetKitType.body, color = colors.muted, textAlign = TextAlign.Center)
        action?.let { Box(Modifier.padding(top = Spacing.tiny)) { it() } }
    }
}

// --- Values ------------------------------------------------------------------------------------

@Composable fun LabeledValue(label: String, value: String, monospace: Boolean = false, valueColor: Color = Color.Unspecified) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Eyebrow(label)
        Text(value, style = if (monospace) NetKitType.address else NetKitType.body.copy(fontWeight = FontWeight.SemiBold),
            color = if (valueColor == Color.Unspecified) NetKitTheme.colors.text else valueColor)
    }
}

/**
 * Status tag: radius 8, padding 4 × 9, `tag` style (sans), optional 6 dp dot. Colour changes
 * cross-fade with the `fast` token.
 */
@Composable fun Tag(text: String, tone: Tone = Tone.NEUTRAL, dot: Boolean = false, modifier: Modifier = Modifier) {
    val target = NetKitTheme.colors.tone(tone)
    val fg by animateColorAsState(target.foreground, rememberNetKitTween(MotionToken.FAST), label = "tag-fg")
    val fill by animateColorAsState(target.fill, rememberNetKitTween(MotionToken.FAST), label = "tag-fill")
    Row(modifier.clip(NetKitShape.tag).background(fill).padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (dot) Box(Modifier.size(6.dp).clip(CircleShape).background(fg))
        Text(text, style = NetKitType.tag, color = fg, maxLines = 1, softWrap = false)
    }
}

/** Pre-M3 name of [Tag]; same look. */
@Composable fun StatusChip(text: String, tone: Tone = Tone.NEUTRAL, dot: Boolean = false, modifier: Modifier = Modifier) =
    Tag(text, tone, dot, modifier)

/** Accent pill "živě · 1 s" with a pulsing dot (stops when paused or with animations off). */
@Composable fun LiveBadge(text: String, modifier: Modifier = Modifier) {
    val colors = NetKitTheme.colors
    Row(modifier.clip(NetKitShape.pill).background(colors.accentSoft).padding(start = 9.dp, end = 11.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(7.dp).pulse(colors.accent).clip(CircleShape).background(colors.accent))
        Text(text, style = NetKitType.label.copy(fontWeight = FontWeight.SemiBold), color = colors.accent, maxLines = 1, softWrap = false)
    }
}

/** Label + value (`value`, tabular) + optional muted unit. [filled] draws the `surface2` tile. */
@Composable fun MetricTile(label: String, value: String, unit: String? = null, tone: Tone = Tone.NEUTRAL, modifier: Modifier = Modifier,
    filled: Boolean = false) {
    val colors = NetKitTheme.colors
    Column(modifier.then(if (filled) Modifier.clip(NetKitShape.nested).background(colors.surface2).padding(horizontal = 12.dp, vertical = 10.dp) else Modifier),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Eyebrow(label)
        ValueWithUnit(value, unit, NetKitType.value, if (tone == Tone.NEUTRAL) colors.text else colors.tone(tone).foreground)
    }
}

/**
 * Value and unit as one text. The unit is glued to the end of the value by a narrow no-break
 * space and never broken inside ("Mb/s"); a long value (IPv6) may still wrap, taking the unit
 * with its last part. With [softWrap] false the whole text stays on one line.
 */
@Composable private fun ValueWithUnit(value: String, unit: String?, style: TextStyle, color: Color,
    modifier: Modifier = Modifier, unitSize: Float = 11.5f, softWrap: Boolean = true) {
    val muted = NetKitTheme.colors.muted
    Text(valueWithUnitText(value, unit, muted, unitSize), modifier, style = style, color = color, softWrap = softWrap,
        maxLines = if (softWrap) Int.MAX_VALUE else 1)
}

private fun valueWithUnitText(value: String, unit: String?, unitColor: Color, unitSize: Float) = buildAnnotatedString {
    append(value)
    unit?.let {
        append(NARROW_NO_BREAK_SPACE)
        withStyle(SpanStyle(fontSize = unitSize.sp, fontWeight = FontWeight.SemiBold, color = unitColor, letterSpacing = 0.sp)) {
            append(unbreakableUnit(it))
        }
    }
}

private const val NARROW_NO_BREAK_SPACE = '\u202F'
private const val NO_BREAK_SPACE = '\u00A0'
private const val WORD_JOINER = '\u2060'

/** [unit] without line-break opportunities: spaces become no-break spaces, a word joiner follows "/" and "-". */
internal fun unbreakableUnit(unit: String): String = buildString(unit.length + 2) {
    unit.forEachIndexed { index, c ->
        append(if (c == ' ') NO_BREAK_SPACE else c)
        if ((c == '/' || c == '-') && index < unit.lastIndex) append(WORD_JOINER)
    }
}

/** One key/value pair of [KeyValueRows]. */
@Immutable
data class KeyValue(val label: String, val value: String, val tone: Tone = Tone.NEUTRAL, val monospace: Boolean = false)

/**
 * Key/value row (docs/design.md §4): 9 dp above and below, `divider` line on top unless
 * [showDivider] is false (first row of a list). Key `muted`, value `text` 600 on the right,
 * `address` (mono) when [monospace]. Values wrap, never ellipsize; they take at most 62 %.
 */
@Composable fun KeyValueRow(label: String, value: String, valueTone: Tone = Tone.NEUTRAL, monospace: Boolean = false,
    showDivider: Boolean = true) {
    val colors = NetKitTheme.colors
    val divider = colors.divider
    Layout(
        content = {
            Text(label, style = NetKitType.body, color = colors.muted)
            Text(value, style = if (monospace) NetKitType.address else NetKitType.body.copy(fontWeight = FontWeight.SemiBold),
                color = if (valueTone == Tone.NEUTRAL) colors.text else colors.tone(valueTone).foreground, textAlign = TextAlign.End)
        },
        modifier = Modifier.fillMaxWidth()
            .then(if (showDivider) Modifier.drawBehind { drawLine(divider, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) } else Modifier)
            .padding(top = if (showDivider) 9.dp else 0.dp, bottom = 9.dp),
    ) { measurables, constraints ->
        val gap = Spacing.medium.roundToPx()
        // Unbounded (e.g. inside a horizontal scroll): size to the content instead of a share.
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth
            else measurables[0].maxIntrinsicWidth(Constraints.Infinity) + gap + measurables[1].maxIntrinsicWidth(Constraints.Infinity)
        val valueP = measurables[1].measure(Constraints(maxWidth = (width * 0.62f).roundToInt()))
        val labelP = measurables[0].measure(Constraints(maxWidth = (width - valueP.width - gap).coerceAtLeast(0)))
        // Align first baselines (prototype `align-items: baseline`).
        val lb = labelP[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: 0
        val vb = valueP[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: 0
        val base = max(lb, vb)
        val height = max(base - lb + labelP.height, base - vb + valueP.height).coerceAtLeast(constraints.minHeight)
        layout(width, height) {
            labelP.place(0, base - lb)
            valueP.place(width - valueP.width, base - vb)
        }
    }
}

/** A list of [KeyValueRow]s with the first divider left out. */
@Composable fun KeyValueRows(rows: List<KeyValue>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, row -> KeyValueRow(row.label, row.value, row.tone, row.monospace, showDivider = index > 0) }
    }
}

/**
 * Tappable list row: 40 × 40 icon tile (radius 12, `surface2`) | title 15 + muted subtitle |
 * trailing content and chevron. [showDivider] draws the `divider` line on top.
 */
@Composable fun ListRow(title: String, subtitle: String? = null, icon: ImageVector? = null, onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier, showDivider: Boolean = false, titleMaxLines: Int = 1, subtitleMaxLines: Int = 1,
    trailing: (@Composable RowScope.() -> Unit)? = null) {
    val colors = NetKitTheme.colors
    val divider = colors.divider
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp)
        .then(if (showDivider) Modifier.drawBehind { drawLine(divider, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) } else Modifier)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        icon?.let {
            Box(Modifier.size(40.dp).clip(NetKitShape.nested).background(colors.surface2), contentAlignment = Alignment.Center) {
                Icon(it, null, Modifier.size(20.dp), tint = colors.muted)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = NetKitType.panelTitle, color = colors.text, maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = NetKitType.label, color = colors.muted, maxLines = subtitleMaxLines, overflow = TextOverflow.Ellipsis) }
        }
        trailing?.invoke(this)
        if (onClick != null) Icon(NetKitIcons.ChevronRight, null, Modifier.size(18.dp), tint = colors.dim)
    }
}

// --- Controls ----------------------------------------------------------------------------------

/**
 * Segmented control: `surface2` track (radius 12, padding 4) with a sliding `surface` indicator
 * (radius 9) that animates to the measured bounds of the selected item with the `base` token.
 * Items are at least 38 dp high and share the width equally; [testTags] go on the items.
 */
@Composable fun SegmentedTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
    testTags: List<String>? = null) {
    val colors = NetKitTheme.colors
    val reduce = LocalReduceMotion.current
    // Item bounds in px relative to the item row, filled by onPlaced.
    val bounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val x = remember { Animatable(0f) }
    val w = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    val target = bounds[selected]
    LaunchedEffect(target, reduce) {
        val (tx, tw) = target ?: return@LaunchedEffect
        if (!placed || reduce) { x.snapTo(tx); w.snapTo(tw); placed = true }
        else {
            val spec = netKitTween<Float>(MotionToken.BASE, false)
            coroutineScope {
                launch { x.animateTo(tx, spec) }
                launch { w.animateTo(tw, spec) }
            }
        }
    }
    val thumbShape = NetKitShape.segmentThumb
    Box(modifier.fillMaxWidth().clip(NetKitShape.nested).background(colors.surface2).padding(4.dp)) {
        if (placed) Box(Modifier.matchParentSize()
            .layout { measurable, constraints ->
                val width = w.value.roundToInt().coerceAtLeast(0)
                val p = measurable.measure(Constraints.fixed(width, constraints.maxHeight))
                layout(constraints.maxWidth, constraints.maxHeight) { p.place(x.value.roundToInt(), 0) }
            }
            .then(if (colors.isDark) Modifier else Modifier.shadow(1.dp, thumbShape, clip = false, ambientColor = LightShadowInk, spotColor = LightShadowInk))
            .clip(thumbShape).background(colors.surface))
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEachIndexed { index, label ->
                val on = index == selected
                val ink by animateColorAsState(if (on) colors.text else colors.muted, rememberNetKitTween(MotionToken.FAST), label = "segment-ink")
                Box(Modifier.weight(1f).heightIn(min = 38.dp)
                    .onPlaced { val b = it.positionInParent().x to it.size.width.toFloat(); if (bounds[index] != b) bounds[index] = b }
                    .then(testTags?.getOrNull(index)?.let { Modifier.testTag(it) } ?: Modifier)
                    .clip(thumbShape)
                    // Before the first measurement the selected item paints its own fill, so there is no empty frame.
                    .then(if (on && !placed) Modifier.background(colors.surface) else Modifier)
                    .selectable(selected = on, role = Role.Tab) { onSelect(index) }, contentAlignment = Alignment.Center) {
                    Text(label, Modifier.padding(horizontal = 6.dp), style = NetKitType.body.copy(fontWeight = FontWeight.SemiBold),
                        color = ink, maxLines = 1, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** One card of [MetricPicker]. [valueText] overrides the animated [value] (e.g. a placeholder). */
@Immutable
data class MetricOption(
    val name: String, val value: Double?, val decimals: Int, val unit: String?, val color: Color,
    val valueText: String? = null, val testTag: String? = null,
)

/**
 * Metric picker (replaces an inner segmented control): cards on `surface2` with a series-colour
 * dash, name and live value. The selected card gets a 55 % border and a 9 % fill of its colour.
 */
@Composable fun MetricPicker(options: List<MetricOption>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = NetKitTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val decimals = options.map { it.decimals }
    val formats = remember(locale, decimals) { decimals.map { numberFormat(locale, it, grouping = false) } }
    val targets = options.mapIndexed { index, option ->
        option.valueText ?: option.value?.let { formatSigned(it, formats[index]).toString() } ?: "–"
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = Spacing.small
        val count = options.size.coerceAtLeast(1)
        val cardWidth = with(density) { ((maxWidth - gap * (count - 1)) / count).roundToPx() }
        // A card needs its wider line (dash + name, or value + unit) plus padding and border. The
        // value is also measured as a two-digit sample, so the layout does not flip with each sample.
        val compact = options.indices.any { index ->
            val option = options[index]
            val name = measurer.measure(option.name, NetKitType.tag, softWrap = false, maxLines = 1).size.width +
                with(density) { (PickerDash + PickerDashGap).roundToPx() }
            val sample = option.valueText ?: formatSigned(if ((option.value ?: 0.0) < 0) -88.0 else 88.0, formats[index]).toString()
            val value = listOf(targets[index], sample).maxOf {
                measurer.measure(valueWithUnitText(it, option.unit, colors.muted, 11f), NetKitType.value, softWrap = false, maxLines = 1).size.width
            }
            max(name, value) + with(density) { (PickerPadding * 2 + 2.dp).roundToPx() } > cardWidth
        }
        if (compact) Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.tiny)) {
            options.forEachIndexed { index, option ->
                MetricPickerItem(option, index == selected, targets[index], formats[index], compact = true, Modifier.fillMaxWidth()) { onSelect(index) }
            }
        } else Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            options.forEachIndexed { index, option ->
                MetricPickerItem(option, index == selected, targets[index], formats[index], compact = false, Modifier.weight(1f)) { onSelect(index) }
            }
        }
    }
}

private val PickerDash = 8.dp
private val PickerDashGap = 6.dp
private val PickerPadding = 11.dp

/**
 * One option of [MetricPicker]: a card (dash + name above the value) or, in [compact] mode, a
 * full-width row (dash + name left, value + unit right). Selection styling and semantics are the same.
 */
@Composable private fun MetricPickerItem(option: MetricOption, on: Boolean, target: String, format: NumberFormat,
    compact: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = NetKitTheme.colors
    val border by animateColorAsState(if (on) option.color.copy(alpha = .55f) else Color.Transparent,
        rememberNetKitTween(MotionToken.FAST), label = "pick-border")
    val fill by animateColorAsState(if (on) option.color.copy(alpha = .09f).compositeOver(colors.surface2) else colors.surface2,
        rememberNetKitTween(MotionToken.FAST), label = "pick-fill")
    val shown by animatedNumberAsState(option.value)
    val text = option.valueText ?: shown?.let { formatSigned(it, format).toString() } ?: "–"
    val container = modifier.heightIn(min = 48.dp)
        .then(option.testTag?.let { Modifier.testTag(it) } ?: Modifier)
        .clip(NetKitShape.nested).background(fill).border(BorderStroke(1.dp, border), NetKitShape.nested)
        .selectable(selected = on, role = Role.Tab, onClick = onClick)
        .clearAndSetSemantics { contentDescription = listOfNotNull(option.name, target, option.unit).joinToString(" ") }
    if (compact) Row(container.padding(horizontal = 12.dp, vertical = Spacing.small), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PickerDashGap)) {
        PickerDashMark(option.color)
        Text(option.name, Modifier.weight(1f).padding(end = Spacing.tiny), style = NetKitType.tag, color = colors.muted)
        ValueWithUnit(text, option.unit, NetKitType.value, colors.text, unitSize = 11f, softWrap = false)
    } else Column(container.padding(horizontal = PickerPadding, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PickerDashGap)) {
            PickerDashMark(option.color)
            Text(option.name, style = NetKitType.tag, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ValueWithUnit(text, option.unit, NetKitType.value, colors.text, unitSize = 11f, softWrap = false)
    }
}

@Composable private fun PickerDashMark(color: Color) {
    Box(Modifier.size(width = PickerDash, height = 3.dp).clip(CircleShape).background(color))
}

/** One tile of [StatTiles]; [weight] is the share of the row width (Signal: 1 / 1.45 / 1.45). */
@Immutable
data class StatTile(val label: String, val value: String, val unit: String? = null, val weight: Float = 1f,
    val monospace: Boolean = false, val tone: Tone = Tone.NEUTRAL)

/**
 * Value tiles on `surface2`, gap 8, widths by [StatTile.weight]. A value is never ellipsized:
 * when the row is too narrow for every tile's content, tiles wrap to further rows.
 */
@Composable fun StatTiles(tiles: List<StatTile>, modifier: Modifier = Modifier) {
    val colors = NetKitTheme.colors
    Layout(
        content = {
            tiles.forEach { tile ->
                Column(Modifier.clip(NetKitShape.nested).background(colors.surface2).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Eyebrow(tile.label)
                    ValueWithUnit(tile.value, tile.unit, if (tile.monospace) NetKitType.addressFor(NetKitType.value).copy(fontSize = 14.5.sp)
                        else NetKitType.value, if (tile.tone == Tone.NEUTRAL) colors.text else colors.tone(tile.tone).foreground)
                }
            }
        },
        modifier = modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val gap = Spacing.small.roundToPx()
        val needs = measurables.map { it.maxIntrinsicWidth(constraints.maxHeight) }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else needs.sum() + gap * (needs.size - 1).coerceAtLeast(0)
        val lines = statTileLines(needs, tiles.map { it.weight }, width, gap)
        val placed = mutableListOf<Triple<Placeable, Int, Int>>()
        var y = 0
        lines.forEach { line ->
            val height = line.maxOf { (index, w) -> measurables[index].minIntrinsicHeight(w) }
            var x = 0
            line.forEach { (index, w) ->
                placed += Triple(measurables[index].measure(Constraints.fixed(w, height)), x, y)
                x += w + gap
            }
            y += height + gap
        }
        layout(width, (y - gap).coerceAtLeast(0)) { placed.forEach { (p, px, py) -> p.place(px, py) } }
    }
}

/**
 * Line breaking of [StatTiles] (pure, unit-tested): greedy lines by required width; inside a line
 * the free space goes by weight, and a tile never gets less than it needs (unless alone and
 * wider than the row, then it gets the full row and its text wraps). Returns (index, width) lines.
 */
internal fun statTileLines(needs: List<Int>, weights: List<Float>, width: Int, gap: Int): List<List<Pair<Int, Int>>> {
    val lines = mutableListOf<MutableList<Int>>()
    var used = 0
    needs.indices.forEach { i ->
        val current = lines.lastOrNull()
        if (current != null && used + gap + needs[i] <= width) { current += i; used += gap + needs[i] }
        else { lines += mutableListOf(i); used = needs[i] }
    }
    return lines.map { line ->
        val free = width - gap * (line.size - 1)
        val weightSum = line.sumOf { weights[it].toDouble() }.toFloat().takeIf { it > 0f } ?: line.size.toFloat()
        // Start from weighted shares, lift tiles below their need and take the difference from the rest.
        val share = line.associateWith { (free * (weights[it].takeIf { w -> w > 0f } ?: 1f) / weightSum) }
        val short = line.filter { share.getValue(it) < needs[it] }
        val fixed = short.sumOf { needs[it] }
        val flex = line - short.toSet()
        val flexWeight = flex.sumOf { (weights[it].takeIf { w -> w > 0f } ?: 1f).toDouble() }.toFloat()
        val widths = line.associateWith { i ->
            when {
                line.size == 1 -> free
                i in short -> needs[i]
                else -> ((free - fixed) * (weights[i].takeIf { w -> w > 0f } ?: 1f) / flexWeight).toInt().coerceAtLeast(needs[i])
            }
        }.toMutableMap()
        // Give rounding leftovers to the last tile so the row ends flush.
        val sum = widths.values.sum()
        val last = line.last()
        widths[last] = (widths.getValue(last) + free - sum).coerceAtLeast(1)
        line.map { it to widths.getValue(it) }
    }
}

private enum class ButtonKind { PRIMARY, TONAL, NEUTRAL }

@Composable private fun NetKitButton(kind: ButtonKind, text: String, onClick: () -> Unit, modifier: Modifier, icon: ImageVector?,
    enabled: Boolean, small: Boolean) {
    val colors = NetKitTheme.colors
    val (container, content) = when (kind) {
        ButtonKind.PRIMARY -> colors.accent to colors.accentInk
        ButtonKind.TONAL -> colors.accentSoft to colors.accent
        ButtonKind.NEUTRAL -> colors.surface2 to colors.text
    }
    Button(
        onClick = onClick, modifier = modifier.heightIn(min = if (small) 36.dp else 44.dp), enabled = enabled,
        shape = if (small) NetKitShape.buttonSmall else NetKitShape.button,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content,
            disabledContainerColor = colors.surface2, disabledContentColor = colors.dim),
        elevation = null, contentPadding = PaddingValues(horizontal = if (small) 14.dp else 18.dp, vertical = 0.dp),
    ) {
        icon?.let { Icon(it, null, Modifier.size(18.dp)); Spacer(Modifier.width(Spacing.small)) }
        Text(text, style = if (small) MaterialTheme.typography.labelLarge.copy(fontSize = 13.5.sp, lineHeight = 18.sp)
            else MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/** Primary action (`accent` / `accentInk`), 44 dp; [small] = 36 dp, radius 10. */
@Composable fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null,
    enabled: Boolean = true, small: Boolean = false) = NetKitButton(ButtonKind.PRIMARY, text, onClick, modifier, icon, enabled, small)

/** Tonal action (`accentSoft` / `accent`). */
@Composable fun TonalButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null,
    enabled: Boolean = true, small: Boolean = false) = NetKitButton(ButtonKind.TONAL, text, onClick, modifier, icon, enabled, small)

/** Neutral action (`surface2` / `text`); replaces outlined buttons. */
@Composable fun NeutralButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null,
    enabled: Boolean = true, small: Boolean = false) = NetKitButton(ButtonKind.NEUTRAL, text, onClick, modifier, icon, enabled, small)

// --- Gauge -------------------------------------------------------------------------------------

/** Geometry of the prototype gauge (viewBox 148 × 128): centre (74, 70), radius 62, 240° from 150°. */
private const val GAUGE_BOX = 148f
private const val GAUGE_CX = 74f
private const val GAUGE_CY = 70f
private const val GAUGE_R = 62f
private const val GAUGE_ARC_BOTTOM = 110f
private const val GAUGE_START = 150f
private const val GAUGE_SWEEP = 240f

private fun signedOf(text: String): SignedNumber? {
    val negative = text.startsWith('-') || text.startsWith(MINUS_SIGN)
    return if (negative) SignedNumber(true, text.drop(1)) else null
}

/** dBm scales of [SignalGauge] (docs/design.md §5): Wi-Fi RSSI and mobile RSRP. */
object GaugeRange {
    /** Wi-Fi RSSI: −90 dBm is an empty arc, −30 dBm a full one (also the fixed chart range). */
    val WIFI: ClosedFloatingPointRange<Float> = -90f..-30f
    /** Mobile RSRP (LTE / 5G): −140 dBm empty, −44 dBm full (3GPP RSRP reporting range). */
    val CELLULAR: ClosedFloatingPointRange<Float> = -140f..-44f
}

/** Share of the arc for [dbm] on [range], clamped to 0..1; `null` without a value or with an empty range. */
internal fun gaugeFraction(dbm: Double?, range: ClosedFloatingPointRange<Float>): Float? {
    if (dbm == null || dbm.isNaN() || range.endInclusive <= range.start) return null
    return ((dbm.toFloat() - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
}

/**
 * RSSI gauge: 240° arc (radius 62 at the 148 dp width, stroke 10, round caps) on a `surface3`
 * track, value arc in the quality colour animated with the `gauge` token. Inside: the value in
 * `gauge` style with a hanging minus and the unit below it. The quality label sits BELOW the
 * arc, never inside it.
 *
 * [dbm] enables the animated number; without it [value] is shown as given. [unit] and
 * [qualityLabel] default to the two lines of the pre-M3 [caption] ("dBm\nDobrý").
 * With [range] (e.g. [GaugeRange.CELLULAR]) the arc is computed from [dbm] on that scale and
 * [fraction] is only the fallback; without it callers pass [fraction] as before.
 */
@Composable fun SignalGauge(fraction: Float?, value: String, caption: String, tone: Tone, modifier: Modifier = Modifier, size: Dp = 148.dp,
    dbm: Double? = null, qualityLabel: String? = null, qualityTone: Tone = tone, unit: String? = null,
    range: ClosedFloatingPointRange<Float>? = null) {
    val colors = NetKitTheme.colors
    @Suppress("NAME_SHADOWING")
    val fraction = range?.let { gaugeFraction(dbm, it) } ?: fraction
    val unitText = unit ?: caption.substringBefore('\n')
    val quality = qualityLabel ?: caption.substringAfter('\n', "").ifBlank { null }
    val sweep by animateFloatAsState((fraction ?: 0f).coerceIn(0f, 1f), rememberNetKitTween(MotionToken.GAUGE), label = "gauge-sweep")
    val arc by animateColorAsState(colors.tone(tone).foreground, rememberNetKitTween(MotionToken.BASE), label = "gauge-colour")
    val qualityInk = colors.tone(qualityTone).foreground
    val track = colors.surface3
    val description = listOfNotNull(value.replace('-', MINUS_SIGN), unitText.ifBlank { null }, quality).joinToString(" ")
    Column(modifier.width(size).clearAndSetSemantics { contentDescription = description }, horizontalAlignment = Alignment.CenterHorizontally) {
        Layout(
            content = {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 10.dp.toPx()
                    val scale = this.size.width / GAUGE_BOX
                    val r = GAUGE_R * scale
                    val topLeft = Offset(GAUGE_CX * scale - r, GAUGE_CY * scale - r)
                    val arcSize = Size(2 * r, 2 * r)
                    drawArc(track, GAUGE_START, GAUGE_SWEEP, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    if (fraction != null && sweep > 0f) drawArc(arc, GAUGE_START, GAUGE_SWEEP * sweep, false, topLeft, arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (dbm != null) AnimatedNumber(dbm, 0, NetKitType.gauge, colors.text, signHanging = true)
                    else {
                        val signed = signedOf(value)
                        if (signed != null) HangingSignText(signed, NetKitType.gauge, colors.text)
                        else Text(value, style = NetKitType.gauge, color = colors.text, maxLines = 1, softWrap = false)
                    }
                    if (unitText.isNotBlank()) Text(unitText, style = NetKitType.unit.copy(fontSize = 12.sp, lineHeight = 16.sp), color = colors.muted, maxLines = 1)
                }
            },
            modifier = Modifier.width(size).height(size * (GAUGE_ARC_BOTTOM / GAUGE_BOX)),
        ) { measurables, constraints ->
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            val canvas = measurables[0].measure(Constraints.fixed(w, h))
            val inner = measurables[1].measure(Constraints(maxWidth = w))
            // Centre the value block slightly above the arc centre (prototype: top 36 of 128).
            val cy = (GAUGE_CY - 5f) / GAUGE_ARC_BOTTOM * h
            layout(w, h) {
                canvas.place(0, 0)
                inner.place((w - inner.width) / 2, (cy - inner.height / 2f).roundToInt().coerceAtLeast(0))
            }
        }
        quality?.let { Text(it, style = NetKitType.panelTitle.copy(fontSize = 13.sp, lineHeight = 18.sp), color = qualityInk,
            textAlign = TextAlign.Center, maxLines = 2) }
    }
}

data class ChartSeries(val values: List<Float?>, val color: Color)

/**
 * Live line chart with area fill and a faint grid. Values are clamped to [range]; `null`
 * breaks the line (lost sample). Y ticks are labelled on the right like WiFiman's charts.
 * All series share the x step: [capacity] slots, newest sample at the right edge.
 */
@Composable fun LiveLineChart(
    series: List<ChartSeries>, range: ClosedFloatingPointRange<Float>, ticks: List<Float>, unit: String,
    capacity: Int, description: String, modifier: Modifier = Modifier, height: Dp = 180.dp,
) {
    val colors = NetKitTheme.colors
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    Column(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.weight(1f).height(height)) {
            fun y(v: Float) = size.height - ((v.coerceIn(range.start, range.endInclusive) - range.start) / span) * size.height
            ticks.forEach { t -> drawLine(colors.borderSoft, Offset(0f, y(t)), Offset(size.width, y(t)), 1.dp.toPx()) }
            for (i in 1..3) {
                val x = size.width * i / 4f
                drawLine(colors.borderSoft, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
            }
            val step = size.width / (capacity - 1).coerceAtLeast(1)
            series.forEach { s ->
                val offset = capacity - s.values.size
                var line = Path(); var area = Path(); var open = false; var startX = 0f; var lastX = 0f
                fun flush() {
                    if (!open) return
                    area.lineTo(lastX, size.height); area.lineTo(startX, size.height); area.close()
                    drawPath(area, Brush.verticalGradient(listOf(s.color.copy(alpha = .26f), s.color.copy(alpha = 0f))))
                    drawPath(line, s.color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                    line = Path(); area = Path(); open = false
                }
                s.values.forEachIndexed { i, v ->
                    val x = (offset + i) * step
                    if (v == null) { flush(); return@forEachIndexed }
                    if (!open) { line.moveTo(x, y(v)); area.moveTo(x, y(v)); startX = x; open = true }
                    else { line.lineTo(x, y(v)); area.lineTo(x, y(v)) }
                    lastX = x
                }
                flush()
                s.values.lastOrNull()?.let { v -> drawCircle(s.color, 3.5.dp.toPx(), Offset((capacity - 1) * step, y(v))) }
            }
        }
        Box(Modifier.width(34.dp).height(height)) {
            ticks.forEach { t ->
                val fraction = 1f - (t - range.start) / span
                Text(formatTick(t), Modifier.align(Alignment.TopEnd).padding(top = (height * fraction - 8.dp).coerceIn(0.dp, height - 16.dp)),
                    style = NetKitType.valueSmall, color = colors.dim)
            }
        }
    }
    // Unit sits under the tick column so it never collides with the lowest tick label.
    Text(unit, Modifier.align(Alignment.End), style = NetKitType.valueSmall, color = colors.dim)
    }
}
private fun formatTick(value: Float) = if (value == value.toInt().toFloat()) value.toInt().toString() else "%.1f".format(value)

/** Thin proportional bar (hop latency, utilization); the fill animates with the `base` token. */
@Composable fun ValueBar(fraction: Float, tone: Tone = Tone.ACCENT, modifier: Modifier = Modifier) {
    val colors = NetKitTheme.colors
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), rememberNetKitTween(MotionToken.BASE), label = "value-bar")
    Box(modifier.height(4.dp).clip(CircleShape).background(colors.surface3)) {
        Box(Modifier.fillMaxWidth(shown).height(4.dp).clip(CircleShape).background(colors.tone(tone).foreground))
    }
}
