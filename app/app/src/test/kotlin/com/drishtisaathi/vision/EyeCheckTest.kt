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
    private fun plan(a: InterviewAnswers) = EyeCheckPlan.from(a, est.plan(a))
    private fun steps(p: VisionProblem, age: Int, primary: Boolean = true) =
        eyeCheckSteps(plan(answers(p, age)), primary).map { it.toString() }
    private fun r(mm: Double) = DistanceReading(mm, Confidence.HIGH)

    @Test fun `far - per eye power then two-line test, right eye first, no grid under 40`() {
        assertEquals(
            listOf("Power(eye=RIGHT)", "Lines(eye=RIGHT)", "Power(eye=LEFT)", "Lines(eye=LEFT)", "Done"),
            steps(VisionProblem.DISTANCE, 30),
        )
    }

    @Test fun `40+ adds the grid per eye, both adds the reading check last`() {
        assertEquals(
            listOf("Power(eye=RIGHT)", "Lines(eye=RIGHT)", "Grid(eye=RIGHT)", "Power(eye=LEFT)", "Lines(eye=LEFT)", "Grid(eye=LEFT)", "Near", "Done"),
            steps(VisionProblem.BOTH, 45),
        )
        assertEquals("Near", steps(VisionProblem.BOTH, 30).let { it[it.size - 2] })
    }

    @Test fun `near only - reading check at any age, grid from 40`() {
        assertEquals(listOf("Grid(eye=RIGHT)", "Grid(eye=LEFT)", "Near", "Done"), steps(VisionProblem.NEAR, 50))
        assertEquals(listOf("Near", "Done"), steps(VisionProblem.NEAR, 30))
        assertTrue(plan(answers(VisionProblem.NEAR, 30)).runsAnything)
    }

    @Test fun `grid only when lines look bent, 40+, or diabetes`() {
        val young = answers(VisionProblem.DISTANCE, 30)
        assertFalse(EyeCheckPlan.gridNeeded(young))
        assertTrue(EyeCheckPlan.gridNeeded(young.copy(straightLinesBent = true)))
        assertTrue(EyeCheckPlan.gridNeeded(young.copy(diabetesOrBp = true)))
        assertTrue(EyeCheckPlan.gridNeeded(answers(VisionProblem.DISTANCE, 40)))
        assertFalse(EyeCheckPlan.gridNeeded(young.copy(straightLinesBent = false, diabetesOrBp = false)))
        assertEquals(listOf("Grid(eye=RIGHT)", "Grid(eye=LEFT)", "Done"),
            eyeCheckSteps(plan(young.copy(diabetesOrBp = true)), primary = false, gridOnly = true).map { it.toString() })
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

    @Test fun `two-line test - a 0_75 D difference flags astigmatism with cyl and axis`() {
        // horizontal blurs at 40 cm (-2.50), vertical at 80 cm (-1.25): 1.25 cyl, horizontal first -> axis 180
        val a = TwoLineAstig.analyse(400.0, 800.0)
        assertTrue(a.flagged); assertEquals(1.25, a.cylD, 1e-9); assertEquals(180, a.axisDeg)
        // vertical blurs first -> axis 90
        assertEquals(90, TwoLineAstig.analyse(800.0, 400.0).axisDeg)
        // exactly 0.75 D apart (50 cm vs 80 cm = 2.00 vs 1.25) flags; rounding to 0.25
        assertTrue(TwoLineAstig.analyse(500.0, 800.0).flagged)
        assertEquals(1.0, TwoLineAstig.analyse(480.0, 1000.0).cylD, 1e-9) // 2.08 - 1.00 = 1.08 -> 1.00
        assertEquals(1.25, TwoLineAstig.analyse(450.0, 1000.0).cylD, 1e-9) // 1.22 -> 1.25
        // still sharp at arm's length counts as 0 D
        val sharp = TwoLineAstig.analyse(null, 500.0)
        assertTrue(sharp.flagged); assertEquals(2.0, sharp.cylD, 1e-9); assertEquals(90, sharp.axisDeg)
    }

    @Test fun `two-line test - lines blurring together give no flag`() {
        val r = TwoLineAstig.analyse(400.0, 450.0)  // 2.50 vs 2.22
        assertFalse(r.flagged); assertNull(r.axisDeg)
        assertFalse(TwoLineAstig.analyse(null, null).flagged)
    }

    @Test fun `two-line flag reaches the engine, the phone estimate stays the power source`() {
        val lines = mapOf(TestEye.RIGHT to TwoLineAstig.analyse(400.0, 800.0), TestEye.LEFT to TwoLineAstig.analyse(440.0, 460.0))
        val q = QuickEstimate(QuickEye(-2.5, 40.0, QuickTarget.WHITE), QuickEye(-2.25, 44.0, QuickTarget.WHITE), primary = true)
        val t = eyeCheckTests(lines, emptyMap(), q.right, q.left)
        assertTrue(t.right!!.astigmatismFlag)
        assertEquals(180, t.right!!.astigmatismAxisDeg)
        assertNull(t.left)
        val withCyl = withCylinder(q.right, lines[TestEye.RIGHT])!!
        assertEquals(-1.25, withCyl.cylD!!, 1e-9); assertEquals(180, withCyl.cylAxisDeg)
        assertNull(withCylinder(q.left, lines[TestEye.LEFT])!!.cylD)
        val result = engine.evaluate(answers(VisionProblem.DISTANCE, 30), tests = t, quick = q.copy(right = withCyl))
        assertEquals(Source.PHONE_ESTIMATE, result.assessment.advice!!.powerSource)
        assertTrue(ReferralReason.ASTIGMATISM_FLAG in result.assessment.referral.reasons)
    }

    @Test fun `reading check 38+ - add rounded to 0_25 and clamped to 0_75 to 3_00`() {
        val a = answers(VisionProblem.NEAR, 50)
        val r = NearCheck.analyse(500.0, 50, null, a, rules)
        assertEquals(50.0, r.nearPointCm, 0.0)
        val add = r.addD!!
        assertTrue(add in 0.75..3.0)
        assertEquals(0.0, (add / 0.25) - Math.round(add / 0.25), 1e-9)
        assertNull(r.expectedNearPointCm); assertFalse(r.fartherThanExpected)
        assertEquals(0.75, NearCheck.analyse(100.0, 50, null, a, rules).addD!!, 1e-9)   // very close: clamped low
        assertTrue(NearCheck.analyse(5000.0, 70, null, a, rules).addD!! in 2.0..3.0)   // very far: high, never above +3.00
        assertTrue(NearCheck.analyse(800.0, 50, null, a, rules).addD!! >= add)          // farther near point, more add
    }

    @Test fun `reading check under 38 - no plus number, near point against the age-expected one`() {
        val a = answers(VisionProblem.NEAR, 20)
        val vm = com.drishtisaathi.engine.vision.VisionMath(rules)
        val far = NearCheck.analyse(500.0, 20, null, a, rules)          // amplitude 2 D at 20: far too little
        assertNull(far.addD)
        assertTrue(far.fartherThanExpected)
        assertEquals(Math.round(100.0 / vm.hofstetterMean(20)).toDouble(), far.expectedNearPointCm!!, 1e-9)
        val normal = NearCheck.analyse(70.0, 20, null, a, rules)        // 7 cm: about 14 D
        assertNull(normal.addD)
        assertFalse(normal.fartherThanExpected)
        assertFalse(NearCheck.analyse(500.0, null, null, a, rules).fartherThanExpected)
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

/** Precision mode letter chart: hard cap, two misses at one size, "Can't see" is a miss, always a result. */
class LetterChartLimitsTest {
    private val cfg = RulesConfig.parse(File("src/main/assets/rules.json").readText()).visionTests

    @Test fun `a customer who sees nothing still ends with a result within 12 answers`() {
        var a = AcuityProcedure.start(cfg, EDirection.UP)
        var n = 0
        while (!a.isDone) { a = a.answer(null, EDirection.LEFT, Confidence.HIGH); n++ }
        assertTrue(n <= AcuityProcedure.MAX_TRIALS)
        assertTrue(a.thresholds.isNotEmpty())
    }

    @Test fun `two misses at one size end that staircase at the smallest size read`() {
        var a = AcuityProcedure.start(cfg, EDirection.UP)
        a = a.answer(EDirection.UP, EDirection.UP, Confidence.HIGH)            // 0.7 read → 0.5
        val lvl = a.staircase.level
        a = a.answer(null, EDirection.UP, Confidence.HIGH)                      // miss at 0.5 → bigger
        while (a.staircase.level != lvl) a = a.answer(a.shown, EDirection.UP, Confidence.HIGH)
        a = a.answer(null, EDirection.UP, Confidence.HIGH)                      // second miss at 0.5
        assertEquals(listOf(0.6), a.thresholds)                                // 0.6 was read on the way back
    }

    @Test fun `a guesser is stopped by the cap`() {
        var a = AcuityProcedure.start(cfg, EDirection.UP)
        val rnd = kotlin.random.Random(3)
        var n = 0
        while (!a.isDone) { a = a.answer(EDirection.random(rnd), EDirection.random(rnd), Confidence.HIGH); n++ }
        assertTrue(n <= 12)
    }
}

/** Power check BLUR: always enabled, distance from the readings of the last second, relaxed camera settings. */
class BlurTapTest {
    private fun r(mm: Double, t: Long, face: Boolean = true) = DistanceReading(mm, Confidence.HIGH, face, timestampMs = t)

    @Test fun `tap uses the median of the last second`() {
        val b = DistanceBuffer()
        b.add(r(200.0, 0)); b.add(r(260.0, 900)); b.add(r(280.0, 1_200)); b.add(r(300.0, 1_500))
        assertEquals(280.0, b.atTap(1_600)!!, 1e-9)     // 0 ms and 900 ms… only 900+ are within 1 s of 1600
        assertTrue(b.live(1_600))
    }

    @Test fun `face-lost frames are ignored and nothing in 1_5 s means not caught`() {
        val b = DistanceBuffer()
        b.add(r(300.0, 0)); b.add(r(999.0, 500, face = false))
        assertEquals(300.0, b.atTap(1_400)!!, 1e-9)     // older than 1 s but within 1.5 s
        assertNull(b.atTap(1_600))
        assertFalse(b.live(1_600))
    }

    @Test fun `eye check camera tolerates a covered eye, head turn up to 10 degrees and motion blur`() {
        val c = powerCheckCamera(RulesConfig.parse(File("src/main/assets/rules.json").readText()).camera)
        assertTrue(c.maxAbsYawDeg >= 10.0 && c.maxAbsRollDeg >= 10.0)
        assertEquals(0.0, c.minLaplacianVariance, 0.0)
        assertTrue(c.minFacePresenceConfidence <= 0.3 && c.minFaceDetectionConfidence <= 0.3)
    }
}
