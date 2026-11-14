package com.drishtisaathi.profile

import com.drishtisaathi.engine.Eye
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.QuickEstimate
import com.drishtisaathi.engine.ReferralLevel
import com.drishtisaathi.engine.ReferralReason
import com.drishtisaathi.engine.VisionProblem
import com.drishtisaathi.engine.profile.AmslerCell
import com.drishtisaathi.engine.profile.AmslerEyeResult
import com.drishtisaathi.engine.profile.ColourOutcome
import com.drishtisaathi.engine.profile.ColourResult
import com.drishtisaathi.engine.profile.ContrastEyeResult
import com.drishtisaathi.engine.profile.DialEyeResult
import com.drishtisaathi.engine.profile.ETestEyeResult
import com.drishtisaathi.engine.profile.ReportTag
import com.drishtisaathi.engine.profile.ScreeningResults
import com.drishtisaathi.engine.profile.SurveyAnswers
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.SProfile
import com.drishtisaathi.pdf.PdfContent
import com.drishtisaathi.vision.PowerRange
import com.drishtisaathi.vision.TestEye
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Phase C in the app: the order of the screening stage and how its results read on every output. */
class ScreeningFlowTest {
    private val f = ProfileFixtures
    private val adult = InterviewAnswers(age = 55, visionProblem = VisionProblem.DISTANCE, distanceBlur = true, redFlagFlashes = false, redFlagOther = false)
    private val normal = ScreeningResults(
        amsler = listOf(AmslerEyeResult(Eye.RIGHT, false, emptyList(), 12, 20, 300.0), AmslerEyeResult(Eye.LEFT, false, emptyList(), 12, 20, 300.0)),
        contrast = listOf(ContrastEyeResult(Eye.RIGHT, 1.5, false), ContrastEyeResult(Eye.LEFT, 3.0, false)),
        colour = ColourResult(ColourOutcome.NORMAL, 0, 0, 0, 8),
        eTest = listOf(ETestEyeResult(Eye.RIGHT, 0.2, false)), clockDial = listOf(DialEyeResult(Eye.RIGHT, false)),
    )

    @Test fun `everyone gets grid, contrast, colour, then the E test and clock dial - the emergency path only the grid`() {
        assertEquals(
            listOf(ScreenStep.Setup, ScreenStep.Amsler(Eye.RIGHT), ScreenStep.Amsler(Eye.LEFT), ScreenStep.Contrast(Eye.RIGHT),
                ScreenStep.Contrast(Eye.LEFT), ScreenStep.Colour, ScreenStep.Classic),
            ScreeningPlan.steps(emergency = false),
        )
        assertEquals(listOf(ScreenStep.Setup, ScreenStep.Amsler(Eye.RIGHT), ScreenStep.Amsler(Eye.LEFT)), ScreeningPlan.steps(emergency = true))
        assertEquals("AMSLER_LEFT", ScreeningPlan.skipKey(ScreenStep.Amsler(Eye.LEFT)))
    }

    @Test fun `the clock dial is placed from the power check - the first pass for a lens-assisted eye`() {
        val first = PowerRange.farEye(235.0)
        val assisted = PowerRange.lensAssistedEye(first, PowerRange.farEye(444.0), -2.0, f.rules.lensAssisted)!!
        val s = ScreeningPlan.seededSession(QuickEstimate(assisted, PowerRange.farEye(500.0)))
        assertEquals(1000.0 / 4.25, s.farPoint.getValue(TestEye.RIGHT).farPointMm!!, 0.5)
        assertEquals(500.0, s.farPoint.getValue(TestEye.LEFT).farPointMm!!, 0.5)
        assertTrue(ScreeningPlan.seededSession(null).farPoint.isEmpty())
        // the seeded far point is only for placing the dial: it is not stored as a result
        val (e, d) = ScreeningPlan.classicResults(s)
        assertTrue(e.isEmpty() && d.isEmpty())
    }

    @Test fun `normal results read as normal with green dots`() {
        val lines = ScreeningText.lines(normal, 55, false, f.rules)
        assertTrue(lines.all { it.status == ScreenLine.Status.OK })
        val en = lines.map { it.text.en }
        assertTrue(en.toString(), "Grid R: lines straight" in en)
        assertTrue("Contrast R: lowest step seen 1.5% – Normal" in en)
        assertTrue("Contrast L: lowest step seen 3% – Normal" in en)
        assertTrue("Colour: Normal" in en)
        assertTrue("Clock dial R: all lines equally dark" in en)
        assertTrue(lines.all { it.text.hi.isNotBlank() })
    }

    @Test fun `reduced contrast - the eye-health note from 50, or with low-light or glare complaints`() {
        val r = normal.copy(contrast = listOf(ContrastEyeResult(Eye.RIGHT, 12.5, true), ContrastEyeResult(Eye.LEFT, null, true)))
        val at55 = ScreeningText.lines(r, 55, false, f.rules).filter { it.label.en.startsWith("Contrast") }
        assertEquals("Contrast R: lowest step seen 12.5% – Reduced. Suggest cataract / eye-health check", at55[0].text.en)
        assertEquals("Contrast L: no step seen – Reduced. Suggest cataract / eye-health check", at55[1].text.en)
        assertTrue(at55.all { it.status == ScreenLine.Status.WATCH })
        assertNull(ScreeningText.lines(r, 30, false, f.rules).first { it.label.en.startsWith("Contrast") }.note)
        assertEquals(SProfile.eyeHealthNote, ScreeningText.lines(r, 30, true, f.rules).first { it.label.en.startsWith("Contrast") }.note)
        // the survey's low-light and glare answers feed that rule
        val glare = SurveyAnswers().with("glareOncoming", listOf("SOMETIMES"))
        assertTrue(SurveyBridge.lowLightOrGlare(glare, f.rules.lensEngine.symptomFrom))
        assertFalse(SurveyBridge.lowLightOrGlare(SurveyAnswers().with("glareOncoming", listOf("NEVER")), f.rules.lensEngine.symptomFrom))
    }

    @Test fun `colour results - possible deficiency with the licence note, a missed control plate as not valid`() {
        fun colour(o: ColourOutcome) = ScreeningText.lines(normal.copy(colour = ColourResult(o, 0, 0, 0, 8)), 30, false, f.rules).first { it.label == SProfile.resColour }
        assertEquals("Colour: Possible red-green deficiency – confirm with optometrist. Ask about colour needs for driving licence/work",
            colour(ColourOutcome.POSSIBLE_RED_GREEN).text.en)
        assertEquals("Colour: Possible blue-yellow deficiency – confirm with optometrist. Ask about colour needs for driving licence/work",
            colour(ColourOutcome.POSSIBLE_BLUE_YELLOW).text.en)
        assertEquals("Colour: Test not valid, repeat", colour(ColourOutcome.INVALID).text.en)
        assertEquals(ScreenLine.Status.WATCH, colour(ColourOutcome.INVALID).status)
    }

    @Test fun `a wavy grid - red flag, tapped region in the report, labelled everywhere`() {
        val bad = normal.copy(amsler = listOf(normal.amsler[0], AmslerEyeResult(Eye.LEFT, true, listOf(AmslerCell(3, 7), AmslerCell(4, 7)), 12, 20, 305.0)))
        val st = f.state(adult).copy(screening = bad, tests = bad.toVisionTests(), testsDone = true)
        val gate = st.gate(f.rules)
        assertEquals(ReportTag.URGENT, gate.tag); assertFalse(gate.lensAllowed)
        val line = ScreeningText.lines(st, f.rules).first { it.label.en == "Grid L" }
        assertEquals(ScreenLine.Status.URGENT, line.status)
        assertEquals("Grid L: wavy or missing lines reported (2 square(s) marked)", line.text.en)

        val shop = f.texts(PdfContent.shopCopy(st, f.rules, f.ctx, Lang.EN))
        assertTrue(SProfile.screeningLabel.en in shop)
        assertTrue(line.text.en in shop)
        assertTrue(SProfile.seeDoctorToday.en in shop)

        val p = com.drishtisaathi.bridge.BridgeProfile.build(st, f.rules, f.consent, "t")
        val sc = p["screening"]!!.jsonObject
        assertEquals("Screening, not a diagnosis", sc["label"]!!.jsonPrimitive.content)
        val cells = sc["results"]!!.jsonObject["amsler"]!!.jsonArray[1].jsonObject["cells"]!!.jsonArray
        assertEquals(listOf("3", "4"), cells.map { it.jsonObject["col"]!!.jsonPrimitive.content })
        assertEquals("URGENT", p["triage"]!!.jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals(emptyList<String>(), com.drishtisaathi.bridge.BridgeProfile.forbiddenPaths(p))
        // the rules engine sees it too
        val a = f.engine.evaluate(adult, tests = bad.toVisionTests()).assessment.referral
        assertEquals(ReferralLevel.URGENT, a.level); assertTrue(ReferralReason.AMSLER_DISTORTION in a.reasons)
    }

    @Test fun `a dial-only eye does not hide that the power check found no short sight`() {
        val clear = QuickEstimate(com.drishtisaathi.engine.QuickEye(null, null, com.drishtisaathi.engine.QuickTarget.WHITE, true),
            com.drishtisaathi.engine.QuickEye(null, null, com.drishtisaathi.engine.QuickTarget.WHITE, true))
        val r = f.engine.evaluate(adult, tests = normal.toVisionTests(), quick = clear).assessment.referral
        assertTrue(ReferralReason.DISTANCE_BLUR_NO_MYOPIA_FOUND in r.reasons)
    }

    @Test fun `skipped tests say not done and are never invented`() {
        val s = ScreeningResults(skipped = listOf("AMSLER_RIGHT", "CONTRAST_RIGHT", "COLOUR"))
        val lines = ScreeningText.lines(s, 40, false, f.rules)
        assertTrue(lines.isNotEmpty() && lines.all { it.status == ScreenLine.Status.NOT_DONE })
        assertTrue(ScreeningText.lines(null, 40, false, f.rules).isEmpty())
    }

    @Test fun `a contrast trial offers the right letter once among four`() {
        val c = contrastChoices('K', 4, Random(1))
        assertEquals(4, c.size); assertEquals(4, c.toSet().size); assertEquals(1, c.count { it == 'K' })
    }
}
