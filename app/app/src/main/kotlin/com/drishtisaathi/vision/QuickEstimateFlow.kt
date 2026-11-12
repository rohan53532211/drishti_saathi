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
import com.drishtisaathi.i18n.SShop
import com.drishtisaathi.engine.MeasuredEye
import com.drishtisaathi.engine.VisionTests
import androidx.compose.foundation.layout.Row
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
data class QuickFarProcedure(
    val seenClose: Boolean = false,
    /** When the phone was first close ([nowMs] of [onReading]). */
    val closeAtMs: Long? = null,
    /** The first reading, kept while the one automatic repeat runs. */
    val first: QuickEye? = null,
) {
    fun onReading(r: DistanceReading?, rules: RulesConfig, nowMs: Long = 0L): QuickFarProcedure {
        val u = r?.takeIf { it.faceVisible } ?: return this
        return if (!seenClose && u.distanceMm <= rules.visionTests.farPointStartMaxMm) copy(seenClose = true, closeAtMs = nowMs) else this
    }

    /**
     * A first reading looks invalid when blur came closer than [MIN_VALID_BLUR_MM] (stronger than −8 D) or BLUR was
     * tapped within [MIN_MOVE_MS] of the phone being close (it was not moved slowly). Then the reading is repeated once.
     */
    fun looksInvalid(blurMm: Double, nowMs: Long): Boolean =
        blurMm < MIN_VALID_BLUR_MM || (closeAtMs != null && nowMs - closeAtMs < MIN_MOVE_MS)

    /** Starts the one repeat, keeping [firstEye]. */
    fun repeatWith(firstEye: QuickEye): QuickFarProcedure = QuickFarProcedure(first = firstEye)

    companion object {
        const val MIN_VALID_BLUR_MM = 125.0
        const val MIN_MOVE_MS = 1_500L

        /** Median of the two readings (the mean vergence); a repeat that stays sharp to arm's length wins. */
        fun median(a: QuickEye, b: QuickEye, est: QuickEstimator, target: QuickTarget): QuickEye {
            val ca = a.blurAtCm; val cb = b.blurAtCm
            if (b.noMyopiaAtReach || cb == null || ca == null) return b
            val dM = 2.0 / (100.0 / ca + 100.0 / cb)
            return QuickEye(est.liveSeD(dM, target), (dM * 100.0).roundToInt().toDouble(), target)
        }
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

/** One screen of the guided eye check. */
internal sealed interface QuickStep {
    data class Power(val eye: TestEye) : QuickStep
    data class Dial(val eye: TestEye) : QuickStep
    data class Grid(val eye: TestEye) : QuickStep
    data object Near : QuickStep
    data object Done : QuickStep
}

/**
 * The guided eye check, right eye then left: power check (far point), then the line check (clock dial) and the
 * grid check (Amsler) for that eye; then the reading check (near point) when [plan] says so (NEAR or BOTH, 38+).
 * NEAR only: the grid check per eye, then the reading check. [primary] false (the optional check to compare with
 * a known power): the power check only, and the reading check if planned.
 */
internal fun eyeCheckSteps(plan: QuickPlan, primary: Boolean): List<QuickStep> = buildList {
    for (eye in listOf(TestEye.RIGHT, TestEye.LEFT)) {
        if (plan.far) add(QuickStep.Power(eye))
        if (primary && plan.far) add(QuickStep.Dial(eye))
        if (primary && plan.runsAnything) add(QuickStep.Grid(eye))
    }
    if (plan.near) add(QuickStep.Near)
    add(QuickStep.Done)
}

private enum class QPhase { RUN, RESULT }

/** The frozen result line for one eye. */
fun quickEyeText(e: QuickEye): Txt =
    if (e.seD == null || e.noMyopiaAtReach) SAnamnesis.quickNoMyopia
    else SAnamnesis.quickFrozen.format(e.blurAtCm?.roundToInt() ?: 0, fmtD(e.seD!!))

private fun eyeName(eye: TestEye): Txt = when (eye) {
    TestEye.RIGHT -> SShop.rightEye
    TestEye.LEFT -> SShop.leftEye
    TestEye.BOTH -> SShop.bothEyes
}

private fun header(eye: TestEye, test: Txt): Txt =
    Txt(SShop.header.en.format(eyeName(eye).en, test.en), SShop.header.hi.format(eyeName(eye).hi, test.hi))

private fun stepHeader(step: QuickStep): Txt = when (step) {
    is QuickStep.Power -> header(step.eye, SShop.powerCheck)
    is QuickStep.Dial -> header(step.eye, SShop.lineCheck)
    is QuickStep.Grid -> header(step.eye, SShop.gridCheck)
    QuickStep.Near -> header(TestEye.BOTH, SShop.readingCheck)
    QuickStep.Done -> SShop.checkDone
}

private fun dialText(r: ClockDialEyeResult?): Txt = when {
    r == null -> SShop.skipped
    !r.astigmatismFlag -> SShop.dialEqualResult
    else -> {
        val i = r.answers.firstNotNullOf { it }
        val (a, b) = ClockDialAnalysis.hoursOf(i)
        SShop.dialLineResult.format(a, b)
    }
}

private fun gridText(r: AmslerEyeResult?): Txt = when {
    r == null -> SShop.skipped
    r.abnormal -> SShop.gridWavyResult
    else -> SShop.gridFineResult
}

private fun eyeLine(eye: TestEye, t: Txt): Txt = Txt("${eyeName(eye).en}: ${t.en}", "${eyeName(eye).hi}: ${t.hi}")

/**
 * The eye check's results for the engine: the line check flags astigmatism per eye (with the power check's
 * "no short-sight" carried over, so the phone estimate stays the power source), and the grid check flags
 * distortion (urgent referral). Nothing else is invented: a skipped check adds nothing.
 */
internal fun eyeCheckTests(
    dial: Map<TestEye, ClockDialEyeResult>, grid: Map<TestEye, AmslerEyeResult>, right: QuickEye?, left: QuickEye?,
): VisionTests {
    fun eye(e: TestEye, q: QuickEye?): MeasuredEye? = dial[e]?.takeIf { it.astigmatismFlag }?.let {
        MeasuredEye(seD = null, confidence = it.confidence, noMyopiaAtArmsLength = q?.noMyopiaAtReach == true,
            astigmatismFlag = true, astigmatismAxisDeg = it.axisDeg)
    }
    return VisionTests(right = eye(TestEye.RIGHT, right), left = eye(TestEye.LEFT, left), amslerAbnormal = grid.values.any { it.abnormal })
}

/**
 * The guided eye check (under 3 minutes): per eye, glasses off and the other eye covered, the power check with
 * distance-locked letters (only the live distance shows; BLUR or "dhundhla" freezes "Blur at 28 cm, about
 * −3.50 D"), the line check and the grid check; then the reading check. Each test has a header, a Skip button
 * and a short result before the next one; nothing loops. Returns the [QuickEstimate] ([primary] = it is the power
 * source) and the line / grid results as [VisionTests].
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
    onDone: (QuickEstimate, VisionTests) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    voice: VoiceController? = null,
    onInstruction: (Txt) -> Unit = {},
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
    val env = remember(lang, rules, scale, onInstruction) { TestEnv(lang, rules, scale, onInstruction).singleRun() }
    val est = remember(rules) { QuickEstimator(rules) }
    val target = rules.quickEstimate.target

    val steps = remember(plan, primary) { eyeCheckSteps(plan, primary) }
    var index by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf(QPhase.RUN) }
    var right by remember { mutableStateOf<QuickEye?>(null) }
    var left by remember { mutableStateOf<QuickEye?>(null) }
    var dial by remember { mutableStateOf(mapOf<TestEye, ClockDialEyeResult>()) }
    var grid by remember { mutableStateOf(mapOf<TestEye, AmslerEyeResult>()) }
    var nearCm by remember { mutableStateOf<Double?>(null) }
    var addD by remember { mutableStateOf<Double?>(null) }
    var nearDone by remember { mutableStateOf(false) }
    val step = steps[index.coerceAtMost(steps.lastIndex)]

    fun next() { index = (index + 1).coerceAtMost(steps.lastIndex); phase = QPhase.RUN }
    fun result() = QuickEstimate(right, left, addD, nearCm, possibleLongSight = plan.possibleLongSight, primary = primary)

    Column(modifier.fillMaxSize()) {
        if (voice != null) MuteRow(voice, lang)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                step == QuickStep.Done -> QuickInfo(
                    lang, SShop.checkDone,
                    buildList {
                        right?.let { add(eyeLine(TestEye.RIGHT, quickEyeText(it))) }
                        left?.let { add(eyeLine(TestEye.LEFT, quickEyeText(it))) }
                        dial.forEach { (e, r) -> add(eyeLine(e, dialText(r))) }
                        grid.forEach { (e, r) -> add(eyeLine(e, gridText(r))) }
                        if (nearDone) add(nearText(nearCm, addD))
                        if (plan.possibleLongSight) add(SAnamnesis.quickLongSight)
                    },
                    primary = SShop.next to { onDone(result(), eyeCheckTests(dial, grid, right, left)) },
                )
                phase == QPhase.RESULT -> QuickInfo(
                    lang, stepHeader(step),
                    listOfNotNull(
                        when (step) {
                            is QuickStep.Power -> (if (step.eye == TestEye.RIGHT) right else left)?.let(::quickEyeText) ?: SShop.skipped
                            is QuickStep.Dial -> dialText(dial[step.eye])
                            is QuickStep.Grid -> gridText(grid[step.eye])
                            QuickStep.Near -> nearText(nearCm, addD)
                            else -> null
                        },
                    ),
                    primary = SShop.next to { next() },
                    speak = env.onInstruction,
                )
                step is QuickStep.Power -> key(step) {
                    QuickPowerScreen(env, step.eye, reading, est, target, voice, stepHeader(step), onSkip = { next() }) { eye ->
                        if (step.eye == TestEye.RIGHT) right = eye else left = eye
                        phase = QPhase.RESULT
                    }
                }
                step is QuickStep.Dial -> key(step) {
                    val q = if (step.eye == TestEye.RIGHT) right else left
                    val fpMm = q?.takeIf { !it.noMyopiaAtReach }?.blurAtCm?.let { it * 10.0 }
                    val targetMm = fpMm?.let { minOf(it + env.cfg.clockDialBeyondFarPointMm, env.cfg.clockDialNoFarPointMm) }
                        ?: env.cfg.clockDialNoFarPointMm
                    ClockDialTestScreen(env, step.eye, targetMm, reading, onSkip = { next() }, header = stepHeader(step), quiet = true,
                        onComplete = { p ->
                            ClockDialAnalysis.analyse(step.eye, p.answers, env.cfg.clockDialAxisToleranceDeg)?.let { dial = dial + (step.eye to it) }
                            phase = QPhase.RESULT
                        })
                }
                step is QuickStep.Grid -> key(step) {
                    AmslerTestScreen(env, step.eye, reading, onSkip = { next() }, header = stepHeader(step), quiet = true, yesNoOnly = true,
                        onComplete = { p ->
                            AmslerAnalysis.analyse(step.eye, p.answers, p.marks, env.cfg)?.let { grid = grid + (step.eye to it) }
                            phase = QPhase.RESULT
                        })
                }
                step == QuickStep.Near -> {
                    val farSe = listOfNotNull(right?.seD, left?.seD).takeIf { it.isNotEmpty() }?.average()
                    QuickNearScreen(env, reading, farSe, voice, stepHeader(step), onSkip = { next() }) { mm ->
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

/** Mute button, always visible on the eye check. */
@Composable
private fun MuteRow(voice: VoiceController, lang: Lang) {
    val muted by voice.instructionsMuted.collectAsState()
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { voice.setInstructionsMuted(!muted) }) {
            Text((if (muted) SShop.unmute else SShop.mute)[lang], style = MaterialTheme.typography.titleSmall)
        }
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

/**
 * Power check for one eye: white background, distance-locked letters, only the live distance on screen. Start close,
 * move away, BLUR (or "dhundhla") freezes the reading; one automatic repeat when it looks invalid, then the median.
 */
@Composable
private fun QuickPowerScreen(
    env: TestEnv, eye: TestEye, reading: DistanceReading?, est: QuickEstimator, target: QuickTarget,
    voice: VoiceController?, header: Txt, onSkip: () -> Unit, onFrozen: (QuickEye) -> Unit,
) {
    val rules = env.rules
    val cfg = env.cfg
    var proc by remember { mutableStateOf(QuickFarProcedure()) }
    LaunchedEffect(reading) { proc = proc.onReading(reading, rules, System.currentTimeMillis()) }
    val status = DistanceGate.check(reading, null, null)
    val ok = status == DistanceStatus.OK
    val bg = rgbColor(if (target == QuickTarget.WHITE) cfg.farPointWhiteRgb else cfg.farPointBlueRgb)
    val rnd = remember { Random(System.nanoTime()) }
    val dirs = remember { List(cfg.farPointLetterCount) { EDirection.random(rnd) } }

    val blurNow: () -> Unit = {
        val r = reading
        if (ok && r != null && proc.canBlur(r, rules)) {
            val e = proc.blur(r, est, target)
            val first = proc.first
            when {
                first != null -> onFrozen(QuickFarProcedure.median(first, e, est, target))
                proc.looksInvalid(r.distanceMm, System.currentTimeMillis()) -> proc = proc.repeatWith(e)
                else -> onFrozen(e)
            }
        }
    }
    BlurKeyword(voice, blurNow)

    // Spoken once per attempt: glasses off, which eye to cover, and how to move the phone.
    val other = if (eye == TestEye.RIGHT) SShop.leftWord else SShop.rightWord
    val setup = Txt(SShop.glassesOffCover.en.format(other.en), SShop.glassesOffCover.hi.format(other.hi))
    val instruction = if (proc.first != null) SShop.repeatOnce
        else Txt("${setup.en} ${SShop.powerInstruction.en}", "${setup.hi} ${SShop.powerInstruction.hi}")
    TestLayout(
        env, SAnamnesis.quickTitle, eye, instruction,
        reading, status, 0, 1, onSkip, background = bg, bigDistance = true, header = header, quiet = true,
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
            if (!proc.seenClose) Text(SShop.powerStartClose[env.lang], style = MaterialTheme.typography.bodyMedium)
            BigButton(SAnamnesis.quickBlur[env.lang], enabled = ok && proc.canBlur(reading, rules), selected = true, onClick = blurNow)
            BigButton(SShop.stillSharp[env.lang], enabled = ok && proc.canStillClear(reading, rules),
                onClick = { onFrozen(proc.stillClear(target)) })
        },
    )
}

@Composable
private fun QuickNearScreen(
    env: TestEnv, reading: DistanceReading?, farSeD: Double?, voice: VoiceController?, header: Txt,
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
        holdAtMm = if (proc.seenFar) null else proc.startMinMm, bigDistance = true, header = header, quiet = true,
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

/** [QuickEstimateFlow] with the real camera (asked for at once); without permission: [onSkip], or (demo) the distance slider. */
@Composable
fun CameraQuickEstimateFlow(
    lang: Lang,
    rules: RulesConfig,
    calibration: CalibrationRepository,
    plan: QuickPlan,
    answers: InterviewAnswers,
    primary: Boolean,
    onDone: (QuickEstimate, VisionTests) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    voice: VoiceController? = null,
    onInstruction: (Txt) -> Unit = {},
    allowDemoWithoutCamera: Boolean = false,
) {
    var demo by remember { mutableStateOf(false) }
    if (demo) {
        val fake = remember { FakeDistanceProvider() }
        QuickEstimateFlow(lang, rules, fake, calibration, plan, answers, primary, onDone, onSkip, modifier, voice, onInstruction)
        return
    }
    CameraPermissionGate(lang, onSkip = { if (allowDemoWithoutCamera) demo = true else onSkip() }, autoRequest = true) {
        val tracker = rememberFaceTracker(rules.camera)
        val provider = remember(tracker) { CameraDistanceProvider(tracker, calibration, rules.camera, controlsTracker = false) }
        QuickEstimateFlow(lang, rules, provider, calibration, plan, answers, primary, onDone, onSkip, modifier, voice, onInstruction)
    }
}
