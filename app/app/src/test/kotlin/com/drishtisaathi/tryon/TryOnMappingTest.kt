package com.drishtisaathi.tryon

import com.drishtisaathi.engine.FrameOverlay
import com.drishtisaathi.engine.config.TryOnConfig
import com.drishtisaathi.engine.face.FaceFrame
import com.drishtisaathi.engine.face.Landmark
import com.drishtisaathi.engine.tryon.TryOnPlacement
import com.drishtisaathi.i18n.STryOn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TryOnMappingTest {
    private fun frame(w: Int, h: Int, mirrored: Boolean) = FaceFrame(List(478) { Landmark(0.5f, 0.5f) }, w, h, mirrored = mirrored)

    @Test fun `fill-center crops the longer side and keeps the centre`() {
        // 480x640 image in a 1080x1920 view: scale max(2.25, 3) = 3 → 1440 px wide, 180 px cropped each side
        val f = frame(480, 640, mirrored = false)
        val c = FillCenterMapping.toView(0.5, 0.5, f, 1080f, 1920f)
        assertEquals(540f, c.x, 1e-3f); assertEquals(960f, c.y, 1e-3f)
        val tl = FillCenterMapping.toView(0.0, 0.0, f, 1080f, 1920f)
        assertEquals((1080f - 480f * 3f) / 2f, tl.x, 1e-3f) // cropped left/right
        assertEquals(0f, tl.y, 1e-3f)
    }

    @Test fun `mirrored image flips x only`() {
        val f = frame(480, 640, mirrored = true)
        val p = FillCenterMapping.toView(0.25, 0.25, f, 480f, 640f)
        assertEquals(480f - 120f, p.x, 1e-3f); assertEquals(160f, p.y, 1e-3f)
    }

    @Test fun `view quad follows the pose corners`() {
        val pts = MutableList(478) { Landmark(0.5f, 0.5f) }
        pts[468] = Landmark(0.4f, 0.5f); pts[473] = Landmark(0.6f, 0.5f)
        val f = FaceFrame(pts, 1000, 1000, mirrored = false)
        val ov = FrameOverlay("frames/x.png", 900, 300, listOf(225.0, 150.0), listOf(675.0, 150.0), 880.0, 250.0)
        val pose = TryOnPlacement.place(f, ov, null, cfg = TryOnConfig(lensCentreAbovePupilMm = 0.0, frontOffsetMm = 0.0, perspective = false))!!
        val q = viewQuad(pose, f, FillCenterMapping, 1000f, 1000f)
        // 200 px pupil distance over 450 px lens distance → 400 px wide, centred on x = 500
        assertEquals(300f, q[0], 0.5f); assertEquals(700f, q[2], 0.5f)
        assertEquals(q[1], q[3], 0.01f)
        val m = viewQuad(pose, f.copy(mirrored = true), FillCenterMapping, 1000f, 1000f)
        assertEquals(700f, m[0], 0.5f) // PNG's left edge lands on the view's right when mirrored
    }

    @Test fun `try-on strings are bilingual and avoid banned wording`() {
        val all = listOf(STryOn.title, STryOn.lookAtCamera, STryOn.noFace, STryOn.pickFrame, STryOn.demoNote,
            STryOn.noFrames, STryOn.loading, STryOn.sizeLine)
        assertTrue(all.all { it.en.isNotBlank() && it.hi.isNotBlank() && it.en != it.hi })
        assertTrue(STryOn.demoNote.en.contains("Demo"))
    }
}
