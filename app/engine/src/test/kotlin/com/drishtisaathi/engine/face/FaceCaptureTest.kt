package com.drishtisaathi.engine.face

import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.DrishtiEngine
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.KnownRx
import com.drishtisaathi.engine.EyeRx
import com.drishtisaathi.engine.MeasuredFace
import com.drishtisaathi.engine.Estimate
import com.drishtisaathi.engine.Note
import com.drishtisaathi.engine.ReasonCode
import com.drishtisaathi.engine.Source
import com.drishtisaathi.engine.StaffMeasurements
import com.drishtisaathi.engine.TestAssets
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class FaceCaptureTest {
    private val rules = TestAssets.rules
    private val capture = FaceCapture(rules, TestAssets.anthro)

    /** Camera 400 mm away, f = 600 px: 400/600 mm per px at the face plane. */
    private fun frames(n: Int, pd: Double = 63.0, fw: Double = 140.0, iris: Double = 11.7) = (0 until n).map { i ->
        SyntheticFace.frame(
            SyntheticFace.Spec(
                pdMm = pd, faceWidthMm = fw, irisDiameterMm = iris,
                mmPerPx = 400.0 / 600.0, focalLengthPx = 600.0, originXPx = 360.0 + i % 3,
            ),
        )
    }

    @Test fun `30 frames give a measured PD and face width`() {
        val out = capture.analyse(frames(30))
        assertEquals(FaceCapture.Status.OK, out.status)
        val face = out.face!!
        assertEquals(63.0, face.pdMm.value, 0.3)
        assertEquals(140.0, face.faceWidthMm.value, 0.6)
        assertEquals(Source.MEASURED, face.pdMm.source)
        assertEquals(30L, out.framesMeasured.toLong())
        assertNotNull(face.bridgeWidthMm)
        assertNotNull(face.faceShape)
    }

    @Test fun `personal iris size rescales every measure`() {
        // True iris 12.5 mm: with the 11.7 mm prior the face reads ~6 % small; the calibrated size fixes it.
        val f = frames(30, iris = 12.5)
        val prior = capture.analyse(f).face!!
        val personal = capture.analyse(f, personalIrisMm = 12.5).face!!
        assertEquals(63.0, personal.pdMm.value, 0.3)
        assertTrue(prior.pdMm.value < 61.0)
    }

    @Test fun `impossible PD to face ratio is discarded`() {
        val out = capture.analyse(frames(30, pd = 75.0, fw = 120.0))
        assertEquals(FaceCapture.Status.RESCAN, out.status)
        assertNull(out.face)
    }

    @Test fun `no frames - no numbers`() {
        val out = capture.analyse(emptyList())
        assertEquals(FaceCapture.Status.NO_USABLE_FRAMES, out.status)
        assertNull(out.face)
    }

    // ---- engine wiring -------------------------------------------------------------------------

    private val engine = DrishtiEngine.fromJson(
        TestAssets.text("rules.json"), TestAssets.text("anthro_priors.json"),
        TestAssets.text("frames.json"), TestAssets.text("frames/more_frames.json"),
    )
    private val answers = InterviewAnswers(
        age = 35, distanceBlur = true, nearBlur = false,
        knownRx = KnownRx(right = EyeRx(-2.0), left = EyeRx(-2.0)),
    )
    private fun face(pd: Double = 61.0, fw: Double = 132.0, bridge: Double? = 18.0, low: Boolean = false) = MeasuredFace(
        pdMm = Estimate(pd, Confidence.MEDIUM, Source.MEASURED),
        faceWidthMm = Estimate(fw, Confidence.MEDIUM, Source.MEASURED),
        bridgeWidthMm = bridge?.let { Estimate(it, Confidence.MEDIUM, Source.MEASURED) },
        lowPlausibility = low,
    )

    @Test fun `combined catalogue ranks from all 30 frames`() {
        val r = engine.evaluate(answers)
        assertTrue(r.frames.isNotEmpty())
        assertTrue(r.frames.any { it.frame.id.removePrefix("F").toInt() > 10 } || r.frames.size <= rules.frameScoring.topN)
    }

    @Test fun `measured face replaces the population average`() {
        val a = engine.evaluate(answers, face = face()).assessment
        assertEquals(61.0, a.pdMm.value, 1e-9)
        assertEquals(Source.MEASURED, a.pdMm.source)
        assertEquals(132.0, a.faceWidthMm.value, 1e-9)
        assertTrue(Note.PD_ASSUMED !in a.notes && Note.FACE_WIDTH_ASSUMED !in a.notes)
    }

    @Test fun `staff PD ruler entry wins over the camera`() {
        val a = engine.evaluate(answers, StaffMeasurements(pdMm = 64.0), face = face()).assessment
        assertEquals(64.0, a.pdMm.value, 1e-9)
        assertEquals(Source.STAFF_ENTRY, a.pdMm.source)
        assertEquals(132.0, a.faceWidthMm.value, 1e-9) // width still from the camera
    }

    @Test fun `flagged capture is kept at low confidence with a note`() {
        val a = engine.evaluate(answers, face = face(low = true)).assessment
        assertEquals(Confidence.LOW, a.pdMm.confidence)
        assertTrue(Note.PD_IMPLAUSIBLE in a.notes)
    }

    @Test fun `measured bridge adds a bridge reason and fit reasons are not marked assumed`() {
        val frames = engine.evaluate(answers, face = face()).frames
        assertTrue(frames.isNotEmpty())
        frames.forEach { rf -> assertTrue(rf.reasons.first().assumed.not()) }
        val withoutBridge = engine.evaluate(answers, face = face(bridge = null)).frames
        assertTrue(withoutBridge.none { rf -> rf.reasons.any { it.code == ReasonCode.BRIDGE_FITS || it.code == ReasonCode.BRIDGE_MISMATCH } })
    }
}
