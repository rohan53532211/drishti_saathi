package com.drishtisaathi.engine.anamnesis

import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.DrishtiEngine
import com.drishtisaathi.engine.EyeRx
import com.drishtisaathi.engine.FrameCatalog
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.KnownRx
import com.drishtisaathi.engine.LensType
import com.drishtisaathi.engine.MeasuredEye
import com.drishtisaathi.engine.NearDistance
import com.drishtisaathi.engine.Note
import com.drishtisaathi.engine.Occupation
import com.drishtisaathi.engine.PowerKnown
import com.drishtisaathi.engine.QuickEstimate
import com.drishtisaathi.engine.QuickEye
import com.drishtisaathi.engine.QuickTarget
import com.drishtisaathi.engine.ReadingFrequency
import com.drishtisaathi.engine.ReferralLevel
import com.drishtisaathi.engine.ScreenHours
import com.drishtisaathi.engine.Source
import com.drishtisaathi.engine.TestAssets
import com.drishtisaathi.engine.VisionProblem
import com.drishtisaathi.engine.VisionTests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickEstimatorTest {
    private val rules = TestAssets.rules
    private val q = QuickEstimator(rules)

    @Test fun `40 cm white gives -2_50 D`() {
        assertEquals(-2.50, q.liveSeD(0.40, QuickTarget.WHITE), 1e-9)
    }

    @Test fun `31_5 cm blue gives about -2_50 D`() {
        // -1/0.315 + 0.67 = -2.505 → -2.50 after 0.25 D rounding
        assertEquals(-2.50, q.liveSeD(0.315, QuickTarget.BLUE), 1e-9)
    }

    @Test fun `live line rounds to 0_25 D and uses the configured target`() {
        assertEquals(QuickTarget.WHITE, rules.quickEstimate.target)
        assertEquals(-1.0, q.liveSeD(1.0), 1e-9)
        assertEquals(-4.0, q.liveSeD(0.25), 1e-9)
        assertEquals(-3.25, q.liveSeD(0.30), 1e-9) // -3.33 → -3.25
        listOf(0.2, 0.33, 0.47, 0.6).forEach { d -> assertEquals(0.0, (q.liveSeD(d) / 0.25) % 1.0, 1e-9) }
    }

    @Test fun `plans by problem and age`() {
        assertEquals(QuickPlan(far = true, near = false, possibleLongSight = false), q.plan(VisionProblem.DISTANCE, 25))
        assertEquals(QuickPlan(far = false, near = true, possibleLongSight = false), q.plan(VisionProblem.NEAR, 38))
        assertEquals(QuickPlan(far = true, near = true, possibleLongSight = false), q.plan(VisionProblem.BOTH, 50))
        assertEquals(QuickPlan(far = true, near = false, possibleLongSight = false), q.plan(VisionProblem.BOTH, 30))
        assertFalse(q.plan(VisionProblem.NONE, 50).runsAnything)
    }

    @Test fun `under-38 near-only gives no plus estimate`() {
        val plan = q.plan(VisionProblem.NEAR, 37)
        assertFalse(plan.near)
        assertTrue(plan.possibleLongSight)
        assertNull(q.nearAdd(0.5, null, InterviewAnswers(age = 37, visionProblem = VisionProblem.NEAR)))
    }

    @Test fun `near add from age 38 with the usual reading distance`() {
        val a = InterviewAnswers(age = 55, visionProblem = VisionProblem.NEAR, nearDistance = NearDistance.NORMAL)
        assertEquals(0.35, q.workingDistanceM(a), 1e-9)
        val add = q.nearAdd(0.70, null, a)!!
        assertTrue("add $add", add in 1.0..3.0)
        assertEquals(0.0, (add / 0.25) % 1.0, 1e-9)
    }

    @Test fun `red flag stops the power path`() {
        val a = InterviewAnswers(age = 50, visionProblem = VisionProblem.BOTH, redFlagFlashes = true, powerKnown = PowerKnown.NO)
        assertFalse(q.plan(a).runsAnything)
        assertFalse(q.offerCompare(a.copy(powerKnown = PowerKnown.YES, glassesAgeYears = 5)))
    }

    @Test fun `compare offered for old glasses or blur with current glasses`() {
        val base = InterviewAnswers(age = 45, wearsGlasses = true, visionProblem = VisionProblem.NONE, powerKnown = PowerKnown.YES, glassesAgeYears = 1)
        assertFalse(q.offerCompare(base)) // NONE: no far point to compare
        val distance = base.copy(visionProblem = VisionProblem.DISTANCE)
        assertTrue(q.offerCompare(distance)) // still blur with current glasses
        assertTrue(q.offerCompare(distance.copy(wearsGlasses = false, glassesAgeYears = 3))) // older than 2 years
        assertFalse(q.offerCompare(distance.copy(wearsGlasses = false, glassesAgeYears = 2)))
        assertFalse(q.offerCompare(distance.copy(powerKnown = PowerKnown.NOT_SURE)))
    }
}

class PowerPathEngineTest {
    private val engine = DrishtiEngine(TestAssets.rules, TestAssets.anthro, FrameCatalog.parse(TestAssets.text("frames.json")))
    private val myope = InterviewAnswers(age = 25, visionProblem = VisionProblem.DISTANCE, distanceBlur = true, nearBlur = false)

    @Test fun `YES path uses the entered power, the quick check is only shown next to it`() {
        val a = myope.copy(powerKnown = PowerKnown.YES, wearsGlasses = true, glassesAgeYears = 3,
            knownRx = KnownRx(EyeRx(-3.0, -0.5, 180), EyeRx(-2.75)))
        val compare = QuickEstimate(QuickEye(-2.0, 50.0, QuickTarget.WHITE), QuickEye(-2.0, 50.0, QuickTarget.WHITE), primary = false)
        // even a staff full test does not replace the customer's known power
        val tests = VisionTests(MeasuredEye(-1.0, Confidence.HIGH), MeasuredEye(-1.0, Confidence.HIGH))
        val adv = engine.evaluate(a, tests = tests, quick = compare).assessment.advice!!
        assertEquals(Source.KNOWN_POWER, adv.powerSource)
        val r = adv.eyes.first { it.eye == com.drishtisaathi.engine.Eye.RIGHT }
        assertEquals(-3.0, r.startingTrialLens.value.sph, 0.0)
        assertEquals(-0.5, r.startingTrialLens.value.cyl, 0.0)
        assertEquals(-2.0, r.phoneEstimateSeD!!, 0.0)
        assertEquals(-3.5, r.thicknessPowerD, 0.0) // strongest meridian of the known power drives thickness
    }

    @Test fun `NOT SURE path stores and uses the estimate for lens and frames`() {
        val a = myope.copy(powerKnown = PowerKnown.NOT_SURE)
        val quick = QuickEstimate(QuickEye(-2.5, 40.0, QuickTarget.WHITE), QuickEye(-2.25, 44.0, QuickTarget.WHITE))
        val res = engine.evaluate(a, quick = quick)
        val adv = res.assessment.advice!!
        assertEquals(Source.PHONE_ESTIMATE, adv.powerSource)
        assertEquals(listOf(-2.5, -2.25), adv.eyes.map { it.startingTrialLens.value.sph })
        assertEquals(listOf(-2.5, -2.25), adv.eyes.map { it.thicknessPowerD })
        assertTrue(adv.eyes.all { it.band.confidence == Confidence.LOW })
        assertEquals(-3.25, adv.eyes[0].band.value.lowD, 1e-9) // ± quickEstimate.bandHalfWidthD
        assertTrue(Note.PHONE_ESTIMATE_APPROXIMATE in res.assessment.notes)
        assertEquals(LensType.SINGLE_VISION, adv.lensType.value)
        // frame thickness is computed from the stored estimate
        val withoutPower = engine.evaluate(a).frames.first().thickestMm
        assertNotNull(res.frames.first().thickestMm)
        assertNull(withoutPower)
    }

    @Test fun `NO path - still clear at arm's length gives no power and a trial-lens note`() {
        val a = myope.copy(powerKnown = PowerKnown.NO)
        val quick = QuickEstimate(QuickEye(null, null, QuickTarget.WHITE, noMyopiaAtReach = true), QuickEye(null, null, QuickTarget.WHITE, noMyopiaAtReach = true))
        val res = engine.evaluate(a, quick = quick).assessment
        assertTrue(res.advice!!.eyes.isEmpty())
        assertTrue(Note.NO_MYOPIA_AT_ARMS_LENGTH in res.notes)
        assertTrue(Note.HYPEROPIA_POSSIBLE in res.notes)
        assertTrue(res.referral.reasons.any { it.name == "DISTANCE_BLUR_NO_MYOPIA_FOUND" })
    }

    @Test fun `quick near add is used from 38 and flagged as phone estimate`() {
        val a = InterviewAnswers(age = 39, visionProblem = VisionProblem.NEAR, distanceBlur = false, nearBlur = true, powerKnown = PowerKnown.NO)
        val adv = engine.evaluate(a, quick = QuickEstimate(addD = 1.25, nearPointCm = 60.0)).assessment.advice!!
        assertEquals(LensType.READING, adv.lensType.value)
        assertEquals(Source.PHONE_ESTIMATE, adv.add!!.source)
        assertEquals(1.25, adv.add!!.value.lowD, 0.0)
        assertTrue(adv.confirmAddWithReadyReader)
    }

    @Test fun `under-38 near-only - no plus power, possible long-sightedness, hand test`() {
        val a = InterviewAnswers(age = 30, visionProblem = VisionProblem.NEAR, distanceBlur = false, nearBlur = true, powerKnown = PowerKnown.NOT_SURE)
        val res = engine.evaluate(a, quick = QuickEstimate(possibleLongSight = true)).assessment
        assertNull(res.advice!!.add)
        assertTrue(res.advice!!.eyes.isEmpty())
        assertTrue(Note.POSSIBLE_LONG_SIGHT in res.notes)
        assertTrue(Note.HAND_TEST_NEEDED in res.notes)
    }

    @Test fun `red flag - urgent referral, the phone estimate and suggestions still given`() {
        val a = myope.copy(redFlagFlashes = true, powerKnown = PowerKnown.NOT_SURE)
        val res = engine.evaluate(a, quick = QuickEstimate(QuickEye(-2.5, 40.0, QuickTarget.WHITE)))
        assertEquals(ReferralLevel.URGENT, res.assessment.referral.level)
        assertEquals(Source.PHONE_ESTIMATE, res.assessment.advice!!.powerSource)
        assertTrue(res.frames.isNotEmpty())
    }

    @Test fun `staff full test outranks the quick estimate on the NOT SURE path`() {
        val a = myope.copy(powerKnown = PowerKnown.NOT_SURE)
        val tests = VisionTests(MeasuredEye(-2.0, Confidence.HIGH), MeasuredEye(-2.0, Confidence.HIGH))
        val adv = engine.evaluate(a, tests = tests, quick = QuickEstimate(QuickEye(-4.0, 25.0, QuickTarget.WHITE), QuickEye(-4.0, 25.0, QuickTarget.WHITE))).assessment.advice!!
        assertEquals(Source.MEASURED, adv.powerSource)
    }
}

class FunctionalProfileTest {
    private val rules = TestAssets.rules

    @Test fun `office worker with long screen time is intermediate-dominant, office lens when presbyopic`() {
        val a = InterviewAnswers(age = 48, occupation = Occupation.OFFICE_COMPUTER, screenHours = ScreenHours.H4_PLUS,
            readingFrequency = ReadingFrequency.RARELY)
        val p = FunctionalProfile.from(a, rules)
        assertEquals(VisionZone.INTERMEDIATE, p.dominant)
        assertEquals(ProfileLens.OFFICE_COMPUTER, p.impliedLens)
        assertEquals(4.8, p.intermediateHours, 1e-9)
        assertEquals(ProfileLens.SINGLE_VISION, FunctionalProfile.from(a.copy(age = 30), rules).impliedLens)
    }

    @Test fun `driver is distance-dominant`() {
        val a = InterviewAnswers(age = 50, occupation = Occupation.DRIVER, nightDriving = true, screenHours = ScreenHours.H0_2,
            readingFrequency = ReadingFrequency.RARELY)
        val p = FunctionalProfile.from(a, rules)
        assertEquals(VisionZone.DISTANCE, p.dominant)
        assertEquals(ProfileLens.SINGLE_VISION, p.impliedLens)
    }

    @Test fun `balanced use in a presbyope is mixed and points to progressive`() {
        val a = InterviewAnswers(age = 55, occupation = Occupation.STUDENT, screenHours = ScreenHours.H2_4,
            readingFrequency = ReadingFrequency.SOMETIMES)
        val p = FunctionalProfile.from(a, rules)
        assertEquals(VisionZone.MIXED, p.dominant)
        assertEquals(ProfileLens.PROGRESSIVE, p.impliedLens)
    }
}
