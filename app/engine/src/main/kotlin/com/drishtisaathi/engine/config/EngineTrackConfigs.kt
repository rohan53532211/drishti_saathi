package com.drishtisaathi.engine.config

import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.FrameShape
import com.drishtisaathi.engine.face.FaceShape
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Track A (engine) configuration: the "distance", "bayes" and "faceGeometry" sections of assets/rules.json.
 *
 * Parsing is strict: every field below without a default must be present in rules.json (a missing key throws
 * MissingFieldException), and unknown keys are rejected by RulesConfig. Only "_comment" fields are optional.
 *
 * RulesConfig declares these sections with a no-argument default (`DistanceConfig()`); that call resolves to the
 * companion `invoke()` below, which throws. So a rules.json without the section fails loudly instead of silently
 * running on code defaults.
 */

private fun missingSection(name: String): Nothing =
    throw IllegalArgumentException("rules.json is missing the \"$name\" section (Track A engine config)")

// =============================================================================================
// distance: eye-to-camera distance from the iris (MediaPipe Iris method)
// =============================================================================================

@Serializable
data class DistanceConfig(
    @SerialName("_comment") val comment: String? = null,
    /** Population mean horizontal visible iris diameter (MediaPipe Iris paper: 11.7 ± 0.5 mm). */
    val irisDiameterPriorMm: Double,
    val irisDiameterPriorSdMm: Double,
    /** Uncertainty of a personal iris diameter measured against the bank card. */
    val personalIrisSdMm: Double,
    /** A card-derived personal iris outside this range is treated as a failed calibration; the prior is used. */
    val personalIrisMinMm: Double,
    val personalIrisMaxMm: Double,
    /** Fallback when CameraCharacteristics gives no focal length: typical front-camera field of view along the image's LONGER side. */
    val fallbackLongSideFovDeg: Double,
    /** Landmark noise of one iris diameter measurement, in pixels (sub-pixel ring fit). */
    val irisPixelSdPx: Double,
    /** Below this many pixels an iris is too small to trust (face too far or too low resolution). */
    val minIrisPx: Double,
    /** Two irises differing by more than this ratio: the smaller one is taken as occluded/foreshortened and dropped. */
    val irisPairMaxRatio: Double,
    /** Relative SD of distance (σd/d) at or below which the estimate is HIGH, then MEDIUM; above → LOW. */
    val highMaxRelativeSd: Double,
    val mediumMaxRelativeSd: Double,
    /** Temporal smoother for the 15–30 Hz stream: median over the last N samples, then EMA. alpha = 1 disables EMA. */
    val smootherWindow: Int,
    val smootherEmaAlpha: Double,
    /** Samples older than this (relative to the newest) are dropped from the window. */
    val smootherMaxAgeMs: Long,
    /**
     * Optional camera → screen-centre correction. The iris method measures eye-to-camera depth; the target is drawn
     * at the screen centre, which on most phones sits some 60–80 mm below the front camera. Offsets are in mm in the
     * upright image axes (x right, y down). Off by default because the value is phone-specific.
     */
    val applyScreenCentreOffset: Boolean,
    val screenCentreOffsetXMm: Double,
    val screenCentreOffsetYMm: Double,
) {
    init {
        require(irisDiameterPriorMm > 0 && irisDiameterPriorSdMm >= 0 && personalIrisSdMm >= 0)
        require(personalIrisMinMm < personalIrisMaxMm)
        require(fallbackLongSideFovDeg in 10.0..170.0)
        require(irisPairMaxRatio >= 1.0)
        require(highMaxRelativeSd <= mediumMaxRelativeSd)
        require(smootherWindow >= 1 && smootherEmaAlpha > 0 && smootherEmaAlpha <= 1.0)
    }

    companion object {
        operator fun invoke(): DistanceConfig = missingSection("distance")
    }
}

// =============================================================================================
// bayes: Bayesian SE band (innovation 10) and add fusion with the age expectation (innovation 5)
// =============================================================================================

@Serializable
data class BayesConfig(
    @SerialName("_comment") val comment: String? = null,
    /** false → measured eyes get the old fixed band (optics.measuredBandHalfWidthD around the rounded SE). */
    val enabled: Boolean,
    /** SE grid (dioptres). */
    val gridMinD: Double,
    val gridMaxD: Double,
    val gridStepD: Double,
    /** Width of the reported band ("−1.00 to −2.00 D"). */
    val bandWidthD: Double,
    /** Likelihood σ by the measurement's own confidence (repeat spread). Brief: σ ≈ 0.8 D for a typical far point. */
    val sigmaHighD: Double,
    val sigmaMediumD: Double,
    val sigmaLowD: Double,
    /** Exponent on the prior: 1 = full Bayes, 0 = flat prior (likelihood only). Lets an optometrist soften the prior. */
    val priorWeight: Double,
    /** Class boundaries used by the prevalence figures: myopia SE ≤ myopiaMaxD, hyperopia SE ≥ hyperopiaMinD. */
    val myopiaMaxD: Double,
    val hyperopiaMinD: Double,
    /** Age used to pick the prior band when the age is unknown. */
    val assumedAgeWhenUnknown: Int,
    val agePriors: List<AgePrior>,
    /** Posterior probability of the reported band: ≥ high → HIGH, ≥ medium → MEDIUM, else LOW. */
    val bandProbabilityHigh: Double,
    val bandProbabilityMedium: Double,
    val addFusion: AddFusionConfig,
) {
    @Serializable
    data class AgePrior(
        @SerialName("_comment") val comment: String? = null,
        val minAge: Int,
        val maxAge: Int,
        /** Fractions of the age band that are myopic / hyperopic; the rest is emmetropic. */
        val myopiaPrevalence: Double,
        val hyperopiaPrevalence: Double,
        /** Myopic SE falls off as exp(−(|SE| − |myopiaMaxD|)/scale) beyond the class boundary. */
        val myopiaScaleD: Double,
        val hyperopiaScaleD: Double,
        /** Emmetropic part: Gaussian on the open interval between the class boundaries. */
        val emmetropiaMeanD: Double,
        val emmetropiaSdD: Double,
    ) {
        init {
            require(minAge <= maxAge)
            require(myopiaPrevalence >= 0 && hyperopiaPrevalence >= 0 && myopiaPrevalence + hyperopiaPrevalence < 1.0)
            require(myopiaScaleD > 0 && hyperopiaScaleD > 0 && emmetropiaSdD > 0)
        }
    }

    init {
        require(gridMinD < gridMaxD && gridStepD > 0)
        require(bandWidthD > 0 && bandWidthD < gridMaxD - gridMinD)
        require(sigmaHighD > 0 && sigmaMediumD > 0 && sigmaLowD > 0)
        require(priorWeight in 0.0..1.0)
        require(myopiaMaxD < hyperopiaMinD)
        require(agePriors.isNotEmpty())
        require(agePriors.any { assumedAgeWhenUnknown in it.minAge..it.maxAge }) { "assumedAgeWhenUnknown not covered" }
        require(bandProbabilityHigh >= bandProbabilityMedium)
    }

    fun sigmaFor(c: Confidence): Double = when (c) {
        Confidence.HIGH -> sigmaHighD
        Confidence.MEDIUM -> sigmaMediumD
        Confidence.LOW -> sigmaLowD
    }

    companion object {
        operator fun invoke(): BayesConfig = missingSection("bayes")
    }
}

/**
 * Innovation 5: the measured near-point add is fused with what the age predicts (Hofstetter).
 * Amplitude ranges per age: min = 15 − 0.25·age, mean = 18.5 − 0.30·age, max = 25 − 0.40·age (min comes from optics).
 */
@Serializable
data class AddFusionConfig(
    @SerialName("_comment") val comment: String? = null,
    val hofstetterMeanIntercept: Double,
    val hofstetterMeanSlopePerYear: Double,
    val hofstetterMaxIntercept: Double,
    val hofstetterMaxSlopePerYear: Double,
    /** Slack around [min, max] before a measured amplitude counts as implausible (dioptres). */
    val amplitudeToleranceD: Double,
    /** σ of a measured add (J Optom 2025: 82.5 % within 0.50 D → σ ≈ 0.37 D). */
    val measuredAddSigmaD: Double,
    /** σ of the add predicted from age alone. */
    val ageAddSigmaD: Double,
    /** Measured σ is multiplied by this when the amplitude is outside the plausible age range. */
    val implausibleSigmaMultiplier: Double,
) {
    init {
        require(amplitudeToleranceD >= 0 && measuredAddSigmaD > 0 && ageAddSigmaD > 0 && implausibleSigmaMultiplier >= 1)
    }
}

// =============================================================================================
// faceGeometry: MediaPipe Face Landmarker indices, face-shape thresholds, aggregation
// =============================================================================================

@Serializable
data class FaceGeometryConfig(
    @SerialName("_comment") val comment: String? = null,
    val landmarks: LandmarkIndices,
    val shape: FaceShapeConfig,
    val pose: PoseConfig,
    val aggregate: AggregateConfig,
) {
    /**
     * Every landmark index the engine reads. MediaPipe 478-point topology (468 mesh + 10 iris).
     * "Right"/"left" are the subject's own sides in MediaPipe's canonical mesh; on screen they may swap with mirroring,
     * which no measure here depends on.
     */
    @Serializable
    data class LandmarkIndices(
        @SerialName("_comment") val comment: String? = null,
        /** 468 = iris centre, 469–472 = its 4 ring points (horizontal pair 469/471, vertical pair 470/472). */
        val irisRightCentre: Int,
        val irisRightRing: List<Int>,
        /** 473 = iris centre, 474–477 = ring (horizontal 474/476, vertical 475/477). */
        val irisLeftCentre: Int,
        val irisLeftRing: List<Int>,
        /** Face width: 234 / 454 = outermost cheek contour at the zygomatic level. */
        val faceWidthRight: Int,
        val faceWidthLeft: Int,
        /** Face length: 10 = top of the forehead on the mesh midline, 152 = chin (menton). */
        val faceTop: Int,
        val chin: Int,
        /** Jaw width: 172 / 397 = contour points near the jaw angles (gonion). */
        val jawRight: Int,
        val jawLeft: Int,
        /** Forehead width: 54 / 284 = upper forehead contour at the temples (heart-shape test). */
        val foreheadRight: Int,
        val foreheadLeft: Int,
        /**
         * Bridge width where the frame's pads rest: 122 / 351 = sides of the nose beside landmark 6 (nasal bridge
         * midline just below the eyes, about the inner-canthus height). Approximate; check against frame DBL in the pilot.
         */
        val bridgeRight: Int,
        val bridgeLeft: Int,
        /** Eye corners for roll: 33 = right outer canthus, 263 = left outer canthus. */
        val eyeOuterRight: Int,
        val eyeOuterLeft: Int,
        /** 1 = nose tip (yaw/pitch from landmarks). */
        val noseTip: Int,
    ) {
        init {
            require(irisRightRing.size == 4 && irisLeftRing.size == 4) { "iris rings need 4 points" }
        }

        val all: List<Int>
            get() = listOf(irisRightCentre, irisLeftCentre, faceWidthRight, faceWidthLeft, faceTop, chin, jawRight, jawLeft,
                foreheadRight, foreheadLeft, bridgeRight, bridgeLeft, eyeOuterRight, eyeOuterLeft, noseTip) +
                irisRightRing + irisLeftRing
    }

    /** Rule-based face shape for style suggestions only. Checked in this order: LONG, SQUARE, ROUND, HEART, else OVAL. */
    @Serializable
    data class FaceShapeConfig(
        @SerialName("_comment") val comment: String? = null,
        /** width/length below this → LONG. */
        val longMaxWidthToLength: Double,
        /** jaw/cheek at or above this (and not LONG) → SQUARE. */
        val squareMinJawToCheek: Double,
        /** width/length at or above this (and jaw not square) → ROUND. */
        val roundMinWidthToLength: Double,
        /** jaw/cheek at or below this and forehead/cheek at or above heartMinForeheadToCheek → HEART. */
        val heartMaxJawToCheek: Double,
        val heartMinForeheadToCheek: Double,
        /** Any ratio within this distance of a threshold it was decided on → LOW confidence, else MEDIUM. */
        val borderlineMargin: Double,
        /** Style advice only: frame shapes that usually suit each face shape, best first. */
        val suggestedFrames: Map<FaceShape, List<FrameShape>>,
    ) {
        init {
            require(FaceShape.entries.all { !suggestedFrames[it].isNullOrEmpty() }) { "suggestedFrames needs every FaceShape" }
        }
    }

    @Serializable
    data class PoseConfig(
        @SerialName("_comment") val comment: String? = null,
        /** Nose-tip protrusion in front of the cheek points as a fraction of face width (yaw from landmarks). */
        val noseDepthToFaceWidth: Double,
        /** Nose-tip protrusion as a fraction of face length (pitch from landmarks). */
        val noseDepthToFaceLength: Double,
        /** Where the nose tip sits between forehead (0) and chin (1) when the head is level. */
        val noseTipNeutralFraction: Double,
    )

    @Serializable
    data class AggregateConfig(
        @SerialName("_comment") val comment: String? = null,
        /** The camera track should collect this many accepted frames. */
        val targetFrames: Int,
        /** Fewer frames than this → every measure LOW confidence. */
        val minFrames: Int,
        /** Outlier: |x − median| > k · 1.4826 · MAD, never tighter than outlierMinAbsMm. */
        val outlierMadK: Double,
        val outlierMinAbsMm: Double,
        /** Robust SD (1.4826·MAD of kept values) of PD at or below → HIGH / MEDIUM, above → LOW. */
        val pdSpreadHighMaxMm: Double,
        val pdSpreadMediumMaxMm: Double,
        /** Same for the wider measures (face width/length, jaw, bridge), relative to the median. */
        val widthSpreadHighMaxFraction: Double,
        val widthSpreadMediumMaxFraction: Double,
        /** Best confidence allowed by how the mm/px scale was obtained. */
        val capCardScale: Confidence,
        val capPersonalIrisScale: Confidence,
        val capPriorIrisScale: Confidence,
    ) {
        init {
            require(minFrames in 1..targetFrames)
            require(pdSpreadHighMaxMm <= pdSpreadMediumMaxMm && widthSpreadHighMaxFraction <= widthSpreadMediumMaxFraction)
        }
    }

    companion object {
        operator fun invoke(): FaceGeometryConfig = missingSection("faceGeometry")
    }
}
