package com.drishtisaathi.camera

import kotlin.math.max
import kotlin.math.tan

/**
 * Camera focal length in pixels of an output image, from Camera2 characteristics. Pure, unit-tested.
 *
 * Camera2 reports the lens focal length in mm (LENS_INFO_AVAILABLE_FOCAL_LENGTHS) and the sensor's physical
 * size in mm (SENSOR_INFO_PHYSICAL_SIZE), both in the sensor's native (landscape) orientation, plus the pixel
 * array and active array sizes. An output stream is the active array cropped to the stream's aspect ratio
 * (keeping the widest field of view) and scaled, so:
 *
 *   f_sensorPx = f_mm × pixelArrayWidth / physicalWidthMm
 *   scale      = max(imageW / activeW, imageH / activeH)      (image size in sensor orientation)
 *   f_px       = f_sensorPx × scale
 *
 * Without pixel/active array sizes it falls back to f_mm × imageW / physicalWidthMm (correct whenever the
 * stream uses the full sensor width, e.g. 16:9 from a 4:3 sensor).
 */
object FocalLength {

    /**
     * @param imageWidthPx, imageHeightPx size of the image as the app sees it, i.e. AFTER rotating it upright.
     * @param rotationDegrees rotation that was applied to make it upright (0, 90, 180, 270).
     */
    fun px(
        focalLengthMm: Double,
        sensorWidthMm: Double,
        sensorHeightMm: Double,
        imageWidthPx: Int,
        imageHeightPx: Int,
        rotationDegrees: Int,
        pixelArrayWidth: Int? = null,
        pixelArrayHeight: Int? = null,
        activeArrayWidth: Int? = null,
        activeArrayHeight: Int? = null,
    ): Double {
        require(focalLengthMm > 0 && sensorWidthMm > 0 && sensorHeightMm > 0) { "Bad lens/sensor size" }
        require(imageWidthPx > 0 && imageHeightPx > 0) { "Bad image size" }
        // Back to sensor orientation: a 90/270° rotation swapped width and height.
        val swapped = ((rotationDegrees % 360) + 360) % 360 % 180 == 90
        val sensorImgW = if (swapped) imageHeightPx else imageWidthPx
        val sensorImgH = if (swapped) imageWidthPx else imageHeightPx

        if (pixelArrayWidth == null || pixelArrayWidth <= 0) {
            return focalLengthMm * sensorImgW / sensorWidthMm
        }
        val activeW = (activeArrayWidth ?: pixelArrayWidth).coerceAtLeast(1)
        val activeH = (activeArrayHeight ?: pixelArrayHeight ?: activeW).coerceAtLeast(1)
        val fSensorPx = focalLengthMm * pixelArrayWidth / sensorWidthMm
        val scale = max(sensorImgW.toDouble() / activeW, sensorImgH.toDouble() / activeH)
        return fSensorPx * scale
    }

    /** Last resort when Camera2 reports nothing: assume a typical selfie-camera horizontal field of view. */
    fun fromFov(imageWidthPx: Int, imageHeightPx: Int, horizontalFovDeg: Double): Double {
        // The "horizontal" FOV of a selfie camera refers to its long (landscape) side.
        val longSide = max(imageWidthPx, imageHeightPx)
        return longSide / (2.0 * tan(Math.toRadians(horizontalFovDeg) / 2.0))
    }
}
