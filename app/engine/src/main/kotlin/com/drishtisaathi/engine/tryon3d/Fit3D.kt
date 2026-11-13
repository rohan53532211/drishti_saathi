package com.drishtisaathi.engine.tryon3d

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
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

/** Frame width vs face width (the fit text under the live try-on). */
object Fit3D {
    /** "frame 132 mm, face about 136 mm" verdict; tolerances in mm. */
    fun fitVerdict(frameWidthMm: Double, headWidthMm: Double, goodMm: Double = 4.0, slightMm: Double = 9.0): FitWord {
        val d = frameWidthMm - headWidthMm
        return when {
            abs(d) <= goodMm -> FitWord.GOOD
            d < -slightMm -> FitWord.TOO_NARROW
            d < 0 -> FitWord.SLIGHTLY_NARROW
            d > slightMm -> FitWord.TOO_WIDE
            else -> FitWord.SLIGHTLY_WIDE
        }
    }
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
