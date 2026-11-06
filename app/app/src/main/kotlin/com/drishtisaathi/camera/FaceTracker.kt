package com.drishtisaathi.camera

import com.drishtisaathi.engine.face.FaceFrame
import kotlinx.coroutines.flow.StateFlow

/**
 * Contract for the live face stream (Track B implements with CameraX + MediaPipe Face Landmarker).
 * Consumers: distance provider, capture screen, try-on overlay (Track E).
 * Frames hold landmarks only; camera images are never stored (Section 6).
 */
interface FaceTracker {
    /** Latest frame with a detected face, or null when no face is visible. */
    val frames: StateFlow<FaceFrame?>
    fun start()
    fun stop()
}
