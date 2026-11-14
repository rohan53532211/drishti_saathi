package com.drishtisaathi.engine.profile

import com.drishtisaathi.engine.Eye
import com.drishtisaathi.engine.EyeRx
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.KnownRx
import com.drishtisaathi.engine.PowerKnown
import com.drishtisaathi.engine.QuickEstimate
import com.drishtisaathi.engine.QuickEye
import com.drishtisaathi.engine.QuickTarget
import com.drishtisaathi.engine.TestAssets
import com.drishtisaathi.engine.VisionTests
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The survey block of the real question file. */
object SurveyAssets {
    val file: SurveyFile by lazy {
        val root = Json.parseToJsonElement(TestAssets.text("anamnesis_questions.json")).jsonObject
        Json.decodeFromJsonElement(SurveyFile.serializer(), root.getValue("survey")).also { it.validate() }
    }
}

/** Phase A: every red flag → no lens + URGENT; emergency → short path; child myopia → referral only + tag. */
class SafetyTest {
    private val cfg = TestAssets.rules.safety
    private val none = SurveyAnswers()
    private fun signs(vararg f: RedFlag) = SurveyAnswers().with(Safety.SIGNS_QUESTION, f.map { it.name })
    private fun eye(se: Double?) = QuickEye(se, 30.0, QuickTarget.WHITE, noMyopiaAtReach = se == null)

    @Test fun `no red flag and an adult - lens allowed, routine, full check`() {
        val g = Safety.gate(InterviewAnswers(age = 35, redFlagFlashes = false, redFlagOther = false), none, cfg)
        assertTrue(g.lensAllowed); assertFalse(g.urgent); assertFalse(g.emergency)
        assertEquals(ReportTag.ROUTINE, g.tag)
        assertEquals(CheckStep.entries.toList(), Safety.steps(g, cfg))
    }

    @Test fun `every pickable red flag - no lens, URGENT, power for the optometrist only`() {
        for (f in RedFlag.pickable) {
            val a = InterviewAnswers(age = 40, redFlagFlashes = f in setOf(RedFlag.NEW_FLASHES, RedFlag.NEW_FLOATERS, RedFlag.CURTAIN_SHADOW),
                redFlagOther = f in setOf(RedFlag.SUDDEN_VISION_LOSS, RedFlag.EYE_PAIN_REDNESS, RedFlag.DOUBLE_VISION))
            val g = Safety.gate(a, signs(f), cfg)
            assertEquals("$f", listOf(f), g.redFlags)
            assertFalse("$f lens", g.lensAllowed)
            assertEquals("$f tag", ReportTag.URGENT, g.tag)
            assertTrue("$f power label", g.powerForOptometristOnly)
        }
    }

    @Test fun `double vision from the survey and a wavy grid are red flags too`() {
        val dv = Safety.gate(InterviewAnswers(age = 30), SurveyAnswers().with(Safety.DOUBLE_VISION_QUESTION, listOf(SurveyQuestion.YES)), cfg)
        assertEquals(listOf(RedFlag.DOUBLE_VISION), dv.redFlags)
        assertEquals(ReportTag.URGENT, dv.tag); assertFalse(dv.lensAllowed); assertFalse(dv.emergency)

        val grid = ScreeningResults(amsler = listOf(AmslerEyeResult(Eye.LEFT, true, listOf(AmslerCell(3, 4)), 12, 20)))
        val am = Safety.gate(InterviewAnswers(age = 62), none, cfg, screening = grid)
        assertEquals(listOf(RedFlag.AMSLER_DISTORTION), am.redFlags)
        assertEquals(ReportTag.URGENT, am.tag); assertFalse(am.lensAllowed); assertFalse(am.emergency)

        assertEquals(listOf(RedFlag.AMSLER_DISTORTION), Safety.gate(InterviewAnswers(age = 62), none, cfg, tests = VisionTests(amslerAbnormal = true)).redFlags)
        assertEquals(listOf(RedFlag.AMSLER_DISTORTION), Safety.gate(InterviewAnswers(straightLinesBent = true), none, cfg).redFlags)
    }

    @Test fun `emergency signs take the short path - power of each eye and the grid only`() {
        for (f in listOf(RedFlag.SUDDEN_VISION_LOSS, RedFlag.CURTAIN_SHADOW, RedFlag.NEW_FLASHES)) {
            val g = Safety.gate(InterviewAnswers(redFlagFlashes = true, redFlagOther = true), signs(f), cfg)
            assertTrue("$f", g.emergency)
            assertEquals("$f", listOf(CheckStep.POWER, CheckStep.AMSLER), Safety.steps(g, cfg))
        }
        for (f in listOf(RedFlag.EYE_PAIN_REDNESS, RedFlag.NEW_FLOATERS, RedFlag.DOUBLE_VISION)) {
            val a = InterviewAnswers(redFlagFlashes = f == RedFlag.NEW_FLOATERS, redFlagOther = f != RedFlag.NEW_FLOATERS)
            val g = Safety.gate(a, signs(f), cfg)
            assertFalse("$f is urgent but not an emergency", g.emergency)
            assertTrue(g.urgent)
            assertEquals(CheckStep.entries.toList(), Safety.steps(g, cfg))
        }
    }

    @Test fun `a red-flag yes without the follow-up is kept and counts as an emergency`() {
        val g = Safety.gate(InterviewAnswers(redFlagFlashes = true), none, cfg)
        assertEquals(listOf(RedFlag.FLASHES_FLOATERS_CURTAIN_UNSPECIFIED), g.redFlags)
        assertTrue(g.emergency); assertFalse(g.lensAllowed); assertEquals(ReportTag.URGENT, g.tag)
        val o = Safety.gate(InterviewAnswers(redFlagOther = true), none, cfg)
        assertEquals(listOf(RedFlag.SUDDEN_LOSS_PAIN_DOUBLE_UNSPECIFIED), o.redFlags)
        assertTrue(o.emergency)
        // floaters picked: the first question is specified, the second (also yes) stays unspecified
        val mixed = Safety.gate(InterviewAnswers(redFlagFlashes = true, redFlagOther = true), signs(RedFlag.NEW_FLOATERS), cfg)
        assertEquals(listOf(RedFlag.NEW_FLOATERS, RedFlag.SUDDEN_LOSS_PAIN_DOUBLE_UNSPECIFIED), mixed.redFlags)
    }

    @Test fun `child with any minus power - measured, no lens choice, tag CHILD_MYOPIA`() {
        val q = QuickEstimate(right = eye(-2.0), left = eye(-1.75))
        val g = Safety.gate(InterviewAnswers(age = 10), none, cfg, quick = q)
        assertTrue(g.childMyopia); assertFalse(g.lensAllowed); assertFalse(g.urgent)
        assertEquals(ReportTag.CHILD_MYOPIA, g.tag)
        assertEquals("the full check still runs", CheckStep.entries.toList(), Safety.steps(g, cfg))
        // the smallest minus power counts; the customer's known power counts too
        assertTrue(Safety.gate(InterviewAnswers(age = 17), none, cfg, quick = QuickEstimate(right = eye(-0.25), left = eye(null))).childMyopia)
        val known = InterviewAnswers(age = 12, powerKnown = PowerKnown.YES, knownRx = KnownRx(EyeRx(-1.0), EyeRx(0.0)))
        assertTrue(Safety.gate(known, none, cfg).childMyopia)
    }

    @Test fun `child without myopia may get a lens, labelled optometrist confirms - adults are never child myopia`() {
        val clear = QuickEstimate(right = eye(null), left = eye(null))
        val g = Safety.gate(InterviewAnswers(age = 10), none, cfg, quick = clear)
        assertFalse(g.childMyopia); assertTrue(g.lensAllowed); assertTrue(g.optometristConfirms)
        assertEquals(ReportTag.ROUTINE, g.tag)
        val adult = Safety.gate(InterviewAnswers(age = 18), none, cfg, quick = QuickEstimate(right = eye(-2.0)))
        assertFalse(adult.childMyopia); assertTrue(adult.lensAllowed); assertFalse(adult.optometristConfirms)
    }

    @Test fun `a red flag outranks child myopia in the tag`() {
        val g = Safety.gate(InterviewAnswers(age = 9, redFlagOther = true), signs(RedFlag.EYE_PAIN_REDNESS), cfg, quick = QuickEstimate(right = eye(-3.0)))
        assertEquals(ReportTag.URGENT, g.tag); assertTrue(g.childMyopia); assertFalse(g.lensAllowed)
    }
}
