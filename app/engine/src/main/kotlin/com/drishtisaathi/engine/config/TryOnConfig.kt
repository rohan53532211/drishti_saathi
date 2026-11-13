package com.drishtisaathi.engine.config

import kotlinx.serialization.Serializable

/**
 * Track E: try-on overlay placement constants, from the "tryOn" section of assets/rules.json.
 * Parsing is strict (unknown keys throw); defaults exist only so a rules.json without the section still loads.
 */
@Serializable
data class TryOnConfig(
    /** The frame's lens centres sit this far above the pupils (mm). Boxed lens centres are usually a little above the pupil. */
    val lensCentreAbovePupilMm: Double = 2.0,
    /** Frame front plane in front of the pupils (mm); shifts the frame sideways a little on head turns. */
    val frontOffsetMm: Double = 12.0,
    /** PD used to convert pixels to mm when neither face width nor PD is known. */
    val defaultPdMm: Double = 63.0,
    /** Landmarks whose distance is the face width the measured face width refers to (cheek/zygion points). */
    val faceWidthLandmarkLeft: Int = 234,
    val faceWidthLandmarkRight: Int = 454,
    /** Use the Face Landmarker transformation matrix for yaw/pitch (roll always comes from the eye line). */
    val useTransformMatrix: Boolean = true,
    /** Yaw/pitch beyond these are clamped (deg); the face tracker is unreliable past them anyway. */
    val maxYawDeg: Double = 45.0,
    val maxPitchDeg: Double = 30.0,
    /** Apply perspective (near side of the frame drawn larger on head turns); false = pure affine foreshortening. */
    val perspective: Boolean = true,
    /** Focal length as a fraction of the longer image side, when the camera's focal length is unknown. */
    val defaultFocalLengthFraction: Double = 0.7,
    /** Overall size fudge (1.0 = true scale). */
    val widthScale: Double = 1.0,
    /** Exponential smoothing of the drawn corners between camera frames: weight of the newest frame (1 = none). */
    val smoothingAlpha: Double = 0.5,
    /** If the corners jump by more than this fraction of the frame width, smoothing restarts (new face or big move). */
    val smoothingResetFraction: Double = 0.35,
    /** 3D try-on (canonical face space): pupils at this fraction of the lens height from the lens bottom. */
    val pupilHeightRatio: Double = 0.55,
    /** 3D try-on: moves the frame up (+) / down (−), mm. */
    val verticalOffsetMm: Double = 0.0,
    /** 3D try-on: the frame's back surface at the bridge sits this far in front of the nasion (landmark 168), mm. */
    val bridgeForwardMm: Double = 11.0,
    /** 3D try-on: temples reach the ear this far behind the nasion, mm. */
    val earBehindNasionMm: Double = 95.0,
    /** 3D try-on: the ear rest point is this far below the pupils, mm. */
    val earBelowPupilMm: Double = 5.0,
) {
    init {
        require(frontOffsetMm in 0.0..40.0) { "tryOn.frontOffsetMm out of range" }
        require(defaultPdMm in 45.0..80.0) { "tryOn.defaultPdMm out of range" }
        require(faceWidthLandmarkLeft in 0 until 478 && faceWidthLandmarkRight in 0 until 478 &&
            faceWidthLandmarkLeft != faceWidthLandmarkRight) { "tryOn face width landmarks must be two different mesh indices" }
        require(maxYawDeg in 0.0..80.0 && maxPitchDeg in 0.0..80.0) { "tryOn max angles out of range" }
        require(defaultFocalLengthFraction > 0.2 && defaultFocalLengthFraction < 3.0) { "tryOn.defaultFocalLengthFraction out of range" }
        require(widthScale in 0.5..2.0) { "tryOn.widthScale out of range" }
        require(smoothingAlpha > 0.0 && smoothingAlpha <= 1.0) { "tryOn.smoothingAlpha must be in (0, 1]" }
        require(smoothingResetFraction > 0.0) { "tryOn.smoothingResetFraction must be positive" }
        require(pupilHeightRatio in 0.3..0.8) { "tryOn.pupilHeightRatio out of range" }
        require(verticalOffsetMm in -15.0..15.0) { "tryOn.verticalOffsetMm out of range" }
        require(bridgeForwardMm in 0.0..25.0) { "tryOn.bridgeForwardMm out of range" }
        require(earBehindNasionMm in 70.0..120.0) { "tryOn.earBehindNasionMm out of range" }
        require(earBelowPupilMm in -10.0..25.0) { "tryOn.earBelowPupilMm out of range" }
    }
}
