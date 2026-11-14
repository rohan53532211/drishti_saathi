package com.drishtisaathi

import com.drishtisaathi.engine.Coating
import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.FitVerdict
import com.drishtisaathi.engine.LensType
import com.drishtisaathi.engine.Note
import com.drishtisaathi.engine.Occupation
import com.drishtisaathi.engine.ReferralLevel
import com.drishtisaathi.engine.ReferralReason
import com.drishtisaathi.engine.Source
import com.drishtisaathi.engine.StylePref
import com.drishtisaathi.i18n.S
import com.drishtisaathi.i18n.Txt
import com.drishtisaathi.i18n.fmtD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Section 6: never "prescription", "diagnosis", "power is" (or Hindi equivalents) outside the disclaimer. */
class StringsWordingTest {
    private val bannedEn = Regex("""\b(prescription|prescribed|diagnos\w*|power is)\b""", RegexOption.IGNORE_CASE)
    private val bannedHi = listOf("पर्चा", "निदान", "पावर है", "आपका नंबर")

    /** The disclaimer itself, and the brief's own question about the customer's existing slip. */
    private val allowed = setOf(S.disclaimer, S.qKnownRx, com.drishtisaathi.i18n.SProfile.screeningLabel)

    private fun violations(t: Txt): List<String> =
        listOfNotNull(bannedEn.find(t.en)?.value) + bannedHi.filter { it in t.hi }

    @Test fun `no banned wording anywhere in the string table`() {
        // Touch every formatter so lazily-built labels are covered too.
        ReferralLevel.entries.forEach { S.referralLevel(it) }
        val rules = com.drishtisaathi.engine.RulesConfig.parse(java.io.File("src/main/assets/rules.json").readText())
        ReferralReason.entries.forEach { S.referralReason(it, rules) }
        com.drishtisaathi.engine.StartingHint.entries.forEach { S.startingHint(it) }
        LensType.entries.forEach { S.lensType(it) }
        Coating.entries.forEach { S.coating(it) }
        Confidence.entries.forEach { S.confidence(it) }
        Source.entries.forEach { S.source(it) }
        Note.entries.forEach { S.note(it, 2) }
        FitVerdict.entries.forEach { S.fit(it) }
        Occupation.entries.forEach { S.occupation(it) }
        StylePref.entries.forEach { S.style(it) }
        com.drishtisaathi.engine.MatchLabel.entries.forEach { S.matchLabel(it) }
        com.drishtisaathi.engine.ColourPref.entries.forEach { S.colour(it) }
        com.drishtisaathi.engine.LookPref.entries.forEach { S.look(it) }
        com.drishtisaathi.engine.ReadingFrequency.entries.forEach { S.readingFrequency(it) }
        com.drishtisaathi.i18n.SProfile.touchAll()
        com.drishtisaathi.engine.ReasonCode.entries.forEach { S.reasonTemplate(it, true); S.reasonTemplate(it, false); S.reasonTemplate(it, true, assumed = true); S.reasonTemplate(it, false, uncalibrated = true) }

        assertTrue(S.all.size > 100)
        val everything = S.all + com.drishtisaathi.i18n.TxtRegistry.loadAll()
        val bad = everything.filter { it !in allowed }.associateWith { violations(it) }.filterValues { it.isNotEmpty() }
        assertTrue("Banned wording: $bad", bad.isEmpty())
    }

    @Test fun `disclaimer has exact bilingual wording`() {
        assertEquals("यह चिकित्सीय निदान या पर्चा नहीं है / Not a medical diagnosis or prescription.", S.disclaimerBoth)
    }

    @Test fun `every string has both languages`() {
        assertTrue((S.all + com.drishtisaathi.i18n.TxtRegistry.loadAll()).all { it.en.isNotBlank() && it.hi.isNotBlank() })
    }

    @Test fun `dioptre formatting`() {
        assertEquals("−1.50", fmtD(-1.5))
        assertEquals("+0.75", fmtD(0.75))
        assertEquals("0.00", fmtD(0.0))
        assertEquals("0.00", fmtD(-0.001))
    }

    @Test fun `formatted strings fill placeholders`() {
        assertEquals("Question 3 of 16", S.questionOf.format(3, 16).en)
        assertTrue(S.edgeAbout.format(4.2, "1.60").hi.contains("4.2"))
        assertTrue(S.edgeAbout.format(4.2, "1.60").hi.startsWith("1.60"))
    }
}
