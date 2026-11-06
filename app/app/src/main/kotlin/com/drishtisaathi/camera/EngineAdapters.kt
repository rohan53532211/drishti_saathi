package com.drishtisaathi.camera

import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.face.FaceFrame
import com.drishtisaathi.engine.face.FaceGeometry
import com.drishtisaathi.engine.vision.DistanceEstimator

/**
 * Connects the camera track to the engine (Track A). The app sets [rules] once at start-up
 * ([com.drishtisaathi.SessionViewModel]); until then, and in plain JVM unit tests, the camera's own
 * provisional estimators are used.
 */
object EngineBridge {
    @Volatile var rules: RulesConfig? = null
        set(value) {
            field = value
            geometry = value?.let(::FaceGeometry)
        }

    @Volatile private var geometry: FaceGeometry? = null

    /** Engine head pose (transform matrix, else landmarks), or null when the engine is not set up. */
    fun headPose(f: FaceFrame): HeadPose? =
        geometry?.headPose(f)?.let { HeadPose(yawDeg = it.yawDeg, rollDeg = it.rollDeg) }
}

/** Track A's iris-based distance: drops a covered/occluded iris, uses the calibrated iris size when known. */
internal class EngineDistanceEstimator(rules: RulesConfig) : FrameDistanceEstimator {
    private val engine = DistanceEstimator(rules)
    override fun estimate(frame: FaceFrame, irisDiameterMm: Double?): FrameDistance? =
        engine.estimate(frame, irisDiameterMm)?.let { FrameDistance(it.distanceMm, it.confidence) }
}
