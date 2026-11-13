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

