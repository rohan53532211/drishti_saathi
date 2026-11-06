package com.drishtisaathi.engine.face

import com.drishtisaathi.engine.AnthroPriors
import com.drishtisaathi.engine.MeasuredFace
import com.drishtisaathi.engine.PlausibilityGate
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.vision.DistanceEstimator

/**
 * M2 end to end: the accepted capture frames → per-frame measurements (scale from the iris distance
 * estimate, personal iris size when the forehead-card calibration was done) → robust aggregate →
 * plausibility gate → [MeasuredFace] for the rules engine.
 */
class FaceCapture(rules: RulesConfig, priors: AnthroPriors) {
    private val distance = DistanceEstimator(rules)
    private val geometry = FaceGeometry(rules)
    private val aggregator = FaceAggregator.from(rules, PlausibilityGate(priors, rules.plausibility))

    enum class Status { OK, LOW_PLAUSIBILITY, RESCAN, NO_USABLE_FRAMES }

    data class Outcome(
        val status: Status,
        /** Null for RESCAN / NO_USABLE_FRAMES: the numbers are never used. */
        val face: MeasuredFace?,
        val framesMeasured: Int,
        val aggregate: FaceAggregator.AggregatedFace?,
    )

    fun analyse(frames: List<FaceFrame>, personalIrisMm: Double? = null): Outcome {
        val measurements = frames.mapNotNull { f ->
            val d = distance.estimate(f, personalIrisMm) ?: return@mapNotNull null
            geometry.measure(f, FaceScale.from(d))
        }
        val agg = aggregator.aggregate(measurements)
            ?: return Outcome(Status.NO_USABLE_FRAMES, null, 0, null)
        val face = agg.toMeasuredFace()
        val status = when {
            face == null -> Status.RESCAN
            face.lowPlausibility -> Status.LOW_PLAUSIBILITY
            else -> Status.OK
        }
        return Outcome(status, face, measurements.size, agg)
    }
}
