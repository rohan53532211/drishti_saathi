package com.drishtisaathi.vision

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.drishtisaathi.calibration.CalibrationRepository
import com.drishtisaathi.camera.CameraDistanceProvider
import com.drishtisaathi.camera.CameraPermissionGate
import com.drishtisaathi.camera.rememberFaceTracker
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.QuickEstimate
import com.drishtisaathi.engine.QuickEye
import com.drishtisaathi.engine.QuickTarget
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.anamnesis.QuickEstimator
import com.drishtisaathi.engine.anamnesis.QuickPlan
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.SAnamnesis
import com.drishtisaathi.i18n.SVision
import com.drishtisaathi.i18n.Txt
import com.drishtisaathi.i18n.fmtD
import com.drishtisaathi.ui.BigButton
import com.drishtisaathi.voice.VoiceController
import kotlin.math.roundToInt
import kotlin.random.Random

/** Words that count as "Blur" when said during the quick estimate (Hindi, Hinglish, English). */
val BLUR_KEYWORDS = listOf("धुंधला", "धुंधली", "धुंधले", "धुंधला है", "dhundhla", "dhundla", "dhundli", "blur", "blurry", "blurred")

/**
 * Quick far point for one eye (pure). The phone must first come close (≤ farPointStartMaxMm) so the letters are
 * clear; after that the live line shows the estimate for the current distance and "Blur" freezes it. "Still clear at
 * arm's length" counts from farPoint.selfHeldReachM (0.60 m): no myopia found within reach.
 */
data class QuickFarProcedure(val seenClose: Boolean = false) {
    fun onReading(r: DistanceReading?, rules: RulesConfig): QuickFarProcedure {
        val u = r?.takeIf { it.faceVisible } ?: return this
        return if (!seenClose && u.distanceMm <= rules.visionTests.farPointStartMaxMm) copy(seenClose = true) else this
    }

    /** "If blur starts here: about −X.XX D", only once the phone has been close. */
    fun liveSeD(r: DistanceReading?, est: QuickEstimator): Double? =
        r?.takeIf { seenClose && it.faceVisible && it.distanceMm > 0 }?.let { est.liveSeD(it.distanceMm / 1000.0) }

    /** The same line for display from the first reading on (Blur still waits until the phone has been close). */
    fun previewSeD(r: DistanceReading?, est: QuickEstimator): Double? =
        r?.takeIf { it.faceVisible && it.distanceMm > 0 }?.let { est.liveSeD(it.distanceMm / 1000.0) }

    fun canBlur(r: DistanceReading?, rules: RulesConfig): Boolean =
        seenClose && r != null && r.faceVisible && r.distanceMm >= rules.visionTests.farPointMinMm

    fun canStillClear(r: DistanceReading?, rules: RulesConfig): Boolean =
        seenClose && r != null && r.faceVisible && r.distanceMm >= rules.farPoint.selfHeldReachM * 1000.0

    fun blur(r: DistanceReading, est: QuickEstimator, target: QuickTarget): QuickEye =
        QuickEye(est.liveSeD(r.distanceMm / 1000.0, target), (r.distanceMm / 10.0).roundToInt().toDouble(), target)

    fun stillClear(target: QuickTarget): QuickEye = QuickEye(null, null, target, noMyopiaAtReach = true)
}

private sealed interface QuickStep {
    data class Far(val eye: TestEye) : QuickStep
    data object Near : QuickStep
    data object Done : QuickStep
}

private fun stepsFor(plan: QuickPlan): List<QuickStep> = buildList {
    if (plan.far) { add(QuickStep.Far(TestEye.RIGHT)); add(QuickStep.Far(TestEye.LEFT)) }
    if (plan.near) add(QuickStep.Near)
    add(QuickStep.Done)
}

private enum class QPhase { INTRO, RUN, RESULT }

/** The frozen result line for one eye. */
fun quickEyeText(e: QuickEye): Txt =
    if (e.seD == null || e.noMyopiaAtReach) SAnamnesis.quickNoMyopia
    else SAnamnesis.quickFrozen.format(e.blurAtCm?.roundToInt() ?: 0, fmtD(e.seD!!))

/**
 * The power path's quick estimate: per eye far point (glasses off, other eye covered, distance-locked letters at
 * quickEstimate.letterLogMar) with the live line and the Blur button / "dhundhla"; then, when [plan] says so, one
 * near-point reading for the reading add. Returns a [QuickEstimate] ([primary] = it is the power source).
 */
@Composable
fun QuickEstimateFlow(
    lang: Lang,
    rules: RulesConfig,
    distance: DistanceProvider,
    calibration: CalibrationRepository,
    plan: QuickPlan,
    answers: InterviewAnswers,
    primary: Boolean,
    onDone: (QuickEstimate) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    voice: VoiceController? = null,
    onInstruction: (Txt) -> Unit = {},
    /** Power path: no intro screens, the measurement runs at once (glasses off and the eye to cover are spoken). */
    autoStart: Boolean = false,
) {
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
    val est = remember(rules) { QuickEstimator(rules) }
    val target = rules.quickEstimate.target

    val steps = remember(plan) { stepsFor(plan) }
    var index by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf(if (autoStart) QPhase.RUN else QPhase.INTRO) }
    var right by remember { mutableStateOf<QuickEye?>(null) }
    var left by remember { mutableStateOf<QuickEye?>(null) }
    var nearCm by remember { mutableStateOf<Double?>(null) }
    var addD by remember { mutableStateOf<Double?>(null) }
    var nearDone by remember { mutableStateOf(false) }
    val step = steps[index.coerceAtMost(steps.lastIndex)]

    fun next() { index = (index + 1).coerceAtMost(steps.lastIndex); phase = if (autoStart) QPhase.RUN else QPhase.INTRO }
    fun result() = QuickEstimate(right, left, addD, nearCm, possibleLongSight = plan.possibleLongSight, primary = primary)

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                step == QuickStep.Done -> QuickInfo(
                    lang, SAnamnesis.quickTitle,
                    buildList {
                        right?.let { add(Txt("R: " + quickEyeText(it).en, "R: " + quickEyeText(it).hi)) }
                        left?.let { add(Txt("L: " + quickEyeText(it).en, "L: " + quickEyeText(it).hi)) }
                        if (nearDone) add(nearText(nearCm, addD))
                        if (plan.possibleLongSight) add(SAnamnesis.quickLongSight)
                    },
                    primary = SAnamnesis.quickDone to { onDone(result()) },
                )
                phase == QPhase.INTRO -> QuickInfo(
                    lang, if (step == QuickStep.Near) SAnamnesis.quickNearTitle else SAnamnesis.quickTitle,
                    listOfNotNull(
                        (step as? QuickStep.Far)?.let { if (it.eye == TestEye.RIGHT) SVision.coverLeft else SVision.coverRight },
                        if (step == QuickStep.Near) SAnamnesis.quickIntroNear else SAnamnesis.quickIntroFar,
                    ),
                    primary = SVision.start to { phase = QPhase.RUN },
                    skip = SAnamnesis.quickSkip to onSkip,
                    speak = env.onInstruction,
                )
                phase == QPhase.RESULT -> QuickInfo(
                    lang, SAnamnesis.quickTitle,
                    listOfNotNull(
                        when (step) {
                            is QuickStep.Far -> (if (step.eye == TestEye.RIGHT) right else left)?.let(::quickEyeText)
                            QuickStep.Near -> nearText(nearCm, addD)
                            else -> null
                        },
                    ),
                    primary = SAnamnesis.quickNext to { next() },
                    speak = env.onInstruction,
                )
                step is QuickStep.Far -> key(step) {
                    QuickFarScreen(env, step.eye, reading, est, target, voice, onSkip = { next() }, spokenSetup = autoStart) { eye ->
                        if (step.eye == TestEye.RIGHT) right = eye else left = eye
                        phase = QPhase.RESULT
                    }
                }
                step == QuickStep.Near -> {
                    val farSe = listOfNotNull(right?.seD, left?.seD).takeIf { it.isNotEmpty() }?.average()
                    QuickNearScreen(env, reading, farSe, voice, onSkip = { next() }) { mm ->
                        nearDone = true
                        nearCm = mm?.let { (it / 10.0).roundToInt().toDouble() }
                        addD = mm?.let { est.nearAdd(it / 1000.0, farSe, answers) }
                        phase = QPhase.RESULT
                    }
                }
            }
        }
        (distance as? FakeDistanceProvider)?.let { DistanceDebugPanel(it, lang) }
    }
}

private fun nearText(nearCm: Double?, add: Double?): Txt =
    if (nearCm != null && add != null) SAnamnesis.quickNearFrozen.format(nearCm.roundToInt(), fmtD(add)) else SAnamnesis.quickNearNone

@Composable
private fun QuickInfo(
    lang: Lang, title: Txt, lines: List<Txt>, primary: Pair<Txt, () -> Unit>,
    skip: Pair<Txt, () -> Unit>? = null, speak: ((Txt) -> Unit)? = null,
) {
    LaunchedEffect(lines) { lines.lastOrNull()?.let { speak?.invoke(it) } }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title[lang], style = MaterialTheme.typography.headlineMedium)
        lines.forEach { Text(it[lang], style = MaterialTheme.typography.titleMedium) }
        BigButton(primary.first[lang], selected = true, onClick = primary.second)
        skip?.let { (t, f) -> TextButton(onClick = f, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(t[lang]) } }
    }
}

/** Starts keyword listening while this screen is shown, if the mic is on. */
@Composable
private fun BlurKeyword(voice: VoiceController?, onBlur: () -> Unit) {
    val blur by rememberUpdatedState(onBlur)
    DisposableEffect(voice) {
        voice?.listenForKeyword(BLUR_KEYWORDS) { blur() }
        onDispose { voice?.stopKeyword() }
    }
}

@Composable
private fun QuickFarScreen(
    env: TestEnv, eye: TestEye, reading: DistanceReading?, est: QuickEstimator, target: QuickTarget,
    voice: VoiceController?, onSkip: () -> Unit, spokenSetup: Boolean = false, onFrozen: (QuickEye) -> Unit,
) {
    val rules = env.rules
    val cfg = env.cfg
    var proc by remember { mutableStateOf(QuickFarProcedure()) }
    LaunchedEffect(reading) { proc = proc.onReading(reading, rules) }
    val status = DistanceGate.check(reading, null, null)
    val ok = status == DistanceStatus.OK
    val bg = rgbColor(if (target == QuickTarget.WHITE) cfg.farPointWhiteRgb else cfg.farPointBlueRgb)
    val rnd = remember { Random(System.nanoTime()) }
    val dirs = remember { List(cfg.farPointLetterCount) { EDirection.random(rnd) } }
    val live = proc.previewSeD(reading, est)

    val blurNow: () -> Unit = {
        val r = reading
        if (ok && r != null && proc.canBlur(r, rules)) onFrozen(proc.blur(r, est, target))
    }
    BlurKeyword(voice, blurNow)

    // With no intro screen, the first instruction also says which eye to cover and to take the glasses off.
    val step = if (proc.seenClose) SAnamnesis.quickMoveAway else SAnamnesis.quickBringClose
    val cover = if (eye == TestEye.RIGHT) SVision.coverLeft else SVision.coverRight
    val instruction = if (spokenSetup && !proc.seenClose)
        Txt("${cover.en} ${SAnamnesis.quickGlassesOff.en} ${step.en}", "${cover.hi} ${SAnamnesis.quickGlassesOff.hi} ${step.hi}")
    else step
    TestLayout(
        env, SAnamnesis.quickTitle, eye, instruction,
        reading, status, 0, 1, onSkip, background = bg, bigDistance = true,
        target = {
            Canvas(Modifier.fillMaxSize()) {
                val d = reading?.distanceMm ?: return@Canvas
                val h = Sizing.letterHeightPx(env.vm, d, env.scale, rules.quickEstimate.letterLogMar).toFloat()
                    .coerceAtMost(size.width / (2f * dirs.size)).coerceAtLeast(1f)
                val total = dirs.size * h + (dirs.size - 1) * h
                dirs.forEachIndexed { i, dir -> drawTumblingE(Offset(center.x - total / 2f + h / 2f + i * 2f * h, center.y), h, dir) }
            }
        },
        controls = {
            Text(
                live?.let { SAnamnesis.quickLive.format(fmtD(it))[env.lang] } ?: " ",
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            BigButton(SAnamnesis.quickBlur[env.lang], enabled = ok && proc.canBlur(reading, rules), selected = true, onClick = blurNow)
            BigButton(SAnamnesis.quickStillClear[env.lang], enabled = ok && proc.canStillClear(reading, rules),
                onClick = { onFrozen(proc.stillClear(target)) })
        },
    )
}

@Composable
private fun QuickNearScreen(
    env: TestEnv, reading: DistanceReading?, farSeD: Double?, voice: VoiceController?,
    onSkip: () -> Unit, onFrozen: (Double?) -> Unit,
) {
    val cfg = env.cfg
    var proc by remember { mutableStateOf(NearPointProcedure.start(cfg, farSeD).copy(repeats = 1)) }
    LaunchedEffect(reading) { proc = proc.onReading(reading, cfg) }
    val status = DistanceGate.check(reading, null, null)
    val ok = status == DistanceStatus.OK
    val rnd = remember { Random(System.nanoTime()) }
    val dirs = remember { List(10) { EDirection.random(rnd) } }

    val blurNow: () -> Unit = {
        val r = reading
        if (ok && r != null && proc.canMarkBlur(r, cfg)) onFrozen(proc.markBlur(r, cfg).marksMm.last())
    }
    BlurKeyword(voice, blurNow)

    TestLayout(
        env, SAnamnesis.quickNearTitle, TestEye.BOTH, if (proc.seenFar) SAnamnesis.quickMoveIn else SAnamnesis.quickStartFar,
        reading, status, 0, 1, onSkip,
        holdAtMm = if (proc.seenFar) null else proc.startMinMm, bigDistance = true,
        target = {
            Canvas(Modifier.fillMaxSize()) {
                val h = Sizing.mmToPx(cfg.nearPointLetterHeightMm, env.scale).toFloat().coerceAtLeast(3f)
                val perRow = 5
                val total = perRow * h + (perRow - 1) * h
                dirs.forEachIndexed { i, dir ->
                    val row = i / perRow; val col = i % perRow
                    drawTumblingE(Offset(center.x - total / 2f + h / 2f + col * 2f * h, center.y + (row - 0.5f) * 3f * h), h, dir)
                }
            }
        },
        controls = {
            BigButton(SAnamnesis.quickBlur[env.lang], enabled = ok && proc.canMarkBlur(reading, cfg), selected = true, onClick = blurNow)
            BigButton(SVision.nearOutOfReach[env.lang], enabled = ok && proc.canMarkOutOfReach(reading, cfg),
                onClick = { onFrozen(null) })
        },
    )
}

/** [QuickEstimateFlow] with the real camera; without permission: [onSkip], or (staff) the demo distance slider. */
@Composable
fun CameraQuickEstimateFlow(
    lang: Lang,
    rules: RulesConfig,
    calibration: CalibrationRepository,
    plan: QuickPlan,
    answers: InterviewAnswers,
    primary: Boolean,
    onDone: (QuickEstimate) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    voice: VoiceController? = null,
    onInstruction: (Txt) -> Unit = {},
    allowDemoWithoutCamera: Boolean = false,
    /** Power path: ask for the camera at once and start measuring without intro screens. */
    autoStart: Boolean = false,
) {
    var demo by remember { mutableStateOf(false) }
    if (demo) {
        val fake = remember { FakeDistanceProvider() }
        QuickEstimateFlow(lang, rules, fake, calibration, plan, answers, primary, onDone, onSkip, modifier, voice, onInstruction, autoStart)
        return
    }
    CameraPermissionGate(lang, onSkip = { if (allowDemoWithoutCamera) demo = true else onSkip() }, autoRequest = autoStart) {
        val tracker = rememberFaceTracker(rules.camera)
        val provider = remember(tracker) { CameraDistanceProvider(tracker, calibration, rules.camera, controlsTracker = false) }
        QuickEstimateFlow(lang, rules, provider, calibration, plan, answers, primary, onDone, onSkip, modifier, voice, onInstruction, autoStart)
    }
}
