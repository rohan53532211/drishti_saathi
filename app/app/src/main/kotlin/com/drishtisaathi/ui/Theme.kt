package com.drishtisaathi.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drishtisaathi.R

// The older screens' colour names, now taken from the design tokens (light or dark) so every screen follows the theme.
val Teal: Color @Composable @ReadOnlyComposable get() = DS.colors.primary
val TealLight: Color @Composable @ReadOnlyComposable get() = DS.colors.primarySoft
val UrgentRed: Color @Composable @ReadOnlyComposable get() = DS.colors.urgentInk
val UrgentBg: Color @Composable @ReadOnlyComposable get() = DS.colors.urgentSoft
val RoutineAmber: Color @Composable @ReadOnlyComposable get() = DS.colors.warningInk
val RoutineBg: Color @Composable @ReadOnlyComposable get() = DS.colors.warningSoft
val OkGreen: Color @Composable @ReadOnlyComposable get() = DS.colors.successInk
val OkBg: Color @Composable @ReadOnlyComposable get() = DS.colors.successSoft

/**
 * Bundled font (tools/build_fonts.py): Inter (Latin) + Noto Sans Devanagari (Hindi) merged into one file per weight,
 * so Hindi, English and mixed lines look the same on every phone and never fall back to the phone's own font. Both
 * SIL OFL 1.1 (assets/licenses/OFL-Inter.txt, OFL-NotoSans.txt).
 */
val AppFamily = FontFamily(
    Font(R.font.inter_deva_regular, FontWeight.Normal),
    Font(R.font.inter_deva_medium, FontWeight.Medium),
    Font(R.font.inter_deva_semibold, FontWeight.SemiBold),
    Font(R.font.inter_deva_bold, FontWeight.Bold),
)
/** The name the older screens use for the bundled family. */
val NotoFamily: FontFamily get() = AppFamily

/** Tabular figures: digits of equal width, so numbers line up and do not jump while they change. */
private const val TNUM = "tnum"

private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = AppFamily, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, fontFeatureSettings = TNUM)

/**
 * Type scale: display 32 sp semibold, title 24 sp, body 18 sp, label 15 sp. Large type throughout: the screens are
 * read by customers of all ages at a shop counter. Line heights leave room for Devanagari matras.
 */
private val type = Typography(
    displayLarge = style(44, 54, FontWeight.SemiBold),
    displayMedium = style(38, 48, FontWeight.SemiBold),
    displaySmall = style(32, 42, FontWeight.SemiBold),
    headlineLarge = style(30, 40, FontWeight.SemiBold),
    headlineMedium = style(28, 38, FontWeight.SemiBold),
    headlineSmall = style(24, 34, FontWeight.SemiBold),
    titleLarge = style(24, 34, FontWeight.SemiBold),
    titleMedium = style(20, 28, FontWeight.Medium),
    titleSmall = style(17, 24, FontWeight.Medium),
    bodyLarge = style(18, 27),
    bodyMedium = style(16, 24),
    bodySmall = style(14, 21),
    labelLarge = style(18, 26, FontWeight.SemiBold),
    labelMedium = style(15, 22, FontWeight.Medium),
    labelSmall = style(13, 19, FontWeight.Medium),
)

/** Shape: cards 24 dp, buttons 16 dp. */
private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp),
)

private fun scheme(c: DrishtiColors) = (if (c.isDark) darkColorScheme() else lightColorScheme()).copy(
    primary = c.primary, onPrimary = c.onPrimary, primaryContainer = c.primarySoft, onPrimaryContainer = c.ink,
    secondary = c.inkSecondary, onSecondary = c.surface, secondaryContainer = c.primarySoft, onSecondaryContainer = c.ink,
    tertiary = c.success, onTertiary = c.surface, tertiaryContainer = c.successSoft, onTertiaryContainer = c.ink,
    background = c.background, onBackground = c.ink,
    surface = c.surface, onSurface = c.ink, surfaceVariant = c.background, onSurfaceVariant = c.inkSecondary,
    surfaceContainerLowest = c.surface, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
    surfaceContainerHigh = c.surface, surfaceContainerHighest = c.background, surfaceTint = c.surface,
    outline = c.hairline, outlineVariant = c.hairline,
    error = c.urgent, onError = Color.White, errorContainer = c.urgentSoft, onErrorContainer = c.urgent,
)

/** The app theme: light or dark tokens (the phone's setting unless [dark] is given), the type scale and the shapes. */
@Composable
fun DrishtiTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) DarkColors else LightColors
    CompositionLocalProvider(LocalDrishtiColors provides c) {
        MaterialTheme(colorScheme = scheme(c), typography = type, shapes = shapes, content = content)
    }
}

/**
 * Always light: the vision tests draw black targets on white at full brightness, whatever the phone's theme, so
 * their size and contrast stay as designed.
 */
@Composable
fun LightTheme(content: @Composable () -> Unit) = DrishtiTheme(dark = false, content = content)
