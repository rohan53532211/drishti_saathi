package com.drishtisaathi.engine.tryon3d

import com.drishtisaathi.engine.config.TryOnConfig
import com.drishtisaathi.engine.face.FaceFrame
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Glasses in MediaPipe's canonical face space (the method of MediaPipe's face_effect / face geometry sample):
 * the glasses are placed ONCE relative to the canonical face (cm; +x the subject's left, +y up, +z out of the face),
 * and each camera frame only moves that whole space with the face pose. The live try-on and the avatar both use
 * [GlassesFit.place], so the glasses sit the same way on both.
 */
data class V3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = V3(x * k, y * k, z * k)
    val length: Double get() = sqrt(x * x + y * y + z * z)
    fun dist(o: V3) = (this - o).length
}

/** The face points the placement needs, in face space (cm). */
data class FaceAnchors(val nasion: V3, val pupilR: V3, val pupilL: V3, val earR: V3, val earL: V3) {
    /** Pupil distance in face-space units ×10 (mm for the canonical face). */
    val pdMm: Double get() = pupilR.dist(pupilL) * 10

    companion object {
        const val NASION = 168
        const val EAR_R = 234
        const val EAR_L = 454
        /** Eye contour points whose mean is the pupil (the canonical mesh has no iris points). */
        val EYE_R = intArrayOf(33, 133, 159, 145, 160, 144, 158, 153)
        val EYE_L = intArrayOf(263, 362, 386, 374, 387, 373, 385, 380)

        /** MediaPipe canonical_face_model.obj values (cm). */
        val CANONICAL = FaceAnchors(
            nasion = V3(0.0, 3.271027, 5.236015),
            pupilR = V3(-3.18079, 2.634187, 3.699112),
            pupilL = V3(3.18079, 2.634187, 3.699112),
            earR = V3(-7.664182, 0.673132, -2.435867),
            earL = V3(7.664182, 0.673132, -2.435867),
        )

        /** Anchors of any mesh with the canonical topology, from its flat xyz vertex array (468 × 3). */
        fun fromVertices(v: FloatArray): FaceAnchors {
            fun p(i: Int) = V3(v[i * 3].toDouble(), v[i * 3 + 1].toDouble(), v[i * 3 + 2].toDouble())
            fun mean(ids: IntArray) = ids.map { p(it) }.reduce { a, b -> a + b } * (1.0 / ids.size)
            return FaceAnchors(p(NASION), mean(EYE_R), mean(EYE_L), p(EAR_R), p(EAR_L))
        }
    }
}

/** Placement settings (rules.json "tryOn"); distances are real mm. */
data class GlassesFitConfig(
    /** Pupils sit at this fraction of the lens height, measured from the lens bottom. */
    val pupilHeightRatio: Double = 0.55,
    /** Moves the whole frame up (+) or down (−). */
    val verticalOffsetMm: Double = 0.0,
    /** The frame's back surface at the bridge sits this far in front of the nasion. */
    val bridgeForwardMm: Double = 11.0,
    /** Ear rest point: this far behind the nasion … */
    val earBehindNasionMm: Double = 95.0,
    /** … and this far below the pupils (the top of the ear root is a little below eye level). */
    val earBelowPupilMm: Double = 5.0,
    /** Temple length can stretch/shrink this much to reach the ear. */
    val minTempleStretch: Double = 0.85,
    val maxTempleStretch: Double = 1.3,
    val maxTempleSwingDeg: Double = 25.0,
) {
    companion object {
        fun from(c: TryOnConfig) = GlassesFitConfig(
            pupilHeightRatio = c.pupilHeightRatio, verticalOffsetMm = c.verticalOffsetMm,
            bridgeForwardMm = c.bridgeForwardMm, earBehindNasionMm = c.earBehindNasionMm, earBelowPupilMm = c.earBelowPupilMm,
        )
    }
}

/** The glasses in face space: [model] maps frame mm → face space; temples are node transforms (hinge origin). */
class GlassesPlacement(
    val model: FloatArray,
    val templeR: FloatArray,
    val templeL: FloatArray,
    /** Face-space size of the glasses relative to true scale: canonical PD / measured PD. */
    val scale: Double,
    /** Lens centres R, L and ear rest points R, L in face space (for the debug overlay and tests). */
    val lensCentres: List<V3>,
    val earR: V3,
    val earL: V3,
    /** Where the pupils should be on the frame (face space). */
    val pupilTarget: V3,
    val templeStretch: Double,
)

object GlassesFit {
    /** Fixed ear bend of the generated temples (tools/frames_to_glb.py EAR_BEND_DEG). */
    const val EAR_BEND_DEG = 28.0
    const val DEFAULT_FRONT_DEPTH_MM = 4.0

    /** Straight part of the temple (mm) from its tip in hinge coordinates. */
    fun templeStraightMm(frame: Frame3D): Double {
        val e = frame.anchors.templeEndLocalMm
        val drop = -e.getOrElse(1) { 0.0 }
        if (drop <= 0.1) return e.getOrElse(2) { 105.0 }
        val bend = drop / sin(EAR_BEND_DEG * PI / 180)
        return e[2] - bend * kotlin.math.cos(EAR_BEND_DEG * PI / 180)
    }

    /** Frame-model point that must land on the pupils' midpoint: bridge centre x, pupil height on the lens, back surface. */
    fun pupilPivotMm(frame: Frame3D, cfg: GlassesFitConfig): V3 {
        val centres = frame.anchors.lensCentresMm.values.filter { it.size >= 2 }
        val lensCy = if (centres.isNotEmpty()) centres.map { it[1] }.average() else frame.anchors.noseRestMm[1] + 6.0
        val b = if (frame.sizes.b > 0) frame.sizes.b.toDouble() else 0.8 * frame.sizes.a
        val back = frame.anchors.hingesMm.values.firstOrNull()?.getOrNull(2) ?: DEFAULT_FRONT_DEPTH_MM
        return V3(0.0, lensCy - b / 2 + cfg.pupilHeightRatio * b, back)
    }

    /**
     * Places [frame] on the face given by [anchors] (canonical by default). [pdMm] is the customer's measured PD; the
     * glasses are scaled by anchors-PD / measured PD so they keep their true size relative to the real face.
     */
    fun place(frame: Frame3D, pdMm: Double?, anchors: FaceAnchors = FaceAnchors.CANONICAL, cfg: GlassesFitConfig = GlassesFitConfig()): GlassesPlacement {
        val k = if (pdMm != null && pdMm in 40.0..85.0) anchors.pdMm / pdMm else 1.0
        val u = 0.1 * k                          // real mm → face-space units
        val pupils = (anchors.pupilR + anchors.pupilL) * 0.5
        val target = V3(anchors.nasion.x, pupils.y + u * cfg.verticalOffsetMm, anchors.nasion.z + u * cfg.bridgeForwardMm)
        val pivot = pupilPivotMm(frame, cfg)
        // frame axes (+X wearer's right, +Z into the face) → face axes (+x subject's left, +z out): a 180° turn about Y
        val model = Mat4.mul(Mat4.translate(target.x, target.y, target.z), Mat4.scale(u), Mat4.rotY(PI), Mat4.translate(-pivot.x, -pivot.y, -pivot.z))
        fun toModel(p: V3): V3 {
            val q = (p - target) * (1.0 / u)
            return V3(-q.x + pivot.x, q.y + pivot.y, -q.z + pivot.z)
        }
        fun toFace(p: V3): V3 = Mat4.apply(model, p.x, p.y, p.z).let { V3(it[0], it[1], it[2]) }
        val earY = pupils.y - u * cfg.earBelowPupilMm
        val earZ = anchors.nasion.z - u * cfg.earBehindNasionMm
        val earR = V3(anchors.earR.x, earY, earZ)
        val earL = V3(anchors.earL.x, earY, earZ)
        val straight = templeStraightMm(frame)
        var stretch = 1.0
        fun temple(name: String, sign: Double, ear: V3): FloatArray {
            val h = frame.anchors.hingesMm[name]?.let { V3(it[0], it[1], it[2]) }
                ?: V3(sign * frame.hingeHalfWidthMm, pivot.y, pivot.z)
            val d = toModel(ear) - h
            val maxSwing = cfg.maxTempleSwingDeg * PI / 180
            val swing = atan2(d.x, d.z).coerceIn(-maxSwing, maxSwing)
            val pitch = -asin((d.y / max(d.length, 1e-6)).coerceIn(-0.5, 0.5))
            stretch = (d.length / straight).coerceIn(cfg.minTempleStretch, cfg.maxTempleStretch)
            return Mat4.mul(Mat4.translate(h.x, h.y, h.z), Mat4.rotY(swing), Mat4.rotX(pitch), Mat4.scale(1.0, 1.0, stretch))
        }
        val tr = temple("temple_R", 1.0, earR)
        val tl = temple("temple_L", -1.0, earL)
        val lens = listOf("lens_R", "lens_L").mapNotNull { n -> frame.anchors.lensCentresMm[n]?.let { toFace(V3(it[0], it[1], pivot.z)) } }
        return GlassesPlacement(model, tr, tl, k, lens, earR, earL, toFace(pivot), stretch)
    }

    /** Head width (mm) for the fit text: the face's 234–454 width at true scale. */
    fun headWidthMm(anchors: FaceAnchors, pdMm: Double?): Double {
        val w = anchors.earR.dist(anchors.earL) * 10
        return if (pdMm != null && pdMm in 40.0..85.0) w * pdMm / anchors.pdMm else w
    }
}

/**
 * Real camera intrinsics for the analysed (upright, un-mirrored) image and how that image fills the view
 * (FILL_CENTER, like the preview). Camera space is OpenGL's: x right, y up, looking down −z, units cm.
 */
data class ViewIntrinsics(val focalPx: Double, val imageW: Int, val imageH: Int, val viewW: Double, val viewH: Double) {
    val scale: Double = max(viewW / imageW, viewH / imageH)
    val offsetX: Double = (viewW - imageW * scale) / 2
    val offsetY: Double = (viewH - imageH * scale) / 2
    val cx: Double get() = imageW / 2.0
    val cy: Double get() = imageH / 2.0

    /** Column-major OpenGL projection that maps camera space straight onto view pixels (un-mirrored). */
    fun projection(near: Double = 1.0, far: Double = 2000.0): DoubleArray {
        val m = DoubleArray(16)
        val f = focalPx * scale
        m[0] = 2 * f / viewW
        m[5] = 2 * f / viewH
        m[8] = -((2 * (scale * cx + offsetX)) / viewW - 1)
        m[9] = -(1 - (2 * (scale * cy + offsetY)) / viewH)
        m[10] = -(far + near) / (far - near)
        m[11] = -1.0
        m[14] = -2 * far * near / (far - near)
        return m
    }

    /** Camera-space point → view pixels; [mirrored] = as seen on the mirrored front preview. */
    fun project(p: V3, mirrored: Boolean): Pair<Double, Double> {
        val d = -p.z
        val xi = cx + focalPx * p.x / d
        val yi = cy - focalPx * p.y / d
        val u = xi * scale + offsetX
        return (if (mirrored) viewW - u else u) to yi * scale + offsetY
    }

    /** Normalised image point → view pixels, same mapping as the preview (camera.PreviewTransform). */
    fun imageToView(xNorm: Double, yNorm: Double, mirrored: Boolean): Pair<Double, Double> {
        val x = if (mirrored) 1 - xNorm else xNorm
        return x * imageW * scale + offsetX to yNorm * imageH * scale + offsetY
    }
}

/** Face pose: face space → camera space (cm). */
object FacePose {
    /**
     * Rotation part of MediaPipe's facial transformation matrix as a column-major 4×4, orthonormalised. Accepts the
     * matrix in either layout (the translation is large, so it shows which one it is). Null when unusable.
     */
    fun rotation(m: FloatArray?): FloatArray? {
        if (m == null || m.size != 16) return null
        val colMajor = abs(m[12]) + abs(m[13]) + abs(m[14]) >= abs(m[3]) + abs(m[7]) + abs(m[11])
        fun r(row: Int, col: Int) = (if (colMajor) m[col * 4 + row] else m[row * 4 + col]).toDouble()
        var x = V3(r(0, 0), r(1, 0), r(2, 0))
        var y = V3(r(0, 1), r(1, 1), r(2, 1))
        if (x.length < 1e-6 || y.length < 1e-6) return null
        x = x * (1 / x.length)
        y = y - x * (x.x * y.x + x.y * y.y + x.z * y.z)
        y = y * (1 / y.length)
        val z = V3(x.y * y.z - x.z * y.y, x.z * y.x - x.x * y.z, x.x * y.y - x.y * y.x)
        return Mat4.identity().also {
            it[0] = x.x.toFloat(); it[1] = x.y.toFloat(); it[2] = x.z.toFloat()
            it[4] = y.x.toFloat(); it[5] = y.y.toFloat(); it[6] = y.z.toFloat()
            it[8] = z.x.toFloat(); it[9] = z.y.toFloat(); it[10] = z.z.toFloat()
        }
    }

    /**
     * Face space → camera space for one frame: the rotation from the transformation matrix, and the translation
     * solved with the REAL focal length so the anchors' nasion lands exactly on landmark 168 and the anchor pupils
     * span the observed iris distance (depth). This pins the glasses to the observed face whatever field of view
     * MediaPipe assumed internally.
     */
    fun solve(face: FaceFrame, focalPx: Double, anchors: FaceAnchors = FaceAnchors.CANONICAL): FloatArray? {
        if (face.landmarks.size < 478 || face.imageWidthPx <= 0) return null
        val rot = rotation(face.transformMatrix) ?: Mat4.identity()
        fun img(i: Int) = face.landmarks[i].x * face.imageWidthPx.toDouble() to face.landmarks[i].y * face.imageHeightPx.toDouble()
        var a = img(468)
        var b = img(473)
        if (b.first < a.first) a = b.also { b = a }             // a = image-left = the subject's right eye
        fun r(p: V3) = Mat4.apply(rot, p.x, p.y, p.z).let { V3(it[0], it[1], it[2]) }
        val qR = r(anchors.pupilR)
        val qL = r(anchors.pupilL)
        val du = hypot(b.first - a.first, b.second - a.second)
        val dq = hypot(qL.x - qR.x, qL.y - qR.y)
        if (du < 2 || dq < 1e-3) return null
        val cx = face.imageWidthPx / 2.0
        val cy = face.imageHeightPx / 2.0
        val qN = r(anchors.nasion)
        val n = img(FaceAnchors.NASION)
        // first guess: weak perspective from the pupil spread; then a few exact-perspective corrections of the depth
        var tz = -(focalPx * dq / du) - (qR.z + qL.z) / 2
        var tx = 0.0
        var ty = 0.0
        repeat(4) {
            val dN = -(qN.z + tz)
            tx = (n.first - cx) * dN / focalPx - qN.x
            ty = -(n.second - cy) * dN / focalPx - qN.y
            fun pr(q: V3): Pair<Double, Double> { val d = -(q.z + tz); return cx + focalPx * (q.x + tx) / d to cy - focalPx * (q.y + ty) / d }
            val pa = pr(qR); val pb = pr(qL)
            val spread = hypot(pb.first - pa.first, pb.second - pa.second)
            if (spread < 1e-6) return null
            val depth = -(tz + (qR.z + qL.z) / 2)
            tz = -(depth * spread / du) - (qR.z + qL.z) / 2
        }
        val dN = -(qN.z + tz)
        tx = (n.first - cx) * dN / focalPx - qN.x
        ty = -(n.second - cy) * dN / focalPx - qN.y
        return Mat4.mul(Mat4.translate(tx, ty, tz), rot)
    }

    /** Yaw, pitch, roll (degrees) of a pose for display; yaw > 0 = the face turned towards the image right. */
    fun angles(pose: FloatArray): Triple<Double, Double, Double> {
        val yaw = asin(pose[8].toDouble().coerceIn(-1.0, 1.0))
        val pitch = atan2(-pose[9].toDouble(), pose[10].toDouble())
        val roll = atan2(-pose[4].toDouble(), pose[5].toDouble())
        return Triple(yaw * 180 / PI, pitch * 180 / PI, roll * 180 / PI)
    }
}

/** One-euro smoothing of a rigid pose: translation per axis, rotation as a quaternion (sign kept continuous). */
class PoseMatrixSmoother(minCutoffT: Double = 1.0, betaT: Double = 0.05, minCutoffR: Double = 1.0, betaR: Double = 0.4) {
    private val t = List(3) { OneEuroFilter(minCutoffT, betaT) }
    private val q = List(4) { OneEuroFilter(minCutoffR, betaR) }
    private var last: DoubleArray? = null
    private var lastMs = Long.MIN_VALUE

    fun reset() { t.forEach { it.reset() }; q.forEach { it.reset() }; last = null }

    fun update(pose: FloatArray?, timeMs: Long): FloatArray? {
        if (pose == null) { reset(); return null }
        if (lastMs != Long.MIN_VALUE && timeMs - lastMs > 600) reset()
        lastMs = timeMs
        var quat = quaternion(pose)
        last?.let { p -> if (p.indices.sumOf { p[it] * quat[it] } < 0) quat = DoubleArray(4) { -quat[it] } }
        val fq = DoubleArray(4) { q[it].filter(quat[it], timeMs) }
        val n = sqrt(fq.sumOf { it * it }).takeIf { it > 1e-9 } ?: 1.0
        for (i in 0 until 4) fq[i] /= n
        last = fq
        val m = fromQuaternion(fq)
        m[12] = t[0].filter(pose[12].toDouble(), timeMs).toFloat()
        m[13] = t[1].filter(pose[13].toDouble(), timeMs).toFloat()
        m[14] = t[2].filter(pose[14].toDouble(), timeMs).toFloat()
        return m
    }

    companion object {
        /** (w, x, y, z) of the rotation part of a column-major matrix. */
        fun quaternion(m: FloatArray): DoubleArray {
            fun r(row: Int, col: Int) = m[col * 4 + row].toDouble()
            val tr = r(0, 0) + r(1, 1) + r(2, 2)
            return when {
                tr > 0 -> { val s = sqrt(tr + 1) * 2; doubleArrayOf(s / 4, (r(2, 1) - r(1, 2)) / s, (r(0, 2) - r(2, 0)) / s, (r(1, 0) - r(0, 1)) / s) }
                r(0, 0) > r(1, 1) && r(0, 0) > r(2, 2) -> { val s = sqrt(1 + r(0, 0) - r(1, 1) - r(2, 2)) * 2; doubleArrayOf((r(2, 1) - r(1, 2)) / s, s / 4, (r(0, 1) + r(1, 0)) / s, (r(0, 2) + r(2, 0)) / s) }
                r(1, 1) > r(2, 2) -> { val s = sqrt(1 + r(1, 1) - r(0, 0) - r(2, 2)) * 2; doubleArrayOf((r(0, 2) - r(2, 0)) / s, (r(0, 1) + r(1, 0)) / s, s / 4, (r(1, 2) + r(2, 1)) / s) }
                else -> { val s = sqrt(1 + r(2, 2) - r(0, 0) - r(1, 1)) * 2; doubleArrayOf((r(1, 0) - r(0, 1)) / s, (r(0, 2) + r(2, 0)) / s, (r(1, 2) + r(2, 1)) / s, s / 4) }
            }
        }

        fun fromQuaternion(q: DoubleArray): FloatArray {
            val (w, x, y, z) = listOf(q[0], q[1], q[2], q[3])
            return Mat4.identity().also {
                it[0] = (1 - 2 * (y * y + z * z)).toFloat(); it[4] = (2 * (x * y - w * z)).toFloat(); it[8] = (2 * (x * z + w * y)).toFloat()
                it[1] = (2 * (x * y + w * z)).toFloat(); it[5] = (1 - 2 * (x * x + z * z)).toFloat(); it[9] = (2 * (y * z - w * x)).toFloat()
                it[2] = (2 * (x * z - w * y)).toFloat(); it[6] = (2 * (y * z + w * x)).toFloat(); it[10] = (1 - 2 * (x * x + y * y)).toFloat()
            }
        }
    }
}

/**
 * Invisible head for occlusion, in face space: the face mesh (pushed back a little so it never cuts the frame),
 * a head ellipsoid behind it and two ear blocks, so the far temple and the temple tips behind the ears are hidden.
 */
object HeadOccluder {
    fun glb(vertices: FloatArray, triangles: IntArray, anchors: FaceAnchors = FaceAnchors.fromVertices(vertices), faceInsetCm: Double = 0.3): ByteArray {
        val g = GlbWriter()
        val m = g.addMaterial(GlbWriter.Material("occluder", roughness = 1f))
        val face = vertices.copyOf().also { for (i in 2 until it.size step 3) it[i] -= faceInsetCm.toFloat() }
        g.addMesh("occluder_face", face, triangles, m, GlbWriter.vertexNormals(face, triangles))
        val halfW = anchors.earR.dist(anchors.earL) / 2
        val eyeY = (anchors.pupilR.y + anchors.pupilL.y) / 2
        val earZ = anchors.nasion.z - 9.5
        ellipsoid(g, "occluder_head", m, V3(0.0, eyeY - 1.0, earZ + 0.5), V3(halfW - 0.5, 10.5, 8.6))
        for ((name, s) in listOf("occluder_ear_R" to -1.0, "occluder_ear_L" to 1.0)) {
            ellipsoid(g, name, m, V3(s * (halfW + 0.2), eyeY - 2.3, earZ - 0.4), V3(1.2, 3.0, 2.0))
        }
        return g.build()
    }

    private fun ellipsoid(g: GlbWriter, name: String, mat: Int, c: V3, r: V3, rings: Int = 14, segments: Int = 20) {
        val p = ArrayList<Float>()
        val n = ArrayList<Float>()
        for (i in 0..rings) {
            val th = PI * i / rings
            for (s in 0..segments) {
                val ph = 2 * PI * s / segments
                val ux = sin(th) * kotlin.math.cos(ph); val uy = kotlin.math.cos(th); val uz = sin(th) * sin(ph)
                p += (c.x + r.x * ux).toFloat(); p += (c.y + r.y * uy).toFloat(); p += (c.z + r.z * uz).toFloat()
                n += ux.toFloat(); n += uy.toFloat(); n += uz.toFloat()
            }
        }
        val idx = ArrayList<Int>()
        val w = segments + 1
        for (i in 0 until rings) for (s in 0 until segments) {
            val a = i * w + s; val b = a + w
            idx += a; idx += a + 1; idx += b
            idx += a + 1; idx += b + 1; idx += b
        }
        g.addMesh(name, p.toFloatArray(), idx.toIntArray(), mat, n.toFloatArray())
    }
}
