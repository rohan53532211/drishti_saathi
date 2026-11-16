package com.drishtisaathi.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Design tokens (docs/DESIGN.md §4): calm, clean, trustworthy. One set for light and one for dark; every screen takes
 * its colours from [DS.colors], never from literals. Text always uses an `*Ink` tone (or [ink] / [inkSecondary]) on
 * its own container or the surface: DesignTokensTest checks 4.5:1 for every such pair in both themes.
 */
data class DrishtiColors(
    val background: Color,
    val surface: Color,
    /** Quiet panels inside a card, input fields, skeletons. */
    val surfaceAlt: Color,
    val hairline: Color,
    val ink: Color,
    /** "ink-muted": helper text, captions. */
    val inkSecondary: Color,
    val primary: Color,
    val primaryPressed: Color,
    val onPrimary: Color,
    /** "primary-container". */
    val primarySoft: Color,
    /** Primary as text on [primarySoft] (the brand blue alone is just under 4.5:1 there). */
    val primaryInk: Color,
    val success: Color,
    /** Success as text: dark enough for 4.5:1 on the soft success background. */
    val successInk: Color,
    val successSoft: Color,
    val warning: Color,
    val warningInk: Color,
    val warningSoft: Color,
    val urgent: Color,
    /** Urgent as text on the soft urgent background or the page (4.5:1). */
    val urgentInk: Color,
    val urgentSoft: Color,
    val work: Color,
    val digital: Color,
    val mobility: Color,
    val leisure: Color,
    val isDark: Boolean,
)

val LightColors = DrishtiColors(
    background = Color(0xFFF6F8FB), surface = Color(0xFFFFFFFF), surfaceAlt = Color(0xFFF0F3F8), hairline = Color(0xFFE4E9F0),
    ink = Color(0xFF0E1A2B), inkSecondary = Color(0xFF5B6B7F),
    primary = Color(0xFF1F5EFF), primaryPressed = Color(0xFF1546D1), onPrimary = Color(0xFFFFFFFF),
    primarySoft = Color(0xFFE8EEFF), primaryInk = Color(0xFF1D4ED8),
    success = Color(0xFF16A34A), successInk = Color(0xFF166534), successSoft = Color(0xFFE7F6EC),
    warning = Color(0xFFD97706), warningInk = Color(0xFF92400E), warningSoft = Color(0xFFFEF3E2),
    urgent = Color(0xFFDC2626), urgentInk = Color(0xFFB91C1C), urgentSoft = Color(0xFFFDECEC),
    work = Color(0xFF2F80ED), digital = Color(0xFF84CC16), mobility = Color(0xFF64748B), leisure = Color(0xFFF59E0B),
    isDark = false,
)

val DarkColors = DrishtiColors(
    background = Color(0xFF0B121C), surface = Color(0xFF131D2A), surfaceAlt = Color(0xFF1A2636), hairline = Color(0xFF243243),
    ink = Color(0xFFEEF3F9), inkSecondary = Color(0xFF9FB0C2),
    primary = Color(0xFF7FA2FF), primaryPressed = Color(0xFF5C86F5), onPrimary = Color(0xFF05122B),
    primarySoft = Color(0xFF16284A), primaryInk = Color(0xFF9DB8FF),
    success = Color(0xFF4ADE80), successInk = Color(0xFF6EE7A0), successSoft = Color(0xFF0F2E1C),
    warning = Color(0xFFFBBF24), warningInk = Color(0xFFFCD34D), warningSoft = Color(0xFF33270C),
    urgent = Color(0xFFF87171), urgentInk = Color(0xFFFCA5A5), urgentSoft = Color(0xFF3A1616),
    work = Color(0xFF5B9BF3), digital = Color(0xFFA3E635), mobility = Color(0xFF94A3B8), leisure = Color(0xFFFBBF24),
    isDark = true,
)

val LocalDrishtiColors = staticCompositionLocalOf { LightColors }

object DS {
    val colors: DrishtiColors
        @Composable @ReadOnlyComposable get() = LocalDrishtiColors.current

    // Shape: cards 20 dp, buttons 16 dp, chips and pills full.
    val card = RoundedCornerShape(20.dp)
    val button = RoundedCornerShape(16.dp)
    val field = RoundedCornerShape(14.dp)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val pill = RoundedCornerShape(percent = 50)

    // Spacing scale 4 / 8 / 12 / 16 / 24 / 32; screen padding 24 dp.
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 24.dp
    val s6 = 32.dp
    val grid = 8.dp
    val screen = 24.dp
    val minTouch = 48.dp
    /** Primary and secondary buttons. */
    val buttonHeight = 56.dp
    /** Big tappable answers (ChoiceCard). */
    val answerMin = 64.dp
    /** The tinted circle an icon sits in on a card, and the icon itself. */
    val iconCircle = 40.dp
    val icon = 24.dp
    /** The one soft shadow level (hero cards, bottom sheets). */
    val heroShadow = 12.dp

    /** Motion: screens fade + slide in this time (ms); press scale; success check; the profile rings draw in over [ringsMs]. */
    const val stepMs = 200
    const val pressMs = 120
    const val successMs = 400
    const val ringsMs = 900
    const val pressScale = 0.97f

    /** A section colour from its RGB hex in the question file (e.g. "#2F80ED"). */
    fun parse(hex: String): Color = Color(android.graphics.Color.parseColor(hex))

    /** Dark ink or white, whichever has the higher contrast on [bg] (WCAG relative luminance). */
    fun on(bg: Color): Color {
        val ink = LightColors.ink
        return if (contrast(ink, bg) > contrast(Color.White, bg)) ink else Color.White
    }

    /** WCAG 2.x relative luminance. */
    fun luminance(c: Color): Double {
        fun lin(v: Float) = if (v <= 0.03928f) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    /** WCAG contrast ratio of two opaque colours (1 to 21). */
    fun contrast(a: Color, b: Color): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
