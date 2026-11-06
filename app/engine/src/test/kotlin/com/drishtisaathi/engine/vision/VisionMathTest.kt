package com.drishtisaathi.engine.vision

import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.TestAssets
import com.drishtisaathi.engine.config.DistanceConfig
import com.drishtisaathi.engine.config.FaceGeometryConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionMathTest {
    private val vm = VisionMath(TestAssets.rules)

    @Test fun `20-20 optotype is 0_001454 x d, and in screen pixels`() {
        assertEquals(0.5816, vm.optotypeHeightMm(400.0), 1e-3)
        // 0.07 mm per screen px → 8.31 px at 40 cm; 20/40 doubles it
        assertEquals(0.5816 / 0.07, vm.optotypeHeightPx(400.0, 0.07), 0.02)
        assertEquals(2 * 0.5816 / 0.07, vm.optotypeHeightPx(400.0, 0.07, sizeMultiple = 2.0), 0.04)
    }

    @Test fun `far point SE for blue and white`() {
        assertEquals(-2.0 + 0.67, vm.seFromBlueFarPoint(0.5), 1e-9)
        assertEquals(-2.0, vm.seFromWhiteFarPoint(0.5), 1e-9)
        assertTrue(vm.whiteUsable(0.8))
        assertFalse(vm.whiteUsable(0.81))
        assertFalse(vm.whiteUsable(null))
    }

    @Test fun `blue-white consistency at 0_50 D`() {
        assertTrue(vm.blueWhiteConsistent(-1.5, -2.0))
        assertFalse(vm.blueWhiteConsistent(-1.4, -2.0))
        val r = vm.farPoint(0.375, 0.5)
        assertTrue(r.reliable)
    }

    @Test fun `accommodation, add and Hofstetter`() {
        assertEquals(2.0, vm.accommodationAmplitude(0.4, -0.5), 1e-9)
        assertEquals(1.50, vm.nearPointAdd(0.5, 0.0, 0.4).addD, 1e-9)
        assertEquals(2.5, vm.hofstetterMin(50), 1e-9)
        assertEquals(3.5, vm.hofstetterMean(50), 1e-9)
        assertEquals(5.0, vm.hofstetterMax(50), 1e-9)
        assertEquals(0.0, vm.hofstetterMax(70), 1e-9)
        assertEquals(1.25, vm.roundToStep(1.3), 1e-9)
        assertEquals(3.0, vm.clampAdd(3.6), 1e-9)
    }

    // ---- innovation 5 ----------------------------------------------------------------------------

    @Test fun `plausible amplitude - measured add dominates the fusion`() {
        // Age 50, emmetrope, near point 33 cm: A = 3.0 (between 2.5 and 5.0). Measured 1.00, age 0.75.
        val f = vm.fuseAdd(1.0 / 3.0, 0.0, 0.4, age = 50, measurementConfidence = Confidence.HIGH)
        assertEquals(VisionMath.AmplitudeCheck.WITHIN_EXPECTED, f.check)
        assertEquals(1.0, f.measuredAddD, 1e-9)
        assertEquals(0.75, f.ageExpectedAddD!!, 1e-9)
        assertTrue(f.measuredWeight > 0.6)
        assertEquals(1.0, f.add.value, 1e-9)
        assertEquals(Confidence.HIGH, f.add.confidence)
        assertTrue(vm.fusedAddSigma(f) < TestAssets.rules.bayes.addFusion.measuredAddSigmaD)
    }

    @Test fun `amplitude above what the age allows - pulled to the age expectation, low confidence`() {
        // Age 60: max amplitude 1.0 D; near point 25 cm gives A = 4.0 → implausible.
        val f = vm.fuseAdd(0.25, 0.0, 0.4, age = 60)
        assertEquals(VisionMath.AmplitudeCheck.ABOVE_EXPECTED, f.check)
        assertEquals(0.5, f.measuredAddD, 1e-9)
        assertEquals(2.25, f.ageExpectedAddD!!, 1e-9)
        assertTrue(f.measuredWeight < 0.3)
        assertEquals(2.0, f.add.value, 1e-9)
        assertEquals(Confidence.LOW, f.add.confidence)
    }

    @Test fun `amplitude below the age minimum is flagged`() {
        // Age 40: min amplitude 5.0; near point 1 m → A = 1.0
        val f = vm.fuseAdd(1.0, 0.0, 0.4, age = 40)
        assertEquals(VisionMath.AmplitudeCheck.BELOW_EXPECTED, f.check)
        assertEquals(Confidence.LOW, f.add.confidence)
        assertTrue(f.add.value in 0.75..3.0)
    }

    @Test fun `without age the measured add is used alone`() {
        val f = vm.fuseAdd(0.5, 0.0, 0.4, age = null)
        assertEquals(VisionMath.AmplitudeCheck.AGE_UNKNOWN, f.check)
        assertEquals(1.5, f.add.value, 1e-9)
        assertNull(f.ageExpectedAddD)
        assertEquals(1.0, f.measuredWeight, 1e-9)
    }

    // ---- config sections ----------------------------------------------------------------------------

    @Test fun `track A sections parse from the shipped rules json`() {
        val r = TestAssets.rules
        assertEquals(11.7, r.distance.irisDiameterPriorMm, 1e-9)
        assertEquals(468, r.faceGeometry.landmarks.irisRightCentre)
        assertEquals(listOf(474, 475, 476, 477), r.faceGeometry.landmarks.irisLeftRing)
        assertEquals(0.277, r.bayes.agePriors.last().myopiaPrevalence, 1e-9)
        assertEquals(30, r.faceGeometry.aggregate.targetFrames)
    }

    @Test fun `missing sections and unknown keys fail loudly`() {
        assertThrows(IllegalArgumentException::class.java) { DistanceConfig() }
        assertThrows(IllegalArgumentException::class.java) { FaceGeometryConfig() }
        val unknown = TestAssets.text("rules.json").replace("\"minIrisPx\"", "\"minIrisPxx\"")
        assertThrows(Exception::class.java) { RulesConfig.parse(unknown) }
        val badShape = TestAssets.text("rules.json").replace("\"HEART\": [\"AVIATOR\", \"OVAL\", \"ROUND\"]", "\"HEART\": []")
        assertThrows(Exception::class.java) { RulesConfig.parse(badShape) }
    }
}
