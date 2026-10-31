package cz.netkit.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Line icons drawn for NetKit on a 24-unit grid with a 1.8 stroke, so navigation and rows
 * share one visual weight. Colour comes from the caller's tint (Icon applies it).
 */
object NetKitIcons {
    private fun icon(name: String, vararg paths: String, filled: Set<Int> = emptySet()) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEachIndexed { index, data ->
                if (index in filled) addPath(addPathNodes(data), fill = SolidColor(Color.Black))
                else addPath(
                    addPathNodes(data), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val Signal = icon("signal", "M2 9a15 15 0 0 1 20 0", "M5.5 12.5a10 10 0 0 1 13 0", "M9 16a5 5 0 0 1 6 0",
        "M12 18.7a0.8 0.8 0 1 0 0 1.6a0.8 0.8 0 1 0 0-1.6z", filled = setOf(3))
    val Spectrum = icon("spectrum", "M3 20h18", "M4 20l3-11h3l3 11", "M11 20l3-7h3l3 7")
    val Devices = icon("devices", "M5 16a2 2 0 1 0 0 4a2 2 0 1 0 0-4z", "M12 4a2 2 0 1 0 0 4a2 2 0 1 0 0-4z",
        "M19 12a2 2 0 1 0 0 4a2 2 0 1 0 0-4z", "M6.5 16.5L10.8 7.8", "M13.6 7.3l4 5.2")
    val Terminal = icon("terminal", "M6 4h12a3 3 0 0 1 3 3v10a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V7a3 3 0 0 1 3-3z",
        "M7 10l3 2.5L7 15", "M12.5 15H17")
    val Power = icon("power", "M12 3v8", "M6.3 6.8a8 8 0 1 0 11.4 0")
    val Router = icon("router", "M4 14h16a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-3a1 1 0 0 1 1-1z",
        "M7 16.5h.01", "M10 16.5h4", "M8 14V5", "M11 6.5a3 3 0 0 1 0 4", "M13.5 4.5a6 6 0 0 1 0 8")
    val Globe = icon("globe", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z", "M3 12h18", "M12 3c2.5 2.6 3.8 5.6 3.8 9s-1.3 6.4-3.8 9",
        "M12 3c-2.5 2.6-3.8 5.6-3.8 9s1.3 6.4 3.8 9")
    val Device = icon("device", "M4 5h11a1 1 0 0 1 1 1v3", "M3 17h9", "M4 5v12", "M15 11h4a1 1 0 0 1 1 1v7a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1v-7a1 1 0 0 1 1-1z")
    /** Smartphone outline ("Tento telefon" in the path card). */
    val Phone = icon("phone", "M8 3h8a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M11 18h2")
    val ChevronRight = icon("chevron", "M9 6l6 6-6 6")
    val ChevronLeft = icon("chevron-left", "M15 6l-6 6 6 6")
    val Close = icon("close", "M6 6l12 12", "M18 6L6 18")
    /** Expand a chart to full screen: two diagonal arrows out of the corners. */
    val Expand = icon("expand", "M14 4h6v6", "M20 4l-6.5 6.5", "M10 20H4v-6", "M4 20l6.5-6.5")
    val Minus = icon("minus", "M5 12h14")
    val Refresh = icon("refresh", "M20 12a8 8 0 1 1-2.3-5.7", "M20 4v4h-4")
    val More = icon("more", "M12 5.2a1 1 0 1 0 0 2a1 1 0 1 0 0-2z", "M12 11a1 1 0 1 0 0 2a1 1 0 1 0 0-2z",
        "M12 16.8a1 1 0 1 0 0 2a1 1 0 1 0 0-2z", filled = setOf(0, 1, 2))
    val Info = icon("info", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z", "M12 11v5", "M12 7.5h.01")
    val Add = icon("add", "M12 5v14", "M5 12h14")
    /** Same glyph as [Add]; name used by the M3 design (zoom "+", add device). */
    val Plus = Add
    val Download = icon("download", "M12 3v12", "M8 11l4 4 4-4", "M4 19h16")
    val Route = icon("route", "M5 16a2 2 0 1 0 0 4a2 2 0 1 0 0-4z", "M19 4a2 2 0 1 0 0 4a2 2 0 1 0 0-4z",
        "M7 18h7a3 3 0 0 0 0-6h-4a3 3 0 0 1 0-6h7")
    /** Speed gauge: 240° dial with a needle (speed test). */
    val Speed = icon("speed", "M5.6 18.4a9 9 0 1 1 12.8 0", "M12 13l3.5-4.5", "M12 12a1 1 0 1 0 0 2a1 1 0 1 0 0-2z", filled = setOf(2))
    val Key = icon("key", "M8 6a4 4 0 1 0 0 8a4 4 0 1 0 0-8z", "M11 12.5L20 20", "M16.5 16.5l2-2", "M18.5 18.5l1.5-1.5")
    /** Privacy policy (About). */
    val Shield = icon("shield", "M12 3l7 3v5c0 4.5-3 8.3-7 10c-4-1.7-7-5.5-7-10V6z", "M9 12l2 2l4-4")
    /** Licence texts (About, open-source licences). */
    val Document = icon("document", "M6 3h8l4 4v14H6z", "M14 3v4h4", "M9 12h6", "M9 16h6")
}
