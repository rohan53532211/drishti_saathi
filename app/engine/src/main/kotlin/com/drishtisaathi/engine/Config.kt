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
            val sum = weightFit + weightThinness + weightStyle + weightBudget
            require(kotlin.math.abs(sum - 1.0) < 1e-6) { "frameScoring weights must sum to 1, got $sum" }
            require(thickestMm > thinnestMm)
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
data class ReferralConfig(val lowConfidenceCountForReferral: Int)

@Serializable
data class FrameScoringConfig(
    val weightFit: Double,
    val weightThinness: Double,
    val weightStyle: Double,
    val weightBudget: Double,
    val endpieceMmEachSide: Double,
    val idealFrameToFaceWidthRatio: Double,
    val fitToleranceMm: Double,
    val goodFitWithinMm: Double,
    val thinnestMm: Double,
    val thickestMm: Double,
    val styleMismatchScore: Double,
    val budgetMaxPriceInrLow: Int,
    val budgetMaxPriceInrMid: Int,
    /** Score falls to 0 at (cap × (1 + fraction)): ₹750 over a ₹1500 cap, ₹2000 over ₹4000. */
    val overBudgetFalloffFraction: Double,
    val topN: Int,
)

@Serializable
data class PlausibilityConfig(val pdResidualSdMultiplier: Double)
