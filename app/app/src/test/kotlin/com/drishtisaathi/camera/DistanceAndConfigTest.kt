package com.drishtisaathi.camera

import com.drishtisaathi.calibration.InMemoryCalibrationRepository
import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.config.CameraConfig
import com.drishtisaathi.engine.face.FaceFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProvisionalDistanceTest {
    private val est = ProvisionalIrisDistanceEstimator(11.7)

    @Test fun `pinhole distance from iris size`() {
        // f = 1000 px, iris 23.4 px → 1000 × 11.7 / 23.4 = 500 mm.
        val d = est.estimate(TestFaces.frontal(irisPx = 23.4, focalPx = 1000.0), null)!!
        assertEquals(500.0, d.distanceMm, 0.01)
        assertEquals(Confidence.MEDIUM, d.confidence)
    }

    @Test fun `calibrated iris raises confidence and changes distance`() {
        val d = est.estimate(TestFaces.frontal(irisPx = 24.0, focalPx = 1000.0), 12.0)!!
        assertEquals(500.0, d.distanceMm, 0.01)
        assertEquals(Confidence.HIGH, d.confidence)
    }

    @Test fun `no focal length or iris gives no estimate`() {
        assertNull(est.estimate(TestFaces.frontal(focalPx = null), null))
        assertNull(est.estimate(TestFaces.frontal().let { it.copy(landmarks = it.landmarks.take(468)) }, null))
    }

    @Test fun `provisional PD scales iris-centre distance by distance over focal length`() {
        // 124 px apart at 500 mm with f = 1000 px → 62 mm.
        assertEquals(62.0, ProvisionalPd.mm(TestFaces.frontal(ipdPx = 124.0, focalPx = 1000.0), 500.0)!!, 0.001)
    }

    @Test fun `median window`() {
        val w = MedianWindow(3)
        assertEquals(10.0, w.add(10.0), 0.0)
        assertEquals(15.0, w.add(20.0), 0.0)
        assertEquals(20.0, w.add(100.0), 0.0) // outlier ignored
        assertEquals(100.0, w.add(100.0), 0.0) // window now 20, 100, 100
    }
}

class CameraDistanceProviderTest {
    private class FakeTracker : FaceTracker {
        override val frames: StateFlow<FaceFrame?> = MutableStateFlow(null)
        override fun start() {}
        override fun stop() {}
    }

    private fun provider(focalReal: Boolean = true, smoothing: Int = 1): Pair<CameraDistanceProvider, InMemoryCalibrationRepository> {
        val repo = InMemoryCalibrationRepository()
        val cfg = CameraConfig(distanceSmoothingFrames = smoothing)
        return CameraDistanceProvider(FakeTracker(), repo, cfg, CameraDistanceProvider.defaultEstimator(cfg), { focalReal }, false) to repo
    }

    @Test fun `readings follow frames and keep the last distance when the face is lost`() {
        val (p, _) = provider()
        assertNull(p.readings.value)
        p.onFrame(null, 1)
        assertNull(p.readings.value)
        p.onFrame(TestFaces.frontal(irisPx = 23.4), 2)
        assertEquals(500.0, p.readings.value!!.distanceMm, 0.01)
        assertTrue(p.readings.value!!.faceVisible)
        p.onFrame(null, 3)
        assertFalse(p.readings.value!!.faceVisible)
        assertEquals(500.0, p.readings.value!!.distanceMm, 0.01)
    }

    @Test fun `uses the customer's calibrated iris size`() {
        val (p, repo) = provider()
        repo.update { it.copy(irisDiameterMm = 12.0) }
        p.onFrame(TestFaces.frontal(irisPx = 24.0), 1)
        assertEquals(500.0, p.readings.value!!.distanceMm, 0.01)
        assertEquals(Confidence.HIGH, p.readings.value!!.confidence)
    }

    @Test fun `assumed focal length means low confidence`() {
        val (p, _) = provider(focalReal = false)
        p.onFrame(TestFaces.frontal(), 1)
        assertEquals(Confidence.LOW, p.readings.value!!.confidence)
    }
}

class CameraConfigTest {
    private val rulesText = File("src/main/assets/rules.json").readText()

    @Test fun `rules json camera section lists every config key`() {
        val section = Json.parseToJsonElement(rulesText).jsonObject["camera"]!!.jsonObject
        val descriptor = CameraConfig.serializer().descriptor
        val fields = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }.toSet()
        assertEquals(fields, section.keys)
    }

    @Test fun `parsed camera config matches the documented gates`() {
        val c = RulesConfig.parse(rulesText).camera
        assertEquals(5.0, c.maxAbsYawDeg, 0.0)
        assertEquals(5.0, c.maxAbsRollDeg, 0.0)
        assertEquals(30, c.captureFrameCount)
        assertEquals(85.60 / 53.98, c.cardAspect, 1e-12)
        assertTrue(c.minMeanBrightness < c.maxMeanBrightness)
        assertTrue(c.minFaceWidthFraction < c.maxFaceWidthFraction)
        assertTrue(c.irisMinMm < c.provisionalIrisDiameterMm && c.provisionalIrisDiameterMm < c.irisMaxMm)
    }

    @Test fun `unknown camera key is rejected`() {
        val bad = rulesText.replace("\"maxAbsYawDeg\"", "\"maxAbsYawDegrees\"")
        assertTrue(runCatching { RulesConfig.parse(bad) }.isFailure)
    }
}
