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
import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.config.CameraConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.graphics.Color
import com.drishtisaathi.engine.MeasuredEye
import com.drishtisaathi.engine.VisionTests
import androidx.compose.foundation.layout.Row
import com.drishtisaathi.i18n.SVision
import com.drishtisaathi.i18n.Txt
import com.drishtisaathi.i18n.fmtD
import com.drishtisaathi.ui.BigButton
import com.drishtisaathi.voice.VoiceController
import kotlin.math.roundToInt
import kotlin.math.pow
import kotlin.math.min
import androidx.compose.foundation.layout.BoxScope
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
    data class Lines(val eye: TestEye) : QuickStep
    data class Grid(val eye: TestEye) : QuickStep
    data object Near : QuickStep
    data object Done : QuickStep
}

/**
 * The guided eye check, right eye then left: power check and the two-line blur test (DISTANCE or BOTH), and the
 * grid check only when [EyeCheckPlan.grid]; then the reading check with both eyes (NEAR or BOTH). [primary] false
 * (the optional check to compare with a known power): the power check only. [gridOnly]: the grid check alone (when
 * no other eye check ran, e.g. a known power at 40+).
 */
internal fun eyeCheckSteps(plan: EyeCheckPlan, primary: Boolean, gridOnly: Boolean = false): List<QuickStep> = buildList {
    for (eye in listOf(TestEye.RIGHT, TestEye.LEFT)) {
        if (!gridOnly && plan.far) add(QuickStep.Power(eye))
        if (!gridOnly && primary && plan.far) add(QuickStep.Lines(eye))
        if ((primary && plan.grid) || gridOnly) add(QuickStep.Grid(eye))
    }
    if (!gridOnly && primary && plan.near) add(QuickStep.Near)
    add(QuickStep.Done)
}

private enum class QPhase { RUN, RESULT }

/** The frozen result line for one eye (with the two-line test's cylinder when it found one). */
fun quickEyeText(e: QuickEye): Txt {
    val base = if (e.seD == null || e.noMyopiaAtReach) SAnamnesis.quickNoMyopia
        else SAnamnesis.quickFrozen.format(e.blurAtCm?.roundToInt() ?: 0, fmtD(e.seD!!))
    val cyl = e.cylD ?: return base
    val c = SShop.cylShort.format(fmtD(cyl), e.cylAxisDeg ?: 0)
    return Txt("${base.en}; ${c.en}", "${base.hi}; ${c.hi}")
}

/** Two-line blur test result. */
fun linesText(r: TwoLineAstig.Result?): Txt = when {
    r == null -> SShop.skipped
    r.flagged -> SShop.linesFlag.format(fmtD(-r.cylD), r.axisDeg ?: 0)
    else -> SShop.linesTogether
}

/** Reading check result: the add (38+), or the near point against the age-expected one (no plus number). */
fun nearResultText(nearPointCm: Double?, addD: Double?, expectedCm: Double?, farther: Boolean): Txt = when {
    nearPointCm == null -> SAnamnesis.quickNearNone
    addD != null -> SShop.nearAdd.format(nearPointCm.roundToInt(), fmtD(addD))
    expectedCm != null && farther -> SShop.nearFarther.format(nearPointCm.roundToInt(), expectedCm.roundToInt())
    expectedCm != null -> SShop.nearNormal.format(nearPointCm.roundToInt(), expectedCm.roundToInt())
    else -> SShop.nearOnly.format(nearPointCm.roundToInt())
}

private fun eyeName(eye: TestEye): Txt = when (eye) {
    TestEye.RIGHT -> SShop.rightEye
    TestEye.LEFT -> SShop.leftEye
    TestEye.BOTH -> SShop.bothEyes
}

private fun header(eye: TestEye, test: Txt): Txt =
    Txt(SShop.header.en.format(eyeName(eye).en, test.en), SShop.header.hi.format(eyeName(eye).hi, test.hi))

private fun stepHeader(step: QuickStep): Txt = when (step) {
    is QuickStep.Power -> header(step.eye, SShop.powerCheck)
    is QuickStep.Lines -> header(step.eye, SShop.lineCheck)
    is QuickStep.Grid -> header(step.eye, SShop.gridCheck)
    QuickStep.Near -> header(TestEye.BOTH, SShop.readingCheck)
    QuickStep.Done -> SShop.checkDone
}

private fun gridText(r: AmslerEyeResult?): Txt = when {
    r == null -> SShop.skipped
    r.abnormal -> SShop.gridWavyResult
    else -> SShop.gridFineResult
}

private fun eyeLine(eye: TestEye, t: Txt): Txt = Txt("${eyeName(eye).en}: ${t.en}", "${eyeName(eye).hi}: ${t.hi}")

/**
 * The eye check's results for the engine: the two-line test flags astigmatism per eye (with the power check's
 * "no short-sight" carried over, so the phone estimate stays the power source), and the grid check flags
 * distortion (urgent referral). Nothing else is invented: a skipped check adds nothing.
 */
internal fun eyeCheckTests(
    lines: Map<TestEye, TwoLineAstig.Result>, grid: Map<TestEye, AmslerEyeResult>, right: QuickEye?, left: QuickEye?,
): VisionTests {
    fun eye(e: TestEye, q: QuickEye?): MeasuredEye? = lines[e]?.takeIf { it.flagged }?.let {
        MeasuredEye(seD = null, confidence = Confidence.LOW, noMyopiaAtArmsLength = q?.noMyopiaAtReach == true,
            astigmatismFlag = true, astigmatismAxisDeg = it.axisDeg)
    }
    return VisionTests(right = eye(TestEye.RIGHT, right), left = eye(TestEye.LEFT, left), amslerAbnormal = grid.values.any { it.abnormal })
}

/** Attaches a flagged two-line result to that eye's power reading (minus cylinder, axis). */
internal fun withCylinder(q: QuickEye?, r: TwoLineAstig.Result?): QuickEye? =
    if (q == null || r == null || !r.flagged) q else q.copy(cylD = -r.cylD, cylAxisDeg = r.axisDeg)

/**
 * The guided eye check (under 3 minutes): per eye, glasses off and the other eye covered, the power check with
 * distance-locked letters (BLUR or "dhundhla" freezes "Blur at 28 cm, about −3.50 D"), the two-line blur test and,
 * when needed, the grid check; then the reading check with both eyes. Each test has a header, a Skip button and a
 * short result; nothing loops. Returns the [QuickEstimate] ([primary] = it is the power source) and the line / grid
 * results as [VisionTests].
 */
@Composable
fun QuickEstimateFlow(
    lang: Lang,
    rules: RulesConfig,
    distance: DistanceProvider,
    calibration: CalibrationRepository,
    plan: EyeCheckPlan,
    answers: InterviewAnswers,
    primary: Boolean,
    onDone: (QuickEstimate, VisionTests) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    voice: VoiceController? = null,
    onInstruction: (Txt) -> Unit = {},
    gridOnly: Boolean = false,
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
    val simulated = distance is FakeDistanceProvider

    val steps = remember(plan, primary, gridOnly) { eyeCheckSteps(plan, primary, gridOnly) }
    var index by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf(QPhase.RUN) }
    var right by remember { mutableStateOf<QuickEye?>(null) }
    var left by remember { mutableStateOf<QuickEye?>(null) }
    var lines by remember { mutableStateOf(mapOf<TestEye, TwoLineAstig.Result>()) }
    var linesSkipped by remember { mutableStateOf(setOf<TestEye>()) }
    var grid by remember { mutableStateOf(mapOf<TestEye, AmslerEyeResult>()) }
    var near by remember { mutableStateOf<NearCheck.Result?>(null) }
    var nearDone by remember { mutableStateOf(false) }
    val step = steps[index.coerceAtMost(steps.lastIndex)]

    fun next() { index = (index + 1).coerceAtMost(steps.lastIndex); phase = QPhase.RUN }
    fun result() = QuickEstimate(
        withCylinder(right, lines[TestEye.RIGHT]), withCylinder(left, lines[TestEye.LEFT]),
        near?.addD, near?.nearPointCm,
        possibleLongSight = near?.fartherThanExpected ?: (plan.possibleLongSight && primary),
        primary = primary, expectedNearPointCm = near?.expectedNearPointCm,
    )
    fun nearLine() = nearResultText(near?.nearPointCm, near?.addD, near?.expectedNearPointCm, near?.fartherThanExpected == true)

    Column(modifier.fillMaxSize()) {
        if (voice != null) MuteRow(voice, lang)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                step == QuickStep.Done -> QuickInfo(
                    lang, SShop.checkDone,
                    buildList {
                        withCylinder(right, lines[TestEye.RIGHT])?.let { add(eyeLine(TestEye.RIGHT, quickEyeText(it))) }
                        withCylinder(left, lines[TestEye.LEFT])?.let { add(eyeLine(TestEye.LEFT, quickEyeText(it))) }
                        lines.forEach { (e, r) -> if (!r.flagged) add(eyeLine(e, linesText(r))) }
                        grid.forEach { (e, r) -> add(eyeLine(e, gridText(r))) }
                        if (nearDone) add(nearLine())
                        if (plan.possibleLongSight && primary && !nearDone && !gridOnly) add(SAnamnesis.quickLongSight)
                    },
                    primary = SShop.next to { onDone(result(), eyeCheckTests(lines, grid, right, left)) },
                )
                phase == QPhase.RESULT -> QuickInfo(
                    lang, stepHeader(step),
                    listOfNotNull(
                        when (step) {
                            is QuickStep.Power -> (if (step.eye == TestEye.RIGHT) right else left)?.let(::quickEyeText) ?: SShop.skipped
                            is QuickStep.Lines -> linesText(lines[step.eye])
                            is QuickStep.Grid -> gridText(grid[step.eye])
                            QuickStep.Near -> nearLine()
                            else -> null
                        },
                    ),
                    primary = SShop.next to { next() },
                    speak = env.onInstruction,
                )
                step is QuickStep.Power -> key(step) {
                    QuickPowerScreen(env, step.eye, reading, est, target, voice, stepHeader(step), simulated,
                        onSkip = { next() }) { eye ->
                        if (step.eye == TestEye.RIGHT) right = eye else left = eye
                        phase = QPhase.RESULT
                    }
                }
                step is QuickStep.Lines -> key(step) {
                    TwoLineScreen(env, step.eye, reading, voice, stepHeader(step), simulated,
                        onSkip = { linesSkipped = linesSkipped + step.eye; next() }) { r ->
                        lines = lines + (step.eye to r)
                        phase = QPhase.RESULT
                    }
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
                    ReadingCheckScreen(env, reading, voice, stepHeader(step), simulated, onSkip = { next() }) { mm ->
                        nearDone = true
                        near = NearCheck.analyse(mm, answers.age, farSe, answers, rules)
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
 * Valid distance readings of the last [keepMs] (face found). BLUR uses the median of the last [useMs], so the tap
 * never waits for a fresh frame; null when nothing valid was seen in [keepMs].
 */
class DistanceBuffer(private val keepMs: Long = 1_500, private val useMs: Long = 1_000) {
    private val items = ArrayDeque<Pair<Long, Double>>()

    fun add(r: DistanceReading?) {
        if (r == null || !r.faceVisible || !(r.distanceMm > 0)) return
        if (items.lastOrNull()?.first == r.timestampMs) return
        items.addLast(r.timestampMs to r.distanceMm)
        trim(r.timestampMs)
    }

    private fun trim(nowMs: Long) {
        while (items.isNotEmpty() && nowMs - items.first().first > keepMs) items.removeFirst()
    }

    /** Distance at the moment of the tap: median of the last [useMs] (else of the last [keepMs]); null if none. */
    fun atTap(nowMs: Long): Double? {
        trim(nowMs)
        val recent = items.filter { nowMs - it.first <= useMs }.ifEmpty { items.toList() }
        return if (recent.isEmpty()) null else median(recent.map { it.second })
    }

    /** Green dot: a valid reading within [freshMs]. */
    fun live(nowMs: Long, freshMs: Long = 500): Boolean = items.lastOrNull()?.let { nowMs - it.first <= freshMs } == true
}

/**
 * The shared blur screen of the eye check (power, two-line, reading): BLUR always enabled (and "dhundhla"), the
 * distance from the median of the last second's readings; with none in 1.5 s "Distance not caught…"; a green / grey
 * dot for live measuring; staff can type the distance from a tape. [second]: an extra button and its action (null =
 * disabled). [onBlur] gets the distance in mm, the time, and whether it was typed.
 */
@Composable
private fun BlurScreen(
    env: TestEnv, eye: TestEye, reading: DistanceReading?, voice: VoiceController?, header: Txt, instruction: Txt,
    simulated: Boolean, onSkip: () -> Unit, background: Color = Color.White,
    second: Pair<Txt, (() -> Unit)?>? = null,
    onBlur: (mm: Double, atMs: Long, typed: Boolean) -> Unit,
    target: @Composable BoxScope.() -> Unit,
) {
    val l = env.lang
    val buffer = remember { DistanceBuffer() }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(reading) { buffer.add(reading) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(250) } }
    val live = if (simulated) reading?.faceVisible == true else buffer.live(now)
    val status = DistanceGate.check(reading, null, null)
    var notCaught by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }

    val blurNow: () -> Unit = {
        val t = System.currentTimeMillis()
        val mm = if (simulated) reading?.takeIf { it.faceVisible }?.distanceMm else buffer.atTap(t)
        if (mm == null) notCaught = true else { notCaught = false; onBlur(mm, t, false) }
    }
    BlurKeyword(voice, blurNow)

    TestLayout(
        env, SAnamnesis.quickTitle, eye, instruction,
        reading, status, 0, 1, onSkip, background = background, bigDistance = true, header = header, quiet = true,
        target = target,
        controls = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(14.dp).background(if (live) Color(0xFF2E7D32) else Color(0xFF9E9E9E), CircleShape))
                Text((if (live) SShop.measuring else SShop.noFaceDot)[l], style = MaterialTheme.typography.bodyMedium)
            }
            if (notCaught) Text(SShop.notCaught[l], color = Color(0xFFB26A00), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold)
            BigButton(SAnamnesis.quickBlur[l], selected = true, onClick = blurNow)
            second?.let { (label, action) -> BigButton(label[l], enabled = action != null, onClick = { action?.invoke() }) }
            if (!typing) {
                TextButton(onClick = { typing = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(SShop.typeDistance[l]) }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        typed, { typed = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f), singleLine = true,
                        label = { Text(SShop.distanceCmLabel[l]) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    )
                    val cm = typed.toIntOrNull()?.takeIf { it in 5..100 }
                    BigButton(SShop.useDistance[l], enabled = cm != null, modifier = Modifier.weight(1f),
                        onClick = { cm?.let { onBlur(it * 10.0, System.currentTimeMillis(), true) } })
                }
            }
        },
    )
}

private fun atReach(r: DistanceReading?, rules: RulesConfig) =
    r != null && r.faceVisible && r.distanceMm >= rules.farPoint.selfHeldReachM * 1000.0

/**
 * Power check for one eye: white background, distance-locked letters, only the live distance on screen. Start close,
 * move away, BLUR freezes the reading; one automatic repeat when it looks invalid, then the median. A distance typed
 * from a tape is used as it is.
 */
@Composable
private fun QuickPowerScreen(
    env: TestEnv, eye: TestEye, reading: DistanceReading?, est: QuickEstimator, target: QuickTarget,
    voice: VoiceController?, header: Txt, simulated: Boolean, onSkip: () -> Unit, onFrozen: (QuickEye) -> Unit,
) {
    val rules = env.rules
    val cfg = env.cfg
    var proc by remember { mutableStateOf(QuickFarProcedure()) }
    LaunchedEffect(reading) { proc = proc.onReading(reading, rules, System.currentTimeMillis()) }
    val bg = rgbColor(if (target == QuickTarget.WHITE) cfg.farPointWhiteRgb else cfg.farPointBlueRgb)
    val rnd = remember { Random(System.nanoTime()) }
    val dirs = remember { List(cfg.farPointLetterCount) { EDirection.random(rnd) } }

    // Spoken once per attempt: glasses off, which eye to cover, and how to move the phone.
    val other = if (eye == TestEye.RIGHT) SShop.leftWord else SShop.rightWord
    val setup = Txt(SShop.glassesOffCover.en.format(other.en), SShop.glassesOffCover.hi.format(other.hi))
    val instruction = if (proc.first != null) SShop.repeatOnce
        else Txt("${setup.en} ${SShop.powerInstruction.en}", "${setup.hi} ${SShop.powerInstruction.hi}")
    BlurScreen(
        env, eye, reading, voice, header, instruction, simulated, onSkip, background = bg,
        second = SShop.stillSharp to (if (atReach(reading, rules)) ({ onFrozen(proc.stillClear(target)) }) else null),
        onBlur = { mm, t, typed ->
            val e = proc.blur(DistanceReading(mm, Confidence.MEDIUM, timestampMs = t), est, target)
            val first = proc.first
            when {
                typed -> onFrozen(e)
                first != null -> onFrozen(QuickFarProcedure.median(first, e, est, target))
                proc.looksInvalid(mm, t) -> proc = proc.repeatWith(e)
                else -> onFrozen(e)
            }
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val d = reading?.distanceMm ?: return@Canvas
            val h = Sizing.letterHeightPx(env.vm, d, env.scale, rules.quickEstimate.letterLogMar).toFloat()
                .coerceAtMost(size.width / (2f * dirs.size)).coerceAtLeast(1f)
            val total = dirs.size * h + (dirs.size - 1) * h
            dirs.forEachIndexed { i, dir -> drawTumblingE(Offset(center.x - total / 2f + h / 2f + i * 2f * h, center.y), h, dir) }
        }
    }
}

/**
 * Two-line blur test for one eye: pass A horizontal lines, pass B vertical lines, both distance-locked on white,
 * each "start close, move away, BLUR". "Still sharp at arm's length" counts as 0 D for that pattern.
 */
@Composable
private fun TwoLineScreen(
    env: TestEnv, eye: TestEye, reading: DistanceReading?, voice: VoiceController?, header: Txt, simulated: Boolean,
    onSkip: () -> Unit, onDone: (TwoLineAstig.Result) -> Unit,
) {
    val rules = env.rules
    var pass by remember { mutableIntStateOf(0) }
    var horizontalMm by remember { mutableStateOf<Double?>(null) }
    fun record(mm: Double?) {
        if (pass == 0) { horizontalMm = mm; pass = 1 } else onDone(TwoLineAstig.analyse(horizontalMm, mm))
    }
    val vertical = pass == 1
    val passHeader = Txt("${header.en} (${pass + 1}/2)", "${header.hi} (${pass + 1}/2)")
    key(pass) {
        BlurScreen(
            env, eye, reading, voice, passHeader, if (vertical) SShop.linesVertical else SShop.linesHorizontal, simulated, onSkip,
            second = SShop.stillSharp to (if (atReach(reading, rules)) ({ record(null) }) else null),
            onBlur = { mm, _, _ -> record(mm) },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val d = reading?.distanceMm ?: return@Canvas
                // Line width = the stroke of the power check's letters (distance-locked).
                val w = Sizing.arcminPx(d, 10.0.pow(rules.quickEstimate.letterLogMar), env.scale).toFloat().coerceAtLeast(1f)
                val n = 7
                val span = (2 * n - 1) * w
                val len = (span * 2.5f).coerceAtMost(min(size.width, size.height) * 0.8f)
                for (i in 0 until n) {
                    val off = -span / 2f + w / 2f + i * 2f * w
                    if (vertical) drawLine(Color.Black, Offset(center.x + off, center.y - len / 2f), Offset(center.x + off, center.y + len / 2f), w)
                    else drawLine(Color.Black, Offset(center.x - len / 2f, center.y + off), Offset(center.x + len / 2f, center.y + off), w)
                }
            }
        }
    }
}

/** Reading text letter size (logMAR), kept the same angular size at every distance. */
const val READING_TEXT_LOGMAR = 0.3

/**
 * Reading check, both eyes: distance-locked small reading text (Hindi and English). Hold far, move closer, BLUR
 * when the text stops being sharp. "Still sharp up close" (within 15 cm) uses that distance as the near point.
 */
@Composable
private fun ReadingCheckScreen(
    env: TestEnv, reading: DistanceReading?, voice: VoiceController?, header: Txt, simulated: Boolean,
    onSkip: () -> Unit, onFrozen: (Double) -> Unit,
) {
    val close = reading != null && reading.faceVisible && reading.distanceMm <= env.cfg.farPointStartMaxMm
    val density = androidx.compose.ui.platform.LocalDensity.current
    BlurScreen(
        env, TestEye.BOTH, reading, voice, header, SShop.readingInstruction, simulated, onSkip,
        second = SShop.sharpUpClose to (if (close) ({ onFrozen(reading!!.distanceMm) }) else null),
        onBlur = { mm, _, _ -> onFrozen(mm) },
    ) {
        val d = reading?.distanceMm
        if (d != null) {
            val letterPx = Sizing.letterHeightPx(env.vm, d, env.scale, READING_TEXT_LOGMAR).toFloat().coerceAtLeast(4f)
            val fontSize = with(density) { (letterPx / 0.7f).toSp() }
            Column(Modifier.align(Alignment.Center).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(SShop.readingSample.hi, fontSize = fontSize, color = Color.Black)
                Text(SShop.readingSample.en, fontSize = fontSize, color = Color.Black)
            }
        }
    }
}

/** [QuickEstimateFlow] with the real camera (asked for at once); without permission: [onSkip], or (demo) the distance slider. */
@Composable
fun CameraQuickEstimateFlow(
    lang: Lang,
    rules: RulesConfig,
    calibration: CalibrationRepository,
    plan: EyeCheckPlan,
    answers: InterviewAnswers,
    primary: Boolean,
    onDone: (QuickEstimate, VisionTests) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    voice: VoiceController? = null,
    onInstruction: (Txt) -> Unit = {},
    allowDemoWithoutCamera: Boolean = false,
    gridOnly: Boolean = false,
) {
    var demo by remember { mutableStateOf(false) }
    if (demo) {
        val fake = remember { FakeDistanceProvider() }
        QuickEstimateFlow(lang, rules, fake, calibration, plan, answers, primary, onDone, onSkip, modifier, voice, onInstruction, gridOnly)
        return
    }
    CameraPermissionGate(lang, onSkip = { if (allowDemoWithoutCamera) demo = true else onSkip() }, autoRequest = true) {
        val relaxed = remember(rules) { powerCheckCamera(rules.camera) }
        val tracker = rememberFaceTracker(relaxed)
        val provider = remember(tracker) { CameraDistanceProvider(tracker, calibration, relaxed, controlsTracker = false) }
        QuickEstimateFlow(lang, rules, provider, calibration, plan, answers, primary, onDone, onSkip, modifier, voice, onInstruction, gridOnly)
    }
}

/**
 * Camera settings for the eye check: the face is still tracked with one eye covered by the palm and while the
 * phone moves (lower detection / presence / tracking thresholds), head yaw and roll up to 10°, no sharpness gate.
 * The distance uses one visible iris when the other is covered, with the card-calibrated iris size or PD when known.
 */
fun powerCheckCamera(c: CameraConfig): CameraConfig = c.copy(
    minFaceDetectionConfidence = minOf(c.minFaceDetectionConfidence, 0.3),
    minFacePresenceConfidence = minOf(c.minFacePresenceConfidence, 0.3),
    minTrackingConfidence = minOf(c.minTrackingConfidence, 0.3),
    maxAbsYawDeg = maxOf(c.maxAbsYawDeg, 10.0),
    maxAbsRollDeg = maxOf(c.maxAbsRollDeg, 10.0),
    minLaplacianVariance = 0.0,
)
