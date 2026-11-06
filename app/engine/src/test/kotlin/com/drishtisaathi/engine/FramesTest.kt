package com.drishtisaathi.engine

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FramesTest {
    private val rules = TestAssets.rules
    private val catalog = FrameCatalog.parse(TestAssets.text("frames.json"))
    private val ranker = FrameRanker(rules)

    private fun est(v: Double, c: Confidence = Confidence.MEDIUM) = Estimate(v, c, Source.STAFF_ENTRY)
    private fun frame(id: String) = catalog.frames.first { it.id == id }

    @Test fun `placeholder catalogue has 10 valid frames`() {
        assertEquals(10, catalog.frames.size)
        assertTrue(catalog.frames.all { it.lensWidthMm in 40.0..62.0 && it.bridgeMm in 12.0..24.0 && it.priceInr > 0 })
    }

    @Test fun `frame width from printed size`() {
        // 52-18 with 6 mm end pieces each side: 104 + 18 + 12 = 134
        assertEquals(134.0, ranker.frameWidthMm(frame("F01")), 1e-9)
    }

    @Test fun `thickness in ranking matches the brief check`() {
        // −4.00 D, PD 62, 52-18 frame (F01) in CR-39 → ≈ 5.6 mm edge
        val r = ranker.rank(listOf(frame("F01")), est(134.0), est(62.0), listOf(est(-4.0)), "CR39", StylePref.ANY, Budget.HIGH).single()
        assertEquals(5.6, r.thickestMm!!, 0.05)
        assertTrue(r.thickestIsEdge)
        assertEquals(FitVerdict.GOOD, r.fit)
    }

    @Test fun `high minus favours smaller lens width`() {
        val ranked = ranker.rank(catalog.frames, est(134.0), est(62.0), listOf(est(-6.0)), "CR39", StylePref.ANY, Budget.HIGH, topN = 10)
        val f09 = ranked.first { it.frame.id == "F09" } // 58 mm aviator
        val f08 = ranked.first { it.frame.id == "F08" } // 49 mm compact
        assertTrue(f09.thickestMm!! > f08.thickestMm!!)
        assertTrue(f08.thinScore > f09.thinScore)
    }

    @Test fun `wide face prefers wider frames, narrow face narrower`() {
        val wide = ranker.rank(catalog.frames, est(150.0), est(64.0), emptyList(), "CR39", StylePref.ANY, Budget.HIGH, topN = 1).single()
        val narrow = ranker.rank(catalog.frames, est(118.0), est(56.0), emptyList(), "CR39", StylePref.ANY, Budget.HIGH, topN = 1).single()
        assertTrue(wide.frameWidthMm > narrow.frameWidthMm)
    }

    @Test fun `no power known - no thickness shown, thinness neutral`() {
        val r = ranker.rank(catalog.frames, est(134.0), est(62.0), emptyList(), "CR39", null, null)
        assertTrue(r.all { it.thickestMm == null && it.thinScore == 1.0 })
        assertEquals(5, r.size)
    }

    @Test fun `low budget pushes expensive frames down`() {
        val r = ranker.rank(catalog.frames, est(134.0), est(62.0), emptyList(), "CR39", StylePref.ANY, Budget.LOW, topN = 10)
        val rimless = r.first { it.frame.id == "F06" } // ₹5200
        assertTrue(!rimless.withinBudget)
        assertEquals(0.0, rimless.budgetScore, 1e-9)
        assertTrue(r.take(3).all { it.withinBudget })
    }

    @Test fun `style preference lifts matching frames but fit still matters`() {
        val r = ranker.rank(catalog.frames, est(134.0), est(62.0), emptyList(), "CR39", StylePref.BOLD, Budget.HIGH, topN = 10)
        assertTrue(r.first().styleMatch)
        assertTrue(r.filter { it.styleMatch }.all { it.styleScore == 1.0 })
        // Same frame scores higher when the style matches
        val f03Bold = r.first { it.frame.id == "F03" }.score
        val f03Minimal = ranker.rank(listOf(frame("F03")), est(134.0), est(62.0), emptyList(), "CR39", StylePref.MINIMAL, Budget.HIGH).single().score
        assertTrue(f03Bold > f03Minimal)
    }

    @Test fun `thickness confidence is the weakest input`() {
        val r = ranker.rank(listOf(frame("F01")), est(134.0), est(62.0, Confidence.LOW), listOf(est(-2.0, Confidence.MEDIUM)), "CR39", null, null).single()
        assertEquals(Confidence.LOW, r.thicknessConfidence)
    }

    // ---- facade --------------------------------------------------------------------------------

    private val engine = DrishtiEngine(rules, TestAssets.anthro, catalog)

    @Test fun `facade - urgent referral gives no frames`() {
        val res = engine.evaluate(InterviewAnswers(age = 50, redFlagFlashes = true))
        assertNull(res.assessment.advice)
        assertTrue(res.frames.isEmpty())
    }

    @Test fun `facade - high myope gets 1_60 and thickness on every frame`() {
        val a = InterviewAnswers(age = 30, glassesAgeYears = 1, distanceBlur = true,
            knownRx = KnownRx(EyeRx(-5.0), EyeRx(-4.5)), budget = Budget.MID, style = StylePref.ANY)
        val res = engine.evaluate(a, StaffMeasurements(pdMm = 62.0))
        assertEquals("HI_160", res.assessment.advice!!.materialId.value)
        assertEquals(5, res.frames.size)
        assertTrue(res.frames.all { it.thickestMm != null && it.materialId == "HI_160" })
    }

    @Test fun `session result round-trips through JSON (Room storage)`() {
        val res = engine.evaluate(InterviewAnswers(age = 46, nearBlur = true, budget = Budget.LOW))
        val json = Json.encodeToString(SessionResult.serializer(), res)
        assertEquals(res, Json.decodeFromString(SessionResult.serializer(), json))
    }

    // ---- Addition 2: labels and reasons -------------------------------------------------------

    @Test fun `labels follow score cut-offs from rules json`() {
        val all = ranker.rank(catalog.frames, est(134.0), est(62.0), listOf(est(-4.0)), "CR39", StylePref.BOLD, Budget.LOW, topN = 10)
        all.forEach { rf ->
            val expected = when {
                rf.score >= rules.frameScoring.labelHighFrom -> MatchLabel.HIGHLY_RECOMMENDED
                rf.score >= rules.frameScoring.labelModerateFrom -> MatchLabel.MODERATE
                else -> MatchLabel.LOWER
            }
            assertEquals(expected, rf.label)
        }
        // A good-fitting, in-budget, thin frame with no style constraint is highly recommended
        val best = ranker.rank(listOf(frame("F01")), est(134.0), est(62.0), listOf(est(-1.0)), "CR39", StylePref.ANY, Budget.MID).single()
        assertEquals(MatchLabel.HIGHLY_RECOMMENDED, best.label)
    }

    @Test fun `reasons come from components - fit first, numbers attached, 2 to 4 bullets`() {
        val r = ranker.rank(listOf(frame("F01")), est(134.0), est(62.0), listOf(est(-4.0)), "CR39", StylePref.BOLD, Budget.MID).single()
        assertTrue(r.reasons.size in 2..rules.frameScoring.maxReasons)
        val fit = r.reasons.first { it.code == ReasonCode.FITS_FACE }
        assertEquals(134.0, fit.a!!, 1e-9)
        assertEquals(134.0, fit.b!!, 1e-9)
        val thick = r.reasons.first { it.code == ReasonCode.THIN_LENS || it.code == ReasonCode.THICK_LENS }
        assertEquals(5.6, thick.a!!, 0.05)
        assertEquals("CR39", thick.materialId)
        assertTrue(r.reasons.any { it.code == ReasonCode.STYLE_MISMATCH && !it.positive })
        // positives listed before negatives
        assertEquals(r.reasons.sortedByDescending { it.positive }, r.reasons)
    }

    @Test fun `over budget reason carries the amount over`() {
        val r = ranker.rank(listOf(frame("F06")), est(134.0), est(62.0), emptyList(), "CR39", StylePref.ANY, Budget.MID).single()
        val over = r.reasons.first { it.code == ReasonCode.OVER_BUDGET }
        assertEquals(1200.0, over.a!!, 1e-9) // 5200 - 4000
        assertTrue(!over.positive)
    }

    @Test fun `narrow face gets a wide-for-face reason`() {
        val r = ranker.rank(listOf(frame("F09")), est(118.0), est(58.0), emptyList(), "CR39", null, null).single()
        assertEquals(ReasonCode.WIDE_FOR_FACE, r.reasons.first().code)
        assertTrue(r.reasons.none { it.code == ReasonCode.IN_BUDGET || it.code == ReasonCode.STYLE_MATCH }) // no prefs → no such bullets
    }

    // ---- Addition 3: comparison helper ----------------------------------------------------------

    @Test fun `thickness by material for comparison matches the brief check`() {
        val a = InterviewAnswers(age = 30, glassesAgeYears = 1, knownRx = KnownRx(EyeRx(-4.0), EyeRx(-3.0)))
        val res = engine.evaluate(a, StaffMeasurements(pdMm = 62.0))
        val t = engine.thicknessByMaterial(frame("F01"), res.assessment)!!
        assertEquals(5.6, t.getValue("CR39"), 0.05)   // thicker eye (−4.00) drives it
        assertEquals(4.2, t.getValue("HI_167"), 0.05)
        assertTrue(t.getValue("HI_167") < t.getValue("HI_160") && t.getValue("HI_160") < t.getValue("CR39"))
    }

    @Test fun `no power - no thickness table`() {
        val res = engine.evaluate(InterviewAnswers(age = 30))
        assertNull(engine.thicknessByMaterial(frame("F01"), res.assessment))
    }
}
