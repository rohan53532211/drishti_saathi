package com.drishtisaathi.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.drishtisaathi.engine.config.CameraConfig
import com.drishtisaathi.engine.face.FaceFrame
import com.drishtisaathi.engine.face.Landmark
import com.google.mediapipe.framework.image.BitmapExtractor
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One analysed frame: the face (null = none) plus brightness/sharpness of the face region and the frame rate. */
data class FrameAnalysis(
    val face: FaceFrame?,
    val stats: ImageStats?,
    val fps: Double,
    val timestampMs: Long,
)

/** A [FaceTracker] that also reports image quality per frame (needed by the quality gates). */
interface AnalysedFaceTracker : FaceTracker {
    val analysis: StateFlow<FrameAnalysis?>
}

/**
 * CameraX front camera → MediaPipe Face Landmarker (LIVE_STREAM, transformation matrixes on) → [FaceFrame].
 *
 * Privacy (Section 6): each camera image lives only in memory for the duration of one analysis; only landmark
 * data and two numbers (brightness, sharpness) leave this class. Nothing is written to disk.
 *
 * Threading: [start], [stop], [setPreviewSurfaceProvider] must be called on the main thread. The tracker is
 * its own [LifecycleOwner] (RESUMED between start and stop), so hosts only call start/stop, e.g. from a
 * DisposableEffect (see [rememberFaceTracker]).
 *
 * @param enablePhoto also bind an ImageCapture use case so [takePhoto] works (forehead-card calibration).
 */
class MediaPipeFaceTracker(
    context: Context,
    private val config: CameraConfig,
    private val enablePhoto: Boolean = false,
    /** 3D avatar: full-quality 4:3 colour photos of at least [AVATAR_PHOTO_SHORT_PX] px on the short side. */
    private val avatarPhoto: Boolean = false,
) : AnalysedFaceTracker, LifecycleOwner {

    private val appContext = context.applicationContext
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val _frames = MutableStateFlow<FaceFrame?>(null)
    override val frames: StateFlow<FaceFrame?> = _frames.asStateFlow()
    private val _analysis = MutableStateFlow<FrameAnalysis?>(null)
    override val analysis: StateFlow<FrameAnalysis?> = _analysis.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    /** Non-null when the camera or the landmarker failed to start (shown to staff; typed flow continues). */
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val selector = CameraSelector.DEFAULT_FRONT_CAMERA
    private val mirrored = true // front camera: PreviewView shows a mirror image

    private val preview: Preview = Preview.Builder().setResolutionSelector(selector16x9(config.analysisWidthPx, config.analysisHeightPx)).build()
    private var analysisUseCase: ImageAnalysis? = null
    private var captureUseCase: ImageCapture? = null
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var executor: ExecutorService? = null

    // Analysis-thread state.
    private var landmarker: FaceLandmarker? = null
    private var lastTimestampMs = -1L
    @Volatile private var lensInfo: LensInfo? = null
    @Volatile private var focalCache: Triple<Int, Int, Double>? = null
    @Volatile private var currentRotation = 0
    private var fpsWindowStart = 0L
    private var fpsCount = 0
    @Volatile private var fps = 0.0
    @Volatile private var started = false

    /** Attach/detach the on-screen preview. Pass PreviewView.surfaceProvider, or null when the view goes away. */
    fun setPreviewSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?) {
        checkMain()
        preview.setSurfaceProvider(surfaceProvider)
    }

    override fun start() {
        checkMain()
        if (started) return
        started = true
        _error.value = null
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        val exec = Executors.newSingleThreadExecutor().also { executor = it }
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener({
            if (!started) return@addListener
            try {
                val p = future.get().also { provider = it }
                if (!p.hasCamera(selector)) {
                    _error.value = "No front camera"
                    return@addListener
                }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(selector16x9(config.analysisWidthPx, config.analysisHeightPx))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(exec) { analyze(it) }
                analysisUseCase = analysis
                val useCases = mutableListOf<UseCase>(preview, analysis)
                if (enablePhoto || avatarPhoto) {
                    captureUseCase = ImageCapture.Builder()
                        .setCaptureMode(if (avatarPhoto) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setResolutionSelector(
                            if (avatarPhoto) selector4x3(AVATAR_PHOTO_SHORT_PX * 4 / 3, AVATAR_PHOTO_SHORT_PX)
                            else selector16x9(config.photoWidthPx, config.photoHeightPx),
                        )
                        .build()
                        .also { useCases += it }
                }
                p.unbindAll()
                camera = p.bindToLifecycle(this, selector, *useCases.toTypedArray())
                lensInfo = camera?.let(::readLensInfo)
                _running.value = true
            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed", e)
                _error.value = e.message ?: e.javaClass.simpleName
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    /** Releases the camera, the landmarker and the analysis thread. Safe to call twice; [start] works again after. */
    override fun stop() {
        checkMain()
        if (!started) return
        started = false
        analysisUseCase?.clearAnalyzer()
        provider?.unbindAll()
        analysisUseCase = null
        captureUseCase = null
        camera = null
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        executor?.let { exec ->
            exec.execute {
                landmarker?.close()
                landmarker = null
                lastTimestampMs = -1L
            }
            exec.shutdown()
        }
        executor = null
        _frames.value = null
        _analysis.value = null
        _running.value = false
    }

    /**
     * One upright (not mirrored) photo held in memory, for the forehead-card calibration. The caller must
     * recycle it as soon as it is measured; it is never written anywhere. Requires enablePhoto = true.
     */
    suspend fun takePhoto(): Bitmap = suspendCancellableCoroutine { cont ->
        val capture = captureUseCase
        val exec = executor
        if (capture == null || exec == null) {
            cont.resumeWithException(IllegalStateException("Photo capture not available"))
            return@suspendCancellableCoroutine
        }
        capture.takePicture(exec, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bmp = try {
                    uprightBitmap(image)
                } catch (e: Exception) {
                    image.close()
                    cont.resumeWithException(e)
                    return
                }
                image.close()
                if (cont.isActive) cont.resume(bmp) else bmp.recycle()
            }

            override fun onError(exception: ImageCaptureException) {
                cont.resumeWithException(exception)
            }
        })
    }

    @Volatile private var grab: ((Bitmap, FaceFrame) -> Unit)? = null

    /**
     * 3D avatar (after its own consent screen): the next analysed frame that has a face, as an upright, not mirrored
     * copy of the image plus its landmarks, so photo and mesh line up exactly. The caller owns the bitmap; it is
     * kept only in app-private encrypted storage for this session (see tryon3d/AvatarStore).
     */
    suspend fun grabFrame(): Pair<Bitmap, FaceFrame> = suspendCancellableCoroutine { cont ->
        grab = { bmp, frame -> if (cont.isActive) cont.resume(bmp to frame) else bmp.recycle() }
        cont.invokeOnCancellation { grab = null }
    }

    // ---------------------------------------------------------------------------------------------
    // Analysis thread
    // ---------------------------------------------------------------------------------------------

    private fun analyze(proxy: ImageProxy) {
        if (!started) {
            proxy.close()
            return
        }
        val bitmap: Bitmap
        val ts: Long
        try {
            currentRotation = proxy.imageInfo.rotationDegrees
            ts = (proxy.imageInfo.timestamp / 1_000_000L).let { if (it <= lastTimestampMs) lastTimestampMs + 1 else it }
            bitmap = uprightBitmap(proxy)
        } catch (e: Exception) {
            Log.w(TAG, "Frame conversion failed", e)
            return
        } finally {
            proxy.close() // the camera buffer is released immediately
        }
        val lm = landmarker ?: try {
            createLandmarker().also { landmarker = it }
        } catch (e: Exception) {
            Log.e(TAG, "Face landmarker failed to load", e)
            _error.value = "Face model: " + (e.message ?: e.javaClass.simpleName)
            return
        }
        lastTimestampMs = ts
        try {
            lm.detectAsync(BitmapImageBuilder(bitmap).build(), ts)
        } catch (e: Exception) {
            Log.w(TAG, "detectAsync failed", e)
        }
    }

    private fun createLandmarker(): FaceLandmarker {
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(config.minFaceDetectionConfidence.toFloat())
            .setMinFacePresenceConfidence(config.minFacePresenceConfidence.toFloat())
            .setMinTrackingConfidence(config.minTrackingConfidence.toFloat())
            .setOutputFaceBlendshapes(false)
            .setOutputFacialTransformationMatrixes(true)
            .setResultListener(::onResult)
            .setErrorListener { e -> Log.w(TAG, "Landmarker error", e) }
            .build()
        return FaceLandmarker.createFromOptions(appContext, options)
    }

    private fun onResult(result: FaceLandmarkerResult, input: MPImage) {
        if (!started) return
        val now = SystemClock.elapsedRealtime()
        updateFps(now)
        val w = input.width
        val h = input.height
        val face = result.faceLandmarks().firstOrNull()
        if (face == null || face.isEmpty()) {
            _frames.value = null
            _analysis.value = FrameAnalysis(null, null, fps, result.timestampMs())
            return
        }
        val matrix = result.facialTransformationMatrixes().orElse(null)?.firstOrNull()
        val frame = FaceFrame(
            landmarks = face.map { Landmark(it.x(), it.y(), it.z()) },
            imageWidthPx = w,
            imageHeightPx = h,
            transformMatrix = matrix,
            focalLengthPx = focalPx(w, h),
            mirrored = mirrored,
            timestampMs = result.timestampMs(),
        )
        val stats = try {
            faceRegionStats(BitmapExtractor.extract(input), frame)
        } catch (e: Exception) {
            null
        }
        _analysis.value = FrameAnalysis(frame, stats, fps, frame.timestampMs)
        _frames.value = frame
        grab?.let { g ->
            grab = null
            try {
                g(BitmapExtractor.extract(input).copy(Bitmap.Config.ARGB_8888, false), frame)
            } catch (e: Exception) {
                Log.w(TAG, "Frame grab failed", e)
                grab = g // try again on the next frame
            }
        }
    }

    private fun updateFps(now: Long) {
        if (fpsWindowStart == 0L) fpsWindowStart = now
        fpsCount++
        val elapsed = now - fpsWindowStart
        if (elapsed >= 1000) {
            fps = fpsCount * 1000.0 / elapsed
            fpsCount = 0
            fpsWindowStart = now
        }
    }

    /** Focal length in pixels for an upright image of [w]×[h] from this camera (Camera2 when known). */
    fun focalPxFor(w: Int, h: Int): Double = focalPx(w, h)

    private fun focalPx(w: Int, h: Int): Double {
        focalCache?.let { (cw, ch, f) -> if (cw == w && ch == h) return f }
        val li = lensInfo
        val f = if (li != null) {
            FocalLength.px(
                li.focalMm, li.sensorWidthMm, li.sensorHeightMm, w, h, currentRotation,
                li.pixelArrayW, li.pixelArrayH, li.activeW, li.activeH,
            )
        } else {
            FocalLength.fromFov(w, h, config.fallbackHorizontalFovDeg)
        }
        if (li != null) focalCache = Triple(w, h, f)
        return f
    }

    /** True when the focal length comes from Camera2 rather than the assumed field of view. */
    val focalFromCamera2: Boolean get() = lensInfo != null

    private data class LensInfo(
        val focalMm: Double, val sensorWidthMm: Double, val sensorHeightMm: Double,
        val pixelArrayW: Int?, val pixelArrayH: Int?, val activeW: Int?, val activeH: Int?,
    )

    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun readLensInfo(cam: Camera): LensInfo? = try {
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val size = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val pixels = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val active = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        if (focal == null || focal <= 0f || size == null || size.width <= 0f || size.height <= 0f) {
            null
        } else {
            LensInfo(
                focal.toDouble(), size.width.toDouble(), size.height.toDouble(),
                pixels?.width, pixels?.height, active?.width(), active?.height(),
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "No Camera2 lens info", e)
        null
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) { "Call on the main thread" }

    companion object {
        private const val TAG = "FaceTracker"
        const val MODEL_ASSET = "face_landmarker.task"

        /** Short side of the avatar photos (1920 px wide when held upright). */
        const val AVATAR_PHOTO_SHORT_PX = 1920

        internal fun selector4x3(w: Int, h: Int): ResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(w, h), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()

        internal fun selector16x9(w: Int, h: Int): ResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(w, h), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()

        /** ImageProxy → upright ARGB bitmap (rotation applied, no mirroring). The proxy is not closed here. */
        internal fun uprightBitmap(proxy: ImageProxy): Bitmap {
            val raw = proxy.toBitmap()
            val rotation = proxy.imageInfo.rotationDegrees
            if (rotation == 0) return raw
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it !== raw) raw.recycle() }
        }

        /** Brightness and sharpness of the face box only; the pixel copy is dropped right after. */
        internal fun faceRegionStats(bitmap: Bitmap, frame: FaceFrame): ImageStats? {
            val roi = ImageStatsCalc.roi(FaceMetrics.faceBox(frame), bitmap.width, bitmap.height) ?: return null
            val (l, t, w, h) = roi.toList()
            val px = IntArray(w * h)
            bitmap.getPixels(px, 0, w, l, t, w, h)
            return ImageStatsCalc.compute(px, w, h)
        }
    }
}
