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
import org.junit.Assert.assertNotEquals
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.SShop
import com.drishtisaathi.UiState
import com.drishtisaathi.pdf.Block
import com.drishtisaathi.pdf.PdfContent
import com.drishtisaathi.ui.PowerPathText
import kotlin.random.Random
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The compulsory reading eye check: which tests run, letters and sentences, ranges, and what reaches the results. */
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
    private fun plan(a: InterviewAnswers) = EyeCheckPlan.from(a)
    private fun steps(p: VisionProblem, age: Int) = eyeCheckSteps(plan(answers(p, age))).map { it.toString() }
    private val ctx = com.drishtisaathi.interview.ChoiceContext(1500, 4000)
    private fun paras(b: List<Block>) = b.mapNotNull { (it as? Block.Para)?.text }
    private fun r(mm: Double) = DistanceReading(mm, Confidence.HIGH)

    @Test fun `everyone does the far check both eyes, near for NEAR, BOTH or 38+`() {
        assertEquals(listOf("Power(eye=RIGHT)", "Power(eye=LEFT)", "Done"), steps(VisionProblem.DISTANCE, 30))
        assertEquals(listOf("Power(eye=RIGHT)", "Power(eye=LEFT)", "Near", "Done"), steps(VisionProblem.DISTANCE, 45))
        assertEquals(listOf("Power(eye=RIGHT)", "Power(eye=LEFT)", "Near", "Done"), steps(VisionProblem.NEAR, 25))
        assertEquals(listOf("Power(eye=RIGHT)", "Power(eye=LEFT)", "Near", "Done"), steps(VisionProblem.BOTH, 30))
        // a known power changes nothing: the check is compulsory
        assertEquals(steps(VisionProblem.DISTANCE, 30),
            eyeCheckSteps(plan(answers(VisionProblem.DISTANCE, 30).copy(powerKnown = PowerKnown.YES))).map { it.toString() })
    }

    @Test fun `far letters - 5 Sloan letters that change only on tap, never with distance`() {
        val rnd = Random(1)
        var set = LetterSet.start(rnd)
        assertEquals(5, set.letters.length)
        assertEquals(5, set.letters.toSet().size)
        assertTrue(set.letters.all { it in SLOAN_LETTERS })
        val first = set.letters
        // the phone moves 10 → 80 cm and back: the letters never change
        for (mm in (100..800 step 7).map { it.toDouble() } + listOf(null, 450.0, 120.0)) set = set.update(mm, rnd)
        assertEquals(first, set.letters)
        // "New letters": a different random set of 5, same rules
        val tapped = set.next(rnd)
        assertNotEquals(first, tapped.letters)
        assertEquals(5, tapped.letters.toSet().size)
        assertTrue(tapped.letters.all { it in SLOAN_LETTERS })
        // the screen wires the button and the "badlo" keyword, and no longer updates letters from readings
        val src = File("src/main/kotlin/com/drishtisaathi/vision/QuickEstimateFlow.kt").readText()
        assertTrue(src.contains("onNewLetters = { letters = letters.next(rnd) }"))
        assertFalse(src.contains("letters = letters.update("))
        assertTrue(NEW_LETTERS_KEYWORDS.containsAll(listOf("badlo", "बदलो")))
        assertTrue(BLUR_KEYWORDS.none { it in NEW_LETTERS_KEYWORDS })
    }

    @Test fun `near check - a new random sentence on every attempt, 30+ per language`() {
        val s = ReadingSentences.parse(File(a + ReadingSentences.ASSET).readText())
        assertTrue(s.en.size >= 30 && s.hi.size >= 30)
        val rnd = Random(7)
        var prev: String? = null
        repeat(50) {
            val next = s.pick(Lang.EN, rnd, prev)
            assertNotEquals(prev, next)
            assertTrue(next in s.en)
            prev = next
        }
        assertTrue(s.pick(Lang.HI, rnd) in s.hi)
    }

    @Test fun `power is shown as a range - 30 cm gives centre -3_25, range -3_00 to -3_50 D`() {
        val r = PowerRange.far(300.0)                       // -3.33 -> nearest 0.25 = -3.25
        assertEquals(-3.25, r.centreD, 1e-9); assertEquals(-3.00, r.fromD, 1e-9); assertEquals(-3.50, r.toD, 1e-9)
        assertEquals(-3.50, PowerRange.far(290.0).centreD, 1e-9)   // -3.45 -> -3.50 (nearest, not always down)
        val r40 = PowerRange.far(400.0)                     // exactly -2.50
        assertEquals(-2.25, r40.fromD, 1e-9); assertEquals(-2.75, r40.toD, 1e-9)
        val eye = PowerRange.farEye(300.0)
        assertEquals(-3.25, eye.seD!!, 1e-9)                // the centre feeds lens and frame suggestions
        assertEquals(30.0, eye.blurAtCm!!, 0.0)
        val text = quickEyeText(eye).en
        assertTrue(text, "between −3.00 and −3.50 D" in text)
        val add = PowerRange.add(1.75)
        assertEquals(1.50, add.fromD, 1e-9); assertEquals(2.00, add.toD, 1e-9)
        assertTrue("+1.50 to +2.00" in nearResultText(40.0, 1.75, null, false).en)
    }

    @Test fun `a red flag does not end the flow - the eye check runs and the referral shows with the ranges`() {
        val red = answers(VisionProblem.DISTANCE, 30).copy(redFlagFlashes = true, powerKnown = null)
        assertEquals(listOf("Power(eye=RIGHT)", "Power(eye=LEFT)", "Done"), eyeCheckSteps(plan(red)).map { it.toString() })
        val q = eyeCheckResult(PowerRange.farEye(300.0), PowerRange.farEye(400.0), null, primary = true)
        val result = engine.evaluate(red, quick = q)
        assertEquals(ReferralLevel.URGENT, result.assessment.referral.level)
        val state = UiState(answers = red, quick = q, result = result)
        val lines = PowerPathText.quickLines(state).map { it.en }
        assertTrue(lines.toString(), lines.any { "−3.00 and −3.50" in it } && lines.any { "−2.25 and −2.75" in it })
        val texts = paras(PdfContent.shopCopy(state, rules, ctx, Lang.EN))
        assertTrue(texts.indexOf(com.drishtisaathi.i18n.SProfile.seeDoctorToday.en) in 0..4)   // at the top
        assertFalse(SShop.phoneCheckNote.en in texts)
        assertTrue(com.drishtisaathi.i18n.SProfile.seeDoctorToday.hi in paras(PdfContent.customerCopy(state, rules)))
    }

    @Test fun `no red flag - the closing line recommends a complete eye check-up, on both PDFs`() {
        val a0 = answers(VisionProblem.DISTANCE, 30).copy(redFlagFlashes = false, redFlagOther = false)
        val q = eyeCheckResult(PowerRange.farEye(500.0), PowerRange.farEye(500.0), null, primary = true)
        val state = UiState(answers = a0, quick = q, result = engine.evaluate(a0, quick = q))
        assertTrue(SShop.phoneCheckNote.en in paras(PdfContent.shopCopy(state, rules, ctx, Lang.EN)))
        assertTrue(SShop.phoneCheckNote.hi in paras(PdfContent.customerCopy(state, rules)))
        assertFalse(SShop.urgentTop.en in paras(PdfContent.shopCopy(state, rules, ctx, Lang.EN)))
    }

    @Test fun `precision mode - the same reading test gives a full result with no answers`() {
        val none = InterviewAnswers()
        val p = EyeCheckPlan.from(none, full = true)
        assertEquals(listOf("Power(eye=RIGHT)", "Power(eye=LEFT)", "Near", "Done"), eyeCheckSteps(p).map { it.toString() })
        val near = NearCheck.analyse(300.0, none.age, -2.0, none, rules)
        val q = eyeCheckResult(PowerRange.farEye(350.0), PowerRange.farEye(450.0), near, primary = true)
        val result = engine.evaluate(none, quick = q)
        assertEquals(Source.PHONE_ESTIMATE, result.assessment.advice!!.powerSource)
        assertEquals(2, result.assessment.advice!!.eyes.size)
        val state = UiState(answers = none, quick = q, result = result, precision = true)
        assertEquals(3, PowerPathText.quickLines(state).size)   // R range, L range, near point
        assertTrue(paras(PdfContent.shopCopy(state, rules, ctx, Lang.EN)).isNotEmpty())
    }

    @Test fun `far letters at 31 cm are at least minLetterPx tall and laid out in the panel`() {
        val cfg = rules.farPoint
        assertEquals(7.5, cfg.letterArcmin, 0.0)                // 20/30 by default, from rules.json
        assertEquals(18.0, cfg.minLetterPx, 0.0)
        val scale = ScreenScale(mmPerPx = 0.0635, fromCard = false)   // ~400 dpi phone
        val panelW = 1000.0
        val l = FarLetters.layout("CDHKN", 310.0, scale, cfg, panelW)
        assertTrue(l.letterPx >= cfg.minLetterPx)
        assertEquals("CDHKN", l.letters)
        assertEquals(5, l.centresPx.size)
        assertTrue(l.centresPx.all { it - l.letterPx / 2 >= 0 && it + l.letterPx / 2 <= panelW })
        assertEquals(2 * l.letterPx, l.centresPx[1] - l.centresPx[0], 1e-9)    // spacing = 1 letter width
        // distance-locked: 2·d·tan(3.75') at 60 cm, above the floor there
        val far = FarLetters.layout("CDHKN", 600.0, scale, cfg, panelW)
        assertEquals(2 * 600.0 * Math.tan(Math.toRadians(3.75 / 60.0)) / 0.0635, far.letterPx, 1e-6)
        // no reading yet: still drawn at the default distance, never empty
        val none = FarLetters.layout(LetterSet.start(Random(3)).letters, null, scale, cfg, panelW)
        assertEquals(5, none.letters.length)
        assertTrue(none.letterPx >= cfg.minLetterPx)
    }

    @Test fun `urgent path - the full interview, lens advice and frames, one red card`() {
        val red = answers(VisionProblem.DISTANCE, 30).copy(redFlagFlashes = true)
        val ids = com.drishtisaathi.interview.Questions.visible(red).map { it.id }
        assertTrue(ids.toString(), "screenHours" in ids && "budget" in ids && "powerKnown" in ids)
        val q = eyeCheckResult(PowerRange.farEye(300.0), PowerRange.farEye(400.0), null, primary = true)
        val result = engine.evaluate(red, quick = q)
        assertEquals(ReferralLevel.URGENT, result.assessment.referral.level)
        assertTrue(result.assessment.advice != null)
        assertTrue(result.frames.isNotEmpty())
        val state = UiState(answers = red, quick = q, result = result)
        val shop = PdfContent.shopCopy(state, rules, ctx, Lang.EN)
        // Phase A: the red banner once, the power for the optometrist only, and no lens or frame is suggested
        assertEquals(1, paras(shop).count { it == com.drishtisaathi.i18n.SProfile.seeDoctorToday.en })
        assertTrue(com.drishtisaathi.i18n.SProfile.forOptometristOnly.en in paras(shop))
        assertFalse(com.drishtisaathi.i18n.S.lensPackage.en in shop.filterIsInstance<com.drishtisaathi.pdf.Block.Heading>().map { it.text })
    }

    @Test fun `skipped lifestyle questions say not answered instead of 0_0 h`() {
        val st = UiState(answers = answers(VisionProblem.DISTANCE, 30))
        val lines = PowerPathText.profileLines(st, rules).map { it.en }
        assertTrue(lines.toString(), lines.take(3).all { it.endsWith("not answered") })
        val answered = PowerPathText.profileLines(
            UiState(answers = answers(VisionProblem.DISTANCE, 30).copy(screenHours = com.drishtisaathi.engine.ScreenHours.H4_PLUS)), rules,
        ).map { it.en }
        assertTrue(answered.toString(), answered[1].contains(" h a day"))
    }

    @Test fun `bent straight lines (no grid in the eye check) still refer`() {
        val bent = answers(VisionProblem.DISTANCE, 30).copy(straightLinesBent = true)
        assertTrue(ReferralReason.AMSLER_DISTORTION in engine.evaluate(bent).assessment.referral.reasons)
    }

    @Test fun `a reading that looks invalid is repeated once and the mean is used`() {
        assertEquals(-2.75, PowerRange.farEyeMedian(400.0, 350.0).seD!!, 1e-9)   // 2.50 and 2.86 -> 2.67 -> centre -2.75
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
