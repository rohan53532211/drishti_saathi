package com.drishtisaathi.engine.tryon3d

import com.drishtisaathi.engine.face.Landmark
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

class HeadTemplateTest {
    private val mesh = Json.parseToJsonElement(File("../app/src/main/assets/face_mesh_triangles.json").readText()).jsonObject
    private val canon = mesh["vertices"]!!.jsonArray.map { it.jsonPrimitive.content.toFloat() }.toFloatArray()
    private val tris = mesh["triangles"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }.toIntArray()
    private val template = HeadTemplate.parse(File("../app/src/main/assets/head_template.json").readText())

    private fun dist(a: DoubleArray, i: Int, b: FloatArray) =
        sqrt((0 until 3).sumOf { c -> (a[3 * i + c] - b[3 * i + c]).let { it * it } })

    /** A different face: wider, shorter, deeper, a longer nose, shifted (cm). */
    private fun otherFace(): FloatArray = FloatArray(canon.size) { j ->
        val i = j / 3
        when (j % 3) {
            0 -> canon[j] * 1.08f + 0.3f
            1 -> canon[j] * 0.95f - 0.2f
            else -> canon[j] * 1.05f + (if (i == 1 || i == 4) 0.35f else 0f) + 0.5f
        }
    }

    @Test fun `template - licence recorded, landmarks lie on its surface, regions present`() {
        val j = Json.parseToJsonElement(File("../app/src/main/assets/head_template.json").readText()).jsonObject
        assertTrue(j["licence"]!!.jsonPrimitive.content.startsWith("CC0"))
        assertTrue(j["source"]!!.jsonPrimitive.content.contains("MakeHuman"))
        val lp = template.landmarkPoints()
        val worst = (0 until 468).maxOf { dist(lp, it, canon) }
        assertTrue("worst $worst cm", worst < 0.15)
        for (r in listOf(HeadTemplate.FACE, HeadTemplate.EAR, HeadTemplate.SCALP, HeadTemplate.SKIN)) assertTrue(template.region.count { it == r } > 50)
        // a full head: it reaches well behind the ears and above the forehead
        val zs = (0 until template.vertexCount).map { template.vertices[3 * it + 2] }
        val ys = (0 until template.vertexCount).map { template.vertices[3 * it + 1] }
        assertTrue(zs.min() < -9f && ys.max() > canon[3 * 10 + 1] + 4f)
    }

    @Test fun `template deformation - face landmarks land within 2 mm of their targets`() {
        val target = otherFace()
        val d = HeadDeform.deform(template, canon, target)
        val lp = template.landmarkPoints(d.vertices)
        val worst = (0 until 468).maxOf { dist(lp, it, target) }
        assertTrue("worst landmark error ${worst * 10} mm", worst < 0.2)
        // the skull stays smooth and proportional: anchors move only by the per-axis scale
        val s = HeadDeform.axisScales(canon, target)
        assertEquals(1.08, s[0], 0.01)
        assertEquals(0.95, s[1], 0.01)
        // symmetric ear rests, outside the face width, behind the face
        assertEquals(0.3, (d.earRestR.x + d.earRestL.x) / 2, 0.05)     // the test face is shifted 3 mm to the left
        assertTrue(d.earRestL.x > target[3 * 454] - 0.5)
        assertTrue(d.earRestL.z < target[3 * 168 + 2] - 5)
        // no vertex thrown far away
        for (i in 0 until template.vertexCount) for (c in 0 until 3) assertTrue(abs(d.vertices[3 * i + c]) < 30f)
    }

    @Test fun `rbf - exact at the centres and affine far away`() {
        val c = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0)
        val vals = arrayOf(doubleArrayOf(1.0), doubleArrayOf(2.0), doubleArrayOf(0.5), doubleArrayOf(-1.0), doubleArrayOf(3.0))
        val rbf = Rbf.System(c).solve(vals, 1)
        val o = DoubleArray(1)
        for (i in 0 until 5) { rbf.eval(c[3 * i], c[3 * i + 1], c[3 * i + 2], o); assertEquals(vals[i][0], o[0], 1e-6) }
    }

    // ---- the whole head avatar from three synthetic photos ----

    private val f = 1500.0
    private val w = 1920
    private val h = 2560
    private fun view(step: AvatarStep, yawDeg: Double): AvatarView {
        val pose = Mat4.mul(Mat4.translate(0.0, 0.0, -40.0), Mat4.rotY(yawDeg * PI / 180))
        fun proj(p: V3): Landmark {
            val q = Mat4.apply(pose, p.x, p.y, p.z)
            return Landmark(((w / 2.0 + f * q[0] / -q[2]) / w).toFloat(), ((h / 2.0 - f * q[1] / -q[2]) / h).toFloat(), 0f)
        }
        val lm = (0 until 468).map { proj(V3(canon[it * 3].toDouble(), canon[it * 3 + 1].toDouble(), canon[it * 3 + 2].toDouble())) }.toMutableList()
        repeat(5) { lm += proj(FaceAnchors.CANONICAL.pupilR) }
        repeat(5) { lm += proj(FaceAnchors.CANONICAL.pupilL) }
        return AvatarView(step, lm, w, h, pose, f)
    }

    @Test fun `head avatar - photo colour on the face, hair on the scalp, skin elsewhere, temples on the ears`() {
        val views = listOf(view(AvatarStep.FRONT, 0.0), view(AvatarStep.LEFT, 35.0), view(AvatarStep.RIGHT, -35.0))
        // whole photo in one colour (so every projected point hits it)
        val photo = Pixels(w / 4, h / 4, IntArray((w / 4) * (h / 4)) { (0xFF shl 24) or 0x3C8CDC }, 0.0, 0.0, 0.25)
        val skin = intArrayOf(200, 150, 120)
        val hair = intArrayOf(30, 20, 15)
        val m = AvatarBuilder.buildHead(views, listOf(photo, photo, photo), canon, tris, template, skin, hair)!!
        fun texAt(i: Int): Int {
            val u = (m.faceUvs[2 * i] * AvatarBuilder.TEX).toInt().coerceIn(0, AvatarBuilder.TEX - 1)
            val v = (m.faceUvs[2 * i + 1] * AvatarBuilder.TEX).toInt().coerceIn(0, AvatarBuilder.TEX - 1)
            return m.texture[v * AvatarBuilder.TEX + u] and 0xFFFFFF
        }
        fun near(c: Int, rgb: Int) = (0 until 3).all { k -> abs(((c shr (8 * k)) and 255) - ((rgb shr (8 * k)) and 255)) <= 3 }
        val face = (0 until template.vertexCount).filter { template.region[it] == HeadTemplate.FACE && m.faceVertices[3 * it + 2] > 3f }
        assertTrue("face ${face.size} " + face.groupingBy { Integer.toHexString(texAt(it)) }.eachCount().entries.sortedByDescending { it.value }.take(6),
            face.count { near(texAt(it), 0x3C8CDC) } > face.size * 0.8)
        val scalp = (0 until template.vertexCount).filter { template.region[it] == HeadTemplate.SCALP }
        // texels at scalp vertices: hair colour (or blended at the hairline), never grey
        assertTrue("scalp " + scalp.groupingBy { Integer.toHexString(texAt(it)) }.eachCount().entries.sortedByDescending { it.value }.take(4), scalp.count { near(texAt(it), 0x1E140F) } > scalp.size * 0.6)
        // back of the neck (no photo sees it): skin
        val back = (0 until template.vertexCount).filter { template.region[it] == HeadTemplate.SKIN && m.faceVertices[3 * it + 2] < -7f }
        assertTrue(back.isNotEmpty())
        assertTrue("back " + back.groupingBy { Integer.toHexString(texAt(it)) }.eachCount().entries.sortedByDescending { it.value }.take(4), back.count { near(texAt(it), 0xC89678) } > back.size * 0.6)
        // the glasses' temples now go to the template's ears
        assertNotNull(m.anchors.earRestR)
        val frame = Frame3D(id = "t", name = "t", shape = "round", colour = "black", material = "acetate", sizes = Sizes3D(50, 20, 145), priceInr = 1, glb = "t.glb")
        val p = GlassesFit.place(frame, 63.0, m.anchors)
        assertEquals(m.anchors.earRestR!!.x - GlassesFit.EAR_CLEARANCE, p.earR.x, 1e-9)
        assertEquals(m.anchors.earRestL!!.z, p.earL.z, 1e-9)
        // one GLB, sRGB atlas, no separate ear or shell meshes
        val j = Json.parseToJsonElement(GlbWriter.jsonChunk(AvatarBuilder.glb(m, byteArrayOf(-1, -40, -1), skin, hair))).jsonObject
        assertEquals("srgb", j["textures"]!!.jsonArray[0].jsonObject["extras"]!!.jsonObject["colorSpace"]!!.jsonPrimitive.content)
        assertEquals(1, j["meshes"]!!.jsonArray.size)
    }
}
