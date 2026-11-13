package com.drishtisaathi.engine.tryon3d

import com.drishtisaathi.engine.face.Landmark
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs

class Avatar3DTest {
    private val mesh = Json.parseToJsonElement(File("../app/src/main/assets/face_mesh_triangles.json").readText()).jsonObject
    private val verts = mesh["vertices"]!!.jsonArray.map { it.jsonPrimitive.content.toFloat() }.toFloatArray()
    private val tris = mesh["triangles"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }.toIntArray()
    private val f = 520.0
    private val w = 480
    private val h = 640

    private fun at(m: FloatArray, p: V3) = Mat4.apply(m, p.x, p.y, p.z).let { V3(it[0], it[1], it[2]) }

    /** The canonical face photographed at [yawDeg] (yaw > 0 = turned to the subject's left). */
    private fun view(step: AvatarStep, yawDeg: Double): AvatarView {
        val pose = Mat4.mul(Mat4.translate(0.5, -1.0, -45.0), Mat4.rotY(yawDeg * PI / 180))
        fun proj(p: V3): Landmark {
            val q = at(pose, p)
            return Landmark(((w / 2.0 + f * q.x / -q.z) / w).toFloat(), ((h / 2.0 - f * q.y / -q.z) / h).toFloat(), 0f)
        }
        val lm = (0 until 468).map { proj(V3(verts[it * 3].toDouble(), verts[it * 3 + 1].toDouble(), verts[it * 3 + 2].toDouble())) }.toMutableList()
        repeat(5) { lm += proj(FaceAnchors.CANONICAL.pupilR) }
        repeat(5) { lm += proj(FaceAnchors.CANONICAL.pupilL) }
        return AvatarView(step, lm, w, h, pose, f)
    }

    private fun solid(rgb: Int) = Pixels(w, h, IntArray(w * h) { (0xFF shl 24) or rgb })
    private val views = listOf(view(AvatarStep.FRONT, 0.0), view(AvatarStep.LEFT, 35.0), view(AvatarStep.RIGHT, -35.0))

    @Test fun `capture gate - front within 5 deg, sides 30-40 deg, pitch limit, held 0_5 s with quality`() {
        val g = AvatarCaptureGate()
        assertTrue(g.poseOk(AvatarStep.FRONT, 4.0, 0.0)); assertFalse(g.poseOk(AvatarStep.FRONT, 6.0, 0.0))
        assertTrue(g.poseOk(AvatarStep.LEFT, 35.0, 0.0)); assertFalse(g.poseOk(AvatarStep.LEFT, -35.0, 0.0))
        assertFalse(g.poseOk(AvatarStep.LEFT, 25.0, 0.0)); assertFalse(g.poseOk(AvatarStep.LEFT, 45.0, 0.0))
        assertTrue(g.poseOk(AvatarStep.RIGHT, -32.0, 0.0)); assertFalse(g.poseOk(AvatarStep.RIGHT, 32.0, 0.0))
        assertFalse(g.poseOk(AvatarStep.FRONT, 0.0, 15.0))
        // must be held: not ready at 400 ms, ready at 500 ms
        assertFalse(g.update(AvatarStep.FRONT, 1.0, 0.0, true, 1000).ready)
        assertFalse(g.update(AvatarStep.FRONT, 1.0, 0.0, true, 1400).ready)
        assertTrue(g.update(AvatarStep.FRONT, 1.0, 0.0, true, 1500).ready)
        // a quality failure or a pose slip restarts the hold
        g.reset()
        g.update(AvatarStep.LEFT, 35.0, 0.0, true, 0)
        assertFalse(g.update(AvatarStep.LEFT, 35.0, 0.0, false, 300).ready)
        assertFalse(g.update(AvatarStep.LEFT, 35.0, 0.0, true, 600).ready)
        assertFalse(g.update(AvatarStep.LEFT, 20.0, 0.0, true, 900).ready)
        assertFalse(g.update(AvatarStep.LEFT, 35.0, 0.0, true, 1000).ready)
        assertTrue(g.update(AvatarStep.LEFT, 35.0, 0.0, true, 1500).ready)
        assertFalse(g.update(AvatarStep.RIGHT, null, null, true, 2000).poseOk)
        // yaw is read from the transformation matrix with the right sign
        assertEquals(35.0, AvatarCaptureGate.angles(views[1].face())!!.first, 0.01)
        assertEquals(-35.0, AvatarCaptureGate.angles(views[2].face())!!.first, 0.01)
    }

    @Test fun `view selection per triangle - each cheek comes from the photo that faces it, the nose from the front`() {
        val poses = views.map { FacePose.solve(it.face(), f)!! }
        val cams = poses.map { AvatarBuilder.cameraInFace(it) }
        val best = AvatarBuilder.bestViews(verts, tris, cams, listOf(2.0, 1.0, 1.0))
        fun centroid(t: Int) = (0 until 3).map { V3(verts[3 * tris[3 * t + it]].toDouble(), verts[3 * tris[3 * t + it] + 1].toDouble(), verts[3 * tris[3 * t + it] + 2].toDouble()) }
            .reduce { a, b -> a + b } * (1.0 / 3)
        val rightCheek = best.indices.filter { centroid(it).x < -6 }
        val leftCheek = best.indices.filter { centroid(it).x > 6 }
        // triangles in the middle of the face that look straight ahead (thin nose-ridge triangles face sideways)
        val normals = GlbWriter.vertexNormals(verts, tris)
        fun facesAhead(t: Int) = (0 until 3).sumOf { normals[3 * tris[3 * t + it] + 2].toDouble() } / 3 > 0.9
        val noseFront = best.indices.filter { abs(centroid(it).x) < 2.5 && facesAhead(it) }
        assertTrue("sizes ${rightCheek.size} ${leftCheek.size} ${noseFront.size}", rightCheek.isNotEmpty() && leftCheek.isNotEmpty() && noseFront.isNotEmpty())
        // the subject's right cheek faces the camera when they turn LEFT, and vice versa
        assertTrue("R ${rightCheek.map { best[it] }}", rightCheek.count { best[it] == 1 } > 0.8 * rightCheek.size)
        assertTrue("L ${leftCheek.map { best[it] }}", leftCheek.count { best[it] == 2 } > 0.8 * leftCheek.size)
        assertTrue("N ${noseFront.map { best[it] }}", noseFront.count { best[it] == 0 } > 0.8 * noseFront.size)
    }

    @Test fun `build - three views to face space, texture blended from the views, shell, hair, ears`() {
        val red = 0xC00000; val green = 0x00C000; val blue = 0x0000C0
        val m = AvatarBuilder.build(views, listOf(solid(red), solid(green), solid(blue)), verts, tris, intArrayOf(200, 160, 140))
        assertNotNull(m); m!!
        // geometry: the synthetic views are exact, so the average is the canonical face
        var maxErr = 0.0
        for (i in 0 until 468) maxErr = maxOf(maxErr, V3(m.faceVertices[3 * i].toDouble(), m.faceVertices[3 * i + 1].toDouble(), m.faceVertices[3 * i + 2].toDouble())
            .dist(V3(verts[3 * i].toDouble(), verts[3 * i + 1].toDouble(), verts[3 * i + 2].toDouble())))
        assertTrue("max vertex error $maxErr cm", maxErr < 0.15)
        assertEquals(FaceAnchors.CANONICAL.pdMm, m.anchors.pdMm, 0.5)
        // texture: nose tip red (front), right cheek green (LEFT view), left cheek blue (RIGHT view)
        fun texAt(i: Int): IntArray {
            val x = (m.faceUvs[2 * i] * AvatarBuilder.TEX).toInt(); val y = (m.faceUvs[2 * i + 1] * AvatarBuilder.TEX).toInt()
            val c = m.texture[y * AvatarBuilder.TEX + x]
            return intArrayOf((c shr 16) and 255, (c shr 8) and 255, c and 255)
        }
        val nose = texAt(4); val rCheek = texAt(93); val lCheek = texAt(323)
        assertTrue("nose ${nose.toList()}", nose[0] > nose[1] && nose[0] > nose[2])
        assertTrue("right cheek ${rCheek.toList()}", rCheek[1] > rCheek[0] && rCheek[1] > rCheek[2])
        assertTrue("left cheek ${lCheek.toList()}", lCheek[2] > lCheek[0] && lCheek[2] > lCheek[1])
        // seams are blended: somewhere between nose and cheek a texel mixes red and green
        val mixed = m.texture.any { c -> val r = (c shr 16) and 255; val g = (c shr 8) and 255; r in 40..160 && g in 40..160 }
        assertTrue("blended seam", mixed)
        // shell and ears
        val nS = m.shellVertices.size / 3
        assertTrue(m.skinTriangles.isNotEmpty() && m.hairTriangles.isNotEmpty())
        assertTrue((m.skinTriangles + m.hairTriangles).all { it in 0 until nS })
        val backZ = (0 until nS).minOf { m.shellVertices[3 * it + 2].toDouble() }
        assertEquals(m.anchors.nasion.z - AvatarBuilder.HEAD_LENGTH_CM, backZ, 0.6)
        assertTrue(m.earTriangles.isNotEmpty())
        val glb = AvatarBuilder.glb(m, byteArrayOf(-1, -40, -1), intArrayOf(200, 160, 140), intArrayOf(40, 30, 25))
        val names = Json.parseToJsonElement(GlbWriter.jsonChunk(glb)).jsonObject["nodes"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("avatar_face", "avatar_shell", "avatar_hair", "avatar_ears"), names)
    }

    @Test fun `build needs the front view`() {
        assertEquals(null, AvatarBuilder.build(views.drop(1), listOf(solid(0), solid(0)), verts, tris, intArrayOf(1, 1, 1)))
    }

    @Test fun `glasses on the avatar use the same placement as live try-on`() {
        val m = AvatarBuilder.build(views, listOf(solid(0x808080), solid(0x808080), solid(0x808080)), verts, tris, intArrayOf(200, 160, 140))!!
        val frame = Frame3D(
            id = "t", name = "T", shape = "round", colour = "black", material = "metal", priceInr = 1,
            sizes = Sizes3D(50, 20, 140, b = 40, frontWidth = 135),
            anchors = Anchors3D(lensCentresMm = mapOf("lens_R" to listOf(35.0, 2.0), "lens_L" to listOf(-35.0, 2.0)),
                hingesMm = mapOf("temple_R" to listOf(66.0, 10.0, 4.0), "temple_L" to listOf(-66.0, 10.0, 4.0))),
        )
        val onAvatar = GlassesFit.place(frame, 63.0, m.anchors)
        val live = GlassesFit.place(frame, 63.0, FaceAnchors.CANONICAL)
        // the avatar of a canonical face carries the glasses exactly where live try-on puts them
        assertTrue(onAvatar.pupilTarget.dist(live.pupilTarget) < 0.15)
        for (i in 0 until 2) assertTrue(onAvatar.lensCentres[i].dist(live.lensCentres[i]) < 0.2)
    }
}
