package com.drishtisaathi.vision

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.drishtisaathi.calibration.CalibrationRepository
import com.drishtisaathi.camera.CameraDistanceProvider
import com.drishtisaathi.camera.CameraPermissionGate
import com.drishtisaathi.camera.rememberFaceTracker
import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.Occupation
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.VisionTests
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.SVision
import com.drishtisaathi.i18n.Txt
import com.drishtisaathi.ui.BigButton
import com.drishtisaathi.ui.TealLight
import kotlin.math.roundToInt

private enum class Phase { INTRO, RUN, RETRY }

/**
 * Runs the M3 vision tests in order ([VisionFlowPlan]): per eye far point (blue, white) and fogged clock dial, the
 * push-up near point (age ≥ ages.nearPointTestFrom or unknown), near acuity and the Amsler grid; then a staff summary.
 * Every test has an intro with "skip" (skipped = no data), runs [VisionTestConfig.repeats] times, and offers one
 * repeat when its result is LOW confidence. Screen brightness is forced to maximum and kept on throughout.
 *
 * @param distance live eye-to-screen distance; started here and stopped on dispose. A [FakeDistanceProvider] shows the
 *   demo slider panel ([debugDistance]) and marks the session as simulated.
 * @param workingDistanceM overrides the working distance from [occupation] (optics.workingDistance*).
 * @param onInstruction every instruction/pause message shown, for the voice track to speak.
 * @param onDone the engine input plus the full session (per-test detail for the staff screen / PDF).
 */
@Composable
fun VisionTestFlow(
    lang: Lang,
    rules: RulesConfig,
    distance: DistanceProvider,
    calibration: CalibrationRepository,
    age: Int?,
    occupation: Occupation?,
    onDone: (VisionTests, VisionSession) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    workingDistanceM: Double? = null,
    onInstruction: (Txt) -> Unit = {},
    debugDistance: FakeDistanceProvider? = distance as? FakeDistanceProvider,
) {
    val cfg = rules.visionTests
    val simulated = distance is FakeDistanceProvider
    val capByDistance = cfg.capByDistanceConfidence && !simulated

    DisposableEffect(distance) {
        distance.start()
        onDispose { distance.stop() }
    }
    MaxBrightnessKeepScreenOn()

    val reading by distance.readings.collectAsState()
    val cal by calibration.calibration.collectAsState()
    val metrics = LocalContext.current.resources.displayMetrics
    val scale = remember(cal, metrics.xdpi, metrics.densityDpi) { Sizing.screenScale(cal, metrics.xdpi, metrics.densityDpi) }
    val env = remember(lang, rules, scale, onInstruction) { TestEnv(lang, rules, scale, onInstruction) }
    val wd = workingDistanceM ?: env.vm.workingDistanceM(occupation)

    val steps = remember(age, rules) { VisionFlowPlan.steps(age, rules) }
    var index by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf(Phase.INTRO) }
    var run by remember { mutableIntStateOf(0) }
    var session by remember { mutableStateOf(VisionSession(simulatedDistance = simulated)) }
    val retries = remember { mutableStateMapOf<VisionStep, Int>() }
    val step = steps[index.coerceAtMost(steps.lastIndex)]

    fun advance() { index = (index + 1).coerceAtMost(steps.lastIndex); phase = Phase.INTRO }
    fun skip() { session = session.copy(skipped = session.skipped + step); advance() }
    fun complete(s: VisionSession) {
        session = s
        val low = VisionFlowPlan.resultConfidence(step, s) == Confidence.LOW
        if (low && (retries[step] ?: 0) < cfg.maxRetriesWhenLow) phase = Phase.RETRY else advance()
    }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                step == VisionStep.Summary ->
                    VisionSummaryScreen(lang, session) { onDone(session.toVisionTests(), session) }
                step == VisionStep.Intro -> InfoScreen(
                    lang, SVision.introTitle, SVision.introBody, null,
                    primary = SVision.start to { advance() }, secondary = SVision.cancel to onCancel,
                )
                phase == Phase.INTRO -> InfoScreen(
                    lang, stepTitle(step), stepIntro(step), stepEye(step),
                    primary = SVision.start to { run++; phase = Phase.RUN }, secondary = SVision.skipTest to { skip() },
                    tertiary = SVision.cancel to onCancel,
                )
                phase == Phase.RETRY -> InfoScreen(
                    lang, stepTitle(step), SVision.lowAgreement, null,
                    primary = SVision.repeatTest to { retries[step] = (retries[step] ?: 0) + 1; run++; phase = Phase.RUN },
                    secondary = SVision.continueAnyway to { advance() },
                )
                else -> key(step, run) {
                    RunStep(env, step, reading, session, age, wd, capByDistance, onSkip = { skip() }, onComplete = { complete(it) })
                }
            }
        }
        debugDistance?.let { DistanceDebugPanel(it, lang) }
    }
}

@Composable
private fun RunStep(
    env: TestEnv,
    step: VisionStep,
    reading: DistanceReading?,
    session: VisionSession,
    age: Int?,
    workingDistanceM: Double,
    capByDistance: Boolean,
    onSkip: () -> Unit,
    onComplete: (VisionSession) -> Unit,
) {
    val rules = env.rules
    val cfg = env.cfg
    fun cap(c: Confidence?) = if (capByDistance) c else null
    when (step) {
        is VisionStep.FarPoint -> FarPointTestScreen(env, step.eye, reading, onSkip) { p ->
            val r = p.result(step.eye, rules, capByDistance)
            onComplete(if (r == null) session else session.copy(farPoint = session.farPoint + (step.eye to r)))
        }
        is VisionStep.ClockDial -> ClockDialTestScreen(
            env, step.eye, FarPointAnalysis.clockDialTargetMm(session.farPoint[step.eye], cfg), reading, onSkip,
        ) { p ->
            val r = ClockDialAnalysis.analyse(step.eye, p.answers, cfg.clockDialAxisToleranceDeg)
            onComplete(if (r == null) session else session.copy(clockDial = session.clockDial + (step.eye to r)))
        }
        VisionStep.NearPoint -> {
            val se = NearPointAnalysis.seForNearPoint(session.farPoint.values)
            NearPointTestScreen(env, reading, seD = se, onSkip = onSkip) { p ->
                val r = NearPointAnalysis.analyse(p.marksMm, se, workingDistanceM, age, rules, cap(p.worstConfidence))
                onComplete(session.copy(nearPoint = r ?: session.nearPoint))
            }
        }
        is VisionStep.NearAcuity -> NearAcuityTestScreen(env, step.eye, reading, onSkip) { p ->
            val r = NearAcuityAnalysis.analyse(step.eye, p.thresholds, cfg, env.scale.fromCard, cap(p.worstConfidence))
            onComplete(if (r == null) session else session.copy(nearAcuity = session.nearAcuity + (step.eye to r)))
        }
        is VisionStep.Amsler -> AmslerTestScreen(env, step.eye, reading, onSkip) { p ->
            val r = AmslerAnalysis.analyse(step.eye, p.answers, p.marks, cfg)
            onComplete(if (r == null) session else session.copy(amsler = session.amsler + (step.eye to r)))
        }
        VisionStep.Intro, VisionStep.Summary -> Unit
    }
}

private fun stepTitle(s: VisionStep): Txt = when (s) {
    is VisionStep.FarPoint -> SVision.farTitle
    is VisionStep.ClockDial -> SVision.dialTitle
    VisionStep.NearPoint -> SVision.nearTitle
    is VisionStep.NearAcuity -> SVision.acuityTitle
    is VisionStep.Amsler -> SVision.amslerTitle
    VisionStep.Intro -> SVision.introTitle
    VisionStep.Summary -> SVision.summaryTitle
}

private fun stepIntro(s: VisionStep): Txt = when (s) {
    is VisionStep.FarPoint -> SVision.farIntro
    is VisionStep.ClockDial -> SVision.dialIntro
    VisionStep.NearPoint -> SVision.nearIntro
    is VisionStep.NearAcuity -> SVision.acuityIntro
    is VisionStep.Amsler -> SVision.amslerIntro
    VisionStep.Intro -> SVision.introBody
    VisionStep.Summary -> SVision.summaryNote
}

private fun stepEye(s: VisionStep): Txt? {
    val eye = when (s) {
        is VisionStep.FarPoint -> s.eye
        is VisionStep.ClockDial -> s.eye
        VisionStep.NearPoint -> TestEye.BOTH
        is VisionStep.NearAcuity -> s.eye
        is VisionStep.Amsler -> s.eye
        else -> return null
    }
    return when (eye) {
        TestEye.RIGHT -> SVision.coverLeft
        TestEye.LEFT -> SVision.coverRight
        TestEye.BOTH -> SVision.bothEyes
    }
}

@Composable
private fun InfoScreen(
    lang: Lang,
    title: Txt,
    body: Txt,
    eyeLine: Txt?,
    primary: Pair<Txt, () -> Unit>,
    secondary: Pair<Txt, () -> Unit>,
    tertiary: Pair<Txt, () -> Unit>? = null,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title[lang], style = MaterialTheme.typography.headlineMedium)
        eyeLine?.let { Text(it[lang], style = MaterialTheme.typography.titleLarge) }
        Text(body[lang], style = MaterialTheme.typography.bodyLarge)
        BigButton(primary.first[lang], selected = true, onClick = primary.second)
        BigButton(secondary.first[lang], onClick = secondary.second)
        tertiary?.let { (t, f) -> TextButton(onClick = f, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(t[lang]) } }
    }
}

/** Demo without a camera: drives a [FakeDistanceProvider] with a slider (50–900 mm) and a face-visible switch. */
@Composable
fun DistanceDebugPanel(fake: FakeDistanceProvider, lang: Lang, modifier: Modifier = Modifier) {
    val current by fake.readings.collectAsState()
    var mm by remember { mutableFloatStateOf((current?.distanceMm ?: 400.0).toFloat()) }
    var face by remember { mutableStateOf(current?.faceVisible ?: true) }
    Column(modifier.fillMaxWidth().background(TealLight).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(SVision.debugTitle[lang] + ": " + (mm / 10f).roundToInt() + " cm", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f), color = Color.Black)
            Checkbox(checked = face, onCheckedChange = { face = it; fake.set(mm.toDouble(), it) })
            Text(SVision.debugFace[lang], style = MaterialTheme.typography.bodySmall, color = Color.Black)
        }
        Slider(value = mm, onValueChange = { mm = it; fake.set(it.toDouble(), face) }, valueRange = 50f..900f)
    }
}

/**
 * [VisionTestFlow] with the real camera: asks for the camera permission, runs the face tracker and a
 * [CameraDistanceProvider]. Without permission: [onCancel], or — when [allowDemoWithoutCamera] — the demo slider.
 */
@Composable
fun CameraVisionTestFlow(
    lang: Lang,
    rules: RulesConfig,
    calibration: CalibrationRepository,
    age: Int?,
    occupation: Occupation?,
    onDone: (VisionTests, VisionSession) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    workingDistanceM: Double? = null,
    onInstruction: (Txt) -> Unit = {},
    allowDemoWithoutCamera: Boolean = false,
) {
    var demo by remember { mutableStateOf(false) }
    if (demo) {
        val fake = remember { FakeDistanceProvider() }
        VisionTestFlow(lang, rules, fake, calibration, age, occupation, onDone, onCancel, modifier, workingDistanceM, onInstruction)
        return
    }
    CameraPermissionGate(lang, onSkip = { if (allowDemoWithoutCamera) demo = true else onCancel() }) {
        val tracker = rememberFaceTracker(rules.camera)
        val provider = remember(tracker) { CameraDistanceProvider(tracker, calibration, rules.camera, controlsTracker = false) }
        VisionTestFlow(lang, rules, provider, calibration, age, occupation, onDone, onCancel, modifier, workingDistanceM, onInstruction)
    }
}
