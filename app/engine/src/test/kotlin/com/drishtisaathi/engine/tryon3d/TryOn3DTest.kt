package com.drishtisaathi.engine.tryon3d

import com.drishtisaathi.engine.ColourPref
import com.drishtisaathi.engine.FrameShape
import com.drishtisaathi.engine.TestAssets
import com.drishtisaathi.engine.face.Landmark
import com.drishtisaathi.engine.face.SyntheticFace
import com.drishtisaathi.engine.tryon.TryOnPlacement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

class TryOn3DTest {
    private val sample = """
    {"version": 1, "licence": "demo", "frames": [
     {"id": "k-a1", "name": "Black round acetate", "sku": "a1", "category": "eyeglasses", "shape": "round", "rim": "full",
      "colour": "black", "material": "acetate", "materialEstimated": true,
      "sizes": {"a": 48, "dbl": 20, "temple": 140, "b": 40, "frontWidth": 135, "label": "48-20-140"},
      "dimensionsSource": "estimated", "priceInr": 799, "priceSource": "demo", "glb": "glb/a1.glb", "thumb": "thumb/a1.jpg",
      "source": "Kaggle photo-extruded",
      "anchors": {"origin": "bridge centre", "noseRestMm": [0.0, -3.0, 0.0],
        "lensCentresMm": {"lens_R": [34.0, -12.0], "lens_L": [-34.0, -12.0]},
        "hingesMm": {"temple_R": [65.5, -4.0, 4.0], "temple_L": [-65.5, -4.0, 4.0]},
        "templeEndLocalMm": [0.0, -16.4, 135.9], "units": "mm", "axes": "+X wearer right"},
      "quality": 0.99, "glbBytes": 100000, "someNewField": 1},
     {"id": "k-a2", "name": "Gold square metal", "sku": "a2", "shape": "square", "colour": "gold", "material": "metal",
      "sizes": {"a": 52, "dbl": 18, "temple": 140}, "priceInr": 1499, "glb": "glb/a2.glb", "quality": 0.95},
     {"id": "k-a3", "name": "Gold round metal", "sku": "a3", "shape": "round", "colour": "gold", "material": "metal",
      "sizes": {"a": 55, "dbl": 17, "temple": 145}, "dimensionsSource": "measured", "priceInr": 2299, "glb": "glb/a3.glb", "quality": 0.9},
     {"id": "k-s1", "name": "Black aviator", "sku": "s1", "category": "sunglasses", "shape": "aviator", "colour": "black",
      "material": "metal", "sizes": {"a": 58, "dbl": 14, "temple": 140}, "priceInr": 1899, "glbUrl": "/catalog/k-s1.glb", "quality": 0.97}
    ]}
    """.trimIndent()
    private val cat = Catalog3D.parse(sample)
    private val a1 = cat.frames[0]

    // ---------------- catalogue ----------------

    @Test fun `catalogue parses, ignores unknown fields and keeps the estimated label`() {
        assertEquals(4, cat.frames.size)
        assertTrue(a1.sizesEstimated)
        assertFalse(cat.frames[2].sizesEstimated)
        assertTrue(a1.materialEstimated)
        assertEquals("48-20-140", a1.sizes.label)
        assertEquals("52-18-140", cat.frames[1].sizes.label)
    }

    @Test fun `frame GLB metadata - bridge origin, mm scale and anchors`() {
        assertEquals("bridge centre", a1.anchors.origin)
        assertEquals("mm", a1.anchors.units)
        // lens centres mirror about the bridge origin, hinges at the outer edges of a 135 mm front
        val r = a1.anchors.lensCentresMm.getValue("lens_R")
        val l = a1.anchors.lensCentresMm.getValue("lens_L")
        assertEquals(0.0, r[0] + l[0], 1.0)
        assertEquals(68.0, r[0] - l[0], 0.5)
        assertEquals(65.5, a1.hingeHalfWidthMm, 1e-9)
        assertTrue(a1.hingeHalfWidthMm < a1.frontWidthMm / 2)
        assertEquals(135.0, a1.frontWidthMm, 1e-9)
        // frames without a stored front width: 2A + DBL + end pieces
        assertEquals(2 * 52.0 + 18 + 6, cat.frames[1].frontWidthMm, 1e-9)
    }

    @Test fun `catalogue rejects duplicates and frames without a model`() {
        val dup = sample.replace("\"k-a2\"", "\"k-a1\"")
        assertTrue(runCatching { Catalog3D.parse(dup) }.isFailure)
        val noGlb = sample.replace("\"glbUrl\": \"/catalog/k-s1.glb\", ", "")
        assertTrue(runCatching { Catalog3D.parse(noGlb) }.isFailure)
    }

    @Test fun `filters by shape, rim, colour, material, price, size, category and search`() {
        fun ids(f: CatalogFilter) = f.apply(cat.frames).map { it.id }
        assertEquals(listOf("k-a1", "k-a3"), ids(CatalogFilter(shape = "round")))
        assertEquals(listOf("k-a2", "k-a3"), ids(CatalogFilter(colour = "gold", material = "metal")))
        assertEquals(listOf("k-s1"), ids(CatalogFilter(category = "sunglasses")))
        assertEquals(listOf("k-a1"), ids(CatalogFilter(priceBand = "budget")))
        assertEquals(listOf("k-a2", "k-s1"), ids(CatalogFilter(priceBand = "mid")))
        assertEquals(listOf("k-a1"), ids(CatalogFilter(sizeBand = "small")))
        assertEquals(listOf("k-a2"), ids(CatalogFilter(sizeBand = "medium")))
        assertEquals(listOf("k-a3"), ids(CatalogFilter(query = "gold ROUND")))
        assertEquals(listOf("k-a2"), ids(CatalogFilter(query = "52-18")))
        assertEquals(4, ids(CatalogFilter()).size)
        assertEquals(0, ids(CatalogFilter(rim = "rimless")).size)
    }

    @Test fun `sort by recommendation keeps unranked frames, cheapest first, and by price`() {
        val rec = CatalogOrder.sort(cat.frames, CatalogSort.RECOMMENDED, listOf("k-a3", "k-a1"))
        assertEquals(listOf("k-a3", "k-a1", "k-a2", "k-s1"), rec.map { it.id })
        assertEquals(listOf("k-a1", "k-a2", "k-s1", "k-a3"), CatalogOrder.sort(cat.frames, CatalogSort.PRICE_LOW).map { it.id })
        assertEquals(listOf("k-a3", "k-s1", "k-a2", "k-a1"), CatalogOrder.sort(cat.frames, CatalogSort.PRICE_HIGH).map { it.id })
    }

    @Test fun `catalogue frames go through the existing ranking engine`() {
        val f = Catalog3D.toEngineFrame(a1)
        assertEquals(FrameShape.ROUND, f.shape)
        assertEquals(listOf(ColourPref.DARK), f.colours)
        assertEquals(48.0, f.lensWidthMm, 0.0)
        assertEquals(40.0, f.lensHeightMm, 0.0)
        val gold = Catalog3D.toEngineFrame(cat.frames[1])
        assertEquals(listOf(ColourPref.METALLIC), gold.colours)
        assertTrue(gold.weightGrams < f.weightGrams)
        // the real ranker accepts them (sizes, prices) like frames.json entries
        val ranker = com.drishtisaathi.engine.FrameRanker(TestAssets.rules)
        val est = { v: Double -> com.drishtisaathi.engine.Estimate(v, com.drishtisaathi.engine.Confidence.MEDIUM, com.drishtisaathi.engine.Source.MEASURED) }
        val ranked = ranker.rank(cat.frames.map(Catalog3D::toEngineFrame), est(140.0), est(63.0), emptyList(),
            TestAssets.rules.materials.first().id, null, null, topN = 10)
        assertEquals(4, ranked.size)
    }

    // ---------------- fit maths ----------------

    @Test fun `PD scaling - view px per mm from the pupil distance, corrected for yaw`() {
        assertEquals(4.0, Fit3D.pxPerMmFromPd(252.0, 63.0, 0.0), 1e-9)
        // turned 30°: the pupils look closer; the head-on equivalent is restored
        val seen = 252.0 * kotlin.math.cos(Math.toRadians(30.0))
        assertEquals(4.0, Fit3D.pxPerMmFromPd(seen, 63.0, Math.toRadians(30.0)), 1e-9)
        assertEquals(140.0, Fit3D.headWidthMm(560.0, 4.0, 0.0), 1e-9)
    }

    @Test fun `measure - synthetic face gives the right scale, head width and source`() {
        val face = SyntheticFace.frame(SyntheticFace.Spec(pdMm = 63.0, faceWidthMm = 140.0, mmPerPx = 0.25))
        val toView = { x: Double, y: Double -> x * face.imageWidthPx to y * face.imageHeightPx }
        val (card, src) = Fit3D.measure(face, toView, pdMm = 63.0)!!
        assertEquals(Scale3DSource.CARD_PD, src)
        assertEquals(4.0, card.pxPerMm, 1e-3)
        assertEquals(140.0, card.headWidthMm, 0.5)
        val (dflt, src2) = Fit3D.measure(face, toView)!!
        assertEquals(Scale3DSource.DEFAULT_PD, src2)
        assertEquals(4.0, dflt.pxPerMm, 1e-3)          // default PD is 63 too
        val (_, src3) = Fit3D.measure(face, toView, faceWidthMm = 140.0)!!
        assertEquals(Scale3DSource.FACE_WIDTH, src3)
        assertNull(Fit3D.measure(null, toView))
    }

    @Test fun `temple bend - splay reaches the head side and is clamped`() {
        val cfg = TryOn3DConfig()
        // head 140 mm, hinge at 65.5: ear at 68 → atan(2.5 / 98)
        assertEquals(Math.toDegrees(atan2(2.5, 98.0)), Fit3D.templeSplayDeg(65.5, 140.0, cfg), 1e-9)
        assertTrue(Fit3D.templeSplayDeg(65.5, 160.0, cfg) > Fit3D.templeSplayDeg(65.5, 140.0, cfg))
        assertTrue(Fit3D.templeSplayDeg(65.5, 120.0, cfg) < 0)               // narrow head: temples bend in
        assertEquals(cfg.maxSplayDeg, Fit3D.templeSplayDeg(65.5, 400.0, cfg), 0.0)
        assertEquals(cfg.minSplayDeg, Fit3D.templeSplayDeg(65.5, 20.0, cfg), 0.0)
        // the right temple's tip moves to +X, the left one's to −X, both stay hinged
        val (tr, tl) = Fit3D.templeTransforms(a1, 10.0)
        val endR = Mat4.apply(tr, 0.0, 0.0, 100.0)
        val endL = Mat4.apply(tl, 0.0, 0.0, 100.0)
        assertEquals(65.5 + 100 * kotlin.math.sin(Math.toRadians(10.0)), endR[0], 1e-4)
        assertEquals(-endR[0], endL[0], 1e-4)
        assertEquals(listOf(65.5, -4.0, 4.0), Mat4.apply(tr, 0.0, 0.0, 0.0).map { (it * 1e4).toLong() / 1e4 })
    }

    @Test fun `model matrix - nose rest lands on the nose point, lenses straddle it at PD scale`() {
        val p = PoseParams(anchorX = 360.0, anchorY = -400.0, pxPerMm = 4.0, yaw = 0.0, pitch = 0.0, roll = 0.0, headWidthMm = 140.0)
        val cfg = TryOn3DConfig(bridgeDropMm = 0.0)
        val pose = Fit3D.pose(p, a1, Scale3DSource.CARD_PD, cfg)
        val nose = Mat4.apply(pose.model, 0.0, -3.0, 0.0)
        assertEquals(360.0, nose[0], 1e-4); assertEquals(-400.0, nose[1], 1e-4)
        // un-mirrored image: the wearer's right lens is on the image left
        val r = Mat4.apply(pose.model, 34.0, -12.0, 0.0)
        val l = Mat4.apply(pose.model, -34.0, -12.0, 0.0)
        assertTrue(r[0] < l[0])
        assertEquals(68 * 4.0, l[0] - r[0], 1e-3)
        assertEquals(-400.0 - 9 * 4.0, r[1], 1e-3)
        // the temples point away from the viewer (into the screen)
        assertTrue(Mat4.apply(pose.model, 65.5, -4.0, 100.0)[2] < Mat4.apply(pose.model, 65.5, -4.0, 4.0)[2])
        // with a bridge drop the frame sits lower
        val dropped = Fit3D.pose(p, a1, Scale3DSource.CARD_PD, TryOn3DConfig(bridgeDropMm = 2.0))
        assertEquals(-400.0 - 8.0, Mat4.apply(dropped.model, 0.0, -3.0, 0.0)[1], 1e-4)
        // a proper rotation (determinant > 0) keeps back-face culling right
        val m = pose.model
        val det = m[0] * (m[5] * m[10] - m[9] * m[6]) - m[4] * (m[1] * m[10] - m[9] * m[2]) + m[8] * (m[1] * m[6] - m[5] * m[2])
        assertTrue(det > 0)
    }

    @Test fun `model matrix follows yaw from the transformation matrix and roll from the pupils`() {
        val p0 = PoseParams(0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 140.0)
        val turned = Fit3D.pose(p0.copy(yaw = Math.toRadians(30.0)), a1, Scale3DSource.CARD_PD)
        val flat = Fit3D.pose(p0, a1, Scale3DSource.CARD_PD)
        fun width(m: FloatArray) = abs(Mat4.apply(m, 34.0, -12.0, 0.0)[0] - Mat4.apply(m, -34.0, -12.0, 0.0)[0])
        assertEquals(width(flat.model) * kotlin.math.cos(Math.toRadians(30.0)), width(turned.model), 1e-3)
        val rolled = Fit3D.pose(p0.copy(roll = Math.toRadians(10.0)), a1, Scale3DSource.CARD_PD)
        val r = Mat4.apply(rolled.model, 34.0, -12.0, 0.0)
        val l = Mat4.apply(rolled.model, -34.0, -12.0, 0.0)
        assertEquals(10.0, Math.toDegrees(atan2(l[1] - r[1], l[0] - r[0])), 1e-3)
        // yaw read back from a MediaPipe-style matrix
        val (yaw, _) = TryOnPlacement.yawPitch(TryOnPlacement.rotationMatrix(20.0, 0.0))
        assertEquals(20.0, Math.toDegrees(yaw), 1e-4)
    }

    @Test fun `fit verdict text thresholds`() {
        assertEquals(FitWord.GOOD, Fit3D.fitVerdict(132.0, 136.0))
        assertEquals(FitWord.SLIGHTLY_NARROW, Fit3D.fitVerdict(128.0, 136.0))
        assertEquals(FitWord.TOO_NARROW, Fit3D.fitVerdict(120.0, 136.0))
        assertEquals(FitWord.SLIGHTLY_WIDE, Fit3D.fitVerdict(144.0, 136.0))
        assertEquals(FitWord.TOO_WIDE, Fit3D.fitVerdict(150.0, 136.0))
    }

    // ---------------- smoothing ----------------

    @Test fun `one-euro filter - still input is smoothed, a step is followed, no overshoot`() {
        val f = OneEuroFilter(1.0, 0.0)
        var v = 0.0
        var t = 0L
        repeat(30) { v = f.filter(if (it % 2 == 0) 1.0 else -1.0, t); t += 33 }
        assertTrue("jitter damped: $v", abs(v) < 0.5)
        val g = OneEuroFilter(1.0, 0.05)
        var out = 0.0
        t = 0
        g.filter(0.0, t)
        repeat(60) { t += 33; out = g.filter(100.0, t) }
        assertTrue(out > 99.0 && out <= 100.0)
    }

    @Test fun `pose smoother restarts after the face is lost`() {
        val s = PoseSmoother()
        val a = PoseParams(0.0, 0.0, 4.0, 0.0, 0.0, 0.0, 140.0)
        s.update(a, 0)
        val moved = s.update(a.copy(anchorX = 100.0), 33)!!
        assertTrue(moved.anchorX in 1.0..99.0)
        assertNull(s.update(null, 66))
        assertEquals(100.0, s.update(a.copy(anchorX = 100.0), 1000)!!.anchorX, 1e-9)
    }

    // ---------------- cache and fallback ----------------

    @Test fun `LRU cache - evicts least recently used past the byte limit, never the new entry`() {
        val c = LruIndex(100)
        assertEquals(emptyList<String>(), c.put("a", 40))
        assertEquals(emptyList<String>(), c.put("b", 40))
        c.touch("a")
        assertEquals(listOf("b"), c.put("c", 40))
        assertEquals(80L, c.bytes)
        assertEquals(listOf("a", "c"), c.keys)
        assertEquals(listOf("a", "c"), c.put("huge", 500))
        assertEquals(listOf("huge"), c.keys)
        c.remove("huge")
        assertEquals(0L, c.bytes)
        assertEquals(100L * 1024 * 1024, LruIndex(100L * 1024 * 1024).maxBytes)
    }

    @Test fun `fallback to 2D - renderer failure, GLB failure, slow phone`() {
        val ok = Render3DFallback()
        var t = 0L
        repeat(200) { ok.onFrame(t); t += 33 }      // ~30 fps
        assertEquals(Render3DFallback.Mode.THREE_D, ok.mode)
        val slow = Render3DFallback()
        t = 0
        repeat(60) { slow.onFrame(t); t += 100 }    // 10 fps
        assertEquals(Render3DFallback.Mode.TWO_D, slow.mode)
        assertEquals(Render3DFallback.Reason.SLOW, slow.reason)
        val early = Render3DFallback()
        repeat(10) { early.onFrame(it * 100L) }      // slow but still within the grace period
        assertEquals(Render3DFallback.Mode.THREE_D, early.mode)
        val glb = Render3DFallback().also { it.glbFailed() }
        assertEquals(Render3DFallback.Reason.GLB_FAILED, glb.reason)
        val none = Render3DFallback().also { it.rendererFailed(); it.glbFailed() }
        assertEquals(Render3DFallback.Reason.NO_RENDERER, none.reason)   // first reason kept
    }

    // ---------------- GLB writer and avatar ----------------

    @Test fun `GLB writer - valid header, chunks and accessors`() {
        val w = GlbWriter()
        val img = w.addImage(byteArrayOf(1, 2, 3), "image/jpeg")
        val m = w.addMaterial(GlbWriter.Material("m", image = img))
        val pos = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)
        w.addMesh("tri", pos, intArrayOf(0, 1, 2), m, GlbWriter.vertexNormals(pos, intArrayOf(0, 1, 2)), floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f))
        val glb = w.build()
        val b = java.nio.ByteBuffer.wrap(glb).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546C67, b.getInt(0))
        assertEquals(2, b.getInt(4))
        assertEquals(glb.size, b.getInt(8))
        assertEquals(0, glb.size % 4)
        val j = Json.parseToJsonElement(GlbWriter.jsonChunk(glb)).jsonObject
        assertEquals("2.0", j["asset"]!!.jsonObject["version"]!!.jsonPrimitive.content)
        val acc = j["accessors"]!!.jsonArray
        assertEquals("3", acc[0].jsonObject["count"]!!.jsonPrimitive.content)
        assertEquals("1.0", acc[0].jsonObject["max"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("image/jpeg", j["images"]!!.jsonArray[0].jsonObject["mimeType"]!!.jsonPrimitive.content)
        // CCW triangle in the XY plane: normal +Z
        assertEquals(1f, GlbWriter.vertexNormals(pos, intArrayOf(0, 1, 2))[2], 1e-6f)
    }

    @Test fun `head occluder sphere - valid GLB with outward-facing triangles`() {
        val glb = GlbWriter.unitSphereGlb(8, 12)
        val j = Json.parseToJsonElement(GlbWriter.jsonChunk(glb)).jsonObject
        assertEquals("occluder", j["nodes"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        // rebuild the same grid and check the winding: normal · centroid > 0 for every non-degenerate triangle
        val rings = 8; val seg = 12; val w = seg + 1
        fun p(r: Int, s: Int): DoubleArray {
            val th = Math.PI * r / rings; val ph = 2 * Math.PI * s / seg
            return doubleArrayOf(kotlin.math.sin(th) * kotlin.math.cos(ph), kotlin.math.cos(th), kotlin.math.sin(th) * kotlin.math.sin(ph))
        }
        var checked = 0
        for (r in 0 until rings) for (s in 0 until seg) {
            for ((a, b, c) in listOf(Triple(p(r, s), p(r, s + 1), p(r + 1, s)), Triple(p(r, s + 1), p(r + 1, s + 1), p(r + 1, s)))) {
                val u = DoubleArray(3) { b[it] - a[it] }; val v = DoubleArray(3) { c[it] - a[it] }
                val n = doubleArrayOf(u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])
                if (hypot(hypot(n[0], n[1]), n[2]) < 1e-9) continue
                val m = DoubleArray(3) { (a[it] + b[it] + c[it]) / 3 }
                assertTrue(n[0] * m[0] + n[1] * m[1] + n[2] * m[2] > 0)
                checked++
            }
        }
        assertTrue(checked > 100)
    }

    private fun avatarLandmarks(): List<Landmark> {
        // canonical-ish face: points on an ellipsoid cap, iris 63 mm apart at 4 px/mm in a 640×480 image
        val pts = MutableList(478) { Landmark(0.5f, 0.5f, 0f) }
        val ovalSet = AvatarMesh.FACE_OVAL.toSet()
        AvatarMesh.FACE_OVAL.forEachIndexed { k, i ->
            val a = 2 * Math.PI * k / AvatarMesh.FACE_OVAL.size
            pts[i] = Landmark((0.5 + 0.11 * kotlin.math.sin(a)).toFloat(), (0.45 - 0.2 * kotlin.math.cos(a)).toFloat(), 0.06f)
        }
        for (i in 0 until 468) if (i !in ovalSet) pts[i] = Landmark(0.5f + ((i % 7) - 3) * 0.01f, 0.45f + ((i % 5) - 2) * 0.02f, -0.02f)
        pts[234] = Landmark(0.39f, 0.45f, 0.06f); pts[454] = Landmark(0.61f, 0.45f, 0.06f)
        pts[6] = Landmark(0.5f, 0.42f, -0.03f)
        pts[468] = Landmark((0.5 - 126.0 / 640).toFloat(), 0.42f, 0f)
        pts[473] = Landmark((0.5 + 126.0 / 640).toFloat(), 0.42f, 0f)
        return pts
    }

    private val triangles: IntArray by lazy {
        Json.parseToJsonElement(TestAssets.text("face_mesh_triangles.json")).jsonObject["triangles"]!!.jsonArray
            .map { it.jsonPrimitive.content.toInt() }.toIntArray()
    }

    @Test fun `face mesh topology asset - 898 triangles on 468 points`() {
        assertEquals(898 * 3, triangles.size)
        assertTrue(triangles.all { it in 0 until 468 })
    }

    @Test fun `avatar - mm scale from the PD, nose at the origin, wearer's right on +X, closed shell`() {
        val g = AvatarMesh.geometry(avatarLandmarks(), 640, 480, triangles, pdMm = 63.0)
        assertEquals(4.0, g.pxPerMm, 1e-3)
        assertFalse(g.approximate)
        assertEquals(0f, g.positions[3 * 6], 1e-6f); assertEquals(0f, g.positions[3 * 6 + 1], 1e-6f)
        // landmark 234 is on the image left = the wearer's right = +X
        assertTrue(g.positions[3 * 234] > 0)
        assertEquals(0.22 * 640 / 4.0, g.faceWidthMm, 0.1)
        // every shell triangle uses shell vertices; the shell meets the face outline
        val nShell = g.shellPositions.size / 3
        assertTrue(g.shellIndices.all { it in 0 until nShell })
        assertEquals(AvatarMesh.FACE_OVAL.size * (AvatarMesh.SHELL_RINGS + 1) + 1, nShell)
        assertEquals(g.positions[3 * 10], g.shellPositions[0], 1e-6f)
        // the shell reaches behind the face
        val maxZ = (0 until nShell).maxOf { g.shellPositions[3 * it + 2] }
        assertTrue(maxZ > (0 until 468).maxOf { g.positions[3 * it + 2] })
        // without a PD it is labelled approximate
        assertTrue(AvatarMesh.geometry(avatarLandmarks(), 640, 480, triangles, pdMm = null).approximate)
        val glb = AvatarMesh.glb(g, byteArrayOf(-1, -40, -1), intArrayOf(200, 160, 140))
        val j = Json.parseToJsonElement(GlbWriter.jsonChunk(glb)).jsonObject
        assertEquals(listOf("avatar_face", "avatar_shell"), j["nodes"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
    }

    @Test fun `avatar and live try-on use the same frame anchors`() {
        // the avatar origin is the nose point; the frame's nose rest maps to the anchor in live try-on
        val p = PoseParams(10.0, 20.0, 2.0, 0.0, 0.0, 0.0, 140.0)
        val m = Fit3D.pose(p, a1, Scale3DSource.CARD_PD, TryOn3DConfig(bridgeDropMm = 0.0)).model
        val n = Mat4.apply(m, a1.anchors.noseRestMm[0], a1.anchors.noseRestMm[1], a1.anchors.noseRestMm[2])
        assertEquals(10.0, n[0], 1e-4); assertEquals(20.0, n[1], 1e-4)
        assertEquals(0.0, hypot(n[0] - 10.0, n[1] - 20.0), 1e-4)
    }
}
