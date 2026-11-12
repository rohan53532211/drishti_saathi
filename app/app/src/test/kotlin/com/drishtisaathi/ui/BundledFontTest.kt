package com.drishtisaathi.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * The app ships its own merged Noto font (tools/build_fonts.py). Every character the app can show (Kotlin sources,
 * JSON assets: strings, frame names, voice lexicon) must have a glyph in every weight, or Android would quietly fall
 * back to the phone's own font for it. Emoji are left to the phone's emoji font.
 */
class BundledFontTest {
    private val weights = listOf("regular", "medium", "semibold", "bold")

    /** Code points mapped by the font's Unicode cmap subtables (formats 4 and 12). */
    private fun cmap(f: File): Set<Int> {
        val b = ByteBuffer.wrap(f.readBytes())
        val numTables = b.getShort(4).toInt() and 0xffff
        var cmapOff = -1
        for (i in 0 until numTables) {
            val rec = 12 + 16 * i
            val tag = String(ByteArray(4) { b.get(rec + it) }, Charsets.US_ASCII)
            if (tag == "cmap") cmapOff = b.getInt(rec + 8)
        }
        check(cmapOff >= 0) { "no cmap in $f" }
        val out = HashSet<Int>()
        val n = b.getShort(cmapOff + 2).toInt() and 0xffff
        for (i in 0 until n) {
            val sub = cmapOff + b.getInt(cmapOff + 4 + 8 * i + 4)
            when (b.getShort(sub).toInt() and 0xffff) {
                4 -> {
                    val segX2 = b.getShort(sub + 6).toInt() and 0xffff
                    val ends = sub + 14
                    val starts = ends + segX2 + 2
                    for (s in 0 until segX2 / 2) {
                        val end = b.getShort(ends + 2 * s).toInt() and 0xffff
                        val start = b.getShort(starts + 2 * s).toInt() and 0xffff
                        if (start != 0xffff) (start..end).forEach { out += it }
                    }
                }
                12 -> {
                    val groups = b.getInt(sub + 12)
                    for (g in 0 until groups) {
                        val o = sub + 16 + 12 * g
                        (b.getInt(o)..b.getInt(o + 4)).forEach { out += it }
                    }
                }
            }
        }
        return out
    }

    private fun usedCodePoints(): Set<Int> {
        val files = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" } +
            File("src/main/assets").walkTopDown().filter { it.extension == "json" }
        return files.flatMap { f -> f.readText().codePoints().toArray().asIterable() }
            .filter { it >= 0x20 && it !in 0x1F000..0x1FFFF && it != 0xFE0F }
            .toSet()
    }

    @Test fun `every weight is bundled and covers Latin, Devanagari and the UI symbols`() {
        val used = usedCodePoints()
        assertTrue("expected Devanagari in the sources", used.any { it in 0x0900..0x097F })
        weights.forEach { w ->
            val f = File("src/main/res/font/noto_$w.ttf")
            assertTrue("$f missing", f.isFile)
            val have = cmap(f)
            val missing = (used - have).sorted().map { "U+%04X".format(it) }
            assertTrue("noto_$w lacks $missing", missing.isEmpty())
        }
    }

    @Test fun `theme and PDF use the bundled font`() {
        val theme = File("src/main/kotlin/com/drishtisaathi/ui/Theme.kt").readText()
        assertTrue(theme.contains(".withFamily(NotoFamily)"))
        weights.forEach { assertTrue(theme.contains("R.font.noto_$it")) }
        val pdf = File("src/main/kotlin/com/drishtisaathi/pdf/PdfExporter.kt").readText()
        assertTrue(pdf.contains("R.font.noto_regular") && pdf.contains("R.font.noto_bold"))
        assertTrue(!pdf.contains("else Typeface.DEFAULT\n"))
        assertTrue(File("src/main/assets/licenses/OFL-NotoSans.txt").readText().contains("SIL Open Font License"))
    }
}
