package com.drishtisaathi.engine

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.tan

/** M3 formulas. All constants come from [OpticsConfig] (rules.json). Distances in metres unless named _mm. */
class Optics(private val c: OpticsConfig) {

    /** Height of a letter subtending [OpticsConfig.letterArcmin] at distance d: h = 2·d·tan(arcmin/2). */
    fun letterHeightMm(distanceMm: Double): Double {
        val halfAngleRad = Math.toRadians(c.letterArcmin / 2.0 / 60.0)
        return 2.0 * distanceMm * tan(halfAngleRad)
    }

    fun roundToStep(d: Double, step: Double = c.powerStepD): Double = (d / step).roundToLong() * step

    data class FarPointResult(
        val seBlueD: Double,
        val seWhiteD: Double?,
        /** Best estimate: mean of blue and white when both are usable and agree, else blue alone. */
        val seD: Double,
        val disagreementD: Double?,
        /** False when blue and white disagree by more than the limit: repeat the test. */
        val reliable: Boolean,
        /** Blur never reached within arm's length: no significant myopia (hyperopia not excluded). */
        val noMyopiaAtArmsLength: Boolean,
    )

    /**
     * SE_blue = −1/d_b + LCA offset, SE_white = −1/d_w. White is used only if d_w ≤ whiteFarPointMaxM.
     * Pass null for a distance where no blur was found within arm's length.
     */
    fun farPoint(dBlueM: Double?, dWhiteM: Double?): FarPointResult {
        if (dBlueM == null || dBlueM >= c.armsLengthM) {
            return FarPointResult(0.0, null, 0.0, null, reliable = true, noMyopiaAtArmsLength = true)
        }
        require(dBlueM > 0) { "far point distance must be positive" }
        val seBlue = -1.0 / dBlueM + c.lcaBlueOffsetD
        val seWhite = dWhiteM?.takeIf { it > 0 && it <= c.whiteFarPointMaxM }?.let { -1.0 / it }
        if (seWhite == null) {
            return FarPointResult(seBlue, null, seBlue, null, reliable = true, noMyopiaAtArmsLength = false)
        }
        val diff = abs(seBlue - seWhite)
        val reliable = diff <= c.farPointAgreementMaxD + 1e-9
        val se = if (reliable) (seBlue + seWhite) / 2.0 else seBlue
        return FarPointResult(seBlue, seWhite, se, diff, reliable, noMyopiaAtArmsLength = false)
    }

    /** Hofstetter minimum expected amplitude of accommodation: 15 − 0.25 × age. */
    fun hofstetterMinAmplitude(age: Int): Double =
        (c.hofstetterMinIntercept - c.hofstetterMinSlopePerYear * age).coerceAtLeast(0.0)

    fun workingDistanceM(occupation: Occupation?): Double =
        if (occupation == Occupation.FINE_HANDWORK) c.workingDistanceFineWorkM else c.workingDistanceDefaultM

    data class NearPointResult(val amplitudeD: Double, val rawAddD: Double, val addD: Double, val expectedMinAmplitudeD: Double?)

    /**
     * A = 1/d_np − |SE|; Add = 1/WD − A/2; rounded to the power step and clamped to [addMin, addMax].
     */
    fun nearPointAdd(dNearPointM: Double, seD: Double, workingDistanceM: Double, age: Int? = null): NearPointResult {
        require(dNearPointM > 0 && workingDistanceM > 0)
        val amplitude = 1.0 / dNearPointM - abs(seD)
        val raw = 1.0 / workingDistanceM - amplitude / 2.0
        return NearPointResult(amplitude, raw, clampAdd(roundToStep(raw)), age?.let { hofstetterMinAmplitude(it) })
    }

    /** Add expected from age alone, using the Hofstetter minimum amplitude. */
    fun addFromAgeExpectation(age: Int, workingDistanceM: Double): Double =
        clampAdd(roundToStep(1.0 / workingDistanceM - hofstetterMinAmplitude(age) / 2.0))

    fun clampAdd(add: Double): Double = add.coerceIn(c.addMinD, c.addMaxD)

    /** Each test is run twice: take the median; confidence from the spread of repeats. */
    fun combineRepeats(values: List<Double>): Estimate<Double> {
        require(values.isNotEmpty())
        val spread = values.max() - values.min()
        val conf = when {
            values.size < 2 -> Confidence.LOW
            spread <= c.repeatSpreadHighMaxD + 1e-9 -> Confidence.HIGH
            spread <= c.repeatSpreadMediumMaxD + 1e-9 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        return Estimate(median(values), conf, Source.MEASURED)
    }

    companion object {
        fun median(values: List<Double>): Double {
            val s = values.sorted()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
        }
    }
}
