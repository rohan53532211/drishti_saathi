package com.drishtisaathi.vision

import com.drishtisaathi.calibration.Calibration
import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.vision.VisionMath
import kotlin.math.pow
import kotlin.math.tan

/** Physical screen scale used to draw targets at true size. */
data class ScreenScale(val mmPerPx: Double, val fromCard: Boolean)

object Sizing {
    private const val MM_PER_INCH = 25.4
    /** Plausible phone screen scales (mm/px); anything outside means a broken DisplayMetrics value. */
    private val PLAUSIBLE = 0.02..0.25

    /**
     * Card calibration first (M0); else DisplayMetrics xdpi; else densityDpi. The fallback is marked so size-dependent
     * results get a lower confidence cap.
     */
    fun screenScale(calibration: Calibration, xdpi: Float, densityDpi: Int): ScreenScale {
        calibration.screenMmPerPx?.takeIf { it > 0 }?.let { return ScreenScale(it, fromCard = true) }
        val fromX = if (xdpi > 0f) MM_PER_INCH / xdpi else Double.NaN
        val mm = if (fromX in PLAUSIBLE) fromX else MM_PER_INCH / densityDpi.coerceAtLeast(1)
        return ScreenScale(mm, fromCard = false)
    }

    /** logMAR → size multiple of the 20/20 letter (logMAR 0.3 → ×2). */
    fun sizeMultiple(logMar: Double): Double = 10.0.pow(logMar)

    /** Tumbling-E height in screen pixels at [distanceMm] for [logMar], via VisionMath (5 arcmin × 10^logMAR). */
    fun letterHeightPx(vm: VisionMath, distanceMm: Double, scale: ScreenScale, logMar: Double): Double =
        vm.optotypeHeightPx(distanceMm, scale.mmPerPx, sizeMultiple(logMar))

    /** Size of [arcmin] at [distanceMm] in pixels (line widths, Amsler squares). */
    fun arcminPx(distanceMm: Double, arcmin: Double, scale: ScreenScale): Double =
        2.0 * distanceMm * tan(Math.toRadians(arcmin / 60.0) / 2.0) / scale.mmPerPx

    fun mmToPx(mm: Double, scale: ScreenScale): Double = mm / scale.mmPerPx

    /** Largest letter that fits: logMAR → px, and whether it had to be clipped to [maxPx]. */
    data class Drawn(val px: Double, val clipped: Boolean)

    fun fit(px: Double, maxPx: Double): Drawn = if (px > maxPx) Drawn(maxPx, true) else Drawn(px, false)
}

/** Lower confidence to at most [cap]. */
fun Confidence.cappedAt(cap: Confidence): Confidence = this.min(cap)

/** Live-distance check every test screen runs before accepting an answer. */
enum class DistanceStatus { OK, NO_READING, NO_FACE, TOO_CLOSE, TOO_FAR }

object DistanceGate {
    /** [minMm]/[maxMm] null = no limit on that side (e.g. far point, where moving through the range is the test). */
    fun check(reading: DistanceReading?, minMm: Double?, maxMm: Double?): DistanceStatus = when {
        reading == null -> DistanceStatus.NO_READING
        !reading.faceVisible -> DistanceStatus.NO_FACE
        minMm != null && reading.distanceMm < minMm -> DistanceStatus.TOO_CLOSE
        maxMm != null && reading.distanceMm > maxMm -> DistanceStatus.TOO_FAR
        else -> DistanceStatus.OK
    }
}
