package com.drishtisaathi.engine.tryon3d

import com.drishtisaathi.engine.face.Landmark
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Simplified 3D face avatar, built on the phone from ONE front photo: the 468 MediaPipe landmarks on the canonical
 * face-mesh topology, scaled to mm with the PD, the photo projected on as texture, and a smooth skin-coloured
 * shell behind the face outline so it can be turned ±60°. A preview, not a scan (no hair, ears or true back).
 *
 * Coordinates match the frame GLBs: mm, origin on the nose bridge (landmark 6), +X wearer's right, +Y up,
 * +Z towards the back of the head. So a frame's nose rest goes at the origin.
 */
object AvatarMesh {
    /** MediaPipe FACEMESH_FACE_OVAL, in order around the face. */
    val FACE_OVAL = intArrayOf(
        10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377,
        152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109,
    )
    const val NOSE = 6
    const val MESH_POINTS = 468
    const val SHELL_RINGS = 8

    data class Geometry(
        val positions: FloatArray,
        val uvs: FloatArray,
        val indices: IntArray,
        val shellPositions: FloatArray,
        val shellIndices: IntArray,
        /** Image px per mm used for the scale. */
        val pxPerMm: Double,
        val faceWidthMm: Double,
        /** True when the scale came from a default PD, not a measured one. */
        val approximate: Boolean,
    )

    /**
     * @param crop the texture's region of the image, normalised [x0, y0, x1, y1] (the photo is cropped to the face).
     * @param pdMm measured PD (card-calibrated if possible); null uses [defaultPdMm] and marks the result approximate.
     */
    fun geometry(
        landmarks: List<Landmark>, imageW: Int, imageH: Int, triangles: IntArray,
        pdMm: Double?, crop: FloatArray = floatArrayOf(0f, 0f, 1f, 1f), defaultPdMm: Double = 63.0,
    ): Geometry {
        require(landmarks.size >= MESH_POINTS) { "need $MESH_POINTS landmarks" }
        require(triangles.isNotEmpty() && triangles.all { it in 0 until MESH_POINTS }) { "bad triangles" }
        val l = landmarks
        fun px(i: Int) = l[i].x.toDouble() * imageW
        fun py(i: Int) = l[i].y.toDouble() * imageH
        val iris = if (l.size > 473) hypot(px(473) - px(468), py(473) - py(468))
        else hypot(px(263) - px(33), py(263) - py(33)) * 0.7   // no iris points: outer eye corners ≈ 1.43·PD
        val pd = pdMm?.takeIf { it > 0 } ?: defaultPdMm
        val ppm = iris / pd
        val n0 = l[NOSE]
        val pos = FloatArray(MESH_POINTS * 3)
        val uv = FloatArray(MESH_POINTS * 2)
        val cw = (crop[2] - crop[0]).takeIf { it > 0 } ?: 1f
        val ch = (crop[3] - crop[1]).takeIf { it > 0 } ?: 1f
        for (i in 0 until MESH_POINTS) {
            pos[3 * i] = (-(l[i].x - n0.x) * imageW / ppm).toFloat()
            pos[3 * i + 1] = (-(l[i].y - n0.y) * imageH / ppm).toFloat()
            pos[3 * i + 2] = ((l[i].z - n0.z) * imageW / ppm).toFloat()
            uv[2 * i] = (l[i].x - crop[0]) / cw
            uv[2 * i + 1] = (l[i].y - crop[1]) / ch
        }
        // face must face the viewer (−Z): flip the winding if the image was mirrored
        var nz = 0.0
        for (t in triangles.indices step 3) nz += normal(pos, triangles[t], triangles[t + 1], triangles[t + 2])[2]
        val faceIdx = if (nz > 0) flip(triangles) else triangles.copyOf()

        // ---- shell: strip from the face outline to an ellipse ring, then a half ellipsoid to the back ----
        val ov = FACE_OVAL
        var cx = 0.0; var cy = 0.0; var zc = 0.0
        ov.forEach { cx += pos[3 * it]; cy += pos[3 * it + 1]; zc += pos[3 * it + 2] }
        cx /= ov.size; cy /= ov.size; zc /= ov.size
        val rx = ov.maxOf { abs(pos[3 * it] - cx) } * 1.04
        val ry = ov.maxOf { abs(pos[3 * it + 1] - cy) } * 1.06
        val rz = 0.95 * rx
        val z0 = zc + 6
        val sp = ArrayList<Float>()
        fun add(x: Double, y: Double, z: Double): Int { sp += x.toFloat(); sp += y.toFloat(); sp += z.toFloat(); return sp.size / 3 - 1 }
        val k = ov.size
        val outline = IntArray(k) { add(pos[3 * ov[it]].toDouble(), pos[3 * ov[it] + 1].toDouble(), pos[3 * ov[it] + 2].toDouble()) }
        val phi = DoubleArray(k) { atan2(pos[3 * ov[it] + 1] - cy, pos[3 * ov[it]] - cx) }
        val rings = Array(SHELL_RINGS) { j ->
            val th = j * (PI / 2) / SHELL_RINGS
            IntArray(k) { add(cx + rx * cos(th) * cos(phi[it]), cy + ry * cos(th) * sin(phi[it]), z0 + rz * sin(th)) }
        }
        val pole = add(cx, cy, z0 + rz)
        val si = ArrayList<Int>()
        val centre = doubleArrayOf(cx, cy, z0)
        fun tri(a: Int, b: Int, c: Int) {
            val p = sp.toFloatArray()
            val n = normal(p, a, b, c)
            val m = doubleArrayOf((p[3 * a] + p[3 * b] + p[3 * c]) / 3.0 - centre[0],
                (p[3 * a + 1] + p[3 * b + 1] + p[3 * c + 1]) / 3.0 - centre[1],
                (p[3 * a + 2] + p[3 * b + 2] + p[3 * c + 2]) / 3.0 - (centre[2] - rz * 0.6))
            if (n[0] * m[0] + n[1] * m[1] + n[2] * m[2] >= 0) { si += a; si += b; si += c } else { si += a; si += c; si += b }
        }
        val all = listOf(outline) + rings.toList()
        for (r in 0 until all.size - 1) for (i in 0 until k) {
            val a = all[r][i]; val b = all[r][(i + 1) % k]; val c = all[r + 1][(i + 1) % k]; val d = all[r + 1][i]
            tri(a, b, c); tri(a, c, d)
        }
        val last = all.last()
        for (i in 0 until k) tri(last[i], last[(i + 1) % k], pole)
        val faceW = abs(pos[3 * 234] - pos[3 * 454]).toDouble()
        return Geometry(pos, uv, faceIdx, sp.toFloatArray(), si.toIntArray(), ppm, faceW, pdMm == null || pdMm <= 0)
    }

    /** The avatar as a GLB: textured face + skin-coloured shell. [skinRgb] 0..255. */
    fun glb(g: Geometry, textureJpeg: ByteArray, skinRgb: IntArray): ByteArray {
        val w = GlbWriter()
        val img = w.addImage(textureJpeg, "image/jpeg")
        val faceMat = w.addMaterial(GlbWriter.Material("avatar_face", image = img, roughness = 0.75f))
        val skin = floatArrayOf(skinRgb[0] / 255f, skinRgb[1] / 255f, skinRgb[2] / 255f, 1f).map { srgbToLinear(it) }.toFloatArray()
        skin[3] = 1f
        val shellMat = w.addMaterial(GlbWriter.Material("avatar_shell", baseColor = skin, roughness = 0.85f))
        w.addMesh("avatar_face", g.positions, g.indices, faceMat, GlbWriter.vertexNormals(g.positions, g.indices), g.uvs)
        w.addMesh("avatar_shell", g.shellPositions, g.shellIndices, shellMat, GlbWriter.vertexNormals(g.shellPositions, g.shellIndices))
        return w.build()
    }

    /** glTF colour factors are linear. */
    fun srgbToLinear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else Math.pow((c + 0.055) / 1.055, 2.4).toFloat()

    private fun flip(t: IntArray) = IntArray(t.size) { i -> if (i % 3 == 1) t[i + 1] else if (i % 3 == 2) t[i - 1] else t[i] }

    private fun normal(p: FloatArray, a: Int, b: Int, c: Int): DoubleArray {
        val ux = p[3 * b] - p[3 * a].toDouble(); val uy = p[3 * b + 1] - p[3 * a + 1].toDouble(); val uz = p[3 * b + 2] - p[3 * a + 2].toDouble()
        val vx = p[3 * c] - p[3 * a].toDouble(); val vy = p[3 * c + 1] - p[3 * a + 1].toDouble(); val vz = p[3 * c + 2] - p[3 * a + 2].toDouble()
        return doubleArrayOf(uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx)
    }
}
