package com.drishtisaathi.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

/** Typed view of `assets/anthro_priors.json`, produced by tools/ansur_analysis.py from ANSUR II. */
@Serializable
data class AnthroPriors(
    val source: String,
    val n: Int,
    @SerialName("pd_mm") val pdMm: Dist,
    @SerialName("face_width_mm") val faceWidthMm: Dist,
    @SerialName("pd_to_face_ratio") val pdToFaceRatio: Dist,
    @SerialName("pd_from_face_width") val pdFromFaceWidth: Regression,
) {
    @Serializable data class Dist(val mean: Double, val sd: Double, val p1: Double, val p99: Double)

    @Serializable
    data class Regression(
        val slope: Double,
        val intercept: Double,
        @SerialName("resid_sd_mm") val residSdMm: Double,
        val corr: Double,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true } // by_sex etc. are informational
        fun parse(text: String): AnthroPriors = json.decodeFromString(serializer(), text)
    }
}

/**
 * M2 plausibility gate: catches a mis-measured PD or face width before it drives frame fit and thickness.
 * - PD/face-width ratio outside ANSUR p1–p99 → RESCAN (landmarks or scale inconsistent within the frame).
 * - PD more than plausibility.pdResidualSdMultiplier × residual SD from the PD predicted by face width → LOW_CONFIDENCE.
 * - PD or face width outside its own ANSUR p1–p99 → LOW_CONFIDENCE. This catches a wrong mm/px scale, which moves
 *   PD and face width together and so leaves the ratio unchanged.
 * - Non-positive or non-finite input → RESCAN.
 * Use directly with FaceGeometry output via `gate.check(measurement)` (extensions in face/FaceGeometry.kt).
 */
class PlausibilityGate(private val priors: AnthroPriors, private val config: PlausibilityConfig) {

    enum class Verdict { OK, LOW_CONFIDENCE, RESCAN }

    enum class Reason { INVALID_INPUT, RATIO_OUT_OF_RANGE, PD_RESIDUAL_HIGH, PD_OUT_OF_RANGE, FACE_WIDTH_OUT_OF_RANGE }

    data class Result(
        val verdict: Verdict,
        val ratio: Double?,
        val predictedPdMm: Double?,
        val residualMm: Double?,
        val reasons: List<Reason> = emptyList(),
    ) {
        /** Confidence the checked PD may carry at most. */
        val confidenceCap: Confidence get() = if (verdict == Verdict.OK) Confidence.HIGH else Confidence.LOW
    }

    fun check(pdMm: Double, faceWidthMm: Double?): Result {
        val valid = pdMm > 0 && pdMm.isFinite() && (faceWidthMm == null || (faceWidthMm > 0 && faceWidthMm.isFinite()))
        if (!valid) return Result(Verdict.RESCAN, null, null, null, listOf(Reason.INVALID_INPUT))
        val pdInRange = pdMm in priors.pdMm.p1..priors.pdMm.p99
        if (faceWidthMm == null) {
            // Only PD known: compare with the population range.
            return if (pdInRange) Result(Verdict.OK, null, null, null)
            else Result(Verdict.LOW_CONFIDENCE, null, null, null, listOf(Reason.PD_OUT_OF_RANGE))
        }
        val ratio = pdMm / faceWidthMm
        if (ratio < priors.pdToFaceRatio.p1 || ratio > priors.pdToFaceRatio.p99) {
            return Result(Verdict.RESCAN, ratio, null, null, listOf(Reason.RATIO_OUT_OF_RANGE))
        }
        val reg = priors.pdFromFaceWidth
        val predicted = reg.slope * faceWidthMm + reg.intercept
        val residual = pdMm - predicted
        val reasons = buildList {
            if (abs(residual) > config.pdResidualSdMultiplier * reg.residSdMm) add(Reason.PD_RESIDUAL_HIGH)
            if (!pdInRange) add(Reason.PD_OUT_OF_RANGE)
            if (faceWidthMm !in priors.faceWidthMm.p1..priors.faceWidthMm.p99) add(Reason.FACE_WIDTH_OUT_OF_RANGE)
        }
        val verdict = if (reasons.isEmpty()) Verdict.OK else Verdict.LOW_CONFIDENCE
        return Result(verdict, ratio, predicted, residual, reasons)
    }
}
