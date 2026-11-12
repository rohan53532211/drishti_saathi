package com.drishtisaathi.engine.config

import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.FrameShape
import com.drishtisaathi.engine.Occupation
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
    /** false → measured eyes get the old fixed band (bandWidthD around the rounded SE). */
    val enabled: Boolean,
    /** SE grid (dioptres). */
    val gridMinD: Double,
    val gridMaxD: Double,
    val gridStepD: Double,
    /** Width of the reported band ("−1.00 to −2.00 D"). */
    val bandWidthD: Double,
    /**
     * Likelihood σ of a measured far-point SE: 0.80 D from the real studies (Luo 2022, MAE 0.65 D), not the optimistic
     * simulated value. Repeats that disagree more widen it ([com.drishtisaathi.engine.vision.BayesianBand.sigmaFromRepeats]).
     */
    val likelihoodSigmaD: Double,
    /** Exponent on the prior: 1 = full Bayes, 0 = flat prior (likelihood only). Lets an optometrist soften the prior. */
    val priorWeight: Double,
    /** Class boundaries used by the prevalence figures: myopia SE ≤ myopiaMaxD, hyperopia SE ≥ hyperopiaMinD. */
    val myopiaMaxD: Double,
    val hyperopiaMinD: Double,
    /** Shape of the myopic part of the prior (all age bands). */
    val myopiaMagnitudePrior: MagnitudePrior,
    /** Age used to pick the prior band when the age is unknown. */
    val assumedAgeWhenUnknown: Int,
    val agePriors: List<AgePrior>,
    /** Band probability at or above this → HIGH confidence. */
    val bandProbabilityHigh: Double,
    /**
     * Below this the most probable band is not shown as an estimated band: the starting trial lens is kept, the band
     * is flagged uncertain (LOW) and staff are told to refine with the hand test. At or above it (below high) → MEDIUM.
     */
    val minBandProbabilityToShow: Double,
    val addFusion: AddFusionConfig,
) {
    enum class MagnitudeShape { EXPONENTIAL }

    /** Myopic |SE| = |myopiaMaxD| + Exp(scale), scale = meanD − |myopiaMaxD|, so the mean myopic magnitude is meanD. */
    @Serializable
    data class MagnitudePrior(
        val type: MagnitudeShape,
        val meanD: Double,
        val note: String? = null,
    )

    @Serializable
    data class AgePrior(
        @SerialName("_comment") val comment: String? = null,
        val minAge: Int,
        val maxAge: Int,
        /** Fractions of the age band that are myopic / hyperopic / emmetropic; must sum to 1. */
        val myopiaPrevalence: Double,
        val hyperopiaPrevalence: Double,
        val emmetropiaPrevalence: Double,
        /** Hyperopic SE falls off as exp(−(SE − hyperopiaMinD)/scale). */
        val hyperopiaScaleD: Double,
        /** Emmetropic part: Gaussian on the open interval between the class boundaries. */
        val emmetropiaMeanD: Double,
        val emmetropiaSdD: Double,
    ) {
        init {
            require(minAge <= maxAge)
            require(myopiaPrevalence >= 0 && hyperopiaPrevalence >= 0 && emmetropiaPrevalence >= 0)
            require(kotlin.math.abs(myopiaPrevalence + hyperopiaPrevalence + emmetropiaPrevalence - 1.0) < 0.005) {
                "agePriors: myopia + hyperopia + emmetropia must sum to 1"
            }
            require(hyperopiaScaleD > 0 && emmetropiaSdD > 0)
        }
    }

    init {
        require(gridMinD < gridMaxD && gridStepD > 0)
        require(bandWidthD > 0 && bandWidthD < gridMaxD - gridMinD)
        require(likelihoodSigmaD > 0)
        require(priorWeight in 0.0..1.0)
        require(myopiaMaxD < hyperopiaMinD)
        require(myopiaMagnitudePrior.meanD > -myopiaMaxD) { "myopia mean magnitude must exceed the class boundary" }
        require(agePriors.isNotEmpty())
        require(agePriors.any { assumedAgeWhenUnknown in it.minAge..it.maxAge }) { "assumedAgeWhenUnknown not covered" }
        require(minBandProbabilityToShow in 0.0..bandProbabilityHigh)
    }

    /** Exponential scale of the myopic magnitude beyond the class boundary. */
    val myopiaScaleD: Double get() = myopiaMagnitudePrior.meanD + myopiaMaxD

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

// =============================================================================================
// farPoint: how the far point is taken (simulation-tuned rules, rules_update.md).
// The LCA offset, white limit and blue/white agreement stay in "optics" (lcaBlueOffsetD, whiteFarPointMaxM,
// farPointAgreementMaxD) and the repeat-disagreement threshold is optics.repeatSpreadMediumMaxD: one source each.
// =============================================================================================

@Serializable
data class FarPointConfig(
    @SerialName("_comment") val comment: String? = null,
    /** Customer still sees clearly with the phone this far (arm's reach): offer the staff-held mode. */
    val selfHeldReachM: Double,
    /** Staff-held mode (Luo method): blur taps valid up to here; "still clear" only counts beyond it. */
    val staffHeldMaxM: Double,
    /** Beyond this the iris is only a few pixels wide: use [beyondIrisUse] for the distance. */
    val irisDistanceMaxM: Double,
    val beyondIrisUse: BeyondIris,
    /** Blue vs white SE differ by more than optics.farPointAgreementMaxD. */
    val consistencyAction: ConsistencyAction,
    /** Two repeats of one colour differ by more than optics.repeatSpreadMediumMaxD. */
    val repeatSpreadAction: RepeatSpreadAction,
    /** Far reading check: letter height in arcmin, distance-locked (7.5 = 20/30; 5 = 20/20). Tuned after the pilot. */
    val letterArcmin: Double = 7.5,
    /** Far reading check: letters are never drawn smaller than this (px), so they always stay visible. */
    val minLetterPx: Double = 18.0,
) {
    enum class BeyondIris {
        /** d = f_px × PD_mm / interpupillary px with the card-calibrated PD; without one, iris at LOW confidence. */
        CARD_CALIBRATED_PD,
        /** Keep the iris estimate, LOW confidence beyond irisDistanceMaxM. */
        IRIS_LOW_CONFIDENCE,
    }

    enum class ConsistencyAction {
        /** Retake both colours once; still disagreeing → blue SE at LOW confidence. */
        RETAKE_ONCE_THEN_LOW_CONFIDENCE,
        /** No retake: blue SE at LOW confidence straight away. */
        LOW_CONFIDENCE,
    }

    enum class RepeatSpreadAction {
        /** Take a third reading of that colour and use the median of the three. */
        THIRD_READING_MEDIAN,
        /** No third reading: the median of two at LOW confidence. */
        LOW_CONFIDENCE,
    }

    init {
        require(selfHeldReachM > 0 && staffHeldMaxM > selfHeldReachM && irisDistanceMaxM > 0)
        require(letterArcmin > 0 && minLetterPx > 0)
    }

    companion object {
        operator fun invoke(): FarPointConfig = missingSection("farPoint")
    }
}

/** How PD (and face width) may be presented and used, by calibration (rules_update.md rule 6). */
@Serializable
data class PdMeasurementConfig(
    @SerialName("_comment") val comment: String? = null,
    /** SD of a camera PD without the card (11.7 mm iris prior scale): shown as "about X mm (±3 mm)". */
    val sdNoCardMm: Double,
    /** SD of a card-calibrated PD: "±1 mm". */
    val sdCardMm: Double,
    /** True: never say a frame "fits" from measurements that are not card-calibrated. */
    val frameFitClaimsRequireCard: Boolean,
) {
    init { require(sdNoCardMm > 0 && sdCardMm > 0) }

    companion object {
        operator fun invoke(): PdMeasurementConfig = missingSection("pdMeasurement")
    }
}

/** Reading-add suggestions (rules_update.md rule 7). */
@Serializable
data class ReadingAddConfig(
    @SerialName("_comment") val comment: String? = null,
    /** Every reading-add suggestion says "confirm with a ready-reader at the counter". */
    val verifyWithReadyReader: Boolean,
) {
    companion object {
        operator fun invoke(): ReadingAddConfig = missingSection("readingAdd")
    }
}

/** Per-phone-model bias correction from verified shop records (see vision/DeviceCorrection.kt). */
@Serializable
data class DeviceCorrectionConfig(
    @SerialName("_comment") val comment: String? = null,
    val enabled: Boolean,
    /** Verified sessions on this phone model needed before any correction is fitted or applied. */
    val minVerifiedSessions: Int,
    /** Below this SD of measured SE (D) only an offset is fitted (slope 1). */
    val minMeasuredSpreadD: Double,
    val slopeMin: Double,
    val slopeMax: Double,
    /** Pairs whose measured and final SE differ by more than this (D) are treated as entry errors. */
    val excludePairAbsDiffOverD: Double,
    /** Largest change the correction may make to one estimate (D). */
    val maxCorrectionD: Double,
) {
    init {
        require(minVerifiedSessions >= 2) { "deviceCorrection.minVerifiedSessions must be >= 2" }
        require(slopeMin in 0.0..1.0 && slopeMax >= 1.0) { "deviceCorrection slope bounds must contain 1" }
        require(minMeasuredSpreadD >= 0 && excludePairAbsDiffOverD > 0 && maxCorrectionD >= 0) { "deviceCorrection: bad bounds" }
    }
    companion object {
        operator fun invoke(): DeviceCorrectionConfig = missingSection("deviceCorrection")
    }
}

/** Power path quick estimate (interview → "do you remember your power?" NOT SURE / NO). */
@Serializable
data class QuickEstimateConfig(
    @SerialName("_comment") val comment: String? = null,
    /** Target for the quick far point: WHITE (SE = −1/d) or BLUE (SE = −1/d + optics.lcaBlueOffsetD). */
    val target: com.drishtisaathi.engine.QuickTarget,
    /** Logarithm of the minimum angle of resolution of the distance-locked letters (0.0 = 20/20). */
    val letterLogMar: Double,
    /** Near point → reading add only from this age; younger near-only problems get "possible long-sightedness". */
    val nearAddFromAge: Int,
    /** YES path: offer an optional quick check when the glasses are older than this (years) or blur is still reported. */
    val compareIfGlassesOlderThanYears: Int,
    /** ± around the estimate for the "estimated band" of a phone estimate (D). */
    val bandHalfWidthD: Double,
    /** Usual reading distance answer → working distance (m) for the add. */
    val workingDistanceM: Map<com.drishtisaathi.engine.NearDistance, Double>,
) {
    init {
        require(bandHalfWidthD > 0 && nearAddFromAge in 30..60 && compareIfGlassesOlderThanYears >= 0) { "quickEstimate: bad values" }
        require(workingDistanceM.keys == com.drishtisaathi.engine.NearDistance.entries.toSet() && workingDistanceM.values.all { it in 0.15..0.8 }) {
            "quickEstimate.workingDistanceM needs every NearDistance, 0.15-0.8 m"
        }
    }
    companion object {
        operator fun invoke(): QuickEstimateConfig = missingSection("quickEstimate")
    }
}

/** Functional vision profile: estimated daily hours per zone from the interview answers. */
@Serializable
data class FunctionalProfileConfig(
    @SerialName("_comment") val comment: String? = null,
    val nearHoursByReading: Map<com.drishtisaathi.engine.ReadingFrequency, Double>,
    val nearHoursByTask: Map<com.drishtisaathi.engine.NearTask, Double>,
    /** Screen hours answer → hours; split between intermediate (computer) and near (phone) by occupation. */
    val screenHours: Map<com.drishtisaathi.engine.ScreenHours, Double>,
    val screenIntermediateShareByOccupation: Map<Occupation, Double>,
    val screenIntermediateShareDefault: Double,
    val distanceHoursBase: Double,
    val distanceHoursByOccupation: Map<Occupation, Double>,
    val distanceHoursOutdoorWork: Double,
    val distanceHoursNightDriving: Double,
    val distanceHoursBySituation: Map<com.drishtisaathi.engine.DistanceSituation, Double>,
    /** A zone is "dominant" when it has at least this share of the total hours; otherwise MIXED. */
    val dominantMinShare: Double,
) {
    init {
        require(dominantMinShare in 0.34..1.0) { "functionalProfile.dominantMinShare must be 0.34-1" }
        require(screenIntermediateShareDefault in 0.0..1.0 && screenIntermediateShareByOccupation.values.all { it in 0.0..1.0 }) {
            "functionalProfile: screen shares must be 0-1"
        }
    }
    companion object {
        operator fun invoke(): FunctionalProfileConfig = missingSection("functionalProfile")
    }
}
