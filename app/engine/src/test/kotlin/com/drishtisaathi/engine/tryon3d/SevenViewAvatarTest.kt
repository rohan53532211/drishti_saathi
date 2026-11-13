package com.drishtisaathi.engine.tryon3d

import com.drishtisaathi.engine.face.Landmark
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** 180° capture (7 views + top), view rejection, multi-band bake with synthesized fill, front-only eyes, clear lenses. */
class SevenViewAvatarTest {
    private val mesh = Json.parseToJsonElement(File("../app/src/main/assets/face_mesh_triangles.json").readText()).jsonObject
    private val canon = mesh["vertices"]!!.jsonArray.map { it.jsonPrimitive.content.toFloat() }.toFloatArray()
    private val tris = mesh["triangles"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }.toIntArray()
    private val template = HeadTemplate.parse(File("../app/src/main/assets/head_template.json").readText())
    private val f = 1500.0
    private val w = 1920
    private val h = 2560
    private val skin = intArrayOf(200, 150, 120)
    private val hair = intArrayOf(90, 50, 20)

    private fun pose(yawDeg: Double) = Mat4.mul(Mat4.translate(0.0, 0.0, -40.0), Mat4.rotY(yawDeg * PI / 180))

    private fun view(step: AvatarStep, yawDeg: Double): AvatarView {
        val p = pose(yawDeg)
        fun proj(q: V3): Landmark {
            val c = Mat4.apply(p, q.x, q.y, q.z)
            return Landmark(((w / 2.0 + f * c[0] / -c[2]) / w).toFloat(), ((h / 2.0 - f * c[1] / -c[2]) / h).toFloat(), 0f)
        }
        val lm = (0 until 468).map { proj(V3(canon[it * 3].toDouble(), canon[it * 3 + 1].toDouble(), canon[it * 3 + 2].toDouble())) }.toMutableList()
        repeat(5) { lm += proj(FaceAnchors.CANONICAL.pupilR) }
        repeat(5) { lm += proj(FaceAnchors.CANONICAL.pupilL) }
        return AvatarView(step, lm, w, h, p, f)
    }

    /** Whole-photo "crop" at 1/4 resolution, colour per pixel, optional class mask (128×170 grid). */
    private fun photo(colour: (Int, Int) -> Int, cls: ((Int, Int) -> Int)? = null): Pixels {
        val pw = w / 4; val ph = h / 4
        val mask = cls?.let { c -> ByteArray(128 * 170) { i -> c(i % 128, i / 128).toByte() } }
        return Pixels(pw, ph, IntArray(pw * ph) { (0xFF shl 24) or colour(it % pw, it / pw) }, 0.0, 0.0, 0.25, mask, if (mask != null) 128 else 0, if (mask != null) 170 else 0)
    }

    private fun solid(rgb: Int, cls: ((Int, Int) -> Int)? = null) = photo({ _, _ -> rgb }, cls)

    private fun texAt(m: AvatarModel, i: Int): Int {
        val u = (m.faceUvs[2 * i] * AvatarBuilder.TEX).toInt().coerceIn(0, AvatarBuilder.TEX - 1)
        val v = (m.faceUvs[2 * i + 1] * AvatarBuilder.TEX).toInt().coerceIn(0, AvatarBuilder.TEX - 1)
        return m.texture[v * AvatarBuilder.TEX + u]
    }

    private fun near(c: Int, rgb: Int, tol: Int) =
        abs(((c shr 16) and 255) - ((rgb shr 16) and 255)) <= tol && abs(((c shr 8) and 255) - ((rgb shr 8) and 255)) <= tol && abs((c and 255) - (rgb and 255)) <= tol

    // ---------------------------------------------------------------- capture

    @Test fun `capture - seven views plus an optional top, yaw zones, profiles may be skipped`() {
        assertEquals(listOf(AvatarStep.FRONT, AvatarStep.LEFT, AvatarStep.LEFT_60, AvatarStep.LEFT_90, AvatarStep.RIGHT, AvatarStep.RIGHT_60,
            AvatarStep.RIGHT_90, AvatarStep.TOP), AvatarStep.ORDER)
        assertEquals(listOf(AvatarStep.FRONT, AvatarStep.LEFT, AvatarStep.LEFT_60, AvatarStep.RIGHT, AvatarStep.RIGHT_60), AvatarStep.REQUIRED)
        assertTrue(AvatarStep.LEFT_90.optional && AvatarStep.RIGHT_90.optional && AvatarStep.TOP.optional)
        val g = AvatarCaptureGate()
        // 30°
        assertTrue(g.poseOk(AvatarStep.LEFT, 30.0, 0.0)); assertTrue(g.poseOk(AvatarStep.RIGHT, -30.0, 0.0))
        // 60°: 52–70
        assertTrue(g.poseOk(AvatarStep.LEFT_60, 60.0, 0.0)); assertFalse(g.poseOk(AvatarStep.LEFT_60, 45.0, 0.0)); assertFalse(g.poseOk(AvatarStep.LEFT_60, 75.0, 0.0))
        assertTrue(g.poseOk(AvatarStep.RIGHT_60, -60.0, 0.0)); assertFalse(g.poseOk(AvatarStep.RIGHT_60, 60.0, 0.0))
        // 90° while MediaPipe still sees the face
        assertTrue(g.poseOk(AvatarStep.LEFT_90, 84.0, 0.0)); assertFalse(g.poseOk(AvatarStep.LEFT_90, 70.0, 0.0)); assertFalse(g.poseOk(AvatarStep.RIGHT_90, 84.0, 0.0))
        // zones do not overlap, in order round the head
        val z = AvatarStep.ORDER.mapNotNull { s -> g.zone(s)?.let { s to it } }.toMap()
        assertTrue(z.getValue(AvatarStep.LEFT).endInclusive < z.getValue(AvatarStep.LEFT_60).start)
        assertTrue(z.getValue(AvatarStep.LEFT_60).endInclusive < z.getValue(AvatarStep.LEFT_90).start)
        assertTrue(z.getValue(AvatarStep.RIGHT_60).endInclusive < z.getValue(AvatarStep.RIGHT).start)
        assertNull(g.zone(AvatarStep.TOP)); assertFalse(g.poseOk(AvatarStep.TOP, 0.0, 0.0))
    }

    @Test fun `capture - a profile uses the last good head pose when the face is lost`() {
        val g = AvatarCaptureGate()
        // seen turned 66° to the left, then lost: the profile is taken after the hold
        assertFalse(g.update(AvatarStep.LEFT_90, 66.0, 0.0, true, 0).poseOk)
        val s1 = g.update(AvatarStep.LEFT_90, null, null, true, 100)
        assertTrue(s1.poseOk && s1.fromLastPose && !s1.ready)
        assertTrue(g.update(AvatarStep.LEFT_90, null, null, true, 600).ready)
        // not when the face was last seen only a little turned, or too long ago, or on the other side
        val g2 = AvatarCaptureGate()
        g2.update(AvatarStep.LEFT_90, 30.0, 0.0, true, 0)
        assertFalse(g2.update(AvatarStep.LEFT_90, null, null, true, 100).poseOk)
        val g3 = AvatarCaptureGate()
        g3.update(AvatarStep.LEFT_90, 66.0, 0.0, true, 0)
        assertFalse(g3.update(AvatarStep.LEFT_90, null, null, true, 5000).poseOk)
        val g4 = AvatarCaptureGate()
        g4.update(AvatarStep.RIGHT_90, 66.0, 0.0, true, 0)
        assertFalse(g4.update(AvatarStep.RIGHT_90, null, null, true, 100).poseOk)
        // other steps never capture without a face; bad light stops the hold
        assertFalse(AvatarCaptureGate().update(AvatarStep.LEFT_60, null, null, true, 0).poseOk)
        val g5 = AvatarCaptureGate()
        g5.update(AvatarStep.LEFT_90, 70.0, 0.0, true, 0)
        g5.update(AvatarStep.LEFT_90, null, null, true, 100)
        assertFalse(g5.update(AvatarStep.LEFT_90, null, null, false, 700).ready)
    }

    @Test fun `profile pose - placed from the 60 deg view and the face-skin silhouette, skipped when it does not fit`() {
        val v = template.vertices
        val ids = ArrayList<Int>()
        for (t in template.triangles.indices step 3) {
            val q = IntArray(3) { template.weld[template.triangles[t + it]] }
            if (q.all { (template.region[it] == HeadTemplate.FACE || template.region[it] == HeadTemplate.EAR) && v[3 * it + 1] > -9.9f }) q.forEach { ids += it }
        }
        val truePose = Mat4.mul(Mat4.translate(1.0, 0.5, -42.0), Mat4.rotY(88 * PI / 180))
        val tmp = DoubleArray(2)
        // the face-skin silhouette of the true profile (class grid 128×170 over the whole photo)
        val cls = ByteArray(128 * 170)
        val keep = ids.toSet()
        val wt = template.weld
        for (t in template.triangles.indices step 3) {
            val q = IntArray(3) { wt[template.triangles[t + it]] }
            if (!q.all { template.region[it] == HeadTemplate.FACE || template.region[it] == HeadTemplate.EAR } || q.any { v[3 * it + 1] <= -9.9f }) continue
            val pts = q.map { i -> AvatarBuilder.project(truePose, f, w, h, v[3 * i].toDouble(), v[3 * i + 1].toDouble(), v[3 * i + 2].toDouble(), tmp); tmp[0] * 128 to tmp[1] * 170 }
            val (ax, ay) = pts[0]; val (bx, by) = pts[1]; val (cx, cy) = pts[2]
            val den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
            if (abs(den) < 1e-12) continue
            for (y in max(0, floor(minOf(ay, by, cy)).toInt())..min(169, floor(maxOf(ay, by, cy)).toInt()))
                for (x in max(0, floor(minOf(ax, bx, cx)).toInt())..min(127, floor(maxOf(ax, bx, cx)).toInt())) {
                    val l0 = ((by - cy) * (x + 0.5 - cx) + (cx - bx) * (y + 0.5 - cy)) / den
                    val l1 = ((cy - ay) * (x + 0.5 - cx) + (ax - cx) * (y + 0.5 - cy)) / den
                    if (l0 >= 0 && l1 >= 0 && 1 - l0 - l1 >= 0) cls[y * 128 + x] = SegClass.FACE_SKIN.toByte()
                }
        }
        assertTrue(keep.isNotEmpty())
        val pv = AvatarView(AvatarStep.LEFT_90, emptyList(), w, h, null, f)
        val px = Pixels(w / 4, h / 4, IntArray((w / 4) * (h / 4)) { -1 }, 0.0, 0.0, 0.25, cls, 128, 170)
        val centre = V3(0.0, 1.0, -6.0)
        val fit = ProfilePose.fit(pv, px, pose(60.0), 1, v, ids.toIntArray(), centre)
        val trace = ArrayList<String>()
        ProfilePose.fit(pv, px, pose(60.0), 1, v, ids.toIntArray(), centre, minScore = 0.0, trace = trace)
        val truth = ArrayList<String>()
        ProfilePose.fit(pv, px, Mat4.mul(truePose, Mat4.rotY(-10 * PI / 180)), 1, v, ids.toIntArray(), V3(0.0, 0.0, 0.0), minScore = 0.0, trace = truth)
        assertNotNull("trace $trace from truth $truth", fit)
        // the camera direction matches the true one within 8°
        val a = AvatarBuilder.cameraInFace(fit!!.pose); val b = AvatarBuilder.cameraInFace(truePose)
        val cosang = (a.x * b.x + a.y * b.y + a.z * b.z) / (a.length * b.length)
        assertTrue("angle ${acos(cosang) * 180 / PI} extra ${fit.extraYawDeg} score ${fit.score}", acos(min(1.0, cosang)) * 180 / PI < 8.0)
        // no mask, a tiny mask or noise: skipped
        assertNull(ProfilePose.fit(pv, Pixels(w / 4, h / 4, IntArray((w / 4) * (h / 4)) { -1 }, 0.0, 0.0, 0.25), pose(60.0), 1, v, ids.toIntArray(), centre))
        val rnd = Random(3)
        val noise = ByteArray(128 * 170) { if (rnd.nextDouble() < 0.3) SegClass.FACE_SKIN.toByte() else 0 }
        assertNull(ProfilePose.fit(pv, Pixels(w / 4, h / 4, IntArray((w / 4) * (h / 4)) { -1 }, 0.0, 0.0, 0.25, noise, 128, 170), pose(60.0), 1, v, ids.toIntArray(), centre))
    }

    // ---------------------------------------------------------------- texture

    @Test fun `view rejection - a photo seen beyond 70 deg never colours the face`() {
        val front = 0x2878DC; val red = 0xDC2828
        val views = listOf(view(AvatarStep.FRONT, 0.0), view(AvatarStep.LEFT_60, 85.0))
        val hb = AvatarBuilder.buildHeadDetailed(views, listOf(solid(front), solid(red)), canon, tris, template, skin, hair)!!
        val m = hb.model
        val nrm = HeadMesh.normals(m.faceVertices, m.faceTriangles, template.weld)
        // face points facing the front camera (within 12°): 73°+ from the 85° camera → front colour only
        val faceFront = (0 until template.vertexCount).filter { template.region[it] == HeadTemplate.FACE && nrm[3 * it + 2] > 0.978f }
        assertTrue("${faceFront.size}", faceFront.size > 30)
        assertTrue(faceFront.count { near(texAt(m, it), front, 6) } > faceFront.size * 0.9)
        assertTrue(faceFront.count { ((texAt(m, it) shr 16) and 255) > 120 } <= faceFront.size / 50)
        // and the 80° photo is used where it sees the surface squarely (the side of the head)
        val cam = AvatarBuilder.cameraInFace(pose(85.0))
        val side = (0 until template.vertexCount).filter {
            val d = V3(cam.x - m.faceVertices[3 * it], cam.y - m.faceVertices[3 * it + 1], cam.z - m.faceVertices[3 * it + 2])
            template.region[it] == HeadTemplate.SKIN && (nrm[3 * it] * d.x + nrm[3 * it + 1] * d.y + nrm[3 * it + 2] * d.z) / d.length > 0.85
        }
        assertTrue(side.isNotEmpty())
        assertTrue(side.count { ((texAt(m, it) shr 16) and 255) > 150 } > side.size / 2)
        assertEquals(60.0, AvatarBuilder.MAX_VIEW_ANGLE_OFF_FACE_DEG, 0.0); assertEquals(70.0, AvatarBuilder.HEAD_FACE_VIEW_DEG, 0.0)
    }

    @Test fun `eyes - the eye region comes from the front photo only`() {
        val front = 0x46A0D2; val sides = 0xC85A3C
        val views = listOf(view(AvatarStep.FRONT, 0.0), view(AvatarStep.LEFT, 35.0), view(AvatarStep.RIGHT, -35.0))
        val hb = AvatarBuilder.buildHeadDetailed(views, listOf(solid(front), solid(sides), solid(sides)), canon, tris, template, skin, hair)!!
        val m = hb.model
        val eye = EyeRegion.weights(template.vertices, template.region, canon)
        val eyes = (0 until template.vertexCount).filter { eye[it] >= 0.99f }
        assertTrue(eyes.size > 40)
        assertTrue(hb.bake.eyeTexels > 500)
        val off = eyes.filter { !near(texAt(m, it), front, 2) }
        // (a few texels at the edge of the region, where the weight is just under 1, may be a shade off)
        val far = eyes.filter { !near(texAt(m, it), front, 12) }
        assertTrue("far: " + far.take(6).map { "#$it ${Integer.toHexString(texAt(m, it))} e=${eye[it]} (${template.vertices[3 * it]}, ${template.vertices[3 * it + 1]}, ${template.vertices[3 * it + 2]}) uv ${m.faceUvs[2 * it]},${m.faceUvs[2 * it + 1]}" }, far.isEmpty())
        assertTrue("${off.size} of ${eyes.size} eye texels off: " + off.take(5).map { "#$it ${Integer.toHexString(texAt(m, it))} (${template.vertices[3 * it]}, ${template.vertices[3 * it + 1]}, ${template.vertices[3 * it + 2]})" }, off.size <= eyes.size / 50)
        // elsewhere on the face the views are blended (the eye region is special)
        val cheeks = (0 until template.vertexCount).filter { template.region[it] == HeadTemplate.FACE && eye[it] == 0f && m.faceVertices[3 * it + 2] > 3f }
        assertTrue(cheeks.count { !near(texAt(m, it), front, 3) } > cheeks.size / 2)
        // the eyes, lids and brows are covered: both eyes, brow to lower lid
        for (ids in listOf(EyeRegion.RIGHT, EyeRegion.LEFT)) for (i in ids) {
            val x = canon[3 * i]; val y = canon[3 * i + 1]
            assertTrue(eyes.any { abs(template.vertices[3 * it] - x) < 0.5f && abs(template.vertices[3 * it + 1] - y) < 0.5f })
        }
    }

    @Test fun `fill - no flat colour, the back of the head gets synthesized hair texture`() {
        val rnd = Random(7)
        val noise = IntArray(1 shl 16) { rnd.nextInt(-14, 15) }
        // textured photos: hair above 40% of the height, face skin below, with pixel noise
        fun textured(base: IntArray) = photo({ x, y ->
            val n = noise[(x * 31 + y * 17) and 0xFFFF]
            val isHair = y < (h / 4) * 0.4
            val c = if (isHair) hair else base
            ((c[0] + n).coerceIn(0, 255) shl 16) or ((c[1] + n).coerceIn(0, 255) shl 8) or (c[2] + n).coerceIn(0, 255)
        }) { _, y -> if (y < 170 * 0.4) SegClass.HAIR else SegClass.FACE_SKIN }
        val steps = listOf(AvatarStep.FRONT to 0.0, AvatarStep.LEFT to 30.0, AvatarStep.RIGHT to -30.0, AvatarStep.LEFT_60 to 60.0, AvatarStep.RIGHT_60 to -60.0)
        val views = steps.map { view(it.first, it.second) }
        val hb = AvatarBuilder.buildHeadDetailed(views, views.map { textured(skin) }, canon, tris, template, skin, hair)!!
        val m = hb.model
        val size = AvatarBuilder.TEX
        // texels inside the UV triangles (the visible head)
        val inside = BooleanArray(size * size)
        for (t in m.faceTriangles.indices step 3) {
            val a = m.faceTriangles[t]; val b = m.faceTriangles[t + 1]; val c = m.faceTriangles[t + 2]
            val ax = m.faceUvs[2 * a] * size; val ay = m.faceUvs[2 * a + 1] * size
            val bx = m.faceUvs[2 * b] * size; val by = m.faceUvs[2 * b + 1] * size
            val cx = m.faceUvs[2 * c] * size; val cy = m.faceUvs[2 * c + 1] * size
            val den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
            if (abs(den) < 1e-9) continue
            for (y in max(0, floor(min(ay, min(by, cy))).toInt())..min(size - 1, floor(max(ay, max(by, cy))).toInt()))
                for (x in max(0, floor(min(ax, min(bx, cx))).toInt())..min(size - 1, floor(max(ax, max(bx, cx))).toInt())) {
                    val qx = x + 0.5f; val qy = y + 0.5f
                    val l0 = ((by - cy) * (qx - cx) + (cx - bx) * (qy - cy)) / den
                    val l1 = ((cy - ay) * (qx - cx) + (ax - cx) * (qy - cy)) / den
                    if (l0 >= 0 && l1 >= 0 && 1 - l0 - l1 >= 0) inside[y * size + x] = true
                }
        }
        var all = 0; var flat = 0
        for (y in 1 until size - 1) for (x in 1 until size - 1) {
            val o = y * size + x
            if (!inside[o]) continue
            all++
            val c = m.texture[o]
            if (m.texture[o - 1] == c && m.texture[o + 1] == c && m.texture[o - size] == c && m.texture[o + size] == c) flat++
        }
        val share = flat.toDouble() / all
        assertTrue("flat share $share", share < 0.05)
        assertTrue(hb.bake.fallback + hb.bake.inpainted > 0)
        // the back of the head (scalp facing −z, which no photo sees within 60°): textured, hair-coloured, not one flat colour
        val nrm = HeadMesh.normals(m.faceVertices, m.faceTriangles, template.weld)
        val back = (0 until template.vertexCount).filter { template.region[it] == HeadTemplate.SCALP && nrm[3 * it + 2] < -0.8f }
        assertTrue(back.size > 20)
        val cols = back.map { texAt(m, it) }
        // texture: the colour varies around each back texel (not one flat cap)
        val varied = back.count { i ->
            val u = (m.faceUvs[2 * i] * size).toInt().coerceIn(4, size - 5); val vv = (m.faceUvs[2 * i + 1] * size).toInt().coerceIn(4, size - 5)
            val g = (-4..4).flatMap { dy -> (-4..4).map { dx -> (m.texture[(vv + dy) * size + u + dx] shr 8) and 255 } }
            g.max() - g.min() >= 6
        }
        assertTrue("varied $varied of ${back.size}", varied > back.size * 0.8)
        assertTrue("distinct ${cols.toSet().size} of ${back.size}: " + cols.take(8).map { Integer.toHexString(it) } + " uv " + back.take(4).map { "${m.faceUvs[2 * it]},${m.faceUvs[2 * it + 1]}" }, cols.toSet().size >= 10)
        assertTrue(cols.count { abs(((it shr 16) and 255) - hair[0]) < 40 && abs(((it shr 8) and 255) - hair[1]) < 40 } > back.size * 0.8)
    }

    // ---------------------------------------------------------------- lenses

    @Test fun `lenses - clear lenses transparent and matte, no photo inside the lens, sunglasses keep their tint`() {
        val cat = Json.parseToJsonElement(File("../app/src/main/assets/frames3d/frames_catalog.json").readText()).jsonObject
        var clear = 0; var tinted = 0
        for (fe in cat["frames"]!!.jsonArray) {
            val fo = fe.jsonObject
            val glb = File("../app/src/main/assets/frames3d/" + fo["glb"]!!.jsonPrimitive.content).readBytes()
            val out = FrameLens.clearLenses(glb)
            val alphas = FrameLens.lensAlphas(out)
            assertTrue(alphas.isNotEmpty())
            val sun = fo["category"]!!.jsonPrimitive.content == "sunglasses"
            if (sun) {
                // tinted: unchanged
                assertEquals(FrameLens.lensAlphas(glb), alphas); tinted++
            } else {
                assertTrue(alphas.all { !it.second && it.first <= 0.05 }); clear++
                val mats = FrameLens.parse(out).json["materials"]!!.jsonArray
                for (i in FrameLens.lensMaterials(FrameLens.parse(out).json)) {
                    val pbr = mats[i].jsonObject["pbrMetallicRoughness"]!!.jsonObject
                    assertEquals(1.0, pbr["roughnessFactor"]!!.jsonPrimitive.content.toDouble(), 1e-9)
                    assertEquals(0.0, pbr["metallicFactor"]!!.jsonPrimitive.content.toDouble(), 1e-9)
                }
                // the frame front (which carries the product photo) has the lens openings cut out
                val lc = fo["anchors"]!!.jsonObject["lensCentresMm"]!!.jsonObject
                for ((_, c) in lc) {
                    val p = c.jsonArray.map { it.jsonPrimitive.content.toDouble() }
                    assertFalse(FrameLens.frontCoversPoint(out, "frame_front", p[0], p[1]))
                }
            }
            // still a valid GLB with the same binary data
            assertEquals(FrameLens.parse(glb).bin.size, FrameLens.parse(out).bin.size)
        }
        assertTrue(clear > 10 && tinted > 2)
    }
}
