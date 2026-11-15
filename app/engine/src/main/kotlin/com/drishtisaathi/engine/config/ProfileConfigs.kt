package com.drishtisaathi.engine.config

import com.drishtisaathi.engine.Budget
import com.drishtisaathi.engine.ScreenHours
import com.drishtisaathi.engine.profile.DriveFrequency
import com.drishtisaathi.engine.profile.Freq
import com.drishtisaathi.engine.profile.PlateType
import com.drishtisaathi.engine.profile.RedFlag
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Vision-profile branch: safety rules, lens-assisted mode, screening tests, profile rings, lens engine.
// Every section is required in rules.json (a missing one throws) and nothing here has a default value.

/** Phase A safety rules (see engine/profile/Safety.kt). */
@Serializable
data class SafetyConfig(
    @SerialName("_comment") val comment: String? = null,
    /** Red flags that put the customer on the short path (power of each eye + grid, then urgent referral). */
    val emergencySigns: List<RedFlag>,
    /** A red-flag "yes" without the follow-up "which signs" counts as an emergency (it cannot be ruled out). */
    val unspecifiedIsEmergency: Boolean,
    val childUnderAge: Int,
    /** Any eye at or below this SE (D) counts as myopia for the child rule. */
    val childMyopiaFromSeD: Double,
    val shortPathSteps: List<String>,
    val shortPathMinutes: Int,
) {
    init {
        require(childUnderAge in 1..25 && childMyopiaFromSeD < 0) { "safety: bad child rule" }
        require(emergencySigns.isNotEmpty()) { "safety.emergencySigns is empty" }
    }
    companion object { operator fun invoke(): SafetyConfig = missingSection("safety") }
}

/** Phase B over-refraction through a known minus lens (see engine/profile/LensAssisted.kt). */
@Serializable
data class LensAssistedConfig(
    @SerialName("_comment") val comment: String? = null,
    /** First-pass estimate at or beyond this (D, minus) switches to lens-assisted mode. */
    val triggerSeD: Double,
    /** The suggested lens is about this much weaker than the estimate (D). */
    val suggestOffsetD: Double,
    /** The suggestion is rounded towards the weaker lens to this step (D). */
    val suggestStepD: Double,
    /** Step of the lens power staff type in (D). */
    val lensStepD: Double,
    /** Lenses at or beyond this power (D, absolute) get the vertex correction. */
    val vertexFromAbsD: Double,
    /** Hold distance in front of the eye (m): Fe = F / (1 − d·F). */
    val vertexDistanceM: Double,
    /** No blur point within this distance (m) through the lens: the lens is too strong. */
    val maxReachM: Double,
    /** An estimate stronger than this (D) gets the high-myopia note. */
    val highMyopiaBeyondSeD: Double,
    val maxLensAbsD: Double,
    val rangeHalfWidthD: Double,
    /** Residual through the lens at or beyond this (D, minus): the lens was too weak, ask for a stronger one. */
    val repeatIfResidualFromSeD: Double,
    /** How many times the test is repeated with a stronger lens. */
    val maxStrongerRepeats: Int,
) {
    init {
        require(triggerSeD < 0 && highMyopiaBeyondSeD < triggerSeD) { "lensAssisted: thresholds must be minus, high myopia beyond the trigger" }
        require(repeatIfResidualFromSeD < 0 && maxStrongerRepeats >= 0) { "lensAssisted: bad repeat rule" }
        require(suggestOffsetD > 0 && suggestStepD > 0 && lensStepD > 0 && vertexDistanceM in 0.0..0.03 && maxReachM > 0.2) { "lensAssisted: bad values" }
    }
    companion object { operator fun invoke(): LensAssistedConfig = missingSection("lensAssisted") }
}

@Serializable
data class AmslerConfig(
    val distanceMm: Double, val minMm: Double, val maxMm: Double,
    val squareMm: Double, val squares: Int,
    /** Fewer whole columns than this on the screen: the grid is not shown (screen too small). */
    val minColumns: Int,
)

@Serializable
data class ContrastConfig(
    val letterLogMar: Double, val minMm: Double, val maxMm: Double,
    /** Contrast steps in percent, highest first. */
    val stepsPercent: List<Double>,
    val trialsPerStep: Int, val passCorrect: Int,
    /** Letters offered as answers per trial (one is right). */
    val choices: Int,
    /** Lowest step passed at or below this (percent) = Normal; anything else = Reduced. */
    val normalMaxPercent: Double,
    val eyeHealthNoteFromAge: Int,
    /** A power at or beyond this (D, absolute) in the tested eye: the correction must be worn for the test. */
    val wearCorrectionFromAbsD: Double,
) {
    init {
        require(stepsPercent.isNotEmpty() && stepsPercent.zipWithNext().all { (a, b) -> a > b }) { "contrast.stepsPercent must fall" }
        require(passCorrect in 1..trialsPerStep && choices in 2..6) { "contrast: bad trial settings" }
        require(stepsPercent.first() == 100.0) { "contrast: the first step must be 100 % (it is the validity check)" }
        require(wearCorrectionFromAbsD > 0) { "contrast.wearCorrectionFromAbsD" }
    }
}

@Serializable data class PlatePalette(val figure: List<String>, val background: List<String>)

@Serializable
data class ColourConfig(
    val controlPlates: Int, val redGreenPlates: Int, val blueYellowPlates: Int,
    /** Wrong red-green plates from which "possible red-green deficiency" is reported. */
    val redGreenFailFrom: Int,
    val blueYellowFailFrom: Int,
    val dots: Int,
    /** Dot radii as a fraction of the plate diameter. */
    val minDotRadius: Double, val maxDotRadius: Double,
    val plateMm: Double,
    val palettes: Map<PlateType, PlatePalette>,
) {
    init {
        require(controlPlates >= 1 && redGreenPlates >= 1 && blueYellowPlates >= 1) { "colour: plate counts" }
        require(palettes.keys == PlateType.entries.toSet()) { "colour.palettes needs every plate type" }
        require(palettes.values.all { it.figure.isNotEmpty() && it.background.isNotEmpty() }) { "colour: empty palette" }
    }
}

/** Phase C screening tests (see engine/profile/Screening.kt). */
@Serializable
data class ScreeningConfig(
    @SerialName("_comment") val comment: String? = null,
    val amsler: AmslerConfig, val contrast: ContrastConfig, val colour: ColourConfig,
) {
    companion object { operator fun invoke(): ScreeningConfig = missingSection("screening") }
}

@Serializable data class WorkRingConfig(val nearWeights: Map<String, Double>, val defaultScore: Int, val nearFocusedFrom: Int, val distanceFocusedBelow: Int)
@Serializable data class DigitalRingConfig(val connectedFromHours: Double, val nativeAboveHours: Double, val fullScaleHours: Double)
@Serializable data class MobilityRingConfig(val scores: Map<DriveFrequency, Int>, val sensitiveFrom: Freq)
@Serializable
data class LeisureRingConfig(
    /** Label thresholds on the ring's own score (0-100), so the label always matches the number shown. */
    val mediumFromScore: Int, val highFromScore: Int, val fullScaleHours: Double, val activityBonus: Map<String, Int>,
) {
    init { require(mediumFromScore in 1 until highFromScore && highFromScore <= 100) { "visionProfile.leisure: bad label thresholds" } }
}

/** Phase E profile rings (see engine/profile/VisionProfile.kt). */
@Serializable
data class VisionProfileConfig(
    @SerialName("_comment") val comment: String? = null,
    val work: WorkRingConfig, val digital: DigitalRingConfig, val mobility: MobilityRingConfig, val leisure: LeisureRingConfig,
) {
    init { require(mobility.scores.keys == DriveFrequency.entries.toSet()) { "visionProfile.mobility.scores needs every frequency" } }
    companion object { operator fun invoke(): VisionProfileConfig = missingSection("visionProfile") }
}

/** Phase E lens engine: the numbers of the lens_catalog.json trigger rules (see engine/profile/LensEngine.kt). */
@Serializable
data class LensEngineConfig(
    @SerialName("_comment") val comment: String? = null,
    val singleVisionUnderAge: Int,
    val antiFatigueFromAge: Int,
    val antiFatigueToAge: Int,
    val progressiveFromAge: Int,
    val nearAddFromD: Double,
    val screenHoursFrom: Double,
    /** "at least sometimes". */
    val symptomFrom: Freq,
    val officeDeskHoursFrom: Double,
    val officeFromAge: Int,
    val photoFusionOutdoorHoursFrom: Double,
    val childUnderAge: Int,
    /** "drives daily / weekly". */
    val driveFrequencies: List<DriveFrequency>,
    /** DriveSafe is the main design (not a second pair) for these. */
    val driveSafeMainFrequencies: List<DriveFrequency>,
    val digitalLifestyleFromHours: Double,
    val veryBrightOutdoorHoursFrom: Double,
    val maxSecondPairs: Int,
    val defaultBudget: Budget,
    /** Used when the survey was not answered: the interview's screen-hours answer in hours. */
    val fallbackScreenHours: Map<ScreenHours, Double>,
    val fallbackOutdoorWorkHours: Double,
    val fallbackOfficeDeskHours: Double,
    /** Display names of catalogue entries that have none (designs, sun lenses). */
    val names: Map<String, String>,
) {
    companion object { operator fun invoke(): LensEngineConfig = missingSection("lensEngine") }
}
