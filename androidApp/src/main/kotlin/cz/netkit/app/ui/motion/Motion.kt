package cz.netkit.app.ui.motion

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.resolveAsTypeface
import androidx.compose.ui.unit.isSpecified
import kotlin.math.roundToInt
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.ui.layout.Layout
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/** Easing curves of docs/design.md §7. */
object NetKitEasing {
    /** cubic(0.2, 0.8, 0.2, 1): segment indicator, content entry, gauge arc. */
    val Base: Easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
    /** Ease-out cubic: numbers settling on a new value. */
    val OutCubic: Easing = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)
}

/** Finite motion tokens (docs/design.md §7). Infinite ones are [PULSE_MS] and [SPIN_MS]. */
enum class MotionToken(val durationMillis: Int, val easing: Easing) {
    /** Colour, press, tag switch. */
    FAST(180, FastOutSlowInEasing),
    /** Sliding segment indicator, content entry (fade + 6 dp), tab change. */
    BASE(320, NetKitEasing.Base),
    /** Numbers settling ([AnimatedNumber]). */
    VALUE(600, NetKitEasing.OutCubic),
    /** Gauge and speed dial arcs. */
    GAUGE(800, NetKitEasing.Base),
}

const val PULSE_MS = 2000
const val SPIN_MS = 1000

/** True when the system animator scale is 0 ("Remove animations"): everything snaps, nothing loops. */
val LocalReduceMotion = compositionLocalOf { false }

/** Pure mapping of `Settings.Global.ANIMATOR_DURATION_SCALE`, separate for unit tests. */
fun reduceMotionFor(animatorDurationScale: Float): Boolean = animatorDurationScale == 0f

private fun readReduceMotion(context: Context): Boolean = reduceMotionFor(
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f),
)

/** Provides [LocalReduceMotion] and follows changes of the developer/accessibility setting live. */
@Composable
fun ProvideReduceMotion(content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val reduce = remember(context) { mutableStateOf(readReduceMotion(context)) }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduce.value = readReduceMotion(context) }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        reduce.value = readReduceMotion(context)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    CompositionLocalProvider(LocalReduceMotion provides reduce.value, content = content)
}

/** Tween for [motion], or a snap when motion is reduced. */
fun <T> netKitTween(motion: MotionToken, reduceMotion: Boolean): FiniteAnimationSpec<T> =
    if (reduceMotion) snap() else tween(motion.durationMillis, easing = motion.easing)

/** [netKitTween] bound to the current [LocalReduceMotion]. */
@Composable
fun <T> rememberNetKitTween(motion: MotionToken): FiniteAnimationSpec<T> {
    val reduce = LocalReduceMotion.current
    return remember(motion, reduce) { netKitTween(motion, reduce) }
}

/** Content entry: fade + 6 dp rise with the `base` token; plain appearance when reduced. */
@Composable
fun rememberNetKitEnter(): EnterTransition {
    val reduce = LocalReduceMotion.current
    val rise = with(LocalDensity.current) { 6.dp.roundToPx() }
    return remember(reduce, rise) {
        if (reduce) EnterTransition.None
        else fadeIn(netKitTween(MotionToken.BASE, false)) + slideInVertically(netKitTween(MotionToken.BASE, false)) { rise }
    }
}

@Composable
fun rememberNetKitExit(): ExitTransition {
    val reduce = LocalReduceMotion.current
    return remember(reduce) { if (reduce) ExitTransition.None else fadeOut(netKitTween(MotionToken.FAST, false)) }
}

/** Whether looping animations may run: the screen is resumed and motion is not reduced. */
@Composable
fun rememberLoopingAllowed(): Boolean {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return !LocalReduceMotion.current && state.isAtLeast(Lifecycle.State.RESUMED)
}

/**
 * Slow breathing value 0..1..0 with the [PULSE_MS] period, e.g. the alpha of a live connection
 * line. Read it in the draw phase (`drawBehind`), so only drawing repeats. Stays at 1 when the
 * screen is paused or motion is reduced.
 */
@Composable
fun rememberBreathing(): State<Float> {
    if (!rememberLoopingAllowed()) return remember { mutableFloatStateOf(1f) }
    val transition = rememberInfiniteTransition(label = "breathing")
    return transition.animateFloat(
        initialValue = 0f, targetValue = 1f, label = "breathing-value",
        animationSpec = infiniteRepeatable(tween(PULSE_MS / 2, easing = FastOutSlowInEasing), RepeatMode.Reverse),
    )
}

/**
 * Expanding ring behind a live dot (2 s period, like the prototype `.live::before`). Put it
 * before any clip of the dot so the ring may grow outside it. Stops when paused or reduced.
 */
fun Modifier.pulse(color: Color, maxSpread: Dp = 7.dp): Modifier = composed {
    if (!rememberLoopingAllowed()) return@composed this
    val transition = rememberInfiniteTransition(label = "pulse")
    val progress by transition.animateFloat(
        initialValue = 0f, targetValue = 1f, label = "pulse-progress",
        animationSpec = infiniteRepeatable(keyframes {
            durationMillis = PULSE_MS
            0f at 0 using LinearEasing
            1f at (PULSE_MS * 0.7f).toInt()
            1f at PULSE_MS
        }, RepeatMode.Restart),
    )
    val spreadPx = with(LocalDensity.current) { maxSpread.toPx() }
    drawBehind {
        if (progress < 1f) {
            val base = size.minDimension / 2f
            drawCircle(color.copy(alpha = color.alpha * 0.55f * (1f - progress)), base + spreadPx * progress, Offset(size.width / 2f, size.height / 2f))
        }
    }
}

// --- Numbers ------------------------------------------------------------------------------------

const val MINUS_SIGN = '−'

/** Sign and digits of [value] rounded to [decimals]; a value that rounds to zero is never negative. */
data class SignedNumber(val negative: Boolean, val digits: String) {
    override fun toString() = if (negative) "$MINUS_SIGN$digits" else digits
}

fun numberFormat(locale: Locale, decimals: Int, grouping: Boolean): NumberFormat =
    NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = decimals.coerceAtLeast(0)
        maximumFractionDigits = decimals.coerceAtLeast(0)
        isGroupingUsed = grouping
        roundingMode = RoundingMode.HALF_UP
    }

/**
 * Sign and digits of [value] in [format]. Whether the value rounds to zero is decided on the
 * number (exact binary value, the format's fraction digits and rounding mode, as `DecimalFormat`
 * rounds), not on the formatted characters, so locales with non-ASCII digits keep their minus.
 */
fun formatSigned(value: Double, format: NumberFormat): SignedNumber {
    val digits = format.format(abs(value))
    val roundsToZero = value.isFinite() &&
        BigDecimal(abs(value)).setScale(format.maximumFractionDigits.coerceIn(0, 340), format.roundingMode).signum() == 0
    return SignedNumber(negative = value < 0 && !roundsToZero, digits = digits)
}

/**
 * [value] animated with the `value` token. The first non-null value and every value while
 * motion is reduced snap; `null` stays `null` (no measurement).
 */
@Composable
fun animatedNumberAsState(value: Double?): State<Double?> {
    val reduce = LocalReduceMotion.current
    val animatable = remember { Animatable((value ?: 0.0).toFloat()) }
    val primed = remember { mutableStateOf(value != null) }
    val latest = rememberUpdatedState(value)
    LaunchedEffect(value, reduce) {
        if (value == null) { primed.value = false; return@LaunchedEffect }
        val target = value.toFloat()
        if (reduce || !primed.value) { animatable.snapTo(target); primed.value = true }
        else animatable.animateTo(target, netKitTween(MotionToken.VALUE, false))
    }
    return remember {
        derivedStateOf {
            val v = latest.value
            when {
                v == null -> null
                // First value after "no measurement": show it at once, the effect snaps next frame.
                !primed.value -> v
                // Settled: keep full Double precision instead of the Float animation value.
                !animatable.isRunning && animatable.value == v.toFloat() -> v
                else -> animatable.value.toDouble()
            }
        }
    }
}

/**
 * A number that settles on [value] (token `value`, 600 ms ease-out) and is formatted with the
 * current locale. With [signHanging] a negative value keeps its digits centred and hangs the
 * minus to the left (weight 500, 72 % alpha), as in the prototype `.minus`. Accessibility gets
 * the target value, never an intermediate frame.
 */
@Composable
fun AnimatedNumber(
    value: Double?, decimals: Int, style: TextStyle, color: Color, modifier: Modifier = Modifier,
    signHanging: Boolean = false, placeholder: String = "–", grouping: Boolean = false,
) {
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale, decimals, grouping) { numberFormat(locale, decimals, grouping) }
    val shown by animatedNumberAsState(value)
    val target = value?.let { formatSigned(it, format).toString() } ?: placeholder
    val current = shown?.let { formatSigned(it, format) }
    val semantics = Modifier.clearAndSetSemantics { contentDescription = target; text = AnnotatedString(target) }
    val ink = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    when {
        current == null -> Text(placeholder, modifier.then(semantics), style = style, color = ink, maxLines = 1, softWrap = false)
        signHanging -> HangingSignText(current, style, ink, modifier.then(semantics))
        else -> Text(current.toString(), modifier.then(semantics), style = style, color = ink, maxLines = 1, softWrap = false)
    }
}

/**
 * Digits measured and centred on their own; the minus (if any) is placed left of them, outside
 * the measured width, so the visual centre of a gauge stays on the digits. The gap is measured
 * between the glyph ink (not the advance boxes), so it stays [HANGING_MINUS_GAP_EM] wide whatever
 * the side bearings of the minus and the first digit are.
 */
@Composable
fun HangingSignText(number: SignedNumber, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val minusStyle = style.copy(fontWeight = FontWeight(500))
    val ink = rememberGlyphInk(style, minusStyle)
    Layout(
        content = {
            Text(number.digits, style = style, color = color, maxLines = 1, softWrap = false)
            if (number.negative) Text(
                MINUS_SIGN.toString(), style = minusStyle,
                color = color.copy(alpha = color.alpha * 0.72f), maxLines = 1, softWrap = false,
            )
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val free = Constraints(maxHeight = constraints.maxHeight)
        val digits = measurables[0].measure(free)
        val minus = measurables.getOrNull(1)?.measure(free)
        val width = digits.width.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = digits.height.coerceIn(constraints.minHeight, constraints.maxHeight)
        val minusX = minus?.let { ink?.minusOffset(number.digits) ?: -(it.width + 1.dp.toPx()) }
        layout(width, height) {
            val x = (width - digits.width) / 2
            digits.place(x, 0)
            if (minus != null && minusX != null) minus.place(x + minusX.roundToInt(), 0)
        }
    }
}

/** Visual gap between the ink of the hanging minus and of the first digit, in em. */
const val HANGING_MINUS_GAP_EM = 0.04f

/**
 * Left edge of the minus relative to the digits' origin, so the ink gap is [gapPx]:
 * `digitsInkLeft − gap − minusInkRight` (all in px, from each text's own origin).
 */
internal fun hangingMinusOffset(digitsInkLeft: Float, minusInkRight: Float, gapPx: Float): Float =
    digitsInkLeft - gapPx - minusInkRight

/** Ink bounds of the hanging-minus texts, measured with the same typeface, size and features. */
private class GlyphInk(private val digitsPaint: Paint, minusPaint: Paint, private val gapPx: Float) {
    private val bounds = Rect()
    private val minusInkRight: Float = minusPaint.run { getTextBounds(MINUS_SIGN.toString(), 0, 1, bounds); bounds.right.toFloat() }

    fun minusOffset(digits: String): Float? {
        if (digits.isEmpty()) return null
        digitsPaint.getTextBounds(digits, 0, digits.length, bounds)
        if (bounds.isEmpty) return null
        return hangingMinusOffset(bounds.left.toFloat(), minusInkRight, gapPx)
    }
}

@Composable
private fun rememberGlyphInk(style: TextStyle, minusStyle: TextStyle): GlyphInk? {
    val resolver = LocalFontFamilyResolver.current
    val density = LocalDensity.current
    val digitsFace by resolver.resolveAsTypeface(style.fontFamily, style.fontWeight ?: FontWeight.Normal)
    val minusFace by resolver.resolveAsTypeface(minusStyle.fontFamily, minusStyle.fontWeight ?: FontWeight.Normal)
    return remember(style, digitsFace, minusFace, density) {
        if (!style.fontSize.isSpecified) return@remember null
        val sizePx = with(density) { style.fontSize.toPx() }
        fun paint(face: Typeface) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face
            textSize = sizePx
            style.fontFeatureSettings?.let { fontFeatureSettings = it }
            val spacing = style.letterSpacing
            letterSpacing = when {
                spacing.isEm -> spacing.value
                spacing.isSp && sizePx > 0f -> with(density) { spacing.toPx() } / sizePx
                else -> 0f
            }
        }
        GlyphInk(paint(digitsFace), paint(minusFace), HANGING_MINUS_GAP_EM * sizePx)
    }
}
