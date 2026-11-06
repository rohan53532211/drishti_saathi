package com.drishtisaathi.engine.tryon

import com.drishtisaathi.engine.Frame
import com.drishtisaathi.engine.FrameCatalog
import com.drishtisaathi.engine.TestAssets
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File

class TryOnCatalogTest {
    private val base = FrameCatalog.parse(TestAssets.text(TryOnCatalog.FRAMES_ASSET))
    private val more = FrameCatalog.parse(TestAssets.text(TryOnCatalog.MORE_FRAMES_ASSET))
    private val all = TryOnCatalog.combine(base, more)
    private val assets = File(System.getProperty("assetsDir") ?: "../app/src/main/assets")

    private fun pngSize(f: File): Pair<Int, Int> = DataInputStream(f.inputStream()).use { s ->
        val sig = ByteArray(8).also { s.readFully(it) }
        assertEquals(0x89.toByte(), sig[0])
        s.readInt() // IHDR length
        val type = ByteArray(4).also { s.readFully(it) }
        assertEquals("IHDR", String(type))
        s.readInt() to s.readInt()
    }

    @Test fun `combined demo catalogue has 30 frames with unique ids`() {
        assertEquals(10, base.frames.size)
        assertEquals(20, more.frames.size)
        assertEquals((1..30).map { "F%02d".format(it) }, all.frames.map { it.id })
    }

    @Test fun `every frame has a bundled try-on PNG whose size matches its overlay`() {
        all.frames.forEach { f ->
            val ov = f.overlay!!
            assertEquals(f.image, ov.png)
            val file = File(assets, ov.png)
            assertTrue("${f.id}: ${file.path} missing", file.isFile)
            assertEquals("${f.id} PNG size", ov.widthPx to ov.heightPx, pngSize(file))
            assertTrue(ov.widthPx <= 900)
            // lens centres roughly level and symmetric about the PNG centre
            assertEquals(ov.leftLensCenterPx[1], ov.rightLensCenterPx[1], 0.02 * ov.heightPx)
            assertEquals(ov.widthPx / 2.0, (ov.leftLensCenterPx[0] + ov.rightLensCenterPx[0]) / 2, 0.02 * ov.widthPx)
        }
    }

    @Test fun `estimated sizes of F11 to F30 are plausible`() {
        more.frames.forEach { f ->
            assertTrue(f.id, f.lensWidthMm in 45.0..58.0 && f.bridgeMm in 14.0..24.0 && f.templeMm in 135.0..145.0)
            assertTrue(f.id, f.lensHeightMm in 28.0..50.0 && f.weightGrams > 0 && f.priceInr > 0)
        }
    }

    @Test fun `duplicate ids across catalogues are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { TryOnCatalog.combine(base, base) }
    }

    @Test fun `bad overlay values are rejected`() {
        val text = TestAssets.text(TryOnCatalog.FRAMES_ASSET)
        val swapped = text.replaceFirst("\"leftLensCenterPx\": [", "\"leftLensCenterPx\": [890.0, 1.0, ")
        assertThrows(IllegalArgumentException::class.java) { FrameCatalog.parse(swapped) }
        val outside = Regex("\"frontWidthPx\": [\\d.]+").replaceFirst(text, "\"frontWidthPx\": 5000.0")
        assertThrows(IllegalArgumentException::class.java) { FrameCatalog.parse(outside) }
    }

    @Test fun `frames stored without overlay still decode`() {
        val json = Json { encodeDefaults = true }
        val f = base.frames.first()
        val old = json.encodeToString(Frame.serializer(), f.copy(overlay = null))
            .replace(Regex(""","overlay":null"""), "")
        assertTrue(!old.contains("overlay"))
        assertNull(Json.decodeFromString(Frame.serializer(), old).overlay)
        // and a frame with overlay round-trips
        assertEquals(f, json.decodeFromString(Frame.serializer(), json.encodeToString(Frame.serializer(), f)))
    }
}
