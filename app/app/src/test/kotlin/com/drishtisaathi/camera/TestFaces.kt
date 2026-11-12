package com.drishtisaathi.camera

import com.drishtisaathi.engine.config.CameraConfig
import com.drishtisaathi.engine.face.FaceFrame
import com.drishtisaathi.engine.face.Landmark
import kotlin.math.cos
import kotlin.math.sin

/** Synthetic faces for the camera tests. */
object TestFaces {
    val cfg = CameraConfig()

    /**
     * A level, frontal, centred face in a [w]×[h] image: face box 0.3..0.7 wide, iris diameter [irisPx] px,
     * iris centres [ipdPx] apart, eyes open (EAR ≈ 0.3).
     */
    fun frontal(
        w: Int = 1000, h: Int = 1000, irisPx: Double = 20.0, ipdPx: Double = 120.0,
        focalPx: Double? = 1000.0, tiltDeg: Double = 0.0, eyeOpen: Double = 0.3, ts: Long = 1L,
    ): FaceFrame {
        val pts = MutableList(FaceFrame.LANDMARK_COUNT) { Landmark(0.5f, 0.5f) }
        fun put(i: Int, xPx: Double, yPx: Double) { pts[i] = Landmark((xPx / w).toFloat(), (yPx / h).toFloat()) }
        val cx = w / 2.0; val cy = h / 2.0
        // Face outline: box from 0.3 to 0.7 of the width/height.
        put(10, cx, h * 0.3); put(152, cx, h * 0.7); put(234, w * 0.3, cy); put(454, w * 0.7, cy)
        put(1, cx, cy) // nose tip in the middle → yaw 0 from landmarks
        val a = Math.toRadians(tiltDeg)
        fun rot(dx: Double, dy: Double) = (cx + dx * cos(a) - dy * sin(a)) to (cy - 50 + dx * sin(a) + dy * cos(a))
        fun putR(i: Int, dx: Double, dy: Double) { val (x, y) = rot(dx, dy); put(i, x, y) }
        val half = ipdPx / 2
        val r = irisPx / 2
        // Iris: centre, then right/top/left/bottom.
        for ((base, ex) in listOf(468 to -half, 473 to half)) {
            putR(base, ex, 0.0); putR(base + 1, ex + r, 0.0); putR(base + 2, ex, -r); putR(base + 3, ex - r, 0.0); putR(base + 4, ex, r)
        }
        // Eye contours: width 60 px, lid gap = eyeOpen × 60.
        val gap = eyeOpen * 60.0
        fun eye(ids: IntArray, ex: Double, outerSign: Double) {
            putR(ids[0], ex + outerSign * 30, 0.0); putR(ids[1], ex - outerSign * 30, 0.0)
            putR(ids[2], ex - 10, -gap / 2); putR(ids[3], ex - 10, gap / 2)
            putR(ids[4], ex + 10, -gap / 2); putR(ids[5], ex + 10, gap / 2)
        }
        eye(intArrayOf(33, 133, 160, 144, 158, 153), -half, -1.0)
        eye(intArrayOf(263, 362, 385, 380, 387, 373), half, 1.0)
        return FaceFrame(pts, w, h, transformMatrix = null, focalLengthPx = focalPx, mirrored = true, timestampMs = ts)
    }

    /** Column-major 4×4 rotation about the vertical axis. */
    fun yawMatrix(deg: Double): FloatArray {
        val a = Math.toRadians(deg)
        val c = cos(a).toFloat(); val s = sin(a).toFloat()
        // Columns: (c,0,-s,0), (0,1,0,0), (s,0,c,0), (tx,ty,tz,1)
        return floatArrayOf(c, 0f, -s, 0f, 0f, 1f, 0f, 0f, s, 0f, c, 0f, 0f, 0f, -50f, 1f)
    }
}
