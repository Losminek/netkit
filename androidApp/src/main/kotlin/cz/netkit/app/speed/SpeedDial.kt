package cz.netkit.app.speed

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.netkit.app.ui.motion.LocalReduceMotion
import cz.netkit.app.ui.theme.NetKitTheme
import cz.netkit.app.ui.theme.NetKitType
import cz.netkit.shared.speed.SPEED_DIAL_LABELS
import cz.netkit.shared.speed.speedDialFraction
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Analog scale only; place [SpeedReadout] below it to keep the needle area clear. */
@Composable
fun SpeedDial(
    mbps: Float,
    active: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    description: String,
) {
    val colors = NetKitTheme.colors
    // Same source as every other animation: follows the system setting live (Motion.kt).
    val animationsEnabled = !LocalReduceMotion.current
    val target = speedDialFraction(mbps.toDouble()).toFloat()
    val shouldAnimate = active && animationsEnabled && target > 0f
    val animatedFraction by animateFloatAsState(
        targetValue = target,
        animationSpec = if (shouldAnimate) tween(250, easing = LinearEasing) else snap(),
        label = "speedDialFraction",
    )
    val measurer = rememberTextMeasurer()
    val labelStyle = NetKitType.valueSmall.copy(fontSize = 11.5.sp, color = colors.muted)

    Canvas(
        modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(320f / 182f)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        // Read animation state during drawing; reset immediately even before the next animation frame.
        val fraction = if (shouldAnimate) animatedFraction else target
        val scale = size.width / 320f
        val pivot = Offset(size.width / 2f, 168f * scale)
        val radius = 138f * scale
        fun point(f: Float, r: Float): Offset {
            val angle = PI * (1.0 + f)
            return Offset(pivot.x + r * cos(angle).toFloat(), pivot.y + r * sin(angle).toFloat())
        }
        val arcOrigin = Offset(pivot.x - radius, pivot.y - radius)
        val arcSize = Size(radius * 2f, radius * 2f)
        val trackStroke = Stroke(5.dp.toPx(), cap = StrokeCap.Round)
        drawArc(colors.surface3, 180f, 180f, false, arcOrigin, arcSize, style = trackStroke)
        if (fraction > 0f) {
            drawArc(color, 180f, 180f * fraction, false, arcOrigin, arcSize, style = trackStroke)
        }
        for (tick in 0..40) {
            val tickFraction = tick / 40f
            val major = tick % 5 == 0
            val lit = fraction > 0f && tickFraction <= fraction
            drawLine(
                color = if (lit) color else colors.dim.copy(alpha = colors.dim.alpha * if (major) .75f else .45f),
                start = point(tickFraction, radius - 14f * scale),
                end = point(tickFraction, radius - (if (major) 30f else 22f) * scale),
                strokeWidth = (if (major) 2.dp else 1.2.dp).toPx(),
                cap = StrokeCap.Round,
            )
        }
        val labelRadius = radius - 46f * scale
        val labels = SPEED_DIAL_LABELS.map { value ->
            val labelFraction = speedDialFraction(value.toDouble()).toFloat()
            val label = measurer.measure(value.toString(), labelStyle)
            val position = point(labelFraction, labelRadius)
            drawText(label, topLeft = Offset(position.x - label.size.width / 2f, position.y - label.size.height / 2f))
            DialLabel(labelFraction, label.size.width / 2f, label.size.height / 2f)
        }
        val needleColor = if (!active && target == 0f) colors.dim else color
        // The tip stays inside the label ring at every angle, so the needle never covers "0" or "1000".
        val needleRadius = speedNeedleRadius(labelRadius, labels, NeedleClearance.toPx())
        drawLine(needleColor, pivot, point(fraction, needleRadius), 2.2.dp.toPx(), StrokeCap.Round)
        drawCircle(colors.surface, 7.dp.toPx(), pivot)
        drawCircle(needleColor, 7.dp.toPx(), pivot, style = Stroke(2.2.dp.toPx()))
    }
}

/** Gap between the needle tip (incl. its round cap) and the nearest label box. */
private val NeedleClearance = 6.dp

/** Label box of the dial: [fraction] of the scale, half sizes in px; its centre lies on the label ring. */
internal data class DialLabel(val fraction: Float, val halfWidth: Float, val halfHeight: Float)

/**
 * Longest needle that cannot touch any label: every point of a label box is at least
 * `labelRadius − (|cos a|·halfWidth + |sin a|·halfHeight)` from the pivot (a = label angle),
 * and never less than `labelRadius − halfHeight` is used. The tip ends [clearance] inside that.
 */
internal fun speedNeedleRadius(labelRadius: Float, labels: List<DialLabel>, clearance: Float): Float {
    val reach = labels.maxOfOrNull { label ->
        val angle = PI * (1.0 + label.fraction)
        val radial = abs(cos(angle)).toFloat() * label.halfWidth + abs(sin(angle)).toFloat() * label.halfHeight
        maxOf(radial, label.halfHeight)
    } ?: 0f
    return (labelRadius - reach - clearance).coerceAtLeast(0f)
}

/** Localized numeric value, unit and phase, separate from the analog instrument. */
@Composable
fun SpeedReadout(
    mbps: Float?,
    unit: String,
    phaseLabel: String,
    phaseColor: Color,
    modifier: Modifier = Modifier,
) {
    val colors = NetKitTheme.colors
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val value = remember(mbps, locale) {
        if (mbps == null || !mbps.isFinite()) "–" else {
            val digits = if (mbps < 100f) 1 else 0
            NumberFormat.getNumberInstance(locale).apply {
                minimumFractionDigits = digits
                maximumFractionDigits = digits
                isGroupingUsed = false
            }.format(mbps.toDouble())
        }
    }
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            value,
            style = NetKitType.metricLarge.copy(fontSize = 46.sp, lineHeight = 50.sp, fontFeatureSettings = "tnum"),
            color = colors.text,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Text(unit, style = NetKitType.valueSmall, color = colors.muted, textAlign = TextAlign.Center)
        Row(
            Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(phaseColor, CircleShape))
            Text(phaseLabel, style = NetKitType.valueSmall, color = colors.muted, textAlign = TextAlign.Center)
        }
    }
}
