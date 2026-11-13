package com.drishtisaathi.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Design tokens (Phase F): calm, clinical, premium. One set for light and one for dark; every screen takes its
 * colours from [DS.colors], never from literals.
 */
data class DrishtiColors(
    val background: Color,
    val surface: Color,
    val hairline: Color,
    val ink: Color,
    val inkSecondary: Color,
    val primary: Color,
    val primaryPressed: Color,
    val onPrimary: Color,
    val primarySoft: Color,
    val success: Color,
    val successSoft: Color,
    val warning: Color,
    val warningInk: Color,
    val warningSoft: Color,
    val urgent: Color,
    val urgentSoft: Color,
    val work: Color,
    val digital: Color,
    val mobility: Color,
    val leisure: Color,
    val isDark: Boolean,
)

val LightColors = DrishtiColors(
    background = Color(0xFFF7F9FC), surface = Color(0xFFFFFFFF), hairline = Color(0xFFE3E8EF),
    ink = Color(0xFF0B1B2B), inkSecondary = Color(0xFF5A6B7D),
    primary = Color(0xFF1A5CFF), primaryPressed = Color(0xFF0B3FCC), onPrimary = Color(0xFFFFFFFF), primarySoft = Color(0xFFE8EFFF),
    success = Color(0xFF1E9E6A), successSoft = Color(0xFFE5F6EE),
    warning = Color(0xFFE8A317), warningInk = Color(0xFF8A5A00), warningSoft = Color(0xFFFFF4DC),
    urgent = Color(0xFFD93838), urgentSoft = Color(0xFFFDEAEA),
    work = Color(0xFF2F80ED), digital = Color(0xFFA3D33A), mobility = Color(0xFF6B7785), leisure = Color(0xFFF2A93B),
    isDark = false,
)

val DarkColors = DrishtiColors(
    background = Color(0xFF0A121C), surface = Color(0xFF131E2B), hairline = Color(0xFF243243),
    ink = Color(0xFFEEF3F9), inkSecondary = Color(0xFF9FB0C2),
    primary = Color(0xFF6C96FF), primaryPressed = Color(0xFF4C7BF2), onPrimary = Color(0xFF05122B), primarySoft = Color(0xFF16284A),
    success = Color(0xFF45C48E), successSoft = Color(0xFF11301F),
    warning = Color(0xFFF2B93B), warningInk = Color(0xFFF2B93B), warningSoft = Color(0xFF33290F),
    urgent = Color(0xFFFF6B6B), urgentSoft = Color(0xFF3A1717),
    work = Color(0xFF5B9BF3), digital = Color(0xFFB5E04F), mobility = Color(0xFF94A0AE), leisure = Color(0xFFF5B95A),
    isDark = true,
)

val LocalDrishtiColors = staticCompositionLocalOf { LightColors }

object DS {
    val colors: DrishtiColors
        @Composable @ReadOnlyComposable get() = LocalDrishtiColors.current

    // Shape: cards 24dp, buttons 16dp, pill chips.
    val card = RoundedCornerShape(24.dp)
    val button = RoundedCornerShape(16.dp)
    val pill = RoundedCornerShape(percent = 50)

    // 8dp grid, 24dp screen padding.
    val grid = 8.dp
    val screen = 24.dp
    val minTouch = 48.dp
    val answerMin = 64.dp

    /** Motion: step changes slide + fade in this time (ms); the profile rings draw in over [ringsMs]. */
    const val stepMs = 250
    const val ringsMs = 900

    /** A section colour from its RGB hex in the question file (e.g. "#2F80ED"). */
    fun parse(hex: String): Color = Color(android.graphics.Color.parseColor(hex))

    /** Black or white, whichever reads better on [bg] (WCAG relative luminance). */
    fun on(bg: Color): Color {
        fun lin(c: Float) = if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        val l = 0.2126f * lin(bg.red) + 0.7152f * lin(bg.green) + 0.0722f * lin(bg.blue)
        return if (l > 0.42f) Color(0xFF0B1B2B) else Color.White
    }
}
