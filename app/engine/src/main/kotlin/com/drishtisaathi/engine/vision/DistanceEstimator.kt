package com.drishtisaathi.engine.vision

import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.Estimate
import com.drishtisaathi.engine.Optics
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.Source
import com.drishtisaathi.engine.config.DistanceConfig
import com.drishtisaathi.engine.config.FaceGeometryConfig
import com.drishtisaathi.engine.face.FaceFrame
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Eye-to-camera distance from the iris (MediaPipe Iris method): d_mm = f_px × irisDiameterMm / irisPx.
 *
 * - Iris px: both irises (centre + 4 ring points each); per iris the larger of the horizontal and vertical ring
 *   diameters (an eyelid shortens the vertical one, a turn shortens the horizontal one). If the two irises disagree
 *   by more than irisPairMaxRatio the smaller is taken as occluded and dropped.
 * - f_px: FaceFrame.focalLengthPx, else from a typical front-camera FOV (then LOW confidence).
 * - Iris mm: personal value from card calibration when given, else the 11.7 mm prior.
 * Confidence comes from the propagated relative SD: (σ_iris/iris)² + (σ_px/px)².
 */
class DistanceEstimator(private val c: DistanceConfig, private val lm: FaceGeometryConfig.LandmarkIndices) {

    constructor(rules: RulesConfig) : this(rules.distance, rules.faceGeometry.landmarks)

    enum class IrisSource { PERSONAL_CARD, POPULATION_PRIOR }
    enum class FocalSource { CAMERA_INTRINSICS, FOV_FALLBACK }

    data class IrisMeasure(
        /** Iris diameter in pixels (mean of the usable irises). */
        val diameterPx: Double,
        /** Image point between the usable iris centres, px. */
        val centreXPx: Double,
        val centreYPx: Double,
        /** 2 = both irises agree; 1 = one missing, too small, or dropped as occluded. */
        val irisesUsed: Int,
        /** True when both irises were seen but disagreed beyond irisPairMaxRatio. */
        val pairDisagreed: Boolean,
    )

    data class DistanceEstimate(
        /** The distance to use: eye-to-screen-centre when distance.applyScreenCentreOffset, else eye-to-camera depth. */
        val distanceMm: Double,
        /** Perpendicular depth f·D/px (the brief's formula). */
        val depthMm: Double,
        /** Euclidean distance from the eye midpoint to the screen centre (uses the configured offset). */
        val eyeToScreenCentreMm: Double,
        val confidence: Confidence,
        /** σ_d / d. */
        val relativeSd: Double,
        val irisSource: IrisSource,
        val focalSource: FocalSource,
        val irisPx: Double,
        val focalPx: Double,
        val irisDiameterMm: Double,
        val irisesUsed: Int,
    ) {
        val distanceM: Double get() = distanceMm / 1000.0

        /** Face-plane scale implied by this distance (mm per image pixel): d / f. */
        val faceMmPerPx: Double get() = depthMm / focalPx

        fun asEstimate(): Estimate<Double> = Estimate(distanceMm, confidence, Source.MEASURED)
    }

    // ---- iris -----------------------------------------------------------------------------------

    private fun hasLandmarks(frame: FaceFrame): Boolean =
        frame.landmarks.size > maxOf(lm.irisRightCentre, lm.irisLeftCentre, lm.irisRightRing.max(), lm.irisLeftRing.max())

    private fun dist(frame: FaceFrame, a: Int, b: Int): Double {
        val (ax, ay) = frame.px(a)
        val (bx, by) = frame.px(b)
        return hypot(ax - bx, ay - by)
    }

    /** One iris: max of horizontal (ring 0↔2) and vertical (ring 1↔3) diameter, null if below minIrisPx. */
    private fun oneIris(frame: FaceFrame, ring: List<Int>): Double? {
        val d = max(dist(frame, ring[0], ring[2]), dist(frame, ring[1], ring[3]))
        return d.takeIf { it.isFinite() && it >= c.minIrisPx }
    }

    fun irisDiameterPx(frame: FaceFrame): IrisMeasure? {
        if (!hasLandmarks(frame)) return null
        val r = oneIris(frame, lm.irisRightRing)
        val l = oneIris(frame, lm.irisLeftRing)
        val rc = frame.px(lm.irisRightCentre)
        val lc = frame.px(lm.irisLeftCentre)
        return when {
            r == null && l == null -> null
            r == null -> IrisMeasure(l!!, lc.first, lc.second, 1, false)
            l == null -> IrisMeasure(r, rc.first, rc.second, 1, false)
            max(r, l) / min(r, l) <= c.irisPairMaxRatio ->
                IrisMeasure((r + l) / 2.0, (rc.first + lc.first) / 2.0, (rc.second + lc.second) / 2.0, 2, false)
            r > l -> IrisMeasure(r, rc.first, rc.second, 1, true)
            else -> IrisMeasure(l, lc.first, lc.second, 1, true)
        }
    }

    // ---- focal length ---------------------------------------------------------------------------

    fun focalLengthPx(frame: FaceFrame): Pair<Double, FocalSource> {
        frame.focalLengthPx?.takeIf { it > 0 && it.isFinite() }?.let { return it to FocalSource.CAMERA_INTRINSICS }
        val longSide = max(frame.imageWidthPx, frame.imageHeightPx).toDouble()
        val f = (longSide / 2.0) / tan(Math.toRadians(c.fallbackLongSideFovDeg / 2.0))
        return f to FocalSource.FOV_FALLBACK
    }

    // ---- distance -------------------------------------------------------------------------------

    /**
     * Distance for one frame, or null when no usable iris is visible.
     * @param personalIrisMm from [personalIrisDiameterMm] after card calibration; ignored if outside the plausible range.
     */
    fun estimate(frame: FaceFrame, personalIrisMm: Double? = null): DistanceEstimate? {
        val iris = irisDiameterPx(frame) ?: return null
        val (f, focalSource) = focalLengthPx(frame)
        val personal = personalIrisMm?.takeIf { it in c.personalIrisMinMm..c.personalIrisMaxMm }
        val irisMm = personal ?: c.irisDiameterPriorMm
        val irisSd = if (personal != null) c.personalIrisSdMm else c.irisDiameterPriorSdMm
        val irisSource = if (personal != null) IrisSource.PERSONAL_CARD else IrisSource.POPULATION_PRIOR

        val depth = f * irisMm / iris.diameterPx
        val pxSd = c.irisPixelSdPx / sqrt(iris.irisesUsed.toDouble())
        val rel = sqrt((irisSd / irisMm).let { it * it } + (pxSd / iris.diameterPx).let { it * it })

        var conf = when {
            rel <= c.highMaxRelativeSd -> Confidence.HIGH
            rel <= c.mediumMaxRelativeSd -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        if (iris.pairDisagreed) conf = conf.min(Confidence.MEDIUM)
        if (focalSource == FocalSource.FOV_FALLBACK) conf = Confidence.LOW

        // Eye position in camera coordinates (principal point assumed at the image centre).
        val ex = (iris.centreXPx - frame.imageWidthPx / 2.0) * depth / f
        val ey = (iris.centreYPx - frame.imageHeightPx / 2.0) * depth / f
        val ox = if (frame.mirrored) -c.screenCentreOffsetXMm else c.screenCentreOffsetXMm
        val toScreen = sqrt((ex - ox) * (ex - ox) + (ey - c.screenCentreOffsetYMm).let { it * it } + depth * depth)

        return DistanceEstimate(
            distanceMm = if (c.applyScreenCentreOffset) toScreen else depth,
            depthMm = depth,
            eyeToScreenCentreMm = toScreen,
            confidence = conf,
            relativeSd = rel,
            irisSource = irisSource,
            focalSource = focalSource,
            irisPx = iris.diameterPx,
            focalPx = f,
            irisDiameterMm = irisMm,
            irisesUsed = iris.irisesUsed,
        )
    }

    /**
     * Personal iris diameter from the card-calibration photo: iris px × camera mm/px at the face plane
     * (card flat on the forehead, so the card scale is the face-plane scale). Null when no iris is visible or the
     * result is outside [personalIrisMinMm, personalIrisMaxMm] (calibration probably failed: use the prior).
     */
    fun personalIrisDiameterMm(calibrationFrame: FaceFrame, camMmPerPx: Double): Estimate<Double>? {
        require(camMmPerPx > 0)
        val iris = irisDiameterPx(calibrationFrame) ?: return null
        val mm = iris.diameterPx * camMmPerPx
        if (mm !in c.personalIrisMinMm..c.personalIrisMaxMm) return null
        val conf = if (iris.irisesUsed == 2) Confidence.HIGH else Confidence.MEDIUM
        return Estimate(mm, conf, Source.MEASURED)
    }
}

/**
 * Temporal smoother for the 15–30 Hz distance stream: median over the last [DistanceConfig.smootherWindow] samples
 * (newer than smootherMaxAgeMs), then an EMA with smootherEmaAlpha. Not thread-safe: feed it from one analyser thread.
 */
class DistanceSmoother(private val c: DistanceConfig) {
    private val window = ArrayDeque<Pair<Long, Double>>()
    private var ema: Double? = null

    fun reset() { window.clear(); ema = null }

    /** Adds one raw value (mm) and returns the smoothed value. */
    fun add(timestampMs: Long, valueMm: Double): Double {
        if (window.isNotEmpty() && timestampMs < window.last().first) reset() // clock went backwards: new stream
        window.addLast(timestampMs to valueMm)
        while (window.size > c.smootherWindow) window.removeFirst()
        while (window.isNotEmpty() && timestampMs - window.first().first > c.smootherMaxAgeMs) window.removeFirst()
        val median = Optics.median(window.map { it.second })
        val prev = ema
        val next = if (prev == null) median else c.smootherEmaAlpha * median + (1 - c.smootherEmaAlpha) * prev
        ema = next
        return next
    }

    /** Smooths an estimate: distances are replaced by the smoothed value, everything else is kept from the newest. */
    fun add(timestampMs: Long, e: DistanceEstimator.DistanceEstimate): DistanceEstimator.DistanceEstimate {
        val smoothed = add(timestampMs, e.distanceMm)
        val k = smoothed / e.distanceMm
        return e.copy(distanceMm = smoothed, depthMm = e.depthMm * k, eyeToScreenCentreMm = e.eyeToScreenCentreMm * k)
    }

    val current: Double? get() = ema
}
