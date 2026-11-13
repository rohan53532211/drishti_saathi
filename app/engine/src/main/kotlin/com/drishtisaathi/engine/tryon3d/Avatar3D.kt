package com.drishtisaathi.engine.tryon3d

import com.drishtisaathi.engine.face.FaceFrame
import com.drishtisaathi.engine.face.Landmark
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** The three guided photos: straight, turned to the subject's left (yaw > 0), turned to the subject's right (yaw < 0). */
enum class AvatarStep { FRONT, LEFT, RIGHT }

/** Capture gates (degrees, ms). Yaw > 0 = the subject turned to their own left. */
data class AvatarCaptureConfig(
    val frontMaxYawDeg: Double = 5.0,
    val sideMinYawDeg: Double = 30.0,
    val sideMaxYawDeg: Double = 40.0,
    val maxPitchDeg: Double = 12.0,
    val holdMs: Long = 500,
)

/**
 * Auto-capture: a photo is taken once the pose for the current step has been held for [AvatarCaptureConfig.holdMs]
 * with the quality gates passing. Pure, so the gating is unit-tested.
 */
class AvatarCaptureGate(private val cfg: AvatarCaptureConfig = AvatarCaptureConfig()) {
    data class State(val poseOk: Boolean, val qualityOk: Boolean, val held: Double, val ready: Boolean)

    private var since = -1L

    fun poseOk(step: AvatarStep, yawDeg: Double, pitchDeg: Double): Boolean {
        if (abs(pitchDeg) > cfg.maxPitchDeg) return false
        return when (step) {
            AvatarStep.FRONT -> abs(yawDeg) < cfg.frontMaxYawDeg
            AvatarStep.LEFT -> yawDeg in cfg.sideMinYawDeg..cfg.sideMaxYawDeg
            AvatarStep.RIGHT -> -yawDeg in cfg.sideMinYawDeg..cfg.sideMaxYawDeg
        }
    }

    /** Target yaw of a step (degrees), for the on-screen indicator. */
    fun targetYaw(step: AvatarStep) = when (step) {
        AvatarStep.FRONT -> 0.0
        AvatarStep.LEFT -> (cfg.sideMinYawDeg + cfg.sideMaxYawDeg) / 2
        AvatarStep.RIGHT -> -(cfg.sideMinYawDeg + cfg.sideMaxYawDeg) / 2
    }

    fun reset() { since = -1 }

    /** [yawDeg]/[pitchDeg] null = no face. */
    fun update(step: AvatarStep, yawDeg: Double?, pitchDeg: Double?, qualityOk: Boolean, timeMs: Long): State {
        val pose = yawDeg != null && pitchDeg != null && poseOk(step, yawDeg, pitchDeg)
        if (!pose || !qualityOk) { since = -1; return State(pose, qualityOk, 0.0, false) }
        if (since < 0) since = timeMs
        val held = ((timeMs - since).toDouble() / cfg.holdMs).coerceIn(0.0, 1.0)
        return State(true, true, held, held >= 1.0)
    }

    companion object {
        /** Yaw/pitch (degrees) of a frame from its transformation matrix; null without a usable matrix. */
        fun angles(f: FaceFrame?): Pair<Double, Double>? {
            val r = FacePose.rotation(f?.transformMatrix) ?: return null
            val (yaw, pitch, _) = FacePose.angles(r)
            return yaw to pitch
        }
    }
}

/** One captured photo's data (no pixels): landmarks in that image, its transformation matrix and focal length. */
class AvatarView(
    val step: AvatarStep,
    val landmarks: List<Landmark>,
    val imageW: Int,
    val imageH: Int,
    val transformMatrix: FloatArray?,
    val focalPx: Double,
) {
    fun face() = FaceFrame(landmarks, imageW, imageH, transformMatrix, focalPx)
}

/** Packed ARGB pixels (Bitmap.getPixels layout). */
class Pixels(val w: Int, val h: Int, val argb: IntArray) {
    /** Bilinear sample; null outside the image. */
    fun sample(x: Double, y: Double): IntArray? {
        if (x < 0 || y < 0 || x > w - 1 || y > h - 1) return null
        val x0 = floor(x).toInt().coerceAtMost(w - 2).coerceAtLeast(0)
        val y0 = floor(y).toInt().coerceAtMost(h - 2).coerceAtLeast(0)
        val fx = x - x0; val fy = y - y0
        val out = IntArray(3)
        for (k in 0 until 3) {
            val sh = 16 - 8 * k
            fun c(xx: Int, yy: Int) = (argb[yy * w + xx] shr sh) and 255
            val top = c(x0, y0) * (1 - fx) + c(x0 + 1, y0) * fx
            val bot = c(x0, y0 + 1) * (1 - fx) + c(x0 + 1, y0 + 1) * fx
            out[k] = (top * (1 - fy) + bot * fy).toInt()
        }
        return out
    }
}

/** A built avatar in face space (cm, canonical axes): textured face + skin shell + hair cap + ears. */
class AvatarModel(
    val faceVertices: FloatArray,
    val faceUvs: FloatArray,
    val faceTriangles: IntArray,
    /** ARGB texture, [AvatarBuilder.TEX]². */
    val texture: IntArray,
    val shellVertices: FloatArray,
    val skinTriangles: IntArray,
    val hairTriangles: IntArray,
    val earVertices: FloatArray,
    val earTriangles: IntArray,
    val anchors: FaceAnchors,
    /** Which view textured each face triangle best (index into the views). */
    val bestView: IntArray,
)

/**
 * Simplified 3D face from three photos: the canonical face-mesh topology with the landmarks of all views brought into
 * the canonical face space (each view's pose from [FacePose.solve]) and averaged, the front view weighted most;
 * texture baked by projective texturing from the 3 photos, each texel taking the views that face it best, blended
 * smoothly at the seams; a smooth head shell, a hair cap and simple ears. A preview, not a scan.
 */
object AvatarBuilder {
    const val TEX = 512
    val FACE_OVAL = intArrayOf(
        10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377,
        152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109,
    )
    /** View weights for the geometry average. */
    const val FRONT_WEIGHT = 2.0
    const val SIDE_WEIGHT = 1.0
    /** Sharpness of the per-texel view choice (higher = closer to "best view only"). */
    const val FACING_POWER = 4.0
    /** Head length used for the back of the shell (cm, canonical scale). */
    const val HEAD_LENGTH_CM = 19.5

    private fun v(a: FloatArray, i: Int) = V3(a[3 * i].toDouble(), a[3 * i + 1].toDouble(), a[3 * i + 2].toDouble())
    private fun norm(p: V3) = p * (1 / max(p.length, 1e-12))
    private fun dot(a: V3, b: V3) = a.x * b.x + a.y * b.y + a.z * b.z

    /** Fixed UV layout: a cylinder around the head's vertical axis, the face in the middle. */
    fun uvLayout(canonical: FloatArray): FloatArray {
        val n = canonical.size / 3
        val uv = FloatArray(n * 2)
        for (i in 0 until n) {
            val p = v(canonical, i)
            val th = atan2(p.x, p.z + 4.0)
            uv[2 * i] = (0.5 + th / (2 * 1.55)).toFloat()
            uv[2 * i + 1] = ((8.9 - p.y) / 18.8).toFloat()
        }
        return uv
    }

    /** Camera position in face space for a pose (face → camera). */
    fun cameraInFace(pose: FloatArray): V3 {
        val t = V3(pose[12].toDouble(), pose[13].toDouble(), pose[14].toDouble())
        // −Rᵀ t
        return V3(
            -(pose[0] * t.x + pose[1] * t.y + pose[2] * t.z),
            -(pose[4] * t.x + pose[5] * t.y + pose[6] * t.z),
            -(pose[8] * t.x + pose[9] * t.y + pose[10] * t.z),
        )
    }

    /** One view's landmarks in face space: each pixel's ray, cut at the depth of the posed canonical vertex. */
    fun toFaceSpace(view: AvatarView, pose: FloatArray, canonical: FloatArray): FloatArray {
        val out = FloatArray(468 * 3)
        val cx = view.imageW / 2.0; val cy = view.imageH / 2.0; val f = view.focalPx
        for (i in 0 until 468) {
            val c = Mat4.apply(pose, canonical[3 * i].toDouble(), canonical[3 * i + 1].toDouble(), canonical[3 * i + 2].toDouble())
            val d = -c[2]
            val px = view.landmarks[i].x * view.imageW.toDouble(); val py = view.landmarks[i].y * view.imageH.toDouble()
            val p = V3((px - cx) * d / f - pose[12], -(py - cy) * d / f - pose[13], -d - pose[14])
            // Rᵀ (P − t)
            out[3 * i] = (pose[0] * p.x + pose[1] * p.y + pose[2] * p.z).toFloat()
            out[3 * i + 1] = (pose[4] * p.x + pose[5] * p.y + pose[6] * p.z).toFloat()
            out[3 * i + 2] = (pose[8] * p.x + pose[9] * p.y + pose[10] * p.z).toFloat()
        }
        return out
    }

    /** Per-triangle facing (cos of the angle to each camera) and the best view per triangle. */
    fun bestViews(vertices: FloatArray, triangles: IntArray, cams: List<V3>, weights: List<Double>): IntArray =
        IntArray(triangles.size / 3) { t ->
            val a = v(vertices, triangles[3 * t]); val b = v(vertices, triangles[3 * t + 1]); val c = v(vertices, triangles[3 * t + 2])
            val n = norm(cross(b - a, c - a))
            val m = (a + b + c) * (1.0 / 3)
            cams.indices.maxByOrNull { k -> weights[k] * max(0.0, dot(n, norm(cams[k] - m))).pow(FACING_POWER) } ?: 0
        }

    private fun cross(a: V3, b: V3) = V3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)

    /**
     * Builds the avatar. [views] and [photos] in the same order; [canonical] 468×3 canonical vertices (cm), [triangles]
     * the canonical topology (CCW from the front). Returns null when no view gives a pose.
     */
    fun build(views: List<AvatarView>, photos: List<Pixels>, canonical: FloatArray, triangles: IntArray, skinRgb: IntArray): AvatarModel? {
        require(views.size == photos.size && views.isNotEmpty())
        val poses = views.map { FacePose.solve(it.face(), it.focalPx) }
        val usable = views.indices.filter { poses[it] != null }
        if (usable.isEmpty() || usable.none { views[it].step == AvatarStep.FRONT }) return null
        val cams = usable.map { cameraInFace(poses[it]!!) }
        val baseW = usable.map { if (views[it].step == AvatarStep.FRONT) FRONT_WEIGHT else SIDE_WEIGHT }
        val canonN = GlbWriter.vertexNormals(canonical, triangles)

        // ---- geometry: per-vertex weighted average of the views in face space (each view weighted by how it faces the point)
        val per = usable.map { toFaceSpace(views[it], poses[it]!!, canonical) }
        val verts = FloatArray(468 * 3)
        for (i in 0 until 468) {
            val n = v(canonN, i); val p = v(canonical, i)
            var sw = 0.0; var acc = V3(0.0, 0.0, 0.0)
            for (k in per.indices) {
                val w = baseW[k] * max(0.05, dot(n, norm(cams[k] - p)))
                acc += v(per[k], i) * w; sw += w
            }
            val r = acc * (1 / sw)
            verts[3 * i] = r.x.toFloat(); verts[3 * i + 1] = r.y.toFloat(); verts[3 * i + 2] = r.z.toFloat()
        }
        val anchors = FaceAnchors.fromVertices(verts)
        val uvs = uvLayout(canonical)
        val camsAvatar = cams
        val best = bestViews(verts, triangles, camsAvatar, baseW)
        val tex = bake(verts, triangles, uvs, usable.map { views[it] }, usable.map { photos[it] }, camsAvatar, baseW, skinRgb)
        val shell = shell(verts, anchors)
        val ears = ears(anchors)
        return AvatarModel(verts, uvs, triangles.copyOf(), tex, shell.first, shell.second, shell.third, ears.first, ears.second, anchors, best)
    }

    /** Texture bake in UV space: every texel is a blend of the photos, weighted by how squarely each view sees it. */
    fun bake(
        verts: FloatArray, triangles: IntArray, uvs: FloatArray, views: List<AvatarView>, photos: List<Pixels>,
        cams: List<V3>, baseW: List<Double>, skinRgb: IntArray,
    ): IntArray {
        val skin = (0xFF shl 24) or (skinRgb[0] shl 16) or (skinRgb[1] shl 8) or skinRgb[2]
        val tex = IntArray(TEX * TEX) { skin }
        val normals = GlbWriter.vertexNormals(verts, triangles)
        for (t in 0 until triangles.size / 3) {
            val ia = triangles[3 * t]; val ib = triangles[3 * t + 1]; val ic = triangles[3 * t + 2]
            val ax = uvs[2 * ia] * TEX; val ay = uvs[2 * ia + 1] * TEX
            val bx = uvs[2 * ib] * TEX; val by = uvs[2 * ib + 1] * TEX
            val cx = uvs[2 * ic] * TEX; val cy = uvs[2 * ic + 1] * TEX
            val den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
            if (abs(den) < 1e-9) continue
            val x0 = floor(min(ax, min(bx, cx))).toInt().coerceIn(0, TEX - 1); val x1 = floor(max(ax, max(bx, cx))).toInt().coerceIn(0, TEX - 1)
            val y0 = floor(min(ay, min(by, cy))).toInt().coerceIn(0, TEX - 1); val y1 = floor(max(ay, max(by, cy))).toInt().coerceIn(0, TEX - 1)
            val pa = v(verts, ia); val pb = v(verts, ib); val pc = v(verts, ic)
            val na = v(normals, ia); val nb = v(normals, ib); val nc = v(normals, ic)
            for (y in y0..y1) for (x in x0..x1) {
                val px = x + 0.5f; val py = y + 0.5f
                val l0 = ((by - cy) * (px - cx) + (cx - bx) * (py - cy)) / den
                val l1 = ((cy - ay) * (px - cx) + (ax - cx) * (py - cy)) / den
                val l2 = 1 - l0 - l1
                if (l0 < -0.02f || l1 < -0.02f || l2 < -0.02f) continue
                val p = pa * l0.toDouble() + pb * l1.toDouble() + pc * l2.toDouble()
                val n = norm(na * l0.toDouble() + nb * l1.toDouble() + nc * l2.toDouble())
                var r = 0.0; var g = 0.0; var b = 0.0; var sw = 0.0
                for (k in views.indices) {
                    val w = baseW[k] * max(0.0, dot(n, norm(cams[k] - p))).pow(FACING_POWER)
                    if (w < 1e-4) continue
                    val lm = views[k].landmarks
                    val sx = (lm[ia].x * l0 + lm[ib].x * l1 + lm[ic].x * l2) * views[k].imageW.toDouble()
                    val sy = (lm[ia].y * l0 + lm[ib].y * l1 + lm[ic].y * l2) * views[k].imageH.toDouble()
                    val c = photos[k].sample(sx * photos[k].w / views[k].imageW, sy * photos[k].h / views[k].imageH) ?: continue
                    r += c[0] * w; g += c[1] * w; b += c[2] * w; sw += w
                }
                if (sw > 0) tex[y * TEX + x] = (0xFF shl 24) or ((r / sw).toInt() shl 16) or ((g / sw).toInt() shl 8) or (b / sw).toInt()
            }
        }
        return tex
    }

    /**
     * Smooth head shell stitched to the face outline: a strip to an ellipse just behind the outline, then a half
     * ellipsoid back to the head length. Triangles split into skin and hair (the top and back of the head).
     */
    fun shell(verts: FloatArray, a: FaceAnchors, rings: Int = 10): Triple<FloatArray, IntArray, IntArray> {
        val ov = FACE_OVAL
        var cx = 0.0; var cy = 0.0; var zc = 0.0
        ov.forEach { val p = v(verts, it); cx += p.x; cy += p.y; zc += p.z }
        cx /= ov.size; cy /= ov.size; zc /= ov.size
        val rx = ov.maxOf { abs(verts[3 * it] - cx) } * 1.03
        val ry = ov.maxOf { abs(verts[3 * it + 1] - cy) } * 1.05
        val z0 = zc - 0.5
        val back = a.nasion.z - HEAD_LENGTH_CM
        val rz = max(z0 - back, rx)
        val sp = ArrayList<Float>()
        fun add(x: Double, y: Double, z: Double): Int { sp += x.toFloat(); sp += y.toFloat(); sp += z.toFloat(); return sp.size / 3 - 1 }
        val k = ov.size
        val outline = IntArray(k) { val p = v(verts, ov[it]); add(p.x, p.y, p.z) }
        val phi = DoubleArray(k) { atan2(verts[3 * ov[it] + 1] - cy, verts[3 * ov[it]] - cx) }
        val ringIdx = Array(rings) { j ->
            val th = j * (PI / 2) / rings
            IntArray(k) { add(cx + rx * cos(th) * cos(phi[it]), cy + ry * cos(th) * sin(phi[it]), z0 - rz * sin(th)) }
        }
        val pole = add(cx, cy, z0 - rz)
        val pts = sp.toFloatArray()
        val centre = V3(cx, cy, z0 - rz * 0.4)
        val eyeY = (a.pupilR.y + a.pupilL.y) / 2
        val earZ = a.nasion.z - 9.5
        val skin = ArrayList<Int>(); val hair = ArrayList<Int>()
        fun tri(i: Int, j: Int, l: Int) {
            val p = v(pts, i); val q = v(pts, j); val r = v(pts, l)
            val n = cross(q - p, r - p)
            val m = (p + q + r) * (1.0 / 3)
            val out = if (dot(n, m - centre) >= 0) intArrayOf(i, j, l) else intArrayOf(i, l, j)
            val isHair = m.y > eyeY + 3.2 || (m.z < earZ - 1.0 && m.y > eyeY - 3.5)
            (if (isHair) hair else skin).addAll(out.toList())
        }
        val all = listOf(outline) + ringIdx.toList()
        for (r in 0 until all.size - 1) for (i in 0 until k) {
            val p0 = all[r][i]; val p1 = all[r][(i + 1) % k]; val p2 = all[r + 1][(i + 1) % k]; val p3 = all[r + 1][i]
            tri(p0, p1, p2); tri(p0, p2, p3)
        }
        val last = all.last()
        for (i in 0 until k) tri(last[i], last[(i + 1) % k], pole)
        return Triple(pts, skin.toIntArray(), hair.toIntArray())
    }

    /** Two simple ear placeholders (flattened ellipsoids) at the ear points. */
    fun ears(a: FaceAnchors, rings: Int = 8, segments: Int = 12): Pair<FloatArray, IntArray> {
        val p = ArrayList<Float>(); val idx = ArrayList<Int>()
        val halfW = a.earR.dist(a.earL) / 2
        val eyeY = (a.pupilR.y + a.pupilL.y) / 2
        for (s in listOf(-1.0, 1.0)) {
            val c = V3(s * (halfW + 0.25), eyeY - 2.3, a.nasion.z - 9.9)
            val r = V3(0.7, 3.0, 1.9)
            val base = p.size / 3
            for (i in 0..rings) {
                val th = PI * i / rings
                for (j in 0..segments) {
                    val ph = 2 * PI * j / segments
                    p += (c.x + r.x * sin(th) * cos(ph)).toFloat(); p += (c.y + r.y * cos(th)).toFloat(); p += (c.z + r.z * sin(th) * sin(ph)).toFloat()
                }
            }
            val w = segments + 1
            for (i in 0 until rings) for (j in 0 until segments) {
                val q = base + i * w + j; val b = q + w
                idx += q; idx += q + 1; idx += b
                idx += q + 1; idx += b + 1; idx += b
            }
        }
        return p.toFloatArray() to idx.toIntArray()
    }

    /** The avatar as one GLB: textured face, skin shell, hair cap, ears. Colours 0..255 sRGB. */
    fun glb(m: AvatarModel, textureJpeg: ByteArray, skinRgb: IntArray, hairRgb: IntArray): ByteArray {
        val g = GlbWriter()
        fun lin(rgb: IntArray) = floatArrayOf(srgbToLinear(rgb[0] / 255f), srgbToLinear(rgb[1] / 255f), srgbToLinear(rgb[2] / 255f), 1f)
        val img = g.addImage(textureJpeg, "image/jpeg")
        val faceMat = g.addMaterial(GlbWriter.Material("avatar_face", image = img, roughness = 0.75f))
        val skinMat = g.addMaterial(GlbWriter.Material("avatar_skin", baseColor = lin(skinRgb), roughness = 0.85f))
        val hairMat = g.addMaterial(GlbWriter.Material("avatar_hair", baseColor = lin(hairRgb), roughness = 0.95f))
        g.addMesh("avatar_face", m.faceVertices, m.faceTriangles, faceMat, GlbWriter.vertexNormals(m.faceVertices, m.faceTriangles), m.faceUvs)
        if (m.skinTriangles.isNotEmpty()) g.addMesh("avatar_shell", m.shellVertices, m.skinTriangles, skinMat, GlbWriter.vertexNormals(m.shellVertices, m.skinTriangles))
        if (m.hairTriangles.isNotEmpty()) g.addMesh("avatar_hair", m.shellVertices, m.hairTriangles, hairMat, GlbWriter.vertexNormals(m.shellVertices, m.hairTriangles))
        g.addMesh("avatar_ears", m.earVertices, m.earTriangles, skinMat, GlbWriter.vertexNormals(m.earVertices, m.earTriangles))
        return g.build()
    }

    fun srgbToLinear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055) / 1.055).pow(2.4).toFloat()

    /** Median colour of 5×5 patches around normalised points (skin on the cheeks, hair above the forehead). */
    fun medianColour(px: Pixels, points: List<Pair<Double, Double>>): IntArray {
        val s = ArrayList<IntArray>()
        for ((x, y) in points) {
            val cx = (x * px.w).toInt(); val cy = (y * px.h).toInt()
            for (dx in -2..2) for (dy in -2..2) {
                val xx = (cx + dx).coerceIn(0, px.w - 1); val yy = (cy + dy).coerceIn(0, px.h - 1)
                val c = px.argb[yy * px.w + xx]
                s += intArrayOf((c shr 16) and 255, (c shr 8) and 255, c and 255)
            }
        }
        if (s.isEmpty()) return intArrayOf(200, 160, 140)
        return IntArray(3) { k -> s.map { it[k] }.sorted()[s.size / 2] }
    }

    /** Skin sample points (cheeks, forehead) and hair points (above the forehead) of a front view. */
    fun skinPoints(lm: List<Landmark>) = intArrayOf(50, 280, 101, 330, 151, 205, 425).map { lm[it].x.toDouble() to lm[it].y.toDouble() }
    fun hairPoints(lm: List<Landmark>): List<Pair<Double, Double>> {
        val h = (lm[152].y - lm[10].y).toDouble()
        return listOf(10, 109, 338).map { lm[it].x.toDouble() to (lm[it].y - 0.12 * h).coerceAtLeast(0.0) }
    }
}
