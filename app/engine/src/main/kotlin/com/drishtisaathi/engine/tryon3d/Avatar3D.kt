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

/**
 * Packed ARGB pixels (Bitmap.getPixels layout) of a crop of a photo: the crop's top-left is at ([offsetX], [offsetY])
 * in full-photo pixels and [scale] crop pixels per full-photo pixel.
 */
class Pixels(
    val w: Int, val h: Int, val argb: IntArray, val offsetX: Double = 0.0, val offsetY: Double = 0.0, val scale: Double = 1.0,
    /** Segmentation classes ([SegClass]) over the same crop, [clsW]×[clsH] (any resolution); null = no mask. */
    val cls: ByteArray? = null, val clsW: Int = 0, val clsH: Int = 0,
) {
    val hasClasses get() = cls != null && clsW > 0 && clsH > 0

    private fun gx(xFull: Double) = ((xFull - offsetX) * scale * clsW / w)
    private fun gy(yFull: Double) = ((yFull - offsetY) * scale * clsH / h)

    /** Class at a full-photo point; −1 outside the crop, or when there is no mask. */
    fun classAtFull(xFull: Double, yFull: Double): Int {
        val m = cls ?: return -1
        val x = floor(gx(xFull)).toInt(); val y = floor(gy(yFull)).toInt()
        if (x < 0 || y < 0 || x >= clsW || y >= clsH) return -1
        return m[y * clsW + x].toInt() and 0xFF
    }

    /**
     * Share of the 3×3 class pixels around a full-photo point that are in [bits] (0..1): 1 everywhere without a mask,
     * 0 outside the crop. Used as a blend weight, so view seams at class edges are feathered.
     */
    fun classFractionFull(xFull: Double, yFull: Double, bits: Int): Double {
        val m = cls ?: return 1.0
        val x = floor(gx(xFull)).toInt(); val y = floor(gy(yFull)).toInt()
        if (x < 0 || y < 0 || x >= clsW || y >= clsH) return 0.0
        var ok = 0; var all = 0
        for (oy in -1..1) for (ox in -1..1) {
            val xx = x + ox; val yy = y + oy
            if (xx < 0 || yy < 0 || xx >= clsW || yy >= clsH) continue
            all++
            if (SegClass.allowed(m[yy * clsW + xx].toInt() and 0xFF, bits)) ok++
        }
        return if (all == 0) 0.0 else ok.toDouble() / all
    }

    /**
     * The horizontal run of skin pixels through a full-photo point (searching ±3 class pixels for skin): its width in
     * full-photo pixels and the median colour along it. Null without a mask or when there is no skin there.
     */
    fun skinRunFull(xFull: Double, yFull: Double): Pair<Double, IntArray>? {
        val m = cls ?: return null
        val y = floor(gy(yFull)).toInt(); var x = floor(gx(xFull)).toInt()
        if (y < 0 || y >= clsH) return null
        fun skin(xx: Int) = xx in 0 until clsW && SegClass.allowed(m[y * clsW + xx].toInt() and 0xFF, SegClass.SKIN_BITS)
        if (!skin(x)) x = (-3..3).map { x + it }.firstOrNull { skin(it) } ?: return null
        var l = x; var r = x
        while (skin(l - 1)) l--
        while (skin(r + 1)) r++
        if (r - l < 2) return null
        val widthFull = (r - l + 1).toDouble() * w / clsW / scale
        val cy = (y + 0.5) * h / clsH
        val cols = (l..r).mapNotNull { xx -> sample((xx + 0.5) * w / clsW, cy) }
        if (cols.isEmpty()) return null
        return widthFull to IntArray(3) { k -> cols.map { it[k] }.sorted()[cols.size / 2] }
    }

    /** Bilinear sample in crop pixels into [out] (r, g, b 0..255); false outside the crop. */
    fun sampleInto(x: Double, y: Double, out: DoubleArray): Boolean {
        if (x < 0 || y < 0 || x > w - 1 || y > h - 1) return false
        val x0 = floor(x).toInt().coerceAtMost(w - 2).coerceAtLeast(0)
        val y0 = floor(y).toInt().coerceAtMost(h - 2).coerceAtLeast(0)
        val fx = x - x0; val fy = y - y0
        val c00 = argb[y0 * w + x0]; val c10 = argb[y0 * w + x0 + 1]; val c01 = argb[(y0 + 1) * w + x0]; val c11 = argb[(y0 + 1) * w + x0 + 1]
        for (k in 0 until 3) {
            val sh = 16 - 8 * k
            val top = ((c00 shr sh) and 255) * (1 - fx) + ((c10 shr sh) and 255) * fx
            val bot = ((c01 shr sh) and 255) * (1 - fx) + ((c11 shr sh) and 255) * fx
            out[k] = top * (1 - fy) + bot * fy
        }
        return true
    }

    /** Sample at a point given in FULL-photo pixels. */
    fun sampleFull(xFull: Double, yFull: Double, out: DoubleArray): Boolean = sampleInto((xFull - offsetX) * scale, (yFull - offsetY) * scale, out)

    fun sample(x: Double, y: Double): IntArray? {
        val o = DoubleArray(3)
        return if (sampleInto(x, y, o)) IntArray(3) { o[it].toInt() } else null
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
    /** Template head: seam duplicates → one vertex, so normals match across the UV seam. */
    val weld: IntArray? = null,
)

/**
 * Simplified 3D face from three photos: the canonical face-mesh topology with the landmarks of all views brought into
 * the canonical face space (each view's pose from [FacePose.solve]) and averaged, the front view weighted most;
 * texture baked by projective texturing from the 3 photos, each texel taking the views that face it best, blended
 * smoothly at the seams; a smooth head shell, a hair cap and simple ears. A preview, not a scan.
 */
object AvatarBuilder {
    /** Colour atlas size (texels per side). */
    const val TEX = 2048
    /** A view never contributes to points it sees at more than this angle (no smeared, back-facing texture or geometry). */
    const val MAX_VIEW_ANGLE_DEG = 75.0
    private val COS_CUT = cos(MAX_VIEW_ANGLE_DEG * PI / 180)
    /** Off the face (ears, neck, sides) a photo is used only up to this angle: near the silhouette it shows background. */
    const val MAX_VIEW_ANGLE_OFF_FACE_DEG = 55.0
    private val COS_CUT_OFF_FACE = cos(MAX_VIEW_ANGLE_OFF_FACE_DEG * PI / 180).toFloat()
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
        val f = fuse(views, photos, canonical, triangles) ?: return null
        val usable = f.usable; val verts = f.verts; val cams = f.cams; val baseW = f.baseW
        val canonN = GlbWriter.vertexNormals(canonical, triangles)
        val anchors = FaceAnchors.fromVertices(verts)
        val uvs = uvLayout(canonical)
        val best = bestViews(verts, triangles, cams, baseW)
        val gains = colourGains(verts, canonN, usable.map { views[it] }, usable.map { photos[it] }, cams)
        val tex = bake(verts, triangles, uvs, usable.map { views[it] }, usable.map { photos[it] }, cams, baseW, skinRgb, gains)
        val shell = shell(verts, anchors)
        val ears = ears(anchors)
        return AvatarModel(verts, uvs, triangles.copyOf(), tex, shell.first, shell.second, shell.third, ears.first, ears.second, anchors, best)
    }

    /** The views that gave a pose, their poses and cameras, and the averaged 468 landmarks in face space. */
    class Fused(val usable: List<Int>, val poses: List<FloatArray>, val cams: List<V3>, val baseW: List<Double>, val verts: FloatArray)

    fun fuse(views: List<AvatarView>, photos: List<Pixels>, canonical: FloatArray, triangles: IntArray): Fused? {
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
                val c = dot(n, norm(cams[k] - p))
                if (c < COS_CUT) continue
                val w = baseW[k] * c
                acc += v(per[k], i) * w; sw += w
            }
            val r = if (sw > 0) acc * (1 / sw) else v(per[usable.indexOfFirst { views[it].step == AvatarStep.FRONT }.coerceAtLeast(0)], i)
            verts[3 * i] = r.x.toFloat(); verts[3 * i + 1] = r.y.toFloat(); verts[3 * i + 2] = r.z.toFloat()
        }
        return Fused(usable, usable.map { poses[it]!! }, cams, baseW, verts)
    }

    /** Normalised image position of a face-space point seen with [pose] (face → camera) and focal [f] px. */
    fun project(pose: FloatArray, f: Double, w: Int, h: Int, x: Double, y: Double, z: Double, out: DoubleArray, o: Int = 0) {
        val q = Mat4.apply(pose, x, y, z)
        val d = max(-q[2], 1e-6)
        out[o] = (w / 2.0 + f * q[0] / d) / w
        out[o + 1] = (h / 2.0 - f * q[1] / d) / h
    }

    /**
     * Real head shape: the [template] (MakeHuman CC0 head) deformed so its face region sits on the averaged landmarks
     * ([HeadDeform]), proportional skull, ears and neck. Texture: every vertex is projected into each photo; on the face
     * each photo's own landmark positions correct that projection (their residuals ride on the same RBF, fading to
     * zero on the skull), so the face is as sharp as the face-only avatar. Ears and neck come from the photos that see
     * them; the scalp is [hairRgb]; anything no photo sees well is [skinRgb]. Nothing grey.
     */
    fun buildHead(
        views: List<AvatarView>, photos: List<Pixels>, canonical: FloatArray, triangles: IntArray, template: HeadTemplate,
        skinRgb: IntArray, hairRgb: IntArray,
    ): AvatarModel? = buildHeadDetailed(views, photos, canonical, triangles, template, skinRgb, hairRgb)?.model

    class HeadBuild(val model: AvatarModel, val bake: HeadBake.Result, val hairOffsets: FloatArray, val folds: Int, val neck: NeckFit.Size)

    /**
     * Real head shape: the [template] (MakeHuman CC0 head) deformed so its face region sits on the averaged landmarks
     * ([HeadDeform]); folds left by the deformation are relaxed; the neck takes its width / depth from the body-skin
     * silhouette; the scalp is pushed out to the hair outline of the photos ([HairShell]). Texture from [HeadBake]:
     * class-gated, depth-tested, feathered, inpainted. With no segmentation masks everything still works (no hair
     * volume, no neck fit, every pixel allowed).
     */
    fun buildHeadDetailed(
        views: List<AvatarView>, photos: List<Pixels>, canonical: FloatArray, triangles: IntArray, template: HeadTemplate,
        skinRgb: IntArray, hairRgb: IntArray,
    ): HeadBuild? {
        val f = fuse(views, photos, canonical, triangles) ?: return null
        val uv = f.usable.map { views[it] }
        val ph = f.usable.map { photos[it] }
        val nv = uv.size
        // per view, landmark image position minus the projection of the averaged landmark: the photo's own correction
        val tmp = DoubleArray(2)
        val extra = Array(468) { i ->
            DoubleArray(2 * nv).also { r ->
                for (k in 0 until nv) {
                    val v = uv[k]
                    project(f.poses[k], v.focalPx, v.imageW, v.imageH, f.verts[3 * i].toDouble(), f.verts[3 * i + 1].toDouble(), f.verts[3 * i + 2].toDouble(), tmp)
                    r[2 * k] = v.landmarks[i].x - tmp[0]
                    r[2 * k + 1] = v.landmarks[i].y - tmp[1]
                }
            }
        }
        val d = HeadDeform.deform(template, canonical, f.verts, extra)
        val n = template.vertexCount
        val weld = template.weld
        val vtx = d.vertices.copyOf()
        HeadMesh.copyWelded(vtx, weld)
        // folds (e.g. a long jaw pressed into the neck) are relaxed away from the face
        val folds = HeadMesh.unfold(template.vertices, vtx, template.triangles, weld, { template.region[it] != HeadTemplate.FACE })
        // the neck from the body-skin silhouette (front: width, sides: depth)
        val chinY = f.verts[3 * 152 + 1].toDouble()
        val neckIds = (0 until n).filter { template.region[it] == HeadTemplate.NECK }
        val level = chinY - 3.5
        val ring = neckIds.filter { abs(vtx[3 * it + 1] - level) < 0.6 }
        var neckSize = NeckFit.Size(null, null, null)
        if (ring.size >= 6) {
            val xs = ring.map { vtx[3 * it].toDouble() }; val zs = ring.map { vtx[3 * it + 2].toDouble() }
            val centre = V3((xs.max() + xs.min()) / 2, level, (zs.max() + zs.min()) / 2)
            neckSize = NeckFit.measure(V3(centre.x, centre.y, zs.max()), uv, f.poses, ph, f.cams)
            val weight = FloatArray(n) { i ->
                if (template.region[i] != HeadTemplate.NECK) 0f
                else ((chinY - 1.0 - vtx[3 * i + 1]) / 3.0).coerceIn(0.0, 1.0).toFloat()
            }
            NeckFit.apply(vtx, weight, centre, neckSize, xs.max() - xs.min(), zs.max() - zs.min())
        }
        // hair volume up to the hair outline in the photos; kept flat where the temples pass
        val hair = HairShell.hairness(template.region, template.triangles, weld)
        val protect = HairShell.templeProtect(vtx, listOf(d.earRestR, d.earRestL))
        val off = HairShell.offsets(vtx, template.triangles, weld, hair, uv, f.poses, ph, f.cams, protect)
        HairShell.displace(vtx, template.triangles, weld, off)
        HeadMesh.copyWelded(vtx, weld)
        // every vertex into every photo (plus the face correction), and its depth for the visibility test
        val imagePts = ArrayList<FloatArray>(); val depths = ArrayList<FloatArray>()
        for (k in 0 until nv) {
            val v = uv[k]
            val ip = FloatArray(2 * n); val dp = FloatArray(n)
            for (i in 0 until n) {
                val x = vtx[3 * i].toDouble(); val y = vtx[3 * i + 1].toDouble(); val z = vtx[3 * i + 2].toDouble()
                project(f.poses[k], v.focalPx, v.imageW, v.imageH, x, y, z, tmp)
                ip[2 * i] = (tmp[0] + d.extra[i][2 * k]).toFloat()
                ip[2 * i + 1] = (tmp[1] + d.extra[i][2 * k + 1]).toFloat()
                dp[i] = (-Mat4.apply(f.poses[k], x, y, z)[2]).toFloat()
            }
            imagePts += ip; depths += dp
        }
        val neckW = FloatArray(n) { if (template.region[it] == HeadTemplate.NECK) 1f else 0f }
        val cosCut = FloatArray(n) { if (template.region[it] == HeadTemplate.FACE) COS_CUT.toFloat() else COS_CUT_OFF_FACE }
        val canonN = GlbWriter.vertexNormals(canonical, triangles)
        val gains = colourGains(f.verts, canonN, uv, ph, f.cams)
        val bake = HeadBake.bake(vtx, template.triangles, template.uvs, weld, uv, ph, f.cams, f.baseW, imagePts, depths,
            hair, neckW, cosCut, skinRgb, neckSize.rgb ?: skinRgb, hairRgb, gains)
        // temples rest on the ear roots (ears are not moved by the hair: the band around the temples is protected)
        val anchors = FaceAnchors.fromVertices(f.verts).copy(earRestR = d.earRestR, earRestL = d.earRestL)
        val best = bestViews(vtx, template.triangles, f.cams, f.baseW)
        val model = AvatarModel(vtx, template.uvs, template.triangles.copyOf(), bake.texture, FloatArray(0), IntArray(0), IntArray(0),
            FloatArray(0), IntArray(0), anchors, best, weld)
        return HeadBuild(model, bake, off, folds, neckSize)
    }

    /** Cheek and forehead points used to match white balance and exposure between the photos. */
    val SKIN_POINTS = intArrayOf(50, 280, 101, 330, 151, 205, 425, 118, 347, 36, 266)

    /**
     * Per-view colour gains (r, g, b) that make each side photo's skin match the front photo's skin on the points both
     * see well: a light white-balance / exposure match. The front view keeps gain 1. Clamped to 0.75–1.33.
     */
    fun colourGains(verts: FloatArray, normals: FloatArray, views: List<AvatarView>, photos: List<Pixels>, cams: List<V3>): List<DoubleArray> {
        val fi = views.indexOfFirst { it.step == AvatarStep.FRONT }
        val o = DoubleArray(3)
        fun mean(k: Int, pts: List<Int>): DoubleArray? {
            val acc = DoubleArray(3); var n = 0
            for (i in pts) {
                val lm = views[k].landmarks[i]
                val fx = lm.x * views[k].imageW.toDouble(); val fy = lm.y * views[k].imageH.toDouble()
                if (photos[k].hasClasses && photos[k].classFractionFull(fx, fy, SegClass.SKIN_BITS) < 1.0) continue
                if (photos[k].sampleFull(fx, fy, o)) { for (c in 0 until 3) acc[c] += o[c]; n++ }
            }
            return if (n == 0) null else DoubleArray(3) { acc[it] / n }
        }
        return views.indices.map { k ->
            if (k == fi || fi < 0) return@map doubleArrayOf(1.0, 1.0, 1.0)
            // skin points both the front and this view see within 45°
            val both = SKIN_POINTS.filter { i ->
                val p = v(verts, i); val n = v(normals, i)
                dot(n, norm(cams[k] - p)) > 0.7 && dot(n, norm(cams[fi] - p)) > 0.7
            }
            val a = mean(fi, both); val b = mean(k, both)
            if (a == null || b == null) doubleArrayOf(1.0, 1.0, 1.0)
            else DoubleArray(3) { c -> (a[c] / max(b[c], 1.0)).coerceIn(0.75, 1.33) }
        }
    }

    /**
     * Texture bake in UV space ([TEX]² sRGB colour atlas): every texel is a blend of the photos, weighted by how squarely
     * each view sees it (facing^4, front ×2); views beyond [MAX_VIEW_ANGLE_DEG] are skipped; side photos get their
     * colour gains. Texels no view sees keep the skin colour (never grey or white).
     */
    fun bake(
        verts: FloatArray, triangles: IntArray, uvs: FloatArray, views: List<AvatarView>, photos: List<Pixels>,
        cams: List<V3>, baseW: List<Double>, skinRgb: IntArray, gains: List<DoubleArray> = views.map { doubleArrayOf(1.0, 1.0, 1.0) },
        size: Int = TEX,
        /** Per view, normalised image (x, y) of every vertex; default: the view's landmarks (canonical topology). */
        imagePts: List<FloatArray>? = null,
        /** Per vertex 0..1: how much of the texel is [hairRgb] (the scalp) instead of photo / skin. */
        hairMask: FloatArray? = null,
        hairRgb: IntArray? = null,
        /** Per vertex: cos of the widest angle a view may see it at (default [MAX_VIEW_ANGLE_DEG] everywhere). */
        cosCut: FloatArray? = null,
    ): IntArray {
        val skin = (0xFF shl 24) or (skinRgb[0] shl 16) or (skinRgb[1] shl 8) or skinRgb[2]
        val ip = imagePts ?: views.map { v -> FloatArray(2 * v.landmarks.size) { j -> if (j % 2 == 0) v.landmarks[j / 2].x else v.landmarks[j / 2].y } }
        val hr = hairRgb ?: skinRgb
        val tex = IntArray(size * size) { skin }
        val nrm = GlbWriter.vertexNormals(verts, triangles)
        val nv = views.size
        val camX = DoubleArray(nv) { cams[it].x }; val camY = DoubleArray(nv) { cams[it].y }; val camZ = DoubleArray(nv) { cams[it].z }
        val px = DoubleArray(3)
        val wv = DoubleArray(nv)
        for (t in 0 until triangles.size / 3) {
            val ia = triangles[3 * t]; val ib = triangles[3 * t + 1]; val ic = triangles[3 * t + 2]
            val ax = uvs[2 * ia] * size; val ay = uvs[2 * ia + 1] * size
            val bx = uvs[2 * ib] * size; val by = uvs[2 * ib + 1] * size
            val cx = uvs[2 * ic] * size; val cy = uvs[2 * ic + 1] * size
            val den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
            if (abs(den) < 1e-9) continue
            val x0 = floor(min(ax, min(bx, cx))).toInt().coerceIn(0, size - 1); val x1 = floor(max(ax, max(bx, cx))).toInt().coerceIn(0, size - 1)
            val y0 = floor(min(ay, min(by, cy))).toInt().coerceIn(0, size - 1); val y1 = floor(max(ay, max(by, cy))).toInt().coerceIn(0, size - 1)
            for (y in y0..y1) for (x in x0..x1) {
                val qx = x + 0.5f; val qy = y + 0.5f
                val l0 = ((by - cy) * (qx - cx) + (cx - bx) * (qy - cy)) / den
                val l1 = ((cy - ay) * (qx - cx) + (ax - cx) * (qy - cy)) / den
                val l2 = 1 - l0 - l1
                if (l0 < -0.02f || l1 < -0.02f || l2 < -0.02f) continue
                // point and smooth normal on the surface
                val p0 = l0 * verts[3 * ia] + l1 * verts[3 * ib] + l2 * verts[3 * ic]
                val p1 = l0 * verts[3 * ia + 1] + l1 * verts[3 * ib + 1] + l2 * verts[3 * ic + 1]
                val p2 = l0 * verts[3 * ia + 2] + l1 * verts[3 * ib + 2] + l2 * verts[3 * ic + 2]
                var n0 = (l0 * nrm[3 * ia] + l1 * nrm[3 * ib] + l2 * nrm[3 * ic]).toDouble()
                var n1 = (l0 * nrm[3 * ia + 1] + l1 * nrm[3 * ib + 1] + l2 * nrm[3 * ic + 1]).toDouble()
                var n2 = (l0 * nrm[3 * ia + 2] + l1 * nrm[3 * ib + 2] + l2 * nrm[3 * ic + 2]).toDouble()
                val nl = sqrt(n0 * n0 + n1 * n1 + n2 * n2).takeIf { it > 1e-9 } ?: continue
                n0 /= nl; n1 /= nl; n2 /= nl
                var r = 0.0; var g = 0.0; var b = 0.0; var sw = 0.0
                val hm = if (hairMask == null) 0f else l0 * hairMask[ia] + l1 * hairMask[ib] + l2 * hairMask[ic]
                if (hm >= 0.999f) {
                    tex[y * size + x] = (0xFF shl 24) or (hr[0] shl 16) or (hr[1] shl 8) or hr[2]
                    continue
                }
                val cut = if (cosCut == null) COS_CUT else (l0 * cosCut[ia] + l1 * cosCut[ib] + l2 * cosCut[ic]).toDouble()
                for (k in 0 until nv) {
                    val dx = camX[k] - p0; val dy = camY[k] - p1; val dz = camZ[k] - p2
                    val dl = sqrt(dx * dx + dy * dy + dz * dz)
                    val c = (n0 * dx + n1 * dy + n2 * dz) / dl
                    if (c < cut) { wv[k] = 0.0; continue }
                    val w = baseW[k] * c.pow(FACING_POWER)
                    val q = ip[k]
                    val sx = (q[2 * ia] * l0 + q[2 * ib] * l1 + q[2 * ic] * l2) * views[k].imageW.toDouble()
                    val sy = (q[2 * ia + 1] * l0 + q[2 * ib + 1] * l1 + q[2 * ic + 1] * l2) * views[k].imageH.toDouble()
                    if (!photos[k].sampleFull(sx, sy, px)) continue
                    val gk = gains[k]
                    r += px[0] * gk[0] * w; g += px[1] * gk[1] * w; b += px[2] * gk[2] * w; sw += w
                }
                if (sw > 0 || hm > 0f) {
                    // photo colour (or skin where no photo sees it), then the hair colour blended in at the hairline
                    var cr = if (sw > 0) r / sw else skinRgb[0].toDouble()
                    var cg = if (sw > 0) g / sw else skinRgb[1].toDouble()
                    var cb = if (sw > 0) b / sw else skinRgb[2].toDouble()
                    if (hm > 0f) { cr += (hr[0] - cr) * hm; cg += (hr[1] - cg) * hm; cb += (hr[2] - cb) * hm }
                    val ri = cr.toInt().coerceIn(0, 255); val gi = cg.toInt().coerceIn(0, 255); val bi = cb.toInt().coerceIn(0, 255)
                    tex[y * size + x] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
                }
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
        val img = g.addImage(textureJpeg, "image/jpeg", srgb = true)
        // opaque (no alpha / cut-out), double-sided: a fold or the inside of the neck never shows as a hole
        val faceMat = g.addMaterial(GlbWriter.Material("avatar_face", image = img, roughness = 0.92f, doubleSided = true))
        val skinMat = g.addMaterial(GlbWriter.Material("avatar_skin", baseColor = lin(skinRgb), roughness = 0.92f))
        val hairMat = g.addMaterial(GlbWriter.Material("avatar_hair", baseColor = lin(hairRgb), roughness = 0.95f))
        val normals = m.weld?.let { HeadMesh.normals(m.faceVertices, m.faceTriangles, it) } ?: GlbWriter.vertexNormals(m.faceVertices, m.faceTriangles)
        g.addMesh("avatar_face", m.faceVertices, m.faceTriangles, faceMat, normals, m.faceUvs)
        if (m.skinTriangles.isNotEmpty()) g.addMesh("avatar_shell", m.shellVertices, m.skinTriangles, skinMat, GlbWriter.vertexNormals(m.shellVertices, m.skinTriangles))
        if (m.hairTriangles.isNotEmpty()) g.addMesh("avatar_hair", m.shellVertices, m.hairTriangles, hairMat, GlbWriter.vertexNormals(m.shellVertices, m.hairTriangles))
        if (m.earTriangles.isNotEmpty()) g.addMesh("avatar_ears", m.earVertices, m.earTriangles, skinMat, GlbWriter.vertexNormals(m.earVertices, m.earTriangles))
        return g.build()
    }

    fun srgbToLinear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055) / 1.055).pow(2.4).toFloat()

    /** Median colour of 5×5 patches around normalised photo points (skin on the cheeks, hair above the forehead). */
    fun medianColour(px: Pixels, imageW: Int, imageH: Int, points: List<Pair<Double, Double>>): IntArray =
        medianColour(listOf(Triple(px, imageW to imageH, points)))

    /** Median colour of the pixels of one class set ([SegClass]) over all photos with a mask; null if too few. */
    fun classMedian(photos: List<Pixels>, bits: Int, step: Int = 3): IntArray? {
        val s = ArrayList<IntArray>()
        for (px in photos) {
            val m = px.cls ?: continue
            for (y in 0 until px.clsH step step) for (x in 0 until px.clsW step step) {
                if (!SegClass.allowed(m[y * px.clsW + x].toInt() and 0xFF, bits)) continue
                px.sample((x + 0.5) * px.w / px.clsW, (y + 0.5) * px.h / px.clsH)?.let { s += it }
            }
        }
        if (s.size < 30) return null
        return IntArray(3) { k -> s.map { it[k] }.sorted()[s.size / 2] }
    }

    /** Median over several photos (hair above the forehead in the front and side photos). */
    fun medianColour(samples: List<Triple<Pixels, Pair<Int, Int>, List<Pair<Double, Double>>>>): IntArray {
        val s = ArrayList<IntArray>()
        for ((px, wh, points) in samples) collect(px, wh.first, wh.second, points, s)
        if (s.isEmpty()) return intArrayOf(200, 160, 140)
        return IntArray(3) { k -> s.map { it[k] }.sorted()[s.size / 2] }
    }

    private fun collect(px: Pixels, imageW: Int, imageH: Int, points: List<Pair<Double, Double>>, s: ArrayList<IntArray>) {
        val o = DoubleArray(3)
        for ((x, y) in points) {
            val cx = (x * imageW - px.offsetX) * px.scale; val cy = (y * imageH - px.offsetY) * px.scale
            for (dx in -2..2) for (dy in -2..2) {
                if (px.sampleInto(cx + dx, cy + dy, o)) s += intArrayOf(o[0].toInt(), o[1].toInt(), o[2].toInt())
            }
        }
    }

    /**
     * Mean chroma (max − min of r, g, b, 0..255) over a grid of samples: about 0 for a greyscale (luminance-only)
     * picture, typically 15+ for a colour photo of a face. The capture refuses photos below [MIN_CHROMA].
     */
    fun chroma(px: Pixels, step: Int = 7): Double {
        var sum = 0.0; var n = 0
        for (y in 0 until px.h step step) for (x in 0 until px.w step step) {
            val c = px.argb[y * px.w + x]
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            sum += maxOf(r, g, b) - minOf(r, g, b); n++
        }
        return if (n == 0) 0.0 else sum / n
    }

    const val MIN_CHROMA = 4.0

    /** Skin sample points (cheeks, forehead) and hair points (above the forehead) of a front view. */
    fun skinPoints(lm: List<Landmark>) = intArrayOf(50, 280, 101, 330, 151, 205, 425).map { lm[it].x.toDouble() to lm[it].y.toDouble() }
    fun hairPoints(lm: List<Landmark>): List<Pair<Double, Double>> {
        val h = (lm[152].y - lm[10].y).toDouble()
        return listOf(10, 109, 338).map { lm[it].x.toDouble() to (lm[it].y - 0.12 * h).coerceAtLeast(0.0) }
    }
}
