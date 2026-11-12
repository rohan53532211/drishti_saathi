package com.drishtisaathi.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.drishtisaathi.R
import androidx.compose.ui.unit.sp

val Teal = Color(0xFF00695C)
val TealLight = Color(0xFFE0F2F1)
val UrgentRed = Color(0xFFB71C1C)
val UrgentBg = Color(0xFFFFEBEE)
val RoutineAmber = Color(0xFF8D6E00)
val RoutineBg = Color(0xFFFFF8E1)
val OkGreen = Color(0xFF1B5E20)
val OkBg = Color(0xFFE8F5E9)

private val colors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = TealLight,
    onPrimaryContainer = Color(0xFF00251A),
    secondary = Color(0xFF37474F),
    background = Color(0xFFFAFAFA),
    surface = Color.White,
    error = UrgentRed,
)

/**
 * Bundled font (tools/build_fonts.py): Noto Sans + Noto Sans Devanagari merged into one file per weight, so Hindi,
 * English and mixed lines look the same on every phone and never fall back to the phone's own font. SIL OFL 1.1
 * (assets/licenses/OFL-NotoSans.txt).
 */
val NotoFamily = FontFamily(
    Font(R.font.noto_regular, FontWeight.Normal),
    Font(R.font.noto_medium, FontWeight.Medium),
    Font(R.font.noto_semibold, FontWeight.SemiBold),
    Font(R.font.noto_bold, FontWeight.Bold),
)

/** Every Material text style uses [NotoFamily]. */
private fun Typography.withFamily(f: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = f), displayMedium = displayMedium.copy(fontFamily = f),
    displaySmall = displaySmall.copy(fontFamily = f), headlineLarge = headlineLarge.copy(fontFamily = f),
    headlineMedium = headlineMedium.copy(fontFamily = f), headlineSmall = headlineSmall.copy(fontFamily = f),
    titleLarge = titleLarge.copy(fontFamily = f), titleMedium = titleMedium.copy(fontFamily = f),
    titleSmall = titleSmall.copy(fontFamily = f), bodyLarge = bodyLarge.copy(fontFamily = f),
    bodyMedium = bodyMedium.copy(fontFamily = f), bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f), labelMedium = labelMedium.copy(fontFamily = f),
    labelSmall = labelSmall.copy(fontFamily = f),
)

/** Large type throughout: the interview is read by customers of all ages at a shop counter. */
private val type = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
).withFamily(NotoFamily)

@Composable
fun DrishtiTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, typography = type, content = content)
