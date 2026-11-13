package com.drishtisaathi.engine.tryon3d

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Full-head template (skull, ears, back of head, neck) in MediaPipe canonical face space, cm.
 *
 * Source: MakeHuman base mesh hm08, CC0 1.0 (see tools/head_template.py and docs/ARCHITECTURE.md). Fitted offline so
 * the 468 canonical landmarks lie on its surface; [landmarkTriangles]/[landmarkBary] say where (template point ←
 * landmark), [region] tags every vertex (face, ear, scalp, other skin), [earRest] is where a temple rests on each ear.
 */
class HeadTemplate(
    val vertices: FloatArray,
    val triangles: IntArray,
    val uvs: FloatArray,
    val region: IntArray,
    val landmarkTriangles: IntArray,
    val landmarkBary: FloatArray,
    val skullAnchors: IntArray,
    /** Subject's right ear rest (x, y, z), then the left. */
    val earRest: FloatArray,
) {
    val vertexCount get() = vertices.size / 3

    /** The 468 landmark points on the template surface for vertex positions [v] (default: the template's own). */
    fun landmarkPoints(v: FloatArray = vertices): DoubleArray {
        val out = DoubleArray(468 * 3)
        for (i in 0 until 468) {
            val t = landmarkTriangles[i]
            for (c in 0 until 3) {
                var s = 0.0
                for (k in 0 until 3) s += landmarkBary[3 * i + k] * v[3 * triangles[3 * t + k] + c]
                out[3 * i + c] = s
            }
        }
        return out
    }

    companion object {
        const val FACE = 0
        const val EAR = 1
        const val SCALP = 2
        const val SKIN = 3

        fun parse(text: String): HeadTemplate {
            val j = Json.parseToJsonElement(text).jsonObject
            fun f(k: String) = j[k]!!.jsonArray.let { a -> FloatArray(a.size) { a[it].jsonPrimitive.content.toFloat() } }
            fun i(k: String) = j[k]!!.jsonArray.let { a -> IntArray(a.size) { a[it].jsonPrimitive.content.toInt() } }
            return HeadTemplate(f("vertices"), i("triangles"), f("uvs"), i("region"), i("landmarkTriangles"), f("landmarkBary"),
                i("skullAnchors"), f("earRest")).also {
                require(it.landmarkTriangles.size == 468 && it.region.size == it.vertexCount && it.uvs.size == 2 * it.vertexCount)
            }
        }
    }
}

/**
 * Radial-basis-function interpolant φ(r) = r with an affine part (the 3D biharmonic spline): exact at the centres,
 * smooth everywhere, affine far away. Several value columns share one factorisation.
 */
class Rbf private constructor(private val c: DoubleArray, private val w: Array<DoubleArray>, private val aff: Array<DoubleArray>, val columns: Int) {
    val n get() = c.size / 3

    fun eval(x: Double, y: Double, z: Double, out: DoubleArray) {
        for (m in 0 until columns) out[m] = aff[0][m] + aff[1][m] * x + aff[2][m] * y + aff[3][m] * z
        for (i in 0 until n) {
            val dx = x - c[3 * i]; val dy = y - c[3 * i + 1]; val dz = z - c[3 * i + 2]
            val r = sqrt(dx * dx + dy * dy + dz * dz)
            val wi = w[i]
            for (m in 0 until columns) out[m] += wi[m] * r
        }
    }

    /** LU factorisation of the RBF system for [centres], to solve for any number of value columns. */
    class System(val centres: DoubleArray) {
        private val n = centres.size / 3
        private val size = n + 4
        private val lu = Array(size) { DoubleArray(size) }
        private val piv = IntArray(size)

        init {
            for (i in 0 until n) {
                for (j in 0 until n) {
                    val dx = centres[3 * i] - centres[3 * j]; val dy = centres[3 * i + 1] - centres[3 * j + 1]; val dz = centres[3 * i + 2] - centres[3 * j + 2]
                    lu[i][j] = sqrt(dx * dx + dy * dy + dz * dz)
                }
                lu[i][i] += 1e-9
                lu[i][n] = 1.0; lu[n][i] = 1.0
                for (k in 0 until 3) { lu[i][n + 1 + k] = centres[3 * i + k]; lu[n + 1 + k][i] = centres[3 * i + k] }
            }
            for (k in 0 until size) {
                var p = k
                for (i in k + 1 until size) if (abs(lu[i][k]) > abs(lu[p][k])) p = i
                require(abs(lu[p][k]) > 1e-12) { "singular RBF system" }
                piv[k] = p
                if (p != k) { val t = lu[p]; lu[p] = lu[k]; lu[k] = t }
                val d = lu[k][k]
                for (i in k + 1 until size) {
                    val f = lu[i][k] / d
                    lu[i][k] = f
                    if (f != 0.0) { val ri = lu[i]; val rk = lu[k]; for (j in k + 1 until size) ri[j] -= f * rk[j] }
                }
            }
        }

        /** [values]: n rows of [columns] values at the centres. */
        fun solve(values: Array<DoubleArray>, columns: Int): Rbf {
            val x = Array(size) { i -> if (i < n) values[i].copyOf(columns) else DoubleArray(columns) }
            for (k in 0 until size) { val p = piv[k]; if (p != k) { val t = x[p]; x[p] = x[k]; x[k] = t } }
            for (i in 0 until size) for (j in 0 until i) { val f = lu[i][j]; if (f != 0.0) for (m in 0 until columns) x[i][m] -= f * x[j][m] }
            for (i in size - 1 downTo 0) {
                for (j in i + 1 until size) { val f = lu[i][j]; if (f != 0.0) for (m in 0 until columns) x[i][m] -= f * x[j][m] }
                for (m in 0 until columns) x[i][m] /= lu[i][i]
            }
            return Rbf(centres, Array(n) { x[it] }, Array(4) { x[n + it] }, columns)
        }
    }
}

/**
 * Deforms the [HeadTemplate] to a customer's face:
 *  1. per-axis scale about the face centre so the head is proportional to the face: width from the 234–454 face width,
 *     height from forehead–chin (10–152), depth from nose tip to ear points (measured mostly in the side views);
 *  2. an RBF displacement that carries the template's landmark points exactly onto the customer's averaged landmarks,
 *     pinned to zero at skull anchors, so skull, ears, back of head and neck stay smooth and proportional.
 * Extra value columns (e.g. per-photo image corrections) can ride on the same RBF.
 */
object HeadDeform {
    class Result(
        /** Deformed vertices (cm, canonical axes). */
        val vertices: FloatArray,
        /** Extra columns interpolated at every vertex: [vertex][column]. */
        val extra: Array<DoubleArray>,
        val earRestR: V3,
        val earRestL: V3,
    )

    private fun p(a: FloatArray, i: Int) = V3(a[3 * i].toDouble(), a[3 * i + 1].toDouble(), a[3 * i + 2].toDouble())

    /** Per-axis scale (x, y, z) of [target] landmarks against [canonical] ones. */
    fun axisScales(canonical: FloatArray, target: FloatArray): DoubleArray {
        fun width(a: FloatArray) = abs(a[3 * 454] - a[3 * 234]).toDouble()
        fun height(a: FloatArray) = abs(a[3 * 10 + 1] - a[3 * 152 + 1]).toDouble()
        fun depth(a: FloatArray) = abs(a[3 * 1 + 2] - (a[3 * 234 + 2] + a[3 * 454 + 2]) / 2.0)
        return doubleArrayOf(
            (width(target) / width(canonical)).coerceIn(0.8, 1.25),
            (height(target) / height(canonical)).coerceIn(0.8, 1.25),
            (depth(target) / depth(canonical)).coerceIn(0.8, 1.25),
        )
    }

    /**
     * [canonical] 468×3 canonical landmarks; [target] 468×3 the customer's averaged landmarks (same space, cm).
     * [extraAtLandmarks] optional 468 rows of extra values to interpolate (zero at the skull anchors).
     */
    fun deform(t: HeadTemplate, canonical: FloatArray, target: FloatArray, extraAtLandmarks: Array<DoubleArray>? = null, refine: Int = 3): Result {
        val s = axisScales(canonical, target)
        var cc = V3(0.0, 0.0, 0.0); var ct = V3(0.0, 0.0, 0.0)
        for (i in 0 until 468) { cc += p(canonical, i); ct += p(target, i) }
        cc *= 1.0 / 468; ct *= 1.0 / 468
        val nv = t.vertexCount
        // 1. proportional head: per-axis scale about the face centre
        val g = FloatArray(nv * 3)
        for (i in 0 until nv) {
            g[3 * i] = (ct.x + s[0] * (t.vertices[3 * i] - cc.x)).toFloat()
            g[3 * i + 1] = (ct.y + s[1] * (t.vertices[3 * i + 1] - cc.y)).toFloat()
            g[3 * i + 2] = (ct.z + s[2] * (t.vertices[3 * i + 2] - cc.z)).toFloat()
        }
        // 2. RBF from the template's landmark points (after the scale) to the targets, zero at the skull anchors
        val lp = t.landmarkPoints(g)
        val na = t.skullAnchors.size
        val centres = DoubleArray((468 + na) * 3)
        System.arraycopy(lp, 0, centres, 0, 468 * 3)
        t.skullAnchors.forEachIndexed { k, vi -> for (c in 0 until 3) centres[3 * (468 + k) + c] = g[3 * vi + c].toDouble() }
        val sys = Rbf.System(centres)
        val ne = extraAtLandmarks?.firstOrNull()?.size ?: 0
        val cols = 3 + ne
        val values = Array(468 + na) { i ->
            DoubleArray(cols).also { r ->
                if (i < 468) {
                    for (c in 0 until 3) r[c] = target[3 * i + c] - lp[3 * i + c]
                    for (e in 0 until ne) r[3 + e] = extraAtLandmarks!![i][e]
                }
            }
        }
        val rbf = sys.solve(values, cols)
        val out = FloatArray(nv * 3)
        val extra = Array(nv) { DoubleArray(ne) }
        val tmp = DoubleArray(cols)
        for (i in 0 until nv) {
            rbf.eval(g[3 * i].toDouble(), g[3 * i + 1].toDouble(), g[3 * i + 2].toDouble(), tmp)
            for (c in 0 until 3) out[3 * i + c] = (g[3 * i + c] + tmp[c]).toFloat()
            for (e in 0 until ne) extra[i][e] = tmp[3 + e]
        }
        // the landmark points are interpolated linearly inside their triangles; a few corrective passes on the same
        // factorisation make them land on the targets
        repeat(refine) {
            val now = t.landmarkPoints(out)
            val corr = sys.solve(Array(468 + na) { i -> DoubleArray(3).also { r -> if (i < 468) for (c in 0 until 3) r[c] = target[3 * i + c] - now[3 * i + c] } }, 3)
            val d = DoubleArray(3)
            for (i in 0 until nv) {
                corr.eval(g[3 * i].toDouble(), g[3 * i + 1].toDouble(), g[3 * i + 2].toDouble(), d)
                for (c in 0 until 3) out[3 * i + c] = (out[3 * i + c] + d[c]).toFloat()
            }
        }
        // ear rest points ride on the same deformation (scale + the RBF at that point)
        fun rest(k: Int): V3 {
            val x = ct.x + s[0] * (t.earRest[3 * k] - cc.x); val y = ct.y + s[1] * (t.earRest[3 * k + 1] - cc.y); val z = ct.z + s[2] * (t.earRest[3 * k + 2] - cc.z)
            rbf.eval(x, y, z, tmp)
            return V3(x + tmp[0], y + tmp[1], z + tmp[2])
        }
        return Result(out, extra, rest(0), rest(1))
    }
}
