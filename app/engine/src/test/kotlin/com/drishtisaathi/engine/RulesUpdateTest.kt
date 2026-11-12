package com.drishtisaathi.engine

import com.drishtisaathi.engine.face.FaceScale
import com.drishtisaathi.engine.face.ScaleSource
import com.drishtisaathi.engine.face.SyntheticFace
import com.drishtisaathi.engine.vision.DistanceEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** rules_update.md rules 2 (PD distance beyond 0.6 m), 6 (PD without the card) and 7 (ready-reader check). */
class RulesUpdateTest {
    private val rules = TestAssets.rules
    private val catalog = FrameCatalog.parse(TestAssets.text("frames.json"))
    private val engine = DrishtiEngine(rules, TestAssets.anthro, catalog)

    private fun face(source: ScaleSource) = MeasuredFace(
        pdMm = Estimate(63.0, Confidence.HIGH, Source.MEASURED),
        faceWidthMm = Estimate(140.0, Confidence.HIGH, Source.MEASURED),
        bridgeWidthMm = Estimate(18.0, Confidence.HIGH, Source.MEASURED),
        framesUsed = 30,
        scaleSource = source,
    )

    // ---- rule 6 -----------------------------------------------------------------------------------

    @Test fun `rule 6 - camera PD without the card is plus-minus 3 mm and frames are never said to fit`() {
        val r = engine.evaluate(InterviewAnswers(age = 35), face = face(ScaleSource.IRIS_PRIOR))
        val a = r.assessment
        assertEquals(3.3, a.pdUncertaintyMm!!, 1e-9)
        assertTrue(a.fitMeasurementsUncalibrated)
        assertTrue(Note.MEASUREMENTS_NOT_CARD_CALIBRATED in a.notes)
        val fitCodes = setOf(ReasonCode.FITS_FACE, ReasonCode.NARROW_FOR_FACE, ReasonCode.WIDE_FOR_FACE,
            ReasonCode.BRIDGE_FITS, ReasonCode.BRIDGE_MISMATCH)
        val fitReasons = r.frames.flatMap { it.reasons }.filter { it.code in fitCodes }
        assertTrue(fitReasons.isNotEmpty())
        assertTrue(fitReasons.all { it.uncalibrated && !it.assumed })
    }

    @Test fun `rule 6 - card-calibrated capture is plus-minus 1 mm and may claim fit`() {
        listOf(ScaleSource.CARD, ScaleSource.IRIS_PERSONAL).forEach { src ->
            val r = engine.evaluate(InterviewAnswers(age = 35), face = face(src))
            assertEquals(1.2, r.assessment.pdUncertaintyMm!!, 1e-9)
            assertFalse(r.assessment.fitMeasurementsUncalibrated)
            assertTrue(r.frames.flatMap { it.reasons }.none { it.uncalibrated })
            assertFalse(Note.MEASUREMENTS_NOT_CARD_CALIBRATED in r.assessment.notes)
        }
    }

    @Test fun `rule 6 - older stored faces without a scale source count as not calibrated`() {
        val old = face(ScaleSource.CARD).copy(scaleSource = null)
        assertFalse(old.cardCalibrated)
        assertTrue(engine.evaluate(InterviewAnswers(age = 35), face = old).assessment.fitMeasurementsUncalibrated)
    }

    @Test fun `rule 6 - population average keeps the assumed wording, staff ruler counts as calibrated`() {
        val avg = engine.evaluate(InterviewAnswers(age = 35))
        assertNull(avg.assessment.pdUncertaintyMm)
        assertFalse(avg.assessment.fitMeasurementsUncalibrated)
        val fit = avg.frames.first().reasons.first { it.code in setOf(ReasonCode.FITS_FACE, ReasonCode.NARROW_FOR_FACE, ReasonCode.WIDE_FOR_FACE) }
        assertTrue(fit.assumed)
        assertFalse(fit.uncalibrated)

        val staff = engine.evaluate(InterviewAnswers(age = 35), StaffMeasurements(pdMm = 62.0, faceWidthMm = 136.0))
        assertEquals(1.2, staff.assessment.pdUncertaintyMm!!, 1e-9)
        assertFalse(staff.assessment.fitMeasurementsUncalibrated)
    }

    @Test fun `rule 6 - frameFitClaimsRequireCard false allows fit wording`() {
        val off = rules.copy(pdMeasurement = rules.pdMeasurement.copy(frameFitClaimsRequireCard = false))
        val r = DrishtiEngine(off, TestAssets.anthro, catalog).evaluate(InterviewAnswers(age = 35), face = face(ScaleSource.IRIS_PRIOR))
        assertFalse(r.assessment.fitMeasurementsUncalibrated)
        assertEquals(3.3, r.assessment.pdUncertaintyMm!!, 1e-9) // still shown with ±3 mm
    }

    @Test fun `rule 6 - capture carries its scale source into MeasuredFace`() {
        val capture = com.drishtisaathi.engine.face.FaceCapture(rules, TestAssets.anthro)
        val frames = List(30) { SyntheticFace.frame(SyntheticFace.Spec(mmPerPx = 400.0 / 600.0, focalLengthPx = 600.0)) }
        assertEquals(ScaleSource.IRIS_PRIOR, capture.analyse(frames).face!!.scaleSource)
        assertEquals(ScaleSource.IRIS_PERSONAL, capture.analyse(frames, personalIrisMm = 11.7).face!!.scaleSource)
    }

    // ---- rule 7 -----------------------------------------------------------------------------------

    @Test fun `rule 7 - every reading add asks for a ready-reader check`() {
        val near = engine.evaluate(InterviewAnswers(age = 50, nearBlur = true, distanceBlur = false)).assessment.advice!!
        assertTrue(near.add != null)
        assertTrue(near.confirmAddWithReadyReader)
        val noAdd = engine.evaluate(InterviewAnswers(age = 30, distanceBlur = true)).assessment.advice!!
        assertNull(noAdd.add)
        assertFalse(noAdd.confirmAddWithReadyReader)
        val off = rules.copy(readingAdd = rules.readingAdd.copy(verifyWithReadyReader = false))
        val offAdvice = RulesEngine(off, TestAssets.anthro).assess(InterviewAnswers(age = 50, nearBlur = true)).advice!!
        assertFalse(offAdvice.confirmAddWithReadyReader)
    }

    // ---- rule 2 -----------------------------------------------------------------------------------

    private val est = DistanceEstimator(rules)
    private fun at(distanceMm: Double) = SyntheticFace.frame(SyntheticFace.Spec(mmPerPx = distanceMm / 600.0, focalLengthPx = 600.0))

    @Test fun `rule 2 - within 0_6 m the iris is used even with a card PD`() {
        val e = est.estimate(at(400.0), cardPdMm = 63.0)!!
        assertEquals(DistanceEstimator.DistanceMethod.IRIS, e.method)
        assertEquals(400.0, e.distanceMm, 0.5)
    }

    @Test fun `rule 2 - beyond 0_6 m the card PD gives the distance`() {
        listOf(800.0, 1500.0).forEach { d ->
            val e = est.estimate(at(d), cardPdMm = 63.0)!!
            assertEquals(DistanceEstimator.DistanceMethod.CARD_PD, e.method)
            assertEquals(d, e.distanceMm, d * 0.002)
            assertEquals(63.0, e.pdMm!!, 1e-9)
            assertTrue(e.confidence != Confidence.LOW)
        }
    }

    @Test fun `rule 2 - beyond 0_6 m without a card PD the iris is kept at LOW, or nothing when too small`() {
        val e = est.estimate(at(800.0))!!
        assertEquals(DistanceEstimator.DistanceMethod.IRIS, e.method)
        assertEquals(Confidence.LOW, e.confidence)
        assertEquals(800.0, e.distanceMm, 1.0)
        assertNull(est.estimate(at(1500.0))) // iris 4.7 px: below minIrisPx
    }

    @Test fun `rule 2 - face scale from the PD estimate stays consistent`() {
        val e = est.estimate(at(1200.0), cardPdMm = 63.0)!!
        assertEquals(1200.0 / 600.0, FaceScale.from(e).mmPerPx, 0.01)
    }
}
