package com.drishtisaathi.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigAndAnthroTest {

    @Test fun `shipped rules json parses and validates`() {
        val r = TestAssets.rules
        assertEquals(1, r.version)
        assertEquals(0.67, r.optics.lcaBlueOffsetD, 1e-9)
        assertEquals(4, r.addAgeTable.size)
        assertEquals(1.67, r.material("HI_167").index, 1e-9)
    }

    @Test fun `unknown key in rules json is rejected`() {
        val broken = TestAssets.text("rules.json").replace("\"lcaBlueOffsetD\"", "\"lcaBlueOfsetD\"")
        assertThrows(Exception::class.java) { RulesConfig.parse(broken) }
    }

    @Test fun `frame weights must sum to 1`() {
        val broken = TestAssets.text("rules.json").replace("\"weightFit\": 0.35", "\"weightFit\": 0.50")
        assertThrows(IllegalArgumentException::class.java) { RulesConfig.parse(broken) }
    }

    @Test fun `anthro priors parse with sane ANSUR values`() {
        val a = TestAssets.anthro
        assertTrue(a.n > 6000)
        assertTrue(a.pdMm.mean in 58.0..68.0)
        assertTrue(a.faceWidthMm.mean in 125.0..150.0)
    }

    @Test fun `plausibility gate`() {
        val gate = PlausibilityGate(TestAssets.anthro, TestAssets.rules.plausibility)
        val a = TestAssets.anthro
        val fw = a.faceWidthMm.mean
        val typicalPd = a.pdFromFaceWidth.slope * fw + a.pdFromFaceWidth.intercept

        assertEquals(PlausibilityGate.Verdict.OK, gate.check(typicalPd, fw).verdict)
        // PD/face ratio far outside p1–p99 → re-scan
        assertEquals(PlausibilityGate.Verdict.RESCAN, gate.check(40.0, fw).verdict)
        assertEquals(PlausibilityGate.Verdict.RESCAN, gate.check(85.0, fw).verdict)
        // PD only: inside population range is fine, outside is low confidence
        assertEquals(PlausibilityGate.Verdict.OK, gate.check(62.0, null).verdict)
        assertEquals(PlausibilityGate.Verdict.LOW_CONFIDENCE, gate.check(50.0, null).verdict)
    }
}
