package com.drishtisaathi.engine.profile

import com.drishtisaathi.engine.Eye
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.NotMeasured
import com.drishtisaathi.engine.QuickEstimate
import com.drishtisaathi.engine.QuickEye
import com.drishtisaathi.engine.QuickTarget
import com.drishtisaathi.engine.TestAssets
import com.drishtisaathi.engine.VisionTests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bugs found in the 15 Nov phone test (reports S2 and S32), one test per fix. */
class PhoneTestFixesTest {
    private val rules = TestAssets.rules
    private val lens = rules.lensAssisted
    private val contrast = rules.screening.contrast
    private fun eye(se: Double) = QuickEye(se, -100.0 / se, QuickTarget.WHITE)
    private val straight = ScreeningResults(amsler = listOf(
        AmslerEyeResult(Eye.RIGHT, false, emptyList(), 12, 20, 300.0), AmslerEyeResult(Eye.LEFT, false, emptyList(), 12, 20, 300.0)))

    // ---- 1. both eyes ----

    @Test fun `1 - both eyes are always listed, an eye without a reading carries its reason`() {
        val q = QuickEstimate(right = eye(-4.75), left = null, leftNotMeasured = NotMeasured.SKIPPED)
        assertEquals(listOf(Eye.RIGHT, Eye.LEFT), q.bothEyes.map { it.first })
        assertNull(q.bothEyes[0].third)
        assertNull(q.bothEyes[1].second); assertEquals(NotMeasured.SKIPPED, q.bothEyes[1].third)
        // a session stored before the reason existed still lists the eye
        assertEquals(NotMeasured.NOT_DONE, QuickEstimate(right = eye(-1.0)).bothEyes[1].third)
        assertEquals(listOf(NotMeasured.NOT_DONE, NotMeasured.NOT_DONE), QuickEstimate().bothEyes.map { it.third })
    }

    // ---- 2. lens-assisted ----

    @Test fun `2 - the suggested lens is about 2 D weaker than the first pass, from rules_json`() {
        assertEquals(2.0, lens.suggestOffsetD, 0.0)
        assertEquals(-2.5, LensAssisted.suggestLens(-4.75, lens), 1e-9)
        var se = lens.triggerSeD
        while (se >= -12.0) {
            val weaker = LensAssisted.suggestLens(se, lens) - se
            assertTrue("$se: the lens is $weaker D weaker", weaker >= lens.suggestOffsetD - 1e-9 && weaker < lens.suggestOffsetD + lens.suggestStepD)
            se -= 0.25
        }
    }

    @Test fun `2 - a residual still at minus 3_75 or beyond asks for a stronger lens, once`() {
        assertEquals(-3.75, lens.repeatIfResidualFromSeD, 0.0); assertEquals(1, lens.maxStrongerRepeats)
        assertTrue(LensAssisted.needsStrongerLens(-3.75, 0, lens))
        assertTrue(LensAssisted.needsStrongerLens(-4.5, 0, lens))
        assertFalse("a residual around 50 cm is what the mode is for", LensAssisted.needsStrongerLens(-2.0, 0, lens))
        assertFalse(LensAssisted.needsStrongerLens(-3.5, 0, lens))
        assertFalse("only one repeat", LensAssisted.needsStrongerLens(-4.5, 1, lens))
        assertFalse(LensAssisted.needsStrongerLens(null, 0, lens))
        // -1.50 lens, residual -4.00: the eye is about -5.50, so the next lens is -3.50 (about 2 D weaker again)
        assertEquals(-3.5, LensAssisted.strongerLens(-5.5, -1.5, lens), 1e-9)
        // never the same or a weaker lens than the one that was too weak
        assertEquals(-2.5, LensAssisted.strongerLens(-3.75, -2.0, lens), 1e-9)
    }

    // ---- 4. contrast ----

    @Test fun `4 - failing even the 100 percent step is 'not valid', never Reduced and never a cataract note`() {
        assertEquals("the first step is the validity check", 100.0, contrast.stepsPercent.first(), 0.0)
        assertEquals("100 % letters are black on white", 0, Contrast.letterGrey(100.0))
        val failed = ContrastRun(contrast).answer(false).answer(false)
        assertTrue(failed.done)
        val r = failed.result(Eye.RIGHT)
        assertTrue(r.invalid); assertFalse(r.reduced); assertFalse(r.reducedValid)
        assertFalse(Contrast.eyeHealthNote(r.reducedValid, 70, true, contrast))
        assertFalse(ScreeningResults(contrast = listOf(r)).contrastReduced)
        // a result stored by the earlier build (reduced = true with no step passed) is not counted as reduced either
        val old = ContrastEyeResult(Eye.LEFT, null, reduced = true)
        assertTrue(old.invalid); assertFalse(old.reducedValid)
        assertFalse(ScreeningResults(contrast = listOf(old)).contrastReduced)
        // a valid reduced result is unchanged
        val six = (1..10).fold(ContrastRun(contrast)) { run, _ -> run.answer(true) }.answer(false).answer(false).result(Eye.LEFT)
        assertFalse(six.invalid); assertTrue(six.reducedValid)
    }

    @Test fun `4 - a power of 1_00 D or more must wear the correction for the contrast test`() {
        assertEquals(1.0, contrast.wearCorrectionFromAbsD, 0.0)
        assertTrue(Contrast.needsCorrection(-1.0, contrast)); assertTrue(Contrast.needsCorrection(-4.75, contrast))
        assertTrue(Contrast.needsCorrection(1.25, contrast))
        assertFalse(Contrast.needsCorrection(-0.75, contrast)); assertFalse(Contrast.needsCorrection(null, contrast))
    }

    // ---- 5. rings ----

    @Test fun `5 - the leisure label follows the score - 17 is Low, not Medium`() {
        val vp = rules.visionProfile
        val oneHour = VisionProfile.from(ProfileInputs(outdoorHours = 1.0), vp).leisure
        assertEquals(17, oneHour.score); assertEquals("LOW", oneHour.label)
        // for every answer the label is the one the score's thresholds give, and it never goes down as the score goes up
        val rings = buildList {
            for (half in 0..20) for (acts in listOf(emptySet(), setOf("OUTDOOR_SPORT"), setOf("WATER", "FARMING_FIELD")))
                add(VisionProfile.from(ProfileInputs(outdoorHours = half / 2.0, leisure = acts), vp).leisure)
        }
        rings.forEach { assertEquals("score ${it.score}", VisionProfile.leisureLabel(it.score, vp).name, it.label) }
        val order = listOf("LOW", "MEDIUM", "HIGH")
        rings.sortedBy { it.score }.zipWithNext().forEach { (a, b) -> assertTrue("${a.score} ${a.label} then ${b.score} ${b.label}", order.indexOf(a.label) <= order.indexOf(b.label)) }
        assertTrue(rings.filter { it.label == "MEDIUM" }.all { it.score in vp.leisure.mediumFromScore until vp.leisure.highFromScore })
        assertTrue(rings.filter { it.label == "HIGH" }.all { it.score >= vp.leisure.highFromScore })
    }

    // ---- 7. Amsler red flag ----

    @Test fun `7 - the grid red flag comes only from a grid result and names the eye`() {
        val cfg = rules.safety
        val none = SurveyAnswers()
        // report S32: both grids straight, so there is no grid flag whatever was answered or stored before
        val s32 = Safety.gate(InterviewAnswers(age = 50, straightLinesBent = true), none, cfg, tests = VisionTests(amslerAbnormal = true), screening = straight)
        assertFalse(RedFlag.AMSLER_DISTORTION in s32.redFlags)
        assertTrue(s32.amslerEyes.isEmpty())
        // the customer's own answer is kept as its own flag, so the referral is not lost
        assertEquals(listOf(RedFlag.BENT_LINES_REPORTED), s32.redFlags)
        assertEquals(emptyList<RedFlag>(), Safety.gate(InterviewAnswers(age = 50), none, cfg, screening = straight).redFlags)

        val left = straight.copy(amsler = listOf(straight.amsler[0], AmslerEyeResult(Eye.LEFT, true, listOf(AmslerCell(2, 3)), 12, 20, 300.0)))
        val g = Safety.gate(InterviewAnswers(age = 62), none, cfg, screening = left)
        assertEquals(listOf(RedFlag.AMSLER_DISTORTION), g.redFlags)
        assertEquals(listOf(Eye.LEFT), g.amslerEyes)
        val both = ScreeningResults(amsler = Eye.entries.map { AmslerEyeResult(it, true, emptyList(), 12, 20) })
        assertEquals(listOf(Eye.RIGHT, Eye.LEFT), Safety.gate(InterviewAnswers(), none, cfg, screening = both).amslerEyes)
        assertFalse("the grid flag alone is not an emergency sign", g.emergency)
    }

    // ---- 9. optometrist-only power ----

    @Test fun `9 - URGENT and CHILD_MYOPIA reports label the power for the optometrist only`() {
        val cfg = rules.safety
        val urgent = Safety.gate(InterviewAnswers(age = 40, redFlagOther = true), SurveyAnswers(), cfg)
        assertEquals(ReportTag.URGENT, urgent.tag); assertTrue(urgent.powerForOptometristOnly)
        val child = Safety.gate(InterviewAnswers(age = 12), SurveyAnswers(), cfg, quick = QuickEstimate(eye(-1.5), eye(-1.25)))
        assertEquals(ReportTag.CHILD_MYOPIA, child.tag); assertTrue(child.powerForOptometristOnly)
        val routine = Safety.gate(InterviewAnswers(age = 40), SurveyAnswers(), cfg, quick = QuickEstimate(eye(-1.5), eye(-1.25)))
        assertEquals(ReportTag.ROUTINE, routine.tag); assertFalse(routine.powerForOptometristOnly)
    }
}
