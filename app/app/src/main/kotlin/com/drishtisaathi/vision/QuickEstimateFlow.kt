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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentSize
import kotlin.random.Random

/** Words that count as "Blur" when said during the quick estimate (Hindi, Hinglish, English). */
/** "New letters" by voice in the far check. */
val NEW_LETTERS_KEYWORDS = listOf("बदलो", "बदल दो", "badlo", "badal do", "badalo", "new letters", "change letters")
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

/** One screen of the eye check. */
internal sealed interface QuickStep {
    /** Far reading check for one eye (other eye covered). */
    data class Power(val eye: TestEye) : QuickStep
    /** Near reading check, both eyes. */
    data object Near : QuickStep
    data object Done : QuickStep
}

/** The eye check (compulsory): far reading check right eye then left, then the near reading check when planned. */
internal fun eyeCheckSteps(plan: EyeCheckPlan): List<QuickStep> = buildList {
    add(QuickStep.Power(TestEye.RIGHT))
    add(QuickStep.Power(TestEye.LEFT))
    if (plan.near) add(QuickStep.Near)
    add(QuickStep.Done)
}

private enum class QPhase { RUN, LENS_ENTRY, LENS_RUN, RESULT }

/**
 * The result line for one eye: a range, never one exact number. [optometristOnly] (URGENT and CHILD MYOPIA
 * reports): the plain range without "Your approximate power", because the customer is not given a number.
 */
fun quickEyeText(e: QuickEye, optometristOnly: Boolean = false): Txt {
    val se = e.seD
    if (se == null || e.noMyopiaAtReach) return SAnamnesis.quickNoMyopia
    val from = e.rangeFromD ?: PowerRange.around(se).fromD
    val to = e.rangeToD ?: PowerRange.around(se).toD
    val P = com.drishtisaathi.i18n.SProfile
    val lens = e.lensD
    val base = if (e.method == com.drishtisaathi.engine.profile.PowerMethod.LENS_ASSISTED && lens != null) {
        // every report shows the method: the lens, the residual through it, the total and its range
        P.methodLens.format(fmtD(lens), fmtD(e.residualD ?: (se - lens)), fmtD(se), fmtD(from), fmtD(to))
    } else (if (optometristOnly) P.optomRange else SShop.farRange).format(fmtD(from), fmtD(to), e.blurAtCm?.roundToInt() ?: 0)
    return if (e.highMyopia) Txt("${base.en}. ${P.highMyopiaNote.en}", "${base.hi}। ${P.highMyopiaNote.hi}") else base
}

/**
 * One eye's line on every report. Both eyes are always printed: a measured eye as "R: …", or for an URGENT /
 * CHILD MYOPIA report "For the optometrist only: R …"; an eye without a reading as "L: not measured – reason".
 */
fun quickEyeLine(eye: com.drishtisaathi.engine.Eye, e: QuickEye?, why: com.drishtisaathi.engine.NotMeasured?, optometristOnly: Boolean = false): Txt {
    val P = com.drishtisaathi.i18n.SProfile
    val n = if (eye == com.drishtisaathi.engine.Eye.RIGHT) "R" else "L"
    if (e == null) {
        val r = P.notMeasuredReason(why ?: com.drishtisaathi.engine.NotMeasured.NOT_DONE)
        return Txt("$n: " + P.notMeasured.en.format(r.en), "$n: " + P.notMeasured.hi.format(r.hi))
    }
    val t = quickEyeText(e, optometristOnly)
    return if (optometristOnly) Txt("${P.forOptometristOnly.en}: $n ${t.en}", "${P.forOptometristOnly.hi}: $n ${t.hi}")
    else Txt("$n: ${t.en}", "$n: ${t.hi}")
}

/** How the eye was measured, short (QR, optometrist page): "through −2.00 D lens" / "without a lens". */
fun quickMethodText(e: QuickEye): Txt {
    val P = com.drishtisaathi.i18n.SProfile
    val lens = e.lensD
    return if (e.method == com.drishtisaathi.engine.profile.PowerMethod.LENS_ASSISTED && lens != null) P.methodLensShort.format(fmtD(lens))
    else P.methodUnaidedShort
}

/**
 * Near reading check result: the add as a range (38+), or the near point against the age-expected one (no plus
 * number). [myopic]: myopia was measured for this customer, so long-sightedness is never mentioned; without the
 * distance correction on ([corrected] not true) the near point is not judged at all.
 */
fun nearResultText(
    nearPointCm: Double?, addD: Double?, expectedCm: Double?, farther: Boolean,
    myopic: Boolean = false, corrected: Boolean? = null,
): Txt = when {
    nearPointCm == null -> SAnamnesis.quickNearNone
    addD != null -> PowerRange.add(addD).let { SShop.nearAddRange.format(nearPointCm.roundToInt(), fmtD(it.fromD), fmtD(it.toD)) }
    myopic && corrected != true -> com.drishtisaathi.i18n.SProfile.nearUncorrectedMyope.format(nearPointCm.roundToInt())
    expectedCm != null && farther && myopic -> com.drishtisaathi.i18n.SProfile.nearFartherMyope.format(nearPointCm.roundToInt(), expectedCm.roundToInt())
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
    is QuickStep.Power -> header(step.eye, SShop.farCheck)
    QuickStep.Near -> header(TestEye.BOTH, SShop.readingCheck)
    QuickStep.Done -> SShop.checkDone
}

private fun eyeLine(eye: TestEye, t: Txt): Txt = Txt("${eyeName(eye).en}: ${t.en}", "${eyeName(eye).hi}: ${t.hi}")

/**
 * The eye check's result. [primary] = it is the session's power source (false next to a known power). An eye
 * without a reading keeps its reason ([rightWhy] / [leftWhy], default "eye check not done"). Long-sightedness is
 * never suggested next to a measured minus power.
 */
internal fun eyeCheckResult(
    right: QuickEye?, left: QuickEye?, near: NearCheck.Result?, primary: Boolean,
    rightWhy: com.drishtisaathi.engine.NotMeasured? = null, leftWhy: com.drishtisaathi.engine.NotMeasured? = null,
): QuickEstimate {
    val add = near?.addD?.let(PowerRange::add)
    val myopic = right?.myopic == true || left?.myopic == true
    return QuickEstimate(
        right, left, add?.centreD, near?.nearPointCm,
        possibleLongSight = near?.fartherThanExpected == true && !myopic,
        primary = primary, expectedNearPointCm = near?.expectedNearPointCm,
        addFromD = add?.fromD, addToD = add?.toD,
        rightNotMeasured = if (right == null) rightWhy ?: com.drishtisaathi.engine.NotMeasured.NOT_DONE else null,
        leftNotMeasured = if (left == null) leftWhy ?: com.drishtisaathi.engine.NotMeasured.NOT_DONE else null,
        nearWithCorrection = near?.withCorrection,
        nearFartherThanExpected = near?.fartherThanExpected,
    )
}

/** The first eye without a reading (right before left), so the check can go back to it; null = both measured. */
internal fun firstUnmeasuredEye(right: QuickEye?, left: QuickEye?): TestEye? = when {
    right == null -> TestEye.RIGHT
    left == null -> TestEye.LEFT
    else -> null
}

/**
 * The eye check (compulsory, about 2 minutes), one test style only: reading. Per eye, glasses off and the other eye
 * covered: random Sloan letters at 20/20 size, kept the same angular size at every distance (a new set only on
 * "New letters"); start close, move away, "Can't read clearly" (or "dhundhla") freezes the distance → a power range.
 * Then, when planned, a new random sentence in small print with both eyes: hold far, move closer → near point.
 * Each step has a header, a Skip button, a short result and "Do it again".
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
    onDone: (QuickEstimate) -> Unit,
    modifier: Modifier = Modifier,
    voice: VoiceController? = null,
    onInstruction: (Txt) -> Unit = {},
    /** Staff / device checks: show the far letters' size line. */
    debug: Boolean = false,
    /** This phone model's correction from verified shop records; in lens-assisted mode it corrects the residual only. */
    deviceFit: com.drishtisaathi.engine.vision.DeviceFit? = null,
    /** A red flag is already known: the power is worded "For the optometrist only" and never spoken to the customer. */
    optometristOnly: Boolean = false,
) {
    // under 18 a minus power is for the optometrist only (child myopia), so a child's lines are worded that way too
    val forOptom = optometristOnly || (answers.age ?: 99) < rules.safety.childUnderAge
    DisposableEffect(distance) {
        distance.start()
        onDispose { distance.stop() }
    }
    MaxBrightnessKeepScreenOn()
    val reading by distance.readings.collectAsState()
    val cal by calibration.calibration.collectAsState()
    val context = LocalContext.current
    val metrics = context.resources.displayMetrics
    val scale = remember(cal, metrics.xdpi, metrics.densityDpi) { Sizing.screenScale(cal, metrics.xdpi, metrics.densityDpi) }
    val env = remember(lang, rules, scale, onInstruction) { TestEnv(lang, rules, scale, onInstruction).singleRun() }
    val sentences = remember {
        ReadingSentences.parse(context.assets.open(ReadingSentences.ASSET).bufferedReader().use { it.readText() })
    }
    val simulated = distance is FakeDistanceProvider

    val steps = remember(plan) { eyeCheckSteps(plan) }
    var index by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf(QPhase.RUN) }
    var attempt by remember { mutableIntStateOf(0) }
    var right by remember { mutableStateOf<QuickEye?>(null) }
    var left by remember { mutableStateOf<QuickEye?>(null) }
    var near by remember { mutableStateOf<NearCheck.Result?>(null) }
    var nearDone by remember { mutableStateOf(false) }
    var lastSentence by remember { mutableStateOf<String?>(null) }
    val step = steps[index.coerceAtMost(steps.lastIndex)]
    // ---- lens-assisted mode (Phase B): the first pass of the eye being measured, the lens typed in, "too strong" ----
    val lensCfg = rules.lensAssisted
    var firstPass by remember { mutableStateOf<QuickEye?>(null) }
    var lensD by remember { mutableStateOf<Double?>(null) }
    var tooStrong by remember { mutableStateOf(false) }
    /** The reading through a lens that turned out too weak (residual still strong): a stronger lens is asked for once. */
    var weak by remember { mutableStateOf<QuickEye?>(null) }
    var strongerRepeats by remember { mutableIntStateOf(0) }
    /** Eyes whose far check was skipped (the report then says "not measured – skipped"). */
    var skippedEyes by remember { mutableStateOf(emptySet<TestEye>()) }
    /** A short-sighted customer: is the distance correction on for the near check? null = not asked yet. */
    var nearCorrection by remember { mutableStateOf<Boolean?>(null) }
    val P = com.drishtisaathi.i18n.SProfile

    fun resetLens() { firstPass = null; lensD = null; tooStrong = false; weak = null; strongerRepeats = 0 }
    fun next() { index = (index + 1).coerceAtMost(steps.lastIndex); phase = QPhase.RUN; attempt++; resetLens() }
    fun again() { phase = QPhase.RUN; attempt++; resetLens(); nearCorrection = null }
    fun goTo(eye: TestEye) { index = steps.indexOf(QuickStep.Power(eye)).coerceAtLeast(0); phase = QPhase.RUN; attempt++; resetLens() }
    fun setEye(eye: TestEye, e: QuickEye) { if (eye == TestEye.RIGHT) right = e else left = e; skippedEyes = skippedEyes - eye }
    fun skipEye(eye: TestEye) { skippedEyes = skippedEyes + eye; next() }
    fun why(eye: TestEye) = if (eye in skippedEyes) com.drishtisaathi.engine.NotMeasured.SKIPPED else com.drishtisaathi.engine.NotMeasured.NOT_DONE
    val myopic = right?.myopic == true || left?.myopic == true
    fun nearLine() = nearResultText(near?.nearPointCm, near?.addD, near?.expectedNearPointCm, near?.fartherThanExpected == true, myopic, near?.withCorrection)

    Column(modifier.fillMaxSize()) {
        if (voice != null) MuteRow(voice, lang)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                step == QuickStep.Done -> {
                    // Both eyes are always listed. An eye without a reading says so, and the main button goes back to it.
                    val missing = firstUnmeasuredEye(right, left)
                    val finish = { onDone(eyeCheckResult(right, left, near, primary, why(TestEye.RIGHT), why(TestEye.LEFT))) }
                    QuickInfo(
                        lang, SShop.checkDone,
                        buildList {
                            add(quickEyeLine(com.drishtisaathi.engine.Eye.RIGHT, right, why(TestEye.RIGHT), forOptom))
                            add(quickEyeLine(com.drishtisaathi.engine.Eye.LEFT, left, why(TestEye.LEFT), forOptom))
                            if (missing != null) add(P.bothEyesNeeded)
                            if (nearDone) add(nearLine())
                        },
                        primary = if (missing == null) SShop.next to finish else {
                            val w = if (missing == TestEye.RIGHT) SShop.rightWord else SShop.leftWord
                            Txt(P.measureEyeNow.en.format(w.en), P.measureEyeNow.hi.format(w.hi)) to { goTo(missing) }
                        },
                        skip = if (missing == null) null else P.continueWithoutEye to finish,
                    )
                }
                phase == QPhase.RESULT -> QuickInfo(
                    lang, stepHeader(step),
                    listOfNotNull(
                        when (step) {
                            is QuickStep.Power -> (if (step.eye == TestEye.RIGHT) right else left)?.let { quickEyeText(it, forOptom) } ?: SShop.skipped
                            QuickStep.Near -> nearLine()
                            else -> null
                        },
                    ),
                    primary = SShop.next to { next() },
                    skip = SShop.doAgain to { again() },
                    // a number that is for the optometrist only is not read out to the customer
                    speak = if (forOptom && step is QuickStep.Power) null else env.onInstruction,
                )
                step is QuickStep.Power && phase == QPhase.LENS_ENTRY -> {
                    val first = firstPass
                    if (first?.seD == null) LaunchedEffect(Unit) { phase = QPhase.RESULT }
                    else com.drishtisaathi.profile.LensEntryScreen(
                        lang, header(step.eye, com.drishtisaathi.i18n.SProfile.lensModeTitle), first.seD!!, lensCfg, tooStrong, env.onInstruction,
                        onStart = { d -> lensD = d; tooStrong = false; attempt++; phase = QPhase.LENS_RUN },
                        // no lens at hand: the first reading stays (or the reading through the weaker lens), never a guess
                        onNoLens = { if (weak == null) setEye(step.eye, PowerRange.unaided(first, lensCfg)); phase = QPhase.RESULT },
                        weak = weak,
                    )
                }
                step is QuickStep.Power && phase == QPhase.LENS_RUN -> key(step, attempt, "lens") {
                    FarLetterScreen(
                        env, step.eye, reading, voice, header(step.eye, com.drishtisaathi.i18n.SProfile.lensModeTitle), simulated, debug,
                        onSkip = { if (weak == null) setEye(step.eye, PowerRange.unaided(firstPass!!, lensCfg)); phase = QPhase.RESULT },
                        throughLens = lensD,
                    ) { through ->
                        val e = PowerRange.lensAssistedEye(firstPass!!, through, lensD ?: 0.0, lensCfg, deviceFit)
                        when {
                            // letters sharp at every distance: the lens is too strong, ask for a weaker one
                            e == null -> { tooStrong = true; phase = QPhase.LENS_ENTRY }
                            // the blur point through the lens is still very close: the lens is too weak, repeat once with a stronger one
                            com.drishtisaathi.engine.profile.LensAssisted.needsStrongerLens(e.residualD, strongerRepeats, lensCfg) -> {
                                setEye(step.eye, e); weak = e; strongerRepeats++; tooStrong = false; phase = QPhase.LENS_ENTRY
                            }
                            else -> { setEye(step.eye, e); phase = QPhase.RESULT }
                        }
                    }
                }
                step is QuickStep.Power -> key(step, attempt) {
                    FarLetterScreen(env, step.eye, reading, voice, stepHeader(step), simulated, debug, onSkip = { skipEye(step.eye) }) { eye ->
                        // from −3.75 D the blur point is so close that 1 cm changes the result a lot: repeat through a lens
                        if (com.drishtisaathi.engine.profile.LensAssisted.needed(eye.seD.takeIf { !eye.noMyopiaAtReach }, lensCfg)) {
                            firstPass = eye; setEye(step.eye, PowerRange.unaided(eye, lensCfg)); phase = QPhase.LENS_ENTRY
                        } else { setEye(step.eye, eye); phase = QPhase.RESULT }
                    }
                }
                // A short-sighted customer reads the near text with the distance correction on; staff confirm it first.
                step == QuickStep.Near && myopic && nearCorrection == null -> QuickInfo(
                    lang, P.nearCorrectionTitle, listOf(P.nearCorrectionBody),
                    primary = P.nearCorrectionOn to { nearCorrection = true },
                    skip = P.nearCorrectionOff to { nearCorrection = false },
                    speak = env.onInstruction,
                )
                step == QuickStep.Near -> key(step, attempt) {
                    val sentence = remember { sentences.pick(lang, Random(System.nanoTime()), lastSentence).also { lastSentence = it } }
                    val farSe = listOfNotNull(right?.seD, left?.seD).takeIf { it.isNotEmpty() }?.average()
                    NearSentenceScreen(env, reading, voice, stepHeader(step), simulated, sentence, onSkip = { next() }) { mm ->
                        nearDone = true
                        near = NearCheck.analyse(mm, answers.age, farSe, answers, rules, myopic, withCorrection = nearCorrection == true)
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
private fun BlurKeyword(voice: VoiceController?, onBlur: () -> Unit, onNewLetters: (() -> Unit)? = null) {
    val blur by rememberUpdatedState(onBlur)
    val fresh by rememberUpdatedState(onNewLetters)
    DisposableEffect(voice, onNewLetters != null) {
        val extra = if (onNewLetters != null) listOf(NEW_LETTERS_KEYWORDS to { fresh?.invoke(); Unit }) else emptyList()
        voice?.listenForKeyword(BLUR_KEYWORDS, extra = extra) { blur() }
        onDispose { voice?.stopKeyword() }
    }
}

/**
 * Valid distance readings of the last [keepMs] (face found). The tap uses the median of the last [useMs], so it
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
 * The one screen design of the eye check (far and near): white background, the live distance in cm only, the big
 * "Can't read clearly" button always enabled (and "dhundhla"), the distance from the median of the last second's
 * readings; with none in 1.5 s "Distance not caught…"; a green / grey dot for live measuring; staff can type the
 * distance from a tape. [second]: an extra button and its action (null = disabled). [onTap] gets the distance in mm,
 * the time, and whether it was typed.
 */
@Composable
private fun ReadScreen(
    env: TestEnv, eye: TestEye, reading: DistanceReading?, voice: VoiceController?, header: Txt, instruction: Txt,
    simulated: Boolean, onSkip: () -> Unit,
    second: Pair<Txt, (() -> Unit)?>? = null,
    /** Far check: a 2-line instruction (full text behind "How to") and the target in its own fixed panel. */
    shortInstruction: Txt? = null,
    debugLine: String? = null,
    /** Far check: "New letters" (also by voice, "badlo"). */
    onNewLetters: (() -> Unit)? = null,
    onTap: (mm: Double, atMs: Long, typed: Boolean) -> Unit,
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

    val tapNow: () -> Unit = {
        val t = System.currentTimeMillis()
        val mm = if (simulated) reading?.takeIf { it.faceVisible }?.distanceMm else buffer.atTap(t)
        if (mm == null) notCaught = true else { notCaught = false; onTap(mm, t, false) }
    }
    BlurKeyword(voice, tapNow, onNewLetters)

    val controls: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(14.dp).background(if (live) Color(0xFF2E7D32) else Color(0xFF9E9E9E), CircleShape))
                Text((if (live) SShop.measuring else SShop.noFaceDot)[l], style = MaterialTheme.typography.bodyMedium)
            }
            if (notCaught) Text(SShop.notCaught[l], color = Color(0xFFB26A00), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold)
            BigButton(SShop.cantRead[l], selected = true, onClick = tapNow)
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
                        onClick = { cm?.let { onTap(it * 10.0, System.currentTimeMillis(), true) } })
                }
            }
    }
    if (shortInstruction != null) PanelLayout(env, header, shortInstruction, instruction, reading, debugLine, onSkip, target, controls, onNewLetters)
    else TestLayout(
        env, SAnamnesis.quickTitle, eye, instruction,
        reading, status, 0, 1, onSkip, background = Color.White, bigDistance = true, header = header, quiet = true,
        target = target, controls = controls,
    )
}

/**
 * Far check layout: header, a 2-line instruction with a "How to" link (the full text is spoken once and shown in a
 * dialog), the live distance, then the letters in their own light grey panel (at least 35% of the screen height,
 * white inside), then the buttons. One scrolling column: nothing overlaps.
 */
@Composable
private fun PanelLayout(
    env: TestEnv, header: Txt, short: Txt, full: Txt, reading: DistanceReading?, debugLine: String?, onSkip: () -> Unit,
    target: @Composable BoxScope.() -> Unit, controls: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
    onNewLetters: (() -> Unit)? = null,
) {
    val l = env.lang
    var showHow by remember { mutableStateOf(false) }
    LaunchedEffect(full) { env.onInstruction(full) }
    val panelHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * PANEL_SHARE).dp
    Column(
        Modifier.fillMaxSize().background(Color.White).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(header[l], color = Color.Black, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(short[l], Modifier.weight(1f), color = Color.Black, style = MaterialTheme.typography.bodyLarge,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            TextButton(onClick = { showHow = true }) { Text(SShop.howTo[l]) }
        }
        val dist = reading?.takeIf { it.faceVisible }?.let { SVision.distanceNow.format((it.distanceMm / 10.0).roundToInt())[l] } ?: SVision.distanceUnknown[l]
        Text(dist, color = Color.Black, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        debugLine?.let { Text(it, color = Color(0xFF616161), style = MaterialTheme.typography.bodySmall) }
        Box(
            Modifier.fillMaxWidth().height(panelHeight)
                .background(Color(0xFFE0E0E0), androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).padding(8.dp)
                .background(Color.White, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
            content = target,
        )
        // "New letters": a small refresh button right under the letter panel (also by voice, "badlo")
        onNewLetters?.let { NewLettersButton(SShop.newLettersShort[l], it, Modifier.align(Alignment.End)) }
        Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = controls)
        TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(SVision.skipTest[l], color = Color.Black) }
    }
    if (showHow) androidx.compose.material3.AlertDialog(
        onDismissRequest = { showHow = false },
        confirmButton = { TextButton(onClick = { showHow = false }) { Text(SShop.ok[l]) } },
        title = { Text(SShop.howTo[l]) },
        text = { Text(full[l], style = MaterialTheme.typography.bodyLarge) },
    )
}

/** A small icon button (refresh arrow + "New") for a new set of letters. */
@Composable
private fun NewLettersButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick, modifier = modifier.height(40.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
    ) {
        val c = MaterialTheme.colorScheme.primary
        androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
            val w = size.width
            drawArc(c, 40f, 290f, false, androidx.compose.ui.geometry.Offset(w * 0.12f, w * 0.12f),
                androidx.compose.ui.geometry.Size(w * 0.76f, w * 0.76f), style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.13f))
            drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(w, w * 0.4f); lineTo(w * 0.6f, w * 0.4f); lineTo(w * 0.9f, w * 0.05f); close() }, c)
        }
        Text(label, Modifier.padding(start = 6.dp), maxLines = 1, style = MaterialTheme.typography.labelLarge)
    }
}

/** The far letter panel takes at least this share of the screen height. */
const val PANEL_SHARE = 0.35f

private fun atReach(r: DistanceReading?, rules: RulesConfig) =
    r != null && r.faceVisible && r.distanceMm >= rules.farPoint.selfHeldReachM * 1000.0

/** Letters at [logMar] for distance [mm], as a font size (cap height ≈ 0.71 of the font size). */
@Composable
private fun letterFontSize(env: TestEnv, mm: Double, logMar: Double, minPx: Float): androidx.compose.ui.unit.TextUnit {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val px = Sizing.letterHeightPx(env.vm, mm, env.scale, logMar).toFloat().coerceAtLeast(minPx)
    return with(density) { (px / 0.71f).toSp() }
}

/** Far letters: 20/20 size (logMAR 0.0), distance-locked. */
const val FAR_LETTER_LOGMAR = 0.0

/**
 * Far reading check for one eye: 5 random Sloan letters at 20/20 size; they stay the same while the phone moves, a
 * new set only on "New letters" (or "badlo"). Start close, move away,
 * "Can't read clearly" freezes the distance → a power range. One automatic repeat when the tap looks invalid (too
 * close, or too soon after being close), then the mean of the two. A distance typed from a tape is used as it is.
 */
@Composable
private fun FarLetterScreen(
    env: TestEnv, eye: TestEye, reading: DistanceReading?, voice: VoiceController?, header: Txt, simulated: Boolean,
    debug: Boolean, onSkip: () -> Unit,
    /** Lens-assisted mode: the minus lens (D) staff hold in front of this eye; null = the normal first pass. */
    throughLens: Double? = null,
    onFrozen: (QuickEye) -> Unit,
) {
    val rules = env.rules
    val rnd = remember { Random(System.nanoTime()) }
    // The last valid distance stays in use while the face is briefly lost, so the letters never disappear.
    var lastMm by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(reading) { reading?.takeIf { it.faceVisible && it.distanceMm > 0 }?.let { lastMm = it.distanceMm } }
    var panelWidthPx by remember { mutableStateOf(0.0) }
    var letters by remember { mutableStateOf(LetterSet.start(rnd)) }
    var proc by remember { mutableStateOf(QuickFarProcedure()) }
    var firstMm by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(reading) {
        proc = proc.onReading(reading, rules, System.currentTimeMillis())
    }

    // Spoken once per attempt: glasses off, which eye to cover, and how to move the phone.
    val other = if (eye == TestEye.RIGHT) SShop.leftWord else SShop.rightWord
    val setup = Txt(SShop.glassesOffCover.en.format(other.en), SShop.glassesOffCover.hi.format(other.hi))
    val lensText = throughLens?.let { com.drishtisaathi.i18n.SProfile.lensInstruction.format(fmtD(it)) }
    val instruction = if (firstMm != null) SShop.repeatOnce
        else lensText ?: Txt("${setup.en} ${SShop.farInstruction.en}", "${setup.hi} ${SShop.farInstruction.hi}")
    val short = if (firstMm != null) SShop.repeatOnce else lensText ?: SShop.farShort.format(other.en).let {
        Txt(it.en, SShop.farShort.hi.format(other.hi))
    }
    val shown = FarLetters.layout(letters.letters, lastMm, env.scale, rules.farPoint, panelWidthPx.takeIf { it > 0 } ?: 1000.0)
    val debugLine = if (debug) "letter height %d px / %.1f mm at %d cm".format(
        java.util.Locale.US, shown.letterPx.roundToInt(), shown.letterMm, (shown.distanceMm / 10.0).roundToInt(),
    ) else null
    ReadScreen(
        env, eye, reading, voice, header, instruction, simulated, onSkip,
        shortInstruction = short, debugLine = debugLine,
        onNewLetters = { letters = letters.next(rnd) },
        second = SShop.stillReadableReach to (if (atReach(reading, rules)) ({ onFrozen(QuickEye(null, null, QuickTarget.WHITE, noMyopiaAtReach = true)) }) else null),
        onTap = { mm, t, typed ->
            val first = firstMm
            when {
                typed -> onFrozen(PowerRange.farEye(mm))
                first != null -> onFrozen(PowerRange.farEyeMedian(first, mm))
                proc.looksInvalid(mm, t) -> { firstMm = mm; proc = QuickFarProcedure() }
                else -> onFrozen(PowerRange.farEye(mm))
            }
        },
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val w = with(density) { maxWidth.toPx() }.toDouble()
            LaunchedEffect(w) { panelWidthPx = w }
            val hDp = with(density) { shown.letterPx.toFloat().toDp() }
            val font = with(density) { (shown.letterPx.toFloat() / 0.71f).toSp() }
            // Black bold Sloan letters, spacing = 1 letter width.
            Row(horizontalArrangement = Arrangement.spacedBy(hDp), verticalAlignment = Alignment.CenterVertically) {
                shown.letters.forEach { c ->
                    Box(Modifier.size(hDp), contentAlignment = Alignment.Center) {
                        Text(
                            c.toString(), Modifier.wrapContentSize(unbounded = true),
                            fontSize = font, fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif,
                            fontWeight = FontWeight.Bold, color = Color.Black, maxLines = 1, softWrap = false,
                        )
                    }
                }
            }
        }
    }
}



/** Near text letter size (logMAR), small print kept the same angular size at every distance. */
const val READING_TEXT_LOGMAR = 0.3

/**
 * Near reading check, both eyes: one random short sentence (chosen language) in small print, distance-locked. Hold
 * far, move closer, "Can't read clearly" when the words blur. "Still readable up close" (within 15 cm) uses that
 * distance as the near point.
 */
@Composable
private fun NearSentenceScreen(
    env: TestEnv, reading: DistanceReading?, voice: VoiceController?, header: Txt, simulated: Boolean, sentence: String,
    onSkip: () -> Unit, onFrozen: (Double) -> Unit,
) {
    val close = reading != null && reading.faceVisible && reading.distanceMm <= env.cfg.farPointStartMaxMm
    ReadScreen(
        env, TestEye.BOTH, reading, voice, header, SShop.nearInstruction, simulated, onSkip,
        second = SShop.stillReadableClose to (if (close) ({ onFrozen(reading!!.distanceMm) }) else null),
        onTap = { mm, _, _ -> onFrozen(mm) },
    ) {
        val d = reading?.distanceMm
        if (d != null) {
            Text(
                sentence, Modifier.align(Alignment.Center).padding(8.dp),
                fontSize = letterFontSize(env, d, READING_TEXT_LOGMAR, 4f), color = Color.Black,
            )
        }
    }
}

/** [QuickEstimateFlow] with the real camera (asked for at once); without permission: [onSkip], or (demo / staff) the distance slider. */
@Composable
fun CameraQuickEstimateFlow(
    lang: Lang,
    rules: RulesConfig,
    calibration: CalibrationRepository,
    plan: EyeCheckPlan,
    answers: InterviewAnswers,
    primary: Boolean,
    onDone: (QuickEstimate) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    voice: VoiceController? = null,
    onInstruction: (Txt) -> Unit = {},
    allowDemoWithoutCamera: Boolean = false,
    debug: Boolean = false,
    deviceFit: com.drishtisaathi.engine.vision.DeviceFit? = null,
    optometristOnly: Boolean = false,
) {
    var demo by remember { mutableStateOf(false) }
    if (demo) {
        val fake = remember { FakeDistanceProvider() }
        QuickEstimateFlow(lang, rules, fake, calibration, plan, answers, primary, onDone, modifier, voice, onInstruction, debug, deviceFit, optometristOnly)
        return
    }
    CameraPermissionGate(lang, onSkip = { if (allowDemoWithoutCamera) demo = true else onSkip() }, autoRequest = true) {
        val relaxed = remember(rules) { powerCheckCamera(rules.camera) }
        val tracker = rememberFaceTracker(relaxed)
        val provider = remember(tracker) { CameraDistanceProvider(tracker, calibration, relaxed, controlsTracker = false) }
        QuickEstimateFlow(lang, rules, provider, calibration, plan, answers, primary, onDone, modifier, voice, onInstruction, debug, deviceFit, optometristOnly)
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
