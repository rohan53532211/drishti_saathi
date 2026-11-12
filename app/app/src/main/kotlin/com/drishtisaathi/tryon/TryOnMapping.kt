package com.drishtisaathi.tryon

import androidx.compose.ui.geometry.Offset
import com.drishtisaathi.engine.face.FaceFrame

/**
 * Maps a point in normalised camera-image coordinates (the same space as [FaceFrame] landmarks) to pixels of the
 * view that shows the camera preview. The lead plugs Track B's preview mapper in here so the overlay lines up with
 * exactly what the preview shows (crop, rotation, mirroring).
 */
fun interface TryOnMapping {
    fun toView(x: Double, y: Double, frame: FaceFrame, viewWidth: Float, viewHeight: Float): Offset
}

/**
 * Default mapping until Track B's mapper is wired in: PreviewView FILL_CENTER (scale to cover, centre crop),
 * mirrored horizontally when [FaceFrame.mirrored]. Assumes the landmarks' image is already upright, i.e.
 * [FaceFrame.imageWidthPx]/[FaceFrame.imageHeightPx] are in display orientation.
 */
object FillCenterMapping : TryOnMapping {
    override fun toView(x: Double, y: Double, frame: FaceFrame, viewWidth: Float, viewHeight: Float): Offset {
        val iw = frame.imageWidthPx.toFloat()
        val ih = frame.imageHeightPx.toFloat()
        if (iw <= 0f || ih <= 0f) return Offset(x.toFloat() * viewWidth, y.toFloat() * viewHeight)
        val scale = maxOf(viewWidth / iw, viewHeight / ih)
        val dx = (viewWidth - iw * scale) / 2f
        val dy = (viewHeight - ih * scale) / 2f
        val vx = x.toFloat() * iw * scale + dx
        val vy = y.toFloat() * ih * scale + dy
        return Offset(if (frame.mirrored) viewWidth - vx else vx, vy)
    }
}
