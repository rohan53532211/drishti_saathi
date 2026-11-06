package com.drishtisaathi.camera

import com.drishtisaathi.engine.config.CameraConfig
import com.drishtisaathi.engine.face.FaceFrame
import com.drishtisaathi.i18n.SCamera
import com.drishtisaathi.i18n.Txt
import kotlin.math.abs
import kotlin.math.min

/**
 * One quality gate failure, in the order they are checked. The first failure is the one spoken to the
 * customer, so the order runs from "fix this first" (no face) to "fine detail" (sharpness).
 */
enum class GateFailure(val instruction: Txt) {
    NO_FACE(SCamera.gNoFace),
    TOO_DARK(SCamera.gDark),
    TOO_BRIGHT(SCamera.gBright),
    TOO_FAR(SCamera.gCloser),
    TOO_CLOSE(SCamera.gFarther),
    NOT_CENTRED(SCamera.gCentre),
    YAW(SCamera.gYaw),
    ROLL(SCamera.gRoll),
    EYES_CLOSED(SCamera.gEyes),
    BLURRY(SCamera.gSharp),
}

/** Everything the gates look at, already measured. Null = not measured (the gate fails safe). */
data class GateInput(
    val facePresent: Boolean,
    val box: FaceBox? = null,
    val yawDeg: Double? = null,
    val rollDeg: Double? = null,
    /** Smaller of the two eyes' aspect ratios. */
    val eyeAspectRatio: Double? = null,
    val stats: ImageStats? = null,
)

data class GateResult(val failures: List<GateFailure>, val input: GateInput) {
    val passed: Boolean get() = failures.isEmpty()
    /** What to say: the first failure, or "good, hold still". */
    val instruction: Txt get() = failures.firstOrNull()?.instruction ?: SCamera.gOk
    /** Stable key for the rate limiter. */
    val instructionKey: String get() = failures.firstOrNull()?.name ?: "OK"
}

/** Pure quality-gate evaluation (Section 5 M2). Thresholds come from rules.json "camera". */
object QualityGates {

    fun evaluate(input: GateInput, cfg: CameraConfig): GateResult {
        if (!input.facePresent || input.box == null) return GateResult(listOf(GateFailure.NO_FACE), input)
        val out = mutableListOf<GateFailure>()
        val s = input.stats
        when {
            s == null -> out += GateFailure.BLURRY // not measured yet: fail safe with "hold still"
            s.meanBrightness < cfg.minMeanBrightness -> out += GateFailure.TOO_DARK
            s.meanBrightness > cfg.maxMeanBrightness -> out += GateFailure.TOO_BRIGHT
        }
        val box = input.box
        when {
            box.width < cfg.minFaceWidthFraction -> out += GateFailure.TOO_FAR
            box.width > cfg.maxFaceWidthFraction -> out += GateFailure.TOO_CLOSE
        }
        if (abs(box.centreX - 0.5f) > cfg.maxCentreOffsetFraction || abs(box.centreY - 0.5f) > cfg.maxCentreOffsetFraction) {
            out += GateFailure.NOT_CENTRED
        }
        if (input.yawDeg == null || abs(input.yawDeg) >= cfg.maxAbsYawDeg) out += GateFailure.YAW
        if (input.rollDeg == null || abs(input.rollDeg) >= cfg.maxAbsRollDeg) out += GateFailure.ROLL
        if (input.eyeAspectRatio == null || input.eyeAspectRatio < cfg.minEyeAspectRatio) out += GateFailure.EYES_CLOSED
        if (s != null && s.laplacianVariance < cfg.minLaplacianVariance) out += GateFailure.BLURRY
        // Keep the documented order whatever order the checks ran in.
        return GateResult(out.distinct().sortedBy { it.ordinal }, input)
    }

    /** Measures a frame (landmarks + face-region stats) into a [GateInput]. */
    fun inputFor(frame: FaceFrame?, stats: ImageStats?, pose: (FaceFrame) -> HeadPose = FaceMetrics::headPose): GateInput {
        if (frame == null || frame.landmarks.size < 468) return GateInput(facePresent = false)
        val p = pose(frame)
        val (er, el) = FaceMetrics.eyeAspectRatios(frame)
        return GateInput(
            facePresent = true,
            box = FaceMetrics.faceBox(frame),
            yawDeg = p.yawDeg,
            rollDeg = p.rollDeg,
            eyeAspectRatio = min(er, el),
            stats = stats,
        )
    }

    fun evaluate(frame: FaceFrame?, stats: ImageStats?, cfg: CameraConfig): GateResult = evaluate(inputFor(frame, stats), cfg)
}
