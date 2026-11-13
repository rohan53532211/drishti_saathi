package com.drishtisaathi.engine.tryon3d

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

/**
 * MediaPipe selfie multiclass segmentation classes (selfie_multiclass_256x256: background, hair, body-skin, face-skin,
 * clothes, others). Bit sets of classes are used to say which pixels may colour which part of the head.
 */
object SegClass {
    const val BACKGROUND = 0
    const val HAIR = 1
    const val BODY_SKIN = 2
    const val FACE_SKIN = 3
    const val CLOTHES = 4
    const val OTHERS = 5
    const val SKIN_BITS = (1 shl BODY_SKIN) or (1 shl FACE_SKIN)
    const val HAIR_BITS = 1 shl HAIR

    fun allowed(cls: Int, bits: Int) = cls in 0..7 && (bits shr cls) and 1 == 1
}

/** Small mesh helpers shared by the head avatar: welded vertices (UV seam duplicates), adjacency, normals. */
object HeadMesh {
    /** For every vertex the first vertex at the same position (seam duplicates share one). */
    fun weld(v: FloatArray): IntArray {
        val first = HashMap<Triple<Float, Float, Float>, Int>()
        return IntArray(v.size / 3) { i -> first.getOrPut(Triple(v[3 * i], v[3 * i + 1], v[3 * i + 2])) { i } }
    }

    /** Neighbours of every welded vertex (indices are welded representatives). */
    fun adjacency(tris: IntArray, weld: IntArray, n: Int): Array<IntArray> {
        val s = Array(n) { HashSet<Int>() }
        for (t in tris.indices step 3) for (k in 0 until 3) {
            val a = weld[tris[t + k]]; val b = weld[tris[t + (k + 1) % 3]]
            if (a != b) { s[a] += b; s[b] += a }
        }
        return Array(n) { s[it].toIntArray() }
    }

    /** Area-weighted vertex normals, the same for seam duplicates (no lighting crease at the seam). */
    fun normals(v: FloatArray, tris: IntArray, weld: IntArray): FloatArray {
        val n = FloatArray(v.size)
        for (t in tris.indices step 3) {
            val a = 3 * tris[t]; val b = 3 * tris[t + 1]; val c = 3 * tris[t + 2]
            val ux = v[b] - v[a]; val uy = v[b + 1] - v[a + 1]; val uz = v[b + 2] - v[a + 2]
            val wx = v[c] - v[a]; val wy = v[c + 1] - v[a + 1]; val wz = v[c + 2] - v[a + 2]
            val nx = uy * wz - uz * wy; val ny = uz * wx - ux * wz; val nz = ux * wy - uy * wx
            for (i in intArrayOf(tris[t], tris[t + 1], tris[t + 2])) {
                val r = 3 * weld[i]
                n[r] += nx; n[r + 1] += ny; n[r + 2] += nz
            }
        }
        for (i in 0 until v.size / 3) {
            val r = 3 * weld[i]
            val l = sqrt(n[r] * n[r] + n[r + 1] * n[r + 1] + n[r + 2] * n[r + 2]).takeIf { it > 1e-12f } ?: 1f
            if (r == 3 * i) { n[r] /= l; n[r + 1] /= l; n[r + 2] /= l }
        }
        for (i in 0 until v.size / 3) { val r = 3 * weld[i]; if (r != 3 * i) { n[3 * i] = n[r]; n[3 * i + 1] = n[r + 1]; n[3 * i + 2] = n[r + 2] } }
        return n
    }

    /** Triangles whose normal points against the template's (folded by a deformation). */
    fun flipped(ref: FloatArray, v: FloatArray, tris: IntArray): List<Int> {
        fun nrm(p: FloatArray, t: Int): DoubleArray {
            val a = 3 * tris[t]; val b = 3 * tris[t + 1]; val c = 3 * tris[t + 2]
            val ux = (p[b] - p[a]).toDouble(); val uy = (p[b + 1] - p[a + 1]).toDouble(); val uz = (p[b + 2] - p[a + 2]).toDouble()
            val wx = (p[c] - p[a]).toDouble(); val wy = (p[c + 1] - p[a + 1]).toDouble(); val wz = (p[c + 2] - p[a + 2]).toDouble()
            return doubleArrayOf(uy * wz - uz * wy, uz * wx - ux * wz, ux * wy - uy * wx)
        }
        return (tris.indices step 3).filter { t ->
            val p = nrm(ref, t); val q = nrm(v, t)
            p[0] * q[0] + p[1] * q[1] + p[2] * q[2] < 0
        }
    }

    /**
     * Unfolds a deformed mesh: vertices of folded triangles (outside the face, which must stay on the landmarks) are
     * moved towards their neighbours' mean until no triangle is folded (or [iterations] run out). Returns the folds left.
     */
    fun unfold(ref: FloatArray, v: FloatArray, tris: IntArray, weld: IntArray, movable: (Int) -> Boolean, iterations: Int = 30): Int {
        val n = v.size / 3
        val adj = adjacency(tris, weld, n)
        repeat(iterations) {
            val bad = flipped(ref, v, tris)
            if (bad.isEmpty()) return 0
            val ring = HashSet<Int>()
            for (t in bad) for (k in 0 until 3) { val r = weld[tris[t + k]]; ring += r; ring.addAll(adj[r].toList()) }
            for (r in ring) {
                if (!movable(r) || adj[r].isEmpty()) continue
                var mx = 0f; var my = 0f; var mz = 0f
                for (o in adj[r]) { mx += v[3 * o]; my += v[3 * o + 1]; mz += v[3 * o + 2] }
                val c = 1f / adj[r].size
                v[3 * r] = 0.5f * v[3 * r] + 0.5f * mx * c; v[3 * r + 1] = 0.5f * v[3 * r + 1] + 0.5f * my * c; v[3 * r + 2] = 0.5f * v[3 * r + 2] + 0.5f * mz * c
            }
            copyWelded(v, weld)
        }
        return flipped(ref, v, tris).size
    }

    fun copyWelded(v: FloatArray, weld: IntArray) {
        for (i in 0 until v.size / 3) { val r = weld[i]; if (r != i) { v[3 * i] = v[3 * r]; v[3 * i + 1] = v[3 * r + 1]; v[3 * i + 2] = v[3 * r + 2] } }
    }

    /** Every edge (by position) used by exactly two triangles in opposite directions: closed, consistently oriented. */
    fun watertight(v: FloatArray, tris: IntArray): Boolean {
        val weld = weld(v)
        val count = HashMap<Long, Int>()
        for (t in tris.indices step 3) for (k in 0 until 3) {
            val a = weld[tris[t + k]].toLong(); val b = weld[tris[t + (k + 1) % 3]].toLong()
            if (a == b) return false
            count.merge((a shl 32) or b, 1, Int::plus)
        }
        return count.all { (key, c) -> c == 1 && count[((key and 0xFFFFFFFFL) shl 32) or (key ushr 32)] == 1 }
    }
}

/**
 * The hair: a smooth "hairness" over the template (1 on the scalp, falling to 0 across one ring at the hairline) and
 * the hair volume. Each scalp vertex is pushed out along its normal until its projection leaves the hair mask in
 * the photos that see it side-on (the hair outline); the largest extent of the views is kept, spikes are removed
 * with a median, the offsets are smoothed, tapered to 0 at the hairline and capped at [MAX_CM].
 */
object HairShell {
    const val MAX_CM = 8.0
    const val STEP_CM = 0.25
    /** A view measures the outline only where it sees the surface side-on (|cos| below this). */
    const val SIDE_ON = 0.6
    /** The hair volume fades in linearly over this distance (cm, along the surface) from the hairline. */
    const val TAPER_CM = 6.0

    fun hairness(region: IntArray, tris: IntArray, weld: IntArray, rounds: Int = 2): FloatArray {
        val n = region.size
        val adj = HeadMesh.adjacency(tris, weld, n)
        var h = FloatArray(n) { if (region[weld[it]] == HeadTemplate.SCALP) 1f else 0f }
        repeat(rounds) {
            h = FloatArray(n) { i ->
                val r = weld[i]
                if (adj[r].isEmpty()) h[r] else 0.5f * h[r] + 0.5f * adj[r].map { h[it] }.average().toFloat()
            }
        }
        // hair stays hair inside the scalp: only the band at the hairline is soft
        for (i in 0 until n) if (region[weld[i]] == HeadTemplate.SCALP) h[i] = max(h[i], 0.6f)
        for (i in 0 until n) h[i] = h[weld[i]]
        return h
    }

    /**
     * Offsets (cm) per vertex. [protect] 0..1 per vertex scales the offset down (where the temples pass).
     * No class masks → no volume (all 0).
     */
    fun offsets(
        v: FloatArray, tris: IntArray, weld: IntArray, hair: FloatArray, views: List<AvatarView>, poses: List<FloatArray>,
        photos: List<Pixels>, cams: List<V3>, protect: FloatArray? = null,
    ): FloatArray {
        val n = v.size / 3
        val out = FloatArray(n)
        if (photos.none { it.hasClasses }) return out
        val nrm = HeadMesh.normals(v, tris, weld)
        val adj = HeadMesh.adjacency(tris, weld, n)
        val ext = DoubleArray(n) { -1.0 }
        val tmp = DoubleArray(2)
        val reps = (0 until n).filter { weld[it] == it && hair[it] > 0.5f }
        for (i in reps) {
            val p = V3(v[3 * i].toDouble(), v[3 * i + 1].toDouble(), v[3 * i + 2].toDouble())
            val nv = V3(nrm[3 * i].toDouble(), nrm[3 * i + 1].toDouble(), nrm[3 * i + 2].toDouble())
            for (k in views.indices) {
                if (!photos[k].hasClasses) continue
                val d = cams[k] - p
                val c = (nv.x * d.x + nv.y * d.y + nv.z * d.z) / max(d.length, 1e-9)
                if (c < 0 || c > SIDE_ON) continue          // facing away, or the walk would run along the view ray
                var last = -1.0
                var t = 0.0
                while (t <= MAX_CM + 1e-9) {
                    val q = p + nv * t
                    AvatarBuilder.project(poses[k], views[k].focalPx, views[k].imageW, views[k].imageH, q.x, q.y, q.z, tmp)
                    val cls = photos[k].classAtFull(tmp[0] * views[k].imageW, tmp[1] * views[k].imageH)
                    if (cls < 0) break                      // left the photo: keep what was measured
                    if (cls == SegClass.HAIR) last = t
                    else if (t - max(last, 0.0) > 0.5) break // past the outline (a 5 mm gap is tolerated)
                    t += STEP_CM
                }
                if (last >= 0) ext[i] = max(ext[i], last)
                else if (ext[i] < 0) ext[i] = 0.0
            }
        }
        // vertices no view measured: from their neighbours
        repeat(40) {
            var open = false
            for (i in reps) if (ext[i] < 0) {
                val known = adj[i].filter { ext[it] >= 0 }
                if (known.isNotEmpty()) ext[i] = known.map { ext[it] }.average() else open = true
            }
            if (!open) return@repeat
        }
        for (i in reps) if (ext[i] < 0) ext[i] = 0.0
        // consistent: a median over each vertex and its neighbours removes single-view spikes, then smoothing
        var o = DoubleArray(n) { if (weld[it] == it && hair[it] > 0.5f) min(ext[it], MAX_CM) else 0.0 }
        o = DoubleArray(n) { i -> if (weld[i] != i || hair[i] <= 0.5f) 0.0 else (adj[i].map { o[it] } + o[i]).sorted().let { it[it.size / 2] } }
        // distance along the surface from the hairline (cm): the volume fades in linearly over TAPER_CM
        val dist = DoubleArray(n) { Double.MAX_VALUE }
        val pq = java.util.PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        for (i in 0 until n) if (weld[i] == i && hair[i] <= 0.5f) { dist[i] = 0.0; pq.add(0.0 to i) }
        while (pq.isNotEmpty()) {
            val (d, i) = pq.poll()
            if (d > dist[i]) continue
            for (j in adj[i]) {
                val dx = v[3 * i] - v[3 * j]; val dy = v[3 * i + 1] - v[3 * j + 1]; val dz = v[3 * i + 2] - v[3 * j + 2]
                val nd = d + sqrt((dx * dx + dy * dy + dz * dz).toDouble())
                if (nd < dist[j]) { dist[j] = nd; pq.add(nd to j) }
            }
        }
        fun taper(i: Int) = min(1.0, dist[i] / TAPER_CM)
        for (i in 0 until n) if (weld[i] == i) o[i] *= (protect?.get(i) ?: 1f)
        repeat(60) {
            o = DoubleArray(n) { i ->
                if (weld[i] != i || hair[i] <= 0.5f || adj[i].isEmpty()) 0.0 else 0.5 * o[i] + 0.5 * adj[i].map { o[it] }.average()
            }
        }
        // faded in from the hairline, then smoothed once more so the fade has no kinks
        o = DoubleArray(n) { i -> if (weld[i] == i) o[i] * taper(i) else 0.0 }
        repeat(40) {
            o = DoubleArray(n) { i ->
                if (weld[i] != i || hair[i] <= 0.5f || adj[i].isEmpty()) 0.0 else 0.5 * o[i] + 0.5 * adj[i].map { o[it] }.average()
            }
        }
        for (i in 0 until n) {
            val r = weld[i]
            // the fade once more (smoothing pulls volume towards the hairline), capped, never above the temple band's limit
            out[i] = min(o[r] * taper(r), MAX_CM * (protect?.get(r) ?: 1f)).coerceIn(0.0, MAX_CM).toFloat()
        }
        return out
    }

    /** 1 everywhere except a band where the temples run from the face to the ears (scaled down to [keep]). */
    fun templeProtect(v: FloatArray, rest: List<V3>, keep: Float = 0.15f): FloatArray = FloatArray(v.size / 3) { i ->
        val x = v[3 * i].toDouble(); val y = v[3 * i + 1].toDouble(); val z = v[3 * i + 2].toDouble()
        var f = 1f
        for (r in rest) {
            if (x * r.x <= 0) continue                     // other side
            val dy = abs(y - r.y)
            // full protection within 1 cm of the temple line and in front of the ear root, fading over 2.5 cm
            val wy = ((3.5 - dy) / 2.5).coerceIn(0.0, 1.0)
            val wz = ((z - (r.z - 3.5)) / 2.5).coerceIn(0.0, 1.0)
            val w = (wy * wz).toFloat()
            if (w <= 0f) continue
            f = min(f, 1f - (1f - keep) * w)
        }
        f
    }

    fun displace(v: FloatArray, tris: IntArray, weld: IntArray, off: FloatArray) {
        val nrm = HeadMesh.normals(v, tris, weld)
        for (i in 0 until v.size / 3) for (c in 0 until 3) v[3 * i + c] += nrm[3 * i + c] * off[i]
    }
}

/**
 * The neck's width (front photo) and depth (side photos) from the body-skin silhouette, at a level below the chin,
 * and its skin colour. Width in face-space cm: pixels × depth / focal; depth from a side view at yaw θ from its
 * apparent width a = √((W cos θ)² + (D sin θ)²).
 */
object NeckFit {
    class Size(val widthCm: Double?, val depthCm: Double?, val rgb: IntArray?)

    fun measure(centre: V3, views: List<AvatarView>, poses: List<FloatArray>, photos: List<Pixels>, cams: List<V3>): Size {
        var w: Double? = null
        val sides = ArrayList<Pair<Double, Double>>()   // (apparent width, yaw)
        var rgb: IntArray? = null
        val tmp = DoubleArray(2)
        for (k in views.indices) {
            val px = photos[k]
            if (!px.hasClasses) continue
            val v = views[k]
            AvatarBuilder.project(poses[k], v.focalPx, v.imageW, v.imageH, centre.x, centre.y, centre.z, tmp)
            val q = Mat4.apply(poses[k], centre.x, centre.y, centre.z)
            val depth = -q[2]
            val run = px.skinRunFull(tmp[0] * v.imageW, tmp[1] * v.imageH) ?: continue
            val cm = run.first * depth / v.focalPx
            val yaw = atan2(cams[k].x - centre.x, cams[k].z - centre.z)
            if (v.step == AvatarStep.FRONT) { w = cm; rgb = run.second } else sides += cm to yaw
        }
        val width = w
        val depth = if (width == null || sides.isEmpty()) null else sides.mapNotNull { (a, th) ->
            val s = abs(sin(th)); val c = abs(cos(th))
            if (s < 0.3) null else sqrt(max(0.0, a * a - (width * c) * (width * c))) / s
        }.takeIf { it.isNotEmpty() }?.average()
        return Size(width, depth, rgb)
    }

    /**
     * Scales the neck (vertices of [neck] weight > 0, 0 just under the jaw rising to 1 lower down) in x and z about
     * its axis so it has the measured width/depth (each ratio clamped to 0.75–1.35).
     */
    fun apply(v: FloatArray, neckWeight: FloatArray, centre: V3, size: Size, templateWidth: Double, templateDepth: Double) {
        val sx = size.widthCm?.let { (it / templateWidth).coerceIn(0.75, 1.35) } ?: 1.0
        val sz = size.depthCm?.let { (it / templateDepth).coerceIn(0.75, 1.35) } ?: 1.0
        for (i in 0 until v.size / 3) {
            val w = neckWeight[i]
            if (w <= 0f) continue
            v[3 * i] = (centre.x + (v[3 * i] - centre.x) * (1 + (sx - 1) * w)).toFloat()
            v[3 * i + 2] = (centre.z + (v[3 * i + 2] - centre.z) * (1 + (sz - 1) * w)).toFloat()
        }
    }
}

/**
 * Texture bake for the head avatar ([AvatarBuilder.TEX]² sRGB atlas):
 *  - a texel takes colour only from photo pixels of the right class (face / ear / neck: face- or body-skin; scalp:
 *    hair; both in the soft band at the hairline), never background or clothes; with no class mask every pixel counts;
 *  - only from views that actually see the point (per-view depth buffer: no pixels of the jaw on the neck behind it)
 *    and within the per-vertex angle limit;
 *  - views blend by facing⁴ × class coverage of a 3×3 neighbourhood, which feathers seams over 2–3 texels; side
 *    photos carry their white-balance gains;
 *  - fill: texels no photo covers are inpainted from covered neighbours (same kind: skin or hair), the rest take the
 *    skin / neck tone or the hair colour with a soft normal shading. Every texel is opaque and coloured.
 */
object HeadBake {
    class Result(val texture: IntArray, val covered: Int, val inpainted: Int, val fallback: Int, val gutter: Int)

    private const val Z_GRID = 384
    /** A point counts as seen when it is at most this far (cm) behind the nearest surface at its pixel. */
    private const val Z_EPS = 0.6

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

    fun bake(
        verts: FloatArray, tris: IntArray, uvs: FloatArray, weld: IntArray, views: List<AvatarView>, photos: List<Pixels>,
        cams: List<V3>, baseW: List<Double>, imagePts: List<FloatArray>, depths: List<FloatArray>,
        hair: FloatArray, neck: FloatArray, cosCut: FloatArray,
        skinRgb: IntArray, neckRgb: IntArray, hairRgb: IntArray, gains: List<DoubleArray>, size: Int = AvatarBuilder.TEX,
    ): Result {
        val n2 = size * size
        val skin = (0xFF shl 24) or (skinRgb[0] shl 16) or (skinRgb[1] shl 8) or skinRgb[2]
        val tex = IntArray(n2) { skin }
        // 0 outside every triangle, 1 not covered by a photo (fallback colour), 2 photo colour, 3 inpainted
        val state = ByteArray(n2)
        val kind = ByteArray(n2)                      // 0 skin, 1 hairline band, 2 hair: filling copies only within a kind
        val nrm = HeadMesh.normals(verts, tris, weld)
        val nv = views.size
        val zb = (0 until nv).map { zbuffer(tris, imagePts[it], depths[it], views[it].imageH.toDouble() / views[it].imageW) }
        val px = DoubleArray(3)
        val light = doubleArrayOf(0.2, 0.8, 0.55).let { l -> val s = sqrt(l.sumOf { it * it }); DoubleArray(3) { l[it] / s } }
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
                val l0 = ((by - cy) * (qx - cx) + (cx - bx) * (qy - cy)) / den
                val l1 = ((cy - ay) * (qx - cx) + (ax - cx) * (qy - cy)) / den
                val l2 = 1 - l0 - l1
                if (l0 < -0.02f || l1 < -0.02f || l2 < -0.02f) continue
                val o = y * size + x
                if (state[o].toInt() == 2) continue     // already coloured by a neighbouring triangle
                val p0 = l0 * verts[3 * ia] + l1 * verts[3 * ib] + l2 * verts[3 * ic]
                val p1 = l0 * verts[3 * ia + 1] + l1 * verts[3 * ib + 1] + l2 * verts[3 * ic + 1]
                val p2 = l0 * verts[3 * ia + 2] + l1 * verts[3 * ib + 2] + l2 * verts[3 * ic + 2]
                var n0 = (l0 * nrm[3 * ia] + l1 * nrm[3 * ib] + l2 * nrm[3 * ic]).toDouble()
                var n1 = (l0 * nrm[3 * ia + 1] + l1 * nrm[3 * ib + 1] + l2 * nrm[3 * ic + 1]).toDouble()
                var n2n = (l0 * nrm[3 * ia + 2] + l1 * nrm[3 * ib + 2] + l2 * nrm[3 * ic + 2]).toDouble()
                val nl = sqrt(n0 * n0 + n1 * n1 + n2n * n2n).takeIf { it > 1e-9 } ?: 1.0
                n0 /= nl; n1 /= nl; n2n /= nl
                val h = (l0 * hair[ia] + l1 * hair[ib] + l2 * hair[ic]).coerceIn(0f, 1f)
                val nk = (l0 * neck[ia] + l1 * neck[ib] + l2 * neck[ic]).coerceIn(0f, 1f)
                val cut = (l0 * cosCut[ia] + l1 * cosCut[ib] + l2 * cosCut[ic]).toDouble()
                val bits = when {
                    h < 0.3f -> SegClass.SKIN_BITS
                    h > 0.75f -> SegClass.HAIR_BITS
                    else -> SegClass.SKIN_BITS or SegClass.HAIR_BITS
                }
                var r = 0.0; var g = 0.0; var b = 0.0; var sw = 0.0
                for (k in 0 until nv) {
                    val dx = cams[k].x - p0; val dy = cams[k].y - p1; val dz = cams[k].z - p2
                    val dl = sqrt(dx * dx + dy * dy + dz * dz)
                    val c = (n0 * dx + n1 * dy + n2n * dz) / dl
                    if (c < cut) continue
                    val q = imagePts[k]
                    val ix = q[2 * ia] * l0 + q[2 * ib] * l1 + q[2 * ic] * l2
                    val iy = q[2 * ia + 1] * l0 + q[2 * ib + 1] * l1 + q[2 * ic + 1] * l2
                    val dk = depths[k]
                    if (!seen(zb[k], ix, iy, dk[ia] * l0 + dk[ib] * l1 + dk[ic] * l2)) continue
                    val fx = ix * views[k].imageW.toDouble(); val fy = iy * views[k].imageH.toDouble()
                    val frac = photos[k].classFractionFull(fx, fy, bits)
                    if (frac <= 0.0) continue
                    if (!photos[k].sampleFull(fx, fy, px)) continue
                    val w = baseW[k] * c.pow(AvatarBuilder.FACING_POWER) * frac
                    val gk = gains[k]
                    r += px[0] * gk[0] * w; g += px[1] * gk[1] * w; b += px[2] * gk[2] * w; sw += w
                }
                kind[o] = (if (h < 0.3f) 0 else if (h > 0.75f) 2 else 1).toByte()
                if (sw > 1e-9) {
                    tex[o] = rgb(r / sw, g / sw, b / sw)
                    state[o] = 2
                } else {
                    // fallback: skin (neck tone on the neck), hair with a soft normal shading, blended at the hairline
                    val sr = skinRgb[0] + (neckRgb[0] - skinRgb[0]) * nk
                    val sg = skinRgb[1] + (neckRgb[1] - skinRgb[1]) * nk
                    val sb = skinRgb[2] + (neckRgb[2] - skinRgb[2]) * nk
                    val shade = 0.78 + 0.22 * max(0.0, n0 * light[0] + n1 * light[1] + n2n * light[2])
                    val hm = ((h - 0.3f) / 0.45f).coerceIn(0f, 1f).toDouble()
                    tex[o] = rgb(sr + (hairRgb[0] * shade - sr) * hm, sg + (hairRgb[1] * shade - sg) * hm, sb + (hairRgb[2] * shade - sb) * hm)
                    state[o] = 1
                }
            }
        }
        val covered = state.count { it.toInt() == 2 }
        // inpaint: uncovered texels take the mean of covered neighbours of the same kind, growing a texel a pass.
        // Only the texels still open are visited, with plain int buffers (fast on the phone).
        var inpainted = 0
        var open = IntArray(n2).let { buf -> var c = 0; for (o in 0 until n2) if (state[o].toInt() == 1) buf[c++] = o; buf.copyOf(c) }
        val doneIdx = IntArray(open.size); val doneCol = IntArray(open.size)
        repeat(24) {
            var nd = 0
            var keep = 0
            for (o in open) {
                val x = o % size; val y = o / size
                var r = 0; var g = 0; var b = 0; var c = 0
                val k0 = kind[o]
                for (oy in -1..1) for (ox in -1..1) {
                    val xx = x + ox; val yy = y + oy
                    if (xx < 0 || yy < 0 || xx >= size || yy >= size) continue
                    val q = yy * size + xx
                    val s = state[q].toInt()
                    if ((s == 2 || s == 3) && kind[q] == k0) {
                        val col = tex[q]; r += (col shr 16) and 255; g += (col shr 8) and 255; b += col and 255; c++
                    }
                }
                if (c > 0) { doneIdx[nd] = o; doneCol[nd] = (0xFF shl 24) or ((r / c) shl 16) or ((g / c) shl 8) or (b / c); nd++ }
                else open[keep++] = o
            }
            if (nd == 0) return@repeat
            for (i in 0 until nd) { tex[doneIdx[i]] = doneCol[i]; state[doneIdx[i]] = 3 }
            inpainted += nd
            open = open.copyOf(keep)
        }
        val fallback = state.count { it.toInt() == 1 }
        // gutter: texels outside the triangles copy their neighbours (no seams under mipmapping / bilinear filtering)
        var gutter = 0
        repeat(4) {
            val add = ArrayList<Pair<Int, Int>>()
            for (o in 0 until n2) {
                if (state[o].toInt() != 0) continue
                val x = o % size; val y = o / size
                for ((ox, oy) in GUTTER) {
                    val xx = x + ox; val yy = y + oy
                    if (xx < 0 || yy < 0 || xx >= size || yy >= size) continue
                    val q = yy * size + xx
                    if (state[q].toInt() != 0) { add += o to tex[q]; break }
                }
            }
            for ((o, col) in add) { tex[o] = col; state[o] = 4 }
            gutter += add.size
        }
        return Result(tex, covered, inpainted, fallback, gutter)
    }

    private val GUTTER = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, -1 to -1, 1 to -1, -1 to 1)

    private fun rgb(r: Double, g: Double, b: Double) =
        (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
}
