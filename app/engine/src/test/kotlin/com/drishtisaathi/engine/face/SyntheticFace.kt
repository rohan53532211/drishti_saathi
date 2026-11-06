package com.drishtisaathi.engine.face

import com.drishtisaathi.engine.TestAssets
import kotlin.math.cos
import kotlin.math.sin

/**
 * Builds a 478-point [FaceFrame] with known geometry: a 3D face in mm (x right, y down, z towards the camera),
 * rotated by R = Rz(roll)·Ry(yaw)·Rx(pitch) and projected orthographically at [Spec.mmPerPx].
 * Landmark indices come from the shipped rules.json, so the test exercises the real config.
 * Unused landmarks sit at the face centre.
 */
object SyntheticFace {

    data class Spec(
        val pdMm: Double = 63.0,
        val faceWidthMm: Double = 140.0,
        /** 171 mm keeps nose depth / length = 0.45 and nose depth / width = 0.55 (the shipped pose ratios). */
        val faceLengthMm: Double = 171.0,
        val jawWidthMm: Double = 112.0,
        val foreheadWidthMm: Double = 115.0,
        val bridgeWidthMm: Double = 18.0,
        val irisDiameterMm: Double = 11.7,
        val noseDepthMm: Double = 77.0,
        val yawDeg: Double = 0.0,
        val pitchDeg: Double = 0.0,
        val rollDeg: Double = 0.0,
        val mmPerPx: Double = 0.25,
        val imageWidthPx: Int = 720,
        val imageHeightPx: Int = 960,
        /** Image position of the face origin (eye-line midpoint), px. */
        val originXPx: Double = 360.0,
        val originYPx: Double = 400.0,
        val focalLengthPx: Double? = null,
        val transformMatrix: FloatArray? = null,
        val mirrored: Boolean = true,
        /** Shrinks the left iris ring (eyelid occlusion / foreshortening), 1 = normal. */
        val leftIrisRingScale: Double = 1.0,
        val landmarkCount: Int = FaceFrame.LANDMARK_COUNT,
    )

    private data class P(val x: Double, val y: Double, val z: Double)

    fun frame(s: Spec = Spec()): FaceFrame {
        val lm = TestAssets.rules.faceGeometry.landmarks
        val pts = HashMap<Int, P>()
        val eyeZ = 20.0
        val r = s.irisDiameterMm / 2
        pts[lm.irisRightCentre] = P(-s.pdMm / 2, 0.0, eyeZ)
        pts[lm.irisLeftCentre] = P(s.pdMm / 2, 0.0, eyeZ)
        fun ring(ids: List<Int>, cx: Double, scale: Double) {
            val rr = r * scale
            pts[ids[0]] = P(cx + rr, 0.0, eyeZ); pts[ids[1]] = P(cx, -rr, eyeZ)
            pts[ids[2]] = P(cx - rr, 0.0, eyeZ); pts[ids[3]] = P(cx, rr, eyeZ)
        }
        ring(lm.irisRightRing, -s.pdMm / 2, 1.0)
        ring(lm.irisLeftRing, s.pdMm / 2, s.leftIrisRingScale)
        pts[lm.eyeOuterRight] = P(-s.pdMm / 2 - 15, 0.0, eyeZ - 5)
        pts[lm.eyeOuterLeft] = P(s.pdMm / 2 + 15, 0.0, eyeZ - 5)
        pts[lm.faceWidthRight] = P(-s.faceWidthMm / 2, 0.0, 0.0)
        pts[lm.faceWidthLeft] = P(s.faceWidthMm / 2, 0.0, 0.0)
        // Nose tip half way between forehead top and chin (noseTipNeutralFraction = 0.5), cheeks at z = 0.
        val c = 40.0
        pts[lm.faceTop] = P(0.0, c - s.faceLengthMm / 2, 0.0)
        pts[lm.chin] = P(0.0, c + s.faceLengthMm / 2, 0.0)
        pts[lm.noseTip] = P(0.0, c, s.noseDepthMm)
        pts[lm.jawRight] = P(-s.jawWidthMm / 2, 100.0, -10.0)
        pts[lm.jawLeft] = P(s.jawWidthMm / 2, 100.0, -10.0)
        pts[lm.foreheadRight] = P(-s.foreheadWidthMm / 2, -35.0, -5.0)
        pts[lm.foreheadLeft] = P(s.foreheadWidthMm / 2, -35.0, -5.0)
        pts[lm.bridgeRight] = P(-s.bridgeWidthMm / 2, 0.0, 15.0)
        pts[lm.bridgeLeft] = P(s.bridgeWidthMm / 2, 0.0, 15.0)

        val landmarks = List(s.landmarkCount) { i ->
            val p = rotate(pts[i] ?: P(0.0, 0.0, 0.0), s)
            val u = s.originXPx + p.x / s.mmPerPx
            val v = s.originYPx + p.y / s.mmPerPx
            Landmark((u / s.imageWidthPx).toFloat(), (v / s.imageHeightPx).toFloat(), (p.z / s.mmPerPx / s.imageWidthPx).toFloat())
        }
        return FaceFrame(landmarks, s.imageWidthPx, s.imageHeightPx, s.transformMatrix, s.focalLengthPx, s.mirrored)
    }

    private fun rotate(p: P, s: Spec): P {
        val (a, b, g) = Triple(Math.toRadians(s.pitchDeg), Math.toRadians(s.yawDeg), Math.toRadians(s.rollDeg))
        // Rx(pitch)
        val y1 = p.y * cos(a) - p.z * sin(a)
        val z1 = p.y * sin(a) + p.z * cos(a)
        // Ry(yaw)
        val x2 = p.x * cos(b) + z1 * sin(b)
        val z2 = -p.x * sin(b) + z1 * cos(b)
        // Rz(roll)
        val x3 = x2 * cos(g) - y1 * sin(g)
        val y3 = x2 * sin(g) + y1 * cos(g)
        return P(x3, y3, z2)
    }

    /** 4×4 column-major matrix R = Rz(roll)·Ry(yaw)·Rx(pitch), scaled by [scale], with a translation. */
    fun transformMatrix(yawDeg: Double, pitchDeg: Double, rollDeg: Double, scale: Double = 1.0): FloatArray {
        val (a, b, g) = Triple(Math.toRadians(pitchDeg), Math.toRadians(yawDeg), Math.toRadians(rollDeg))
        val rx = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, cos(a), -sin(a)), doubleArrayOf(0.0, sin(a), cos(a)))
        val ry = arrayOf(doubleArrayOf(cos(b), 0.0, sin(b)), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-sin(b), 0.0, cos(b)))
        val rz = arrayOf(doubleArrayOf(cos(g), -sin(g), 0.0), doubleArrayOf(sin(g), cos(g), 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        fun mul(p: Array<DoubleArray>, q: Array<DoubleArray>) =
            Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { k -> p[i][k] * q[k][j] } } }
        val r = mul(rz, mul(ry, rx))
        val m = FloatArray(16)
        for (col in 0..2) for (row in 0..2) m[col * 4 + row] = (r[row][col] * scale).toFloat()
        m[12] = 1.5f; m[13] = -2f; m[14] = -40f; m[15] = 1f
        return m
    }
}
