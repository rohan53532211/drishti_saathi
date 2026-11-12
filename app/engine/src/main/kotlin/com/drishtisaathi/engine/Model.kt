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
enum class Source {
    MEASURED, OLD_GLASSES_NUMBERS, AGE_TABLE, INTERVIEW, STAFF_ENTRY, POPULATION_AVERAGE, RULE,
    /** Power path YES: the numbers the customer remembered or brought on the slip. */
    KNOWN_POWER,
    /** Power path NOT SURE / NO: the phone's quick far-point / near-point estimate (approximate). */
    PHONE_ESTIMATE,
}

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

// Addition 4 preferences
@Serializable enum class ColourPref { DARK, BROWN_TORTOISE, METALLIC, COLOURFUL, ANY }
@Serializable enum class LookPref { PROFESSIONAL, CASUAL, BOTH }
@Serializable enum class ReadingFrequency { RARELY, SOMETIMES, DAILY_LONG }

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
    // ---- Addition 4 ----
    val colourPreference: ColourPref? = null,
    val lightweightPreference: Boolean? = null,
    val lookPreference: LookPref? = null,
    val firstTimeWearer: Boolean? = null,
    val readingFrequency: ReadingFrequency? = null,
    // ---- Adaptive anamnesis (12 Nov) ----
    /** Early question; also fills [distanceBlur] / [nearBlur] so every existing rule keeps working. */
    val visionProblem: VisionProblem? = null,
    val distanceSituation: DistanceSituation? = null,
    val distanceSince: ProblemSince? = null,
    val nearTask: NearTask? = null,
    /** Usual reading distance: sets the working distance for the near add. */
    val nearDistance: NearDistance? = null,
    /** Existing wearers: happy with the current glasses? */
    val happyWithGlasses: Boolean? = null,
    val redFlagOnset: RedFlagOnset? = null,
    val redFlagEye: AffectedEye? = null,
    /** "Do you remember your glasses power, or have the slip?" */
    val powerKnown: PowerKnown? = null,
) {
    /** Flashes/floaters/curtain or sudden loss/pain/redness/halos/double: urgent, stops the interview and the power path. */
    val urgentRedFlag: Boolean get() = redFlagFlashes == true || redFlagOther == true
}

@Serializable enum class VisionProblem { DISTANCE, NEAR, BOTH, NONE;
    val distance: Boolean get() = this == DISTANCE || this == BOTH
    val near: Boolean get() = this == NEAR || this == BOTH
}
@Serializable enum class DistanceSituation { DRIVING, TV, SIGNBOARDS, CLASSROOM_BOARD, OTHER }
@Serializable enum class ProblemSince { UNDER_6_MONTHS, MONTHS_6_TO_24, OVER_2_YEARS }
@Serializable enum class NearTask { PHONE, NEWSPAPER, SEWING, ACCOUNTS, OTHER }
@Serializable enum class NearDistance { VERY_CLOSE, NORMAL, ARM_LENGTH }
@Serializable enum class RedFlagOnset { TODAY, THIS_WEEK, LONGER }
@Serializable enum class AffectedEye { RIGHT, LEFT, BOTH }
@Serializable enum class PowerKnown { YES, NOT_SURE, NO }

/** Target used for the quick far-point estimate. */
@Serializable enum class QuickTarget { WHITE, BLUE }

/** One eye of the quick far-point estimate. */
@Serializable
data class QuickEye(
    /** Approximate SE (D), rounded to 0.25; null when no blur was found within reach. */
    val seD: Double?,
    val blurAtCm: Double?,
    val target: QuickTarget,
    /** Still clear at arm's length: no myopia found, check with the trial lens. */
    val noMyopiaAtReach: Boolean = false,
)

/**
 * The power path's quick estimate, stored with the session. [primary] = it is the session's power source
 * (NOT SURE / NO); false = an optional comparison next to the customer's known power (shown to staff only).
 */
@Serializable
data class QuickEstimate(
    val right: QuickEye? = null,
    val left: QuickEye? = null,
    /** Reading add from the quick near point (age ≥ quickEstimate.nearAddFromAge only). */
    val addD: Double? = null,
    val nearPointCm: Double? = null,
    /** Near-only problem under quickEstimate.nearAddFromAge: no plus power is estimated, hand test instead. */
    val possibleLongSight: Boolean = false,
    val primary: Boolean = true,
) {
    val eyes: List<Pair<Eye, QuickEye>> get() = listOfNotNull(right?.let { Eye.RIGHT to it }, left?.let { Eye.LEFT to it })
}

/** Measurements typed in by staff (PD ruler, face width) until the camera flow exists. */
@Serializable
data class StaffMeasurements(val pdMm: Double? = null, val faceWidthMm: Double? = null)

/** M2 camera capture, aggregated over the accepted frames (median, outliers dropped). Landmarks only, no image. */
@Serializable
data class MeasuredFace(
    val pdMm: Estimate<Double>,
    val faceWidthMm: Estimate<Double>,
    /** Addition 5: nose width where the frame bridge rests. */
    val bridgeWidthMm: Estimate<Double>? = null,
    /** Addition 6: rule-based, used for style suggestions only. */
    val faceShape: Estimate<com.drishtisaathi.engine.face.FaceShape>? = null,
    val suggestedFrameShapes: List<FrameShape> = emptyList(),
    val framesUsed: Int = 0,
    /** The plausibility gate flagged the numbers: kept, but at LOW confidence. */
    val lowPlausibility: Boolean = false,
    /** How the mm scale was obtained; null for sessions stored before this field (treated as not card-calibrated). */
    val scaleSource: com.drishtisaathi.engine.face.ScaleSource? = null,
) {
    /** Scale from the forehead card or the card-measured iris (rules_update.md rule 6), not the 11.7 mm prior. */
    val cardCalibrated: Boolean
        get() = scaleSource == com.drishtisaathi.engine.face.ScaleSource.CARD ||
            scaleSource == com.drishtisaathi.engine.face.ScaleSource.IRIS_PERSONAL
}

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
