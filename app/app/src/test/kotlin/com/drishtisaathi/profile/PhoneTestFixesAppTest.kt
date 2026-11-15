package com.drishtisaathi.profile

import com.drishtisaathi.bridge.BridgeProfile
import com.drishtisaathi.engine.Eye
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.NotMeasured
import com.drishtisaathi.engine.Note
import com.drishtisaathi.engine.QuickEstimate
import com.drishtisaathi.engine.VisionProblem
import com.drishtisaathi.engine.VisionTests
import com.drishtisaathi.engine.profile.AmslerCell
import com.drishtisaathi.engine.profile.AmslerEyeResult
import com.drishtisaathi.engine.profile.ContrastEyeResult
import com.drishtisaathi.engine.profile.LensAssisted
import com.drishtisaathi.engine.profile.LensCatalog
import com.drishtisaathi.engine.profile.LensEngine
import com.drishtisaathi.engine.profile.ReportTag
import com.drishtisaathi.engine.profile.ScreeningResults
import com.drishtisaathi.engine.profile.SurveyAnswers
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S
import com.drishtisaathi.i18n.SProfile
import com.drishtisaathi.pdf.Block
import com.drishtisaathi.pdf.PdfContent
import com.drishtisaathi.ui.PowerPathText
import com.drishtisaathi.vision.NearCheck
import com.drishtisaathi.vision.PowerRange
import com.drishtisaathi.vision.TestEye
import com.drishtisaathi.vision.eyeCheckResult
import com.drishtisaathi.vision.firstUnmeasuredEye
import com.drishtisaathi.vision.nearResultText
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The bugs found in the 15 Nov phone test (reports S2 and S32) as the reports show them, one test per fix. */
class PhoneTestFixesAppTest {
    private val f = ProfileFixtures
    private val rules = f.rules
    private val engine = LensEngine(LensCatalog.parse(File("src/main/assets/lens_catalog.json").readText()), rules.lensEngine)
    private val adult = InterviewAnswers(age = 34, visionProblem = VisionProblem.DISTANCE, distanceBlur = true, redFlagFlashes = false, redFlagOther = false)
    private val emergency = adult.copy(redFlagFlashes = true)
    private val straight = ScreeningResults(amsler = listOf(
        AmslerEyeResult(Eye.RIGHT, false, emptyList(), 12, 20, 300.0), AmslerEyeResult(Eye.LEFT, false, emptyList(), 12, 20, 300.0)))
    private fun share(state: com.drishtisaathi.UiState) = QrPayload.shareLines(state, ProfileView.build(state, rules, engine), rules).map { it.en }
    private fun shop(state: com.drishtisaathi.UiState) =
        f.texts(PdfContent.shopCopy(state, rules, f.ctx, Lang.EN, view = ProfileView.build(state, rules, engine)))

    // ---- 1. both eyes ----

    @Test fun `1 - report S2 - the left eye is printed as 'not measured' with the reason, on every output`() {
        val q = eyeCheckResult(PowerRange.farEye(210.0), null, null, primary = true, leftWhy = NotMeasured.SKIPPED)
        assertEquals(NotMeasured.SKIPPED, q.leftNotMeasured); assertNull(q.rightNotMeasured)
        val state = f.state(adult, quick = q)
        val lines = PowerPathText.quickLines(state).map { it.en }
        assertTrue(lines[0].startsWith("R: "))
        assertEquals("L: not measured – skipped", lines[1])
        assertTrue(PowerPathText.quickLines(state).map { it.hi }[1].startsWith("L: नहीं नापा गया"))
        assertTrue("L: not measured – skipped" in share(state))
        assertTrue("L: not measured – skipped" in shop(state))
        // QR and optometrist report: two eyes, the left one with its reason
        val eyes = QrPayload.json(state, ProfileView.build(state, rules, engine), rules, "1.1")["eyes"]!!.jsonArray
        assertEquals(listOf("R", "L"), eyes.map { it.jsonObject["eye"]!!.jsonPrimitive.content })
        assertEquals("SKIPPED", eyes[1].jsonObject["notMeasured"]!!.jsonPrimitive.content)
        val range = BridgeProfile.build(state, rules, f.consent, "1.1")["power"]!!.jsonObject["phoneRange"]!!.jsonObject
        assertEquals("not measured – skipped", range["left"]!!.jsonPrimitive.content)
        assertTrue(range["right"]!!.jsonPrimitive.content.startsWith("−4.50 to −5.00 D"))
    }

    @Test fun `1 - the check goes back to an eye that has no reading, and a session without the eye check says so`() {
        val r = PowerRange.farEye(300.0)
        assertEquals(TestEye.LEFT, firstUnmeasuredEye(r, null))
        assertEquals(TestEye.RIGHT, firstUnmeasuredEye(null, null))
        assertNull(firstUnmeasuredEye(r, r))
        val none = PowerPathText.quickLines(f.state(adult, quick = null)).map { it.en }
        assertEquals(listOf("R: not measured – eye check not done", "L: not measured – eye check not done"), none)
    }

    // ---- 2. lens-assisted ----

    @Test fun `2 - a lens that leaves the residual at minus 4_00 is too weak - a stronger lens gives the same total from farther away`() {
        val cfg = rules.lensAssisted
        val first = PowerRange.farEye(267.0)                          // first pass: -3.75 D at 27 cm
        assertEquals(-3.75, first.seD!!, 1e-9)
        assertEquals(-1.5, LensAssisted.suggestLens(first.seD!!, cfg), 1e-9)
        // the eye is really about -5.50: through the -1.50 lens the letters still blur at 25 cm
        val weak = PowerRange.lensAssistedEye(first, PowerRange.farEye(250.0), -1.5, cfg)!!
        assertEquals(-4.0, weak.residualD!!, 1e-9); assertEquals(-5.5, weak.seD!!, 1e-9)
        assertTrue(LensAssisted.needsStrongerLens(weak.residualD, 0, cfg))
        val next = LensAssisted.strongerLens(weak.seD!!, weak.lensD!!, cfg)
        assertEquals(-3.5, next, 1e-9)
        // repeated through -3.50: blur at 50 cm, residual -2.00, total still -5.50, and no further repeat
        val strong = PowerRange.lensAssistedEye(first, PowerRange.farEye(500.0), next, cfg)!!
        assertEquals(-2.0, strong.residualD!!, 1e-9); assertEquals(-5.5, strong.seD!!, 1e-9)
        assertFalse(LensAssisted.needsStrongerLens(strong.residualD, 1, cfg))
    }

    // ---- 3. near point of a myope ----

    @Test fun `3 - report S2 - a minus 4_75 myope never gets 'possible long-sightedness'`() {
        val far = PowerRange.farEye(210.0)                            // -4.75 D
        assertEquals(-4.75, far.seD!!, 1e-9)
        val young = adult.copy(age = 30)
        // without the distance correction the near point of a short-sighted eye is not judged
        val bare = NearCheck.analyse(120.0, 30, far.seD, young, rules, myopic = true, withCorrection = false)
        assertFalse(bare.fartherThanExpected); assertNull(bare.expectedNearPointCm)
        val bareText = nearResultText(bare.nearPointCm, bare.addD, bare.expectedNearPointCm, bare.fartherThanExpected, true, false).en
        assertTrue(bareText.contains("without the distance correction") && bareText.contains("repeat with the glasses or the trial lens on"))
        // with the correction on the far power counts as corrected: 10 cm is normal at 30, 25 cm is farther than expected
        val ok = NearCheck.analyse(100.0, 30, far.seD, young, rules, myopic = true, withCorrection = true)
        assertFalse(ok.fartherThanExpected); assertEquals(11.0, ok.expectedNearPointCm!!, 0.0)
        val weak = NearCheck.analyse(250.0, 30, far.seD, young, rules, myopic = true, withCorrection = true)
        assertTrue(weak.fartherThanExpected)
        val q = eyeCheckResult(far, far, weak, primary = true)
        assertFalse(q.possibleLongSight); assertEquals(true, q.nearWithCorrection)
        val lines = PowerPathText.quickLines(f.state(young, quick = q)).map { it.en }
        assertTrue(lines.last().contains("with the distance correction on") && lines.last().contains("focusing weakness"))
        // every wording, also for a session stored by the earlier build with the flag set next to a minus power
        val stored = QuickEstimate(far, far, nearPointCm = 12.0, possibleLongSight = true, expectedNearPointCm = 11.0)
        val old = f.state(young, quick = stored)
        (lines + PowerPathText.quickLines(old).map { it.en } + PowerPathText.quickLines(old).map { it.hi } + share(old) + shop(old)).forEach {
            assertFalse(it, it.contains("long-sight", ignoreCase = true)); assertFalse(it, it.contains("दूर-दृष्टि"))
        }
        assertFalse(Note.POSSIBLE_LONG_SIGHT in old.result!!.assessment.notes)
        // someone with no minus power keeps the hint
        val plano = PowerRange.farEye(300.0).copy(seD = null, noMyopiaAtReach = true)
        val far2 = NearCheck.analyse(250.0, 30, null, young, rules)
        assertTrue(eyeCheckResult(plano, plano, far2, primary = true).possibleLongSight)
    }

    // ---- 4. contrast ----

    @Test fun `4 - report S2 - contrast with no step seen reads 'Test not valid', not Reduced with a cataract check`() {
        val s = straight.copy(contrast = listOf(ContrastEyeResult(Eye.RIGHT, null, reduced = true), ContrastEyeResult(Eye.LEFT, 12.5, true)))
        val lines = ScreeningText.lines(s, 62, true, rules)
        val right = lines.first { it.label.en == "Contrast R" }
        assertEquals("Test not valid – repeat with glasses/trial lens on", right.value.en)
        assertNull(right.note)
        assertFalse(right.text.en.contains("Reduced")); assertFalse(right.text.en.contains("cataract"))
        assertEquals(SProfile.resContrastInvalid.hi, right.value.hi)
        // a valid reduced result still gets its note
        val left = lines.first { it.label.en == "Contrast L" }
        assertTrue(left.value.en.contains("Reduced")); assertEquals(SProfile.eyeHealthNote, left.note)
        // -4.75 D in the right eye: the correction goes on before the contrast test
        val q = QuickEstimate(PowerRange.farEye(210.0), PowerRange.farEye(1250.0))
        val c = rules.screening.contrast
        assertTrue(com.drishtisaathi.engine.profile.Contrast.needsCorrection(ScreeningPlan.eyePower(Eye.RIGHT, q, adult), c))
        assertFalse(com.drishtisaathi.engine.profile.Contrast.needsCorrection(ScreeningPlan.eyePower(Eye.LEFT, q, adult), c))
        val state = f.state(adult, quick = q).copy(screening = s)
        val qr = QrPayload.json(state, ProfileView.build(state, rules, engine), rules, "1.1")["screening"]!!.jsonObject["contrast"]!!.jsonArray
        assertEquals("R:none:INVALID", qr[0].jsonPrimitive.content)
    }

    // ---- 6. disclaimer and header ----

    @Test fun `6 - the disclaimer is printed once and the header has the date, age band and session language`() {
        val state = f.state(adult).copy(screening = straight, createdAt = 1_763_208_000_000, sessionId = 2)
        val lines = share(state)
        assertEquals(1, lines.count { it.contains(S.disclaimer.en) })
        assertEquals(S.disclaimer.en, lines.last())
        assertEquals("Date: 15 Nov 2025 · Age: 30-44 · Language: English", lines.first())
        assertEquals("तारीख़: 15 Nov 2025 · उम्र: 30-44 · भाषा: हिन्दी", QrPayload.headerLine(state.copy(lang = Lang.HI)).hi)
        assertTrue(QrPayload.headerLine(f.state(adult.copy(age = null))).en.contains("Age: not given"))
        val blocks = PdfContent.shopCopy(state, rules, f.ctx, Lang.EN)
        assertEquals(1, blocks.count { it is Block.Para && it.text == S.disclaimerBoth })
        assertTrue("Date: 15 Nov 2025 · Age: 30-44 · Language: English" in f.texts(blocks))
        assertEquals(1, PdfContent.customerCopy(state, rules).count { it is Block.Para && it.text == S.disclaimerBoth })
    }

    // ---- 7. Amsler red flag ----

    @Test fun `7 - report S32 - no grid red flag next to two straight grids, and a real one names the eye`() {
        val s32 = f.state(emergency.copy(straightLinesBent = true)).copy(screening = straight, tests = VisionTests(amslerAbnormal = true), testsDone = true)
        val out = share(s32) + shop(s32)
        assertTrue(out.none { it.contains("on the grid test") })
        assertTrue(out.any { it == "Grid R: lines straight" }); assertTrue(out.any { it == "Grid L: lines straight" })
        // the customer's own answer is still reported, worded as an answer
        assertTrue("Straight lines look bent or wavy (the customer's answer)" in out)

        val bad = straight.copy(amsler = listOf(straight.amsler[0], AmslerEyeResult(Eye.LEFT, true, listOf(AmslerCell(1, 1)), 12, 20, 300.0)))
        val st = f.state(adult).copy(screening = bad)
        val gate = st.gate(rules)
        assertEquals(listOf("Wavy, bent or missing lines on the grid test – left eye"), SProfile.redFlagLines(gate).map { it.en })
        assertTrue(SProfile.redFlagLines(gate).single().hi.endsWith("बाईं आँख"))
        assertTrue("Wavy, bent or missing lines on the grid test – left eye" in share(st))
        val reasons = BridgeProfile.build(st, rules, f.consent, "1.1")["triage"]!!.jsonObject["redFlagReasons"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("Wavy, bent or missing lines on the grid test – left eye"), reasons)
    }

    // ---- 8. emergency short path ----

    @Test fun `8 - report S32 - on the emergency short path the rings say 'Skipped' and the header says 'Short path (emergency)'`() {
        val st = f.state(emergency.copy(occupation = null, screenHours = null)).copy(screening = straight)
        val view = ProfileView.build(st, rules, engine)
        assertTrue(view.gate.emergency)
        val rings = LensText.ringLines(view.profile, view.gate.emergency).map { it.en }
        assertEquals(listOf("Work", "Digital", "Mobility", "Leisure").map { "$it: Skipped – emergency short path" }, rings)
        val lines = share(st)
        assertEquals("Short path (emergency)", lines[1])
        assertTrue(lines.containsAll(rings)); assertTrue(lines.none { it.contains("not answered") })
        val pdf = PdfContent.shopCopy(st, rules, f.ctx, Lang.EN, view = view)
        assertTrue("Short path (emergency)" in f.texts(pdf))
        assertTrue(pdf.filterIsInstance<Block.Rings>().single().rings.all { it.label == "Skipped – emergency short path" })
        assertTrue(f.texts(PdfContent.shopCopy(st, rules, f.ctx, Lang.HI, view = view)).contains(SProfile.shortPathHeader.hi))
        // the full path still says "not answered"
        val routine = f.state(adult.copy(occupation = null, screenHours = null))
        assertTrue(share(routine).any { it == "Work: not answered" })
        assertTrue(share(routine).none { it == "Short path (emergency)" })
    }

    // ---- 9. optometrist-only power ----

    @Test fun `9 - URGENT and CHILD_MYOPIA reports say 'For the optometrist only - R', never 'Your approximate power'`() {
        val urgent = f.state(emergency)
        val child = f.state(adult.copy(age = 12))
        assertEquals(ReportTag.URGENT, urgent.gate(rules).tag); assertEquals(ReportTag.CHILD_MYOPIA, child.gate(rules).tag)
        for (st in listOf(urgent, child)) {
            val out = share(st) + shop(st)
            assertTrue(out.none { it.contains("Your approximate power") })
            assertTrue(out.any { it.startsWith("For the optometrist only: R −1.75 to −2.25 D") })
            assertTrue(out.any { it.startsWith("For the optometrist only: L −1.50 to −2.00 D") })
            val hi = QrPayload.shareLines(st, ProfileView.build(st, rules, engine), rules).map { it.hi }
            assertTrue(hi.any { it.startsWith("सिर्फ़ ऑप्टोमेट्रिस्ट के लिए: R ") }); assertTrue(hi.none { it.contains("आपका अनुमानित") })
        }
        // a routine report keeps the customer wording
        val routine = share(f.state(adult))
        assertTrue(routine.any { it.startsWith("R: Your approximate power") })
        assertTrue(routine.none { it.contains("For the optometrist only") })
    }
}
