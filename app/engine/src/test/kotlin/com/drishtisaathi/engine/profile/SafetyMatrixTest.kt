package com.drishtisaathi.engine.profile

import com.drishtisaathi.engine.Budget
import com.drishtisaathi.engine.Eye
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.QuickEstimate
import com.drishtisaathi.engine.QuickEye
import com.drishtisaathi.engine.QuickTarget
import com.drishtisaathi.engine.TestAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The safety matrix (test pass before submission), one row per case: every red flag alone, emergency or not,
 * children with and without minus power, a red flag on a child, and the lens exclusions.
 */
class SafetyMatrixTest {
    private val rules = TestAssets.rules
    private val cfg = rules.safety
    private val catalog = LensCatalog.parse(TestAssets.text(LensCatalog.ASSET))
    private val engine = LensEngine(catalog, rules.lensEngine)
    private val words = LensWords(L10n("far", "दूर"), L10n("near", "पास"), L10n("tired eyes", "आँखों की थकान"), L10n("headaches", "सिरदर्द"),
        L10n("neck pain", "गर्दन दर्द"), L10n("eye strain", "आँखों पर ज़ोर"))
    private val none = SurveyAnswers()
    private fun eye(se: Double?) = QuickEye(se, se?.let { -100.0 / it }, QuickTarget.WHITE, noMyopiaAtReach = se == null)
    private val minus = QuickEstimate(right = eye(-2.0), left = eye(-1.75))
    private val clear = QuickEstimate(right = eye(null), left = eye(null))
    private fun signs(vararg f: RedFlag) = SurveyAnswers().with(Safety.SIGNS_QUESTION, f.map { it.name })
    private fun grid(eye: Eye) = ScreeningResults(amsler = listOf(
        AmslerEyeResult(eye, true, listOf(AmslerCell(3, 4)), 12, 20),
        AmslerEyeResult(if (eye == Eye.RIGHT) Eye.LEFT else Eye.RIGHT, false, emptyList(), 12, 20),
    ))

    /** One row: the flag expected, and how a session gets it. */
    private data class Row(val name: String, val flag: RedFlag, val a: InterviewAnswers, val s: SurveyAnswers = SurveyAnswers(),
                           val screening: ScreeningResults? = null, val eyes: List<Eye> = emptyList())

    private val adult = InterviewAnswers(age = 40, redFlagFlashes = false, redFlagOther = false)
    private val rows = listOf(
        Row("sudden vision loss", RedFlag.SUDDEN_VISION_LOSS, adult.copy(redFlagOther = true), signs(RedFlag.SUDDEN_VISION_LOSS)),
        Row("pain / redness", RedFlag.EYE_PAIN_REDNESS, adult.copy(redFlagOther = true), signs(RedFlag.EYE_PAIN_REDNESS)),
        Row("new flashes", RedFlag.NEW_FLASHES, adult.copy(redFlagFlashes = true), signs(RedFlag.NEW_FLASHES)),
        Row("new floaters", RedFlag.NEW_FLOATERS, adult.copy(redFlagFlashes = true), signs(RedFlag.NEW_FLOATERS)),
        Row("curtain / shadow", RedFlag.CURTAIN_SHADOW, adult.copy(redFlagFlashes = true), signs(RedFlag.CURTAIN_SHADOW)),
        Row("double vision (follow-up)", RedFlag.DOUBLE_VISION, adult.copy(redFlagOther = true), signs(RedFlag.DOUBLE_VISION)),
        Row("double vision (survey)", RedFlag.DOUBLE_VISION, adult, SurveyAnswers().with(Safety.DOUBLE_VISION_QUESTION, listOf(SurveyQuestion.YES))),
        Row("grid distortion, right eye", RedFlag.AMSLER_DISTORTION, adult, screening = grid(Eye.RIGHT), eyes = listOf(Eye.RIGHT)),
        Row("grid distortion, left eye", RedFlag.AMSLER_DISTORTION, adult, screening = grid(Eye.LEFT), eyes = listOf(Eye.LEFT)),
        Row("bent lines reported", RedFlag.BENT_LINES_REPORTED, adult.copy(straightLinesBent = true)),
        Row("flashes group, sign not picked", RedFlag.FLASHES_FLOATERS_CURTAIN_UNSPECIFIED, adult.copy(redFlagFlashes = true)),
        Row("sudden-loss group, sign not picked", RedFlag.SUDDEN_LOSS_PAIN_DOUBLE_UNSPECIFIED, adult.copy(redFlagOther = true)),
    )

    @Test fun `the matrix covers every red flag the app knows`() {
        assertEquals(RedFlag.entries.toSet(), rows.map { it.flag }.toSet())
    }

    @Test fun `every red flag alone - URGENT, no lens, power for the optometrist only`() {
        for (r in rows) {
            val g = Safety.gate(r.a, r.s, cfg, quick = minus, screening = r.screening)
            assertEquals(r.name, listOf(r.flag), g.redFlags)
            assertEquals(r.name, ReportTag.URGENT, g.tag)
            assertTrue(r.name, g.urgent); assertFalse("${r.name}: lens", g.lensAllowed)
            assertTrue("${r.name}: power label", g.powerForOptometristOnly)
            assertEquals("${r.name}: grid eyes", r.eyes, g.amslerEyes)
            // the lens engine gives nothing at all, whatever the customer answered
            val rich = ProfileInputs(age = 40, screenHours = 9.0, deskHours = 8.0, drive = DriveFrequency.DAILY, glare = Freq.OFTEN,
                outdoorHours = 6.0, onePair = true, budget = Budget.HIGH, leisure = setOf("WATER"))
            val p = engine.plan(rich, g, words)
            assertFalse(r.name, p.allowed); assertEquals(r.name, emptyList<String>(), p.ids)
            assertNull(r.name, p.type); assertNull(p.design); assertNull(p.coating); assertNull("${r.name}: no referral product", p.referralInfo)
            assertEquals(ReportTag.URGENT, p.tag)
        }
    }

    @Test fun `emergency signs take the short path, the other red flags the full check`() {
        assertEquals(listOf(RedFlag.SUDDEN_VISION_LOSS, RedFlag.NEW_FLASHES, RedFlag.CURTAIN_SHADOW).map { it.name }.toSet(), cfg.emergencySigns.map { it.toString() }.toSet())
        val short = listOf(CheckStep.POWER, CheckStep.AMSLER)
        for (r in rows) {
            val g = Safety.gate(r.a, r.s, cfg, screening = r.screening)
            val emergency = r.flag in setOf(RedFlag.SUDDEN_VISION_LOSS, RedFlag.NEW_FLASHES, RedFlag.CURTAIN_SHADOW) || r.flag.unspecified
            assertEquals("${r.name}: emergency", emergency, g.emergency)
            assertEquals("${r.name}: steps", if (emergency) short else CheckStep.entries.toList(), Safety.steps(g, cfg))
            // the power of each eye is measured on both paths
            assertTrue(r.name, CheckStep.POWER in Safety.steps(g, cfg))
        }
    }

    @Test fun `ages 3, 8, 12 and 17 with a minus power - CHILD_MYOPIA, no lens, referral information only`() {
        for (age in listOf(3, 8, 12, 17)) for (q in listOf(minus, QuickEstimate(right = eye(-0.25), left = eye(null)), QuickEstimate(left = eye(-6.0)))) {
            val g = Safety.gate(InterviewAnswers(age = age, redFlagFlashes = false, redFlagOther = false), none, cfg, quick = q)
            assertEquals("age $age", ReportTag.CHILD_MYOPIA, g.tag)
            assertTrue(g.child); assertTrue(g.childMyopia); assertFalse(g.urgent); assertFalse(g.lensAllowed); assertTrue(g.powerForOptometristOnly)
            assertEquals("the full check still runs", CheckStep.entries.toList(), Safety.steps(g, cfg))
            val p = engine.plan(ProfileInputs(age = age, myopic = true, screenHours = 6.0, budget = Budget.HIGH), g, words)
            assertFalse(p.allowed); assertEquals(emptyList<String>(), p.ids)
            assertEquals("age $age: myopia management is information for the referral", "myopia_management", p.referralInfo?.id)
        }
    }

    @Test fun `17 without myopia - SmartLife Young only, labelled optometrist confirms - 18 is an adult`() {
        val others = catalog.lensTypes.map { it.id }.minus("single_vision") + catalog.designs.map { it.id }.minus("smartlife_young")
        for (age in listOf(3, 8, 12, 17)) for (b in Budget.entries) {
            val g = Safety.gate(InterviewAnswers(age = age), none, cfg, quick = clear)
            assertFalse(g.childMyopia); assertTrue(g.lensAllowed); assertTrue(g.optometristConfirms); assertEquals(ReportTag.ROUTINE, g.tag)
            val p = engine.plan(ProfileInputs(age = age, screenHours = 9.0, deskHours = 8.0, drive = DriveFrequency.DAILY, glare = Freq.OFTEN, budget = b,
                headache = Freq.OFTEN, workActivities = setOf("DESKTOP", "MEETINGS")), g, words)
            assertTrue(p.allowed); assertTrue(p.optometristConfirms)
            assertEquals("age $age $b", "smartlife_young", p.design?.id)
            assertEquals("single_vision", p.type?.id); assertEquals("dv_kids", p.coating?.id)
            assertEquals("age $age $b: no adult design or lens type", emptyList<String>(), p.ids.filter { it in others })
        }
        val adult = Safety.gate(InterviewAnswers(age = 18), none, cfg, quick = minus)
        assertFalse(adult.child); assertFalse(adult.childMyopia); assertTrue(adult.lensAllowed)
        assertFalse("smartlife_young" in engine.plan(ProfileInputs(age = 18, myopic = true), adult, words).ids)
    }

    @Test fun `a red flag on a child - URGENT wins and the child tag is kept`() {
        for (r in rows) for (age in listOf(3, 8, 12, 17)) {
            val g = Safety.gate(r.a.copy(age = age), r.s, cfg, quick = minus, screening = r.screening)
            assertEquals("${r.name} at $age", ReportTag.URGENT, g.tag)
            assertTrue(g.child); assertTrue("${r.name} at $age: still child myopia", g.childMyopia); assertFalse(g.lensAllowed)
            val p = engine.plan(ProfileInputs(age = age, myopic = true), g, words)
            assertFalse(p.allowed); assertEquals(ReportTag.URGENT, p.tag); assertNull("urgent comes first: no product at all", p.referralInfo)
        }
    }

    @Test fun `an adult without a red flag gets lens suggestions, each with a why`() {
        val g = Safety.gate(adult, none, cfg, quick = minus)
        assertTrue(g.lensAllowed); assertFalse(g.powerForOptometristOnly); assertEquals(ReportTag.ROUTINE, g.tag)
        val personas = listOf(
            ProfileInputs(age = 24, myopic = true, screenHours = 8.0, budget = Budget.MID),
            ProfileInputs(age = 38, screenHours = 6.0, tiredEyes = Freq.OFTEN, deskHours = 7.0, headache = Freq.SOMETIMES, budget = Budget.HIGH, workActivities = setOf("DESKTOP", "MEETINGS")),
            ProfileInputs(age = 52, addD = 1.5, drive = DriveFrequency.DAILY, glare = Freq.OFTEN, budget = Budget.MID, firstProgressive = true),
            ProfileInputs(age = 61, addD = 2.25, outdoorHours = 5.0, onePair = true, budget = Budget.LOW, leisure = setOf("FARMING_FIELD")),
            ProfileInputs(age = 30, budget = Budget.LOW),
        )
        for (i in personas) {
            val p = engine.plan(i, g, words)
            assertTrue(p.allowed); assertNotNull(p.type); assertNotNull(p.design); assertNotNull(p.coating)
            assertTrue("UVProtect always", p.materials.any { it.id == "uvprotect" })
            for (s in p.all) assertTrue("age ${i.age}: ${s.id} has no why", s.why.isNotEmpty() || s.reasons.isNotEmpty())
            assertTrue(p.secondPairs.size <= rules.lensEngine.maxSecondPairs)
        }
    }

    @Test fun `exclusions - never BlueProtect with BlueGuard, never PhotoFusion X for night glare`() {
        val g = SafetyGate()
        val glareOnly = ProfileInputs(age = 45, drive = DriveFrequency.DAILY, glare = Freq.OFTEN, lowLightDriving = Freq.OFTEN, lowLightTrouble = Freq.OFTEN, contrastReduced = true)
        for (b in Budget.entries + listOf(null)) for (outdoor in listOf(null, 0.0, 1.5, 2.0, 8.0)) for (onePair in listOf(null, false)) {
            val p = engine.plan(glareOnly.copy(budget = b, outdoorHours = outdoor, onePair = onePair), g, words)
            assertFalse("night glare never gives PhotoFusion X ($b, $outdoor h, onePair $onePair)", "photofusion_x" in p.ids)
        }
        for (b in Budget.entries + listOf(null)) for (screen in listOf(0.0, 3.0, 4.0, 9.0)) for (tired in listOf(null, Freq.NEVER, Freq.OFTEN)) for (onePair in listOf(null, true)) {
            val p = engine.plan(ProfileInputs(age = 33, screenHours = screen, tiredEyes = tired, outdoorHours = 3.0, onePair = onePair, budget = b), g, words)
            val blue = p.ids.any { it == "blueguard" || it == "photofusion_x" }
            assertFalse("BlueProtect with BlueGuard ($b, $screen h)", blue && "dv_blueprotect" in p.ids)
            assertFalse("BlueGuard is not added to PhotoFusion X", "blueguard" in p.ids && "photofusion_x" in p.ids)
        }
        // and the check that enforces it drops a BlueProtect coating that was put next to BlueGuard by hand
        val uv = engine.plan(ProfileInputs(age = 33, screenHours = 9.0), g, words)
        val forced = uv.copy(coating = uv.coating!!.copy(id = "dv_blueprotect"))
        assertTrue(forced.materials.any { it.id == "blueguard" })
        assertNull(engine.validate(forced).coating)
    }
}
