package com.drishtisaathi.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.pow

/**
 * docs/DESIGN.md §4: the tokens are the ones in the spec, every text / background pair reaches WCAG AA (4.5:1) in the
 * light and the dark theme, text on a solid colour is picked for contrast, the survey's section colours are the
 * tokens, and the type scale, spacing and motion values follow the spec.
 */
class DesignTokensTest {
    private fun hex(c: Color) = "#%02X%02X%02X".format((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
    private fun lum(c: Color): Double {
        fun lin(v: Float) = if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }
    private fun contrast(a: Color, b: Color): Double { val (hi, lo) = listOf(lum(a), lum(b)).sortedDescending(); return (hi + 0.05) / (lo + 0.05) }

    @Test fun `light tokens are the ones from the spec`() {
        val c = LightColors
        assertEquals(
            listOf("#F6F8FB", "#FFFFFF", "#F0F3F8", "#0E1A2B", "#5B6B7F", "#E4E9F0", "#1F5EFF", "#FFFFFF", "#E8EEFF", "#16A34A", "#D97706", "#DC2626"),
            listOf(c.background, c.surface, c.surfaceAlt, c.ink, c.inkSecondary, c.hairline, c.primary, c.onPrimary, c.primarySoft,
                c.success, c.warning, c.urgent).map(::hex),
        )
        assertEquals(listOf("#2F80ED", "#84CC16", "#64748B", "#F59E0B"), listOf(c.work, c.digital, c.mobility, c.leisure).map(::hex))
    }

    /** Every pair a screen draws text with. */
    private fun textPairs(c: DrishtiColors): Map<String, Pair<Color, Color>> = mapOf(
        "ink on background" to (c.ink to c.background), "ink on surface" to (c.ink to c.surface), "ink on surface-alt" to (c.ink to c.surfaceAlt),
        "ink-muted on surface" to (c.inkSecondary to c.surface), "ink-muted on background" to (c.inkSecondary to c.background),
        "ink-muted on surface-alt" to (c.inkSecondary to c.surfaceAlt),
        "primary on surface" to (c.primary to c.surface), "primary on background" to (c.primary to c.background),
        "on-primary on primary" to (c.onPrimary to c.primary),
        "primary ink on primary container" to (c.primaryInk to c.primarySoft), "ink on primary container" to (c.ink to c.primarySoft),
        "success ink on its container" to (c.successInk to c.successSoft), "success ink on surface" to (c.successInk to c.surface),
        "warning ink on its container" to (c.warningInk to c.warningSoft), "warning ink on surface" to (c.warningInk to c.surface),
        "urgent ink on its container" to (c.urgentInk to c.urgentSoft), "urgent ink on surface" to (c.urgentInk to c.surface),
        "ink on urgent container" to (c.ink to c.urgentSoft), "ink on warning container" to (c.ink to c.warningSoft),
        "ink on success container" to (c.ink to c.successSoft),
    )

    @Test fun `every text pair reaches 4_5 to 1 in the light and the dark theme`() {
        for (c in listOf(LightColors, DarkColors)) {
            val pairs = textPairs(c) + if (c.isDark) emptyMap() else mapOf("white on urgent (red banner)" to (Color.White to c.urgent))
            for ((name, p) in pairs) {
                val r = contrast(p.first, p.second)
                assertTrue("${if (c.isDark) "dark" else "light"}: $name is %.2f:1".format(r), r >= 4.5)
            }
        }
    }

    @Test fun `text on a solid colour is picked for contrast`() {
        for (t in listOf(LightColors, DarkColors)) {
            for (c in listOf(t.work, t.digital, t.mobility, t.leisure, t.urgent, t.primary, t.success, t.warning)) {
                assertEquals(hex(c), maxOf(contrast(Color.White, c), contrast(LightColors.ink, c)), contrast(DS.on(c), c), 1e-6)
                assertTrue(hex(c) + " " + contrast(DS.on(c), c), contrast(DS.on(c), c) >= 4.5)
            }
        }
        assertEquals(contrast(LightColors.ink, LightColors.surface), DS.contrast(LightColors.ink, LightColors.surface), 1e-9)
    }

    @Test fun `the survey sections use the token colours`() {
        val q = File("src/main/assets/anamnesis_questions.json").readText()
        for (h in listOf(LightColors.work, LightColors.digital, LightColors.mobility, LightColors.leisure, LightColors.urgent).map(::hex)) {
            assertTrue(h, q.contains("\"colour\": \"$h\""))
        }
    }

    @Test fun `spacing, shape, touch targets and motion follow the spec`() {
        assertEquals(listOf(4f, 8f, 12f, 16f, 24f, 32f), listOf(DS.s1, DS.s2, DS.s3, DS.s4, DS.s5, DS.s6).map { it.value })
        assertEquals(24f, DS.screen.value)
        assertTrue(DS.minTouch.value >= 48f && DS.answerMin.value >= 64f)
        assertEquals(56f, DS.buttonHeight.value)
        assertEquals(40f, DS.iconCircle.value); assertEquals(24f, DS.icon.value)
        assertEquals(200, DS.stepMs); assertEquals(120, DS.pressMs); assertEquals(900, DS.ringsMs)
        val tokens = File("src/main/kotlin/com/drishtisaathi/ui/Tokens.kt").readText()
        assertTrue(tokens.contains("val card = RoundedCornerShape(20.dp)") && tokens.contains("val button = RoundedCornerShape(16.dp)"))
    }

    @Test fun `the type scale follows the spec - display 32 40, headline 24 32, title 20 28, body 17 26, label 14 20, caption 13 18`() {
        val t = File("src/main/kotlin/com/drishtisaathi/ui/Theme.kt").readText()
        listOf(
            "displaySmall = style(32, 40, FontWeight.Bold)", "headlineSmall = style(24, 32, FontWeight.SemiBold)",
            "titleLarge = style(20, 28, FontWeight.SemiBold)", "bodyLarge = style(17, 26)",
            "labelLarge = style(14, 20, FontWeight.Medium)", "bodySmall = style(13, 18)", "fontFeatureSettings = TNUM",
        ).forEach { assertTrue(it, t.contains(it)) }
    }

    @Test fun `screens take colours from the tokens - no colour literals in the design system`() {
        val ds = File("src/main/kotlin/com/drishtisaathi/ui/designsystem")
        val literal = Regex("""Color\(0x[0-9A-Fa-f]{8}\)""")
        ds.listFiles()!!.filter { it.extension == "kt" }.forEach { f ->
            // the hero shadow tint is the one literal (a shadow colour, not a text or fill colour)
            val found = literal.findAll(f.readText()).map { it.value }.filter { it != "Color(0x1A0E1A2B)" }.toList()
            assertTrue("${f.name}: $found", found.isEmpty())
        }
    }
}
