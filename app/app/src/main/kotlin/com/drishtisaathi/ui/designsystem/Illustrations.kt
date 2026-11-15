package com.drishtisaathi.ui.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.drishtisaathi.ui.DS
import com.drishtisaathi.ui.DrishtiColors

/**
 * Spot illustrations (docs/DESIGN.md §4): simple, original flat drawings in the brand palette, drawn in code so they
 * follow the light and dark theme and cost no APK space. Decorative: TalkBack skips them (the text next to them says
 * the same thing).
 */
enum class Spot { EYE, PHONE_DISTANCE, CARD_ON_SCREEN, GLASSES, LIGHT, BATTERY, CHAT, SUCCESS }

@Composable
fun Illustration(spot: Spot, modifier: Modifier = Modifier, size: Dp = 160.dp) {
    val c = DS.colors
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        val u = this.size.minDimension / 100f              // drawings use a 100 × 100 grid
        // a soft disc behind every drawing
        drawCircle(c.primarySoft, 46 * u, Offset(50 * u, 50 * u))
        when (spot) {
            Spot.EYE -> eye(c, u)
            Spot.PHONE_DISTANCE -> phoneDistance(c, u)
            Spot.CARD_ON_SCREEN -> cardOnScreen(c, u)
            Spot.GLASSES -> glasses(c, u)
            Spot.LIGHT -> light(c, u)
            Spot.BATTERY -> battery(c, u)
            Spot.CHAT -> chat(c, u)
            Spot.SUCCESS -> success(c, u)
        }
    }
}

private fun DrawScope.o(x: Float, y: Float, u: Float) = Offset(x * u, y * u)

private fun DrawScope.eye(c: DrishtiColors, u: Float) {
    val p = Path().apply {
        moveTo(14 * u, 50 * u)
        cubicTo(24 * u, 32 * u, 37 * u, 25 * u, 50 * u, 25 * u)
        cubicTo(63 * u, 25 * u, 76 * u, 32 * u, 86 * u, 50 * u)
        cubicTo(76 * u, 68 * u, 63 * u, 75 * u, 50 * u, 75 * u)
        cubicTo(37 * u, 75 * u, 24 * u, 68 * u, 14 * u, 50 * u)
        close()
    }
    drawPath(p, c.surface)
    drawPath(p, c.ink, style = Stroke(3 * u, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    drawCircle(c.primary, 15 * u, o(50f, 50f, u))
    drawCircle(c.ink, 6.5f * u, o(50f, 50f, u))
    drawCircle(Color.White, 2.6f * u, o(54f, 45.5f, u))
}

private fun DrawScope.phone(c: DrishtiColors, u: Float, x: Float, y: Float, w: Float, h: Float) {
    drawRoundRect(c.ink, o(x, y, u), Size(w * u, h * u), CornerRadius(5 * u))
    drawRoundRect(c.surface, o(x + 2.5f, y + 4f, u), Size((w - 5) * u, (h - 8) * u), CornerRadius(2.5f * u))
}

private fun DrawScope.phoneDistance(c: DrishtiColors, u: Float) {
    // a face on the left, the phone held away on the right, a dashed distance line between them
    drawCircle(c.leisure.copy(alpha = 0.85f), 13 * u, o(26f, 46f, u))
    drawCircle(c.ink, 1.8f * u, o(30f, 44f, u))
    phone(c, u, 66f, 28f, 20f, 36f)
    drawCircle(c.primary, 3.2f * u, o(76f, 46f, u))
    val y = 72 * u
    drawLine(c.primary, Offset(24 * u, y), Offset(78 * u, y), 2.5f * u, StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(5 * u, 4 * u)))
    listOf(24f to 1f, 78f to -1f).forEach { (x, d) ->
        drawLine(c.primary, Offset(x * u, y), Offset((x + 4 * d) * u, y - 4 * u), 2.5f * u, StrokeCap.Round)
        drawLine(c.primary, Offset(x * u, y), Offset((x + 4 * d) * u, y + 4 * u), 2.5f * u, StrokeCap.Round)
    }
}

private fun DrawScope.cardOnScreen(c: DrishtiColors, u: Float) {
    phone(c, u, 30f, 18f, 40f, 66f)
    rotate(-8f, o(52f, 54f, u)) {
        drawRoundRect(c.work, o(24f, 42f, u), Size(52 * u, 32 * u), CornerRadius(4 * u))
        drawRoundRect(c.leisure, o(30f, 50f, u), Size(10 * u, 8 * u), CornerRadius(1.5f * u))
        drawLine(Color.White.copy(alpha = 0.85f), o(30f, 66f, u), o(62f, 66f, u), 2.5f * u, StrokeCap.Round)
    }
}

private fun DrawScope.glasses(c: DrishtiColors, u: Float) {
    val s = Stroke(4 * u, cap = StrokeCap.Round)
    listOf(31f, 69f).forEach { x ->
        drawRoundRect(c.surface, o(x - 15, 38f, u), Size(30 * u, 24 * u), CornerRadius(10 * u))
        drawRoundRect(c.ink, o(x - 15, 38f, u), Size(30 * u, 24 * u), CornerRadius(10 * u), style = s)
        drawLine(c.work.copy(alpha = 0.55f), o(x - 8, 44f, u), o(x - 3, 41f, u), 2.5f * u, StrokeCap.Round)
    }
    drawArc(c.ink, 200f, 140f, false, o(44f, 40f, u), Size(12 * u, 10 * u), style = s)
    drawLine(c.ink, o(16f, 44f, u), o(9f, 40f, u), 4 * u, StrokeCap.Round)
    drawLine(c.ink, o(84f, 44f, u), o(91f, 40f, u), 4 * u, StrokeCap.Round)
}

private fun DrawScope.light(c: DrishtiColors, u: Float) {
    val centre = o(50f, 50f, u)
    for (i in 0 until 8) rotate(i * 45f, centre) {
        drawLine(c.leisure, o(50f, 18f, u), o(50f, 26f, u), 4.5f * u, StrokeCap.Round)
    }
    drawCircle(c.leisure, 17 * u, centre)
    drawCircle(Color.White.copy(alpha = 0.5f), 6 * u, o(44f, 44f, u))
}

private fun DrawScope.battery(c: DrishtiColors, u: Float) {
    drawRoundRect(c.ink, o(22f, 34f, u), Size(52 * u, 32 * u), CornerRadius(6 * u), style = Stroke(4 * u))
    drawRoundRect(c.ink, o(75f, 44f, u), Size(5 * u, 12 * u), CornerRadius(2 * u))
    drawRoundRect(c.success, o(28f, 40f, u), Size(40 * u, 20 * u), CornerRadius(3 * u))
    val bolt = Path().apply {
        moveTo(51 * u, 41 * u); lineTo(42 * u, 52 * u); lineTo(49 * u, 52 * u); lineTo(46 * u, 60 * u); lineTo(56 * u, 48 * u); lineTo(49 * u, 48 * u); close()
    }
    drawPath(bolt, Color.White)
}

private fun DrawScope.chat(c: DrishtiColors, u: Float) {
    drawRoundRect(c.surface, o(16f, 24f, u), Size(48 * u, 30 * u), CornerRadius(12 * u))
    drawRoundRect(c.hairline, o(16f, 24f, u), Size(48 * u, 30 * u), CornerRadius(12 * u), style = Stroke(1.5f * u))
    listOf(28f, 40f, 52f).forEach { drawCircle(c.inkSecondary, 3 * u, o(it, 39f, u)) }
    drawRoundRect(c.primary, o(38f, 52f, u), Size(46 * u, 24 * u), CornerRadius(12 * u))
    drawLine(c.onPrimary, o(48f, 61f, u), o(74f, 61f, u), 3 * u, StrokeCap.Round)
    drawLine(c.onPrimary, o(48f, 68f, u), o(64f, 68f, u), 3 * u, StrokeCap.Round)
}

private fun DrawScope.success(c: DrishtiColors, u: Float) {
    drawCircle(c.success, 26 * u, o(50f, 50f, u))
    val p = Path().apply { moveTo(38 * u, 51 * u); lineTo(46.5f * u, 59 * u); lineTo(63 * u, 42 * u) }
    drawPath(p, DS.on(c.success), style = Stroke(6 * u, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}
