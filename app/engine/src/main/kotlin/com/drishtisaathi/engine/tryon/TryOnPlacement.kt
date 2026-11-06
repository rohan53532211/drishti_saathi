package com.drishtisaathi.engine.tryon

import com.drishtisaathi.engine.Frame
import com.drishtisaathi.engine.FrameOverlay
import com.drishtisaathi.engine.config.TryOnConfig
import com.drishtisaathi.engine.face.FaceFrame
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** A point in normalised image coordinates (0..1 of the camera image, same space as the landmarks). */
data class NPoint(val x: Double, val y: Double)

enum class TryOnScale {
    /** Real frame width (mm) against the measured face width (mm) and its landmarks. */
    FACE_WIDTH,
    /** Real frame width (mm) against the PD (mm) and the pupil distance in pixels. */
    PD,
    /** No mm known for the frame: the PNG's lens centres are put on the pupils. */
    LENS_CENTRES,
}

/**
 * Where to draw a frame PNG over one camera image. The four corners are the PNG's corners in normalised
 * image coordinates; a view maps them through its own image→view mapping and draws the PNG with the
 * projective transform PNG rect → mapped corners (android.graphics.Matrix.setPolyToPoly with 4 points).
 */
data class TryOnPose(
    val topLeft: NPoint,
    val topRight: NPoint,
    val bottomRight: NPoint,
    val bottomLeft: NPoint,
    /** Where the PNG's left/right lens centres land (useful for debugging and tests). */
    val leftLens: NPoint,
    val rightLens: NPoint,
    val rollDeg: Double,
    val yawDeg: Double,
    val pitchDeg: Double,
    /** Camera-image pixels per mm at the eyes. */
    val pxPerMm: Double,
    val scaleSource: TryOnScale,
) {
    val corners: List<NPoint> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)
}

/**
 * Pure placement math for the live try-on (Addition 8). All work is done in camera-image pixels so the
 * image aspect ratio is respected; the result is returned normalised.
 *
 * Model: the frame front is a flat plate centred between the pupils (raised by [TryOnConfig.lensCentreAbovePupilMm],
 * pushed forward by [TryOnConfig.frontOffsetMm]). It is turned by the head's yaw and pitch (from the MediaPipe
 * facial transformation matrix: canonical face space → camera space, x right, y up, z towards the camera),
 * projected with weak perspective (focal length f in px; a point nearer the camera by z px is scaled f/(f−z)),
 * then rolled in the image by the angle of the line between the pupils, which is observed directly.
 */
object TryOnPlacement {
    const val LEFT_IRIS = 468
    const val RIGHT_IRIS = 473

    /** Real frame front width from the printed size: 2·A + DBL + both end pieces. */
    fun frontWidthMm(frame: Frame, endpieceMmEachSide: Double): Double =
        2 * frame.lensWidthMm + frame.bridgeMm + 2 * endpieceMmEachSide

    /**
     * @param frontWidthMm real total frame width in mm, or null to fit the PNG's lens centres to the pupils.
     * @param faceWidthMm measured face width in mm (matching the landmarks in [TryOnConfig]); preferred scale source.
     * @param pdMm PD in mm, used when no face width is given (else [TryOnConfig.defaultPdMm]).
     * @return null when there is no usable face.
     */
    fun place(
        face: FaceFrame?,
        overlay: FrameOverlay,
        frontWidthMm: Double?,
        faceWidthMm: Double? = null,
        pdMm: Double? = null,
        cfg: TryOnConfig = TryOnConfig(),
    ): TryOnPose? {
        if (face == null || face.landmarks.size <= RIGHT_IRIS || face.imageWidthPx <= 0 || face.imageHeightPx <= 0) return null
        var a = face.px(LEFT_IRIS)
        var b = face.px(RIGHT_IRIS)
        if (b.first < a.first) a = b.also { b = a } // a = pupil on the image's left
        val ipdPx = hypot(b.first - a.first, b.second - a.second)
        if (ipdPx < 1.0) return null

        // ---- angles ----
        val roll = atan2(b.second - a.second, b.first - a.first) // image space, y down: positive = clockwise on screen
        var yaw = 0.0
        var pitch = 0.0
        val m = face.transformMatrix
        if (cfg.useTransformMatrix && m != null && m.size == 16) {
            val (y, p) = yawPitch(m)
            yaw = y.coerceIn(-rad(cfg.maxYawDeg), rad(cfg.maxYawDeg))
            pitch = p.coerceIn(-rad(cfg.maxPitchDeg), rad(cfg.maxPitchDeg))
        }
        val cosYaw = max(cos(yaw), 0.5)

        // ---- scale: camera px per mm at the eyes (head-on equivalent) ----
        val ipdFrontal = ipdPx / cosYaw
        val fw = faceWidthMm?.takeIf { it > 0 && face.landmarks.size > max(cfg.faceWidthLandmarkLeft, cfg.faceWidthLandmarkRight) }
        val pxPerMm: Double
        var source: TryOnScale
        if (fw != null) {
            val l = face.px(cfg.faceWidthLandmarkLeft)
            val r = face.px(cfg.faceWidthLandmarkRight)
            pxPerMm = hypot(r.first - l.first, r.second - l.second) / cosYaw / fw
            source = TryOnScale.FACE_WIDTH
        } else {
            pxPerMm = ipdFrontal / (pdMm?.takeIf { it > 0 } ?: cfg.defaultPdMm)
            source = TryOnScale.PD
        }
        // PNG px → camera px (head-on, at the eyes)
        val s = if (frontWidthMm != null && frontWidthMm > 0) {
            frontWidthMm * pxPerMm / overlay.frontWidthPx
        } else {
            source = TryOnScale.LENS_CENTRES
            ipdFrontal / overlay.lensCentreDistancePx
        } * cfg.widthScale

        // ---- model: PNG point → 3D point (camera px units, x right, y up, z towards camera), origin between the pupils ----
        val cu = (overlay.leftLensCenterPx[0] + overlay.rightLensCenterPx[0]) / 2
        val cv = (overlay.leftLensCenterPx[1] + overlay.rightLensCenterPx[1]) / 2
        val lift = cfg.lensCentreAbovePupilMm * pxPerMm
        val zFront = cfg.frontOffsetMm * pxPerMm
        val f = face.focalLengthPx?.takeIf { it > 0 }
            ?: (cfg.defaultFocalLengthFraction * max(face.imageWidthPx, face.imageHeightPx))
        val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        val cr = cos(roll); val sr = sin(roll)
        val mx = (a.first + b.first) / 2
        val my = (a.second + b.second) / 2

        fun project(u: Double, v: Double): NPoint {
            val x0 = (u - cu) * s
            val y0 = -(v - cv) * s + lift
            val z0 = zFront
            // R = Ry(yaw)·Rx(pitch)
            val y1 = cp * y0 - sp * z0
            val z1 = sp * y0 + cp * z0
            val x2 = cy * x0 + sy * z1
            val z2 = -sy * x0 + cy * z1
            val k = if (cfg.perspective) f / max(f - z2, 0.5 * f) else 1.0
            // image space (y down), then roll about the pupil midpoint
            val ix = k * x2
            val iy = -k * y1
            val rx = cr * ix - sr * iy
            val ry = sr * ix + cr * iy
            return NPoint((mx + rx) / face.imageWidthPx, (my + ry) / face.imageHeightPx)
        }

        val w = overlay.widthPx.toDouble()
        val h = overlay.heightPx.toDouble()
        return TryOnPose(
            topLeft = project(0.0, 0.0), topRight = project(w, 0.0),
            bottomRight = project(w, h), bottomLeft = project(0.0, h),
            leftLens = project(overlay.leftLensCenterPx[0], overlay.leftLensCenterPx[1]),
            rightLens = project(overlay.rightLensCenterPx[0], overlay.rightLensCenterPx[1]),
            rollDeg = deg(roll), yawDeg = deg(yaw), pitchDeg = deg(pitch),
            pxPerMm = pxPerMm, scaleSource = source,
        )
    }

    /**
     * Yaw and pitch (radians) from a 4×4 column-major rigid transform, decomposed as R = Rz(roll)·Ry(yaw)·Rx(pitch).
     * Positive yaw turns the face's +z (towards the camera) towards image +x; positive pitch turns it downwards (chin down).
     */
    fun yawPitch(m: FloatArray): Pair<Double, Double> {
        fun r(row: Int, col: Int) = m[col * 4 + row].toDouble()
        // normalise the rotation's scale (MediaPipe's matrix is rigid but be safe)
        val sx = hypot(hypot(r(0, 0), r(1, 0)), r(2, 0)).takeIf { it > 1e-9 } ?: 1.0
        val sz = hypot(hypot(r(0, 2), r(1, 2)), r(2, 2)).takeIf { it > 1e-9 } ?: 1.0
        val syP = hypot(hypot(r(0, 1), r(1, 1)), r(2, 1)).takeIf { it > 1e-9 } ?: 1.0
        val yaw = asin((-r(2, 0) / sx).coerceIn(-1.0, 1.0))
        val pitch = atan2(r(2, 1) / syP, r(2, 2) / sz)
        return yaw to pitch
    }

    /** Builds a column-major 4×4 rotation R = Rz(roll)·Ry(yaw)·Rx(pitch) (for tests and fakes). */
    fun rotationMatrix(yawDeg: Double, pitchDeg: Double, rollDeg: Double = 0.0): FloatArray {
        val (a, b, c) = Triple(rad(yawDeg), rad(pitchDeg), rad(rollDeg))
        val rz = arrayOf(doubleArrayOf(cos(c), -sin(c), 0.0), doubleArrayOf(sin(c), cos(c), 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        val ry = arrayOf(doubleArrayOf(cos(a), 0.0, sin(a)), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-sin(a), 0.0, cos(a)))
        val rx = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, cos(b), -sin(b)), doubleArrayOf(0.0, sin(b), cos(b)))
        fun mul(p: Array<DoubleArray>, q: Array<DoubleArray>) =
            Array(3) { i -> DoubleArray(3) { j -> (0 until 3).sumOf { k -> p[i][k] * q[k][j] } } }
        val r = mul(mul(rz, ry), rx)
        val out = FloatArray(16)
        for (row in 0 until 3) for (col in 0 until 3) out[col * 4 + row] = r[row][col].toFloat()
        out[15] = 1f
        return out
    }

    private fun rad(d: Double) = d * PI / 180
    private fun deg(r: Double) = r * 180 / PI
}

/**
 * Exponential smoothing of poses between camera frames so the overlay does not jitter.
 * Restarts when the frame jumps (new face, big move) or the face is lost.
 */
class TryOnSmoother(private val cfg: TryOnConfig = TryOnConfig()) {
    private var last: TryOnPose? = null

    fun reset() { last = null }

    fun update(pose: TryOnPose?): TryOnPose? {
        val prev = last
        if (pose == null) { last = null; return null }
        if (prev == null || prev.scaleSource != pose.scaleSource || jump(prev, pose)) { last = pose; return pose }
        val k = cfg.smoothingAlpha
        fun mix(p: NPoint, q: NPoint) = NPoint(p.x + k * (q.x - p.x), p.y + k * (q.y - p.y))
        fun mixD(p: Double, q: Double) = p + k * (q - p)
        val out = TryOnPose(
            mix(prev.topLeft, pose.topLeft), mix(prev.topRight, pose.topRight),
            mix(prev.bottomRight, pose.bottomRight), mix(prev.bottomLeft, pose.bottomLeft),
            mix(prev.leftLens, pose.leftLens), mix(prev.rightLens, pose.rightLens),
            mixD(prev.rollDeg, pose.rollDeg), mixD(prev.yawDeg, pose.yawDeg), mixD(prev.pitchDeg, pose.pitchDeg),
            mixD(prev.pxPerMm, pose.pxPerMm), pose.scaleSource,
        )
        last = out
        return out
    }

    private fun jump(p: TryOnPose, q: TryOnPose): Boolean {
        val width = hypot(q.topRight.x - q.topLeft.x, q.topRight.y - q.topLeft.y)
        val move = p.corners.zip(q.corners).maxOf { (u, v) -> hypot(u.x - v.x, u.y - v.y) }
        return width <= 0 || move > cfg.smoothingResetFraction * width || abs(p.rollDeg - q.rollDeg) > 30
    }
}
