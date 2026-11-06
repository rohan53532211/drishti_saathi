package com.drishtisaathi.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesEngineTest {
    private val engine = RulesEngine(TestAssets.rules, TestAssets.anthro)

    /** A calm adult with nothing notable: every boolean answered "no". */
    private fun base(age: Int = 35) = InterviewAnswers(
        age = age, wearsGlasses = false, distanceBlur = false, nearBlur = false,
        screenHours = ScreenHours.H0_2, nightDriving = false, outdoorWork = false,
        occupation = Occupation.OTHER, headacheStrain = false, diabetesOrBp = false,
        redFlagFlashes = false, redFlagOther = false, budget = Budget.MID, style = StylePref.ANY,
    )

    // ---- referral ----------------------------------------------------------------------------

    @Test fun `no complaints - no referral, no lens suggested`() {
        val r = engine.assess(base())
        assertEquals(ReferralLevel.NONE, r.referral.level)
        assertEquals(LensType.NONE_SUGGESTED, r.advice!!.lensType.value)
    }

    @Test fun `flashes or floaters - urgent, advice withheld`() {
        val r = engine.assess(base().copy(redFlagFlashes = true, distanceBlur = true))
        assertEquals(ReferralLevel.URGENT, r.referral.level)
        assertTrue(r.referral.adviceWithheld)
        assertNull(r.advice)
        assertTrue(ReferralReason.FLASHES_FLOATERS_CURTAIN in r.referral.reasons)
    }

    @Test fun `sudden loss, pain, double vision - urgent`() {
        val r = engine.assess(base().copy(redFlagOther = true))
        assertEquals(ReferralLevel.URGENT, r.referral.level)
        assertNull(r.advice)
    }

    @Test fun `amsler distortion - urgent`() {
        val r = engine.assess(base(), tests = VisionTests(amslerAbnormal = true))
        assertEquals(ReferralLevel.URGENT, r.referral.level)
    }

    @Test fun `urgent outranks routine reasons`() {
        val r = engine.assess(base(age = 8).copy(redFlagFlashes = true))
        assertEquals(ReferralLevel.URGENT, r.referral.level)
        assertTrue(ReferralReason.CHILD_UNDER_AGE in r.referral.reasons)
    }

    @Test fun `diabetes or BP with blur - routine, advice still given`() {
        val r = engine.assess(base().copy(diabetesOrBp = true, nearBlur = true))
        assertEquals(ReferralLevel.ROUTINE, r.referral.level)
        assertTrue(ReferralReason.DIABETES_OR_BP_WITH_BLUR in r.referral.reasons)
        assertNotNull(r.advice)
    }

    @Test fun `diabetes without blur - no referral`() {
        assertEquals(ReferralLevel.NONE, engine.assess(base().copy(diabetesOrBp = true)).referral.level)
    }

    @Test fun `child under 12 - routine referral and polycarbonate`() {
        val r = engine.assess(base(age = 10).copy(distanceBlur = true))
        assertEquals(ReferralLevel.ROUTINE, r.referral.level)
        assertTrue(ReferralReason.CHILD_UNDER_AGE in r.referral.reasons)
        assertEquals("POLYCARBONATE", r.advice!!.materialId.value)
    }

    @Test fun `low confidence twice - referral only`() {
        val r = engine.assess(base(), tests = VisionTests(lowConfidenceCount = 2))
        assertEquals(ReferralLevel.ROUTINE, r.referral.level)
        assertTrue(r.referral.adviceWithheld)
        assertNull(r.advice)
    }

    @Test fun `near acuity below threshold - routine`() {
        val r = engine.assess(base(), tests = VisionTests(nearAcuityBelowThreshold = true))
        assertTrue(ReferralReason.NEAR_ACUITY_BELOW_THRESHOLD in r.referral.reasons)
    }

    @Test fun `distance blur with no myopia found - routine, hand test, hyperopia possible`() {
        val noMyopia = MeasuredEye(seD = null, confidence = Confidence.MEDIUM, noMyopiaAtArmsLength = true)
        val r = engine.assess(base().copy(distanceBlur = true), tests = VisionTests(right = noMyopia, left = noMyopia))
        assertEquals(ReferralLevel.ROUTINE, r.referral.level)
        assertTrue(ReferralReason.DISTANCE_BLUR_NO_MYOPIA_FOUND in r.referral.reasons)
        assertTrue(Note.HYPEROPIA_POSSIBLE in r.notes)
        assertTrue(Note.HAND_TEST_NEEDED in r.notes)
        assertTrue(r.advice!!.eyes.isEmpty())
    }

    @Test fun `astigmatism flag - routine referral and check cylinder`() {
        val astig = MeasuredEye(seD = -1.5, confidence = Confidence.HIGH, astigmatismFlag = true, astigmatismAxisDeg = 90)
        val r = engine.assess(base().copy(distanceBlur = true), tests = VisionTests(right = astig))
        assertTrue(ReferralReason.ASTIGMATISM_FLAG in r.referral.reasons)
        assertTrue(Note.CHECK_CYLINDER in r.notes)
        // Start with sphere only
        assertEquals(0.0, r.advice!!.eyes.single().startingTrialLens.value.cyl, 1e-9)
    }

    // ---- bands and starting trial lens ------------------------------------------------------

    @Test fun `measured SE gives 1 D band and midpoint starting lens`() {
        val m = MeasuredEye(seD = -1.4, confidence = Confidence.HIGH)
        val r = engine.assess(base().copy(distanceBlur = true), tests = VisionTests(right = m, left = m))
        val eye = r.advice!!.eyes.first()
        assertEquals(-2.0, eye.band.value.lowD, 1e-9)
        assertEquals(-1.0, eye.band.value.highD, 1e-9)
        assertEquals(-1.5, eye.startingTrialLens.value.sph, 1e-9)
        assertEquals(Source.MEASURED, eye.band.source)
        assertEquals(LensType.SINGLE_VISION, r.advice!!.lensType.value)
    }

    @Test fun `fresh old numbers - medium confidence, half-D band, starting lens as recorded`() {
        val rx = KnownRx(EyeRx(-4.5), EyeRx(-4.25))
        val r = engine.assess(base().copy(wearsGlasses = true, glassesAgeYears = 1, knownRx = rx, distanceBlur = true))
        val right = r.advice!!.eyes.first { it.eye == Eye.RIGHT }
        assertEquals(PowerBand(-5.0, -4.0), right.band.value)
        assertEquals(Confidence.MEDIUM, right.band.confidence)
        assertEquals(Source.OLD_GLASSES_NUMBERS, right.band.source)
        assertEquals(-4.5, right.startingTrialLens.value.sph, 1e-9)
        assertEquals("HI_160", r.advice!!.materialId.value)
    }

    @Test fun `stale old numbers - low confidence and wider band`() {
        val rx = KnownRx(EyeRx(-2.0), EyeRx(-2.0))
        val r = engine.assess(base().copy(wearsGlasses = true, glassesAgeYears = 5, knownRx = rx))
        val right = r.advice!!.eyes.first()
        assertEquals(Confidence.LOW, right.band.confidence)
        assertEquals(PowerBand(-3.0, -1.0), right.band.value)
        assertTrue(Note.OLD_NUMBERS_STALE in r.notes)
    }

    @Test fun `unknown age of old numbers is flagged`() {
        val r = engine.assess(base().copy(knownRx = KnownRx(EyeRx(-1.0))))
        assertTrue(Note.OLD_NUMBERS_AGE_UNKNOWN in r.notes)
    }

    @Test fun `old cylinder - starting lens keeps it and staff must check`() {
        val rx = KnownRx(EyeRx(-2.0, -1.0, 180))
        val r = engine.assess(base().copy(glassesAgeYears = 1, knownRx = rx))
        val eye = r.advice!!.eyes.single()
        assertEquals(TrialLens(-2.0, -1.0, 180), eye.startingTrialLens.value)
        assertTrue(Note.CHECK_CYLINDER in r.notes)
        assertEquals(-3.0, eye.thicknessPowerD, 1e-9)
        // SE −2.50 → significant distance power → single vision even without reported blur
        assertEquals(LensType.SINGLE_VISION, r.advice!!.lensType.value)
    }

    @Test fun `very high power uses 1_67`() {
        val r = engine.assess(base().copy(glassesAgeYears = 1, knownRx = KnownRx(EyeRx(-6.5), EyeRx(-6.0))))
        assertEquals("HI_167", r.advice!!.materialId.value)
    }

    @Test fun `distance blur with no power info - hand test, no starting lens`() {
        val r = engine.assess(base().copy(distanceBlur = true))
        assertTrue(r.advice!!.eyes.isEmpty())
        assertTrue(Note.HAND_TEST_NEEDED in r.notes)
        assertTrue(Note.NO_POWER_INFORMATION in r.notes)
        assertEquals(LensType.SINGLE_VISION, r.advice!!.lensType.value)
        assertEquals(Confidence.LOW, r.advice!!.materialId.confidence)
    }

    // ---- lens type and add -------------------------------------------------------------------

    @Test fun `45, near blur only - reading glasses with age-table add`() {
        val r = engine.assess(base(age = 45).copy(nearBlur = true))
        val adv = r.advice!!
        assertEquals(LensType.READING, adv.lensType.value)
        assertEquals(PowerBand(1.25, 1.50), adv.add!!.value)
        assertEquals(Source.AGE_TABLE, adv.add!!.source)
        assertEquals(Confidence.LOW, adv.add!!.confidence)
        assertTrue(Note.ADD_FROM_AGE_TABLE in r.notes)
    }

    @Test fun `age table boundaries`() {
        fun add(age: Int) = engine.assess(base(age).copy(nearBlur = true)).advice!!.add!!.value
        assertEquals(PowerBand(0.75, 1.00), add(40))
        assertEquals(PowerBand(0.75, 1.00), add(44))
        assertEquals(PowerBand(1.75, 2.00), add(50))
        assertEquals(PowerBand(2.25, 2.50), add(70))
    }

    @Test fun `near and distance blur, office user, mid budget - progressive`() {
        val r = engine.assess(base(age = 50).copy(nearBlur = true, distanceBlur = true, occupation = Occupation.OFFICE_COMPUTER))
        assertEquals(LensType.PROGRESSIVE, r.advice!!.lensType.value)
        assertNotNull(r.advice!!.add)
    }

    @Test fun `near and distance blur, low budget - bifocal or separate pairs`() {
        val r = engine.assess(base(age = 50).copy(nearBlur = true, distanceBlur = true,
            occupation = Occupation.OFFICE_COMPUTER, budget = Budget.LOW))
        assertEquals(LensType.BIFOCAL_OR_SEPARATE_PAIRS, r.advice!!.lensType.value)
    }

    @Test fun `near and distance blur, not a screen user - bifocal or separate pairs`() {
        val r = engine.assess(base(age = 55).copy(nearBlur = true, distanceBlur = true, occupation = Occupation.FIELD_OUTDOOR))
        assertEquals(LensType.BIFOCAL_OR_SEPARATE_PAIRS, r.advice!!.lensType.value)
    }

    @Test fun `heavy screen use alone qualifies for progressive`() {
        val r = engine.assess(base(age = 48).copy(nearBlur = true, distanceBlur = true, screenHours = ScreenHours.H4_PLUS))
        assertEquals(LensType.PROGRESSIVE, r.advice!!.lensType.value)
    }

    @Test fun `under 40 with near blur - no add, hand test for possible hyperopia`() {
        val r = engine.assess(base(age = 30).copy(nearBlur = true))
        assertEquals(LensType.NONE_SUGGESTED, r.advice!!.lensType.value)
        assertNull(r.advice!!.add)
        assertTrue(Note.HYPEROPIA_POSSIBLE in r.notes)
    }

    @Test fun `fresh old add is used over age table`() {
        val rx = KnownRx(EyeRx(-1.0, add = 2.0), EyeRx(-1.0, add = 2.0))
        val r = engine.assess(base(age = 52).copy(glassesAgeYears = 1, knownRx = rx, nearBlur = true))
        assertEquals(PowerBand(2.0, 2.0), r.advice!!.add!!.value)
        assertEquals(Source.OLD_GLASSES_NUMBERS, r.advice!!.add!!.source)
    }

    @Test fun `measured add wins and is clamped`() {
        val t = VisionTests(measuredAddD = Estimate(3.6, Confidence.MEDIUM, Source.MEASURED))
        val r = engine.assess(base(age = 60).copy(nearBlur = true), tests = t)
        assertEquals(PowerBand(3.0, 3.0), r.advice!!.add!!.value)
    }

    // ---- coatings ------------------------------------------------------------------------------

    @Test fun `coatings from rules json`() {
        fun coat(a: InterviewAnswers) = engine.assess(a).advice!!.coatings
        assertEquals(listOf(Coating.ANTI_REFLECTIVE, Coating.BLUE_FILTER_OPTIONAL), coat(base().copy(screenHours = ScreenHours.H4_PLUS)))
        assertEquals(listOf(Coating.ANTI_REFLECTIVE), coat(base().copy(nightDriving = true)))
        assertEquals(listOf(Coating.PHOTOCHROMIC_OR_TINT), coat(base().copy(outdoorWork = true)))
        // No duplicates when two rules give anti-reflective
        assertEquals(1, coat(base().copy(nightDriving = true, screenHours = ScreenHours.H4_PLUS)).count { it == Coating.ANTI_REFLECTIVE })
        assertTrue(coat(base()).isEmpty())
    }

    @Test fun `editing rules json changes behaviour without code`() {
        val edited = TestAssets.text("rules.json").replace("\"presbyopiaFrom\": 40", "\"presbyopiaFrom\": 50")
        val e = RulesEngine(RulesConfig.parse(edited), TestAssets.anthro)
        assertEquals(LensType.NONE_SUGGESTED, e.assess(base(age = 45).copy(nearBlur = true)).advice!!.lensType.value)
    }

    @Test fun `sport occupation gets polycarbonate`() {
        val r = engine.assess(base().copy(occupation = Occupation.SPORT_ACTIVE))
        assertEquals("POLYCARBONATE", r.advice!!.materialId.value)
    }

    // ---- PD / face width -------------------------------------------------------------------------

    @Test fun `no PD entered - population average, low confidence`() {
        val r = engine.assess(base())
        assertEquals(Source.POPULATION_AVERAGE, r.pdMm.source)
        assertEquals(Confidence.LOW, r.pdMm.confidence)
        assertTrue(Note.PD_ASSUMED in r.notes)
        assertTrue(Note.FACE_WIDTH_ASSUMED in r.notes)
    }

    @Test fun `staff PD that fails plausibility is kept but low confidence`() {
        val r = engine.assess(base(), StaffMeasurements(pdMm = 45.0, faceWidthMm = 140.0))
        assertEquals(Confidence.LOW, r.pdMm.confidence)
        assertTrue(Note.PD_IMPLAUSIBLE in r.notes)
        val ok = engine.assess(base(), StaffMeasurements(pdMm = 63.0, faceWidthMm = 140.0))
        assertEquals(Confidence.MEDIUM, ok.pdMm.confidence)
        assertFalse(Note.PD_IMPLAUSIBLE in ok.notes)
    }
}
