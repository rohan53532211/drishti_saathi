package com.drishtisaathi.tryon

import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.drishtisaathi.engine.FrameOverlay
import com.drishtisaathi.engine.config.TryOnConfig
import com.drishtisaathi.engine.face.FaceFrame
import com.drishtisaathi.engine.tryon.TryOnPlacement
import com.drishtisaathi.engine.tryon.TryOnPose
import com.drishtisaathi.engine.tryon.TryOnSmoother

/**
 * Draws a frame PNG over the camera preview, posed on the face in [frame]. Put it in a Box on top of the preview,
 * same size as the preview view. Draws nothing when there is no face or the bitmap is not loaded yet.
 *
 * @param mapping normalised image coords → this view's pixels; must match the preview's crop and mirroring
 *   (plug in Track B's mapper; [FillCenterMapping] is the default).
 * @param frontWidthMm real frame front width ([TryOnPlacement.frontWidthMm]); null fits lens centres to the pupils.
 * @param faceWidthMm / [pdMm] measured values for true scale (see [TryOnPlacement.place]).
 */
@Composable
fun TryOnOverlay(
    frame: FaceFrame?,
    overlay: FrameOverlay,
    bitmap: ImageBitmap?,
    mapping: TryOnMapping = FillCenterMapping,
    modifier: Modifier = Modifier,
    frontWidthMm: Double? = null,
    faceWidthMm: Double? = null,
    pdMm: Double? = null,
    config: TryOnConfig = TryOnConfig(),
) {
    val smoother = remember(overlay, config) { TryOnSmoother(config) }
    val pose = remember(frame, overlay, frontWidthMm, faceWidthMm, pdMm, config) {
        smoother.update(TryOnPlacement.place(frame, overlay, frontWidthMm, faceWidthMm, pdMm, config))
    }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    val matrix = remember { Matrix() }
    Canvas(modifier.fillMaxSize()) {
        val f = frame ?: return@Canvas
        val p = pose ?: return@Canvas
        val bmp = bitmap ?: return@Canvas
        if (!overlayMatrix(p, f, bmp, mapping, size.width, size.height, matrix)) return@Canvas
        drawIntoCanvas { it.nativeCanvas.drawBitmap(bmp.asAndroidBitmap(), matrix, paint) }
    }
}

/**
 * Fills [out] with the projective transform that takes the bitmap's corners to the pose's corners in view pixels.
 * The bitmap may be a downsampled copy of the overlay PNG; its own size is used. False if the quad is degenerate.
 */
fun overlayMatrix(
    pose: TryOnPose, frame: FaceFrame, bitmap: ImageBitmap,
    mapping: TryOnMapping, viewWidth: Float, viewHeight: Float, out: Matrix,
): Boolean {
    val dst = viewQuad(pose, frame, mapping, viewWidth, viewHeight)
    val w = bitmap.width.toFloat()
    val h = bitmap.height.toFloat()
    val src = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
    return out.setPolyToPoly(src, 0, dst, 0, 4)
}

/** The pose's four PNG corners (TL, TR, BR, BL) in view pixels, as x0, y0, x1, y1, … (pure; unit-tested). */
fun viewQuad(pose: TryOnPose, frame: FaceFrame, mapping: TryOnMapping, viewWidth: Float, viewHeight: Float): FloatArray {
    val out = FloatArray(8)
    pose.corners.forEachIndexed { i, c ->
        val o = mapping.toView(c.x, c.y, frame, viewWidth, viewHeight)
        out[2 * i] = o.x
        out[2 * i + 1] = o.y
    }
    return out
}
