package com.drishtisaathi.camera

import com.drishtisaathi.engine.face.FaceFrame
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot

/** Face box in normalised image coordinates. */
data class FaceBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centreX get() = (left + right) / 2f
    val centreY get() = (top + bottom) / 2f
}

/** Head pose in degrees. Sign conventions are not relied on by the gates (they use |yaw|, |roll|). */
data class HeadPose(val yawDeg: Double, val rollDeg: Double)

/**
 * Small landmark measurements the camera layer needs (MediaPipe Face Landmarker 478-point indices).
 * Pure Kotlin, unit-tested. Track A's engine owns the full face geometry (PD, face width); these are only
 * what the quality gates and the provisional distance need.
 */
object FaceMetrics {
    // Iris: centre then 4 boundary points (right/top/left/bottom in image space).
    val RIGHT_IRIS = 468..472   // subject's right eye (image left on the un-mirrored image)
    val LEFT_IRIS = 473..477

    // Eye contour points for the eye aspect ratio: [outer corner, inner corner, upper1, lower1, upper2, lower2].
    private val RIGHT_EYE = intArrayOf(33, 133, 160, 144, 158, 153)
    private val LEFT_EYE = intArrayOf(263, 362, 385, 380, 387, 373)

    /** Outer eye corners: subject's right (33) and left (263). */
    const val RIGHT_EYE_OUTER = 33
    const val LEFT_EYE_OUTER = 263
    const val NOSE_TIP = 1
    const val RIGHT_CHEEK = 234
    const val LEFT_CHEEK = 454

    fun hasIris(f: FaceFrame) = f.landmarks.size >= FaceFrame.LANDMARK_COUNT

    private fun dist(f: FaceFrame, a: Int, b: Int): Double {
        val (ax, ay) = f.px(a)
        val (bx, by) = f.px(b)
        return hypot(ax - bx, ay - by)
    }

    /** Diameter of one iris in image pixels: mean of its horizontal and vertical boundary spans. */
    fun irisDiameterPx(f: FaceFrame, iris: IntRange): Double {
        val c = iris.first
        val horizontal = dist(f, c + 1, c + 3)
        val vertical = dist(f, c + 2, c + 4)
        return (horizontal + vertical) / 2.0
    }

    /** Mean iris diameter of both eyes in pixels, or null when the iris points are missing. */
    fun meanIrisDiameterPx(f: FaceFrame): Double? {
        if (!hasIris(f)) return null
        val d = (irisDiameterPx(f, RIGHT_IRIS) + irisDiameterPx(f, LEFT_IRIS)) / 2.0
        return d.takeIf { it > 0.0 }
    }

    /** Distance between the two iris centres in pixels (PD before scaling). */
    fun irisCentreDistancePx(f: FaceFrame): Double? =
        if (hasIris(f)) dist(f, RIGHT_IRIS.first, LEFT_IRIS.first) else null

    private fun ear(f: FaceFrame, p: IntArray): Double {
        val width = dist(f, p[0], p[1])
        if (width <= 0.0) return 0.0
        return (dist(f, p[2], p[3]) + dist(f, p[4], p[5])) / (2.0 * width)
    }

    /** Eye aspect ratio (right, left): about 0.25–0.35 open, below ~0.15 closed. */
    fun eyeAspectRatios(f: FaceFrame): Pair<Double, Double> = ear(f, RIGHT_EYE) to ear(f, LEFT_EYE)

    fun faceBox(f: FaceFrame): FaceBox {
        var l = 1f; var t = 1f; var r = 0f; var b = 0f
        for (p in f.landmarks.take(468)) {
            if (p.x < l) l = p.x
            if (p.x > r) r = p.x
            if (p.y < t) t = p.y
            if (p.y > b) b = p.y
        }
        return FaceBox(l, t, r, b)
    }

    // ------------------------------------------------------------------------------------------------
    // PROVISIONAL head pose. Track A is writing head pose in com.drishtisaathi.engine.face; once merged,
    // the lead swaps the body of [headPose] for Track A's function (one line). Only |yaw| and |roll| are used.
    // ------------------------------------------------------------------------------------------------

    /** Roll from the outer-eye-corner line in image pixels (0° = level). */
    fun provisionalRollDeg(f: FaceFrame): Double {
        val (rx, ry) = f.px(RIGHT_EYE_OUTER)
        val (lx, ly) = f.px(LEFT_EYE_OUTER)
        return Math.toDegrees(atan2(ly - ry, lx - rx))
    }

    /**
     * Yaw (rotation about the vertical axis) from MediaPipe's 4×4 facial transformation matrix:
     * atan2(r02, r22). Column-major storage puts r02 at [8] and r22 at [10]; if a build ever delivered
     * row-major data only the sign flips, which the |yaw| gate ignores. Uniform scale cancels out.
     */
    fun yawFromMatrixDeg(m: FloatArray): Double {
        require(m.size == 16) { "Expected a 4×4 matrix" }
        return Math.toDegrees(atan2(m[8].toDouble(), m[10].toDouble()))
    }

    /** Fallback yaw without a matrix: nose-tip position between the cheek contour points. */
    fun yawFromLandmarksDeg(f: FaceFrame): Double {
        val (rx, _) = f.px(RIGHT_CHEEK)
        val (lx, _) = f.px(LEFT_CHEEK)
        val (nx, _) = f.px(NOSE_TIP)
        val span = lx - rx
        if (span == 0.0) return 0.0
        val t = ((nx - rx) / span - 0.5) * 2.0   // −1 … 1
        return Math.toDegrees(asin(t.coerceIn(-1.0, 1.0)))
    }

    /** PROVISIONAL: swap for Track A's head pose when merged. */
    fun headPose(f: FaceFrame): HeadPose = HeadPose(
        yawDeg = f.transformMatrix?.takeIf { it.size == 16 }?.let(::yawFromMatrixDeg) ?: yawFromLandmarksDeg(f),
        rollDeg = provisionalRollDeg(f),
    )
}
