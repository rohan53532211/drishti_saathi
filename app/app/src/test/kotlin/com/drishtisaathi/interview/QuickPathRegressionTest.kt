package com.drishtisaathi.interview

import com.drishtisaathi.UiState
import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.DrishtiEngine
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.PowerKnown
import com.drishtisaathi.engine.QuickEstimate
import com.drishtisaathi.engine.QuickEye
import com.drishtisaathi.engine.QuickTarget
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.Source
import com.drishtisaathi.engine.VisionProblem
import com.drishtisaathi.engine.anamnesis.QuickEstimator
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.pdf.PdfContent
import com.drishtisaathi.ui.PowerPathText
import com.drishtisaathi.vision.DistanceReading
import com.drishtisaathi.vision.QuickFarProcedure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Phone-test bug (12 Nov): the quick estimate must run right after "Not sure" / "No" and be the power source. */
class QuickPathRegressionTest {
    private val a = "src/main/assets/"
    private val rules = RulesConfig.parse(File(a + "rules.json").readText())
    private val engine = DrishtiEngine.fromJson(File(a + "rules.json").readText(), File(a + "anthro_priors.json").readText(),
        File(a + "frames.json").readText(), File(a + "frames/more_frames.json").readText())
    private val est = QuickEstimator(rules)

    private fun answers(problem: VisionProblem, age: Int, pk: PowerKnown) = Questions.setVisionProblem(
        InterviewAnswers(age = age, wearsGlasses = false, redFlagFlashes = false, redFlagOther = false, powerKnown = pk), problem,
    )

    @Test fun `not sure or no with a distance problem runs the far point for both eyes`() {
        for (pk in listOf(PowerKnown.NOT_SURE, PowerKnown.NO)) for (p in listOf(VisionProblem.DISTANCE, VisionProblem.BOTH)) {
            val plan = est.plan(answers(p, 30, pk))
            assertTrue("$pk $p", plan.far)
        }
    }

    @Test fun `near only runs the near point from 38, and nothing before`() {
        assertTrue(est.plan(answers(VisionProblem.NEAR, 38, PowerKnown.NO)).near)
        assertFalse(est.plan(answers(VisionProblem.NEAR, 38, PowerKnown.NO)).far)
        val young = est.plan(answers(VisionProblem.NEAR, 30, PowerKnown.NO))
        assertFalse(young.runsAnything)
        assertTrue(young.possibleLongSight)
    }

    @Test fun `the power question is shown when the quick path applies (staff mode plays no part)`() {
        val ans = answers(VisionProblem.DISTANCE, 30, PowerKnown.NOT_SURE).copy(powerKnown = null)
        assertTrue(Questions.visible(ans).any { it.id == Questions.POWER_KNOWN })
    }

    @Test fun `the live line shows from the first reading and blur still needs the phone to have been close`() {
        val p = QuickFarProcedure()
        val r = DistanceReading(400.0, Confidence.HIGH)
        assertEquals(-2.5, p.previewSeD(r, est)!!, 1e-9)
        assertFalse(p.canBlur(r, rules))
        assertNull(p.previewSeD(r.copy(faceVisible = false), est))
    }

    @Test fun `the frozen estimate is the power source for lenses and frames, and is on the summary and shop PDF`() {
        val ans = answers(VisionProblem.DISTANCE, 30, PowerKnown.NOT_SURE)
        val q = QuickEstimate(QuickEye(-2.5, 40.0, QuickTarget.WHITE), QuickEye(-2.25, 44.0, QuickTarget.WHITE), primary = true)
        val result = engine.evaluate(ans, quick = q)
        val adv = result.assessment.advice!!
        assertEquals(Source.PHONE_ESTIMATE, adv.powerSource)
        assertEquals(-2.5, adv.eyes.first { it.eye == com.drishtisaathi.engine.Eye.RIGHT }.startingTrialLens.value.sph, 1e-9)
        assertTrue(result.frames.isNotEmpty())

        val state = UiState(lang = Lang.EN, answers = ans, quick = q, result = result)
        val lines = PowerPathText.quickLines(state).map { it.en }
        assertTrue(lines.toString(), lines.any { it.contains("Blur at 40 cm, about −2.50 D (approximate, confirm with trial lens)") })
        val ctx = ChoiceContext(rules.frameScoring.budgetMaxPriceInrLow, rules.frameScoring.budgetMaxPriceInrMid)
        val pdf = PdfContent.shopCopy(state, rules, ctx, Lang.EN).toString()
        assertTrue(pdf.contains("Blur at 40 cm, about −2.50 D"))
        assertTrue(pdf.contains(PowerPathText.sourceLine(adv)!!.en))
    }
}
