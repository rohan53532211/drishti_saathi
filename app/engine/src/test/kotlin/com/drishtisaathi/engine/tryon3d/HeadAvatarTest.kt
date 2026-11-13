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
import kotlin.math.abs

/** Segmentation-gated texture, fill, watertight head, hair volume (the head avatar after the S23 FE phone test). */
class HeadAvatarTest {
    private val mesh = Json.parseToJsonElement(File("../app/src/main/assets/face_mesh_triangles.json").readText()).jsonObject
    private val canon = mesh["vertices"]!!.jsonArray.map { it.jsonPrimitive.content.toFloat() }.toFloatArray()
    private val tris = mesh["triangles"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }.toIntArray()
    private val template = HeadTemplate.parse(File("../app/src/main/assets/head_template.json").readText())
    private val f = 1500.0
    private val w = 1920
    private val h = 2560
    private val skin = intArrayOf(200, 150, 120)
    private val hair = intArrayOf(90, 50, 20)

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

    private val views = listOf(view(AvatarStep.FRONT, 0.0), view(AvatarStep.LEFT, 35.0), view(AvatarStep.RIGHT, -35.0))

    /** A whole-photo "crop" at 1/4 resolution in one colour, with a class mask (one class everywhere, or a function). */
    private fun photo(rgb: Int, cls: (Int, Int) -> Int?): Pixels {
        val pw = w / 4; val ph = h / 4
        val mask = ByteArray(128 * 170) { i -> (cls(i % 128, i / 128) ?: 0).toByte() }
        return Pixels(pw, ph, IntArray(pw * ph) { (0xFF shl 24) or rgb }, 0.0, 0.0, 0.25, mask.takeIf { cls(0, 0) != null }, 128, 170)
    }

    private fun build(px: Pixels) = AvatarBuilder.buildHeadDetailed(views, listOf(px, px, px), canon, tris, template, skin, hair)!!

    private fun near(c: Int, r: Int, g: Int, b: Int, tol: Int = 4) =
        abs(((c shr 16) and 255) - r) <= tol && abs(((c shr 8) and 255) - g) <= tol && abs((c and 255) - b) <= tol

    @Test fun `segmentation gating - background pixels are never used, every texel still filled and coloured`() {
        val hb = build(photo(0x00C800) { _, _ -> SegClass.BACKGROUND })   // a green "background" everywhere
        val tex = hb.model.texture
        assertEquals(0, hb.bake.covered)                                   // not one photo pixel taken
        assertTrue(tex.none { near(it, 0, 200, 0, 40) })                   // no green anywhere
        assertTrue(tex.all { (it ushr 24) == 0xFF })                       // opaque
        // nothing grey: every texel is skin / neck tone or hair (chroma well above a grey's)
        assertTrue(tex.all { c -> val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255; maxOf(r, g, b) - minOf(r, g, b) >= 30 })
        // and no hair volume without hair pixels
        assertTrue(hb.hairOffsets.all { it == 0f })
    }

    @Test fun `class gating - skin pixels colour the face, never the scalp, the scalp falls back to the hair colour`() {
        val hb = build(photo(0x3C8CDC) { _, _ -> SegClass.FACE_SKIN })
        val m = hb.model
        fun texAt(i: Int): Int {
            val u = (m.faceUvs[2 * i] * AvatarBuilder.TEX).toInt().coerceIn(0, AvatarBuilder.TEX - 1)
            val v = (m.faceUvs[2 * i + 1] * AvatarBuilder.TEX).toInt().coerceIn(0, AvatarBuilder.TEX - 1)
            return m.texture[v * AvatarBuilder.TEX + u]
        }
        val face = (0 until template.vertexCount).filter { template.region[it] == HeadTemplate.FACE && m.faceVertices[3 * it + 2] > 3f }
        assertTrue(face.count { near(texAt(it), 0x3C, 0x8C, 0xDC) } > face.size * 0.8)
        // the scalp's interior (the hairline band may take skin or hair pixels: the photo decides there)
        val hairness = HairShell.hairness(template.region, template.triangles, template.weld)
        val adj = HeadMesh.adjacency(template.triangles, template.weld, template.vertexCount)
        val scalp = (0 until template.vertexCount).filter { i ->
            val r = template.weld[i]
            template.region[i] == HeadTemplate.SCALP && hairness[i] > 0.9f && adj[r].all { template.region[it] == HeadTemplate.SCALP }
        }
        assertTrue(scalp.size > 100)
        val blue = scalp.filter { near(texAt(it), 0x3C, 0x8C, 0xDC, 10) }
        assertTrue("${blue.size} of ${scalp.size} scalp texels blue, e.g. " + blue.take(3).map { "#$it (${m.faceVertices[3 * it]}, ${m.faceVertices[3 * it + 1]}, ${m.faceVertices[3 * it + 2]}) uv ${m.faceUvs[2 * it]},${m.faceUvs[2 * it + 1]}" }, blue.size <= scalp.size / 50)   // ≤ 2%: texels shared with the ear where its UVs fold over the scalp
        // hair fallback, softly shaded (0.78–1.0 of the hair colour)
        assertTrue(scalp.count { val c = texAt(it); val r = (c shr 16) and 255; r in 68..92 } > scalp.size * 0.6)
    }

    @Test fun `fill - no uncovered texels - photo, inpainted or fallback, plus a gutter`() {
        val hb = build(photo(0xB48C78) { x, _ -> if (x < 64) SegClass.FACE_SKIN else SegClass.CLOTHES })   // half the photo usable
        val b = hb.bake
        assertTrue(b.covered > 0 && b.inpainted > 0)
        assertTrue(hb.model.texture.all { (it ushr 24) == 0xFF })
        // inpainting grows photo colour into neighbouring uncovered texels instead of leaving holes
        assertTrue(b.inpainted > b.covered / 50)
    }

    @Test fun `watertight - the head and neck are closed and consistently oriented, no folds after deformation`() {
        assertTrue(HeadMesh.watertight(template.vertices, template.triangles))
        // neck reaches the collar: well below the chin
        val minY = (0 until template.vertexCount).minOf { template.vertices[3 * it + 1] }
        assertTrue(minY < canon[3 * 152 + 1] - 8f)
        val hb = build(photo(0xB48C78) { _, _ -> SegClass.FACE_SKIN })
        assertEquals(0, hb.folds)
        assertTrue(HeadMesh.watertight(hb.model.faceVertices, hb.model.faceTriangles))
        // a longer jaw (chin 1.2 cm lower) still leaves no folded (back-facing, see-through) triangles
        val longJaw = canon.copyOf().also { a -> for (i in 0 until 468) if (a[3 * i + 1] < -3f) a[3 * i + 1] -= 1.2f * ((-3f - a[3 * i + 1]) / 6f).coerceIn(0f, 1f) }
        val d = HeadDeform.deform(template, canon, longJaw)
        val v = d.vertices.copyOf()
        val left = HeadMesh.unfold(template.vertices, v, template.triangles, template.weld, { template.region[it] != HeadTemplate.FACE })
        assertEquals(0, left)
        // the GLB material is opaque and double-sided
        val j = Json.parseToJsonElement(GlbWriter.jsonChunk(AvatarBuilder.glb(hb.model, byteArrayOf(-1, -40, -1), skin, hair))).jsonObject
        val mat = j["materials"]!!.jsonArray[0].jsonObject
        assertEquals("true", mat["doubleSided"]!!.jsonPrimitive.content)
        assertTrue(mat["alphaMode"] == null)
    }

    @Test fun `hair volume - pushed out to the hair outline, capped at 8 cm, smooth, flat where the temples pass`() {
        // hair everywhere: the walk runs to the cap, then smoothing and the hairline taper
        val hb = build(photo(0x281E14) { _, _ -> SegClass.HAIR })
        val o = hb.hairOffsets
        assertTrue(o.all { it in 0f..HairShell.MAX_CM.toFloat() })
        assertTrue(o.max() > 3f)
        val weld = template.weld
        val adj = HeadMesh.adjacency(template.triangles, weld, template.vertexCount)
        var worst = 0f; var wi = 0; var wj = 0
        val v = hb.model.faceVertices
        val tv = template.vertices
        fun len(i: Int, j: Int) = kotlin.math.sqrt((0 until 3).sumOf { c -> ((tv[3 * i + c] - tv[3 * j + c]) * (tv[3 * i + c] - tv[3 * j + c])).toDouble() }).toFloat()
        // smooth = no cliff: the offset changes by less than the edge length between neighbours (slope under 45°+)
        for (i in 0 until template.vertexCount) if (weld[i] == i) for (j in adj[i]) {
            val slope = abs(o[i] - o[j]) / maxOf(len(i, j), 0.3f)
            if (slope > worst) { worst = slope; wi = i; wj = j }
        }
        fun at(i: Int) = "#$i r${template.region[i]} o=${o[i]} (${v[3 * i]}, ${v[3 * i + 1]}, ${v[3 * i + 2]}) n${adj[i].size}"
        assertTrue("steepest offset slope $worst: ${at(wi)} / ${at(wj)}", worst < 1.5f)
        // non-hair vertices never move; seam duplicates move together
        for (i in 0 until template.vertexCount) {
            if (template.region[weld[i]] == HeadTemplate.FACE) assertEquals(0f, o[i])
            assertEquals(o[weld[i]], o[i])
        }
        // temples: the band in front of the ears stays nearly flat
        val rest = hb.model.anchors.earRestL!!
        val band = (0 until template.vertexCount).filter { i ->
            val x = hb.model.faceVertices[3 * i]; val y = hb.model.faceVertices[3 * i + 1]; val z = hb.model.faceVertices[3 * i + 2]
            x > 4 && abs(y - rest.y) < 1.0 && z > rest.z + 1
        }
        assertTrue(band.all { o[it] <= 0.15f * HairShell.MAX_CM.toFloat() + 1e-3f })
        // no mask at all: no volume
        val plain = AvatarBuilder.buildHeadDetailed(views, List(3) { Pixels(w / 4, h / 4, IntArray((w / 4) * (h / 4)) { -1 }, 0.0, 0.0, 0.25) },
            canon, tris, template, skin, hair)!!
        assertTrue(plain.hairOffsets.all { it == 0f })
    }

    @Test fun `neck - width from the body-skin silhouette in the front photo`() {
        // skin in a vertical band 12 cm wide at the subject (40 cm away, f 1500: 450 px full, 28 class px), clothes elsewhere
        val half = 14
        val hb = build(photo(0xB48C78) { x, _ -> if (abs(x - 64) <= half) SegClass.BODY_SKIN else SegClass.CLOTHES })
        val wcm = hb.neck.widthCm!!
        assertTrue("neck width $wcm", wcm in 10.0..14.5)
    }
}
