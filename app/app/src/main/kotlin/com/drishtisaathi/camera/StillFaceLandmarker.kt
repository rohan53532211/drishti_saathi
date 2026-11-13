package com.drishtisaathi.camera

import android.content.Context
import android.graphics.Bitmap
import com.drishtisaathi.engine.face.FaceFrame
import com.drishtisaathi.engine.face.Landmark
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.io.Closeable

/**
 * Face Landmarker in IMAGE mode for full-resolution still photos (the 3D avatar), so the landmarks and the
 * transformation matrix belong to the very photo that becomes the texture. Not thread-safe; close when done.
 */
class StillFaceLandmarker(context: Context) : Closeable {
    private val landmarker: FaceLandmarker = FaceLandmarker.createFromOptions(
        context.applicationContext,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MediaPipeFaceTracker.MODEL_ASSET).build())
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(false)
            .setOutputFacialTransformationMatrixes(true)
            .build(),
    )

    /** Landmarks of [bitmap] (upright ARGB_8888), with [focalPx] for this image size; null when no face. */
    fun detect(bitmap: Bitmap, focalPx: Double?): FaceFrame? {
        val r = landmarker.detect(BitmapImageBuilder(bitmap).build())
        val face = r.faceLandmarks().firstOrNull()?.takeIf { it.size >= FaceFrame.LANDMARK_COUNT } ?: return null
        return FaceFrame(
            landmarks = face.map { Landmark(it.x(), it.y(), it.z()) },
            imageWidthPx = bitmap.width,
            imageHeightPx = bitmap.height,
            transformMatrix = r.facialTransformationMatrixes().orElse(null)?.firstOrNull(),
            focalLengthPx = focalPx,
            mirrored = false,
        )
    }

    override fun close() = landmarker.close()
}
