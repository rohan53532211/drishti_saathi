package com.drishtisaathi.engine.tryon3d

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Class-aware low-pass copies of one photo crop for multi-band blending: level 1 (1/6 resolution, 3×3 box) and
 * level 2 (1/24, 3×3 box), each a normalised convolution over skin pixels and over hair pixels separately, so the
 * low bands never mix background, clothes or hair into skin (or skin into hair). Without a class mask one group
 * holds every pixel.
 */
class ViewBands(private val px: Pixels) {
    private val groups = if (px.hasClasses) 2 else 1
    private val w1 = (px.w + F1 - 1) / F1
    private val h1 = (px.h + F1 - 1) / F1
    private val w2 = (w1 + 3) / 4
    private val h2 = (h1 + 3) / 4
    private val l1 = Array(groups) { FloatArray(4 * w1 * h1) }
    private val l2 = Array(groups) { FloatArray(4 * w2 * h2) }

    init {
        val cls = px.cls
        for (y in 0 until px.h) {
            val cy = if (cls != null) min(px.clsH - 1, y * px.clsH / px.h) else 0
            val row = (y / F1) * w1
            for (x in 0 until px.w) {
                val g = if (cls == null) 0 else {
                    val c = cls[cy * px.clsW + min(px.clsW - 1, x * px.clsW / px.w)].toInt() and 0xFF
                    when {
                        SegClass.allowed(c, SegClass.SKIN_BITS) -> 0
                        c == SegClass.HAIR -> 1
                        else -> -1
                    }
                }
                if (g < 0) continue
                val o = 4 * (row + x / F1)
                val col = px.argb[y * px.w + x]
                val a = l1[g]
                a[o] += ((col shr 16) and 255).toFloat(); a[o + 1] += ((col shr 8) and 255).toFloat(); a[o + 2] += (col and 255).toFloat(); a[o + 3] += 1f
            }
        }
        for (g in 0 until groups) {
            blur3(l1[g], w1, h1)
            val b = l2[g]; val a = l1[g]
            for (y in 0 until h1) for (x in 0 until w1) {
                val o = 4 * (y * w1 + x); val q = 4 * ((y / 4) * w2 + x / 4)
                for (c in 0 until 4) b[q + c] += a[o + c]
            }
            blur3(b, w2, h2)
        }
    }

    private fun blur3(a: FloatArray, w: Int, h: Int) {
        val t = FloatArray(a.size)
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until 4) {
            var s = 0f
            for (d in -1..1) { val xx = x + d; if (xx in 0 until w) s += a[4 * (y * w + xx) + c] }
            t[4 * (y * w + x) + c] = s
        }
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until 4) {
            var s = 0f
            for (d in -1..1) { val yy = y + d; if (yy in 0 until h) s += t[4 * (yy * w + x) + c] }
            a[4 * (y * w + x) + c] = s
        }
    }

    /** Low-pass colour at crop pixel ([xc], [yc]) of [level] 1 or 2 over the groups allowed by [bits]; false if none. */
    fun sample(level: Int, bits: Int, xc: Double, yc: Double, out: DoubleArray): Boolean {
        val f = if (level == 1) F1.toDouble() else F1 * 4.0
        val w = if (level == 1) w1 else w2; val h = if (level == 1) h1 else h2
        val arrs = if (level == 1) l1 else l2
        val gx = xc / f - 0.5; val gy = yc / f - 0.5
        val x0 = floor(gx).toInt(); val y0 = floor(gy).toInt()
        val fx = gx - x0; val fy = gy - y0
        var r = 0.0; var g = 0.0; var b = 0.0; var n = 0.0
        for (g2 in 0 until groups) {
            if (groups == 2 && !((g2 == 0 && bits and SegClass.SKIN_BITS != 0) || (g2 == 1 && bits and SegClass.HAIR_BITS != 0))) continue
            val a = arrs[g2]
            for (dy in 0..1) for (dx in 0..1) {
                val xx = (x0 + dx).coerceIn(0, w - 1); val yy = (y0 + dy).coerceIn(0, h - 1)
                val wt = (if (dx == 0) 1 - fx else fx) * (if (dy == 0) 1 - fy else fy)
                val o = 4 * (yy * w + xx)
                r += a[o] * wt; g += a[o + 1] * wt; b += a[o + 2] * wt; n += a[o + 3] * wt
            }
        }
        if (n < 0.5) return false
        out[0] = r / n; out[1] = g / n; out[2] = b / n
        return true
    }

    companion object { const val F1 = 6 }
}

/** The eyes, eyelids and brows of the template (canonical space), where the texture comes from the front photo only. */
object EyeRegion {
    val RIGHT = intArrayOf(33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246, 46, 53, 52, 65, 55, 70, 63, 105, 66, 107)
    val LEFT = intArrayOf(263, 249, 390, 373, 374, 380, 381, 382, 362, 398, 384, 385, 386, 387, 388, 466, 276, 283, 282, 295, 285, 300, 293, 334, 296, 336)

    /** 1 inside the box around each eye + brow (plus [margin] cm), falling to 0 over [feather] cm; face vertices only. */
    fun weights(rest: FloatArray, region: IntArray, canonical: FloatArray, margin: Double = 0.3, feather: Double = 0.4): FloatArray {
        val boxes = listOf(RIGHT, LEFT).map { ids ->
            doubleArrayOf(ids.minOf { canonical[3 * it].toDouble() }, ids.maxOf { canonical[3 * it].toDouble() },
                ids.minOf { canonical[3 * it + 1].toDouble() }, ids.maxOf { canonical[3 * it + 1].toDouble() },
                ids.minOf { canonical[3 * it + 2].toDouble() })
        }
        return FloatArray(region.size) { i ->
            if (region[i] != HeadTemplate.FACE) return@FloatArray 0f
            val x = rest[3 * i].toDouble(); val y = rest[3 * i + 1].toDouble(); val z = rest[3 * i + 2].toDouble()
            var best = 0.0
            for (b in boxes) {
                if (z < b[4] - 1.5) continue
                val dx = max(0.0, max(b[0] - x, x - b[1])); val dy = max(0.0, max(b[2] - y, y - b[3]))
                val d = sqrt(dx * dx + dy * dy)
                best = max(best, (1 - (d - margin) / feather).coerceIn(0.0, 1.0))
            }
            best.toFloat()
        }
    }
}

/**
 * Pose of a 90° profile photo, where MediaPipe usually loses the face: the 60° view of the same side (last good head
 * pose) turned further about the head's vertical axis, each candidate angle aligned (scale and position) to the
 * face-skin silhouette of the segmentation and scored by the overlap (IoU) of the projected face/ear triangles with
 * the face-skin pixels. Null (view skipped) without a mask or when no candidate fits well.
 */
object ProfilePose {
    const val MIN_SCORE = 0.55

    class Fit(val pose: FloatArray, val extraYawDeg: Double, val score: Double)

    fun fit(view: AvatarView, photo: Pixels, ref: FloatArray, side: Int, verts: FloatArray, tris: IntArray, centre: V3, minScore: Double = MIN_SCORE, trace: MutableList<String>? = null): Fit? {
        val ids = tris.distinct().toIntArray()
        val slot = HashMap<Int, Int>().also { m -> ids.forEachIndexed { j, i -> m[i] = j } }
        val m = photo.cls ?: return null
        val gw = photo.clsW; val gh = photo.clsH
        val W = view.imageW.toDouble(); val H = view.imageH.toDouble(); val f = view.focalPx
        // face-skin cells (full-photo pixel coordinates of their centres)
        val mx = ArrayList<Double>(); val my = ArrayList<Double>()
        for (y in 0 until gh) for (x in 0 until gw) if ((m[y * gw + x].toInt() and 0xFF) == SegClass.FACE_SKIN) {
            mx += (x + 0.5) * photo.w / gw / photo.scale + photo.offsetX
            my += (y + 0.5) * photo.h / gh / photo.scale + photo.offsetY
        }
        if (mx.size < 60) return null
        fun pct(a: List<Double>, p: Double) = a.sorted()[((a.size - 1) * p).toInt()]
        val box = doubleArrayOf(pct(mx, 0.02), pct(mx, 0.98), pct(my, 0.02), pct(my, 0.98))
        val tmp = DoubleArray(2)
        fun projectAll(p: FloatArray): DoubleArray {
            val out = DoubleArray(2 * ids.size)
            for ((j, i) in ids.withIndex()) {
                AvatarBuilder.project(p, f, view.imageW, view.imageH, verts[3 * i].toDouble(), verts[3 * i + 1].toDouble(), verts[3 * i + 2].toDouble(), tmp)
                out[2 * j] = tmp[0] * W; out[2 * j + 1] = tmp[1] * H
            }
            return out
        }
        var cx = 0.0; var cy = 0.0; var cz = 0.0
        for (i in ids) { cx += verts[3 * i]; cy += verts[3 * i + 1]; cz += verts[3 * i + 2] }
        val cen = V3(cx / ids.size, cy / ids.size, cz / ids.size)
        /** The projected face / ear triangles filled on the class grid. */
        fun silhouette(p: FloatArray): BooleanArray {
            val pr = projectAll(p)
            val gxOf = DoubleArray(ids.size) { j -> (pr[2 * j] - photo.offsetX) * photo.scale * gw / photo.w }
            val gyOf = DoubleArray(ids.size) { j -> (pr[2 * j + 1] - photo.offsetY) * photo.scale * gh / photo.h }
            val sil = BooleanArray(gw * gh)
            for (t in tris.indices step 3) {
                val a = slot.getValue(tris[t]); val b = slot.getValue(tris[t + 1]); val c = slot.getValue(tris[t + 2])
                val ax = gxOf[a]; val ay = gyOf[a]; val bx = gxOf[b]; val by = gyOf[b]; val cx = gxOf[c]; val cy = gyOf[c]
                val den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
                if (abs(den) < 1e-12) continue
                val x0 = max(0, floor(min(ax, min(bx, cx))).toInt()); val x1 = min(gw - 1, floor(max(ax, max(bx, cx))).toInt())
                val y0 = max(0, floor(min(ay, min(by, cy))).toInt()); val y1 = min(gh - 1, floor(max(ay, max(by, cy))).toInt())
                for (y in y0..y1) for (x in x0..x1) {
                    val l0 = ((by - cy) * (x + 0.5 - cx) + (cx - bx) * (y + 0.5 - cy)) / den
                    val l1 = ((cy - ay) * (x + 0.5 - cx) + (ax - cx) * (y + 0.5 - cy)) / den
                    if (l0 >= 0 && l1 >= 0 && 1 - l0 - l1 >= 0) sil[y * gw + x] = true
                }
            }
            return sil
        }
        /** Scale (by distance) and position the head so its silhouette box matches the face-skin box (same statistics). */
        fun align(p: FloatArray) {
            repeat(4) {
                val sil = silhouette(p)
                val sx = ArrayList<Double>(); val sy = ArrayList<Double>()
                for (o in sil.indices) if (sil[o]) {
                    sx += (o % gw + 0.5) * photo.w / gw / photo.scale + photo.offsetX
                    sy += (o / gw + 0.5) * photo.h / gh / photo.scale + photo.offsetY
                }
                if (sx.size < 10) return
                val pb = doubleArrayOf(pct(sx, 0.02), pct(sx, 0.98), pct(sy, 0.02), pct(sy, 0.98))
                val s = ((box[3] - box[2]) / max(pb[3] - pb[2], 1.0)).coerceIn(0.5, 2.0)
                AvatarBuilder.project(p, f, view.imageW, view.imageH, cen.x, cen.y, cen.z, tmp)
                val px0 = tmp[0] * W; val py0 = tmp[1] * H
                val tx = (box[0] + box[1]) / 2 + (px0 - (pb[0] + pb[1]) / 2) * s
                val ty = (box[2] + box[3]) / 2 + (py0 - (pb[2] + pb[3]) / 2) * s
                val q = Mat4.apply(p, cen.x, cen.y, cen.z)
                val d = -q[2]; val d2 = d / s
                val nx = (tx - W / 2) * d2 / f; val ny = -(ty - H / 2) * d2 / f
                p[12] += (nx - q[0]).toFloat(); p[13] += (ny - q[1]).toFloat(); p[14] += (-d2 - q[2]).toFloat()
            }
        }
        fun score(p: FloatArray): Double {
            val sil = silhouette(p)
            var inter = 0; var uni = 0
            for (o in sil.indices) {
                val mk = (m[o].toInt() and 0xFF) == SegClass.FACE_SKIN
                if (sil[o] && mk) inter++
                if (sil[o] || mk) uni++
            }
            return if (uni == 0) 0.0 else inter.toDouble() / uni
        }
        var best: Fit? = null
        var deg = 10.0
        while (deg <= 45.0 + 1e-9) {          // 2.5° steps
            val p = Mat4.mul(ref, Mat4.translate(centre.x, centre.y, centre.z), Mat4.rotY(side * deg * Math.PI / 180), Mat4.translate(-centre.x, -centre.y, -centre.z))
            align(p)
            val sc = score(p)
            trace?.add("%.1f:%.3f".format(deg, sc))
            if (best == null || sc > best.score) best = Fit(p, deg, sc)
            deg += 2.5
        }
        return best?.takeIf { it.score >= minScore }
    }
}

/**
 * Texture bake for the head avatar ([AvatarBuilder.TEX]² sRGB atlas):
 *  - a texel takes colour only from photo pixels of the right class (face / ear / neck: face- or body-skin; scalp:
 *    hair; both in the soft band at the hairline), never background or clothes; with no class mask every pixel counts;
 *  - only from views that actually see the point (per-view depth buffer) within the per-vertex angle limit
 *    (70° on the face, 60° elsewhere: beyond that a photo smears into streaks);
 *  - multi-band blending: each photo is split into a coarse band (1/24 resolution), a middle band (1/6) and the
 *    detail; the coarse band blends broadly (weight w), the middle band more sharply (w²) and the detail almost from the
 *    best view only (w⁴), with w = facing⁴ × class coverage of a 3×3 neighbourhood (front ×2). Skin tone matches
 *    across views, detail stays sharp, seams are feathered;
 *  - the eyes, eyelids and brows come from the front photo only (no ghosting), slightly sharpened;
 *  - fill: texels no photo covers get a smooth base (the nearest covered colour of the same kind, fading into the
 *    skin / neck tone or the shaded hair colour), then detail copied from patches of covered skin in the atlas or of
 *    hair in the photos (two overlapping tilings), and mild noise: never a flat colour. A gutter goes around every
 *    UV island. Every texel is opaque and coloured.
 */
object HeadBake {
    class Result(val texture: IntArray, val covered: Int, val inpainted: Int, val fallback: Int, val gutter: Int, val eyeTexels: Int)

    private const val Z_GRID = 384
    /** A point counts as seen when it is at most this far (cm) behind the nearest surface at its pixel. */
    private const val Z_EPS = 0.6
    /** Detail patch size (texels / crop pixels). */
    const val PATCH = 32
    /** The fill fades from the nearest covered colour to the tone over this many texels. */
    private const val FADE_TEXELS = 32
    /** Classes the front photo may use in the eye region (eyes and brows may be labelled hair or "others"). */
    private const val EYE_BITS = SegClass.SKIN_BITS or SegClass.HAIR_BITS or (1 shl SegClass.OTHERS)

    private class ZBuf(val gw: Int, val gh: Int) { val z = FloatArray(gw * gh) { Float.MAX_VALUE } }

    private fun zbuffer(tris: IntArray, ip: FloatArray, depth: FloatArray, aspect: Double): ZBuf {
        val gw = Z_GRID; val gh = max(1, (Z_GRID * aspect).toInt())
        val zb = ZBuf(gw, gh)
        for (t in tris.indices step 3) {
            val a = tris[t]; val b = tris[t + 1]; val c = tris[t + 2]
            val ax = ip[2 * a] * gw; val ay = ip[2 * a + 1] * gh
            val bx = ip[2 * b] * gw; val by = ip[2 * b + 1] * gh
            val cx = ip[2 * c] * gw; val cy = ip[2 * c + 1] * gh
            val den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
            if (abs(den) < 1e-9) continue
            val x0 = max(0, floor(min(ax, min(bx, cx))).toInt()); val x1 = min(gw - 1, floor(max(ax, max(bx, cx))).toInt())
            val y0 = max(0, floor(min(ay, min(by, cy))).toInt()); val y1 = min(gh - 1, floor(max(ay, max(by, cy))).toInt())
            for (y in y0..y1) for (x in x0..x1) {
                val qx = x + 0.5f; val qy = y + 0.5f
                val l0 = ((by - cy) * (qx - cx) + (cx - bx) * (qy - cy)) / den
                val l1 = ((cy - ay) * (qx - cx) + (ax - cx) * (qy - cy)) / den
                val l2 = 1 - l0 - l1
                if (l0 < 0 || l1 < 0 || l2 < 0) continue
                val d = l0 * depth[a] + l1 * depth[b] + l2 * depth[c]
                val o = y * gw + x
                if (d < zb.z[o]) zb.z[o] = d
            }
        }
        return zb
    }

    /** Depth-buffer test for a point at normalised image (x, y) with depth d (the 3×3 minimum, so edges stay seen). */
    private fun seen(zb: ZBuf, x: Float, y: Float, d: Float): Boolean {
        val gx = (x * zb.gw).toInt(); val gy = (y * zb.gh).toInt()
        if (gx < 0 || gy < 0 || gx >= zb.gw || gy >= zb.gh) return false
        var m = Float.MAX_VALUE
        for (oy in -1..1) for (ox in -1..1) {
            val xx = gx + ox; val yy = gy + oy
            if (xx in 0 until zb.gw && yy in 0 until zb.gh) m = min(m, zb.z[yy * zb.gw + xx])
        }
        return d <= m + Z_EPS
    }

    /** A square of residual detail (colour minus its best-fit plane), [PATCH]² × rgb. */
    private class Patch(val d: FloatArray)

    /** Residual of a least-squares plane per channel over a [PATCH]² block read by [get] (packed rgb). */
    private fun planeResidual(get: (Int, Int) -> Int): Patch? {
        val t = PATCH
        val d = FloatArray(t * t * 3)
        val mid = (t - 1) / 2.0
        var sxx = 0.0
        for (i in 0 until t) sxx += (i - mid) * (i - mid)
        sxx *= t
        var energy = 0.0
        for (c in 0 until 3) {
            var s = 0.0; var sx = 0.0; var sy = 0.0
            for (y in 0 until t) for (x in 0 until t) {
                val v = ((get(x, y) shr (16 - 8 * c)) and 255).toDouble()
                s += v; sx += v * (x - mid); sy += v * (y - mid)
            }
            val a = s / (t * t); val bx = sx / sxx; val by = sy / sxx
            for (y in 0 until t) for (x in 0 until t) {
                val v = ((get(x, y) shr (16 - 8 * c)) and 255).toDouble()
                val r = v - (a + bx * (x - mid) + by * (y - mid))
                d[3 * (y * t + x) + c] = r.toFloat()
                energy += r * r
            }
        }
        val std = sqrt(energy / (3 * t * t))
        return if (std > 30.0) null else Patch(d)     // eyes, nostrils, hair over skin: not a texture to repeat
    }

    /** Square patches of one class group in the photos (crop pixels, fully of that class on a 5×5 check grid). */
    private fun photoPatches(photos: List<Pixels>, bits: Int, maxN: Int = 48): List<Patch> {
        val out = ArrayList<Patch>()
        val t = PATCH
        for (p in photos) {
            val m = p.cls ?: continue
            var y = 0
            while (y + t <= p.h && out.size < maxN) {
                var x = 0
                while (x + t <= p.w && out.size < maxN) {
                    var ok = true
                    loop@ for (j in 0..4) for (i in 0..4) {
                        val cx = min(p.clsW - 1, (x + i * (t - 1) / 4) * p.clsW / p.w)
                        val cy = min(p.clsH - 1, (y + j * (t - 1) / 4) * p.clsH / p.h)
                        if (!SegClass.allowed(m[cy * p.clsW + cx].toInt() and 0xFF, bits)) { ok = false; break@loop }
                    }
                    if (ok) {
                        val x0 = x; val y0 = y
                        planeResidual { xx, yy -> p.argb[(y0 + yy) * p.w + x0 + xx] }?.let { out += it }
                    }
                    x += t
                }
                y += t
            }
        }
        return out
    }

    private fun hash(a: Int, b: Int, c: Int): Int {
        var h = a * 73856093 xor b * 19349663 xor c * 83492791
        h = h xor (h ushr 13); h *= 0x5bd1e995; h = h xor (h ushr 15)
        return h and 0x7FFFFFFF
    }

    fun bake(
        verts: FloatArray, tris: IntArray, uvs: FloatArray, weld: IntArray, views: List<AvatarView>, photos: List<Pixels>,
        cams: List<V3>, baseW: List<Double>, imagePts: List<FloatArray>, depths: List<FloatArray>,
        hair: FloatArray, neck: FloatArray, cosCut: FloatArray,
        skinRgb: IntArray, neckRgb: IntArray, hairRgb: IntArray, gains: List<DoubleArray>, size: Int = AvatarBuilder.TEX,
        /** Per vertex 0..1: eye region (front photo only); null = none. */
        eye: FloatArray? = null,
        /** Index of the front view in [views] (−1: none). */
        frontIndex: Int = views.indexOfFirst { it.step == AvatarStep.FRONT },
        /** Photos with no pose (the top of the head) that still give hair texture patches. */
        extraHair: List<Pixels> = emptyList(),
    ): Result {
        val n2 = size * size
        val skin = (0xFF shl 24) or (skinRgb[0] shl 16) or (skinRgb[1] shl 8) or skinRgb[2]
        val tex = IntArray(n2) { skin }
        // 0 outside every triangle, 1 not covered by a photo (filled), 2 photo colour
        val state = ByteArray(n2)
        // eye weight a texel was coloured with: a later triangle with more eye weight (UV folds round the eyelids) recolours it
        val eyeAt = ByteArray(n2)
        val kind = ByteArray(n2)                      // 0 skin, 1 hairline band, 2 hair: filling copies only within a kind
        val nrm = HeadMesh.normals(verts, tris, weld)
        val nv = views.size
        val zb = (0 until nv).map { zbuffer(tris, imagePts[it], depths[it], views[it].imageH.toDouble() / views[it].imageW) }
        val bands = photos.map { ViewBands(it) }
        val px = DoubleArray(3); val l1 = DoubleArray(3); val l2 = DoubleArray(3); val nb = DoubleArray(3)
        val light = doubleArrayOf(0.2, 0.8, 0.55).let { l -> val s = sqrt(l.sumOf { it * it }); DoubleArray(3) { l[it] / s } }
        var eyeTexels = 0
        for (t in tris.indices step 3) {
            val ia = tris[t]; val ib = tris[t + 1]; val ic = tris[t + 2]
            val ax = uvs[2 * ia] * size; val ay = uvs[2 * ia + 1] * size
            val bx = uvs[2 * ib] * size; val by = uvs[2 * ib + 1] * size
            val cx = uvs[2 * ic] * size; val cy = uvs[2 * ic + 1] * size
            val den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
            if (abs(den) < 1e-9) continue
            val x0 = floor(min(ax, min(bx, cx))).toInt().coerceIn(0, size - 1); val x1 = floor(max(ax, max(bx, cx))).toInt().coerceIn(0, size - 1)
            val y0 = floor(min(ay, min(by, cy))).toInt().coerceIn(0, size - 1); val y1 = floor(max(ay, max(by, cy))).toInt().coerceIn(0, size - 1)
            for (y in y0..y1) for (x in x0..x1) {
                val qx = x + 0.5f; val qy = y + 0.5f
                val b0 = ((by - cy) * (qx - cx) + (cx - bx) * (qy - cy)) / den
                val b1 = ((cy - ay) * (qx - cx) + (ax - cx) * (qy - cy)) / den
                val b2 = 1 - b0 - b1
                if (b0 < -0.02f || b1 < -0.02f || b2 < -0.02f) continue
                val o = y * size + x
                val e = if (eye == null) 0f else (b0 * eye[ia] + b1 * eye[ib] + b2 * eye[ic]).coerceIn(0f, 1f)
                // already coloured by a neighbouring triangle (unless this one is more inside the eye region)
                if (state[o].toInt() == 2 && (e * 255).toInt() <= (eyeAt[o].toInt() and 0xFF) + 12) continue
                val p0 = b0 * verts[3 * ia] + b1 * verts[3 * ib] + b2 * verts[3 * ic]
                val p1 = b0 * verts[3 * ia + 1] + b1 * verts[3 * ib + 1] + b2 * verts[3 * ic + 1]
                val p2 = b0 * verts[3 * ia + 2] + b1 * verts[3 * ib + 2] + b2 * verts[3 * ic + 2]
                var n0 = (b0 * nrm[3 * ia] + b1 * nrm[3 * ib] + b2 * nrm[3 * ic]).toDouble()
                var n1 = (b0 * nrm[3 * ia + 1] + b1 * nrm[3 * ib + 1] + b2 * nrm[3 * ic + 1]).toDouble()
                var n2n = (b0 * nrm[3 * ia + 2] + b1 * nrm[3 * ib + 2] + b2 * nrm[3 * ic + 2]).toDouble()
                val nl = sqrt(n0 * n0 + n1 * n1 + n2n * n2n).takeIf { it > 1e-9 } ?: 1.0
                n0 /= nl; n1 /= nl; n2n /= nl
                val h = (b0 * hair[ia] + b1 * hair[ib] + b2 * hair[ic]).coerceIn(0f, 1f)
                val nk = (b0 * neck[ia] + b1 * neck[ib] + b2 * neck[ic]).coerceIn(0f, 1f)
                val cut = (b0 * cosCut[ia] + b1 * cosCut[ib] + b2 * cosCut[ic]).toDouble()
                val bits = when {
                    h < 0.3f -> SegClass.SKIN_BITS
                    h > 0.75f -> SegClass.HAIR_BITS
                    else -> SegClass.SKIN_BITS or SegClass.HAIR_BITS
                }
                // three bands: coarse (weight w), middle (w²), detail (w⁴)
                var c2r = 0.0; var c2g = 0.0; var c2b = 0.0; var sw1 = 0.0
                var c1r = 0.0; var c1g = 0.0; var c1b = 0.0; var sw2 = 0.0
                var c0r = 0.0; var c0g = 0.0; var c0b = 0.0; var sw4 = 0.0
                var fr = -1.0; var fg = 0.0; var fb = 0.0          // front-only colour (eye region)
                for (k in 0 until nv) {
                    val dx = cams[k].x - p0; val dy = cams[k].y - p1; val dz = cams[k].z - p2
                    val dl = sqrt(dx * dx + dy * dy + dz * dz)
                    val c = (n0 * dx + n1 * dy + n2n * dz) / dl
                    val q = imagePts[k]
                    val ix = q[2 * ia] * b0 + q[2 * ib] * b1 + q[2 * ic] * b2
                    val iy = q[2 * ia + 1] * b0 + q[2 * ib + 1] * b1 + q[2 * ic + 1] * b2
                    val pk = photos[k]
                    val fx = ix * views[k].imageW.toDouble(); val fy = iy * views[k].imageH.toDouble()
                    // eye region: what the front photo shows at this point (also in the folds of the lids it cannot see squarely)
                    if (k == frontIndex && e > 0f && pk.classFractionFull(fx, fy, EYE_BITS) > 0.0 && pk.sampleFull(fx, fy, px)) {
                        // front photo, slightly sharpened (unsharp mask over ±1.2 crop pixels)
                        val d = 1.2 / pk.scale
                        var mr = 0.0; var mg = 0.0; var mb = 0.0; var mn = 0
                        for ((ox, oy) in SHARP) if (pk.sampleFull(fx + ox * d, fy + oy * d, nb)) { mr += nb[0]; mg += nb[1]; mb += nb[2]; mn++ }
                        if (mn > 0) { mr /= mn; mg /= mn; mb /= mn } else { mr = px[0]; mg = px[1]; mb = px[2] }
                        fr = px[0] + SHARPEN * (px[0] - mr); fg = px[1] + SHARPEN * (px[1] - mg); fb = px[2] + SHARPEN * (px[2] - mb)
                    }
                    if (c < cut) continue
                    val dk = depths[k]
                    if (!seen(zb[k], ix, iy, dk[ia] * b0 + dk[ib] * b1 + dk[ic] * b2)) continue
                    val frac = pk.classFractionFull(fx, fy, bits)
                    if (frac <= 0.0) continue
                    if (!pk.sampleFull(fx, fy, px)) continue
                    val xc = (fx - pk.offsetX) * pk.scale; val yc = (fy - pk.offsetY) * pk.scale
                    if (!bands[k].sample(1, bits, xc, yc, l1)) { l1[0] = px[0]; l1[1] = px[1]; l1[2] = px[2] }
                    if (!bands[k].sample(2, bits, xc, yc, l2)) { l2[0] = l1[0]; l2[1] = l1[1]; l2[2] = l1[2] }
                    val w = baseW[k] * c.pow(AvatarBuilder.FACING_POWER) * frac
                    val w2 = w * w; val w4 = w2 * w2
                    val g = gains[k]
                    c2r += w * l2[0] * g[0]; c2g += w * l2[1] * g[1]; c2b += w * l2[2] * g[2]
                    c1r += w2 * (l1[0] - l2[0]) * g[0]; c1g += w2 * (l1[1] - l2[1]) * g[1]; c1b += w2 * (l1[2] - l2[2]) * g[2]
                    c0r += w4 * (px[0] - l1[0]) * g[0]; c0g += w4 * (px[1] - l1[1]) * g[1]; c0b += w4 * (px[2] - l1[2]) * g[2]
                    sw1 += w; sw2 += w2; sw4 += w4
                }
                kind[o] = (if (h < 0.3f) 0 else if (h > 0.75f) 2 else 1).toByte()
                val multi = sw1 > 1e-12 && sw4 > 1e-30
                if (multi || fr >= 0) {
                    var r: Double; var g: Double; var b: Double
                    if (multi) {
                        r = c2r / sw1 + c1r / sw2 + c0r / sw4; g = c2g / sw1 + c1g / sw2 + c0g / sw4; b = c2b / sw1 + c1b / sw2 + c0b / sw4
                    } else { r = fr; g = fg; b = fb }
                    if (fr >= 0) {
                        val ee = e.toDouble()
                        r += (fr - r) * ee; g += (fg - g) * ee; b += (fb - b) * ee
                    }
                    tex[o] = rgb(r, g, b)
                    state[o] = 2
                    eyeAt[o] = (e * 255).toInt().toByte()
                } else {
                    if (state[o].toInt() == 2) continue
                    // the tone the fill fades into: skin (neck tone on the neck), hair with a soft normal shading
                    val sr = skinRgb[0] + (neckRgb[0] - skinRgb[0]) * nk
                    val sg = skinRgb[1] + (neckRgb[1] - skinRgb[1]) * nk
                    val sb = skinRgb[2] + (neckRgb[2] - skinRgb[2]) * nk
                    val shade = 0.8 + 0.2 * max(0.0, n0 * light[0] + n1 * light[1] + n2n * light[2])
                    val hm = ((h - 0.3f) / 0.45f).coerceIn(0f, 1f).toDouble()
                    tex[o] = rgb(sr + (hairRgb[0] * shade - sr) * hm, sg + (hairRgb[1] * shade - sg) * hm, sb + (hairRgb[2] * shade - sb) * hm)
                    state[o] = 1
                }
            }
        }
        val covered = state.count { it.toInt() == 2 }
        eyeTexels = eyeAt.count { (it.toInt() and 0xFF) >= 252 }
        val (inpainted, fallback) = fill(tex, state, kind, size, photos + extraHair)
        // gutter: texels outside the triangles copy their neighbours (no seams under mipmapping / bilinear filtering)
        var gutter = 0
        repeat(4) {
            val add = ArrayList<Long>()
            for (o in 0 until n2) {
                if (state[o].toInt() != 0) continue
                val x = o % size; val y = o / size
                for ((ox, oy) in GUTTER) {
                    val xx = x + ox; val yy = y + oy
                    if (xx < 0 || yy < 0 || xx >= size || yy >= size) continue
                    val q = yy * size + xx
                    if (state[q].toInt() != 0) { add += (o.toLong() shl 32) or (tex[q].toLong() and 0xFFFFFFFFL); break }
                }
            }
            for (v in add) { val o = (v ushr 32).toInt(); tex[o] = v.toInt(); state[o] = 4 }
            gutter += add.size
        }
        return Result(tex, covered, inpainted, fallback, gutter, eyeTexels)
    }

    /**
     * Fills the uncovered texels (state 1, holding their tone): smooth base from the nearest covered texel of the same
     * kind fading into the tone, box-smoothed; then synthesized detail and mild noise. Returns (texels with a covered
     * source, texels without one).
     */
    private fun fill(tex: IntArray, state: ByteArray, kind: ByteArray, size: Int, photos: List<Pixels>): Pair<Int, Int> {
        val n2 = size * size
        // nearest covered texel of the same kind (breadth-first from every covered texel)
        val dist = ShortArray(n2) { -1 }
        val near = IntArray(n2)
        val queue = IntArray(n2)
        var qh = 0; var qt = 0
        for (o in 0 until n2) if (state[o].toInt() == 2) { dist[o] = 0; near[o] = tex[o]; queue[qt++] = o }
        while (qh < qt) {
            val o = queue[qh++]
            val x = o % size; val y = o / size
            val nd = min(32000, dist[o] + 1).toShort()
            for (dir in 0 until 4) {
                val xx = x + DX[dir]; val yy = y + DY[dir]
                if (xx < 0 || yy < 0 || xx >= size || yy >= size) continue
                val q = yy * size + xx
                if (state[q].toInt() != 1 || dist[q] >= 0 || kind[q] != kind[o]) continue
                dist[q] = nd; near[q] = near[o]; queue[qt++] = q
            }
        }
        var inpainted = 0; var fallback = 0
        for (o in 0 until n2) {
            if (state[o].toInt() != 1) continue
            if (dist[o] < 0) { fallback++; continue }
            inpainted++
            val t = min(1.0, dist[o].toDouble() / FADE_TEXELS)
            tex[o] = mixKeepChroma(near[o], tex[o], t)
        }
        // smooth the base (box 13×13 within the same kind, covered texels included as sources): no Voronoi streaks
        val r = 6
        val hsum = near    // reused: horizontal pass result
        for (y in 0 until size) for (x in 0 until size) {
            val o = y * size + x
            if (state[o].toInt() != 1 && state[o].toInt() != 2) continue
            var sr = 0; var sg = 0; var sb = 0; var n = 0
            val k0 = kind[o]
            for (d in -r..r) {
                val xx = x + d
                if (xx < 0 || xx >= size) continue
                val q = y * size + xx
                val s = state[q].toInt()
                if ((s != 1 && s != 2) || kind[q] != k0) continue
                val c = tex[q]; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255; n++
            }
            hsum[o] = (0xFF shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
        }
        for (y in 0 until size) for (x in 0 until size) {
            val o = y * size + x
            if (state[o].toInt() != 1) continue
            var sr = 0; var sg = 0; var sb = 0; var n = 0
            val k0 = kind[o]
            for (d in -r..r) {
                val yy = y + d
                if (yy < 0 || yy >= size) continue
                val q = yy * size + x
                val s = state[q].toInt()
                if ((s != 1 && s != 2) || kind[q] != k0) continue
                val c = hsum[q]; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255; n++
            }
            if (n > 0) queue[o] = (0xFF shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n) else queue[o] = tex[o]
        }
        for (o in 0 until n2) if (state[o].toInt() == 1) tex[o] = queue[o]
        // detail banks: skin from covered atlas blocks (else skin patches of the photos), hair from the photos' hair
        val t = PATCH
        val skinBank = ArrayList<Patch>()
        var by = 0
        while (by + t <= size && skinBank.size < 64) {
            var bx = 0
            while (bx + t <= size && skinBank.size < 64) {
                var ok = true
                loop@ for (yy in by until by + t step 4) for (xx in bx until bx + t step 4) {
                    val q = yy * size + xx
                    if (state[q].toInt() != 2 || kind[q].toInt() != 0) { ok = false; break@loop }
                }
                if (ok) {
                    val x0 = bx; val y0 = by
                    if ((0 until t).all { yy -> (0 until t).all { xx -> state[(y0 + yy) * size + x0 + xx].toInt() == 2 } })
                        planeResidual { xx, yy -> tex[(y0 + yy) * size + x0 + xx] }?.let { skinBank += it }
                }
                bx += t
            }
            by += t
        }
        if (skinBank.isEmpty()) skinBank += photoPatches(photos, SegClass.SKIN_BITS)
        val hairBank = photoPatches(photos, SegClass.HAIR_BITS)
        fun detail(bank: List<Patch>, x: Int, y: Int, k: Int, out: DoubleArray): Boolean {
            if (bank.isEmpty()) return false
            out[0] = 0.0; out[1] = 0.0; out[2] = 0.0
            var ws = 0.0
            for (shift in 0..1) {
                val sx = x + shift * t / 2; val sy = y + shift * t / 2
                val tx = sx / t; val ty = sy / t
                val ux = sx % t; val uy = sy % t
                // triangular weight: 1 at the tile centre, 0 at its edges (the other tiling covers the edges)
                val w = (1 - abs(2.0 * (ux + 0.5) / t - 1)) * (1 - abs(2.0 * (uy + 0.5) / t - 1)) + 1e-3
                val p = bank[hash(tx, ty, k * 2 + shift) % bank.size].d
                val i = 3 * (uy * t + ux)
                out[0] += p[i] * w; out[1] += p[i + 1] * w; out[2] += p[i + 2] * w
                ws += w
            }
            out[0] /= ws; out[1] /= ws; out[2] /= ws
            return true
        }
        val dd = DoubleArray(3)
        for (o in 0 until n2) {
            if (state[o].toInt() != 1) continue
            val x = o % size; val y = o / size
            val k = kind[o].toInt()
            val c = tex[o]
            var rr = ((c shr 16) and 255).toDouble(); var gg = ((c shr 8) and 255).toDouble(); var bb = (c and 255).toDouble()
            val bank = if (k == 2) hairBank else skinBank
            if (detail(bank, x, y, k, dd)) {
                val amp = if (k == 2) 1.0 else 0.8
                rr += (dd[0] * amp).coerceIn(-40.0, 40.0); gg += (dd[1] * amp).coerceIn(-40.0, 40.0); bb += (dd[2] * amp).coerceIn(-40.0, 40.0)
            }
            // mild luminance noise, so nothing is flat even without any patch
            val nz = (hash(x, y, 7) % 7 - 3).toDouble()
            tex[o] = rgb(rr + nz, gg + nz, bb + nz)
        }
        return inpainted to fallback
    }

    /**
     * Mix of two colours that keeps the interpolated chroma: a plain mix between two different hues passes through grey
     * (e.g. a blue photo colour fading into dark brown hair); here the hue turns but the colour never goes grey.
     */
    internal fun mixKeepChroma(a: Int, b: Int, t: Double): Int {
        val ca = doubleArrayOf(((a shr 16) and 255).toDouble(), ((a shr 8) and 255).toDouble(), (a and 255).toDouble())
        val cb = doubleArrayOf(((b shr 16) and 255).toDouble(), ((b shr 8) and 255).toDouble(), (b and 255).toDouble())
        val m = DoubleArray(3) { ca[it] * (1 - t) + cb[it] * t }
        fun chroma(c: DoubleArray) = c.max() - c.min()
        val want = chroma(ca) * (1 - t) + chroma(cb) * t
        val have = chroma(m)
        if (have > 1e-6 && have < want) {
            val lum = m.average(); val k = want / have
            for (i in 0 until 3) m[i] = lum + (m[i] - lum) * k
        } else if (have <= 1e-6 && want > 0) {
            // exactly grey in the middle: take the hue of the closer end
            val src = if (t < 0.5) ca else cb; val lum = m.average(); val sl = src.average(); val sc = chroma(src).takeIf { it > 0 } ?: 1.0
            for (i in 0 until 3) m[i] = lum + (src[i] - sl) * want / sc
        }
        return rgb(m[0], m[1], m[2])
    }

    private const val SHARPEN = 0.6
    private val SHARP = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    private val DX = intArrayOf(1, -1, 0, 0)
    private val DY = intArrayOf(0, 0, 1, -1)
    private val GUTTER = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, -1 to -1, 1 to -1, -1 to 1)

    internal fun rgb(r: Double, g: Double, b: Double) =
        (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
}
