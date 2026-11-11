package com.drishtisaathi.engine.vision

import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.DrishtiEngine
import com.drishtisaathi.engine.FrameCatalog
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.MeasuredEye
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.Source
import com.drishtisaathi.engine.TestAssets
import com.drishtisaathi.engine.VisionTests
import com.drishtisaathi.engine.config.DeviceCorrectionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCorrectionTest {
    private val cfg = TestAssets.rules.deviceCorrection

    /** Two eyes per session, final = a + b × measured exactly. */
    private fun exact(sessions: Int, a: Double, b: Double, start: Double = -4.0, step: Double = 0.5) =
        (0 until sessions).flatMap { i ->
            val m1 = start + i * step
            val m2 = m1 + 0.25
            listOf(PairedEye(i.toLong(), m1, a + b * m1), PairedEye(i.toLong(), m2, a + b * m2))
        }

    @Test fun `shipped config - 5 sessions, enabled`() {
        assertTrue(cfg.enabled)
        assertEquals(5, cfg.minVerifiedSessions)
    }

    @Test fun `recovers an exact line`() {
        val fit = DeviceCorrection.fit(exact(6, a = -0.25, b = 1.1), cfg)!!
        assertEquals(-0.25, fit.intercept, 1e-9)
        assertEquals(1.1, fit.slope, 1e-9)
        assertEquals(6, fit.sessions)
        assertEquals(12, fit.eyes)
        assertEquals(0.0, fit.residualSdD, 1e-9)
    }

    @Test fun `fewer than 5 sessions - no fit, and it counts sessions not eyes`() {
        assertNull(DeviceCorrection.fit(exact(4, -0.5, 1.0), cfg)) // 8 eyes, 4 sessions
        assertNotNull(DeviceCorrection.fit(exact(5, -0.5, 1.0), cfg))
        assertNull(DeviceCorrection.fit(emptyList(), cfg))
    }

    @Test fun `phone reads 0_5 D too weak - offset found and applied`() {
        // final is 0.5 D more minus than measured, every time
        val fit = DeviceCorrection.fit(exact(5, -0.5, 1.0), cfg)!!
        assertEquals(-2.5, fit.apply(-2.0), 1e-9)
    }

    @Test fun `little spread in measured values - offset only, slope 1`() {
        val pairs = (0 until 5).map { PairedEye(it.toLong(), -2.0 + 0.05 * it, -2.6 + 0.4 * it) }
        val fit = DeviceCorrection.fit(pairs, cfg)!!
        assertEquals(1.0, fit.slope, 0.0)
        assertEquals(pairs.map { it.finalSeD - it.measuredSeD }.average(), fit.intercept, 1e-9)
    }

    @Test fun `steep slope is clamped and the intercept refitted for it`() {
        // measured -2.0 .. +0.75 D, so no pair is beyond the 3 D entry-error cut-off
        val p = exact(6, 0.0, 2.0, start = -2.0)
        val fit = DeviceCorrection.fit(p, cfg)!!
        assertEquals(12, fit.eyes)
        assertEquals(cfg.slopeMax, fit.slope, 1e-9)
        assertEquals(p.map { it.finalSeD }.average() - cfg.slopeMax * p.map { it.measuredSeD }.average(), fit.intercept, 1e-9)
    }

    @Test fun `entry errors over 3 D are left out, and can drop the count below 5`() {
        val good = exact(5, -0.5, 1.0)
        val typo = PairedEye(99, -1.0, -7.0)
        assertEquals(10, DeviceCorrection.fit(good + typo, cfg)!!.eyes)
        val fourGoodOneTypo = exact(4, -0.5, 1.0) + typo
        assertNull(DeviceCorrection.fit(fourGoodOneTypo, cfg))
    }

    @Test fun `applied change is capped at 1 D`() {
        val fit = DeviceFit(intercept = -3.0, slope = 1.0, sessions = 5, eyes = 10, residualSdD = 0.0, maxCorrectionD = 1.0)
        assertEquals(-3.0, fit.apply(-2.0), 1e-9)
        assertEquals(1.0, DeviceFit(3.0, 1.0, 5, 10, 0.0, 1.0).apply(0.0), 1e-9)
    }

    @Test fun `disabled - no fit`() {
        assertNull(DeviceCorrection.fit(exact(10, -0.5, 1.0), cfg.copy(enabled = false)))
    }

    @Test fun `bad config is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { cfg.copy(slopeMin = 1.1) }
        assertThrows(IllegalArgumentException::class.java) { cfg.copy(minVerifiedSessions = 1) }
    }

    @Test fun `rules json without the section fails to parse`() {
        val json = TestAssets.text("rules.json")
        val stripped = json.replace(Regex(""",\s*"deviceCorrection"\s*:\s*\{[^}]*\}"""), "")
        assertTrue(stripped.length < json.length)
        assertThrows(IllegalArgumentException::class.java) { RulesConfig.parse(stripped) }
    }

    // ---- through the engine ------------------------------------------------------------------

    private val engine = DrishtiEngine(TestAssets.rules, TestAssets.anthro, FrameCatalog.parse(TestAssets.text("frames.json")))
    private val answers = InterviewAnswers(age = 25, distanceBlur = true, nearBlur = false)
    private val tests = VisionTests(
        right = MeasuredEye(seD = -2.0, confidence = Confidence.HIGH),
        left = MeasuredEye(seD = -2.0, confidence = Confidence.HIGH),
    )

    @Test fun `engine applies the fit and labels it with N shop records`() {
        val plain = engine.evaluate(answers, tests = tests).assessment.advice!!.eyes
        val fit = DeviceCorrection.fit(exact(7, -1.0, 1.0), cfg)!!
        val corr = engine.evaluate(answers, tests = tests, correction = fit).assessment.advice!!.eyes
        plain.forEach { assertNull(it.correctedWithShopRecords) }
        corr.forEach {
            assertEquals(7, it.correctedWithShopRecords)
            assertEquals(Source.MEASURED, it.startingTrialLens.source)
        }
        // 1 D more minus: the same as measuring -3.0 D without a correction
        val ref = engine.evaluate(answers, tests = tests.copy(
            right = tests.right!!.copy(seD = -3.0), left = tests.left!!.copy(seD = -3.0),
        )).assessment.advice!!.eyes
        assertEquals(ref.map { it.startingTrialLens.value }, corr.map { it.startingTrialLens.value })
        assertEquals(ref.map { it.band.value }, corr.map { it.band.value })
    }

    @Test fun `old-glasses numbers are never corrected`() {
        val oldOnly = InterviewAnswers(age = 40, wearsGlasses = true, glassesAgeYears = 1,
            knownRx = com.drishtisaathi.engine.KnownRx(com.drishtisaathi.engine.EyeRx(-2.0), com.drishtisaathi.engine.EyeRx(-2.0)))
        val fit = DeviceCorrection.fit(exact(7, -1.0, 1.0), cfg)!!
        engine.evaluate(oldOnly, correction = fit).assessment.advice!!.eyes.forEach {
            assertEquals(Source.OLD_GLASSES_NUMBERS, it.band.source)
            assertNull(it.correctedWithShopRecords)
        }
    }

    @Test fun `disabled in rules - engine ignores a fit`() {
        val off = TestAssets.rules.copy(deviceCorrection = cfg.copy(enabled = false))
        val e = DrishtiEngine(off, TestAssets.anthro, FrameCatalog.parse(TestAssets.text("frames.json")))
        val fit = DeviceFit(-1.0, 1.0, 7, 14, 0.0, 1.0)
        e.evaluate(answers, tests = tests, correction = fit).assessment.advice!!.eyes.forEach { assertNull(it.correctedWithShopRecords) }
    }
}

class TrialLensTextTest {
    private fun se(t: String?) = TrialLensText.sphericalEquivalent(t)

    @Test fun `common staff formats`() {
        assertEquals(-2.0, se("-1.75 / -0.50 x 180")!!, 1e-9)
        assertEquals(-2.0, se("-1.75/-0.5x90")!!, 1e-9)
        assertEquals(-2.0, se("-1.75 -0.50 × 180")!!, 1e-9)
        assertEquals(1.0, se("+1.00 DS")!!, 1e-9)
        assertEquals(-2.25, se("−2.25")!!, 1e-9)
        assertEquals(0.0, se("plano")!!, 1e-9)
        assertEquals(-0.375, se("pl / -0.75 x 10")!!, 1e-9)
        assertEquals(-1.5, se(" -1.75 / +0.50 x 90 ")!!, 1e-9) // plus-cyl notation
    }

    @Test fun `unreadable entries give null`() {
        listOf(null, "", "  ", "abc", "-1.75 x 180", "-1 -2 -3", "-25.00", "-2.00 / -12.00 x 90").forEach {
            assertNull("'$it'", se(it))
        }
    }
}
