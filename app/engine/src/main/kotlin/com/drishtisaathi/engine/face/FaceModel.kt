package com.drishtisaathi.engine.face

/**
 * Shared contract between camera (Track B), face geometry (Track A) and try-on (Track E).
 * Pure Kotlin so the engine can unit-test geometry on synthetic landmarks.
 */

/** One MediaPipe Face Landmarker point. x, y normalised to [0,1] of the image; z relative depth (same scale as x). */
data class Landmark(val x: Float, val y: Float, val z: Float = 0f)

/**
 * One processed camera frame. Images are never stored: only this landmark data leaves the analyser,
 * and only in memory (Section 6 privacy).
 */
data class FaceFrame(
    /** 478 points (468 face mesh + 10 iris), MediaPipe ordering. */
    val landmarks: List<Landmark>,
    val imageWidthPx: Int,
    val imageHeightPx: Int,
    /** MediaPipe facial transformation matrix, 4×4 column-major, when available. */
    val transformMatrix: FloatArray? = null,
    /** Camera focal length in pixels for this image size, when known from CameraCharacteristics. */
    val focalLengthPx: Double? = null,
    /** True when the image is mirrored (front camera preview) so left/right are swapped on screen. */
    val mirrored: Boolean = true,
    val timestampMs: Long = 0L,
) {
    fun px(i: Int): Pair<Double, Double> =
        landmarks[i].x.toDouble() * imageWidthPx to landmarks[i].y.toDouble() * imageHeightPx

    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)

    companion object {
        const val LANDMARK_COUNT = 478
    }
}
