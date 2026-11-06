package com.drishtisaathi.engine.face

import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.Estimate
import com.drishtisaathi.engine.FrameShape
import com.drishtisaathi.engine.Optics
import com.drishtisaathi.engine.PlausibilityGate
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.Source
import com.drishtisaathi.engine.config.FaceGeometryConfig
import com.drishtisaathi.engine.vision.DistanceEstimator
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Rule-based face shape. Used only for style suggestions, never for fit or optics. */
enum class FaceShape { ROUND, OVAL, SQUARE, LONG, HEART }

/** How the face-plane mm/px scale was obtained; caps the confidence of every mm measure. */
enum class ScaleSource { CARD, IRIS_PERSONAL, IRIS_PRIOR }

/** Face-plane scale in mm per image pixel. */
data class FaceScale(val mmPerPx: Double, val source: ScaleSource) {
    init { require(mmPerPx > 0 && mmPerPx.isFinite()) }

    companion object {
        /** From the card photo (card flat on the forehead): camMmPerPx = 85.60 / cardWidthPx. */
        fun fromCard(camMmPerPx: Double) = FaceScale(camMmPerPx, ScaleSource.CARD)

        /** mmPerPx = d_mm / f_px. Note d/f = irisMm/irisPx, so the FOV fallback does not matter here. */
        fun fromDistance(distanceMm: Double, focalPx: Double, personalIris: Boolean) =
            FaceScale(distanceMm / focalPx, if (personalIris) ScaleSource.IRIS_PERSONAL else ScaleSource.IRIS_PRIOR)

        fun from(d: DistanceEstimator.DistanceEstimate) =
            fromDistance(d.depthMm, d.focalPx, d.irisSource == DistanceEstimator.IrisSource.PERSONAL_CARD)
    }
}

enum class PoseSource { TRANSFORM_MATRIX, LANDMARKS }

/**
 * Head pose in degrees. From the transformation matrix: R = Rz(roll)·Ry(yaw)·Rx(pitch).
 * From landmarks (image frame): roll = angle of the outer-eye-corner line (positive = clockwise on the image),
 * yaw positive = nose tip towards image +x, pitch positive = nose tip towards image +y (head down).
 * The camera gates only use magnitudes ([maxAbsYawRollDeg]).
 */
data class HeadPose(
    val yawDeg: Double,
    val pitchDeg: Double,
    val rollDeg: Double,
    val source: PoseSource,
    val confidence: Confidence,
) {
    val maxAbsYawRollDeg: Double get() = maxOf(abs(yawDeg), abs(rollDeg))
    fun within(maxYawDeg: Double, maxRollDeg: Double, maxPitchDeg: Double = Double.MAX_VALUE): Boolean =
        abs(yawDeg) <= maxYawDeg && abs(rollDeg) <= maxRollDeg && abs(pitchDeg) <= maxPitchDeg
}

/** Measures from ONE frame, in mm. Aggregate 30 of these with [FaceAggregator]. */
data class FaceMeasurement(
    val pdMm: Double,
    val faceWidthMm: Double,
    val bridgeWidthMm: Double,
    val faceLengthMm: Double,
    val jawWidthMm: Double,
    val foreheadWidthMm: Double,
    val pose: HeadPose,
    val scaleSource: ScaleSource,
    val timestampMs: Long = 0L,
) {
    val widthToLength: Double get() = faceWidthMm / faceLengthMm
    val jawToCheek: Double get() = jawWidthMm / faceWidthMm
    val foreheadToCheek: Double get() = foreheadWidthMm / faceWidthMm
}

/**
 * M2 face geometry from MediaPipe landmarks. Every landmark index comes from rules.json `faceGeometry.landmarks`.
 * Pure Kotlin; images never reach the engine.
 */
class FaceGeometry(private val c: FaceGeometryConfig) {

    constructor(rules: RulesConfig) : this(rules.faceGeometry)

    private val lm = c.landmarks
    private val maxIndex = lm.all.max()

    fun hasLandmarks(frame: FaceFrame): Boolean = frame.landmarks.size > maxIndex

    private fun distPx(f: FaceFrame, a: Int, b: Int): Double {
        val (ax, ay) = f.px(a)
        val (bx, by) = f.px(b)
        return hypot(ax - bx, ay - by)
    }

    // ---- head pose ------------------------------------------------------------------------------

    /** Pose from the transformation matrix when present (HIGH), else from landmarks (MEDIUM). Null without landmarks. */
    fun headPose(frame: FaceFrame): HeadPose? {
        frame.transformMatrix?.takeIf { it.size >= 16 }?.let { m -> poseFromMatrix(m)?.let { return it } }
        if (!hasLandmarks(frame)) return null
        return poseFromLandmarks(frame)
    }

    /** 4×4 column-major matrix; scale is removed per column. */
    fun poseFromMatrix(m: FloatArray): HeadPose? {
        fun e(row: Int, col: Int) = m[col * 4 + row].toDouble()
        val s = DoubleArray(3) { col -> sqrt(e(0, col) * e(0, col) + e(1, col) * e(1, col) + e(2, col) * e(2, col)) }
        if (s.any { it < 1e-9 || !it.isFinite() }) return null
        fun r(row: Int, col: Int) = e(row, col) / s[col]
        val yaw = asin((-r(2, 0)).coerceIn(-1.0, 1.0))
        val pitch = atan2(r(2, 1), r(2, 2))
        val roll = atan2(r(1, 0), r(0, 0))
        return HeadPose(Math.toDegrees(yaw), Math.toDegrees(pitch), Math.toDegrees(roll), PoseSource.TRANSFORM_MATRIX, Confidence.HIGH)
    }

    /**
     * Roll from the outer-eye-corner line. Yaw and pitch from the nose tip against the face outline, after undoing
     * roll: under a turn by θ the nose tip (depth D in front of the cheek points) shifts by D·sinθ while the face
     * width shrinks to W·cosθ, so offset/width = (D/W)·tanθ; pitch likewise with face length.
     */
    fun poseFromLandmarks(frame: FaceFrame): HeadPose {
        val (ax, ay) = frame.px(lm.eyeOuterRight)
        val (bx, by) = frame.px(lm.eyeOuterLeft)
        var dx = bx - ax
        var dy = by - ay
        if (dx < 0) { dx = -dx; dy = -dy } // mirrored or unmirrored: same roll
        val roll = atan2(dy, dx)

        val cx = (ax + bx) / 2.0
        val cy = (ay + by) / 2.0
        val cr = cos(-roll)
        val sr = sin(-roll)
        fun derot(i: Int): Pair<Double, Double> {
            val (x, y) = frame.px(i)
            val ux = x - cx
            val uy = y - cy
            return (ux * cr - uy * sr) to (ux * sr + uy * cr)
        }
        val nose = derot(lm.noseTip)
        val wr = derot(lm.faceWidthRight)
        val wl = derot(lm.faceWidthLeft)
        val top = derot(lm.faceTop)
        val chin = derot(lm.chin)

        val width = abs(wl.first - wr.first)
        val yaw = if (width > 1e-9) {
            val off = (nose.first - (wl.first + wr.first) / 2.0) / width
            atan(off / c.pose.noseDepthToFaceWidth)
        } else 0.0
        val length = chin.second - top.second
        val pitch = if (abs(length) > 1e-9) {
            val frac = (nose.second - top.second) / length
            atan((frac - c.pose.noseTipNeutralFraction) / c.pose.noseDepthToFaceLength)
        } else 0.0
        return HeadPose(Math.toDegrees(yaw), Math.toDegrees(pitch), Math.toDegrees(roll), PoseSource.LANDMARKS, Confidence.MEDIUM)
    }

    // ---- measures -------------------------------------------------------------------------------

    /** All mm measures from one frame; null if the frame lacks the 478 landmarks. */
    fun measure(frame: FaceFrame, scale: FaceScale): FaceMeasurement? {
        if (!hasLandmarks(frame)) return null
        val k = scale.mmPerPx
        return FaceMeasurement(
            pdMm = distPx(frame, lm.irisRightCentre, lm.irisLeftCentre) * k,
            faceWidthMm = distPx(frame, lm.faceWidthRight, lm.faceWidthLeft) * k,
            bridgeWidthMm = distPx(frame, lm.bridgeRight, lm.bridgeLeft) * k,
            faceLengthMm = distPx(frame, lm.faceTop, lm.chin) * k,
            jawWidthMm = distPx(frame, lm.jawRight, lm.jawLeft) * k,
            foreheadWidthMm = distPx(frame, lm.foreheadRight, lm.foreheadLeft) * k,
            pose = headPose(frame)!!,
            scaleSource = scale.source,
            timestampMs = frame.timestampMs,
        )
    }

    // ---- face shape -----------------------------------------------------------------------------

    /**
     * Order: LONG (width/length < longMax), SQUARE (jaw/cheek ≥ squareMin), ROUND (width/length ≥ roundMin),
     * HEART (jaw/cheek ≤ heartMax and forehead/cheek ≥ heartMin), else OVAL.
     * LOW confidence when any ratio decided on lies within borderlineMargin of its threshold, else MEDIUM
     * (never HIGH: shape is a style hint).
     */
    fun classifyShape(widthToLength: Double, jawToCheek: Double, foreheadToCheek: Double): Estimate<FaceShape> {
        val s = c.shape
        val margins = mutableListOf<Double>()
        fun test(value: Double, threshold: Double, hit: Boolean): Boolean { margins += abs(value - threshold); return hit }

        val shape = when {
            test(widthToLength, s.longMaxWidthToLength, widthToLength < s.longMaxWidthToLength) -> FaceShape.LONG
            test(jawToCheek, s.squareMinJawToCheek, jawToCheek >= s.squareMinJawToCheek) -> FaceShape.SQUARE
            test(widthToLength, s.roundMinWidthToLength, widthToLength >= s.roundMinWidthToLength) -> FaceShape.ROUND
            test(jawToCheek, s.heartMaxJawToCheek, jawToCheek <= s.heartMaxJawToCheek) &&
                test(foreheadToCheek, s.heartMinForeheadToCheek, foreheadToCheek >= s.heartMinForeheadToCheek) -> FaceShape.HEART
            else -> FaceShape.OVAL
        }
        val conf = if (margins.min() < s.borderlineMargin) Confidence.LOW else Confidence.MEDIUM
        return Estimate(shape, conf, Source.MEASURED)
    }

    fun classifyShape(m: FaceMeasurement): Estimate<FaceShape> = classifyShape(m.widthToLength, m.jawToCheek, m.foreheadToCheek)

    /** Frame shapes that usually suit the face shape, best first (e.g. angular frames for a round face). */
    fun suggestedFrameShapes(shape: FaceShape): List<FrameShape> = c.shape.suggestedFrames.getValue(shape)
}

// =============================================================================================
// Aggregation over N accepted frames
// =============================================================================================

/**
 * Median over N accepted frames (target faceGeometry.aggregate.targetFrames = 30) with MAD outlier rejection,
 * spread → confidence, scale-source cap, and the ANSUR plausibility gate on PD + face width.
 */
class FaceAggregator(
    private val c: FaceGeometryConfig,
    private val gate: PlausibilityGate,
) {
    private val geometry = FaceGeometry(c)

    data class Measure(
        val estimate: Estimate<Double>,
        /** Robust SD of the kept values (1.4826 · MAD), mm. */
        val spreadMm: Double,
        val kept: Int,
        val dropped: Int,
    ) {
        val mm: Double get() = estimate.value
    }

    data class AggregatedFace(
        val pd: Measure,
        val faceWidth: Measure,
        val bridgeWidth: Measure,
        val faceLength: Measure,
        val jawWidth: Measure,
        val foreheadWidth: Measure,
        val shape: Estimate<FaceShape>,
        val suggestedFrameShapes: List<FrameShape>,
        val plausibility: PlausibilityGate.Result,
        /** True when the gate says RESCAN: discard and capture again. */
        val rescan: Boolean,
        val framesUsed: Int,
        val scaleSource: ScaleSource,
    ) {
        val pdMm: Estimate<Double> get() = pd.estimate
        val faceWidthMm: Estimate<Double> get() = faceWidth.estimate
    }

    /** Null for an empty list. */
    fun aggregate(frames: List<FaceMeasurement>): AggregatedFace? {
        if (frames.isEmpty()) return null
        val a = c.aggregate
        val scaleSource = frames.map { it.scaleSource }.maxBy { it.ordinal } // CARD < IRIS_PERSONAL < IRIS_PRIOR
        val cap = when (scaleSource) {
            ScaleSource.CARD -> a.capCardScale
            ScaleSource.IRIS_PERSONAL -> a.capPersonalIrisScale
            ScaleSource.IRIS_PRIOR -> a.capPriorIrisScale
        }
        val countCap = if (frames.size < a.minFrames) Confidence.LOW else Confidence.HIGH

        fun spreadConf(spread: Double, median: Double, isPd: Boolean): Confidence = if (isPd) {
            when {
                spread <= a.pdSpreadHighMaxMm + 1e-9 -> Confidence.HIGH
                spread <= a.pdSpreadMediumMaxMm + 1e-9 -> Confidence.MEDIUM
                else -> Confidence.LOW
            }
        } else {
            val rel = spread / median
            when {
                rel <= a.widthSpreadHighMaxFraction + 1e-9 -> Confidence.HIGH
                rel <= a.widthSpreadMediumMaxFraction + 1e-9 -> Confidence.MEDIUM
                else -> Confidence.LOW
            }
        }

        fun measure(values: List<Double>, isPd: Boolean = false): Measure {
            val kept = robustKeep(values, a.outlierMadK, a.outlierMinAbsMm)
            val median = Optics.median(kept)
            val spread = MAD_TO_SD * Optics.median(kept.map { abs(it - median) })
            val conf = spreadConf(spread, median, isPd).min(cap).min(countCap)
            return Measure(Estimate(median, conf, Source.MEASURED), spread, kept.size, values.size - kept.size)
        }

        val pd = measure(frames.map { it.pdMm }, isPd = true)
        val fw = measure(frames.map { it.faceWidthMm })
        val bridge = measure(frames.map { it.bridgeWidthMm })
        val length = measure(frames.map { it.faceLengthMm })
        val jaw = measure(frames.map { it.jawWidthMm })
        val forehead = measure(frames.map { it.foreheadWidthMm })

        val check = gate.check(pd.mm, fw.mm)
        val pdFinal = when (check.verdict) {
            PlausibilityGate.Verdict.OK -> pd
            else -> pd.copy(estimate = pd.estimate.copy(confidence = Confidence.LOW))
        }
        val fwFinal = if (check.verdict == PlausibilityGate.Verdict.RESCAN) {
            fw.copy(estimate = fw.estimate.copy(confidence = Confidence.LOW))
        } else fw

        val shapeRaw = geometry.classifyShape(fw.mm / length.mm, jaw.mm / fw.mm, forehead.mm / fw.mm)
        val shapeConf = shapeRaw.confidence.min(fw.estimate.confidence).min(length.estimate.confidence).min(jaw.estimate.confidence)
        val shape = shapeRaw.copy(confidence = shapeConf)

        return AggregatedFace(
            pd = pdFinal, faceWidth = fwFinal, bridgeWidth = bridge, faceLength = length, jawWidth = jaw,
            foreheadWidth = forehead, shape = shape, suggestedFrameShapes = geometry.suggestedFrameShapes(shape.value),
            plausibility = check, rescan = check.verdict == PlausibilityGate.Verdict.RESCAN,
            framesUsed = frames.size, scaleSource = scaleSource,
        )
    }

    companion object {
        const val MAD_TO_SD = 1.4826

        fun from(rules: RulesConfig, gate: PlausibilityGate) = FaceAggregator(rules.faceGeometry, gate)

        /** Keeps values with |x − median| ≤ max(k·1.4826·MAD, minAbs). */
        fun robustKeep(values: List<Double>, k: Double, minAbs: Double): List<Double> {
            require(values.isNotEmpty())
            val med = Optics.median(values)
            val mad = Optics.median(values.map { abs(it - med) })
            val limit = maxOf(k * MAD_TO_SD * mad, minAbs)
            return values.filter { abs(it - med) <= limit }
        }
    }
}

/** Plausibility gate straight on one frame's FaceGeometry output. */
fun PlausibilityGate.check(m: FaceMeasurement): PlausibilityGate.Result = check(m.pdMm, m.faceWidthMm)

/** Plausibility gate on aggregated output (re-runs the same check the aggregator already applied). */
fun PlausibilityGate.check(face: FaceAggregator.AggregatedFace): PlausibilityGate.Result = check(face.pd.mm, face.faceWidth.mm)
