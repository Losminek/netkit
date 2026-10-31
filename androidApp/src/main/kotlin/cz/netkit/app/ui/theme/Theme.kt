package cz.netkit.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import cz.netkit.app.R
import cz.netkit.app.ui.motion.ProvideReduceMotion

/**
 * NetKit M3 palette "Čistý UniFi" (docs/design.md §2): flat surface layers instead of borders,
 * teal accent, semantic colours kept apart from the accent. `*Soft` variants are translucent
 * fills behind tags, tonal buttons and banners.
 *
 * Keep the literals in the `name = Color(0xAARRGGBB)` form inside the two palette constructors:
 * `tools/check_contrast.py` parses them from this file to verify WCAG ratios.
 */
@Immutable
data class NetKitColors(
    val background: Color, val surface: Color, val surface2: Color, val surface3: Color,
    /** Visible hairline for the few outlined controls and chart axes (cards have no border). */
    val border: Color,
    /** Same value as [divider]; kept for code written before M3. */
    val borderSoft: Color,
    /** Row separator and chart grid. */
    val divider: Color,
    val text: Color, val muted: Color, val dim: Color,
    val accent: Color, val accentInk: Color, val accentSoft: Color,
    val info: Color, val infoSoft: Color,
    /** Second chart series (upload), dashed next to [info]. */
    val violet: Color, val violetSoft: Color,
    val ok: Color, val okSoft: Color,
    val warn: Color, val warnSoft: Color,
    val err: Color, val errSoft: Color,
    val navBackground: Color,
    /** The terminal stays dark in both themes; these never follow the light palette. */
    val terminalBackground: Color, val terminalText: Color,
    val isDark: Boolean,
)

val DarkNetKitColors = NetKitColors(
    background = Color(0xFF0C131A), surface = Color(0xFF141E28), surface2 = Color(0xFF1A2633), surface3 = Color(0xFF22303E),
    border = Color(0xFF2B3B4B), borderSoft = Color(0x0DFFFFFF), divider = Color(0x0DFFFFFF),
    text = Color(0xFFE8F1F5), muted = Color(0xFF93A6B3), dim = Color(0xFF5F7380),
    accent = Color(0xFF2DD4BF), accentInk = Color(0xFF04201C), accentSoft = Color(0x1F2DD4BF),
    info = Color(0xFF4C9DFF), infoSoft = Color(0x244C9DFF),
    violet = Color(0xFFB69CFF), violetSoft = Color(0x24B69CFF),
    ok = Color(0xFF3DDC97), okSoft = Color(0x243DDC97),
    warn = Color(0xFFF5B544), warnSoft = Color(0x26F5B544),
    err = Color(0xFFF2706B), errSoft = Color(0x26F2706B),
    navBackground = Color(0xFF101922),
    terminalBackground = Color(0xFF05090C), terminalText = Color(0xFFB8F5E9),
    isDark = true,
)

val LightNetKitColors = NetKitColors(
    background = Color(0xFFEEF3F6), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFF1F5F8), surface3 = Color(0xFFE3EAEF),
    border = Color(0xFFD3DDE5), borderSoft = Color(0x0F0C2D41), divider = Color(0x0F0C2D41),
    text = Color(0xFF0B1A24), muted = Color(0xFF4E6372), dim = Color(0xFF7C8E9A),
    accent = Color(0xFF0A7166), accentInk = Color(0xFFFFFFFF), accentSoft = Color(0x1A0A7166),
    info = Color(0xFF1F6EBF), infoSoft = Color(0x1A1F6EBF),
    violet = Color(0xFF6E4FD0), violetSoft = Color(0x1A6E4FD0),
    ok = Color(0xFF117C57), okSoft = Color(0x1A117C57),
    warn = Color(0xFF975F00), warnSoft = Color(0x1A975F00),
    err = Color(0xFFC04039), errSoft = Color(0x1AC04039),
    navBackground = Color(0xFFFFFFFF),
    terminalBackground = Color(0xFF05090C), terminalText = Color(0xFFB8F5E9),
    isDark = false,
)

private val LocalNetKitColors = staticCompositionLocalOf { DarkNetKitColors }

@OptIn(ExperimentalTextApi::class)
private fun variable(resource: Int, weight: Int) = Font(
    resource, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/**
 * Manrope and JetBrains Mono, OFL-1.1 (licences in assets/licenses/manrope-OFL.txt and
 * jetbrains-mono-OFL.txt), variable weight axis. Sources and SHA-256 in docs/zavislosti.md.
 */
object NetKitFonts {
    val sans = FontFamily(listOf(400, 500, 600, 700, 800).map { variable(R.font.manrope, it) })
    val mono = FontFamily(listOf(400, 500, 600).map { variable(R.font.jetbrains_mono, it) })
}

private const val TNUM = "tnum"

private fun style(
    size: Double, line: Double, weight: Int, tracking: TextUnit = 0.em,
    family: FontFamily = NetKitFonts.sans, numeric: Boolean = false,
) = TextStyle(
    fontFamily = family, fontSize = size.sp, lineHeight = line.sp, fontWeight = FontWeight(weight),
    letterSpacing = tracking, fontFeatureSettings = if (numeric) TNUM else null,
)

/** Material roles on Manrope, sized to the M3 scale so stock components blend in. */
private val NetKitTypography = Typography(
    displaySmall = style(38.0, 40.0, 700, (-0.03).em, numeric = true),
    headlineLarge = style(30.0, 34.0, 700, (-0.02).em),
    headlineMedium = style(26.0, 30.0, 700, (-0.02).em),
    headlineSmall = style(22.0, 28.0, 700, (-0.015).em),
    titleLarge = style(20.0, 26.0, 700, (-0.015).em),
    titleMedium = style(16.0, 22.0, 700),
    titleSmall = style(15.0, 20.0, 700),
    bodyLarge = style(16.0, 24.0, 500),
    bodyMedium = style(14.0, 20.0, 500),
    bodySmall = style(12.5, 17.0, 500),
    labelLarge = style(14.5, 20.0, 700),
    labelMedium = style(12.5, 16.0, 600),
    labelSmall = style(11.5, 14.0, 600),
)

/**
 * NetKit type roles (docs/design.md §3). All numeric roles use tabular figures; monospace is
 * only for machine values ([address], [terminal]).
 */
object NetKitType {
    val screenTitle = style(26.0, 30.0, 700, (-0.02).em)
    val subtitle = style(13.0, 18.0, 500)
    val panelTitle = style(15.0, 20.0, 700, (-0.005).em)
    val body = style(14.0, 20.0, 500)
    /** Sentence-case caption above a value, drawn in `muted`. Never uppercase. */
    val label = style(12.5, 16.0, 500, numeric = true)
    val value = style(17.0, 22.0, 700, (-0.01).em, numeric = true)
    val metric = style(19.0, 24.0, 700, (-0.01).em, numeric = true)
    val gauge = style(38.0, 40.0, 700, (-0.03).em, numeric = true)
    val hero = style(46.0, 48.0, 700, (-0.035).em, numeric = true)
    val unit = style(11.5, 14.0, 600, numeric = true)
    val tag = style(12.0, 16.0, 600, numeric = true)
    val nav = style(11.5, 14.0, 700)
    /** IP, MAC, BSSID next to 14 sp body text (0.92 × body). Use [addressFor] inside other sizes. */
    val address = style(13.0, 18.0, 500, (-0.01).em, NetKitFonts.mono)
    val terminal = style(12.5, 20.0, 400, family = NetKitFonts.mono)

    /** Large stand-alone metric (pre-M3 name), now Manrope with tabular figures. */
    val metricLarge = style(28.0, 32.0, 700, (-0.02).em, numeric = true)
    /** Secondary numeric text (pre-M3 name): small Manrope with tabular figures. */
    val valueSmall = style(12.5, 16.0, 500, numeric = true)
    /** Pre-M3 name of [label]; sentence case, no letter spacing. */
    val eyebrow = label

    /** Monospace machine value at 0.92 × the surrounding [base] size, weight at most 500. */
    fun addressFor(base: TextStyle): TextStyle = base.copy(
        fontFamily = NetKitFonts.mono,
        fontSize = if (base.fontSize.isSpecified) base.fontSize * 0.92f else address.fontSize,
        letterSpacing = (-0.01).em,
        fontWeight = if ((base.fontWeight?.weight ?: 400) > 500) FontWeight(500) else base.fontWeight,
        fontFeatureSettings = null,
    )
}

/** Corner radii from docs/design.md §4. */
object NetKitShape {
    /** Card (Panel). */
    val card = RoundedCornerShape(16.dp)
    /** Nested element: tile, segment track, icon tile, picker card. */
    val nested = RoundedCornerShape(12.dp)
    val button = RoundedCornerShape(12.dp)
    val buttonSmall = RoundedCornerShape(10.dp)
    /** Sliding indicator inside the segment track. */
    val segmentThumb = RoundedCornerShape(9.dp)
    val tag = RoundedCornerShape(8.dp)
    val pill = RoundedCornerShape(percent = 50)
}

private val NetKitMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private fun NetKitColors.toMaterial() = if (isDark) darkColorScheme(
    primary = accent, onPrimary = accentInk, primaryContainer = accentSoft, onPrimaryContainer = accent,
    secondary = info, onSecondary = background, secondaryContainer = surface3, onSecondaryContainer = text,
    tertiary = violet, onTertiary = background, tertiaryContainer = violetSoft, onTertiaryContainer = violet,
    error = err, onError = background, errorContainer = errSoft, onErrorContainer = err,
    background = background, onBackground = text, surface = background, onSurface = text,
    surfaceVariant = surface2, onSurfaceVariant = muted, outline = border, outlineVariant = divider,
    surfaceContainerLowest = background, surfaceContainerLow = surface, surfaceContainer = surface,
    surfaceContainerHigh = surface2, surfaceContainerHighest = surface3, inverseSurface = text, inverseOnSurface = background,
    surfaceTint = Color.Transparent, scrim = Color(0xB805090C),
) else lightColorScheme(
    primary = accent, onPrimary = accentInk, primaryContainer = accentSoft, onPrimaryContainer = accent,
    secondary = info, onSecondary = surface, secondaryContainer = surface3, onSecondaryContainer = text,
    tertiary = violet, onTertiary = surface, tertiaryContainer = violetSoft, onTertiaryContainer = violet,
    error = err, onError = surface, errorContainer = errSoft, onErrorContainer = err,
    background = background, onBackground = text, surface = background, onSurface = text,
    surfaceVariant = surface2, onSurfaceVariant = muted, outline = border, outlineVariant = divider,
    surfaceContainerLowest = surface, surfaceContainerLow = surface, surfaceContainer = surface,
    surfaceContainerHigh = surface2, surfaceContainerHighest = surface3, inverseSurface = text, inverseOnSurface = surface,
    surfaceTint = Color.Transparent, scrim = Color(0x730B1A24),
)

object NetKitTheme {
    val colors: NetKitColors @Composable @ReadOnlyComposable get() = LocalNetKitColors.current
}

@Composable
fun NetKitTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkNetKitColors else LightNetKitColors
    CompositionLocalProvider(LocalNetKitColors provides colors) {
        ProvideReduceMotion {
            MaterialTheme(colorScheme = colors.toMaterial(), typography = NetKitTypography, shapes = NetKitMaterialShapes, content = content)
        }
    }
}
