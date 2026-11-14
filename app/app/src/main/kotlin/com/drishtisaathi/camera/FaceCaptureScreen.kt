package com.drishtisaathi.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.drishtisaathi.engine.config.CameraConfig
import com.drishtisaathi.engine.face.FaceFrame
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.SCamera
import com.drishtisaathi.ui.BigButton
import com.drishtisaathi.ui.OkGreen
import com.drishtisaathi.ui.UrgentRed

/** Pure accumulator of accepted frames (unit-tested). */
class CaptureCollector(val target: Int) {
    private val accepted = mutableListOf<FaceFrame>()
    private var lastTs = Long.MIN_VALUE
    val count: Int get() = accepted.size
    val done: Boolean get() = accepted.size >= target

    /** Adds the frame if it passed every gate and is a new frame; returns true when it was added. */
    fun offer(frame: FaceFrame?, gates: GateResult): Boolean {
        if (done || frame == null || !gates.passed || frame.timestampMs == lastTs) return false
        lastTs = frame.timestampMs
        accepted += frame
        return true
    }

    fun frames(): List<FaceFrame> = accepted.toList()
    fun reset() { accepted.clear(); lastTs = Long.MIN_VALUE }
}

/**
 * M2 face capture: live preview, quality gates with spoken instructions, collects
 * [CameraConfig.captureFrameCount] accepted frames and returns them through [onDone] (landmarks only; the
 * lead computes PD / face width with Track A's FaceGeometry). Handles the camera permission; [onSkip] lets
 * the typed flow continue without the camera.
 *
 * @param speaker defaults to an Android TTS speaker (hi-IN, else en-IN) owned by this screen.
 */
@Composable
fun FaceCaptureScreen(
    lang: Lang,
    config: CameraConfig,
    onDone: (frames: List<FaceFrame>) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    speaker: InstructionSpeaker? = null,
) {
    CameraPermissionGate(lang, onSkip = onSkip) {
        val context = LocalContext.current
        val ownSpeaker = remember(speaker) { speaker ?: TtsInstructionSpeaker(context) }
        DisposableEffect(ownSpeaker) { onDispose { if (speaker == null) ownSpeaker.shutdown() else ownSpeaker.stop() } }
        val announcer = remember(ownSpeaker, config) { InstructionAnnouncer(ownSpeaker, InstructionRateLimiter(config)) }
        val collector = remember(config) { CaptureCollector(config.captureFrameCount) }
        var finished by remember { mutableStateOf(false) }
        val tracker = rememberFaceTracker(config, active = !finished)
        val error by tracker.error.collectAsState()
        val running by tracker.running.collectAsState()
        val latest by tracker.analysis.collectAsState()
        var gates by remember { mutableStateOf<GateResult?>(null) }
        var count by remember { mutableIntStateOf(0) }
        val langNow by rememberUpdatedState(lang)
        val onDoneNow by rememberUpdatedState(onDone)

        LaunchedEffect(tracker, finished) {
            if (finished) return@LaunchedEffect
            tracker.analysis.collect { a ->
                if (a == null) return@collect
                val r = QualityGates.evaluate(a.face, a.stats, config)
                gates = r
                if (collector.offer(a.face, r)) count = collector.count
                if (collector.done) {
                    finished = true
                    announcer.sayNow(SCamera.captureDone, langNow)
                    tracker.stop()
                    onDoneNow(collector.frames())
                } else {
                    announcer.announce(r, langNow)
                }
            }
        }

        Column(modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(SCamera.captureIntro[lang], style = MaterialTheme.typography.bodyMedium)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CameraPreview(tracker, Modifier.fillMaxSize())
                LandmarkOverlay(latest?.face, passed = gates?.passed == true, modifier = Modifier.fillMaxSize())
                if (!running && error == null && !finished) Text(SCamera.cameraStarting[lang], color = Color.White)
            }
            error?.let { Text(SCamera.cameraError[lang] + " ($it)", color = UrgentRed) }
            val instruction = if (finished) SCamera.captureDone else gates?.instruction
            if (instruction != null) {
                Text(
                    instruction[lang],
                    modifier = Modifier.fillMaxWidth()
                        .background(if (gates?.passed == true || finished) OkGreen else UrgentRed, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
            LinearProgressIndicator(progress = { count.toFloat() / collector.target }, modifier = Modifier.fillMaxWidth())
            Text(SCamera.captureProgress.format(count, collector.target)[lang], style = MaterialTheme.typography.bodyMedium)
            BigButton(SCamera.restart[lang], onClick = {
                collector.reset()
                count = 0
                finished = false
            })
            BigButton(SCamera.skipCamera[lang], onClick = onSkip, modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}

/** Draws the iris centres and face box over a [CameraPreview] of the same size (uses [PreviewTransform]). */
@Composable
fun LandmarkOverlay(frame: FaceFrame?, passed: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val f = frame ?: return@Canvas
        val t = PreviewTransform.forFrame(f, size.width, size.height)
        val colour = if (passed) com.drishtisaathi.ui.LightColors.success else com.drishtisaathi.ui.LightColors.urgent
        drawFaceBox(t, FaceMetrics.faceBox(f), colour)
        if (FaceMetrics.hasIris(f)) {
            listOf(FaceMetrics.RIGHT_IRIS.first, FaceMetrics.LEFT_IRIS.first).forEach { i ->
                val p = t.map(f.landmarks[i])
                drawCircle(colour, radius = 5.dp.toPx(), center = Offset(p.x, p.y))
            }
        }
    }
}

private fun DrawScope.drawFaceBox(t: PreviewTransform, b: FaceBox, colour: Color) {
    val a = t.map(b.left, b.top)
    val c = t.map(b.right, b.bottom)
    val left = minOf(a.x, c.x)
    val top = minOf(a.y, c.y)
    drawRect(
        colour, topLeft = Offset(left, top),
        size = androidx.compose.ui.geometry.Size(kotlin.math.abs(c.x - a.x), kotlin.math.abs(c.y - a.y)),
        style = Stroke(width = 2.dp.toPx()),
    )
}
