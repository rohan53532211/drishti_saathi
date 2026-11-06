package com.drishtisaathi.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.drishtisaathi.calibration.CalibrationRepository
import com.drishtisaathi.engine.config.CameraConfig
import com.drishtisaathi.engine.face.FaceFrame
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.SCamera
import java.util.Locale

/** PROVISIONAL PD for the debug screen: iris-centre distance px × (distance / focal length px). */
object ProvisionalPd {
    fun mm(frame: FaceFrame, distanceMm: Double): Double? {
        val px = FaceMetrics.irisCentreDistancePx(frame) ?: return null
        val f = frame.focalLengthPx ?: return null
        return px * distanceMm / f
    }
}

/**
 * Staff/developer screen: preview plus live numbers (distance, provisional PD, yaw, roll, sharpness,
 * brightness, gate pass/fail, fps). Use it on a real phone to tune the "camera" thresholds in rules.json.
 * Handles the camera permission; [onSkip] leaves when it is refused.
 */
@Composable
fun CameraDebugScreen(
    lang: Lang,
    config: CameraConfig,
    calibration: CalibrationRepository,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CameraPermissionGate(lang, onSkip = onSkip) {
        val tracker = rememberFaceTracker(config)
        val distance = remember(tracker) { CameraDistanceProvider(tracker, calibration, config, controlsTracker = false) }
        DisposableEffect(distance) {
            distance.start()
            onDispose { distance.stop() }
        }
        val a by tracker.analysis.collectAsState()
        val reading by distance.readings.collectAsState()
        val error by tracker.error.collectAsState()
        val cal by calibration.calibration.collectAsState()

        Column(modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopStart) {
                CameraPreview(tracker, Modifier.fillMaxSize())
                val gates = QualityGates.evaluate(a?.face, a?.stats, config)
                LandmarkOverlay(a?.face, gates.passed, Modifier.fillMaxSize())
                val face = a?.face
                val pose = face?.let(FaceMetrics::headPose)
                val ear = face?.let(FaceMetrics::eyeAspectRatios)
                val pd = face?.let { f -> reading?.takeIf { it.faceVisible }?.let { ProvisionalPd.mm(f, it.distanceMm) } }
                val lines = buildList {
                    add(SCamera.debugTitle[lang])
                    error?.let { add("ERROR: $it") }
                    add("fps %.1f   focal %s px (%s)".fmt(a?.fps ?: 0.0, face?.focalLengthPx?.let { "%.0f".fmt(it) } ?: "-",
                        if (tracker.focalFromCamera2) "Camera2" else "assumed FOV"))
                    add("image %s".fmt(face?.let { "${it.imageWidthPx}×${it.imageHeightPx}" } ?: "-"))
                    add("distance %s mm  [%s]%s".fmt(reading?.distanceMm?.let { "%.0f".fmt(it) } ?: "-",
                        reading?.confidence ?: "-", if (reading?.faceVisible == false) " (face lost)" else ""))
                    add("PD (provisional) %s mm".fmt(pd?.let { "%.1f".fmt(it) } ?: "-"))
                    add("iris %s px; calibrated iris %s mm".fmt(face?.let(FaceMetrics::meanIrisDiameterPx)?.let { "%.1f".fmt(it) } ?: "-",
                        cal.irisDiameterMm?.let { "%.2f".fmt(it) } ?: "prior"))
                    add("yaw %s°  roll %s°".fmt(pose?.yawDeg?.let { "%.1f".fmt(it) } ?: "-", pose?.rollDeg?.let { "%.1f".fmt(it) } ?: "-"))
                    add("EAR %s".fmt(ear?.let { "%.2f / %.2f".fmt(it.first, it.second) } ?: "-"))
                    add("sharpness %s  brightness %s".fmt(a?.stats?.laplacianVariance?.let { "%.0f".fmt(it) } ?: "-",
                        a?.stats?.meanBrightness?.let { "%.0f".fmt(it) } ?: "-"))
                    add("screen %s mm/px (%s)".fmt(cal.screenMmPerPx?.let { "%.4f".fmt(it) } ?: "-", cal.screenSource))
                    add((if (gates.passed) SCamera.pass[lang] else SCamera.fail[lang]) +
                        if (gates.passed) "" else ": " + gates.failures.joinToString { it.name })
                    if (face == null) add(SCamera.noFaceShort[lang])
                }
                Column(
                    Modifier.background(Color.Black.copy(alpha = 0.55f)).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    lines.forEach {
                        Text(it, color = Color.White, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun String.fmt(vararg args: Any?): String = String.format(Locale.US, this, *args)
