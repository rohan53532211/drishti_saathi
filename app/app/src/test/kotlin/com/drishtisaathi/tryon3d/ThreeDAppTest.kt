package com.drishtisaathi.tryon3d

import com.drishtisaathi.engine.tryon3d.Catalog3D
import com.drishtisaathi.engine.tryon3d.GlbWriter
import com.drishtisaathi.i18n.S3D
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ThreeDAppTest {
    private val assets = File("src/main/assets")

    @Test fun `3D strings are bilingual, the avatar consent says phone-only and deleted with the session`() {
        assertTrue(S3D.allFixed.all { it.en.isNotBlank() && it.hi.isNotBlank() })
        assertTrue(S3D.avatarConsentBody.en.contains("on this phone only"))
        assertTrue(S3D.avatarConsentBody.en.contains("deleted with the session"))
        assertTrue(S3D.avatarConsentBody.en.contains("never uploaded"))
        assertEquals("Simplified 3D preview, not a scan", S3D.avatarLabel.en)
        // material and colour always carry the "estimated" word in the viewer
        assertTrue(S3D.materialLine.format("Metal", S3D.estimated.en).en.endsWith("(estimated)"))
        assertTrue(S3D.colourLine.format("Gold", S3D.estimated.en).en.endsWith("(estimated)"))
        assertEquals("Round", S3D.label("round").en)
        assertEquals("unknown", S3D.label("unknown").en)
        assertTrue(S3D.fitText.format(132, 136).en == "Frame 132 mm, face about 136 mm")
    }

    @Test fun `bundled 3D catalogue - at most 30 frames, files present, small GLBs with the named parts`() {
        val dir = File(assets, CatalogRepository.ASSET_DIR)
        assumeTrue("generated assets not present (git-ignored); run tools/frames_to_glb.py + bundle_frames3d.py", File(dir, "frames_catalog.json").isFile)
        val cat = Catalog3D.parse(File(dir, "frames_catalog.json").readText())
        assertTrue(cat.frames.size in 1..30)
        assertTrue(cat.frames.any { it.category == "sunglasses" })
        cat.frames.forEach { f ->
            val glb = File(dir, f.glb!!)
            assertTrue(glb.isFile && File(dir, f.thumb!!).isFile)
            assertTrue("${f.id} ${glb.length()} B", glb.length() < 300_000)
            assertEquals("estimated", f.dimensionsSource)
            val nodes = Json.parseToJsonElement(GlbWriter.jsonChunk(glb.readBytes())).jsonObject["nodes"]!!.jsonArray
                .mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
            for (n in listOf("frame_front", "lens_L", "lens_R", "temple_L", "temple_R", "contact_shadow")) {
                assertTrue("${f.id} lacks $n: $nodes", n in nodes)
            }
            // anchors: hinges mirror about the bridge origin
            val r = f.anchors.hingesMm.getValue("temple_R")
            val l = f.anchors.hingesMm.getValue("temple_L")
            assertTrue(r[0] > 50 && l[0] < -50)
        }
        val total = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertTrue("bundle ${total / 1e6} MB", total < 5_000_000)
    }

    @Test fun `live try-on and avatar share one canonical-space placement module`() {
        val dir = File("src/main/kotlin/com/drishtisaathi/tryon3d")
        for (name in listOf("LiveTryOn3DScreen.kt", "AvatarScreens.kt")) {
            val t = File(dir, name).readText()
            assertTrue("$name must place glasses with GlassesFit.place", t.contains("GlassesFit.place("))
            assertFalse("$name has its own temple maths", t.contains("templeTransforms") || t.contains("templeSplay"))
        }
        // the viewer gives the 3D view a real height (the old zero-height bug) and keeps the labels out of it
        val avatar = File(dir, "AvatarScreens.kt").readText()
        assertTrue(avatar.contains("fillMaxHeight(0.6f)"))
    }

    @Test fun `avatar data never reaches the bridge, the PDFs or the session database`() {
        val src = File("src/main/kotlin/com/drishtisaathi")
        val outward = listOf("bridge", "pdf", "data").flatMap { File(src, it).walkTopDown().filter { f -> f.extension == "kt" }.toList() }
        assertTrue(outward.isNotEmpty())
        outward.forEach { f ->
            val t = f.readText()
            assertFalse("${f.name} touches the avatar", t.contains("AvatarStore") || t.contains("avatarStore") || t.contains("AvatarMesh"))
        }
    }
}
