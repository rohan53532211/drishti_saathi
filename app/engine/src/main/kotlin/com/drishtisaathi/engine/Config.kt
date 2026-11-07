package com.drishtisaathi.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Typed view of `assets/rules.json`. Every clinical constant used by the engine comes from here.
 * Parsing is strict: an unknown or misspelt key throws, so a bad edit fails the unit tests
 * instead of silently falling back to a default.
 */
@Serializable
data class RulesConfig(
    @SerialName("_comment") val comment: String? = null,
    val version: Int,
    val optics: OpticsConfig,
    val ages: AgeConfig,
    val addAgeTable: List<AddAgeRow>,
    val oldNumbers: OldNumbersConfig,
    val materials: List<MaterialSpec>,
    val materialRules: MaterialRules,
    val lensTypeRules: LensTypeRules,
    val coatingRules: List<CoatingRule>,
    val referral: ReferralConfig,
    val frameScoring: FrameScoringConfig,
    val plausibility: PlausibilityConfig,
    // ---- Sections owned by the parallel tracks (see config/TrackConfigs.kt) ----
    val distance: com.drishtisaathi.engine.config.DistanceConfig = com.drishtisaathi.engine.config.DistanceConfig(),
    val bayes: com.drishtisaathi.engine.config.BayesConfig = com.drishtisaathi.engine.config.BayesConfig(),
    val faceGeometry: com.drishtisaathi.engine.config.FaceGeometryConfig = com.drishtisaathi.engine.config.FaceGeometryConfig(),
    val camera: com.drishtisaathi.engine.config.CameraConfig = com.drishtisaathi.engine.config.CameraConfig(),
    val visionTests: com.drishtisaathi.engine.config.VisionTestConfig = com.drishtisaathi.engine.config.VisionTestConfig(),
    val tryOn: com.drishtisaathi.engine.config.TryOnConfig = com.drishtisaathi.engine.config.TryOnConfig(),
    // ---- Simulation-tuned rules (rules_update.md); a missing section throws ----
    val farPoint: com.drishtisaathi.engine.config.FarPointConfig = com.drishtisaathi.engine.config.FarPointConfig(),
    val pdMeasurement: com.drishtisaathi.engine.config.PdMeasurementConfig = com.drishtisaathi.engine.config.PdMeasurementConfig(),
    val readingAdd: com.drishtisaathi.engine.config.ReadingAddConfig = com.drishtisaathi.engine.config.ReadingAddConfig(),
) {
    fun material(id: String): MaterialSpec =
        materials.firstOrNull { it.id == id } ?: error("Material '$id' is not defined in rules.json")

    companion object {
        private val strictJson = Json { ignoreUnknownKeys = false }
        fun parse(json: String): RulesConfig = strictJson.decodeFromString(serializer(), json).also { it.validate() }
    }

    private fun validate() {
        require(materials.map { it.id }.toSet().size == materials.size) { "Duplicate material ids" }
        listOf(
            materialRules.defaultMaterial, materialRules.impactMaterial,
            materialRules.highIndexMaterial, materialRules.veryHighIndexMaterial,
        ).forEach { material(it) }
        require(addAgeTable.all { it.lowD <= it.highD && it.minAge <= it.maxAge }) { "Bad addAgeTable row" }
        with(frameScoring) {
            val sum = weightFit + weightThinness + weightStyle + weightBudget + weightColour + weightWeight + weightLook
            require(kotlin.math.abs(sum - 1.0) < 1e-6) { "frameScoring weights must sum to 1, got $sum" }
            require(thickestMm > thinnestMm)
            require(labelHighFrom > labelModerateFrom) { "labelHighFrom must exceed labelModerateFrom" }
            require(maxReasons >= 2)
            require(heaviestGrams > lightestGrams)
        }
    }
}

@Serializable
data class OpticsConfig(
    val letterArcmin: Double,
    val lcaBlueOffsetD: Double,
    val farPointAgreementMaxD: Double,
    val whiteFarPointMaxM: Double,
    val armsLengthM: Double,
    val workingDistanceDefaultM: Double,
    val workingDistanceFineWorkM: Double,
    val hofstetterMinIntercept: Double,
    val hofstetterMinSlopePerYear: Double,
    val powerStepD: Double,
    val addMinD: Double,
    val addMaxD: Double,
    val repeatSpreadHighMaxD: Double,
    val repeatSpreadMediumMaxD: Double,
    val measuredBandHalfWidthD: Double,
)

@Serializable
data class AgeConfig(
    val presbyopiaFrom: Int,
    val nearPointTestFrom: Int,
    val childReferralUnder: Int,
    val polycarbonateUnder: Int,
)

@Serializable
data class AddAgeRow(val minAge: Int, val maxAge: Int, val lowD: Double, val highD: Double)

@Serializable
data class OldNumbersConfig(
    val freshMaxYears: Int,
    val bandHalfWidthFreshD: Double,
    val bandHalfWidthStaleD: Double,
    val significantDistanceSeD: Double,
    val significantCylinderD: Double,
)

@Serializable
data class MaterialSpec(
    val id: String,
    val index: Double,
    val centreThicknessMinusMm: Double,
    val minEdgePlusMm: Double,
)

@Serializable
data class MaterialRules(
    val defaultMaterial: String,
    val impactMaterial: String,
    val impactOccupations: List<Occupation>,
    val highIndexFromAbsSeD: Double,
    val highIndexMaterial: String,
    val veryHighIndexFromAbsSeD: Double,
    val veryHighIndexMaterial: String,
)

@Serializable
data class LensTypeRules(
    val progressiveOccupations: List<Occupation>,
    val progressiveScreenHours: List<ScreenHours>,
    val lowBudgets: List<Budget>,
    /** Addition 4: reading this often favours a progressive (with screen/office use). */
    val progressiveReadingFrequencies: List<ReadingFrequency>,
    /** Addition 4: reading this rarely favours separate distance + reading pairs over a multifocal. */
    val separatePairsReadingFrequencies: List<ReadingFrequency>,
)

/** A declarative rule: if every non-null field of [when] matches the answers, add [coatings]. */
@Serializable
data class CoatingRule(val id: String, val `when`: Condition, val coatings: List<Coating>)

@Serializable
data class Condition(
    val screenHours: List<ScreenHours>? = null,
    val occupation: List<Occupation>? = null,
    val nightDriving: Boolean? = null,
    val outdoorWork: Boolean? = null,
    val minAge: Int? = null,
    val maxAge: Int? = null,
) {
    fun matches(a: InterviewAnswers): Boolean =
        (screenHours == null || a.screenHours in screenHours) &&
            (occupation == null || a.occupation in occupation) &&
            (nightDriving == null || a.nightDriving == nightDriving) &&
            (outdoorWork == null || a.outdoorWork == outdoorWork) &&
            (minAge == null || (a.age != null && a.age >= minAge)) &&
            (maxAge == null || (a.age != null && a.age <= maxAge))
}

@Serializable
data class ReferralConfig(
    val lowConfidenceCountForReferral: Int,
    /** Glasses older than this (years) plus headaches/tired eyes → routine full eye test. */
    val oldGlassesStrainOlderThanYears: Int,
)

@Serializable
data class FrameScoringConfig(
    val weightFit: Double,
    val weightThinness: Double,
    val weightStyle: Double,
    val weightBudget: Double,
    val endpieceMmEachSide: Double,
    val idealFrameToFaceWidthRatio: Double,
    val fitToleranceMm: Double,
    /** Addition 5: frame bridge (DBL) vs measured nose width. Share of the fit score given to the bridge. */
    val bridgeFitShare: Double = 0.3,
    val bridgeGoodWithinMm: Double = 2.0,
    val bridgeFitToleranceMm: Double = 5.0,
    val goodFitWithinMm: Double,
    val thinnestMm: Double,
    val thickestMm: Double,
    val styleMismatchScore: Double,
    val budgetMaxPriceInrLow: Int,
    val budgetMaxPriceInrMid: Int,
    /** Score falls to 0 at (cap × (1 + fraction)): ₹750 over a ₹1500 cap, ₹2000 over ₹4000. */
    val overBudgetFalloffFraction: Double,
    /** Frames priced more than this fraction above the budget cap are not shown at all (0.30 = 30 %). */
    val hideOverBudgetFraction: Double,
    val topN: Int,
    val labelHighFrom: Double,
    val labelModerateFrom: Double,
    /** Thinness component at or above this counts as a "thin lens" reason. */
    val thinPositiveFrom: Double,
    val maxReasons: Int,
    // ---- Addition 4 ----
    val weightColour: Double,
    val weightWeight: Double,
    val weightLook: Double,
    val colourMismatchScore: Double,
    val lookMismatchScore: Double,
    val lightestGrams: Double,
    val heaviestGrams: Double,
    /** Weight component at or above this counts as a "light frame" reason. */
    val lightPositiveFrom: Double,
    val progressiveMinLensHeightMm: Double,
    /** Subtracted from the total score when a progressive frame's lens is too short. */
    val shortLensHeightPenalty: Double,
)

@Serializable
data class PlausibilityConfig(val pdResidualSdMultiplier: Double)
