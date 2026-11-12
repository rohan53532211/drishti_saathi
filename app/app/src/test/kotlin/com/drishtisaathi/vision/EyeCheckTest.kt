package com.drishtisaathi.vision

import com.drishtisaathi.camera.InstructionPacer
import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.DrishtiEngine
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.PowerKnown
import com.drishtisaathi.engine.QuickEstimate
import com.drishtisaathi.engine.QuickEye
import com.drishtisaathi.engine.QuickTarget
import com.drishtisaathi.engine.ReferralLevel
import com.drishtisaathi.engine.ReferralReason
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.Source
import com.drishtisaathi.engine.VisionProblem
import com.drishtisaathi.engine.anamnesis.QuickEstimator
import com.drishtisaathi.interview.Questions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The guided customer eye check: which tests run, the one automatic repeat, and what reaches the engine. */
class EyeCheckTest {
    private val a = "src/main/assets/"
    private val rules = RulesConfig.parse(File(a + "rules.json").readText())
    private val est = QuickEstimator(rules)
    private val engine by lazy {
        DrishtiEngine.fromJson(File(a + "rules.json").readText(), File(a + "anthro_priors.json").readText(),
            File(a + "frames.json").readText(), File(a + "frames/more_frames.json").readText())
    }
    private fun answers(p: VisionProblem, age: Int) =
        Questions.setVisionProblem(InterviewAnswers(age = age), p).copy(powerKnown = PowerKnown.NOT_SURE)
    private fun steps(p: VisionProblem, age: Int, primary: Boolean = true) =
        eyeCheckSteps(est.plan(answers(p, age)), primary).map { it.toString() }
    private fun r(mm: Double) = DistanceReading(mm, Confidence.HIGH)

    @Test fun `far - per eye power, line and grid check, right eye first, no reading check under 38`() {
        assertEquals(
            listOf("Power(eye=RIGHT)", "Dial(eye=RIGHT)", "Grid(eye=RIGHT)", "Power(eye=LEFT)", "Dial(eye=LEFT)", "Grid(eye=LEFT)", "Done"),
            steps(VisionProblem.DISTANCE, 30),
        )
    }

    @Test fun `both and 38+ - the reading check runs after the far power check`() {
        val s = steps(VisionProblem.BOTH, 45)
        assertEquals("Near", s[s.size - 2])
        assertTrue(s.indexOf("Near") > s.indexOf("Power(eye=LEFT)"))
        assertFalse("Near" in steps(VisionProblem.BOTH, 30))
    }

    @Test fun `near and 38+ - grid per eye then the reading check, under 38 nothing runs`() {
        assertEquals(listOf("Grid(eye=RIGHT)", "Grid(eye=LEFT)", "Near", "Done"), steps(VisionProblem.NEAR, 50))
        assertFalse(est.plan(answers(VisionProblem.NEAR, 30)).runsAnything)
        assertTrue(est.plan(answers(VisionProblem.NEAR, 30)).possibleLongSight)
    }

    @Test fun `the comparison with a known power is the power check only`() {
        assertEquals(listOf("Power(eye=RIGHT)", "Power(eye=LEFT)", "Done"), steps(VisionProblem.DISTANCE, 30, primary = false))
    }

    @Test fun `a reading that looks invalid is repeated once and the median is used`() {
        val p = QuickFarProcedure().onReading(r(120.0), rules, nowMs = 1_000)
        assertTrue(p.looksInvalid(400.0, nowMs = 1_800))      // tapped too soon after being close
        assertFalse(p.looksInvalid(400.0, nowMs = 6_000))
        assertTrue(p.looksInvalid(110.0, nowMs = 6_000))      // closer than 12.5 cm
        val first = p.blur(r(400.0), est, QuickTarget.WHITE)
        val again = p.repeatWith(first)
        assertEquals(first, again.first)
        assertFalse(again.seenClose)
        val m = QuickFarProcedure.median(first, QuickEye(-2.0, 50.0, QuickTarget.WHITE), est, QuickTarget.WHITE)
        assertEquals(44.0, m.blurAtCm!!, 1e-9)
        assertEquals(-2.25, m.seD!!, 1e-9)
        // the repeat stayed sharp to arm's length: that wins
        val clear = QuickFarProcedure.median(first, p.stillClear(QuickTarget.WHITE), est, QuickTarget.WHITE)
        assertTrue(clear.noMyopiaAtReach)
    }

    @Test fun `line check flags astigmatism, the phone estimate stays the power source`() {
        val dial = mapOf(TestEye.RIGHT to ClockDialAnalysis.analyse(TestEye.RIGHT, listOf(2), 30)!!)
        val equal = mapOf(TestEye.LEFT to ClockDialAnalysis.analyse(TestEye.LEFT, listOf(null), 30)!!)
        val q = QuickEstimate(QuickEye(-2.5, 40.0, QuickTarget.WHITE), QuickEye(-2.25, 44.0, QuickTarget.WHITE), primary = true)
        val t = eyeCheckTests(dial + equal, emptyMap(), q.right, q.left)
        assertTrue(t.right!!.astigmatismFlag)
        assertNull(t.left)
        val result = engine.evaluate(answers(VisionProblem.DISTANCE, 30), tests = t, quick = q)
        assertEquals(Source.PHONE_ESTIMATE, result.assessment.advice!!.powerSource)
        assertTrue(ReferralReason.ASTIGMATISM_FLAG in result.assessment.referral.reasons)
    }

    @Test fun `wavy grid lines mean an urgent referral`() {
        val grid = mapOf(TestEye.LEFT to AmslerAnalysis.analyse(TestEye.LEFT, listOf(true), emptyList(), rules.visionTests.copy(repeats = 1))!!)
        val t = eyeCheckTests(emptyMap(), grid, null, null)
        assertTrue(t.amslerAbnormal)
        val result = engine.evaluate(answers(VisionProblem.DISTANCE, 30), tests = t)
        assertEquals(ReferralLevel.URGENT, result.assessment.referral.level)
        assertTrue(ReferralReason.AMSLER_DISTORTION in result.assessment.referral.reasons)
    }

    @Test fun `instructions are spoken once, a repeat waits 8 seconds, a new one goes at once`() {
        var now = 0L
        val p = InstructionPacer(clock = { now })
        assertTrue(p.allow("move away"))
        now = 3_000; assertFalse(p.allow("move away"))
        now = 4_000; assertTrue(p.allow("blur at 28 cm"))
        now = 8_000; assertFalse(p.allow("blur at 28 cm"))
        now = 12_001; assertTrue(p.allow("blur at 28 cm"))
    }

    @Test fun `the face hint waits 5 seconds`() = assertEquals(5_000L, FACE_HINT_AFTER_MS)
}
