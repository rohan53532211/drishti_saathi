package com.drishtisaathi.voice

import java.text.Normalizer

/**
 * Canonical form for matching recognised speech against the lexicon. Applied to both sides, so lexicon
 * entries can be written with or without nukta / chandrabindu.
 * - Unicode NFD, then nukta (U+093C) removed: ज़ → ज, ड़ → ड (recognisers are inconsistent with nukta);
 * - chandrabindu (U+0901) → anusvara (U+0902): हाँ → हां;
 * - Devanagari digits → ASCII; lower case;
 * - apostrophes dropped (don't → dont); any other non-letter/digit/mark (punctuation, danda, hyphen, ₹) → space.
 */
object TextNormalizer {
    private const val NUKTA = '\u093C'
    private const val CHANDRABINDU = '\u0901'
    private const val ANUSVARA = '\u0902'
    private const val ZWNJ = '\u200C'
    private const val ZWJ = '\u200D'

    fun normalize(s: String): String {
        val nfd = Normalizer.normalize(s, Normalizer.Form.NFD)
        val sb = StringBuilder(nfd.length)
        for ((i, c) in nfd.withIndex()) {
            when {
                // "1,500" → "1500"
                c == ',' && i > 0 && i < nfd.length - 1 && isDigitAny(nfd[i - 1]) && isDigitAny(nfd[i + 1]) -> {}
                c == NUKTA || c == ZWNJ || c == ZWJ || c == '\'' || c == '’' -> {}
                c == CHANDRABINDU -> sb.append(ANUSVARA)
                c in '०'..'९' -> sb.append('0' + (c - '०'))
                Character.isLetterOrDigit(c) || isMark(c) -> sb.append(c.lowercaseChar())
                else -> sb.append(' ')
            }
        }
        // Recompose (NFC) so Latin accents etc. compare consistently; nukta letters stay decomposed-and-stripped.
        return Normalizer.normalize(sb.toString(), Normalizer.Form.NFC).trim().replace(Regex("\\s+"), " ")
    }

    private fun isDigitAny(c: Char) = c in '0'..'9' || c in '०'..'९'

    fun tokens(s: String): List<String> = normalize(s).split(' ').filter { it.isNotEmpty() }

    private fun isMark(c: Char): Boolean = when (Character.getType(c)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    fun isDevanagari(s: String): Boolean = s.any { it in '\u0900'..'\u097F' }
    fun isLatin(s: String): Boolean = s.isNotBlank() && s.all { it.code < 0x250 }
}
