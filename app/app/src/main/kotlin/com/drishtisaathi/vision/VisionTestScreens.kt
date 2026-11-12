package com.drishtisaathi.vision

import com.drishtisaathi.engine.vision.FarPointProtocol
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.config.VisionTestConfig
import com.drishtisaathi.engine.vision.VisionMath
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.SVision
import com.drishtisaathi.i18n.Txt
import com.drishtisaathi.ui.BigButton
import com.drishtisaathi.ui.Teal
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** What every test screen needs; built once by [VisionTestFlow]. */
class TestEnv(
    val lang: Lang,
    val rules: RulesConfig,
    val scale: ScreenScale,
    /** Called with each new instruction or pause message, so the voice track can speak it. */
    val onInstruction: (Txt) -> Unit,
    /** The customer eye check runs each test once: a copy of the config with repeats = 1. */
    private val cfgOverride: VisionTestConfig? = null,
) {
    val cfg: VisionTestConfig get() = cfgOverride ?: rules.visionTests
    fun singleRun(): TestEnv = TestEnv(lang, rules, scale, onInstruction, rules.visionTests.copy(repeats = 1))
    val vm = VisionMath(rules)
}

private fun pauseText(s: DistanceStatus): Txt? = when (s) {
    DistanceStatus.OK -> null
    DistanceStatus.NO_READING -> SVision.pauseNoReading
    DistanceStatus.NO_FACE -> SVision.pauseNoFace
    DistanceStatus.TOO_CLOSE -> SVision.pauseTooClose
    DistanceStatus.TOO_FAR -> SVision.pauseTooFar
}

private fun eyeInstruction(eye: TestEye): Txt = when (eye) {
    TestEye.RIGHT -> SVision.coverLeft
    TestEye.LEFT -> SVision.coverRight
    TestEye.BOTH -> SVision.bothEyes
}

/**
 * Shared test layout: title, eye to cover, instruction, live distance, the target area (replaced by a pause message
 * while the face is lost or the distance is out of range) and the answer controls.
 */
@Composable
internal fun TestLayout(
    env: TestEnv,
    title: Txt,
    eye: TestEye,
    instruction: Txt,
    reading: DistanceReading?,
    status: DistanceStatus,
    repeatIndex: Int,
    repeats: Int,
    onSkip: () -> Unit,
    background: Color = Color.White,
    subtitle: Txt? = null,
    holdAtMm: Double? = null,
    /** Show the live distance large (quick estimate), without the repeat counter. */
    bigDistance: Boolean = false,
    /** Customer eye check: "Right eye: Power check" replaces the title line. */
    header: Txt? = null,
    /**
     * Customer eye check: quality gates run silently (no pause card, nothing spoken); only when the face has been
     * lost for 5 s one hint shows: "Keep your face in the frame".
     */
    quiet: Boolean = false,
    target: @Composable BoxScope.() -> Unit,
    controls: @Composable ColumnScope.() -> Unit,
) {
    val l = env.lang
    val fg = if (background.luminance() < 0.4f) Color.White else Color.Black
    val pause = if (quiet) null else pauseText(status)
    LaunchedEffect(instruction) { env.onInstruction(instruction) }
    LaunchedEffect(pause) { pause?.let(env.onInstruction) }
    val faceLost = status == DistanceStatus.NO_FACE || status == DistanceStatus.NO_READING
    var showFaceHint by remember { mutableStateOf(false) }
    LaunchedEffect(quiet && faceLost) {
        showFaceHint = false
        if (quiet && faceLost) { kotlinx.coroutines.delay(FACE_HINT_AFTER_MS); showFaceHint = true }
    }

    Column(
        Modifier.fillMaxSize().background(background).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (header != null) Text(header[l], color = fg, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        else Text(title[l] + (subtitle?.let { " · " + it[l] } ?: ""), color = fg, style = MaterialTheme.typography.titleLarge)
        if (header == null || eye != TestEye.BOTH) {
            Text(eyeInstruction(eye)[l], color = fg, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Text(instruction[l], color = fg, style = MaterialTheme.typography.bodyLarge)
        val dist = reading?.let { SVision.distanceNow.format((it.distanceMm / 10.0).roundToInt())[l] } ?: SVision.distanceUnknown[l]
        val hold = holdAtMm?.let { "  ·  " + SVision.holdAt.format((it / 10.0).roundToInt())[l] } ?: ""
        if (bigDistance || quiet) Text(dist + hold, color = fg, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        else Text(
            dist + hold + "  ·  " + SVision.repeatOf.format(repeatIndex + 1, repeats)[l],
            color = fg, style = MaterialTheme.typography.bodyMedium,
        )
        if (showFaceHint) Text(com.drishtisaathi.i18n.SShop.faceHint[l], color = Color(0xFFB26A00),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (pause == null) target()
            else Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))) {
                Text(pause[l], Modifier.padding(24.dp), style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center, color = Color.Black)
            }
        }
        Column(
            Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = controls,
        )
        TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(SVision.skipTest[l], color = fg)
        }
    }
}

/** Customer eye check: the face-lost hint appears after this long. */
const val FACE_HINT_AFTER_MS = 5_000L

// =================================================================================================
// 2. Far point
// =================================================================================================

@Composable
fun FarPointTestScreen(env: TestEnv, eye: TestEye, reading: DistanceReading?, onSkip: () -> Unit, onComplete: (FarPointProcedure) -> Unit) {
    val cfg = env.cfg
    var proc by remember { mutableStateOf(FarPointProcedure.start(env.rules)) }
    LaunchedEffect(reading) { proc = proc.onReading(reading, cfg) }
    val status = DistanceGate.check(reading, null, null)
    val white = proc.colour == FarColour.WHITE
    val bg = rgbColor(if (white) cfg.farPointWhiteRgb else cfg.farPointBlueRgb)
    val rnd = remember { Random(System.nanoTime()) }
    val dirs = remember(proc.colour, proc.repeatIndex) { List(cfg.farPointLetterCount) { EDirection.random(rnd) } }

    val step = when {
        proc.staffHeld -> if (proc.seenClose) SVision.farStaffMoveAway else SVision.farStaffBringClose
        else -> if (proc.seenClose) SVision.farMoveAway else SVision.farBringClose
    }
    // rules_update.md rules 3 and 4: say why an extra reading is asked for, before the phone is brought close again
    val notice = when {
        proc.seenClose -> null
        proc.why == FarPointProtocol.Why.THIRD_READING -> SVision.farThirdReading
        proc.why == FarPointProtocol.Why.RETAKE && proc.repeatIndex == 0 && !white -> SVision.farRetake
        else -> null
    }
    val instruction = notice?.let { Txt(it.en + " " + step.en, it.hi + " " + step.hi) } ?: step

    TestLayout(
        env, SVision.farTitle, eye, instruction, reading, status,
        proc.repeatIndex, maxOf(cfg.repeats, proc.repeatIndex + 1), onSkip, background = bg,
        subtitle = if (white) SVision.farWhite else SVision.farBlue,
        target = {
            Canvas(Modifier.fillMaxSize()) {
                val d = reading?.distanceMm ?: return@Canvas
                val h = Sizing.letterHeightPx(env.vm, d, env.scale, cfg.farPointLetterLogMar).toFloat()
                    .coerceAtMost(size.width / (2f * dirs.size)).coerceAtLeast(1f)
                val gap = h
                val total = dirs.size * h + (dirs.size - 1) * gap
                dirs.forEachIndexed { i, dir ->
                    drawTumblingE(Offset(center.x - total / 2f + h / 2f + i * (h + gap), center.y), h, dir)
                }
            }
        },
        controls = {
            BigButton(SVision.farBlurred[env.lang], enabled = status == DistanceStatus.OK && proc.canMarkBlur(reading, cfg), selected = true,
                onClick = { val n = proc.markBlur(reading!!, cfg); if (n.isDone) onComplete(n) else proc = n })
            // rule 1: still clear at selfHeldReachM → staff hold the phone; "still clear" only counts beyond staffHeldMaxM
            if (!proc.staffHeld) {
                BigButton(SVision.farCannotReach[env.lang], enabled = status == DistanceStatus.OK && proc.canOfferStaffHeld(reading),
                    onClick = { proc = proc.startStaffHeld() })
            } else {
                BigButton(SVision.farStillClear[env.lang], enabled = status == DistanceStatus.OK && proc.canMarkNoBlur(reading, cfg),
                    onClick = { val n = proc.markNoBlur(reading!!, cfg); if (n.isDone) onComplete(n) else proc = n })
            }
        },
    )
}

// =================================================================================================
// 3. Fogged clock dial
// =================================================================================================

@Composable
fun ClockDialTestScreen(
    env: TestEnv, eye: TestEye, targetMm: Double, reading: DistanceReading?, onSkip: () -> Unit,
    header: Txt? = null, quiet: Boolean = false, onComplete: (ClockDialProcedure) -> Unit,
) {
    val cfg = env.cfg
    var proc by remember { mutableStateOf(ClockDialProcedure(cfg.repeats)) }
    val status = DistanceGate.check(reading, targetMm - cfg.clockDialToleranceMm, targetMm + cfg.clockDialToleranceMm)
    val ok = status == DistanceStatus.OK
    val answer: (Int?) -> Unit = { m -> val n = proc.answer(m); if (n.isDone) onComplete(n) else proc = n }
    val currentAnswer by rememberUpdatedState(answer)
    val measurer = rememberTextMeasurer()
    val instruction = if (proc.repeatIndex > 0) SVision.lookAway else SVision.dialQuestion

    TestLayout(
        env, SVision.dialTitle, eye, instruction, reading, status, proc.repeatIndex, cfg.repeats, onSkip, holdAtMm = targetMm,
        header = header, quiet = quiet,
        target = {
            Canvas(
                Modifier.fillMaxSize().pointerInput(proc.repeatIndex) {
                    detectTapGestures { p ->
                        val r = min(size.width, size.height) / 2f
                        ClockDialAnalysis.meridianFromTap((p.x - size.width / 2f).toDouble(), (p.y - size.height / 2f).toDouble(), r * 0.12)
                            ?.let { currentAnswer(it) }
                    }
                },
            ) {
                val d = reading?.distanceMm ?: return@Canvas
                val lw = Sizing.arcminPx(d, cfg.clockDialLineArcmin, env.scale).toFloat().coerceAtLeast(1f)
                val r = min(size.width, size.height) / 2f
                val inner = r * 0.18f
                val outer = r * 0.80f
                for (hour in 0 until 12) {
                    val a = Math.toRadians(hour * 30.0)
                    val ux = sin(a).toFloat(); val uy = -cos(a).toFloat() // along the spoke
                    val px = -uy; val py = ux // perpendicular
                    for (k in -1..1) {
                        val off = k * 2.5f * lw
                        drawLine(
                            Color.Black,
                            Offset(center.x + ux * inner + px * off, center.y + uy * inner + py * off),
                            Offset(center.x + ux * outer + px * off, center.y + uy * outer + py * off),
                            strokeWidth = lw,
                        )
                    }
                    val label = if (hour == 0) "12" else hour.toString()
                    val layout = measurer.measure(label, TextStyle(fontSize = 18.sp, color = Teal, fontWeight = FontWeight.Bold, fontFamily = com.drishtisaathi.ui.NotoFamily))
                    val c = Offset(center.x + ux * r * 0.92f, center.y + uy * r * 0.92f)
                    drawText(layout, topLeft = Offset(c.x - layout.size.width / 2f, c.y - layout.size.height / 2f))
                }
            }
        },
        controls = {
            for (row in 0..1) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in row * 3 until row * 3 + 3) {
                        val (a, b) = ClockDialAnalysis.hoursOf(i)
                        OutlinedButton(onClick = { answer(i) }, enabled = ok, modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            contentPadding = PaddingValues(4.dp), shape = RoundedCornerShape(12.dp)) {
                            Text("$a–$b", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            BigButton(SVision.dialAllSame[env.lang], enabled = ok, onClick = { answer(null) })
        },
    )
}

// =================================================================================================
// 4. Near point push-up
// =================================================================================================

@Composable
fun NearPointTestScreen(
    env: TestEnv, reading: DistanceReading?, seD: Double?, onSkip: () -> Unit, onComplete: (NearPointProcedure) -> Unit,
) {
    val cfg = env.cfg
    val seMeasured = seD != null
    var proc by remember { mutableStateOf(NearPointProcedure.start(cfg, seD)) }
    LaunchedEffect(reading) { proc = proc.onReading(reading, cfg) }
    val status = DistanceGate.check(reading, null, null)
    val rnd = remember { Random(System.nanoTime()) }
    val dirs = remember(proc.repeatIndex) { List(10) { EDirection.random(rnd) } }

    TestLayout(
        env, SVision.nearTitle, TestEye.BOTH, if (proc.seenFar) SVision.nearMoveIn else SVision.nearStartFar, reading, status,
        proc.repeatIndex, cfg.repeats, onSkip,
        subtitle = if (seMeasured) null else SVision.nearNoSe,
        holdAtMm = if (proc.seenFar) null else proc.startMinMm,
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
            BigButton(SVision.nearBlurred[env.lang], enabled = status == DistanceStatus.OK && proc.canMarkBlur(reading, cfg), selected = true,
                onClick = { val n = proc.markBlur(reading!!, cfg); if (n.isDone) onComplete(n) else proc = n })
            BigButton(SVision.nearOutOfReach[env.lang], enabled = status == DistanceStatus.OK && proc.canMarkOutOfReach(reading, cfg),
                onClick = { val n = proc.markOutOfReach(reading!!, cfg); if (n.isDone) onComplete(n) else proc = n })
        },
    )
}

// =================================================================================================
// 1. Near acuity
// =================================================================================================

@Composable
fun NearAcuityTestScreen(env: TestEnv, eye: TestEye, reading: DistanceReading?, onSkip: () -> Unit, onComplete: (AcuityProcedure) -> Unit) {
    val cfg = env.cfg
    val rnd = remember { Random(System.nanoTime()) }
    var proc by remember { mutableStateOf(AcuityProcedure.start(cfg, EDirection.random(rnd))) }
    val status = DistanceGate.check(reading, cfg.nearAcuityMinMm, cfg.nearAcuityMaxMm)
    val ok = status == DistanceStatus.OK
    val answer: (EDirection?) -> Unit = { dir ->
        val n = proc.answer(dir, EDirection.random(rnd, proc.shown), reading!!.confidence)
        if (n.isDone) onComplete(n) else proc = n
    }

    TestLayout(
        env, SVision.acuityTitle, eye, SVision.acuityQuestion, reading, status, proc.repeatIndex, cfg.repeats, onSkip,
        target = {
            Canvas(Modifier.fillMaxSize()) {
                val d = reading?.distanceMm ?: return@Canvas
                val px = Sizing.letterHeightPx(env.vm, d, env.scale, proc.staircase.level)
                val drawn = Sizing.fit(px, min(size.width, size.height) * 0.9).px.toFloat().coerceAtLeast(1f)
                drawTumblingE(center, drawn, proc.shown)
            }
        },
        controls = {
            val arrow: @Composable (Txt, String, EDirection, Modifier) -> Unit = { t, symbol, dir, m ->
                ArrowButton("$symbol ${t[env.lang]}", ok, m) { answer(dir) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                arrow(SVision.arrowUp, "↑", EDirection.UP, Modifier.width(150.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                arrow(SVision.arrowLeft, "←", EDirection.LEFT, Modifier.weight(1f))
                arrow(SVision.arrowRight, "→", EDirection.RIGHT, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                arrow(SVision.arrowDown, "↓", EDirection.DOWN, Modifier.width(150.dp))
            }
            TextButton(onClick = { answer(null) }, enabled = ok, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(SVision.cannotSee[env.lang])
            }
        },
    )
}

// =================================================================================================
// 5. Amsler grid
// =================================================================================================

@Composable
fun AmslerTestScreen(
    env: TestEnv, eye: TestEye, reading: DistanceReading?, onSkip: () -> Unit,
    header: Txt? = null, quiet: Boolean = false,
    /** Customer eye check: a plain yes / no, without marking where the lines look wrong. */
    yesNoOnly: Boolean = false,
    onComplete: (AmslerProcedure) -> Unit,
) {
    val cfg = env.cfg
    var proc by remember { mutableStateOf(AmslerProcedure(cfg.repeats)) }
    var marking by remember { mutableStateOf(false) }
    val status = DistanceGate.check(reading, cfg.amslerMinMm, cfg.amslerMaxMm)
    val ok = status == DistanceStatus.OK
    val finish: (Boolean) -> Unit = { yes ->
        val n = proc.answer(yes); marking = false
        if (n.isDone) onComplete(n) else proc = n
    }
    // Grid side in px, shared between drawing and tap handling.
    val gridPx = remember { FloatArray(1) }
    val currentMarking by rememberUpdatedState(marking)

    TestLayout(
        env, SVision.amslerTitle, eye,
        if (marking) SVision.amslerMarkHint else if (yesNoOnly) com.drishtisaathi.i18n.SShop.gridInstruction else SVision.amslerQuestion,
        reading, status, proc.repeatIndex, cfg.repeats, onSkip, header = header, quiet = quiet,
        target = {
            Canvas(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures { p ->
                        val side = gridPx[0]
                        if (!currentMarking || side <= 0f) return@detectTapGestures
                        val x = (p.x - size.width / 2f) / side
                        val y = (p.y - size.height / 2f) / side
                        if (x in -0.5f..0.5f && y in -0.5f..0.5f) proc = proc.mark(GridMark(x, y))
                    }
                },
            ) {
                val d = reading?.distanceMm ?: return@Canvas
                val sq = Sizing.arcminPx(d, cfg.amslerSquareDeg * 60.0, env.scale).toFloat().coerceAtLeast(4f)
                val n = (floor(min(size.width, size.height) / sq).toInt() / 2 * 2).coerceIn(2, 20)
                val side = n * sq
                gridPx[0] = side
                val left = center.x - side / 2f; val top = center.y - side / 2f
                for (i in 0..n) {
                    drawLine(Color.Black, Offset(left + i * sq, top), Offset(left + i * sq, top + side), strokeWidth = 2f)
                    drawLine(Color.Black, Offset(left, top + i * sq), Offset(left + side, top + i * sq), strokeWidth = 2f)
                }
                drawCircle(Color.Black, radius = sq * 0.25f, center = center)
                proc.marks.forEach { m ->
                    drawCircle(Color(0xFFD32F2F), radius = sq * 0.6f, center = Offset(center.x + m.x * side, center.y + m.y * side), alpha = 0.6f)
                }
            }
        },
        controls = {
            if (marking) {
                BigButton(SVision.done[env.lang], selected = true, onClick = { finish(true) })
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (yesNoOnly) {
                        BigButton(com.drishtisaathi.i18n.SShop.gridWavy[env.lang], enabled = ok, modifier = Modifier.weight(1f), onClick = { finish(true) })
                        BigButton(com.drishtisaathi.i18n.SShop.gridFine[env.lang], enabled = ok, modifier = Modifier.weight(1f), onClick = { finish(false) })
                    } else {
                        BigButton(SVision.yes[env.lang], enabled = ok, modifier = Modifier.weight(1f), onClick = { marking = true })
                        BigButton(SVision.no[env.lang], enabled = ok, modifier = Modifier.weight(1f), onClick = { finish(false) })
                    }
                }
            }
        },
    )
}

@Composable
private fun ArrowButton(label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) = OutlinedButton(
    onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 60.dp), shape = RoundedCornerShape(14.dp),
) { Text(label, style = MaterialTheme.typography.labelLarge) }

/** Staff summary at the end of the flow. */
@Composable
fun VisionSummaryScreen(lang: Lang, session: VisionSession, onFinish: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(SVision.summaryTitle[lang], style = MaterialTheme.typography.headlineMedium)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            VisionSummary.sections(session).forEach { s ->
                Text(s.title[lang], style = MaterialTheme.typography.titleLarge, color = Teal)
                s.lines.forEach { Text("•  " + it[lang], style = MaterialTheme.typography.bodyLarge) }
            }
            Text(SVision.summaryNote[lang], style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }
        BigButton(SVision.finish[lang], selected = true, onClick = onFinish)
    }
}
