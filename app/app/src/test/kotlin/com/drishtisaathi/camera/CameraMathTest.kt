package com.drishtisaathi.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocalLengthTest {
    // 4.0 mm lens, 6.4 × 4.8 mm sensor, 4000 × 3000 pixel array → 2500 px at full resolution.
    @Test fun `16 by 9 stream from 4 by 3 sensor keeps full width`() {
        val f = FocalLength.px(4.0, 6.4, 4.8, 1280, 720, 0, 4000, 3000, 4000, 3000)
        assertEquals(800.0, f, 1e-9) // 2500 × 1280/4000
    }

    @Test fun `rotated portrait image gives the same focal length`() {
        val landscape = FocalLength.px(4.0, 6.4, 4.8, 1280, 720, 0, 4000, 3000, 4000, 3000)
        val portrait270 = FocalLength.px(4.0, 6.4, 4.8, 720, 1280, 270, 4000, 3000, 4000, 3000)
        val portrait90 = FocalLength.px(4.0, 6.4, 4.8, 720, 1280, 90, 4000, 3000, 4000, 3000)
        assertEquals(landscape, portrait270, 1e-9)
        assertEquals(landscape, portrait90, 1e-9)
    }

    @Test fun `4 by 3 stream from 16 by 9 sensor is limited by height`() {
        // Sensor 6.4 × 3.6 mm, 3840 × 2160 px; a 640×480 stream crops the sides: scale = 480/2160.
        val f = FocalLength.px(4.0, 6.4, 3.6, 640, 480, 0, 3840, 2160, 3840, 2160)
        assertEquals(4.0 * 3840 / 6.4 * 480.0 / 2160.0, f, 1e-9)
    }

    @Test fun `fallback without pixel array uses physical width`() {
        assertEquals(4.0 * 1280 / 6.4, FocalLength.px(4.0, 6.4, 4.8, 720, 1280, 90), 1e-9)
        assertEquals(4.0 * 1280 / 6.4, FocalLength.px(4.0, 6.4, 4.8, 1280, 720, 0), 1e-9)
    }

    @Test fun `field of view fallback`() {
        // 90° FOV → f = half the long side.
        assertEquals(640.0, FocalLength.fromFov(720, 1280, 90.0), 1e-6)
    }
}

class PreviewMappingTest {
    @Test fun `same aspect scales without offset and mirrors x`() {
        val t = PreviewTransform(720, 1280, 1080f, 1920f, mirrored = true)
        assertEquals(1.5f, t.scale, 1e-6f)
        assertEquals(ViewPoint(810f, 0f), t.map(0.25f, 0f))
        assertEquals(ViewPoint(540f, 960f), t.map(0.5f, 0.5f))
    }

    @Test fun `fill center crops the overflowing axis equally`() {
        // View is shorter than the scaled image: scale = 1.5, 1920 − 1800 = 120 px cropped, 60 top and bottom.
        val t = PreviewTransform(720, 1280, 1080f, 1800f, mirrored = false)
        assertEquals(1.5f, t.scale, 1e-6f)
        val top = t.map(0.25f, 0f)
        assertEquals(270f, top.x, 1e-4f)
        assertEquals(-60f, top.y, 1e-4f)
        assertFalse(t.isVisible(top))
        assertEquals(900f, t.map(0.5f, 0.5f).y, 1e-4f)
        assertTrue(t.isVisible(t.map(0.5f, 0.5f)))
    }

    @Test fun `fill center crops width when the view is wider`() {
        // 720×1280 image in a 1200×1600 view: sx = 1.667, sy = 1.25 → scale 1.667, height overflows.
        val t = PreviewTransform(720, 1280, 1200f, 1600f, mirrored = true)
        assertEquals(1200f / 720f, t.scale, 1e-5f)
        val p = t.map(0f, 0.5f) // mirrored: image left edge goes to view right edge
        assertEquals(1200f, p.x, 1e-3f)
        assertEquals(800f, p.y, 1e-3f)
    }

    @Test fun `fit center letterboxes`() {
        val t = PreviewTransform(720, 1280, 1080f, 1800f, mirrored = false, scaleType = PreviewScale.FIT_CENTER)
        assertEquals(1800f / 1280f, t.scale, 1e-6f)
        val x0 = t.map(0f, 0f).x
        assertEquals((1080f - 720f * t.scale) / 2f, x0, 1e-3f)
    }

    @Test fun `forFrame uses frame size and mirroring`() {
        val f = TestFaces.frontal(w = 720, h = 1280)
        val t = PreviewTransform.forFrame(f, 720f, 1280f)
        assertTrue(t.mirrored)
        assertEquals(ViewPoint(720f, 0f), t.map(0f, 0f))
    }
}

class FaceMetricsTest {
    @Test fun `iris diameter and centre distance`() {
        val f = TestFaces.frontal(irisPx = 24.0, ipdPx = 130.0)
        assertEquals(24.0, FaceMetrics.meanIrisDiameterPx(f)!!, 1e-3)
        assertEquals(130.0, FaceMetrics.irisCentreDistancePx(f)!!, 1e-3)
    }

    @Test fun `missing iris points gives null`() {
        val f = TestFaces.frontal().let { it.copy(landmarks = it.landmarks.take(468)) }
        assertNull(FaceMetrics.meanIrisDiameterPx(f))
    }

    @Test fun `eye aspect ratio open and closed`() {
        val (r, l) = FaceMetrics.eyeAspectRatios(TestFaces.frontal(eyeOpen = 0.3))
        assertEquals(0.3, r, 1e-3); assertEquals(0.3, l, 1e-3)
        assertEquals(0.05, FaceMetrics.eyeAspectRatios(TestFaces.frontal(eyeOpen = 0.05)).first, 1e-3)
    }

    @Test fun `roll follows the eye line`() {
        assertEquals(0.0, FaceMetrics.provisionalRollDeg(TestFaces.frontal()), 1e-3)
        assertEquals(8.0, FaceMetrics.provisionalRollDeg(TestFaces.frontal(tiltDeg = 8.0)), 1e-2)
        assertEquals(-12.0, FaceMetrics.provisionalRollDeg(TestFaces.frontal(tiltDeg = -12.0)), 1e-2)
    }

    @Test fun `yaw from transformation matrix`() {
        assertEquals(10.0, FaceMetrics.yawFromMatrixDeg(TestFaces.yawMatrix(10.0)), 1e-3)
        assertEquals(-3.0, FaceMetrics.yawFromMatrixDeg(TestFaces.yawMatrix(-3.0)), 1e-3)
        val f = TestFaces.frontal().copy(transformMatrix = TestFaces.yawMatrix(7.0))
        assertEquals(7.0, FaceMetrics.headPose(f).yawDeg, 1e-3)
    }

    @Test fun `yaw from landmarks when no matrix`() {
        assertEquals(0.0, FaceMetrics.headPose(TestFaces.frontal()).yawDeg, 1e-3)
    }

    @Test fun `face box`() {
        val b = FaceMetrics.faceBox(TestFaces.frontal())
        assertEquals(0.3f, b.left, 1e-4f); assertEquals(0.7f, b.right, 1e-4f)
        assertEquals(0.5f, b.centreX, 1e-4f); assertEquals(0.4f, b.width, 1e-4f)
    }
}

class ImageStatsTest {
    @Test fun `uniform image has zero laplacian variance`() {
        val px = IntArray(20 * 20) { 0xFF808080.toInt() }
        val s = ImageStatsCalc.compute(px, 20, 20)
        assertEquals(128.0, s.meanBrightness, 1.0)
        assertEquals(0.0, s.laplacianVariance, 1e-9)
    }

    @Test fun `checkerboard is much sharper than a gradient`() {
        val w = 32
        val checker = IntArray(w * w) { i -> if (((i % w) + (i / w)) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        val ramp = IntArray(w * w) { i -> val v = (i % w) * 8; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val sharp = ImageStatsCalc.compute(checker, w, w).laplacianVariance
        val soft = ImageStatsCalc.compute(ramp, w, w).laplacianVariance
        assertTrue(sharp > 1e5)
        assertEquals(0.0, soft, 1e-9) // linear ramp: Laplacian is 0 everywhere
    }

    @Test fun `luma weights`() {
        assertEquals(255, ImageStatsCalc.luma(0xFFFFFFFF.toInt()))
        assertEquals(76, ImageStatsCalc.luma(0xFFFF0000.toInt()))
    }

    @Test fun `roi is clamped to the image`() {
        val r = ImageStatsCalc.roi(FaceBox(-0.1f, 0.2f, 0.5f, 1.2f), 100, 200)!!
        assertEquals(listOf(0, 40, 50, 160), r.toList())
        assertNull(ImageStatsCalc.roi(FaceBox(0.5f, 0.5f, 0.505f, 0.6f), 100, 100))
    }
}
