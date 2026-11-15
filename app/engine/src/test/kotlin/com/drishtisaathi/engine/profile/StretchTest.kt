package com.drishtisaathi.engine.profile

import com.drishtisaathi.engine.Budget
import com.drishtisaathi.engine.EyeRx
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.KnownRx
import com.drishtisaathi.engine.Occupation
import com.drishtisaathi.engine.PowerKnown
import com.drishtisaathi.engine.QuickEstimate
import com.drishtisaathi.engine.QuickEye
import com.drishtisaathi.engine.QuickTarget
import com.drishtisaathi.engine.ScreenHours
import com.drishtisaathi.engine.TestAssets
import com.drishtisaathi.engine.VisionProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Randomized edge tests (test pass before submission): 10 000 cases each, fixed seed, so a failure repeats exactly.
 * They check properties that must hold for every input, not single examples.
 */
class StretchTest {
    private val rules = TestAssets.rules
    private val lens = rules.lensAssisted
    private val catalog = LensCatalog.parse(TestAssets.text(LensCatalog.ASSET))
    private val engine = LensEngine(catalog, rules.lensEngine)
    private val words = LensWords(L10n("far", "दूर"), L10n("near", "पास"), L10n("tired eyes", "आँखों की थकान"), L10n("headaches", "सिरदर्द"),
        L10n("neck pain", "गर्दन दर्द"), L10n("eye strain", "आँखों पर ज़ोर"))
    private val survey = SurveyAssets.file

    private fun onGrid(v: Double, step: Double = 0.25) = abs(v / step - (v / step).roundToInt()) < 1e-9
    private fun <T> Random.pick(list: List<T>): T = list[nextInt(list.size)]
    private fun <T> Random.maybe(p: Double = 0.7, f: () -> T): T? = if (nextDouble() < p) f() else null

    // ---- lens-assisted mode ----

    @Test fun `lens-assisted - 10 000 lens and distance pairs give a finite total on the 0_25 grid with an ordered range, never plus`() {
        val rnd = Random(20261115)
        repeat(N) {
            val lensD = -rnd.nextInt(0, 41) * 0.25                                   // 0 to -10.00 D
            val cm = if (it % 50 == 0) rnd.pick(listOf(5.0, 600.0, 33.3, 100.0)) else rnd.nextDouble(5.0, 600.0)
            val raw = -100.0 / cm
            assertTrue(LensAssisted.validLens(lensD, lens))
            val out = LensAssisted.total(lensD, raw, lens)
            assertTrue("lens $lensD at $cm cm", out is LensAssisted.Outcome.Measured)
            val t = (out as LensAssisted.Outcome.Measured).total
            for (v in listOf(t.totalD, t.residualD, t.effectiveLensD, t.rangeFromD, t.rangeToD)) assertTrue("lens $lensD at $cm cm: $v", v.isFinite())
            assertTrue("total ${t.totalD} is on the grid", onGrid(t.totalD)); assertTrue(onGrid(t.residualD))
            assertTrue("never a plus power (lens $lensD at $cm cm gave ${t.totalD})", t.totalD <= 0.0 && t.residualD <= 0.0)
            assertTrue("range ordered", t.rangeToD <= t.totalD && t.totalD <= t.rangeFromD)
            assertEquals(2 * lens.rangeHalfWidthD, t.rangeFromD - t.rangeToD, 1e-9)
            // a minus lens held in front of the eye never counts for more than its own power, and from 4 D it counts for less
            assertTrue(abs(t.effectiveLensD) <= abs(lensD) + 1e-12)
            if (abs(lensD) >= lens.vertexFromAbsD) assertTrue("vertex: $lensD -> ${t.effectiveLensD}", abs(t.effectiveLensD) < abs(lensD))
            else assertEquals(lensD, t.effectiveLensD, 0.0)
            // the total is the lens plus the residual, within the rounding
            assertEquals(t.effectiveLensD + raw, t.totalD, 0.125 + 1e-9)
            assertEquals(t.totalD < lens.highMyopiaBeyondSeD, t.highMyopia)
        }
    }

    @Test fun `lens-assisted - a lens that is too strong is 'use a weaker lens', never a number, and a typed plus or off-grid lens is refused`() {
        val rnd = Random(7)
        repeat(N) {
            val lensD = -rnd.nextInt(0, 41) * 0.25
            assertEquals(LensAssisted.Outcome.LensTooStrong, LensAssisted.total(lensD, null, lens))
            val bad = rnd.pick(listOf(0.25, 3.0, -lens.maxLensAbsD - 0.25, -1.1, -0.3, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, rnd.nextDouble(0.01, 20.0)))
            assertFalse("lens $bad", LensAssisted.validLens(bad, lens))
        }
        assertFalse(LensAssisted.validLens(null, lens))
    }

    @Test fun `lens-assisted - a residual at or beyond minus 3_00 asks for a stronger lens once, and the stronger lens is always stronger`() {
        assertEquals(-3.0, lens.repeatIfResidualFromSeD, 0.0); assertEquals(1, lens.maxStrongerRepeats)
        val rnd = Random(11)
        repeat(N) {
            val residual = -rnd.nextInt(0, 81) * 0.25 + (if (rnd.nextBoolean()) 0.0 else rnd.nextDouble(-0.12, 0.12))
            val repeats = rnd.nextInt(0, 6)
            val ask = LensAssisted.needsStrongerLens(residual, repeats, lens)
            assertEquals("residual $residual after $repeats repeats", residual <= -3.0 && repeats == 0, ask)
            assertFalse("never more than one repeat", repeats >= 1 && ask)
            // the first suggestion: a minus lens on the 1 D grid, about 2 D weaker than the eye
            val first = -rnd.nextInt(15, 49) * 0.25                                  // -3.75 to -12.00
            val held = LensAssisted.suggestLens(first, lens)
            assertTrue("$first -> $held", held <= 0.0 && held > first && onGrid(held, lens.suggestStepD) && LensAssisted.validLens(held, lens))
            // the repeat: stronger than the lens that was too weak, still a minus lens, still weaker than the eye
            val total = held + residual
            if (ask && total >= -lens.maxLensAbsD) {
                val next = LensAssisted.strongerLens(total, held, lens)
                assertTrue("held $held, total $total -> $next", next <= held - lens.suggestStepD + 1e-9 && next <= 0.0 && next.isFinite())
            }
        }
        assertFalse(LensAssisted.needsStrongerLens(null, 0, lens))
        assertFalse(LensAssisted.needsStrongerLens(Double.NaN, 0, lens))
    }

    // ---- survey ----

    private val junk = listOf("", " ", "abc", "-5", "0", "24", "25", "99", "1e9", "-0", "12.5", "YES", "NO", "MAYBE", "null", "१२", "3 hours")

    /** A random set of answers: some skipped, some valid, some nonsense, some contradicting each other. */
    private fun randomSurvey(rnd: Random): SurveyAnswers {
        var s = SurveyAnswers()
        if (rnd.nextInt(20) == 0) return s                                           // everything skipped
        for (q in survey.questions) {
            if (rnd.nextDouble() < 0.3) continue
            val picked: List<String> = when {
                rnd.nextDouble() < 0.15 -> listOf(rnd.pick(junk))
                q.kind == SurveyKind.NUMBER -> listOf(rnd.pick(listOf(q.min, q.max, q.max + 1, q.max * 10, q.min - 1, rnd.nextInt(q.min, q.max + 1))).toString())
                q.kind == SurveyKind.MULTI -> q.optionCodes.filter { rnd.nextBoolean() } + (if (rnd.nextInt(10) == 0) listOf("NOT_AN_OPTION") else emptyList())
                else -> listOf(rnd.pick(q.optionCodes))
            }
            s = s.with(q.id, picked)
        }
        return s
    }

    private fun randomAnswers(rnd: Random): InterviewAnswers = InterviewAnswers(
        age = rnd.maybe(0.9) { rnd.pick(listOf(0, 1, 3, 17, 18, 29, 30, 44, 45, 120, rnd.nextInt(0, 121))) },
        screenHours = rnd.maybe { rnd.pick(ScreenHours.entries) }, occupation = rnd.maybe { rnd.pick(Occupation.entries) },
        nightDriving = rnd.maybe { rnd.nextBoolean() }, outdoorWork = rnd.maybe { rnd.nextBoolean() }, headacheStrain = rnd.maybe { rnd.nextBoolean() },
        nearBlur = rnd.maybe { rnd.nextBoolean() }, distanceBlur = rnd.maybe { rnd.nextBoolean() }, straightLinesBent = rnd.maybe(0.2) { rnd.nextBoolean() },
        redFlagFlashes = rnd.maybe(0.5) { rnd.nextInt(8) == 0 }, redFlagOther = rnd.maybe(0.5) { rnd.nextInt(8) == 0 },
        budget = rnd.maybe { rnd.pick(Budget.entries) }, firstTimeWearer = rnd.maybe { rnd.nextBoolean() }, visionProblem = rnd.maybe { rnd.pick(VisionProblem.entries) },
        powerKnown = rnd.maybe(0.3) { rnd.pick(PowerKnown.entries) }, knownRx = rnd.maybe(0.2) { KnownRx(EyeRx(-rnd.nextInt(0, 30) * 0.25), EyeRx(rnd.nextInt(-20, 12) * 0.25)) },
    )

    private fun randomQuick(rnd: Random): QuickEstimate? = rnd.maybe(0.8) {
        fun eye(): QuickEye? = rnd.maybe(0.85) {
            val se = rnd.maybe(0.75) { -rnd.nextInt(0, 49) * 0.25 }
            QuickEye(se, se?.takeIf { it < 0 }?.let { -100.0 / it }, QuickTarget.WHITE, noMyopiaAtReach = se == null)
        }
        QuickEstimate(right = eye(), left = eye(), addD = rnd.maybe(0.3) { rnd.nextInt(0, 13) * 0.25 })
    }

    @Test fun `survey - 10 000 random, missing and contradictory answer sets never crash and the rings stay within 0 to 100 with matching labels`() {
        val rnd = Random(42)
        val vp = rules.visionProfile
        repeat(N) { n ->
            val a = randomAnswers(rnd); val s = randomSurvey(rnd); val quick = randomQuick(rnd)
            val what = "case $n: $a / ${s.values}"
            // the survey's own logic
            val pruned = Survey.prune(survey, a, s)
            assertTrue(what, pruned.values.keys.all { it in s.values })
            Survey.nextUnanswered(survey, a, pruned)
            assertTrue(Survey.plannedSeconds(survey, a, pruned) in 0..survey.maxMinutes * 60 + survey.questions.size * survey.secondsPerQuestion)
            // gate, inputs, rings
            val gate = Safety.gate(a, s, rules.safety, quick = quick)
            val i = ProfileInputs.from(a, s, rules.lensEngine, quick, gate = gate)
            val p = VisionProfile.from(i, vp)
            for ((id, r) in p.rings) assertTrue("$what: $id = ${r.score}", r.score in 0..100)
            assertTrue(p.overall in 0..100)
            assertEquals(what, VisionProfile.leisureLabel(p.leisure.score, vp).name, p.leisure.label)
            assertEquals(what, when { p.work.score >= vp.work.nearFocusedFrom -> "NEAR_FOCUSED"; p.work.score < vp.work.distanceFocusedBelow -> "DISTANCE_FOCUSED"; else -> "BALANCED" }, p.work.label)
            val h = i.screenHours
            assertEquals(what, when { h == null || h < vp.digital.connectedFromHours -> "LIGHT"; h > vp.digital.nativeAboveHours -> "DIGITAL_NATIVE"; else -> "CONNECTED" }, p.digital.label)
            assertEquals(what, when (i.drive) { DriveFrequency.DAILY -> "DAILY"; DriveFrequency.WEEKLY -> "WEEKLY"; else -> "RARELY" }, p.mobility.label)
            // nothing answered = nothing claimed
            if (s.values.isEmpty() && a.screenHours == null) assertFalse("$what: digital ring", p.digital.known)
            // and the lens engine takes the same inputs without crashing, respecting the gate
            val plan = engine.plan(i, gate, words)
            assertEquals(what, gate.lensAllowed, plan.allowed)
            if (!gate.lensAllowed) assertEquals(what, emptyList<String>(), plan.ids)
        }
    }

    @Test fun `survey - all skipped, age 0 and age 120 give defaults, not a crash`() {
        for (age in listOf(null, 0, 1, 120)) {
            val a = InterviewAnswers(age = age)
            val gate = Safety.gate(a, SurveyAnswers(), rules.safety)
            val i = ProfileInputs.from(a, SurveyAnswers(), rules.lensEngine, gate = gate)
            val p = VisionProfile.from(i, rules.visionProfile)
            assertEquals("age $age", listOf(false, false, false, false), p.rings.map { it.second.known })
            assertEquals(rules.visionProfile.work.defaultScore, p.work.score); assertEquals(0, p.digital.score); assertEquals(0, p.leisure.score)
            assertEquals("LIGHT", p.digital.label); assertEquals("RARELY", p.mobility.label); assertEquals("LOW", p.leisure.label)
            assertEquals("age $age", age != null && age < rules.safety.childUnderAge, gate.child)
            val plan = engine.plan(i, gate, words)
            assertTrue(plan.allowed); assertTrue(plan.ids.all { it in catalog.ids })
        }
        // hours beyond a day are capped by the ring, never shown above 100
        val silly = ProfileInputs(screenHours = 99.0, outdoorHours = 500.0, deskHours = 1000.0, leisure = setOf("WATER", "OUTDOOR_SPORT", "FARMING_FIELD"))
        val p = VisionProfile.from(silly, rules.visionProfile)
        assertEquals(100, p.digital.score); assertEquals(100, p.leisure.score); assertEquals("HIGH", p.leisure.label); assertEquals("DIGITAL_NATIVE", p.digital.label)
        val minus = VisionProfile.from(ProfileInputs(screenHours = -5.0, outdoorHours = -1.0), rules.visionProfile)
        assertEquals(0, minus.digital.score); assertEquals(0, minus.leisure.score)
    }

    // ---- lens engine ----

    private val activities = rules.visionProfile.work.nearWeights.keys.toList() + listOf("MEETINGS", "DESKTOP", "FIELD_FARM", "UNKNOWN_ACTIVITY")
    private val leisure = rules.visionProfile.leisure.activityBonus.keys.toList() + listOf("WATER", "OUTDOOR_SPORT", "FARMING_FIELD", "UNKNOWN")

    private fun randomCustomer(rnd: Random): ProfileInputs {
        fun freq() = rnd.maybe { rnd.pick(Freq.entries) }
        fun hours() = rnd.maybe { rnd.pick(listOf(0.0, 0.5, 1.9, 2.0, 3.9, 4.0, 6.0, 8.0, 12.0, 24.0, 30.0, rnd.nextDouble(0.0, 16.0))) }
        return ProfileInputs(
            age = rnd.maybe(0.9) { rnd.pick(listOf(0, 3, 8, 12, 17, 18, 29, 30, 39, 40, 44, 45, 60, 120, rnd.nextInt(0, 121))) },
            addD = rnd.maybe(0.4) { rnd.nextInt(0, 13) * 0.25 }, myopic = rnd.nextBoolean(), screenHours = hours(),
            tiredEyes = freq(), nearBlur = freq(), headache = freq(), neckPain = freq(), focusStruggle = freq(), lowLightTrouble = freq(),
            deskHours = hours(), workActivities = activities.filter { rnd.nextInt(4) == 0 }.toSet(), drive = rnd.maybe { rnd.pick(DriveFrequency.entries) },
            glare = freq(), lowLightDriving = freq(), outdoorHours = hours(), leisure = leisure.filter { rnd.nextInt(4) == 0 }.toSet(),
            indoorOutdoor = rnd.maybe { rnd.pick(listOf("BOTH", "INDOOR", "OUTDOOR", "???")) }, onePair = rnd.maybe { rnd.nextBoolean() },
            firstProgressive = rnd.maybe { rnd.nextBoolean() }, budget = rnd.maybe { rnd.pick(Budget.entries) }, contrastReduced = rnd.nextInt(5) == 0,
            problem = rnd.maybe { rnd.pick(VisionProblem.entries) },
        )
    }

    private fun randomGate(rnd: Random, i: ProfileInputs): SafetyGate {
        val flags = if (rnd.nextInt(6) == 0) listOf(rnd.pick(RedFlag.entries)) else emptyList()
        val child = i.age != null && i.age!! < rules.safety.childUnderAge
        val childMyopia = child && i.myopic
        val tag = when { flags.isNotEmpty() -> ReportTag.URGENT; childMyopia -> ReportTag.CHILD_MYOPIA; else -> ReportTag.ROUTINE }
        return SafetyGate(flags, Safety.isEmergency(flags, rules.safety), child, childMyopia, tag)
    }

    @Test fun `lens engine - 10 000 random customers get valid catalogue ids, at most two second pairs, and the safety gate is respected`() {
        val rnd = Random(2026)
        val kinds = mapOf(SuggestionKind.LENS_TYPE to catalog.lensTypes.map { it.id }, SuggestionKind.DESIGN to catalog.designs.map { it.id },
            SuggestionKind.MATERIAL to catalog.materials.map { it.id }, SuggestionKind.COATING to catalog.coatings.map { it.id })
        var allowed = 0; var blocked = 0; var withoutWhy = 0
        val noWhy = sortedMapOf<String, Int>()
        repeat(N) { n ->
            val i = randomCustomer(rnd); val gate = randomGate(rnd, i)
            val what = "case $n: $i / $gate"
            assertEquals(what, catalog.ids, engine.triggers(i).keys)
            val p = engine.plan(i, gate, words)
            assertEquals(what, gate.tag, p.tag)
            if (!gate.lensAllowed) {
                blocked++
                assertFalse(what, p.allowed); assertEquals(what, emptyList<String>(), p.ids)
                assertEquals(what, if (gate.childMyopia && !gate.urgent) "myopia_management" else null, p.referralInfo?.id)
                return@repeat
            }
            allowed++
            assertTrue(what, p.allowed)
            assertTrue("$what: ${p.ids}", p.ids.all { it in catalog.ids })
            assertEquals("$what: no product twice", p.ids.size, p.ids.toSet().size)
            for (s in listOfNotNull(p.type, p.design, p.coating) + p.materials) assertTrue("$what: ${s.id} as ${s.kind}", s.id in kinds.getValue(s.kind))
            assertTrue(what, p.type != null && p.design != null && p.coating != null)
            assertTrue("$what: UVProtect", p.materials.first().id == "uvprotect" && p.materials.size <= 2)
            assertTrue("$what: second pairs ${p.secondPairs.map { it.id }}", p.secondPairs.size <= 2 && p.secondPairs.all { it.kind == SuggestionKind.SECOND_PAIR })
            assertTrue("$what: at most one sun lens", p.secondPairs.count { it.id.startsWith("sun_") } <= 1)
            assertFalse("$what: myopia management is never a lens choice", "myopia_management" in p.ids)
            // exclusions
            val blue = p.materials.any { it.id == "blueguard" || it.id == "photofusion_x" }
            assertFalse("$what: BlueProtect with BlueGuard", blue && p.coating!!.id == "dv_blueprotect")
            assertFalse(what, p.materials.any { it.id == "blueguard" } && p.materials.any { it.id == "photofusion_x" })
            if ("photofusion_x" in p.ids) assertTrue("$what: PhotoFusion X only for outdoor hours and one pair", i.onePair == true && (i.outdoorHours ?: 0.0) >= rules.lensEngine.photoFusionOutdoorHoursFrom)
            // children
            if (gate.child) { assertEquals(what, "smartlife_young", p.design!!.id); assertEquals(what, "dv_kids", p.coating!!.id); assertTrue(p.optometristConfirms) }
            else assertFalse(what, "smartlife_young" in p.ids || "dv_kids" in p.ids)
            // every product is named, described and linked where the catalogue has a link
            for (s in p.all) { assertTrue("$what: ${s.id}", s.name.isNotBlank() && s.what.isNotBlank()); assertFalse("$what: ${s.id} why has a hole", s.why.any { "?" in it.en || "{" in it.en || "{" in it.hi }) }
            val bare = p.all.filter { it.why.isEmpty() && it.reasons.isEmpty() }.map { it.id }
            bare.forEach { noWhy[it] = (noWhy[it] ?: 0) + 1 }
            withoutWhy += bare.size
            // a customer who answered the hours questions and gave an age always gets a why line on every product
            if (i.age != null && i.screenHours != null && i.deskHours != null && i.outdoorHours != null) assertEquals("$what: no why on $bare", emptyList<String>(), bare)
        }
        assertTrue("both sides of the gate were exercised ($allowed allowed, $blocked blocked)", allowed > 5000 && blocked > 1000)
        // known gap (listed in TEST_REPORT.md): a product chosen from a symptom alone, when the hours or the age were skipped,
        // has no "why for you" line because its sentence quotes that number. It is never a wrong line.
        println("lens engine stretch: $allowed plans, $blocked blocked by the gate, $withoutWhy suggestions without a why line: $noWhy")
        assertTrue("only the products whose sentence quotes a skipped number: $noWhy", noWhy.keys.all { it in setOf("anti_fatigue", "progressive", "blueguard", "photofusion_x", "office", "single_vision") })
    }

    companion object { const val N = 10_000 }
}
