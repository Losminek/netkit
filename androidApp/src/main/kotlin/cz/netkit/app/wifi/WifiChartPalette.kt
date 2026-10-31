package cz.netkit.app.wifi

import androidx.compose.ui.graphics.toArgb
import cz.netkit.app.ui.theme.DarkNetKitColors
import cz.netkit.app.ui.theme.LightNetKitColors
import cz.netkit.app.ui.theme.NetKitColors
import kotlin.math.pow

/**
 * Opaque sRGB colours of the Wi-Fi charts, taken from the design tokens (docs/design.md §2):
 * the plot sits directly on the card ([background] = `surface`), ticks use `dim`, and the six
 * series follow the prototype order accent, warn, violet, info, ok, err. Every series is ≥ 3:1
 * against the card in both themes (WifiChartPaletteTest).
 */
internal data class WifiChartPalette(val background: Int, val axis: Int, val series: List<Int>)

internal fun wifiChartPalette(colors: NetKitColors): WifiChartPalette = WifiChartPalette(
    background = colors.surface.toArgb(), axis = colors.dim.toArgb(),
    series = listOf(colors.accent, colors.warn, colors.violet, colors.info, colors.ok, colors.err).map { it.toArgb() },
)

internal fun wifiChartPalette(dark: Boolean): WifiChartPalette = wifiChartPalette(if (dark) DarkNetKitColors else LightNetKitColors)

/** W3C sRGB relative luminance and contrast, evaluated without rounding at the threshold. */
internal fun chartContrast(first: Int, second: Int): Double {
    fun luminance(color: Int): Double {
        fun component(shift: Int): Double {
            val value = ((color ushr shift) and 255) / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * component(16) + 0.7152 * component(8) + 0.0722 * component(0)
    }
    val a = luminance(first)
    val b = luminance(second)
    return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
}
