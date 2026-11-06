package com.drishtisaathi.engine.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Track B: quality gates, frame counts, card detection, spoken-instruction pacing.
 * Mirrors the "camera" section of assets/rules.json. Every field has a default only because
 * RulesConfig needs a no-argument instance when the section is absent; `CameraConfigTest`
 * checks that the JSON section lists every key, so the defaults are never silently used.
 * Unknown keys still fail parsing (strict JSON).
 */
@Serializable
data class CameraConfig(
    @SerialName("_comment") val comment: String? = null,
    // ---- Live analysis ----
    /** ImageAnalysis target size (landscape sensor orientation; CameraX picks the closest). */
    val analysisWidthPx: Int = 1280,
    val analysisHeightPx: Int = 720,
    /** Forehead-card photo target size. */
    val photoWidthPx: Int = 1920,
    val photoHeightPx: Int = 1080,
    val minFaceDetectionConfidence: Double = 0.5,
    val minFacePresenceConfidence: Double = 0.5,
    val minTrackingConfidence: Double = 0.5,
    /** Used only when Camera2 does not report focal length / sensor size. */
    val fallbackHorizontalFovDeg: Double = 62.0,

    // ---- Quality gates (M2) ----
    val maxAbsYawDeg: Double = 5.0,
    val maxAbsRollDeg: Double = 5.0,
    /** Eye aspect ratio (lid gap / eye width); open eyes are ~0.25–0.35. */
    val minEyeAspectRatio: Double = 0.18,
    /** Variance of the 4-neighbour Laplacian of the face-region luminance (0–255 scale). */
    val minLaplacianVariance: Double = 30.0,
    val minMeanBrightness: Double = 70.0,
    val maxMeanBrightness: Double = 210.0,
    /** Face-box centre may be at most this fraction of the image away from the image centre. */
    val maxCentreOffsetFraction: Double = 0.15,
    /** Face-box width as a fraction of image width: smaller = too far, larger = too close. */
    val minFaceWidthFraction: Double = 0.30,
    val maxFaceWidthFraction: Double = 0.80,
    /** Frames that must pass every gate before capture finishes. */
    val captureFrameCount: Int = 30,

    // ---- Spoken instructions ----
    /** An instruction must stay the current one this long before it is spoken (avoids flicker). */
    val speakStableMs: Long = 700,
    /** Minimum gap between any two spoken instructions. */
    val speakMinGapMs: Long = 2500,
    /** The same instruction is repeated at most this often. */
    val speakRepeatMs: Long = 7000,

    // ---- Distance (provisional until Track A's DistanceEstimator is merged) ----
    val provisionalIrisDiameterMm: Double = 11.7,
    /** Median over this many recent readings. */
    val distanceSmoothingFrames: Int = 5,

    // ---- Bank card (ID-1) ----
    val cardWidthMm: Double = 85.60,
    val cardHeightMm: Double = 53.98,
    /** Allowed relative deviation of the detected quad's aspect ratio from 85.60/53.98. */
    val cardAspectTolerance: Double = 0.10,
    /** Detected card width as a fraction of photo width. */
    val cardMinWidthFraction: Double = 0.15,
    val cardMaxWidthFraction: Double = 0.95,
    /** Opposite sides may differ by at most this fraction (rejects strong perspective). */
    val cardMaxSideMismatch: Double = 0.12,
    val cardBlurKernelPx: Int = 5,
    val cardCannyLow: Double = 40.0,
    val cardCannyHigh: Double = 120.0,
    /** approxPolyDP epsilon as a fraction of the contour perimeter. */
    val cardApproxEpsilonFraction: Double = 0.02,
    /** Plausible human iris diameters; a forehead-card result outside is rejected. */
    val irisMinMm: Double = 10.0,
    val irisMaxMm: Double = 13.5,
    /** Plausible screen scales (mm per px) for the drag-to-match calibration. */
    val screenMinMmPerPx: Double = 0.02,
    val screenMaxMmPerPx: Double = 0.25,
) {
    val cardAspect: Double get() = cardWidthMm / cardHeightMm
}
