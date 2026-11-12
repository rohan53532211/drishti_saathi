package com.drishtisaathi.engine.vision

import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.TestAssets
import com.drishtisaathi.engine.face.SyntheticFace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.math.tan

class DistanceEstimatorTest {
    private val rules = TestAssets.rules
    private val est = DistanceEstimator(rules)

    /** A face whose eyes are [distanceMm] from a camera with focal length [f] px (scale = d / f). */
    private fun face(distanceMm: Double, f: Double? = 600.0, iris: Double = 11.7, leftRing: Double = 1.0, eyeAtCentre: Boolean = false) =
        SyntheticFace.frame(
            SyntheticFace.Spec(
                mmPerPx = distanceMm / (f ?: fallbackF()), focalLengthPx = f, irisDiameterMm = iris,
                leftIrisRingScale = leftRing, originYPx = if (eyeAtCentre) 480.0 else 400.0,
            ),
        )

    private fun fallbackF() = 480.0 / tan(Math.toRadians(rules.distance.fallbackLongSideFovDeg / 2))

    @Test fun `distance from iris size with camera intrinsics`() {
        listOf(250.0, 400.0, 650.0).forEach { d ->
            val e = est.estimate(face(d))!!
            assertEquals(d, e.distanceMm, d * 0.001)
            assertEquals(d, e.depthMm, d * 0.001)
            assertEquals(DistanceEstimator.IrisSource.POPULATION_PRIOR, e.irisSource)
            assertEquals(DistanceEstimator.FocalSource.CAMERA_INTRINSICS, e.focalSource)
            assertEquals(2, e.irisesUsed)
        }
    }

    @Test fun `iris prior alone is medium confidence, about 4 to 5 percent`() {
        val e = est.estimate(face(400.0))!!
        assertEquals(Confidence.MEDIUM, e.confidence)
        assertTrue(e.relativeSd in 0.042..0.05)
        assertEquals(400.0, e.asEstimate().value, 0.5)
    }

    @Test fun `personal iris from the card removes the prior's bias and raises confidence`() {
        val f = face(400.0, iris = 12.5)
        val prior = est.estimate(f)!!
        assertEquals(400.0 * 11.7 / 12.5, prior.distanceMm, 0.5) // biased by the 11.7 mm assumption
        val personal = est.estimate(f, personalIrisMm = 12.5)!!
        assertEquals(400.0, personal.distanceMm, 0.5)
        assertEquals(DistanceEstimator.IrisSource.PERSONAL_CARD, personal.irisSource)
        assertEquals(Confidence.HIGH, personal.confidence)
    }

    @Test fun `implausible personal iris is ignored`() {
        val e = est.estimate(face(400.0), personalIrisMm = 16.0)!!
        assertEquals(DistanceEstimator.IrisSource.POPULATION_PRIOR, e.irisSource)
    }

    @Test fun `personal iris diameter from the calibration photo`() {
        val cal = SyntheticFace.frame(SyntheticFace.Spec(irisDiameterMm = 12.2, mmPerPx = 0.3))
        val p = est.personalIrisDiameterMm(cal, camMmPerPx = 0.3)!!
        assertEquals(12.2, p.value, 0.01)
        assertEquals(Confidence.HIGH, p.confidence)
        // A card scale that is twice too large gives a 24 mm iris: rejected
        assertNull(est.personalIrisDiameterMm(cal, camMmPerPx = 0.6))
    }

    @Test fun `no focal length - FOV fallback, low confidence`() {
        val e = est.estimate(face(400.0, f = null))!!
        assertEquals(DistanceEstimator.FocalSource.FOV_FALLBACK, e.focalSource)
        assertEquals(fallbackF(), e.focalPx, 1e-6)
        assertEquals(400.0, e.distanceMm, 0.5)
        assertEquals(Confidence.LOW, e.confidence)
    }

    @Test fun `one iris occluded - dropped, distance still right`() {
        val e = est.estimate(face(400.0, leftRing = 0.6))!!
        assertEquals(1, e.irisesUsed)
        assertEquals(400.0, e.distanceMm, 0.5)
        assertTrue(e.confidence != Confidence.HIGH)
    }

    @Test fun `eyelid over one iris - vertical shrink does not matter`() {
        // Ring scale applies to both axes; with a small difference (< 15 %) both are averaged.
        val e = est.estimate(face(400.0, leftRing = 0.95))!!
        assertEquals(2, e.irisesUsed)
        assertEquals(400.0 * 2 / 1.95, e.distanceMm, 0.5)
    }

    @Test fun `iris below the pixel minimum is unusable`() {
        // At 4 m with f = 600 the iris is 1.75 px
        assertNull(est.estimate(face(4000.0)))
    }

    @Test fun `no landmarks - null`() {
        assertNull(est.estimate(SyntheticFace.frame(SyntheticFace.Spec(landmarkCount = 468))))
    }

    @Test fun `screen centre offset correction`() {
        val cfg = rules.distance.copy(applyScreenCentreOffset = true, screenCentreOffsetXMm = 0.0, screenCentreOffsetYMm = 70.0)
        val e = DistanceEstimator(cfg, rules.faceGeometry.landmarks).estimate(face(400.0, eyeAtCentre = true))!!
        assertEquals(400.0, e.depthMm, 0.5)
        assertEquals(sqrt(400.0 * 400.0 + 70.0 * 70.0), e.distanceMm, 0.5)
        // Off by default: distance is the depth
        assertFalse(rules.distance.applyScreenCentreOffset)
        assertEquals(e.eyeToScreenCentreMm, est.estimate(face(400.0, eyeAtCentre = true))!!.eyeToScreenCentreMm, 1e-6)
    }

    @Test fun `face plane scale from the estimate`() {
        val e = est.estimate(face(400.0))!!
        assertEquals(400.0 / 600.0, e.faceMmPerPx, 1e-3)
    }

    // ---- smoother ---------------------------------------------------------------------------------

    @Test fun `smoother rejects a single spike and converges`() {
        val s = DistanceSmoother(rules.distance)
        var t = 0L
        repeat(10) { s.add(t, 400.0); t += 50 }
        val afterSpike = s.add(t, 900.0); t += 50
        assertEquals(400.0, afterSpike, 1e-9) // median of the window ignores one spike
        var v = 0.0
        repeat(30) { v = s.add(t, 450.0); t += 50 }
        assertEquals(450.0, v, 0.5)
    }

    @Test fun `smoother drops stale samples and resets when time goes backwards`() {
        val s = DistanceSmoother(rules.distance.copy(smootherEmaAlpha = 1.0))
        s.add(0, 300.0); s.add(10, 300.0); s.add(20, 300.0)
        // 5 s later the old samples are out of the window
        assertEquals(500.0, s.add(5000, 500.0), 1e-9)
        assertEquals(350.0, s.add(100, 350.0), 1e-9)
    }

    @Test fun `smoothing an estimate keeps its metadata`() {
        val s = DistanceSmoother(rules.distance)
        val e = est.estimate(face(400.0))!!
        val out = s.add(0, e)
        assertEquals(e.distanceMm, out.distanceMm, 1e-9)
        assertEquals(e.confidence, out.confidence)
    }
}
