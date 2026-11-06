package com.drishtisaathi.engine.tryon

import com.drishtisaathi.engine.FrameOverlay
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.TestAssets
import com.drishtisaathi.engine.config.TryOnConfig
import com.drishtisaathi.engine.face.FaceFrame
import com.drishtisaathi.engine.face.Landmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class TryOnPlacementTest {
    private val w = 1000
    private val h = 800

    /** Synthetic face: pupils at [l]/[r] (px), cheek points at [cl]/[cr] (px), everything else at the centre. */
    private fun face(
        l: Pair<Double, Double>, r: Pair<Double, Double>,
        cl: Pair<Double, Double> = 300.0 to 450.0, cr: Pair<Double, Double> = 700.0 to 450.0,
        matrix: FloatArray? = null, focal: Double? = null,
    ): FaceFrame {
        val pts = MutableList(FaceFrame.LANDMARK_COUNT) { Landmark(0.5f, 0.5f) }
        fun set(i: Int, p: Pair<Double, Double>) { pts[i] = Landmark((p.first / w).toFloat(), (p.second / h).toFloat()) }
        set(TryOnPlacement.LEFT_IRIS, l); set(TryOnPlacement.RIGHT_IRIS, r)
        set(234, cl); set(454, cr)
        return FaceFrame(pts, w, h, transformMatrix = matrix, focalLengthPx = focal)
    }

    private val overlay = FrameOverlay(
        png = "frames/test.png", widthPx = 900, heightPx = 300,
        leftLensCenterPx = listOf(225.0, 150.0), rightLensCenterPx = listOf(675.0, 150.0),
        frontWidthPx = 880.0, lensHeightPx = 250.0,
    )

    /** No lift, no forward offset, no perspective: pure 2D checks. */
    private val flat = TryOnConfig(lensCentreAbovePupilMm = 0.0, frontOffsetMm = 0.0, perspective = false)

    private fun px(p: NPoint) = p.x * w to p.y * h
    private fun dist(a: NPoint, b: NPoint): Double { val (ax, ay) = px(a); val (bx, by) = px(b); return hypot(bx - ax, by - ay) }
    private fun eq(e: Pair<Double, Double>, a: NPoint, tol: Double = 0.5) {
        val (x, y) = px(a)
        assertEquals("x", e.first, x, tol); assertEquals("y", e.second, y, tol)
    }

    @Test fun `no face or too few landmarks - nothing to draw`() {
        assertNull(TryOnPlacement.place(null, overlay, 140.0))
        val few = FaceFrame(List(100) { Landmark(0.5f, 0.5f) }, w, h)
        assertNull(TryOnPlacement.place(few, overlay, 140.0))
    }

    @Test fun `fallback without frame width puts the lens centres on the pupils`() {
        val p = TryOnPlacement.place(face(400.0 to 400.0, 600.0 to 400.0), overlay, null, cfg = flat)!!
        assertEquals(TryOnScale.LENS_CENTRES, p.scaleSource)
        eq(400.0 to 400.0, p.leftLens)
        eq(600.0 to 400.0, p.rightLens)
        // PNG scaled by 200 / 450 → width 400 px, height 133.3 px
        assertEquals(400.0, dist(p.topLeft, p.topRight), 0.5)
        assertEquals(133.3, dist(p.topLeft, p.bottomLeft), 0.5)
        assertEquals(0.0, p.rollDeg, 1e-9)
    }

    @Test fun `pupil order in the landmarks does not matter`() {
        val a = TryOnPlacement.place(face(400.0 to 400.0, 600.0 to 410.0), overlay, 140.0, cfg = flat)!!
        val b = TryOnPlacement.place(face(600.0 to 410.0, 400.0 to 400.0), overlay, 140.0, cfg = flat)!!
        a.corners.zip(b.corners).forEach { (u, v) -> assertEquals(u.x, v.x, 1e-9); assertEquals(u.y, v.y, 1e-9) }
    }

    @Test fun `scale from real frame width and PD`() {
        // PD 63 over 200 px → 3.175 px/mm; 140 mm front → 444.4 px front → PNG scale 444.4/880
        val p = TryOnPlacement.place(face(400.0 to 400.0, 600.0 to 400.0), overlay, 140.0, pdMm = 63.0, cfg = flat)!!
        assertEquals(TryOnScale.PD, p.scaleSource)
        assertEquals(200.0 / 63.0, p.pxPerMm, 1e-5)
        val s = 140.0 * 200.0 / 63.0 / 880.0
        assertEquals(900 * s, dist(p.topLeft, p.topRight), 0.5)
        assertEquals(300 * s, dist(p.topLeft, p.bottomLeft), 0.5)
        // lens centres symmetric about the pupil midpoint, 450·s apart
        eq(500.0 - 225 * s to 400.0, p.leftLens)
        eq(500.0 + 225 * s to 400.0, p.rightLens)
    }

    @Test fun `face width beats PD as scale source`() {
        // cheek points 400 px apart, face width 140 mm → 2.857 px/mm whatever the PD says
        val p = TryOnPlacement.place(face(400.0 to 400.0, 600.0 to 400.0), overlay, 130.0, faceWidthMm = 140.0, pdMm = 50.0, cfg = flat)!!
        assertEquals(TryOnScale.FACE_WIDTH, p.scaleSource)
        assertEquals(400.0 / 140.0, p.pxPerMm, 1e-5)
        assertEquals(130.0 * 400.0 / 140.0 / 880.0 * 900, dist(p.topLeft, p.topRight), 0.5)
    }

    @Test fun `no PD known uses the default PD from config`() {
        val p = TryOnPlacement.place(face(400.0 to 400.0, 600.0 to 400.0), overlay, 140.0, cfg = flat)!!
        assertEquals(200.0 / flat.defaultPdMm, p.pxPerMm, 1e-5)
    }

    @Test fun `lens centres sit the configured height above the pupils`() {
        val cfg = flat.copy(lensCentreAbovePupilMm = 3.0)
        val p = TryOnPlacement.place(face(400.0 to 400.0, 600.0 to 400.0), overlay, null, pdMm = 63.0, cfg = cfg)!!
        val lift = 3.0 * 200.0 / 63.0
        eq(400.0 to 400.0 - lift, p.leftLens)
        eq(600.0 to 400.0 - lift, p.rightLens)
    }

    @Test fun `roll follows the eye line`() {
        val ang = Math.toRadians(20.0)
        val c = 500.0 to 400.0
        val l = c.first - 100 * cos(ang) to c.second - 100 * sin(ang)
        val r = c.first + 100 * cos(ang) to c.second + 100 * sin(ang)
        val p = TryOnPlacement.place(face(l, r), overlay, null, cfg = flat)!!
        assertEquals(20.0, p.rollDeg, 1e-3)
        eq(l, p.leftLens); eq(r, p.rightLens)
        val (ax, ay) = px(p.topLeft); val (bx, by) = px(p.topRight)
        assertEquals(20.0, Math.toDegrees(atan2(by - ay, bx - ax)), 1e-3)
        // size unchanged by roll
        assertEquals(400.0, dist(p.topLeft, p.topRight), 0.5)
    }

    @Test fun `yaw and pitch recovered from the transformation matrix`() {
        val (yaw, pitch) = TryOnPlacement.yawPitch(TryOnPlacement.rotationMatrix(20.0, -10.0, 15.0))
        assertEquals(20.0, Math.toDegrees(yaw), 1e-4)
        assertEquals(-10.0, Math.toDegrees(pitch), 1e-4)
    }

    @Test fun `yaw foreshortens the frame horizontally, scale stays put`() {
        val yaw = 30.0
        val half = 100 * cos(Math.toRadians(yaw)) // pupils come closer together when the head turns
        val m = TryOnPlacement.rotationMatrix(yaw, 0.0)
        val turned = TryOnPlacement.place(face(500 - half to 400.0, 500 + half to 400.0, matrix = m), overlay, 140.0, pdMm = 63.0, cfg = flat)!!
        val front = TryOnPlacement.place(face(400.0 to 400.0, 600.0 to 400.0), overlay, 140.0, pdMm = 63.0, cfg = flat)!!
        assertEquals(30.0, turned.yawDeg, 1e-4)
        assertEquals(front.pxPerMm, turned.pxPerMm, 1e-3)
        assertEquals(dist(front.topLeft, front.topRight) * cos(Math.toRadians(yaw)), dist(turned.topLeft, turned.topRight), 0.5)
        assertEquals(dist(front.topLeft, front.bottomLeft), dist(turned.topLeft, turned.bottomLeft), 0.5)
        // lens centres still on the pupils' line, symmetric about their midpoint
        assertEquals(400.0, turned.leftLens.y * h, 1e-3)
        assertEquals(500.0, (turned.leftLens.x + turned.rightLens.x) / 2 * w, 1e-3)
    }

    @Test fun `yaw with perspective makes the far side shorter`() {
        val m = TryOnPlacement.rotationMatrix(30.0, 0.0)
        val half = 100 * cos(Math.toRadians(30.0))
        val p = TryOnPlacement.place(face(500 - half to 400.0, 500 + half to 400.0, matrix = m, focal = 900.0), overlay, 140.0,
            pdMm = 63.0, cfg = flat.copy(perspective = true))!!
        val leftEdge = dist(p.topLeft, p.bottomLeft)
        val rightEdge = dist(p.topRight, p.bottomRight)
        // positive yaw turns the face towards image +x, so the image-right side moves away from the camera
        assertTrue("left $leftEdge right $rightEdge", rightEdge < leftEdge)
        // and a mirror-image turn mirrors it
        val q = TryOnPlacement.place(face(500 - half to 400.0, 500 + half to 400.0, matrix = TryOnPlacement.rotationMatrix(-30.0, 0.0), focal = 900.0),
            overlay, 140.0, pdMm = 63.0, cfg = flat.copy(perspective = true))!!
        assertEquals(leftEdge, dist(q.topRight, q.bottomRight), 1e-3)
    }

    @Test fun `pitch foreshortens vertically`() {
        val p = TryOnPlacement.place(face(400.0 to 400.0, 600.0 to 400.0, matrix = TryOnPlacement.rotationMatrix(0.0, 25.0)),
            overlay, 140.0, pdMm = 63.0, cfg = flat)!!
        val front = TryOnPlacement.place(face(400.0 to 400.0, 600.0 to 400.0), overlay, 140.0, pdMm = 63.0, cfg = flat)!!
        assertEquals(25.0, p.pitchDeg, 1e-4)
        assertEquals(dist(front.topLeft, front.bottomLeft) * cos(Math.toRadians(25.0)), dist(p.topLeft, p.bottomLeft), 0.5)
        assertEquals(dist(front.topLeft, front.topRight), dist(p.topLeft, p.topRight), 0.5)
    }

    @Test fun `angles are clamped and the matrix can be switched off`() {
        val m = TryOnPlacement.rotationMatrix(70.0, 50.0)
        val f = face(450.0 to 400.0, 550.0 to 400.0, matrix = m)
        val p = TryOnPlacement.place(f, overlay, 140.0, cfg = flat)!!
        assertEquals(flat.maxYawDeg, p.yawDeg, 1e-3)
        assertEquals(flat.maxPitchDeg, p.pitchDeg, 1e-3)
        val off = TryOnPlacement.place(f, overlay, 140.0, cfg = flat.copy(useTransformMatrix = false))!!
        assertEquals(0.0, off.yawDeg, 1e-9); assertEquals(0.0, off.pitchDeg, 1e-9)
    }

    @Test fun `front offset shifts the frame sideways on a head turn`() {
        val m = TryOnPlacement.rotationMatrix(20.0, 0.0)
        val half = 100 * cos(Math.toRadians(20.0))
        val f = face(500 - half to 400.0, 500 + half to 400.0, matrix = m)
        val none = TryOnPlacement.place(f, overlay, 140.0, pdMm = 63.0, cfg = flat)!!
        val some = TryOnPlacement.place(f, overlay, 140.0, pdMm = 63.0, cfg = flat.copy(frontOffsetMm = 12.0))!!
        val shift = (some.leftLens.x - none.leftLens.x) * w
        assertEquals(12.0 * 200.0 / 63.0 * sin(Math.toRadians(20.0)), shift, 0.5)
    }

    @Test fun `width scale fudge applies`() {
        val f = face(400.0 to 400.0, 600.0 to 400.0)
        val a = TryOnPlacement.place(f, overlay, 140.0, cfg = flat)!!
        val b = TryOnPlacement.place(f, overlay, 140.0, cfg = flat.copy(widthScale = 1.1))!!
        assertEquals(1.1 * dist(a.topLeft, a.topRight), dist(b.topLeft, b.topRight), 1e-3)
    }

    @Test fun `smoother eases small moves and restarts on jumps or lost face`() {
        val sm = TryOnSmoother(flat.copy(smoothingAlpha = 0.5))
        val a = TryOnPlacement.place(face(400.0 to 400.0, 600.0 to 400.0), overlay, 140.0, cfg = flat)!!
        val b = TryOnPlacement.place(face(404.0 to 400.0, 604.0 to 400.0), overlay, 140.0, cfg = flat)!!
        assertEquals(a, sm.update(a))
        val mid = sm.update(b)!!
        assertEquals((a.topLeft.x + b.topLeft.x) / 2, mid.topLeft.x, 1e-9)
        assertNull(sm.update(null))
        assertEquals(b, sm.update(b)) // restarted
        val far = TryOnPlacement.place(face(100.0 to 200.0, 300.0 to 200.0), overlay, 140.0, cfg = flat)!!
        assertEquals(far, sm.update(far)) // jump: no easing
    }

    @Test fun `frame front width from the printed size`() {
        val f = com.drishtisaathi.engine.FrameCatalog.parse(TestAssets.text("frames.json")).frames.first { it.id == "F01" }
        assertEquals(134.0, TryOnPlacement.frontWidthMm(f, TestAssets.rules.frameScoring.endpieceMmEachSide), 1e-9)
    }

    // ---- config ----

    @Test fun `tryOn section in rules json parses`() {
        val c = TestAssets.rules.tryOn
        assertEquals(2.0, c.lensCentreAbovePupilMm, 1e-9)
        assertEquals(234, c.faceWidthLandmarkLeft)
        assertEquals(454, c.faceWidthLandmarkRight)
        assertTrue(c.useTransformMatrix)
        assertNotNull(TryOnPlacement.place(face(400.0 to 400.0, 600.0 to 400.0), overlay, 140.0, cfg = c))
    }

    @Test fun `unknown or bad tryOn key is rejected`() {
        val text = TestAssets.text("rules.json")
        assertThrows(Exception::class.java) { RulesConfig.parse(text.replace("\"frontOffsetMm\"", "\"frontOfsetMm\"")) }
        assertThrows(IllegalArgumentException::class.java) {
            RulesConfig.parse(text.replace("\"smoothingAlpha\": 0.5", "\"smoothingAlpha\": 0.0"))
        }
    }
}
