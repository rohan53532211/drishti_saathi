package com.drishtisaathi.engine.face

import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.FrameShape
import com.drishtisaathi.engine.PlausibilityGate
import com.drishtisaathi.engine.TestAssets
import com.drishtisaathi.engine.vision.DistanceEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class FaceGeometryTest {
    private val rules = TestAssets.rules
    private val geo = FaceGeometry(rules)
    private val gate = PlausibilityGate(TestAssets.anthro, rules.plausibility)
    private val card = FaceScale.fromCard(0.25)

    // ---- measures -------------------------------------------------------------------------------

    @Test fun `recovers every measure at a known card scale`() {
        val m = geo.measure(SyntheticFace.frame(), card)!!
        assertEquals(63.0, m.pdMm, 0.05)
        assertEquals(140.0, m.faceWidthMm, 0.05)
        assertEquals(18.0, m.bridgeWidthMm, 0.05)
        assertEquals(171.0, m.faceLengthMm, 0.05)
        assertEquals(112.0, m.jawWidthMm, 0.05)
        assertEquals(115.0, m.foreheadWidthMm, 0.05)
        assertEquals(ScaleSource.CARD, m.scaleSource)
    }

    @Test fun `PD is unaffected by head roll and by a different scale`() {
        val f = SyntheticFace.frame(SyntheticFace.Spec(pdMm = 58.0, rollDeg = 12.0, mmPerPx = 0.3))
        val m = geo.measure(f, FaceScale.fromCard(0.3))!!
        assertEquals(58.0, m.pdMm, 0.05)
    }

    @Test fun `scale from the iris distance gives the same PD`() {
        val f = SyntheticFace.frame(SyntheticFace.Spec(mmPerPx = 400.0 / 600.0, focalLengthPx = 600.0, originXPx = 360.0))
        val d = DistanceEstimator(rules).estimate(f)!!
        val m = geo.measure(f, FaceScale.from(d))!!
        assertEquals(63.0, m.pdMm, 0.1)
        assertEquals(ScaleSource.IRIS_PRIOR, m.scaleSource)
    }

    @Test fun `missing landmarks give null`() {
        val f = SyntheticFace.frame(SyntheticFace.Spec(landmarkCount = 468))
        assertNull(geo.measure(f, card))
        assertNull(geo.headPose(f))
    }

    // ---- head pose ------------------------------------------------------------------------------

    @Test fun `frontal face has zero pose from landmarks`() {
        val p = geo.headPose(SyntheticFace.frame())!!
        assertEquals(PoseSource.LANDMARKS, p.source)
        assertEquals(0.0, p.yawDeg, 0.05)
        assertEquals(0.0, p.pitchDeg, 0.05)
        assertEquals(0.0, p.rollDeg, 0.05)
        assertTrue(p.within(5.0, 5.0))
    }

    @Test fun `roll from the eye-corner line`() {
        assertEquals(7.0, geo.headPose(SyntheticFace.frame(SyntheticFace.Spec(rollDeg = 7.0)))!!.rollDeg, 0.1)
        assertEquals(-3.0, geo.headPose(SyntheticFace.frame(SyntheticFace.Spec(rollDeg = -3.0)))!!.rollDeg, 0.1)
    }

    @Test fun `yaw from nose tip against the cheeks`() {
        listOf(-12.0, -4.0, 3.0, 8.0).forEach { yaw ->
            val p = geo.headPose(SyntheticFace.frame(SyntheticFace.Spec(yawDeg = yaw)))!!
            assertEquals(yaw, p.yawDeg, 0.1)
            assertEquals(0.0, p.rollDeg, 0.1)
        }
    }

    @Test fun `yaw still found when the head is also rolled`() {
        val p = geo.headPose(SyntheticFace.frame(SyntheticFace.Spec(yawDeg = 6.0, rollDeg = 10.0)))!!
        assertEquals(10.0, p.rollDeg, 0.3)
        assertEquals(6.0, p.yawDeg, 0.5)
        assertFalse(p.within(5.0, 5.0))
    }

    @Test fun `pitch from landmarks - nose up gives negative pitch`() {
        val p = geo.headPose(SyntheticFace.frame(SyntheticFace.Spec(pitchDeg = 8.0)))!!
        assertEquals(-8.0, p.pitchDeg, 0.1)
    }

    @Test fun `pose from the transformation matrix wins and removes scale`() {
        val m = SyntheticFace.transformMatrix(yawDeg = 4.0, pitchDeg = -6.0, rollDeg = 2.5, scale = 1.3)
        val p = geo.headPose(SyntheticFace.frame(SyntheticFace.Spec(yawDeg = 20.0, transformMatrix = m)))!!
        assertEquals(PoseSource.TRANSFORM_MATRIX, p.source)
        assertEquals(Confidence.HIGH, p.confidence)
        assertEquals(4.0, p.yawDeg, 0.01)
        assertEquals(-6.0, p.pitchDeg, 0.01)
        assertEquals(2.5, p.rollDeg, 0.01)
    }

    // ---- face shape -----------------------------------------------------------------------------

    @Test fun `face shape rules`() {
        assertEquals(FaceShape.OVAL, geo.classifyShape(0.82, 0.80, 0.82).value)
        assertEquals(FaceShape.LONG, geo.classifyShape(0.70, 0.80, 0.82).value)
        assertEquals(FaceShape.SQUARE, geo.classifyShape(0.85, 0.90, 0.85).value)
        assertEquals(FaceShape.ROUND, geo.classifyShape(0.95, 0.78, 0.82).value)
        assertEquals(FaceShape.HEART, geo.classifyShape(0.82, 0.68, 0.86).value)
        // Narrow jaw but narrow forehead too: not a heart
        assertEquals(FaceShape.OVAL, geo.classifyShape(0.82, 0.68, 0.74).value)
    }

    @Test fun `borderline ratios give low confidence, clear ones medium`() {
        assertEquals(Confidence.MEDIUM, geo.classifyShape(0.82, 0.80, 0.82).confidence)
        assertEquals(Confidence.LOW, geo.classifyShape(0.76, 0.80, 0.82).confidence)
    }

    @Test fun `synthetic default face is oval, a wide one is round`() {
        assertEquals(FaceShape.OVAL, geo.classifyShape(geo.measure(SyntheticFace.frame(), card)!!).value)
        val round = SyntheticFace.frame(SyntheticFace.Spec(faceWidthMm = 150.0, faceLengthMm = 160.0, jawWidthMm = 115.0))
        assertEquals(FaceShape.ROUND, geo.classifyShape(geo.measure(round, card)!!).value)
    }

    @Test fun `angular frames suggested for a round face`() {
        val s = geo.suggestedFrameShapes(FaceShape.ROUND)
        assertTrue(FrameShape.RECTANGLE in s && FrameShape.SQUARE in s)
        assertFalse(FrameShape.ROUND in s)
        FaceShape.entries.forEach { assertTrue(geo.suggestedFrameShapes(it).isNotEmpty()) }
    }

    // ---- aggregation ----------------------------------------------------------------------------

    private fun frames(n: Int, scale: FaceScale = card, noiseMm: Double = 0.2, seed: Int = 1, pd: Double = 63.0, fw: Double = 140.0): List<FaceMeasurement> {
        val rnd = Random(seed)
        val base = geo.measure(SyntheticFace.frame(SyntheticFace.Spec(pdMm = pd, faceWidthMm = fw)), scale)!!
        fun j() = (rnd.nextDouble() * 2 - 1) * noiseMm
        return List(n) {
            base.copy(pdMm = base.pdMm + j(), faceWidthMm = base.faceWidthMm + j(), faceLengthMm = base.faceLengthMm + j(),
                jawWidthMm = base.jawWidthMm + j(), bridgeWidthMm = base.bridgeWidthMm + j(), foreheadWidthMm = base.foreheadWidthMm + j())
        }
    }

    @Test fun `30 card-scaled frames - median, outliers dropped, high confidence`() {
        val fs = frames(30).toMutableList()
        fs[3] = fs[3].copy(pdMm = 71.0)
        fs[17] = fs[17].copy(pdMm = 55.0)
        val agg = FaceAggregator(rules.faceGeometry, gate).aggregate(fs)!!
        assertEquals(63.0, agg.pdMm.value, 0.15)
        assertEquals(2, agg.pd.dropped)
        assertEquals(28, agg.pd.kept)
        assertTrue(agg.pd.spreadMm < 0.5)
        assertEquals(Confidence.HIGH, agg.pdMm.confidence)
        assertEquals(140.0, agg.faceWidthMm.value, 0.15)
        assertEquals(PlausibilityGate.Verdict.OK, agg.plausibility.verdict)
        assertFalse(agg.rescan)
        assertEquals(FaceShape.OVAL, agg.shape.value)
        assertEquals(30, agg.framesUsed)
    }

    @Test fun `noisy frames lower the confidence`() {
        val agg = FaceAggregator(rules.faceGeometry, gate).aggregate(frames(30, noiseMm = 2.5))!!
        assertEquals(Confidence.LOW, agg.pdMm.confidence)
    }

    @Test fun `iris-prior scale caps confidence at LOW, personal iris at MEDIUM`() {
        val agg = FaceAggregator(rules.faceGeometry, gate)
        assertEquals(Confidence.LOW, agg.aggregate(frames(30, FaceScale(0.25, ScaleSource.IRIS_PRIOR)))!!.pdMm.confidence)
        assertEquals(Confidence.MEDIUM, agg.aggregate(frames(30, FaceScale(0.25, ScaleSource.IRIS_PERSONAL)))!!.pdMm.confidence)
    }

    @Test fun `too few frames - low confidence`() {
        val agg = FaceAggregator(rules.faceGeometry, gate).aggregate(frames(rules.faceGeometry.aggregate.minFrames - 1))!!
        assertEquals(Confidence.LOW, agg.pdMm.confidence)
        assertEquals(Confidence.LOW, agg.faceWidthMm.confidence)
    }

    @Test fun `implausible PD to face ratio asks for a rescan`() {
        val agg = FaceAggregator(rules.faceGeometry, gate).aggregate(frames(30, pd = 48.0))!!
        assertTrue(agg.rescan)
        assertEquals(PlausibilityGate.Verdict.RESCAN, agg.plausibility.verdict)
        assertEquals(Confidence.LOW, agg.pdMm.confidence)
    }

    @Test fun `empty list gives null`() {
        assertNull(FaceAggregator(rules.faceGeometry, gate).aggregate(emptyList()))
    }

    @Test fun `robust keep uses MAD with a floor`() {
        val kept = FaceAggregator.robustKeep(listOf(63.0, 63.0, 63.0, 63.4, 70.0), 3.0, 0.5)
        assertEquals(listOf(63.0, 63.0, 63.0, 63.4), kept)
    }

    // ---- plausibility gate on geometry output ---------------------------------------------------

    @Test fun `gate checks a single FaceGeometry measurement directly`() {
        val m = geo.measure(SyntheticFace.frame(), card)!!
        assertEquals(PlausibilityGate.Verdict.OK, gate.check(m).verdict)
        val agg = FaceAggregator(rules.faceGeometry, gate).aggregate(frames(30))!!
        assertEquals(PlausibilityGate.Verdict.OK, gate.check(agg).verdict)
    }

    @Test fun `wrong scale keeps the ratio but is caught by the population ranges`() {
        // Scale 25 % too large: PD and face width grow together, the ratio stays normal.
        val m = geo.measure(SyntheticFace.frame(), FaceScale.fromCard(0.25 * 1.25))!!
        val r = gate.check(m)
        assertEquals(PlausibilityGate.Verdict.LOW_CONFIDENCE, r.verdict)
        assertTrue(PlausibilityGate.Reason.FACE_WIDTH_OUT_OF_RANGE in r.reasons)
        assertTrue(abs(r.ratio!! - 0.45) < 0.01)
    }

    @Test fun `gate rejects non-positive input`() {
        assertEquals(PlausibilityGate.Verdict.RESCAN, gate.check(0.0, 140.0).verdict)
        assertEquals(PlausibilityGate.Verdict.RESCAN, gate.check(Double.NaN, null).verdict)
        assertNotNull(gate.check(62.0, 140.0).predictedPdMm)
        assertEquals(Confidence.HIGH, gate.check(62.0, 140.0).confidenceCap)
    }
}
