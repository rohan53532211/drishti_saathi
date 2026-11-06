package com.drishtisaathi.vision

import com.drishtisaathi.engine.Confidence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Contract between the camera (Track B, real implementation) and the vision tests (Track C).
 * Distance is eye-to-screen, in millimetres.
 */
data class DistanceReading(
    val distanceMm: Double,
    val confidence: Confidence,
    /** False when no face is in view; tests must pause and ask the customer to look at the screen. */
    val faceVisible: Boolean = true,
    val timestampMs: Long = System.currentTimeMillis(),
)

interface DistanceProvider {
    /** Latest reading, or null before the first one. Updated at camera frame rate (~15–30 Hz). */
    val readings: StateFlow<DistanceReading?>
    fun start()
    fun stop()
}

/**
 * Stand-in until the camera lands, and for tests/demos without a camera: the distance is set by hand
 * (e.g. a slider in a debug panel).
 */
class FakeDistanceProvider(initialMm: Double = 400.0) : DistanceProvider {
    private val _readings = MutableStateFlow<DistanceReading?>(DistanceReading(initialMm, Confidence.LOW))
    override val readings: StateFlow<DistanceReading?> = _readings.asStateFlow()
    override fun start() {}
    override fun stop() {}
    fun set(distanceMm: Double, faceVisible: Boolean = true) {
        _readings.value = DistanceReading(distanceMm, Confidence.LOW, faceVisible)
    }
}
