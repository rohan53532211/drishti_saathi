package com.drishtisaathi.engine.tryon3d

import com.drishtisaathi.engine.config.TryOnConfig
import com.drishtisaathi.engine.face.FaceFrame
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

class GlassesFitTest {
    private val mesh = Json.parseToJsonElement(File("../app/src/main/assets/face_mesh_triangles.json").readText()).jsonObject
    private val verts = mesh["vertices"]!!.jsonArray.map { it.jsonPrimitive.content.toFloat() }.toFloatArray()
    private val tris = mesh["triangles"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }.toIntArray()

    private val frame = Frame3D(
        id = "t", name = "Test", shape = "rectangle", colour = "black", material = "acetate", priceInr = 999,
        sizes = Sizes3D(52, 18, 140, b = 40, frontWidth = 135),
        anchors = Anchors3D(
            noseRestMm = listOf(0.0, -8.0, 0.0),
            lensCentresMm = mapOf("lens_R" to listOf(35.0, 2.0), "lens_L" to listOf(-35.0, 2.0)),
            hingesMm = mapOf("temple_R" to listOf(66.0, 10.0, 4.0), "temple_L" to listOf(-66.0, 10.0, 4.0)),
            templeEndLocalMm = listOf(0.0, -16.43, 135.9),
        ),
    )

    private fun near(e: Double, a: Double, tol: Double, what: String = "") = assertTrue("$what expected $e got $a", abs(e - a) <= tol)
    private fun at(m: FloatArray, p: V3) = Mat4.apply(m, p.x, p.y, p.z).let { V3(it[0], it[1], it[2]) }

    @Test fun `canonical anchors - computed from the bundled canonical mesh, PD about 63-64 mm`() {
        val a = FaceAnchors.fromVertices(verts)
        val c = FaceAnchors.CANONICAL
        for ((x, y) in listOf(a.nasion to c.nasion, a.pupilR to c.pupilR, a.pupilL to c.pupilL, a.earR to c.earR, a.earL to c.earL)) {
            assertTrue("$x vs $y", x.dist(y) < 0.01)
        }
        near(63.6, c.pdMm, 0.2, "canonical PD")
        assertTrue("subject's right is −x", c.pupilR.x < 0 && c.earR.x < 0)
    }

    @Test fun `placement - bridge 11 mm in front of the nasion, pupils at 55 percent of the lens height, lens centres level with the pupils`() {
        val cfg = GlassesFitConfig.from(TryOnConfig())
        val p = GlassesFit.place(frame, null, cfg = cfg)
        val c = FaceAnchors.CANONICAL
        near(0.0, p.pupilTarget.x, 1e-6, "x")
        near(c.pupilR.y, p.pupilTarget.y, 1e-6, "pupil height")
        near(c.nasion.z + 1.1, p.pupilTarget.z, 1e-6, "bridge forward")
        // lens bottom = centre − b/2 = −18 mm; pupils at −18 + 22 = +4 mm → lens centre (2 mm) is 2 mm below the pupils
        p.lensCentres.forEach { near(c.pupilR.y - 0.2, it.y, 1e-6, "lens centre height") }
        assertTrue("lens_R on the subject's right (−x)", p.lensCentres[0].x < 0 && p.lensCentres[1].x > 0)
        near(-3.5, p.lensCentres[0].x, 1e-6)
        // the knee of each temple (end of its straight part) lands on the ear rest point
        val straight = GlassesFit.templeStraightMm(frame)
        near(105.0, straight, 0.2, "straight temple")
        for ((tm, ear) in listOf(p.templeR to p.earR, p.templeL to p.earL)) {
            val knee = at(Mat4.mul(p.model, tm), V3(0.0, 0.0, straight))
            assertTrue("knee $knee ear $ear", knee.dist(ear) < 0.2)
        }
        near(c.nasion.z - 9.5, p.earR.z, 1e-6, "ear 95 mm behind the nasion")
        assertTrue("ear below the pupils", p.earR.y < c.pupilR.y)
    }

    @Test fun `scale from PD - canonical PD over measured PD, also for the fit width`() {
        val c = FaceAnchors.CANONICAL
        val p70 = GlassesFit.place(frame, 70.0)
        near(c.pdMm / 70.0, p70.scale, 1e-9)
        val p0 = GlassesFit.place(frame, null)
        near(1.0, p0.scale, 1e-9)
        val w70 = p70.lensCentres[0].dist(p70.lensCentres[1])
        val w0 = p0.lensCentres[0].dist(p0.lensCentres[1])
        near(w0 * c.pdMm / 70.0, w70, 1e-6, "lens spacing")
        near(153.3, GlassesFit.headWidthMm(c, null), 0.1)
        near(153.3 * 70 / c.pdMm, GlassesFit.headWidthMm(c, 70.0), 0.1)
        near(1.0, GlassesFit.place(frame, 20.0).scale, 1e-9)   // nonsense PD → no scaling
    }

    @Test fun `projection from intrinsics - the GL matrix maps camera points onto the same view pixels as the pinhole`() {
        val vi = ViewIntrinsics(focalPx = 520.0, imageW = 480, imageH = 640, viewW = 1080.0, viewH = 1700.0)
        val m = vi.projection(1.0, 1000.0)
        for (p in listOf(V3(0.0, 0.0, -40.0), V3(3.0, -2.0, -45.0), V3(-6.0, 7.5, -60.0))) {
            val cx = m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12]
            val cy = m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13]
            val cw = m[3] * p.x + m[7] * p.y + m[11] * p.z + m[15]
            val u = (cx / cw + 1) / 2 * vi.viewW
            val v = (1 - cy / cw) / 2 * vi.viewH
            val (eu, ev) = vi.project(p, mirrored = false)
            near(eu, u, 1e-6, "u"); near(ev, v, 1e-6, "v")
        }
        // the optical axis hits the view centre of the image (FILL_CENTER keeps it centred)
        val (u0, v0) = vi.project(V3(0.0, 0.0, -50.0), false)
        near(vi.viewW / 2, u0, 1e-9); near(vi.viewH / 2, v0, 1e-9)
    }

    /** A synthetic camera frame: the canonical face posed by [pose], projected with focal [f]; irises at the eye centres. */
    private fun synthFace(pose: FloatArray, f: Double, w: Int = 480, h: Int = 640): FaceFrame {
        fun proj(p: V3): Landmark {
            val q = at(pose, p)
            return Landmark(((w / 2.0 + f * q.x / -q.z) / w).toFloat(), ((h / 2.0 - f * q.y / -q.z) / h).toFloat(), 0f)
        }
        val lm = (0 until 468).map { proj(V3(verts[it * 3].toDouble(), verts[it * 3 + 1].toDouble(), verts[it * 3 + 2].toDouble())) }.toMutableList()
        val c = FaceAnchors.CANONICAL
        repeat(5) { lm += proj(c.pupilR) }      // 468–472 (MediaPipe's first iris)
        repeat(5) { lm += proj(c.pupilL) }      // 473–477
        // MediaPipe gives the matrix with ITS assumed camera; only the rotation is used, so perturb the translation
        val mp = pose.copyOf().also { it[12] *= 1.3f; it[14] *= 0.8f }
        return FaceFrame(lm, w, h, transformMatrix = mp, focalLengthPx = f, mirrored = true)
    }

    @Test fun `pose and mirror - glasses follow the face at 0 and plus-minus 30 deg yaw, and look down`() {
        val f = 520.0
        val vi = ViewIntrinsics(f, 480, 640, 1080.0, 1700.0)
        for ((yaw, pitch) in listOf(0.0 to 0.0, 30.0 to 0.0, -30.0 to 0.0, 0.0 to -20.0)) {
            val truth = Mat4.mul(Mat4.translate(1.5, -2.0, -42.0), Mat4.rotY(yaw * PI / 180), Mat4.rotX(pitch * PI / 180))
            val face = synthFace(truth, f)
            val pose = FacePose.solve(face, f)!!
            // the solved pose reproduces the true one (translation within 2 mm, it only had the image to go on)
            assertTrue("yaw $yaw: t", V3(pose[12].toDouble(), pose[13].toDouble(), pose[14].toDouble()).dist(V3(1.5, -2.0, -42.0)) < 0.2)
            val (y, p, _) = FacePose.angles(pose)
            near(yaw, y, 0.5, "yaw"); near(pitch, p, 0.5, "pitch")
            // nasion and pupils of the glasses' face space land on the observed landmarks (mirrored view)
            fun lmView(i: Int) = vi.imageToView(face.landmarks[i].x.toDouble(), face.landmarks[i].y.toDouble(), true)
            fun view(q: V3) = vi.project(at(pose, q), true)
            for ((anchor, idx) in listOf(FaceAnchors.CANONICAL.nasion to 168, FaceAnchors.CANONICAL.pupilR to 468, FaceAnchors.CANONICAL.pupilL to 473)) {
                val (a, b) = view(anchor); val (c, d) = lmView(idx)
                assertTrue("yaw $yaw anchor $idx off by ${a - c},${b - d}", abs(a - c) < 1.5 && abs(b - d) < 1.5)
            }
            // mirror: like a mirror, the subject's right lens is on the screen right
            val pl = GlassesFit.place(frame, null)
            val (rx, _) = view(pl.lensCentres[0]); val (lx, _) = view(pl.lensCentres[1])
            assertTrue("yaw $yaw: lens_R right of lens_L on the mirrored screen", rx > lx)
        }
        // turning the head to the subject's left (yaw > 0) moves the nose to the screen LEFT in the mirrored preview
        val ahead = FacePose.solve(synthFace(Mat4.mul(Mat4.translate(0.0, 0.0, -42.0), Mat4.rotY(0.0)), f), f)!!
        val left = FacePose.solve(synthFace(Mat4.mul(Mat4.translate(0.0, 0.0, -42.0), Mat4.rotY(30 * PI / 180)), f), f)!!
        val tip = V3(0.0, -0.46, 7.59)
        assertTrue(vi.project(at(left, tip), true).first < vi.project(at(ahead, tip), true).first)
    }

    @Test fun `transformation matrix - row-major or column-major gives the same rotation`() {
        val m = Mat4.mul(Mat4.translate(2.0, -1.0, -40.0), Mat4.rotY(0.4), Mat4.rotX(-0.2), Mat4.rotZ(0.1))
        val t = FloatArray(16) { m[(it % 4) * 4 + it / 4] }
        val a = FacePose.rotation(m)!!
        val b = FacePose.rotation(t)!!
        for (i in 0 until 16) near(a[i].toDouble(), b[i].toDouble(), 1e-5)
        near(0.0, a[12].toDouble(), 0.0)
    }

    @Test fun `pose smoothing - still input passes through, jitter is damped, quaternion round trip`() {
        val m = Mat4.mul(Mat4.translate(1.0, 2.0, -40.0), Mat4.rotY(0.5), Mat4.rotX(0.2))
        val back = PoseMatrixSmoother.fromQuaternion(PoseMatrixSmoother.quaternion(m))
        for (i in 0 until 11) near(m[i].toDouble(), back[i].toDouble(), 1e-5)
        val s = PoseMatrixSmoother()
        var out: FloatArray? = null
        for (k in 0 until 30) out = s.update(m, k * 33L)
        for (i in 0 until 15) near(m[i].toDouble(), out!![i].toDouble(), 1e-4)
        // ±3 mm jitter at rest → much less after the filter
        val s2 = PoseMatrixSmoother()
        var maxDev = 0.0
        for (k in 0 until 60) {
            val j = if (k % 2 == 0) 0.3 else -0.3
            val o = s2.update(Mat4.mul(Mat4.translate(j, 0.0, -40.0)), k * 33L)!!
            if (k > 10) maxDev = maxOf(maxDev, abs(o[12].toDouble()))
        }
        assertTrue("jitter $maxDev", maxDev < 0.15)
    }

    @Test fun `head occluder - one GLB with face mesh, head and ears`() {
        val glb = HeadOccluder.glb(verts, tris)
        val json = GlbWriter.jsonChunk(glb)
        for (n in listOf("occluder_face", "occluder_head", "occluder_ear_R", "occluder_ear_L")) assertTrue(n, json.contains("\"$n\""))
        assertEquals(1404, verts.size)
    }
}
