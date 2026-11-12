package com.drishtisaathi.interview

import com.drishtisaathi.UiState
import com.drishtisaathi.data.Measurements
import com.drishtisaathi.data.SessionCodec
import com.drishtisaathi.data.SessionEntity
import com.drishtisaathi.engine.AffectedEye
import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.DrishtiEngine
import com.drishtisaathi.engine.EyeRx
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.KnownRx
import com.drishtisaathi.engine.PowerKnown
import com.drishtisaathi.engine.QuickEstimate
import com.drishtisaathi.engine.QuickEye
import com.drishtisaathi.engine.QuickTarget
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.Source
import com.drishtisaathi.engine.StaffMeasurements
import com.drishtisaathi.engine.VisionProblem
import com.drishtisaathi.i18n.SAnamnesis
import com.drishtisaathi.ui.PowerPathText
import com.drishtisaathi.vision.BLUR_KEYWORDS
import com.drishtisaathi.vision.DistanceReading
import com.drishtisaathi.vision.QuickFarProcedure
import com.drishtisaathi.vision.VisionFlowPlan
import com.drishtisaathi.vision.VisionStep
import com.drishtisaathi.voice.ParseResult
import com.drishtisaathi.voice.TextNormalizer
import com.drishtisaathi.voice.VoiceAnswer
import com.drishtisaathi.voice.VoiceTestSupport
import com.drishtisaathi.voice.keywordHeard
import com.drishtisaathi.engine.anamnesis.QuickEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Adaptive anamnesis and power path, app side (12 Nov). */
class AnamnesisPowerPathTest {
    private val rules by lazy { RulesConfig.parse(File("src/main/assets/rules.json").readText()) }
    private val engine by lazy {
        val a = "src/main/assets/"
        DrishtiEngine.fromJson(File(a + "rules.json").readText(), File(a + "anthro_priors.json").readText(),
            File(a + "frames.json").readText(), File(a + "frames/more_frames.json").readText())
    }

    private fun ids(a: InterviewAnswers) = Questions.visible(a).map { it.id }
    private fun problem(v: VisionProblem, a: InterviewAnswers = InterviewAnswers(age = 30)) = Questions.setVisionProblem(a, v)

    // ---- adaptive follow-ups ----
    @Test fun `far problem asks situation and since, near problem asks task and distance`() {
        val far = ids(problem(VisionProblem.DISTANCE))
        assertTrue("distanceSituation" in far && "distanceSince" in far)
        assertFalse("nearTask" in far || "nearDistance" in far)
        val near = ids(problem(VisionProblem.NEAR))
        assertTrue("nearTask" in near && "nearDistance" in near)
        assertFalse("distanceSituation" in near)
        val both = ids(problem(VisionProblem.BOTH))
        assertTrue(listOf("distanceSituation", "distanceSince", "nearTask", "nearDistance").all { it in both })
        val none = ids(problem(VisionProblem.NONE))
        assertTrue(listOf("distanceSituation", "distanceSince", "nearTask", "nearDistance").none { it in none })
    }

    @Test fun `vision problem fills the old yes-no fields and clears follow-ups that no longer apply`() {
        val a = problem(VisionProblem.BOTH).copy(nearTask = com.drishtisaathi.engine.NearTask.PHONE)
        assertEquals(true, a.distanceBlur); assertEquals(true, a.nearBlur)
        val b = Questions.setVisionProblem(a, VisionProblem.DISTANCE)
        assertEquals(true, b.distanceBlur); assertEquals(false, b.nearBlur); assertNull(b.nearTask)
    }

    @Test fun `existing wearers get glasses age and satisfaction, new wearers the first-pair question`() {
        val wearer = ids(problem(VisionProblem.DISTANCE).copy(wearsGlasses = true))
        assertTrue("glassesAgeYears" in wearer && "happyWithGlasses" in wearer)
        assertFalse("firstTimeWearer" in wearer)
        val newbie = ids(problem(VisionProblem.DISTANCE).copy(wearsGlasses = false))
        assertTrue("firstTimeWearer" in newbie)
        assertFalse("glassesAgeYears" in newbie || "happyWithGlasses" in newbie)
    }

    @Test fun `vision problem is asked right after age, the power question after the red flags`() {
        val all = ids(problem(VisionProblem.DISTANCE).copy(wearsGlasses = true))
        assertEquals(Questions.VISION_PROBLEM, all[1])
        assertTrue(all.indexOf(Questions.POWER_KNOWN) > all.indexOf("redFlagOther"))
        assertFalse(Questions.KNOWN_RX in all)
    }

    // ---- red flag stops everything ----
    @Test fun `urgent red flag asks onset and eye, then hides the power path and all later questions`() {
        val a = problem(VisionProblem.DISTANCE).copy(wearsGlasses = true, redFlagFlashes = true)
        val v = ids(a)
        assertTrue("redFlagOnset" in v && "redFlagEye" in v)
        assertEquals("redFlagEye", v.last())
        assertFalse(Questions.POWER_KNOWN in v)
        val calm = ids(a.copy(redFlagFlashes = false, redFlagOther = false))
        assertFalse("redFlagOnset" in calm)
        assertTrue(Questions.POWER_KNOWN in calm)
    }

    @Test fun `clearing the red flag clears onset and eye`() {
        val q = Questions.all.first { it.id == "redFlagFlashes" } as Question.YesNo
        val a = q.set(InterviewAnswers(redFlagFlashes = true, redFlagOnset = com.drishtisaathi.engine.RedFlagOnset.TODAY, redFlagEye = AffectedEye.LEFT), false)
        assertNull(a.redFlagOnset); assertNull(a.redFlagEye)
    }

    @Test fun `no trouble and no glasses skips the power question`() {
        assertFalse(Questions.POWER_KNOWN in ids(problem(VisionProblem.NONE).copy(wearsGlasses = false)))
        assertTrue(Questions.POWER_KNOWN in ids(problem(VisionProblem.NONE).copy(wearsGlasses = true)))
    }

    @Test fun `answering power NO drops numbers typed earlier`() {
        val q = Questions.all.first { it.id == Questions.POWER_KNOWN } as Question.Choice<*>
        @Suppress("UNCHECKED_CAST")
        val set = (q as Question.Choice<PowerKnown>).set
        val rx = KnownRx(EyeRx(-1.0, 0.0, null, null), EyeRx(-1.0, 0.0, null, null))
        assertNull(set(InterviewAnswers(knownRx = rx), PowerKnown.NO).knownRx)
        assertEquals(rx, set(InterviewAnswers(knownRx = rx), PowerKnown.YES).knownRx)
    }

    // ---- voice ----
    private fun voice(id: String, text: String): Any? =
        ((VoiceTestSupport.parser.parse(VoiceTestSupport.q(id), text, VoiceTestSupport.ctx) as? ParseResult.Parsed)?.answer as? VoiceAnswer.Choice)?.option

    @Test fun `voice answers for the power question`() {
        assertEquals(PowerKnown.YES, voice(Questions.POWER_KNOWN, "हाँ याद है"))
        assertEquals(PowerKnown.YES, voice(Questions.POWER_KNOWN, "पर्ची है"))
        assertEquals(PowerKnown.NOT_SURE, voice(Questions.POWER_KNOWN, "पता नहीं"))
        assertEquals(PowerKnown.NOT_SURE, voice(Questions.POWER_KNOWN, "नहीं पता"))
        assertEquals(PowerKnown.NOT_SURE, voice(Questions.POWER_KNOWN, "not sure"))
        assertEquals(PowerKnown.NO, voice(Questions.POWER_KNOWN, "नहीं"))
        assertEquals(PowerKnown.NO, voice(Questions.POWER_KNOWN, "पर्ची नहीं है"))
    }

    @Test fun `voice answers for the vision problem`() {
        assertEquals(VisionProblem.DISTANCE, voice(Questions.VISION_PROBLEM, "दूर का"))
        assertEquals(VisionProblem.NEAR, voice(Questions.VISION_PROBLEM, "पास का"))
        assertEquals(VisionProblem.BOTH, voice(Questions.VISION_PROBLEM, "दूर और पास दोनों"))
        assertEquals(VisionProblem.NONE, voice(Questions.VISION_PROBLEM, "कोई दिक्कत नहीं"))
        assertEquals(VisionProblem.BOTH, voice(Questions.VISION_PROBLEM, "both"))
    }

    @Test fun `blur keyword is heard in Hindi, Hinglish and English, inside a sentence`() {
        val words = BLUR_KEYWORDS.map(TextNormalizer::tokens)
        assertTrue(keywordHeard("अब धुंधला हो गया", words))
        assertTrue(keywordHeard("dhundhla", words))
        assertTrue(keywordHeard("it is blurry now", words))
        assertFalse(keywordHeard("साफ़ दिख रहा है", words))
        assertFalse(keywordHeard("", words))
    }

    // ---- quick far point screen logic ----
    private fun r(mm: Double) = DistanceReading(mm, Confidence.HIGH)

    @Test fun `live line appears only after the phone was close, and blur freezes it`() {
        val est = QuickEstimator(rules)
        var p = QuickFarProcedure()
        assertNull(p.liveSeD(r(400.0), est))
        assertFalse(p.canBlur(r(400.0), rules))
        p = p.onReading(r(120.0), rules).onReading(r(400.0), rules)
        assertEquals(-2.5, p.liveSeD(r(400.0), est)!!, 1e-9)
        val eye = p.blur(r(400.0), est, QuickTarget.WHITE)
        assertEquals(-2.5, eye.seD!!, 1e-9)
        assertEquals(40.0, eye.blurAtCm!!, 1e-9)
        assertEquals("Blur at 40 cm, about −2.50 D (approximate, confirm with trial lens)", com.drishtisaathi.vision.quickEyeText(eye).en)
    }

    @Test fun `still clear counts only from arm's length`() {
        val p = QuickFarProcedure().onReading(r(120.0), rules)
        assertFalse(p.canStillClear(r(500.0), rules))
        assertTrue(p.canStillClear(r(rules.farPoint.selfHeldReachM * 1000.0), rules))
        assertEquals(SAnamnesis.quickNoMyopia, com.drishtisaathi.vision.quickEyeText(p.stillClear(QuickTarget.WHITE)))
    }

    // ---- test plan ----
    @Test fun `power path leaves the full far point out unless staff ask, quick near point replaces the near point`() {
        val full = VisionFlowPlan.steps(50, rules)
        assertTrue(full.any { it is VisionStep.FarPoint } && VisionStep.NearPoint in full)
        val quick = VisionFlowPlan.steps(50, rules, farPoint = false, nearPoint = false)
        assertTrue(quick.none { it is VisionStep.FarPoint || it is VisionStep.ClockDial })
        assertFalse(VisionStep.NearPoint in quick)
        assertTrue(quick.any { it is VisionStep.Amsler })
    }

    // ---- storage ----
    @Test fun `quick estimate round-trips with the session, old rows have none`() {
        val q = QuickEstimate(QuickEye(-2.5, 40.0, QuickTarget.WHITE), QuickEye(null, null, QuickTarget.WHITE, noMyopiaAtReach = true),
            addD = 1.5, nearPointCm = 33.0)
        val e = SessionEntity(createdAt = 0, lang = "HI", phoneModel = "x", answersJson = "{}", staffJson = "{}", resultJson = "{}",
            referralLevel = "NONE", measurementsJson = SessionCodec.encode(Measurements(quick = q)))
        assertEquals(q, SessionCodec.measurements(e).quick)
        assertNull(SessionCodec.measurements(e.copy(measurementsJson = """{"demo":false}""")).quick)
    }

    // ---- summary lines ----
    private val base = InterviewAnswers(age = 30, occupation = com.drishtisaathi.engine.Occupation.STUDENT, wearsGlasses = true, glassesAgeYears = 3,
        visionProblem = VisionProblem.DISTANCE, distanceBlur = true, nearBlur = false, redFlagFlashes = false, redFlagOther = false)

    @Test fun `known power is the stated power source, the phone check is a staff comparison`() {
        val rx = KnownRx(EyeRx(-2.0, 0.0, null, null), EyeRx(-1.5, 0.0, null, null))
        val a = base.copy(powerKnown = PowerKnown.YES, knownRx = rx)
        val compare = QuickEstimate(QuickEye(-2.75, 36.0, QuickTarget.WHITE), QuickEye(-1.5, 67.0, QuickTarget.WHITE), primary = false)
        val res = engine.evaluate(a, StaffMeasurements(), quick = compare)
        val adv = res.assessment.advice!!
        assertEquals(Source.KNOWN_POWER, adv.powerSource)
        val state = UiState(answers = a, result = res, quick = compare)
        assertEquals("Power source: customer's known power", PowerPathText.sourceLine(adv)!!.en)
        assertEquals(listOf("R: phone about −2.75 D, known −2.00 D", "L: phone about −1.50 D, known −1.50 D"),
            PowerPathText.compareLines(state, adv).map { it.en })
        assertTrue(PowerPathText.quickLines(state).isEmpty())
    }

    @Test fun `phone estimate is the stated power source on NOT SURE`() {
        val a = base.copy(powerKnown = PowerKnown.NOT_SURE, wearsGlasses = false, glassesAgeYears = null)
        val q = QuickEstimate(QuickEye(-2.5, 40.0, QuickTarget.WHITE), QuickEye(-2.25, 44.0, QuickTarget.WHITE))
        val res = engine.evaluate(a, StaffMeasurements(), quick = q)
        val adv = res.assessment.advice!!
        assertEquals(Source.PHONE_ESTIMATE, adv.powerSource)
        val state = UiState(answers = a, result = res, quick = q)
        assertEquals("Power source: phone estimate, approximate", PowerPathText.sourceLine(adv)!!.en)
        assertTrue(PowerPathText.compareLines(state, adv).isEmpty())
        assertEquals(2, PowerPathText.quickLines(state).size)
    }

    @Test fun `functional profile card has hours per zone, main zone and lens`() {
        val lines = PowerPathText.profileLines(UiState(answers = base.copy(screenHours = com.drishtisaathi.engine.ScreenHours.H4_PLUS)), rules)
        assertEquals(5, lines.size)
        assertTrue(lines[0].en.startsWith("Near (under 40 cm)"))
        assertTrue(lines[3].en.startsWith("Main zone: "))
        assertTrue(lines[4].en.startsWith("This points to: "))
    }
}
