package com.drishtisaathi.engine.vision

import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.Estimate
import com.drishtisaathi.engine.Occupation
import com.drishtisaathi.engine.Optics
import com.drishtisaathi.engine.OpticsConfig
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.Source
import com.drishtisaathi.engine.config.AddFusionConfig
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The named public API for the M3 vision-test formulas. Built on [Optics] (constants from rules.json `optics`)
 * plus the add fusion of innovation 5 (rules.json `bayes.addFusion`). Distances in metres unless named _mm.
 */
class VisionMath(private val c: OpticsConfig, private val fusion: AddFusionConfig) {

    constructor(rules: RulesConfig) : this(rules.optics, rules.bayes.addFusion)

    val optics = Optics(c)

    // ---- optotype size --------------------------------------------------------------------------

    /** 20/20 letter: h = 2·d·tan(2.5′) ≈ 0.001454·d (letter subtends optics.letterArcmin = 5′). */
    fun optotypeHeightMm(distanceMm: Double): Double = optics.letterHeightMm(distanceMm)

    /** Same letter in screen pixels, from the card-calibrated screen scale; [sizeMultiple] 2 = 20/40 etc. */
    fun optotypeHeightPx(distanceMm: Double, screenMmPerPx: Double, sizeMultiple: Double = 1.0): Double {
        require(screenMmPerPx > 0)
        return optotypeHeightMm(distanceMm) * sizeMultiple / screenMmPerPx
    }

    // ---- far point -----------------------------------------------------------------------------

    /** SE from a blue target's far point: −1/d + LCA offset (blue focuses ~0.67 D in front of white). */
    fun seFromBlueFarPoint(dBlueM: Double): Double {
        require(dBlueM > 0); return -1.0 / dBlueM + c.lcaBlueOffsetD
    }

    /** SE from a white target's far point: −1/d. */
    fun seFromWhiteFarPoint(dWhiteM: Double): Double {
        require(dWhiteM > 0); return -1.0 / dWhiteM
    }

    /** White is only trusted up to optics.whiteFarPointMaxM (0.8 m). */
    fun whiteUsable(dWhiteM: Double?): Boolean = dWhiteM != null && dWhiteM > 0 && dWhiteM <= c.whiteFarPointMaxM

    /** Two-colour self-check (innovation 3): agree when |SE_blue − SE_white| ≤ optics.farPointAgreementMaxD after LCA correction. */
    fun blueWhiteConsistent(seBlueD: Double, seWhiteD: Double): Boolean =
        abs(seBlueD - seWhiteD) <= c.farPointAgreementMaxD + 1e-9

    /** Full far-point evaluation (null distance = no blur within arm's length). */
    fun farPoint(dBlueM: Double?, dWhiteM: Double?): Optics.FarPointResult = optics.farPoint(dBlueM, dWhiteM)

    // ---- near point / add -----------------------------------------------------------------------

    /** A = 1/d_np − |SE|. */
    fun accommodationAmplitude(dNearPointM: Double, seD: Double): Double {
        require(dNearPointM > 0); return 1.0 / dNearPointM - abs(seD)
    }

    /** Add = 1/WD − A/2, rounded to 0.25 and clamped to [addMin, addMax]. */
    fun nearPointAdd(dNearPointM: Double, seD: Double, workingDistanceM: Double, age: Int? = null): Optics.NearPointResult =
        optics.nearPointAdd(dNearPointM, seD, workingDistanceM, age)

    fun workingDistanceM(occupation: Occupation?): Double = optics.workingDistanceM(occupation)

    /** Hofstetter: minimum 15 − 0.25·age, mean 18.5 − 0.30·age, maximum 25 − 0.40·age (never below 0). */
    fun hofstetterMin(age: Int): Double = optics.hofstetterMinAmplitude(age)
    fun hofstetterMean(age: Int): Double = (fusion.hofstetterMeanIntercept - fusion.hofstetterMeanSlopePerYear * age).coerceAtLeast(0.0)
    fun hofstetterMax(age: Int): Double = (fusion.hofstetterMaxIntercept - fusion.hofstetterMaxSlopePerYear * age).coerceAtLeast(0.0)

    fun roundToStep(d: Double): Double = optics.roundToStep(d)
    fun clampAdd(add: Double): Double = optics.clampAdd(add)

    /** Median of repeats; confidence from their spread (≤ 0.25 HIGH, ≤ 0.50 MEDIUM, else LOW). */
    fun combineRepeats(values: List<Double>): Estimate<Double> = optics.combineRepeats(values)

    // ---- innovation 5: add fused with the age expectation -----------------------------------------

    enum class AmplitudeCheck {
        /** Measured amplitude within [Hofstetter min − tol, max + tol]. */
        WITHIN_EXPECTED,
        /** More accommodation than the age allows: target probably pushed too close or blur reported late. */
        ABOVE_EXPECTED,
        /** Less than the age minimum: possible uncorrected hyperopia or poor accommodation → hand test. */
        BELOW_EXPECTED,
        AGE_UNKNOWN,
    }

    data class AddFusion(
        /** Fused add, rounded and clamped: the value to put in VisionTests.measuredAddD. */
        val add: Estimate<Double>,
        val measuredAddD: Double,
        val ageExpectedAddD: Double?,
        val amplitudeD: Double,
        val expectedMinAmplitudeD: Double?,
        val expectedMaxAmplitudeD: Double?,
        val check: AmplitudeCheck,
        /** Weight given to the measurement in the fusion (1 = measurement only). */
        val measuredWeight: Double,
    )

    /**
     * Rule (innovation 5):
     * 1. Measured add from the near point (A = 1/d_np − |SE|, Add = 1/WD − A/2), unrounded.
     * 2. Age-expected add from the Hofstetter mean amplitude: 1/WD − A_mean/2.
     * 3. If A lies outside [A_min − tol, A_max + tol] the measurement is implausible for the age:
     *    its σ is multiplied by implausibleSigmaMultiplier and the result is LOW confidence.
     * 4. Fused add = inverse-variance mean of the two, rounded to 0.25 D and clamped to +0.75…+3.00.
     * Confidence: the repeat confidence of the near point ([measurementConfidence]), capped LOW when implausible;
     * without age the measured add is used alone.
     */
    fun fuseAdd(
        dNearPointM: Double,
        seD: Double,
        workingDistanceM: Double,
        age: Int?,
        measurementConfidence: Confidence = Confidence.MEDIUM,
    ): AddFusion {
        val near = optics.nearPointAdd(dNearPointM, seD, workingDistanceM)
        val measured = near.rawAddD
        if (age == null) {
            val add = clampAdd(roundToStep(measured))
            return AddFusion(Estimate(add, measurementConfidence, Source.MEASURED), measured, null, near.amplitudeD,
                null, null, AmplitudeCheck.AGE_UNKNOWN, 1.0)
        }
        val aMin = hofstetterMin(age)
        val aMax = hofstetterMax(age)
        val check = when {
            near.amplitudeD > aMax + fusion.amplitudeToleranceD -> AmplitudeCheck.ABOVE_EXPECTED
            near.amplitudeD < aMin - fusion.amplitudeToleranceD -> AmplitudeCheck.BELOW_EXPECTED
            else -> AmplitudeCheck.WITHIN_EXPECTED
        }
        val ageAdd = 1.0 / workingDistanceM - hofstetterMean(age) / 2.0
        val sigmaM = fusion.measuredAddSigmaD *
            (if (check == AmplitudeCheck.WITHIN_EXPECTED) 1.0 else fusion.implausibleSigmaMultiplier)
        val wM = 1.0 / (sigmaM * sigmaM)
        val wA = 1.0 / (fusion.ageAddSigmaD * fusion.ageAddSigmaD)
        val weight = wM / (wM + wA)
        val fused = clampAdd(roundToStep(weight * measured + (1 - weight) * ageAdd))
        val conf = if (check == AmplitudeCheck.WITHIN_EXPECTED) measurementConfidence else Confidence.LOW
        return AddFusion(Estimate(fused, conf, Source.MEASURED), measured, ageAdd, near.amplitudeD, aMin, aMax, check, weight)
    }

    /** σ of the fused add (dioptres), for display or for downstream fusion. */
    fun fusedAddSigma(f: AddFusion): Double {
        if (f.ageExpectedAddD == null) return fusion.measuredAddSigmaD
        val sigmaM = fusion.measuredAddSigmaD *
            (if (f.check == AmplitudeCheck.WITHIN_EXPECTED) 1.0 else fusion.implausibleSigmaMultiplier)
        return 1.0 / sqrt(1.0 / (sigmaM * sigmaM) + 1.0 / (fusion.ageAddSigmaD * fusion.ageAddSigmaD))
    }
}
