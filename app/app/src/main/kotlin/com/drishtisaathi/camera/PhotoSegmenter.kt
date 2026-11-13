package com.drishtisaathi.camera

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.io.Closeable

/**
 * MediaPipe Image Segmenter with the selfie multiclass model (background, hair, body-skin, face-skin, clothes,
 * others; Apache-2.0) in IMAGE mode, for the three avatar photos: which pixels may colour skin and which hair.
 * Runs on the phone only. Not thread-safe; close when done.
 */
class PhotoSegmenter(context: Context) : Closeable {
    private val segmenter: ImageSegmenter = ImageSegmenter.createFromOptions(
        context.applicationContext,
        ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
            .setRunningMode(RunningMode.IMAGE)
            .setOutputCategoryMask(true)
            .setOutputConfidenceMasks(false)
            .build(),
    )

    /** Class per pixel (row-major) with its width and height, or null when segmentation fails. */
    fun classes(bitmap: Bitmap): Triple<ByteArray, Int, Int>? = runCatching {
        val r = segmenter.segment(BitmapImageBuilder(bitmap).build())
        val mask = r.categoryMask().orElse(null) ?: return null
        val buf = ByteBufferExtractor.extract(mask)
        val w = mask.width; val h = mask.height
        val out = ByteArray(w * h)
        buf.rewind()
        buf.get(out, 0, minOf(out.size, buf.remaining()))
        Triple(out, w, h)
    }.getOrNull()

    override fun close() = segmenter.close()

    companion object {
        const val MODEL_ASSET = "selfie_multiclass_256x256.tflite"
    }
}
