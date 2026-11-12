package com.drishtisaathi.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpticsTest {
    private val optics = Optics(TestAssets.rules)

    // ---- letter size -------------------------------------------------------------------------

    @Test fun `20-20 letter height is about 0_001454 x distance`() {
        assertEquals(0.001454 * 400.0, optics.letterHeightMm(400.0), 0.001)
        assertEquals(0.001454 * 1000.0, optics.letterHeightMm(1000.0), 0.002)
    }

    // ---- far point ---------------------------------------------------------------------------

    @Test fun `blue and white agree - mean SE, reliable`() {
        // d_w = 0.50 m → −2.00; d_b = 0.375 m → −2.667 + 0.67 = −1.997
        val r = optics.farPoint(dBlueM = 0.375, dWhiteM = 0.50)
        assertEquals(-1.997, r.seBlueD, 0.001)
        assertEquals(-2.000, r.seWhiteD!!, 1e-9)
        assertTrue(r.reliable)
        assertEquals(-1.998, r.seD, 0.001)
        assertFalse(r.noMyopiaAtArmsLength)
    }

    @Test fun `blue and white disagree by more than 0_50 D - unreliable`() {
        // d_b = 0.55 → −1.818 + 0.67 = −1.148; d_w = 0.50 → −2.00; diff ≈ 0.85 D (reach is 0.60 m)
        val r = optics.farPoint(dBlueM = 0.55, dWhiteM = 0.50)
        assertFalse(r.reliable)
        assertEquals(0.852, r.disagreementD!!, 0.001)
    }

    @Test fun `exactly 0_50 D disagreement is still reliable`() {
        // d_w = 0.5 → −2.0 ; need SE_blue = −1.5 → −1/d_b = −2.17 → d_b = 1/2.17
        val r = optics.farPoint(dBlueM = 1.0 / 2.17, dWhiteM = 0.5)
        assertEquals(0.50, r.disagreementD!!, 1e-9)
        assertTrue(r.reliable)
    }

    @Test fun `white beyond 0_8 m is ignored - blue only`() {
        val r = optics.farPoint(dBlueM = 0.55, dWhiteM = 0.85)
        assertNull(r.seWhiteD)
        assertEquals(-1.0 / 0.55 + 0.67, r.seD, 1e-9)
    }

    @Test fun `no blur at arms length - no significant myopia`() {
        assertTrue(optics.farPoint(dBlueM = null, dWhiteM = null).noMyopiaAtArmsLength)
        assertTrue(optics.farPoint(dBlueM = 0.75, dWhiteM = null).noMyopiaAtArmsLength)
        // One reach setting: farPoint.selfHeldReachM (0.60 m)
        assertTrue(optics.farPoint(dBlueM = 0.60, dWhiteM = null).noMyopiaAtArmsLength)
        assertFalse(optics.farPoint(dBlueM = 0.59, dWhiteM = null).noMyopiaAtArmsLength)
    }

    // ---- near point / add ------------------------------------------------------------------

    @Test fun `near point add - emmetrope at 50 cm, WD 40 cm`() {
        // A = 1/0.5 − 0 = 2.00; Add = 2.50 − 1.00 = +1.50
        val r = optics.nearPointAdd(dNearPointM = 0.50, seD = 0.0, workingDistanceM = 0.40)
        assertEquals(2.0, r.amplitudeD, 1e-9)
        assertEquals(1.50, r.addD, 1e-9)
    }

    @Test fun `near point add uses abs SE and rounds to 0_25`() {
        // A = 1/0.30 − 1.0 = 2.333; raw = 2.5 − 1.1667 = 1.3333 → 1.25
        val r = optics.nearPointAdd(dNearPointM = 0.30, seD = -1.0, workingDistanceM = 0.40)
        assertEquals(1.3333, r.rawAddD, 1e-3)
        assertEquals(1.25, r.addD, 1e-9)
    }

    @Test fun `add is clamped to 0_75 - 3_00`() {
        assertEquals(0.75, optics.nearPointAdd(0.20, 0.0, 0.40).addD, 1e-9)  // A=5 → raw 0
        assertEquals(3.00, optics.nearPointAdd(5.0, 0.0, 0.33).addD, 1e-9)   // A=0.2 → raw 2.93 → 3.00
        assertEquals(3.00, optics.nearPointAdd(10.0, 0.0, 0.25).addD, 1e-9)  // raw 3.95 → clamp
    }

    @Test fun `fine handwork uses 33 cm working distance`() {
        assertEquals(0.33, optics.workingDistanceM(Occupation.FINE_HANDWORK), 1e-9)
        assertEquals(0.40, optics.workingDistanceM(Occupation.OFFICE_COMPUTER), 1e-9)
        assertEquals(0.40, optics.workingDistanceM(null), 1e-9)
    }

    @Test fun `hofstetter minimum amplitude`() {
        assertEquals(5.0, optics.hofstetterMinAmplitude(40), 1e-9)
        assertEquals(2.5, optics.hofstetterMinAmplitude(50), 1e-9)
        assertEquals(0.0, optics.hofstetterMinAmplitude(70), 1e-9) // never negative
        // age 50, WD 0.40: 2.50 − 1.25 = +1.25
        assertEquals(1.25, optics.addFromAgeExpectation(50, 0.40), 1e-9)
    }

    // ---- repeats -----------------------------------------------------------------------------

    @Test fun `repeat spread sets confidence`() {
        assertEquals(Confidence.HIGH, optics.combineRepeats(listOf(-2.0, -2.25)).confidence)
        assertEquals(Confidence.MEDIUM, optics.combineRepeats(listOf(-2.0, -2.5)).confidence)
        assertEquals(Confidence.LOW, optics.combineRepeats(listOf(-2.0, -2.75)).confidence)
        assertEquals(Confidence.LOW, optics.combineRepeats(listOf(-2.0)).confidence)
        assertEquals(-2.125, optics.combineRepeats(listOf(-2.0, -2.25)).value, 1e-9)
        assertEquals(-2.0, Optics.median(listOf(-1.0, -2.0, -3.0)), 1e-9)
    }

    @Test fun `round to 0_25 step`() {
        assertEquals(-1.50, optics.roundToStep(-1.4), 1e-9)
        assertEquals(-1.25, optics.roundToStep(-1.3), 1e-9)
        assertEquals(2.00, optics.roundToStep(1.95), 1e-9)
    }
}
