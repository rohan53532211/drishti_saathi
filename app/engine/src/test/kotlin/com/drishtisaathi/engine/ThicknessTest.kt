package com.drishtisaathi.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThicknessTest {
    private val rules = TestAssets.rules
    private val cr39 = rules.material("CR39")
    private val hi167 = rules.material("HI_167")

    /** The worked check from the brief (Section 5, M5): −4.00 D, PD 62, frame 52-18. */
    @Test fun `brief check - minus 4, PD 62, frame 52-18`() {
        assertEquals(4.0, Thickness.decentrationMm(52.0, 18.0, 62.0), 1e-9)
        assertEquals(30.0, Thickness.maxRadiusMm(52.0, 18.0, 62.0), 1e-9)

        val cr = Thickness.estimate(-4.00, 52.0, 18.0, 62.0, cr39)
        assertEquals(5.6, cr.edgeMm, 0.05)
        assertEquals(2.0, cr.centreMm, 1e-9)

        val hi = Thickness.estimate(-4.00, 52.0, 18.0, 62.0, hi167)
        assertEquals(4.2, hi.edgeMm, 0.05)
        assertTrue(hi.edgeMm < cr.edgeMm)
    }

    @Test fun `plus lens - centre is min edge plus sag`() {
        val r = Thickness.estimate(+3.00, 50.0, 18.0, 64.0, cr39)
        // y = 25 + (50+18-64)/2 = 27; sag = 27² · 3 / (2000 · 0.5) = 2.187
        assertEquals(27.0, r.yMm, 1e-9)
        assertEquals(1.0, r.edgeMm, 1e-9)
        assertEquals(1.0 + 2.187, r.centreMm, 1e-3)
        assertTrue(r.isPlus)
        assertEquals(r.centreMm, r.maxMm, 1e-9)
    }

    @Test fun `plano lens is centre thickness everywhere`() {
        val r = Thickness.estimate(0.0, 52.0, 18.0, 62.0, cr39)
        assertEquals(cr39.centreThicknessMinusMm, r.edgeMm, 1e-9)
    }

    @Test fun `wide PD gives negative decentration but same radius rule`() {
        // A + DBL < PD: optical centres sit outward; furthest edge is still A/2 + |dec|.
        assertEquals(-2.0, Thickness.decentrationMm(48.0, 16.0, 68.0), 1e-9)
        assertEquals(26.0, Thickness.maxRadiusMm(48.0, 16.0, 68.0), 1e-9)
    }

    @Test fun `strongest meridian drives thickness`() {
        assertEquals(-3.0, EyeRx(sph = -2.0, cyl = -1.0).strongestMeridian, 1e-9)
        assertEquals(2.5, EyeRx(sph = 2.5, cyl = -1.0).strongestMeridian, 1e-9)
        assertEquals(-2.5, EyeRx(sph = -2.0, cyl = -1.0).sphericalEquivalent, 1e-9)
    }
}
