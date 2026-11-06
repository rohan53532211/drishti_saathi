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
}
