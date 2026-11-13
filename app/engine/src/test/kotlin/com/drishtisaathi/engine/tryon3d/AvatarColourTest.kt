package com.drishtisaathi.engine.tryon3d

import com.drishtisaathi.engine.face.Landmark
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI

class AvatarColourTest {
    private val mesh = Json.parseToJsonElement(File("../app/src/main/assets/face_mesh_triangles.json").readText()).jsonObject
    private val verts = mesh["vertices"]!!.jsonArray.map { it.jsonPrimitive.content.toFloat() }.toFloatArray()
    private val tris = mesh["triangles"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }.toIntArray()
    private val f = 1500.0
    private val w = 1920
    private val h = 2560

    private fun view(step: AvatarStep, yawDeg: Double): AvatarView {
        val pose = Mat4.mul(Mat4.translate(0.0, 0.0, -40.0), Mat4.rotY(yawDeg * PI / 180))
        fun proj(p: V3): Landmark {
            val q = Mat4.apply(pose, p.x, p.y, p.z)
            return Landmark(((w / 2.0 + f * q[0] / -q[2]) / w).toFloat(), ((h / 2.0 - f * q[1] / -q[2]) / h).toFloat(), 0f)
        }
        val lm = (0 until 468).map { proj(V3(verts[it * 3].toDouble(), verts[it * 3 + 1].toDouble(), verts[it * 3 + 2].toDouble())) }.toMutableList()
        repeat(5) { lm += proj(FaceAnchors.CANONICAL.pupilR) }
        repeat(5) { lm += proj(FaceAnchors.CANONICAL.pupilL) }
        return AvatarView(step, lm, w, h, pose, f)
    }

    /** A face crop (top-left at 400, 600, half resolution) filled with one colour. */
    private fun crop(rgb: Int) = Pixels(560, 680, IntArray(560 * 680) { (0xFF shl 24) or rgb }, 400.0, 600.0, 0.5)
    private val views = listOf(view(AvatarStep.FRONT, 0.0), view(AvatarStep.LEFT, 35.0), view(AvatarStep.RIGHT, -35.0))
    private fun rgb(c: Int) = intArrayOf((c shr 16) and 255, (c shr 8) and 255, c and 255)

    @Test fun `colour capture - chroma tells a colour photo from a luminance-only one`() {
        val grey = Pixels(64, 64, IntArray(64 * 64) { val y = (it * 7) % 256; (0xFF shl 24) or (y shl 16) or (y shl 8) or y })
        val skin = Pixels(64, 64, IntArray(64 * 64) { (0xFF shl 24) or (0xC8 shl 16) or (0x96 shl 8) or 0x78 })
        assertTrue(AvatarBuilder.chroma(grey) < AvatarBuilder.MIN_CHROMA)
        assertTrue(AvatarBuilder.chroma(skin) > 4 * AvatarBuilder.MIN_CHROMA)
    }

    @Test fun `crop sampling - full-photo coordinates map into the face crop`() {
        val px = Pixels(4, 4, IntArray(16) { (0xFF shl 24) or (it * 10) }, offsetX = 100.0, offsetY = 200.0, scale = 0.5)
        val o = DoubleArray(3)
        assertTrue(px.sampleFull(104.0, 202.0, o))      // → crop (2, 1)
        assertEquals((1 * 4 + 2) * 10.0, o[2], 1e-9)
        assertTrue(!px.sampleFull(50.0, 200.0, o))      // left of the crop
    }

    @Test fun `bake - a colour atlas from colour photos, skin wherever no view sees well, sRGB flag in the GLB`() {
        val skin = intArrayOf(200, 150, 120)
        val m = AvatarBuilder.build(views, listOf(crop(0xC89678), crop(0xC89678), crop(0xC89678)), verts, tris, skin)!!
        assertEquals(AvatarBuilder.TEX * AvatarBuilder.TEX, m.texture.size)
        assertEquals(2048, AvatarBuilder.TEX)
        // every texel is coloured (skin-toned), none grey or white
        val sample = (0 until m.texture.size step 997).map { rgb(m.texture[it]) }
        assertTrue(sample.all { it[0] - it[2] > 40 })
        val glb = AvatarBuilder.glb(m, byteArrayOf(-1, -40, -1), skin, intArrayOf(40, 30, 25))
        val j = Json.parseToJsonElement(GlbWriter.jsonChunk(glb)).jsonObject
        val tex = j["textures"]!!.jsonArray[0].jsonObject
        assertEquals("srgb", tex["extras"]!!.jsonObject["colorSpace"]!!.jsonPrimitive.content)
        val face = j["materials"]!!.jsonArray[0].jsonObject
        assertEquals(0, face["pbrMetallicRoughness"]!!.jsonObject["baseColorTexture"]!!.jsonObject["index"]!!.jsonPrimitive.content.toInt())
        // linear filtering with mipmaps
        val smp = j["samplers"]!!.jsonArray[0].jsonObject
        assertEquals(9729, smp["magFilter"]!!.jsonPrimitive.content.toInt())
        assertEquals(9987, smp["minFilter"]!!.jsonPrimitive.content.toInt())
        // non-shiny skin
        assertTrue(face["pbrMetallicRoughness"]!!.jsonObject["roughnessFactor"]!!.jsonPrimitive.content.toFloat() >= 0.9f)
    }

    @Test fun `white balance - a darker, bluer side photo is matched to the front`() {
        val front = crop(0xC89678)
        val side = crop(0xA07A70)      // ~0.8 exposure and cooler
        val poses = views.map { FacePose.solve(it.face(), f)!! }
        val cams = poses.map { AvatarBuilder.cameraInFace(it) }
        val gains = AvatarBuilder.colourGains(verts, GlbWriter.vertexNormals(verts, tris), views, listOf(front, side, side), cams)
        assertTrue(gains[0].all { it == 1.0 })
        assertEquals(0xC8.toDouble(), 0xA0 * gains[1][0], 2.0)
        assertEquals(0x78.toDouble(), 0x70 * gains[1][2], 2.0)
    }

    @Test fun `views beyond 75 deg never colour a point - the far cheek of a side view stays out`() {
        // only the LEFT view (yaw 35°) has a colour; the subject's LEFT cheek (+x) is seen by it at > 75°
        val skin = intArrayOf(200, 150, 120)
        val m = AvatarBuilder.build(views, listOf(crop(0xC89678), crop(0x00C000), crop(0xC89678)), verts, tris, skin)!!
        fun at(i: Int) = rgb(m.texture[(m.faceUvs[2 * i + 1] * AvatarBuilder.TEX).toInt() * AvatarBuilder.TEX + (m.faceUvs[2 * i] * AvatarBuilder.TEX).toInt()])
        val farCheek = at(454)            // subject's left ear-side point
        assertTrue("far cheek ${farCheek.toList()}", farCheek[1] < 170)    // not green
        val nearCheek = at(234)           // subject's right ear-side point: from the LEFT view, green-dominated
        assertTrue("near cheek ${nearCheek.toList()}", nearCheek[1] > nearCheek[0])
    }
}
