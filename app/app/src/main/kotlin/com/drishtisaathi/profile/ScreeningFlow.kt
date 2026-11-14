package com.drishtisaathi.profile

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.drishtisaathi.calibration.CalibrationRepository
import com.drishtisaathi.camera.CameraDistanceProvider
import com.drishtisaathi.camera.CameraPermissionGate
import com.drishtisaathi.camera.rememberFaceTracker
import com.drishtisaathi.engine.Eye
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.QuickEstimate
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.profile.Amsler
import com.drishtisaathi.engine.profile.AmslerCell
import com.drishtisaathi.engine.profile.AmslerEyeResult
import com.drishtisaathi.engine.profile.AmslerLayout
import com.drishtisaathi.engine.profile.ColourPlates
import com.drishtisaathi.engine.profile.ColourResult
import com.drishtisaathi.engine.profile.Contrast
import com.drishtisaathi.engine.profile.ContrastEyeResult
import com.drishtisaathi.engine.profile.ContrastRun
import com.drishtisaathi.engine.profile.PlateSpec
import com.drishtisaathi.engine.profile.ScreeningResults
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S
import com.drishtisaathi.i18n.SProfile
import com.drishtisaathi.i18n.SShop
import com.drishtisaathi.i18n.Txt
import com.drishtisaathi.ui.DS
import com.drishtisaathi.vision.DistanceDebugPanel
import com.drishtisaathi.vision.DistanceProvider
import com.drishtisaathi.vision.DistanceReading
import com.drishtisaathi.vision.FakeDistanceProvider
import com.drishtisaathi.vision.MaxBrightnessKeepScreenOn
import com.drishtisaathi.vision.SLOAN_LETTERS
import com.drishtisaathi.vision.ScreenScale
import com.drishtisaathi.vision.Sizing
import com.drishtisaathi.vision.VisionFlowPlan
import com.drishtisaathi.vision.VisionTestFlow
import com.drishtisaathi.vision.powerCheckCamera
import com.drishtisaathi.voice.VoiceController
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.random.Random

private fun eyeWord(e: Eye): Txt = if (e == Eye.RIGHT) SShop.rightEye else SShop.leftEye
/** The eye to cover is the other one. */
private fun coverLine(e: Eye): Txt {
    val other = if (e == Eye.RIGHT) SShop.leftWord else SShop.rightWord
    return Txt(SProfile.coverEye.en.format(other.en), SProfile.coverEye.hi.format(other.hi))
}
private fun join(a: Txt, b: Txt) = Txt("${a.en} ${b.en}", "${a.hi} ${b.hi}")
private fun head(e: Eye?, t: Txt): Txt = if (e == null) t else Txt("${eyeWord(e).en}: ${t.en}", "${eyeWord(e).hi}: ${t.hi}")

/** Hides the status and navigation bars while a test screen is shown (they come back on a swipe and when it leaves). */
@Composable
fun ImmersiveMode() {
    val context = LocalContext.current
    DisposableEffect(context) {
        var c: android.content.Context? = context
        while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
        val window = (c as? android.app.Activity)?.window
        val ctrl = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        ctrl?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctrl?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose { ctrl?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
    }
}

/** Live distance pill: green inside [minMm]..[maxMm], amber outside or when the face is lost. */
@Composable
fun DistancePill(lang: Lang, reading: DistanceReading?, minMm: Double?, maxMm: Double?, modifier: Modifier = Modifier) {
    val c = DS.colors
    val mm = reading?.takeIf { it.faceVisible && it.distanceMm > 0 }?.distanceMm
    val ok = mm != null && (minMm == null || mm >= minMm) && (maxMm == null || mm <= maxMm)
    val text = when {
        mm == null -> SProfile.noFace[lang]
        ok || minMm == null || maxMm == null -> SProfile.distanceCm.format((mm / 10).roundToInt())[lang]
        else -> SProfile.distanceCm.format((mm / 10).roundToInt())[lang] + "  ·  " + SProfile.holdRange.format((minMm / 10).roundToInt(), (maxMm / 10).roundToInt())[lang]
    }
    Surface(shape = DS.pill, color = if (ok) c.successSoft else c.warningSoft, modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(10.dp).background(if (ok) c.success else c.warning, CircleShape))
            Text(text, color = if (ok) c.successInk else c.warningInk, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** 3-2-1, then [onDone]. */
@Composable
fun Countdown(onDone: () -> Unit) {
    val c = DS.colors
    var n by remember { mutableIntStateOf(3) }
    val s = remember { Animatable(0.6f) }
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(Unit) {
        while (n > 0) {
            s.snapTo(0.6f); s.animateTo(1f, tween(450)); delay(250)
            n--
        }
        done()
    }
    if (n > 0) Text(n.toString(), color = c.primary, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold,
        modifier = Modifier.scale(s.value))
}

/** A gentle tick drawn in, then [onDone] (after each test). */
@Composable
fun SuccessTick(onDone: () -> Unit) {
    val c = DS.colors
    val p = remember { Animatable(0f) }
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(Unit) { p.animateTo(1f, tween(500)); delay(350); done() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(120.dp)) {
            drawCircle(c.successSoft)
            val a = Offset(size.width * 0.28f, size.height * 0.52f); val b = Offset(size.width * 0.45f, size.height * 0.68f)
            val e = Offset(size.width * 0.74f, size.height * 0.36f)
            val t = p.value
            val w = size.width * 0.08f
            if (t > 0f) drawLine(c.success, a, Offset(a.x + (b.x - a.x) * minOf(1f, t * 2), a.y + (b.y - a.y) * minOf(1f, t * 2)), w, StrokeCap.Round)
            if (t > 0.5f) drawLine(c.success, b, Offset(b.x + (e.x - b.x) * (t - 0.5f) * 2, b.y + (e.y - b.y) * (t - 0.5f) * 2), w, StrokeCap.Round)
        }
    }
}

/**
 * The one layout of every screening test: minimal chrome (title, skip), the live distance pill, a big instruction
 * (spoken once), the target, the controls.
 */
@Composable
private fun TestChrome(
    lang: Lang, title: Txt, instruction: Txt, reading: DistanceReading?, minMm: Double?, maxMm: Double?,
    speak: (Txt) -> Unit, onSkip: () -> Unit, background: Color = DS.colors.background,
    controls: @Composable ColumnScope.() -> Unit = {},
    target: @Composable () -> Unit,
) {
    val c = DS.colors
    LaunchedEffect(instruction) { speak(instruction) }
    Column(Modifier.fillMaxSize().background(background).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title[lang], Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = c.ink, fontWeight = FontWeight.SemiBold, maxLines = 1)
            TextButton(onClick = onSkip) { Text(SProfile.skipTest[lang], color = c.inkSecondary) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DistancePill(lang, reading, minMm, maxMm)
        }
        Text(instruction[lang], style = MaterialTheme.typography.titleLarge, color = c.ink)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { target() }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = controls)
    }
}

// ---- Amsler grid --------------------------------------------------------------------------------

private enum class GridPhase { WAIT, COUNT, ASK, MARK }

/**
 * Amsler grid, one eye: squares of 5 real millimetres (display dpi or the bank-card scale), centre dot. It starts
 * only while the camera reads 27–33 cm; the question is answered by voice or tap; on "yes" the person taps the
 * places on the grid. Any yes = red flag, with the tapped squares in the report.
 */
@Composable
private fun AmslerScreen(
    lang: Lang, rules: RulesConfig, eye: Eye, reading: DistanceReading?, scale: ScreenScale, voice: VoiceController?,
    speak: (Txt) -> Unit, onSkip: () -> Unit, onResult: (AmslerEyeResult) -> Unit,
) {
    val cfg = rules.screening.amsler
    val c = DS.colors
    var phase by remember { mutableStateOf(GridPhase.WAIT) }
    var cells by remember { mutableStateOf(emptyList<AmslerCell>()) }
    var layout by remember { mutableStateOf<AmslerLayout?>(null) }
    var tooSmall by remember { mutableStateOf(false) }
    val mm = reading?.takeIf { it.faceVisible }?.distanceMm
    val inRange = Amsler.distanceOk(mm, cfg)
    LaunchedEffect(inRange, phase) { if (phase == GridPhase.WAIT && inRange) phase = GridPhase.COUNT }

    val instruction = when (phase) {
        GridPhase.WAIT, GridPhase.COUNT -> join(coverLine(eye), SProfile.amslerHold)
        GridPhase.ASK -> SProfile.amslerQuestion
        GridPhase.MARK -> SProfile.amslerTap
    }
    fun finish(distortion: Boolean) { layout?.let { onResult(Amsler.result(eye, distortion, cells, it, mm)) } }

    if (voice != null && phase == GridPhase.ASK) {
        val yes by rememberUpdatedState { phase = GridPhase.MARK }
        val no by rememberUpdatedState { finish(false) }
        DisposableEffect(voice, inRange) {
            if (inRange) voice.listenForKeyword(SProfile.sayNo, extra = listOf(SProfile.sayYes to { yes() })) { no() }
            onDispose { voice.stopKeyword() }
        }
    }

    TestChrome(
        lang, head(eye, SProfile.amslerTitle), instruction, reading, cfg.minMm, cfg.maxMm, speak, onSkip, background = Color.White,
        controls = {
            layout?.takeIf { !it.full }?.let { Text(SProfile.amslerNarrow.format(it.columns, it.rows)[lang], style = MaterialTheme.typography.bodySmall, color = c.inkSecondary) }
            when (phase) {
                GridPhase.ASK -> {
                    PrimaryButton(SProfile.amslerNo[lang], enabled = inRange) { finish(false) }
                    AnswerCard(SProfile.amslerYes[lang], selected = false) { if (inRange) phase = GridPhase.MARK }
                }
                GridPhase.MARK -> PrimaryButton(SProfile.done[lang]) { finish(true) }
                else -> {}
            }
            if (tooSmall) Text(SProfile.amslerTooSmall[lang], color = c.urgentInk)
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val wPx = with(density) { maxWidth.toPx() }.toDouble()
            val hPx = with(density) { maxHeight.toPx() }.toDouble()
            val l = remember(wPx, hPx, scale) { Amsler.layout(wPx, hPx, scale.mmPerPx, cfg) }
            LaunchedEffect(l) { layout = l; tooSmall = l == null }
            if (l != null) {
                val active = phase == GridPhase.ASK || phase == GridPhase.MARK
                Canvas(
                    Modifier.size(with(density) { l.widthPx.toFloat().toDp() }, with(density) { l.heightPx.toFloat().toDp() })
                        .semantics { contentDescription = SProfile.amslerTitle[lang] }
                        .pointerInput(phase, l) {
                            if (phase == GridPhase.MARK) detectTapGestures { o ->
                                l.cellAt(o.x.toDouble(), o.y.toDouble())?.let { cell -> cells = if (cell in cells) cells - cell else cells + cell }
                            }
                        },
                ) {
                    val sq = l.squarePx.toFloat()
                    val line = if (active) Color.Black else Color(0xFFBDBDBD)
                    cells.forEach { drawRect(Color(0x55D93838), Offset(it.col * sq, it.row * sq), Size(sq, sq)) }
                    for (i in 0..l.columns) drawLine(line, Offset(i * sq, 0f), Offset(i * sq, size.height), 1.5f)
                    for (j in 0..l.rows) drawLine(line, Offset(0f, j * sq), Offset(size.width, j * sq), 1.5f)
                    drawCircle(Color.Black, sq * 0.22f, Offset(size.width / 2, size.height / 2))
                }
            }
            if (phase == GridPhase.COUNT) Countdown { phase = GridPhase.ASK }
        }
    }
}

// ---- Contrast -----------------------------------------------------------------------------------

/** The letters offered for one trial: the right one and [n] − 1 others, shuffled. */
fun contrastChoices(correct: Char, n: Int, rnd: Random): List<Char> =
    (SLOAN_LETTERS.filter { it != correct }.toList().shuffled(rnd).take(n - 1) + correct).shuffled(rnd)

/**
 * Contrast, one eye: one letter of fixed size (0.5 logMAR at the measured distance) on white; the contrast falls
 * 100 → 1.5 %; 2 of 3 right passes a step. The result is the lowest step passed.
 */
@Composable
private fun ContrastScreen(
    lang: Lang, rules: RulesConfig, eye: Eye, reading: DistanceReading?, scale: ScreenScale,
    speak: (Txt) -> Unit, onSkip: () -> Unit, onResult: (ContrastEyeResult) -> Unit,
) {
    val cfg = rules.screening.contrast
    val vm = remember(rules) { com.drishtisaathi.engine.vision.VisionMath(rules) }
    val rnd = remember { Random(System.nanoTime()) }
    var run by remember { mutableStateOf(ContrastRun(cfg)) }
    var started by remember { mutableStateOf(false) }
    var letter by remember { mutableStateOf(SLOAN_LETTERS.random(rnd)) }
    var choices by remember { mutableStateOf(contrastChoices(letter, cfg.choices, rnd)) }
    val mm = reading?.takeIf { it.faceVisible }?.distanceMm
    val inRange = mm != null && mm in cfg.minMm..cfg.maxMm
    // the last good distance keeps the letter's size while the face is briefly lost
    var sizeMm by remember { mutableStateOf(400.0) }
    LaunchedEffect(mm) { if (mm != null && mm > 0) sizeMm = mm.coerceIn(cfg.minMm, cfg.maxMm) }

    fun answer(right: Boolean) {
        val next = run.answer(right)
        if (next.done) onResult(next.result(eye)) else {
            run = next
            letter = SLOAN_LETTERS.filter { it != letter }.random(rnd)
            choices = contrastChoices(letter, cfg.choices, rnd)
        }
    }

    TestChrome(
        lang, head(eye, SProfile.contrastTitle), if (started) SProfile.contrastInstruction else join(coverLine(eye), SProfile.contrastInstruction),
        reading, cfg.minMm, cfg.maxMm, speak, onSkip, background = Color.White,
        controls = {
            if (started) {
                choices.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { ch ->
                            Surface(
                                onClick = { if (inRange) answer(ch == letter) }, shape = DS.button, color = DS.colors.surface,
                                border = BorderStroke(1.dp, DS.colors.hairline),
                                modifier = Modifier.weight(1f).heightIn(min = DS.answerMin).semantics { role = Role.Button; contentDescription = ch.toString() },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(ch.toString(), style = MaterialTheme.typography.headlineMedium, color = DS.colors.ink, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = { if (inRange) answer(false) }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(SProfile.cantSee[lang], color = DS.colors.inkSecondary) }
            }
        },
    ) {
        if (!started) {
            if (inRange) Countdown { started = true }
        } else {
            val density = LocalDensity.current
            val px = Sizing.letterHeightPx(vm, sizeMm, scale, cfg.letterLogMar).toFloat()
            val g = Contrast.letterGrey(run.percent)
            Text(
                letter.toString(), fontSize = with(density) { (px / 0.71f).toSp() }, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
                color = Color(g, g, g), maxLines = 1, softWrap = false,
            )
        }
    }
}

// ---- Colour -------------------------------------------------------------------------------------

/** Colour plates with both eyes: 1 control + 5 red-green + 2 blue-yellow of our own dot plates; digit by voice or pad. */
@Composable
private fun ColourScreen(
    lang: Lang, rules: RulesConfig, reading: DistanceReading?, scale: ScreenScale, voice: VoiceController?,
    speak: (Txt) -> Unit, onSkip: () -> Unit, onResult: (ColourResult) -> Unit,
) {
    val cfg = rules.screening.colour
    val gate = rules.screening.contrast
    val series = remember { ColourPlates.series(cfg, Random(System.nanoTime())) }
    var index by remember { mutableIntStateOf(0) }
    var answers by remember { mutableStateOf(emptyList<Pair<PlateSpec, Int?>>()) }
    val spec = series[index.coerceAtMost(series.lastIndex)]
    val dots = remember(spec) { ColourPlates.dots(spec, cfg) }
    val pal = cfg.palettes.getValue(spec.type)
    val fig = remember(pal) { pal.figure.map(DS::parse) }
    val bg = remember(pal) { pal.background.map(DS::parse) }

    fun answer(d: Int?) {
        val next = answers + (spec to d)
        if (next.size >= series.size) onResult(ColourPlates.score(next, cfg)) else { answers = next; index++ }
    }
    if (voice != null) {
        val ans by rememberUpdatedState(::answer)
        DisposableEffect(voice, index) {
            val extra = (1..9).map { d -> SProfile.sayDigits[d] to { ans(d) } }
            voice.listenForKeyword(SProfile.sayDigits[0], extra = extra) { ans(0) }
            onDispose { voice.stopKeyword() }
        }
    }

    TestChrome(
        lang, Txt("${SProfile.colourTitle.en} · ${SProfile.plateOf.format(index + 1, series.size).en}", "${SProfile.colourTitle.hi} · ${SProfile.plateOf.format(index + 1, series.size).hi}"),
        SProfile.colourInstruction, reading, gate.minMm, gate.maxMm, speak, onSkip, background = Color.White,
        controls = {
            listOf(listOf(1, 2, 3, 4, 5), listOf(6, 7, 8, 9, 0)).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { d ->
                        Surface(
                            onClick = { answer(d) }, shape = DS.button, color = DS.colors.surface, border = BorderStroke(1.dp, DS.colors.hairline),
                            modifier = Modifier.weight(1f).height(56.dp).semantics { role = Role.Button; contentDescription = d.toString() },
                        ) { Box(contentAlignment = Alignment.Center) { Text(d.toString(), style = MaterialTheme.typography.headlineSmall, color = DS.colors.ink) } }
                    }
                }
            }
            TextButton(onClick = { answer(null) }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(SProfile.noNumber[lang], color = DS.colors.inkSecondary) }
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val want = Sizing.mmToPx(cfg.plateMm, scale).toFloat()
            val side = minOf(want, with(density) { maxWidth.toPx() }, with(density) { maxHeight.toPx() })
            Canvas(Modifier.size(with(density) { side.toDp() }).semantics { contentDescription = SProfile.colourTitle[lang] }) {
                dots.forEach { d ->
                    drawCircle(if (d.figure) fig[d.shade % fig.size] else bg[d.shade % bg.size], (d.r * size.width).toFloat(),
                        Offset((d.x * size.width).toFloat(), (d.y * size.height).toFloat()))
                }
            }
        }
    }
}

// ---- The flow -----------------------------------------------------------------------------------

/**
 * The screening stage after the power of each eye: full brightness and "turn off Night light", then the grid per
 * eye, contrast per eye, colour with both eyes, and the earlier E test and clock dial. Every test has Skip (skipped
 * = no data). [emergency]: the short path, the grid only. [onDone] gets every result of the stage.
 */
@Composable
fun ScreeningFlow(
    lang: Lang, rules: RulesConfig, distance: DistanceProvider, calibration: CalibrationRepository,
    answers: InterviewAnswers, quick: QuickEstimate?, emergency: Boolean,
    onDone: (ScreeningResults) -> Unit,
    modifier: Modifier = Modifier, voice: VoiceController? = null, speak: (Txt) -> Unit = {},
) {
    DisposableEffect(distance) { distance.start(); onDispose { distance.stop() } }
    MaxBrightnessKeepScreenOn()
    ImmersiveMode()
    val reading by distance.readings.collectAsState()
    val cal by calibration.calibration.collectAsState()
    val metrics = LocalContext.current.resources.displayMetrics
    val scale = remember(cal, metrics.xdpi, metrics.densityDpi) { Sizing.screenScale(cal, metrics.xdpi, metrics.densityDpi) }
    val steps = remember(emergency) { ScreeningPlan.steps(emergency) }
    var index by remember { mutableIntStateOf(0) }
    var tick by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(ScreeningResults()) }
    val c = DS.colors

    fun advance() { if (index + 1 >= steps.size) onDone(results) else index++ }
    fun finished(r: ScreeningResults) { results = r; tick = true }
    fun skip(step: ScreenStep) { results = results.copy(skipped = results.skipped + ScreeningPlan.skipKey(step)); advance() }

    Column(modifier.fillMaxSize().background(c.background)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val step = steps[index.coerceAtMost(steps.lastIndex)]
            when {
                tick -> SuccessTick { tick = false; advance() }
                step == ScreenStep.Setup -> {
                    LaunchedEffect(Unit) { speak(SProfile.setupBody) }
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(DS.screen), verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(SProfile.setupTitle[lang], style = MaterialTheme.typography.headlineMedium, color = c.ink)
                        Text(SProfile.setupBody[lang], style = MaterialTheme.typography.bodyLarge, color = c.ink)
                        Text(SProfile.screeningLabel[lang], style = MaterialTheme.typography.labelMedium, color = c.inkSecondary)
                        PrimaryButton(SProfile.setupStart[lang]) { advance() }
                    }
                }
                step is ScreenStep.Amsler -> key(step) {
                    AmslerScreen(lang, rules, step.eye, reading, scale, voice, speak, onSkip = { skip(step) }) { r -> finished(results.copy(amsler = results.amsler + r)) }
                }
                step is ScreenStep.Contrast -> key(step) {
                    ContrastScreen(lang, rules, step.eye, reading, scale, speak, onSkip = { skip(step) }) { r -> finished(results.copy(contrast = results.contrast + r)) }
                }
                step == ScreenStep.Colour -> ColourScreen(lang, rules, reading, scale, voice, speak, onSkip = { skip(step) }) { r -> finished(results.copy(colour = r)) }
                step == ScreenStep.Classic -> VisionTestFlow(
                    lang, rules, distance, calibration, answers.age, answers.occupation,
                    onDone = { _, session ->
                        val (e, d) = ScreeningPlan.classicResults(session)
                        results = results.copy(eTest = e, clockDial = d)
                        onDone(results)
                    },
                    onCancel = { skip(step) },
                    onInstruction = speak, debugDistance = null,
                    stepsOverride = remember(rules) { VisionFlowPlan.screeningSteps(rules) },
                    initialSession = remember(quick) { ScreeningPlan.seededSession(quick) },
                )
            }
        }
        (distance as? FakeDistanceProvider)?.let { DistanceDebugPanel(it, lang) }
    }
}

/** [ScreeningFlow] with the real camera; without the permission: [onSkip], or (demo / precision) the distance slider. */
@Composable
fun CameraScreeningFlow(
    lang: Lang, rules: RulesConfig, calibration: CalibrationRepository, answers: InterviewAnswers, quick: QuickEstimate?,
    emergency: Boolean, onDone: (ScreeningResults) -> Unit, onSkip: () -> Unit,
    modifier: Modifier = Modifier, voice: VoiceController? = null, speak: (Txt) -> Unit = {}, allowDemoWithoutCamera: Boolean = false,
) {
    var demo by remember { mutableStateOf(false) }
    if (demo) {
        val fake = remember { FakeDistanceProvider(300.0) }
        ScreeningFlow(lang, rules, fake, calibration, answers, quick, emergency, onDone, modifier, voice, speak)
        return
    }
    CameraPermissionGate(lang, onSkip = { if (allowDemoWithoutCamera) demo = true else onSkip() }, autoRequest = true) {
        // one eye is covered by the palm: the same relaxed tracking as the power check
        val relaxed = remember(rules) { powerCheckCamera(rules.camera) }
        val tracker = rememberFaceTracker(relaxed)
        val provider = remember(tracker) { CameraDistanceProvider(tracker, calibration, relaxed, controlsTracker = false) }
        ScreeningFlow(lang, rules, provider, calibration, answers, quick, emergency, onDone, modifier, voice, speak)
    }
}
