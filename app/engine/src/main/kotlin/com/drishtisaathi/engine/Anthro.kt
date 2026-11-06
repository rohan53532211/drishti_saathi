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

/** M2 plausibility gate: catches a mis-measured PD or face width before it drives frame fit and thickness. */
class PlausibilityGate(private val priors: AnthroPriors, private val config: PlausibilityConfig) {

    enum class Verdict { OK, LOW_CONFIDENCE, RESCAN }

    data class Result(val verdict: Verdict, val ratio: Double?, val predictedPdMm: Double?, val residualMm: Double?)

    fun check(pdMm: Double, faceWidthMm: Double?): Result {
        if (faceWidthMm == null) {
            // Only PD known: compare with the population range.
            val ok = pdMm in priors.pdMm.p1..priors.pdMm.p99
            return Result(if (ok) Verdict.OK else Verdict.LOW_CONFIDENCE, null, null, null)
        }
        val ratio = pdMm / faceWidthMm
        if (ratio < priors.pdToFaceRatio.p1 || ratio > priors.pdToFaceRatio.p99) {
            return Result(Verdict.RESCAN, ratio, null, null)
        }
        val reg = priors.pdFromFaceWidth
        val predicted = reg.slope * faceWidthMm + reg.intercept
        val residual = pdMm - predicted
        val verdict = if (abs(residual) > config.pdResidualSdMultiplier * reg.residSdMm) Verdict.LOW_CONFIDENCE else Verdict.OK
        return Result(verdict, ratio, predicted, residual)
    }
}
