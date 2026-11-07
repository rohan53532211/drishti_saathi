package com.drishtisaathi.engine.config

import com.drishtisaathi.engine.Confidence
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Track C (vision tests): staircase, repeats, test distances and target sizes. Mirrors the "visionTests" section of
 * assets/rules.json. Defaults exist only so RulesConfig can build a no-argument instance when the section is absent;
 * a unit test checks that rules.json lists every key, so the defaults are never silently used. Unknown keys fail
 * parsing (strict JSON). Optics constants (letter arcmin, LCA offset, arm's length, repeat-spread confidence in
 * dioptres) stay in the "optics" section; this section holds only how the tests are run.
 *
 * Distances are eye-to-screen in millimetres. logMAR 0.0 = the 5-arcmin (20/20) letter, +0.1 per step is larger.
 */
@Serializable
data class VisionTestConfig(
    @SerialName("_comment") val comment: String? = null,

    // ---- General ----
    /** Each test is run this many times; the result is the median of the repeats (brief M3: twice). */
    val repeats: Int = 2,
    /** A test whose combined result is LOW confidence may be repeated this many extra times before moving on. */
    val maxRetriesWhenLow: Int = 1,
    /** Cap every result at the distance reading's own confidence (iris distance with an assumed focal length = LOW). */
    val capByDistanceConfidence: Boolean = true,
    /** Best confidence for size-dependent results (acuity) when the screen scale comes from DisplayMetrics, not the card. */
    val fallbackScreenScaleCap: Confidence = Confidence.MEDIUM,

    // ---- 1. Near acuity: tumbling E, 4-alternative staircase ----
    val nearAcuityPerEye: Boolean = true,
    /** Allowed eye-to-screen range; letters are resized from the live distance so the angle stays fixed. */
    val nearAcuityMinMm: Double = 300.0,
    val nearAcuityMaxMm: Double = 500.0,
    val staircaseStartLogMar: Double = 0.7,
    val staircaseMinLogMar: Double = -0.2,
    val staircaseMaxLogMar: Double = 1.0,
    /** Step until the first reversal (1-down/1-up then), and after it (n-down/1-up). */
    val staircaseInitialStepLogMar: Double = 0.2,
    val staircaseStepLogMar: Double = 0.1,
    /** Correct answers in a row needed to make the letter smaller after the first reversal (2 → ~71 % point). */
    val staircaseCorrectToStepDown: Int = 2,
    /** Stop after this many reversals, or after maxTrials. */
    val staircaseReversals: Int = 6,
    /** Threshold = mean logMAR of the last this-many reversals. */
    val staircaseThresholdLastReversals: Int = 4,
    val staircaseMaxTrials: Int = 40,
    /** Wrong this many times in a row at the largest size (or right at the smallest): stop at the boundary. */
    val staircaseBoundaryRuns: Int = 3,
    /** Near acuity worse than this (logMAR) → VisionTests.nearAcuityBelowThreshold (routine referral). */
    val nearAcuityReferAboveLogMar: Double = 0.7,
    /** Repeat spread (logMAR) at or below → HIGH, MEDIUM, else LOW. */
    val acuityRepeatHighMaxLogMar: Double = 0.1,
    val acuityRepeatMediumMaxLogMar: Double = 0.2,

    // ---- 2. Far point, blue then white ----
    /** Letter size of the far-point target, kept at this angle at every distance. */
    val farPointLetterLogMar: Double = 0.2,
    val farPointLetterCount: Int = 3,
    /** The phone must have been this close before a blur tap counts (start close, move away). */
    val farPointStartMaxMm: Double = 150.0,
    /** A blur tap closer than this is rejected (too close to be a far point). */
    val farPointMinMm: Double = 100.0,
    // "Still clear" is now governed by farPoint.selfHeldReachM (switch to staff-held) and farPoint.staffHeldMaxM.
    /** Background colours as #RRGGBB; letters are black. Blue must be pure blue for the LCA offset to hold. */
    val farPointBlueRgb: String = "#0000FF",
    val farPointWhiteRgb: String = "#FFFFFF",

    // ---- 3. Fogged clock dial ----
    /** Hold the dial this far beyond the measured far point (brief: ~10 cm). */
    val clockDialBeyondFarPointMm: Double = 100.0,
    /** Allowed ± range around the dial target distance. */
    val clockDialToleranceMm: Double = 60.0,
    /** Target distance when no far point was found (no myopia) or the far point was skipped. */
    val clockDialNoFarPointMm: Double = 600.0,
    /** Spoke line width, locked to the live distance (arcmin). */
    val clockDialLineArcmin: Double = 2.0,
    /** Two repeats naming lines within this angle count as the same axis. */
    val clockDialAxisToleranceDeg: Int = 30,

    // ---- 4. Near point push-up ----
    /** Fixed physical letter height of the push-up target (≈ N5–N6 print). */
    val nearPointLetterHeightMm: Double = 1.0,
    /** Must have started at least this far (letters clear) before moving in. */
    val nearPointStartMinMm: Double = 350.0,
    /** Valid blur-tap range. */
    val nearPointMinMm: Double = 70.0,
    val nearPointMaxMm: Double = 700.0,

    // ---- 5. Amsler grid ----
    val amslerMinMm: Double = 250.0,
    val amslerMaxMm: Double = 400.0,
    /** One grid square subtends this angle at the live distance (classic Amsler: 1°). */
    val amslerSquareDeg: Double = 1.0,
    /** "Yes, wavy or missing" in at least this many of the repeats → amslerAbnormal (urgent). 1 = safest. */
    val amslerYesCountForAbnormal: Int = 1,
) {
    init {
        require(repeats >= 1) { "visionTests.repeats must be ≥ 1" }
        require(maxRetriesWhenLow >= 0)
        require(nearAcuityMinMm < nearAcuityMaxMm)
        require(staircaseMinLogMar < staircaseMaxLogMar)
        require(staircaseStartLogMar in staircaseMinLogMar..staircaseMaxLogMar)
        require(staircaseInitialStepLogMar > 0 && staircaseStepLogMar > 0)
        require(staircaseCorrectToStepDown >= 1 && staircaseReversals >= 1 && staircaseMaxTrials >= 1 && staircaseBoundaryRuns >= 1)
        require(staircaseThresholdLastReversals in 1..staircaseReversals)
        require(acuityRepeatHighMaxLogMar <= acuityRepeatMediumMaxLogMar)
        require(farPointLetterCount >= 1 && farPointMinMm < farPointStartMaxMm)
        require(RGB.matches(farPointBlueRgb) && RGB.matches(farPointWhiteRgb)) { "colours must be #RRGGBB" }
        require(clockDialToleranceMm > 0 && clockDialLineArcmin > 0 && clockDialAxisToleranceDeg in 0..90)
        require(nearPointLetterHeightMm > 0 && nearPointMinMm < nearPointStartMinMm && nearPointStartMinMm < nearPointMaxMm)
        require(amslerMinMm < amslerMaxMm && amslerSquareDeg > 0)
        require(amslerYesCountForAbnormal in 1..repeats)
    }
}

private val RGB = Regex("#[0-9A-Fa-f]{6}")
