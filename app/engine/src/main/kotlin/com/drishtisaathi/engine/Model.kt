package com.drishtisaathi.engine

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------------------------
// Shared vocabulary. The engine returns codes only; all user-facing wording lives in the app,
// so the Section 6 wording rules are enforced in one place.
// ---------------------------------------------------------------------------------------------

enum class Confidence { HIGH, MEDIUM, LOW;
    fun min(other: Confidence): Confidence = if (other.ordinal > ordinal) other else this
}

/** Where a value came from. Printed next to every output so staff know how far to trust it. */
enum class Source { MEASURED, OLD_GLASSES_NUMBERS, AGE_TABLE, INTERVIEW, STAFF_ENTRY, POPULATION_AVERAGE, RULE }

/** Every engine output carries a confidence level and a source (Section 9). */
@Serializable
data class Estimate<T>(val value: T, val confidence: Confidence, val source: Source)

@Serializable enum class ScreenHours { H0_2, H2_4, H4_PLUS }

@Serializable
enum class Occupation {
    OFFICE_COMPUTER, SHOP_BUSINESS, FINE_HANDWORK, DRIVER, FIELD_OUTDOOR, HOMEMAKER, STUDENT, SPORT_ACTIVE, OTHER
}

@Serializable enum class Budget { LOW, MID, HIGH }

@Serializable enum class StylePref { CLASSIC, MODERN, BOLD, MINIMAL, ANY }

@Serializable enum class Coating { ANTI_REFLECTIVE, BLUE_FILTER_OPTIONAL, PHOTOCHROMIC_OR_TINT }

/** One eye of an existing spectacle record (the customer's old glasses numbers). */
@Serializable
data class EyeRx(val sph: Double, val cyl: Double = 0.0, val axis: Int? = null, val add: Double? = null) {
    val sphericalEquivalent: Double get() = sph + cyl / 2.0

    /** Power of the meridian with the largest magnitude: this one sets the lens thickness. */
    val strongestMeridian: Double get() = listOf(sph, sph + cyl).maxBy { kotlin.math.abs(it) }
}

@Serializable
data class KnownRx(val right: EyeRx? = null, val left: EyeRx? = null) {
    val eyes: List<EyeRx> get() = listOfNotNull(right, left)
}

/** M1 interview fields. Null means "not answered". */
@Serializable
data class InterviewAnswers(
    val age: Int? = null,
    val wearsGlasses: Boolean? = null,
    val glassesAgeYears: Int? = null,
    val knownRx: KnownRx? = null,
    val distanceBlur: Boolean? = null,
    val nearBlur: Boolean? = null,
    val screenHours: ScreenHours? = null,
    val nightDriving: Boolean? = null,
    val outdoorWork: Boolean? = null,
    val occupation: Occupation? = null,
    val headacheStrain: Boolean? = null,
    val diabetesOrBp: Boolean? = null,
    val redFlagFlashes: Boolean? = null,
    val redFlagOther: Boolean? = null,
    val budget: Budget? = null,
    val style: StylePref? = null,
)

/** Measurements typed in by staff (PD ruler, face width) until the camera flow exists. */
@Serializable
data class StaffMeasurements(val pdMm: Double? = null, val faceWidthMm: Double? = null)

/** Phase 3 on-screen test results for one eye. Unused in Phase 1 but the rules already handle them. */
@Serializable
data class MeasuredEye(
    val seD: Double?,
    val confidence: Confidence,
    val noMyopiaAtArmsLength: Boolean = false,
    val astigmatismFlag: Boolean = false,
    val astigmatismAxisDeg: Int? = null,
)

@Serializable
data class VisionTests(
    val right: MeasuredEye? = null,
    val left: MeasuredEye? = null,
    val measuredAddD: Estimate<Double>? = null,
    val nearAcuityBelowThreshold: Boolean = false,
    val amslerAbnormal: Boolean = false,
    val lowConfidenceCount: Int = 0,
)
