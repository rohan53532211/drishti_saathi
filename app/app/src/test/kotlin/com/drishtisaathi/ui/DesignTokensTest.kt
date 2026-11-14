package com.drishtisaathi.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.pow

/** Phase F: the design tokens match the brief, text pairs reach 4.5:1 in both themes, and the survey's section colours are the tokens. */
class DesignTokensTest {
    private fun hex(c: Color) = "#%02X%02X%02X".format((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
    private fun lum(c: Color): Double {
        fun lin(v: Float) = if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }
    private fun contrast(a: Color, b: Color): Double { val (hi, lo) = listOf(lum(a), lum(b)).sortedDescending(); return (hi + 0.05) / (lo + 0.05) }

    @Test fun `light tokens are the ones from the brief`() {
        val c = LightColors
        assertEquals(
            listOf("#F7F9FC", "#FFFFFF", "#E3E8EF", "#0B1B2B", "#5A6B7D", "#1A5CFF", "#0B3FCC", "#1E9E6A", "#E8A317", "#D93838"),
            listOf(c.background, c.surface, c.hairline, c.ink, c.inkSecondary, c.primary, c.primaryPressed, c.success, c.warning, c.urgent).map(::hex),
        )
        assertEquals(listOf("#2F80ED", "#A3D33A", "#6B7785", "#F2A93B"), listOf(c.work, c.digital, c.mobility, c.leisure).map(::hex))
    }

    @Test fun `text reaches 4_5 to 1 on its background in the light and the dark theme`() {
        for (c in listOf(LightColors, DarkColors)) {
            val pairs = mapOf(
                "ink on background" to (c.ink to c.background), "ink on surface" to (c.ink to c.surface),
                "secondary ink on surface" to (c.inkSecondary to c.surface), "secondary ink on background" to (c.inkSecondary to c.background),
                "primary on surface" to (c.primary to c.surface), "on-primary on primary" to (c.onPrimary to c.primary),
                "primary on its soft tint" to (c.primary to c.primarySoft),
                "success ink on its soft tint" to (c.successInk to c.successSoft), "warning ink on its soft tint" to (c.warningInk to c.warningSoft),
                "urgent ink on its soft tint" to (c.urgentInk to c.urgentSoft), "urgent ink on surface" to (c.urgentInk to c.surface), "ink on urgent soft" to (c.ink to c.urgentSoft),
            ) + if (c.isDark) emptyMap() else mapOf("white on urgent (red banner)" to (Color.White to c.urgent))
            for ((name, p) in pairs) {
                val r = contrast(p.first, p.second)
                assertTrue("${if (c.isDark) "dark" else "light"}: $name is %.2f:1".format(r), r >= 4.5)
            }
        }
    }

    @Test fun `text on a section colour is picked for contrast`() {
        for (c in listOf(LightColors.work, LightColors.digital, LightColors.mobility, LightColors.leisure, LightColors.urgent, LightColors.primary)) {
            assertEquals(hex(c), maxOf(contrast(Color.White, c), contrast(Color(0xFF0B1B2B), c)), contrast(DS.on(c), c), 1e-6)
            assertTrue(hex(c), contrast(DS.on(c), c) >= 4.5)
        }
    }

    @Test fun `the survey sections use the token colours and touch targets are at least 48 dp`() {
        val q = File("src/main/assets/anamnesis_questions.json").readText()
        for (h in listOf(LightColors.work, LightColors.digital, LightColors.mobility, LightColors.leisure, LightColors.urgent).map(::hex)) {
            assertTrue(h, q.contains("\"colour\": \"$h\""))
        }
        assertTrue(DS.minTouch.value >= 48f && DS.answerMin.value >= 64f)
        assertEquals(250, DS.stepMs); assertEquals(900, DS.ringsMs)
    }

    @Test fun `the type scale follows the brief - display 32, title 24, body 18, label 15, tabular figures`() {
        val t = File("src/main/kotlin/com/drishtisaathi/ui/Theme.kt").readText()
        assertTrue(t.contains("displaySmall = style(32, 42, FontWeight.SemiBold)"))
        assertTrue(t.contains("titleLarge = style(24,"))
        assertTrue(t.contains("bodyLarge = style(18,"))
        assertTrue(t.contains("labelMedium = style(15,"))
        assertTrue(t.contains("fontFeatureSettings = TNUM"))
    }
}
