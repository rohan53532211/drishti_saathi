package com.drishtisaathi.camera

import com.drishtisaathi.engine.face.FaceFrame
import com.drishtisaathi.engine.face.Landmark
import kotlin.math.max
import kotlin.math.min

/** A point in preview-view pixels (origin top-left of the view). */
data class ViewPoint(val x: Float, val y: Float)

enum class PreviewScale {
    /** PreviewView.ScaleType.FILL_CENTER (default): image scaled to cover the view, overflow cropped equally. */
    FILL_CENTER,
    /** PreviewView.ScaleType.FIT_CENTER: whole image visible, letterboxed. */
    FIT_CENTER,
}

/**
 * Maps normalised landmark coordinates of the analysed (upright, un-mirrored) camera image onto the preview
 * view, the way PreviewView draws it: scaled about the centre (FILL_CENTER crops, FIT_CENTER letterboxes)
 * and, for the front camera, mirrored left-right. Pure: Track E's try-on overlay uses it to place frames.
 *
 * Assumes the preview stream and the analysis stream show the same field of view (both are bound at 16:9 by
 * [MediaPipeFaceTracker]).
 */
class PreviewTransform(
    val imageWidthPx: Int,
    val imageHeightPx: Int,
    val viewWidthPx: Float,
    val viewHeightPx: Float,
    val mirrored: Boolean,
    val scaleType: PreviewScale = PreviewScale.FILL_CENTER,
) {
    init {
        require(imageWidthPx > 0 && imageHeightPx > 0) { "Bad image size" }
    }

    /** View pixels per image pixel (multiply image-space lengths by this to get on-screen lengths). */
    val scale: Float = run {
        val sx = viewWidthPx / imageWidthPx
        val sy = viewHeightPx / imageHeightPx
        if (scaleType == PreviewScale.FILL_CENTER) max(sx, sy) else min(sx, sy)
    }
    private val offsetX = (viewWidthPx - imageWidthPx * scale) / 2f
    private val offsetY = (viewHeightPx - imageHeightPx * scale) / 2f

    /** Normalised image coordinates (0..1) → view pixels. */
    fun map(xNorm: Float, yNorm: Float): ViewPoint {
        val x = if (mirrored) 1f - xNorm else xNorm
        return ViewPoint(x * imageWidthPx * scale + offsetX, yNorm * imageHeightPx * scale + offsetY)
    }

    fun map(l: Landmark): ViewPoint = map(l.x, l.y)

    /** True when the point lands inside the visible view (FILL_CENTER crops the image edges). */
    fun isVisible(p: ViewPoint): Boolean = p.x in 0f..viewWidthPx && p.y in 0f..viewHeightPx

    companion object {
        fun forFrame(frame: FaceFrame, viewWidthPx: Float, viewHeightPx: Float, scaleType: PreviewScale = PreviewScale.FILL_CENTER) =
            PreviewTransform(frame.imageWidthPx, frame.imageHeightPx, viewWidthPx, viewHeightPx, frame.mirrored, scaleType)
    }
}
