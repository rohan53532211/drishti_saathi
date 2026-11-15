package com.drishtisaathi.ui.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Our own icons, drawn in the same style as Material Symbols Rounded: a 24 × 24 grid, 2 dp strokes, round caps and
 * joins (docs/DESIGN.md §4). Tint them like any Material icon. Original drawings, no third-party art.
 */
object DsIcons {
    private fun icon(name: String, strokes: List<String>, fills: List<String> = emptyList()): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            strokes.forEach {
                addPath(addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            }
            fills.forEach { addPath(addPathNodes(it), fill = SolidColor(Color.Black)) }
        }.build()

    /** The brand mark: an eye with a round iris and a filled pupil. */
    val EyeMark: ImageVector by lazy {
        icon("EyeMark",
            listOf("M2.5 12C5.2 7.3 8.4 5 12 5s6.8 2.3 9.5 7c-2.7 4.7-5.9 7-9.5 7S5.2 16.7 2.5 12z",
                "M8.6 12a3.4 3.4 0 1 0 6.8 0a3.4 3.4 0 1 0 -6.8 0"),
            listOf("M10.6 12a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0 -2.8 0"))
    }

    /** A spectacle lens with a highlight. */
    val Lens: ImageVector by lazy {
        icon("Lens", listOf("M4 12a8 8 0 1 0 16 0a8 8 0 1 0 -16 0", "M8 10.2c0.5-1.4 1.6-2.4 3-2.8"))
    }

    /** The Amsler grid: a square, a 3 × 3 grid and the centre dot. */
    val AmslerGrid: ImageVector by lazy {
        icon("AmslerGrid",
            listOf("M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2H6a2 2 0 0 1 -2 -2V6a2 2 0 0 1 2 -2z",
                "M4 9.33h16", "M4 14.67h16", "M9.33 4v16", "M14.67 4v16"),
            listOf("M11 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0"))
    }

    /** A colour-vision plate: a circle of dots with a figure inside. */
    val ColourPlate: ImageVector by lazy {
        icon("ColourPlate",
            listOf("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0", "M10 9.5c0.6-0.9 2.9-1.1 3.6 0.3c0.7 1.4-1.5 2.4-1.5 2.4s2.4 0.6 1.8 2.4c-0.6 1.6-3 1.4-3.9 0.4"),
            listOf("M6.4 9.6a1 1 0 1 0 2 0a1 1 0 1 0 -2 0", "M6.6 14.2a1 1 0 1 0 2 0a1 1 0 1 0 -2 0",
                "M15.6 9.4a1 1 0 1 0 2 0a1 1 0 1 0 -2 0", "M15.4 14.4a1 1 0 1 0 2 0a1 1 0 1 0 -2 0"))
    }
}
