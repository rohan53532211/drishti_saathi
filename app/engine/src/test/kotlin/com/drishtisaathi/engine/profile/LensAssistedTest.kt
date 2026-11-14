package com.drishtisaathi.engine.profile

import com.drishtisaathi.engine.TestAssets
import com.drishtisaathi.engine.vision.DeviceFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase B: over-refraction through a known minus lens. */
class LensAssistedTest {
    private val cfg = TestAssets.rules.lensAssisted
    private fun measured(lens: Double, residual: Double?, fit: DeviceFit? = null) =
        (LensAssisted.total(lens, residual, cfg, fit) as LensAssisted.Outcome.Measured).total

    @Test fun `the mode starts from minus 3_75 D - a blur point closer than about 27 cm`() {
        assertEquals(-3.75, cfg.triggerSeD, 0.0)
        assertTrue(LensAssisted.needed(-3.75, cfg))
        assertTrue(LensAssisted.needed(-4.25, cfg))
        assertFalse(LensAssisted.needed(-3.5, cfg))
        assertFalse(LensAssisted.needed(null, cfg))
        assertEquals(26.7, 100.0 / 3.75, 0.05)
    }

    @Test fun `the suggested lens is about 2 D weaker - minus 4_25 gives minus 2_00, minus 6_00 gives minus 4_00`() {
        assertEquals(-2.0, LensAssisted.suggestLens(-4.25, cfg), 1e-9)
        assertEquals(-4.0, LensAssisted.suggestLens(-6.0, cfg), 1e-9)
        assertEquals(-1.5, LensAssisted.suggestLens(-3.75, cfg), 1e-9)
        assertEquals(-6.0, LensAssisted.suggestLens(-8.25, cfg), 1e-9)
        assertEquals("never a plus lens", 0.0, LensAssisted.suggestLens(-1.0, cfg), 0.0)
    }

    @Test fun `the minus 4_25 example - through a minus 2_00 lens the blur point is at 44 cm`() {
        // residual = -1/d = -1/0.444 m = -2.25 D; total = -2.00 + -2.25 = -4.25 D; shown as -4.00 to -4.50
        val t = measured(-2.0, -1.0 / 0.4444)
        assertEquals(-2.0, t.lensD, 0.0)
        assertEquals("no vertex correction below 4 D", -2.0, t.effectiveLensD, 0.0)
        assertEquals(-2.25, t.residualD, 1e-9)
        assertEquals(-4.25, t.totalD, 1e-9)
        assertEquals(-4.0, t.rangeFromD, 1e-9)
        assertEquals(-4.5, t.rangeToD, 1e-9)
        assertFalse(t.highMyopia)
    }

    @Test fun `vertex correction from 4 D for a 12 mm hold - Fe = F over (1 - 0_012 F)`() {
        assertEquals(-4.0 / 1.048, LensAssisted.effectiveLens(-4.0, cfg), 1e-9)
        assertEquals(-6.0 / 1.072, LensAssisted.effectiveLens(-6.0, cfg), 1e-9)
        assertEquals("below 4 D the lens counts as it is", -3.75, LensAssisted.effectiveLens(-3.75, cfg), 0.0)
        // -6.00 lens (effective -5.60) + residual -2.00 = -7.60 -> -7.50
        val t = measured(-6.0, -2.0)
        assertEquals(-5.597, t.effectiveLensD, 0.001)
        assertEquals(-7.5, t.totalD, 1e-9)
        assertEquals(-7.25, t.rangeFromD, 1e-9); assertEquals(-7.75, t.rangeToD, 1e-9)
    }

    @Test fun `letters sharp at every distance - the lens is too strong, never a guess`() {
        assertEquals(LensAssisted.Outcome.LensTooStrong, LensAssisted.total(-5.0, null, cfg))
        assertEquals(0.60, cfg.maxReachM, 0.0)
    }

    @Test fun `stronger than minus 8 D gets the high-myopia note and is still measured through a lens`() {
        assertTrue(LensAssisted.needed(-8.5, cfg))
        assertTrue(LensAssisted.highMyopia(-8.25, cfg))
        assertFalse("exactly -8 is not beyond -8", LensAssisted.highMyopia(-8.0, cfg))
        // -6.50 lens (effective -6.03) + residual -2.50 = -8.53 -> -8.50
        val t = measured(-6.5, -2.5)
        assertEquals(-8.5, t.totalD, 1e-9)
        assertTrue(t.highMyopia)
    }

    @Test fun `totals are rounded to 0_25 D and the range is plus or minus 0_25`() {
        for ((lens, residual, total) in listOf(Triple(-2.0, -2.1, -4.0), Triple(-2.0, -2.13, -4.25), Triple(-3.0, -1.87, -4.75), Triple(-2.5, -2.38, -5.0))) {
            val t = measured(lens, residual)
            assertEquals("$lens $residual", total, t.totalD, 1e-9)
            assertEquals(total + 0.25, t.rangeFromD, 1e-9)
            assertEquals(total - 0.25, t.rangeToD, 1e-9)
        }
    }

    @Test fun `the phone-model correction changes the residual only, never the known lens`() {
        // this phone model reads 0.25 D too strong: final = measured + 0.25
        val fit = DeviceFit(intercept = 0.25, slope = 1.0, sessions = 6, eyes = 12, residualSdD = 0.2, maxCorrectionD = 1.0)
        val plain = measured(-2.0, -2.5)
        val corrected = measured(-2.0, -2.5, fit)
        assertEquals(-4.5, plain.totalD, 1e-9)
        assertEquals(-2.25, corrected.residualD, 1e-9)
        assertEquals(-2.0, corrected.effectiveLensD, 0.0)
        assertEquals(-4.25, corrected.totalD, 1e-9)
    }

    @Test fun `typed lens powers - minus or plano, on the 0_25 grid, within the limit`() {
        assertTrue(LensAssisted.validLens(-2.0, cfg))
        assertTrue(LensAssisted.validLens(-4.75, cfg))
        assertTrue(LensAssisted.validLens(0.0, cfg))
        assertFalse(LensAssisted.validLens(2.0, cfg))
        assertFalse(LensAssisted.validLens(-2.1, cfg))
        assertFalse(LensAssisted.validLens(-25.0, cfg))
        assertFalse(LensAssisted.validLens(null, cfg))
    }
}
