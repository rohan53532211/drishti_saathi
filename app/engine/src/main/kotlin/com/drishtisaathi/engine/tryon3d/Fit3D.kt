package com.drishtisaathi.engine.tryon3d

import com.drishtisaathi.engine.face.FaceFrame
import com.drishtisaathi.engine.tryon.TryOnPlacement
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** 4×4 column-major matrices (Filament / OpenGL layout). */
object Mat4 {
    fun identity() = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val o = FloatArray(16)
        for (c in 0 until 4) for (r in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[k * 4 + r] * b[c * 4 + k]
            o[c * 4 + r] = s
        }
        return o
    }

    fun mul(vararg ms: FloatArray): FloatArray = ms.reduce { a, b -> mul(a, b) }

    fun translate(x: Double, y: Double, z: Double) = identity().also { it[12] = x.toFloat(); it[13] = y.toFloat(); it[14] = z.toFloat() }

    fun scale(x: Double, y: Double = x, z: Double = x) = identity().also { it[0] = x.toFloat(); it[5] = y.toFloat(); it[10] = z.toFloat() }

    fun rotY(rad: Double) = identity().also {
        it[0] = cos(rad).toFloat(); it[8] = sin(rad).toFloat(); it[2] = (-sin(rad)).toFloat(); it[10] = cos(rad).toFloat()
    }

    fun rotX(rad: Double) = identity().also {
        it[5] = cos(rad).toFloat(); it[9] = (-sin(rad)).toFloat(); it[6] = sin(rad).toFloat(); it[10] = cos(rad).toFloat()
    }

    fun rotZ(rad: Double) = identity().also {
        it[0] = cos(rad).toFloat(); it[4] = (-sin(rad)).toFloat(); it[1] = sin(rad).toFloat(); it[5] = cos(rad).toFloat()
    }

    fun apply(m: FloatArray, x: Double, y: Double, z: Double): DoubleArray = doubleArrayOf(
        m[0] * x + m[4] * y + m[8] * z + m[12],
        m[1] * x + m[5] * y + m[9] * z + m[13],
        m[2] * x + m[6] * y + m[10] * z + m[14],
    )
}

enum class Scale3DSource { CARD_PD, FACE_WIDTH, DEFAULT_PD }

/** Tunables for the 3D try-on (defaults chosen on the bench; documented in docs/ARCHITECTURE.md). */
data class TryOn3DConfig(
    val defaultPdMm: Double = 63.0,
    val maxYawDeg: Double = 45.0,
    val maxPitchDeg: Double = 30.0,
    /** MediaPipe nose-bridge point the frame's nose rest sits on (6 = between the eyes, on the nasal bone). */
    val noseLandmark: Int = 6,
    val cheekLeft: Int = 234,
    val cheekRight: Int = 454,
    /** The bridge sits this far below landmark 6 (mm, + = down). */
    val bridgeDropMm: Double = 1.5,
    /** Temple tip reaches the ear this far behind the front (mm), straight part. */
    val earReachMm: Double = 98.0,
    /** Temples press this far inside the measured head half-width. */
    val templeInsetMm: Double = 2.0,
    val minSplayDeg: Double = -6.0,
    val maxSplayDeg: Double = 18.0,
    /** Fit text: frame vs face width tolerances (mm). */
    val fitGoodMm: Double = 4.0,
    val fitSlightMm: Double = 9.0,
)

/** Result of one live placement, in "view world" units: x right / y up in view pixels, z towards the viewer. */
data class Pose3D(
    /** Frame model (mm, bridge origin) → view world. */
    val model: FloatArray,
    /** Temple node local transforms (hinge origin) for temple_R / temple_L. */
    val templeR: FloatArray,
    val templeL: FloatArray,
    /** Head proxy ellipsoid (unit sphere) → view world, for the invisible occluder. */
    val occluder: FloatArray,
    val viewPxPerMm: Double,
    val headWidthMm: Double,
    val yawDeg: Double,
    val pitchDeg: Double,
    val rollDeg: Double,
    val splayDeg: Double,
    val scaleSource: Scale3DSource,
)

/** Pose parameters before matrices, so they can be smoothed one by one. */
data class PoseParams(
    val anchorX: Double, val anchorY: Double, val pxPerMm: Double,
    val yaw: Double, val pitch: Double, val roll: Double, val headWidthMm: Double,
)

/**
 * MEGANE-inspired fitting, simplified: the bridge rests on the nose-bridge landmark, the front turns with the
 * head (yaw/pitch from the facial transformation matrix, roll from the pupils), the scale comes from the PD,
 * and each temple swings outwards about its hinge so its ear end reaches the head width.
 * Weak perspective (orthographic camera in view pixels), like the 2D try-on.
 */
object Fit3D {
    const val LEFT_IRIS = TryOnPlacement.LEFT_IRIS
    const val RIGHT_IRIS = TryOnPlacement.RIGHT_IRIS

    /** View px per mm at the eyes from the pupil distance (head-on equivalent). */
    fun pxPerMmFromPd(ipdViewPx: Double, pdMm: Double, yawRad: Double): Double =
        ipdViewPx / max(cos(yawRad), 0.5) / pdMm

    /** Head width (mm) from the outer face points, head-on equivalent. */
    fun headWidthMm(cheekViewPx: Double, pxPerMm: Double, yawRad: Double): Double =
        cheekViewPx / max(cos(yawRad), 0.5) / pxPerMm

    /**
     * Temple bend: the outward swing (degrees, about the hinge's vertical axis) that brings the temple's ear end to
     * the head side. Positive = outwards. Clamped so a bad head-width reading never splays the temples wildly.
     */
    fun templeSplayDeg(hingeHalfWidthMm: Double, headWidthMm: Double, cfg: TryOn3DConfig = TryOn3DConfig()): Double {
        val ear = headWidthMm / 2 - cfg.templeInsetMm
        val deg = atan2(ear - hingeHalfWidthMm, cfg.earReachMm) * 180 / PI
        return deg.coerceIn(cfg.minSplayDeg, cfg.maxSplayDeg)
    }

    /** Temple node transforms (hinge origin): right swings to +X, left to −X. */
    fun templeTransforms(frame: Frame3D, splayDeg: Double): Pair<FloatArray, FloatArray> {
        val rad = splayDeg * PI / 180
        fun at(name: String, sign: Double): FloatArray {
            val h = frame.anchors.hingesMm[name] ?: listOf(sign * frame.hingeHalfWidthMm, 0.0, 4.0)
            return Mat4.mul(Mat4.translate(h[0], h[1], h[2]), Mat4.rotY(sign * rad))
        }
        return at("temple_R", 1.0) to at("temple_L", -1.0)
    }

    /** "frame 132 mm, face about 136 mm" verdict. */
    fun fitVerdict(frameWidthMm: Double, headWidthMm: Double, cfg: TryOn3DConfig = TryOn3DConfig()): FitWord {
        val d = frameWidthMm - headWidthMm
        return when {
            abs(d) <= cfg.fitGoodMm -> FitWord.GOOD
            d < -cfg.fitSlightMm -> FitWord.TOO_NARROW
            d < 0 -> FitWord.SLIGHTLY_NARROW
            d > cfg.fitSlightMm -> FitWord.TOO_WIDE
            else -> FitWord.SLIGHTLY_WIDE
        }
    }

    /**
     * Measure pose parameters from a face frame. [toView] maps normalised image coords to view pixels WITHOUT
     * mirroring (the renderer's view is mirrored as a whole when the preview is). Null when there is no usable face.
     * Scale: [pdMm] (card-calibrated when known) else [faceWidthMm] else the default PD ("approximate").
     */
    fun measure(
        face: FaceFrame?,
        toView: (Double, Double) -> Pair<Double, Double>,
        pdMm: Double? = null,
        faceWidthMm: Double? = null,
        cfg: TryOn3DConfig = TryOn3DConfig(),
    ): Pair<PoseParams, Scale3DSource>? {
        if (face == null || face.landmarks.size <= RIGHT_IRIS) return null
        fun v(i: Int) = toView(face.landmarks[i].x.toDouble(), face.landmarks[i].y.toDouble())
        var a = v(LEFT_IRIS)
        var b = v(RIGHT_IRIS)
        if (b.first < a.first) a = b.also { b = a }
        val ipd = hypot(b.first - a.first, b.second - a.second)
        if (ipd < 2.0) return null
        var yaw = 0.0
        var pitch = 0.0
        face.transformMatrix?.takeIf { it.size == 16 }?.let {
            val (y, p) = TryOnPlacement.yawPitch(it)
            yaw = y.coerceIn(-rad(cfg.maxYawDeg), rad(cfg.maxYawDeg))
            pitch = p.coerceIn(-rad(cfg.maxPitchDeg), rad(cfg.maxPitchDeg))
        }
        // view y is down; world y is up
        val roll = -atan2(b.second - a.second, b.first - a.first)
        val cl = v(cfg.cheekLeft)
        val cr = v(cfg.cheekRight)
        val cheekPx = hypot(cr.first - cl.first, cr.second - cl.second)
        val (pxPerMm, src) = when {
            pdMm != null && pdMm > 0 -> pxPerMmFromPd(ipd, pdMm, yaw) to Scale3DSource.CARD_PD
            faceWidthMm != null && faceWidthMm > 0 -> cheekPx / max(cos(yaw), 0.5) / faceWidthMm to Scale3DSource.FACE_WIDTH
            else -> pxPerMmFromPd(ipd, cfg.defaultPdMm, yaw) to Scale3DSource.DEFAULT_PD
        }
        val head = faceWidthMm?.takeIf { it > 0 } ?: headWidthMm(cheekPx, pxPerMm, yaw)
        val n = v(cfg.noseLandmark)
        return PoseParams(n.first, -n.second, pxPerMm, yaw, pitch, roll, head) to src
    }

    /** Matrices from (smoothed) parameters. */
    fun pose(p: PoseParams, frame: Frame3D, src: Scale3DSource, cfg: TryOn3DConfig = TryOn3DConfig()): Pose3D {
        // model axes: +X wearer's right, +Z towards the face. In the un-mirrored camera image the wearer's right is
        // on the image left and the face looks towards the viewer: a 180° turn about Y (a proper rotation, so
        // triangle winding and back-face culling stay right).
        val base = Mat4.rotY(PI)
        val rot = Mat4.mul(Mat4.rotZ(p.roll), Mat4.rotY(p.yaw), Mat4.rotX(p.pitch))
        val nose = frame.anchors.noseRestMm
        val model = Mat4.mul(
            Mat4.translate(p.anchorX, p.anchorY, 0.0), rot, Mat4.scale(p.pxPerMm), base,
            Mat4.translate(-nose[0], -nose[1] - cfg.bridgeDropMm, -nose[2]),
        )
        val splay = templeSplayDeg(frame.hingeHalfWidthMm, p.headWidthMm, cfg)
        val (tr, tl) = templeTransforms(frame, splay)
        // head proxy: an ellipsoid behind the eyes (model mm), slightly inside the temples
        val rx = p.headWidthMm / 2 - 4
        val rz = 0.62 * p.headWidthMm
        val occ = Mat4.mul(
            Mat4.translate(p.anchorX, p.anchorY, 0.0), rot, Mat4.scale(p.pxPerMm), base,
            Mat4.translate(0.0, -12.0, 14.0 + rz), Mat4.scale(rx, 0.75 * p.headWidthMm, rz),
        )
        return Pose3D(model, tr, tl, occ, p.pxPerMm, p.headWidthMm, deg(p.yaw), deg(p.pitch), deg(p.roll), splay, src)
    }

    fun rad(d: Double) = d * PI / 180
    fun deg(r: Double) = r * 180 / PI
}

enum class FitWord { GOOD, SLIGHTLY_NARROW, SLIGHTLY_WIDE, TOO_NARROW, TOO_WIDE }

/** One-euro filter (Casiez et al. 2012): little lag when moving fast, strong smoothing when still. */
class OneEuroFilter(
    private val minCutoffHz: Double = 1.2,
    private val beta: Double = 0.02,
    private val dCutoffHz: Double = 1.0,
) {
    private var x: Double? = null
    private var dx = 0.0
    private var tMs = 0L

    fun reset() { x = null; dx = 0.0 }

    fun filter(value: Double, timeMs: Long): Double {
        val prev = x
        if (prev == null) { x = value; tMs = timeMs; return value }
        val dt = ((timeMs - tMs).coerceAtLeast(1)) / 1000.0
        tMs = timeMs
        val ad = alpha(dCutoffHz, dt)
        dx = ad * ((value - prev) / dt) + (1 - ad) * dx
        val a = alpha(minCutoffHz + beta * abs(dx), dt)
        val out = a * value + (1 - a) * prev
        x = out
        return out
    }

    private fun alpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2 * PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }

    companion object {
        /** Fraction of a full step a filter takes at [dt] s; used in tests. */
        fun alphaFor(cutoff: Double, dt: Double) = 1.0 - exp(-dt * 2 * PI * cutoff)
    }
}

/** Smooths every pose parameter; restarts after the face was lost. Position uses view pixels, angles radians. */
class PoseSmoother {
    private val ax = OneEuroFilter(1.0, 0.004)
    private val ay = OneEuroFilter(1.0, 0.004)
    private val sc = OneEuroFilter(0.6, 0.002)
    private val yaw = OneEuroFilter(1.0, 0.3)
    private val pitch = OneEuroFilter(1.0, 0.3)
    private val roll = OneEuroFilter(1.0, 0.3)
    private val head = OneEuroFilter(0.3, 0.0)
    private var lastMs = Long.MIN_VALUE

    fun reset() { listOf(ax, ay, sc, yaw, pitch, roll, head).forEach { it.reset() } }

    fun update(p: PoseParams?, timeMs: Long): PoseParams? {
        if (p == null) { reset(); return null }
        if (lastMs != Long.MIN_VALUE && timeMs - lastMs > 600) reset()   // face was gone for a while
        lastMs = timeMs
        return PoseParams(
            ax.filter(p.anchorX, timeMs), ay.filter(p.anchorY, timeMs), sc.filter(p.pxPerMm, timeMs),
            yaw.filter(p.yaw, timeMs), pitch.filter(p.pitch, timeMs), roll.filter(p.roll, timeMs),
            head.filter(p.headWidthMm, timeMs),
        )
    }
}

/**
 * Least-recently-used index for the on-phone GLB/thumbnail cache (the app keeps the files; this keeps the order
 * and the byte budget). [put] returns the keys to delete.
 */
class LruIndex(val maxBytes: Long) {
    private val map = LinkedHashMap<String, Long>(16, 0.75f, true)
    var bytes = 0L
        private set

    val keys: List<String> get() = map.keys.toList()

    fun touch(key: String): Boolean = map[key] != null

    operator fun contains(key: String) = map.containsKey(key)

    fun put(key: String, size: Long): List<String> {
        map.remove(key)?.let { bytes -= it }
        map[key] = size
        bytes += size
        val evicted = mutableListOf<String>()
        val it = map.entries.iterator()
        while (bytes > maxBytes && it.hasNext()) {
            val e = it.next()
            if (e.key == key) continue          // never evict what was just added
            bytes -= e.value
            evicted += e.key
            it.remove()
        }
        return evicted
    }

    fun remove(key: String) { map.remove(key)?.let { bytes -= it } }
}

/**
 * When to give up on 3D and show the 2D try-on instead (no error dialog): the renderer could not start, a GLB failed
 * to load, or the phone draws fewer than [minFps] frames per second over [windowMs].
 */
class Render3DFallback(private val minFps: Double = 15.0, private val windowMs: Long = 3000, private val graceMs: Long = 1500) {
    enum class Mode { THREE_D, TWO_D }
    enum class Reason { NONE, NO_RENDERER, GLB_FAILED, SLOW }

    var mode = Mode.THREE_D
        private set
    var reason = Reason.NONE
        private set
    private var startMs = -1L
    private val frames = ArrayDeque<Long>()

    fun rendererFailed() = fallBack(Reason.NO_RENDERER)
    fun glbFailed() = fallBack(Reason.GLB_FAILED)

    /** Call once per drawn frame. */
    fun onFrame(timeMs: Long): Mode {
        if (mode == Mode.TWO_D) return mode
        if (startMs < 0) startMs = timeMs
        frames.addLast(timeMs)
        while (frames.isNotEmpty() && frames.first() < timeMs - windowMs) frames.removeFirst()
        if (timeMs - startMs >= graceMs + windowMs) {
            val fps = (frames.size - 1) * 1000.0 / windowMs
            if (fps < minFps) fallBack(Reason.SLOW)
        }
        return mode
    }

    /** Frames per second over the window (for the debug line). */
    fun fps(): Double = if (frames.size < 2) 0.0 else (frames.size - 1) * 1000.0 / max(1L, frames.last() - frames.first())

    private fun fallBack(r: Reason) {
        if (mode == Mode.TWO_D) return
        mode = Mode.TWO_D
        reason = r
    }
}
