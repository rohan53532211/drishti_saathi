package com.drishtisaathi.calibration

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.drishtisaathi.camera.FaceMetrics
import com.drishtisaathi.camera.MediaPipeFaceTracker
import com.drishtisaathi.engine.config.CameraConfig
import com.drishtisaathi.engine.face.FaceFrame
import com.drishtisaathi.engine.face.Landmark
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Outcome of one forehead-card photo. */
sealed interface ForeheadOutcome {
    data class Ok(
        val irisDiameterMm: Double,
        val cameraMmPerPx: Double,
        val cardWidthPx: Double,
        val irisDiameterPx: Double,
    ) : ForeheadOutcome
    data object CardNotFound : ForeheadOutcome
    data object FaceNotFound : ForeheadOutcome
    data class Implausible(val irisDiameterMm: Double) : ForeheadOutcome
}

/** Pure: card + iris pixel sizes → result (unit-tested). */
object ForeheadMath {
    fun outcome(cardWidthPx: Double, irisDiameterPx: Double, cfg: CameraConfig): ForeheadOutcome {
        val mmPerPx = cfg.cardWidthMm / cardWidthPx
        val irisMm = irisDiameterPx * mmPerPx
        return if (irisMm in cfg.irisMinMm..cfg.irisMaxMm) ForeheadOutcome.Ok(irisMm, mmPerPx, cardWidthPx, irisDiameterPx)
        else ForeheadOutcome.Implausible(irisMm)
    }
}

/** OpenCV card finder: grayscale → blur → Canny → contours → approxPolyDP 4-corner fit → [CardGeometry]. */
object CardDetector {
    @Volatile private var loaded = false

    private fun ensureOpenCv(): Boolean {
        if (!loaded) loaded = OpenCVLoader.initLocal()
        return loaded
    }

    /** Finds the card in [bitmap] (not modified, not kept). Null when no card-shaped outline is found. */
    fun detect(bitmap: Bitmap, cfg: CameraConfig): CardQuad? {
        if (!ensureOpenCv()) error("OpenCV failed to load")
        val rgba = Mat()
        val gray = Mat()
        val edges = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val k = cfg.cardBlurKernelPx.let { if (it % 2 == 0) it + 1 else it }.coerceAtLeast(1)
            Imgproc.GaussianBlur(gray, gray, Size(k.toDouble(), k.toDouble()), 0.0)
            Imgproc.Canny(gray, edges, cfg.cardCannyLow, cfg.cardCannyHigh)
            // Close small gaps in the card edge (fingers, glare).
            Imgproc.dilate(edges, edges, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            val minArea = (cfg.cardMinWidthFraction * bitmap.width).let { it * it / cfg.cardAspect }
            val candidates = mutableListOf<List<Pt>>()
            for (c in contours) {
                if (Imgproc.contourArea(c) < minArea) continue
                val c2f = MatOfPoint2f(*c.toArray())
                val approx = MatOfPoint2f()
                val peri = Imgproc.arcLength(c2f, true)
                Imgproc.approxPolyDP(c2f, approx, cfg.cardApproxEpsilonFraction * peri, true)
                val pts = approx.toArray()
                if (pts.size == 4) candidates += pts.map { Pt(it.x, it.y) }
                c2f.release()
                approx.release()
            }
            return CardGeometry.best(candidates, bitmap.width, cfg)
        } finally {
            contours.forEach { it.release() }
            rgba.release(); gray.release(); edges.release(); hierarchy.release()
        }
    }
}

/** Runs the Face Landmarker once on a still image (IMAGE mode) and closes it. */
object StillFaceLandmarks {
    fun detect(context: Context, bitmap: Bitmap): FaceFrame? {
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MediaPipeFaceTracker.MODEL_ASSET).build())
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(1)
            .build()
        val lm = FaceLandmarker.createFromOptions(context.applicationContext, options)
        try {
            val face = lm.detect(BitmapImageBuilder(bitmap).build()).faceLandmarks().firstOrNull() ?: return null
            return FaceFrame(face.map { Landmark(it.x(), it.y(), it.z()) }, bitmap.width, bitmap.height, mirrored = false)
        } finally {
            lm.close()
        }
    }
}

/**
 * Forehead-card calibration: one photo with a bank card flat on the forehead → camera mm/px at the face plane
 * (85.60 mm / card width px) → this customer's iris diameter in mm (iris px × mm/px).
 * The photo is measured in memory and recycled here; it is never saved (Section 6).
 */
class ForeheadCardCalibration(private val context: Context, private val cfg: CameraConfig) {

    suspend fun measure(photo: Bitmap): ForeheadOutcome = withContext(Dispatchers.Default) {
        try {
            val card = CardDetector.detect(photo, cfg) ?: return@withContext ForeheadOutcome.CardNotFound
            val face = StillFaceLandmarks.detect(context, photo) ?: return@withContext ForeheadOutcome.FaceNotFound
            val irisPx = FaceMetrics.meanIrisDiameterPx(face) ?: return@withContext ForeheadOutcome.FaceNotFound
            ForeheadMath.outcome(card.widthPx, irisPx, cfg)
        } catch (e: Exception) {
            Log.e("ForeheadCard", "Measurement failed", e)
            ForeheadOutcome.CardNotFound
        } finally {
            photo.recycle()
        }
    }

    companion object {
        fun save(repo: CalibrationRepository, ok: ForeheadOutcome.Ok) =
            repo.update { it.copy(irisDiameterMm = ok.irisDiameterMm, cameraMmPerPx = ok.cameraMmPerPx) }
    }
}
