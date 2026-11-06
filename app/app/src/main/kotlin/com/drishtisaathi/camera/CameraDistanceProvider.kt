package com.drishtisaathi.camera

import com.drishtisaathi.calibration.CalibrationRepository
import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.config.CameraConfig
import com.drishtisaathi.engine.face.FaceFrame
import com.drishtisaathi.vision.DistanceProvider
import com.drishtisaathi.vision.DistanceReading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Eye-to-camera distance from one face frame. */
internal fun interface FrameDistanceEstimator {
    /** @param irisDiameterMm this customer's calibrated iris size, or null for the population prior. */
    fun estimate(frame: FaceFrame, irisDiameterMm: Double?): FrameDistance?
}

internal data class FrameDistance(val distanceMm: Double, val confidence: Confidence)

/**
 * ================================================================================================
 * PROVISIONAL distance estimate. Track A is writing the real one in the engine
 * (com.drishtisaathi.engine.vision.DistanceEstimator). When it is merged, the lead replaces
 * [CameraDistanceProvider.defaultEstimator] with an adapter to Track A's class (one line) and deletes this.
 * ================================================================================================
 *
 * Pinhole model: d = f_px × irisDiameterMm / irisDiameterPx, iris from landmarks 468–472 / 473–477.
 * Confidence: HIGH with Camera2 focal length + this customer's card-measured iris; MEDIUM with the
 * 11.7 mm prior; LOW when the focal length had to be assumed.
 */
internal class ProvisionalIrisDistanceEstimator(private val priorIrisMm: Double) : FrameDistanceEstimator {
    override fun estimate(frame: FaceFrame, irisDiameterMm: Double?): FrameDistance? {
        val irisPx = FaceMetrics.meanIrisDiameterPx(frame) ?: return null
        val f = frame.focalLengthPx ?: return null
        val irisMm = irisDiameterMm ?: priorIrisMm
        val confidence = when {
            irisDiameterMm != null -> Confidence.HIGH
            else -> Confidence.MEDIUM
        }
        return FrameDistance(f * irisMm / irisPx, confidence)
    }
}

/** Median of the last [size] values (pure; smooths landmark jitter without lag from a long average). */
class MedianWindow(private val size: Int) {
    private val values = ArrayDeque<Double>()
    fun add(v: Double): Double {
        values.addLast(v)
        while (values.size > size.coerceAtLeast(1)) values.removeFirst()
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }
    fun clear() = values.clear()
}

/**
 * [DistanceProvider] backed by the live face tracker (Track C's vision tests read this).
 * When the face is lost, it keeps the last distance with faceVisible = false so tests can pause.
 *
 * @param focalFromCamera2 reports whether the tracker's focal length is real (else confidence is LOW).
 * @param controlsTracker when true, start/stop also start/stop the tracker; set false when a screen already
 *   runs the tracker (e.g. shows its preview).
 */
class CameraDistanceProvider internal constructor(
    private val tracker: FaceTracker,
    private val calibration: CalibrationRepository,
    private val config: CameraConfig,
    private val estimator: FrameDistanceEstimator,
    private val focalFromCamera2: () -> Boolean,
    private val controlsTracker: Boolean,
) : DistanceProvider {

    constructor(
        tracker: MediaPipeFaceTracker,
        calibration: CalibrationRepository,
        config: CameraConfig,
        controlsTracker: Boolean = true,
    ) : this(tracker, calibration, config, defaultEstimator(config), { tracker.focalFromCamera2 }, controlsTracker)

    private val _readings = MutableStateFlow<DistanceReading?>(null)
    override val readings: StateFlow<DistanceReading?> = _readings.asStateFlow()
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private val window = MedianWindow(config.distanceSmoothingFrames)

    override fun start() {
        if (job != null) return
        if (controlsTracker) tracker.start()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        job = s.launch { tracker.frames.collect { onFrame(it) } }
    }

    override fun stop() {
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
        window.clear()
        if (controlsTracker) tracker.stop()
    }

    internal fun onFrame(frame: FaceFrame?, nowMs: Long = System.currentTimeMillis()) {
        val est = frame?.let { estimator.estimate(it, calibration.calibration.value.irisDiameterMm) }
        if (est == null) {
            val last = _readings.value ?: return
            if (last.faceVisible) _readings.value = last.copy(faceVisible = false, timestampMs = nowMs)
            return
        }
        val smoothed = window.add(est.distanceMm)
        val conf = if (focalFromCamera2()) est.confidence else Confidence.LOW
        _readings.value = DistanceReading(smoothed, conf, faceVisible = true, timestampMs = nowMs)
    }

    companion object {
        /** PROVISIONAL: swap for an adapter to Track A's engine DistanceEstimator when merged. */
        internal fun defaultEstimator(config: CameraConfig): FrameDistanceEstimator =
            ProvisionalIrisDistanceEstimator(config.provisionalIrisDiameterMm)
    }
}
