package com.drishtisaathi.profile

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.profile.Freq
import com.drishtisaathi.engine.profile.L10n
import com.drishtisaathi.engine.profile.Survey
import com.drishtisaathi.engine.profile.SurveyAnswers
import com.drishtisaathi.engine.profile.SurveyFile
import com.drishtisaathi.engine.profile.SurveyKind
import com.drishtisaathi.engine.profile.SurveyQuestion
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S
import com.drishtisaathi.i18n.SProfile
import com.drishtisaathi.i18n.SVoice
import com.drishtisaathi.i18n.Txt
import com.drishtisaathi.ui.DS
import com.drishtisaathi.voice.MicAction
import com.drishtisaathi.voice.VoiceController
import kotlin.math.roundToInt

val L10n.txt: Txt get() = Txt(en, hi)

/** Spoken keywords of each option of [q] (fixed ones for never / sometimes / often and yes / no). */
fun spokenOptions(q: SurveyQuestion): List<Pair<String, List<String>>> = when (q.kind) {
    // "never" is listed before "no": "कभी नहीं" contains "नहीं"
    SurveyKind.FREQ -> Freq.entries.map { it.name to SProfile.sayFreq.getValue(it) }
    SurveyKind.YES_NO -> listOf(SurveyQuestion.YES to SProfile.sayYes, SurveyQuestion.NO to SProfile.sayNo)
    SurveyKind.CHOICE, SurveyKind.MULTI -> q.optionCodes.map { it to q.say[it].orEmpty() }.filter { it.second.isNotEmpty() }
    SurveyKind.NUMBER -> emptyList()
}

fun optionLabel(q: SurveyQuestion, code: String): Txt = when (q.kind) {
    SurveyKind.FREQ -> SProfile.freq(Freq.valueOf(code))
    SurveyKind.YES_NO -> if (code == SurveyQuestion.YES) S.yes else S.no
    else -> q.options.getValue(code).txt
}

/**
 * The vision-profile survey: one question per screen, answers as large cards (voice or tap), a "Why we ask?" chip
 * that opens a sheet and speaks, a segmented progress bar coloured by section, and a full-colour intro per section.
 * [sections] limits it (the emergency short path asks the safety section only).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurveyScreen(
    lang: Lang,
    file: SurveyFile,
    answers: InterviewAnswers,
    survey: SurveyAnswers,
    sections: () -> Set<String>?,
    onAnswer: (id: String, picked: List<String>) -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
    voice: VoiceController? = null,
) {
    val c = DS.colors
    val visible = Survey.visible(file, answers, survey, sections())
    // The question shown is the first unanswered one, unless the customer went back to an earlier one. It is never
    // an index of its own: an answer reaches this screen a moment after the tap, and the list can grow or shrink with it.
    var backTo by rememberSaveable { mutableStateOf<String?>(null) }
    var introSeen by rememberSaveable { mutableStateOf(listOf<String>()) }
    var showWhy by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    val q = backTo?.let { id -> visible.firstOrNull { it.id == id } } ?: visible.firstOrNull { !survey.answered(it.id) }
    if (q == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val index = visible.indexOf(q)
    var multi by remember(q.id) { mutableStateOf(survey.set(q.id)) }
    val section = file.section(q.section)
    val sectionColour = DS.parse(section.colour)
    val intro = q.section !in introSeen

    fun goBack() { if (index > 0) backTo = visible[index - 1].id else onBack() }
    BackHandler { goBack() }

    fun submit(picked: List<String>) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onAnswer(q.id, picked)
        backTo = null
    }

    // Read the question aloud once it is on screen (the section's "why" with its intro).
    LaunchedEffect(q.id, intro, lang) {
        voice?.instructionSpeaker?.speak(if (intro) Txt("${section.title.en}. ${section.why.en}", "${section.title.hi}। ${section.why.hi}") else q.text.txt, lang)
    }

    Column(Modifier.fillMaxSize().background(if (intro) sectionColour else c.background).systemBarsPadding()) {
        // ---- top: back + segmented progress + "Step n of m" ----
        val onTop = if (intro) DS.on(sectionColour) else c.ink
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = ::goBack, modifier = Modifier.heightIn(min = DS.minTouch)) { Text("‹ " + S.back[lang], color = onTop) }
            Spacer(Modifier.weight(1f))
            Text(SProfile.stepOf.format(index + 1, visible.size)[lang], color = onTop, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(end = 16.dp))
        }
        SegmentedProgress(
            segments = visible.map { DS.parse(file.section(it.section).colour) }, done = index,
            track = if (intro) DS.on(sectionColour).copy(alpha = 0.25f) else c.hairline,
            modifier = Modifier.fillMaxWidth().padding(horizontal = DS.screen),
        )

        AnimatedContent(
            targetState = Triple(index, intro, lang),
            transitionSpec = {
                (slideInHorizontally(tween(DS.stepMs)) { it / 4 } + fadeIn(tween(DS.stepMs))) togetherWith
                    (slideOutHorizontally(tween(DS.stepMs)) { -it / 4 } + fadeOut(tween(DS.stepMs)))
            },
            label = "survey-step", modifier = Modifier.weight(1f),
        ) { (i, showIntro, _) ->
            val cur = visible.getOrNull(i) ?: return@AnimatedContent
            if (showIntro) {
                val on = DS.on(sectionColour)
                Column(Modifier.fillMaxSize().padding(DS.screen), verticalArrangement = Arrangement.Center) {
                    Text(section.title.txt[lang], color = on, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(16.dp))
                    Text(section.why.txt[lang], color = on, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(40.dp))
                    Surface(
                        onClick = { introSeen = introSeen + cur.section }, shape = DS.button, color = on,
                        modifier = Modifier.fillMaxWidth().heightIn(min = DS.answerMin),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(S.next[lang], color = sectionColour.takeIf { DS.on(on) == Color.White } ?: DS.on(on),
                                style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp))
                        }
                    }
                }
            } else {
                QuestionBody(lang, cur, section.title.txt[lang], sectionColour, survey, multi, { multi = it }, ::submit, onWhy = { showWhy = true })
            }
        }
        if (voice != null && !intro) {
            Box(Modifier.fillMaxWidth().padding(end = DS.screen, bottom = 16.dp), contentAlignment = Alignment.CenterEnd) { MicFab(voice, lang) }
        }
    }

    if (voice != null && !intro) SurveyVoice(voice, q, multi, { multi = it }, ::submit)

    if (showWhy) {
        LaunchedEffect(q.id) { voice?.instructionSpeaker?.speak(q.why.txt, lang) }
        ModalBottomSheet(onDismissRequest = { showWhy = false }, containerColor = c.surface) {
            Column(Modifier.padding(horizontal = DS.screen).padding(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(SProfile.whyWeAsk[lang], style = MaterialTheme.typography.titleLarge, color = c.ink)
                Text(q.why.txt[lang], style = MaterialTheme.typography.bodyLarge, color = c.ink)
            }
        }
    }
}

/** One segment per step, coloured by its section; done steps are filled. */
@Composable
fun SegmentedProgress(segments: List<Color>, done: Int, track: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.height(6.dp).semantics { contentDescription = "${done + 1} / ${segments.size}" }) {
        val n = segments.size.coerceAtLeast(1)
        val gap = if (n > 1) 3.dp.toPx() else 0f
        val w = (size.width - gap * (n - 1)) / n
        segments.forEachIndexed { i, col ->
            val x = i * (w + gap)
            drawLine(if (i <= done) col else track, androidx.compose.ui.geometry.Offset(x + size.height / 2, size.height / 2),
                androidx.compose.ui.geometry.Offset(x + w - size.height / 2, size.height / 2), size.height, StrokeCap.Round)
        }
    }
}

@Composable
private fun QuestionBody(
    lang: Lang, q: SurveyQuestion, sectionTitle: String, sectionColour: Color, survey: SurveyAnswers,
    multi: Set<String>, onMulti: (Set<String>) -> Unit, submit: (List<String>) -> Unit, onWhy: () -> Unit,
) {
    val c = DS.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = DS.screen, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(10.dp).background(sectionColour, CircleShape))
            Text(sectionTitle, style = MaterialTheme.typography.labelMedium, color = c.inkSecondary)
        }
        Text(q.text.txt[lang], style = MaterialTheme.typography.headlineMedium, color = c.ink)
        Surface(
            onClick = onWhy, shape = DS.pill, color = c.primarySoft,
            modifier = Modifier.heightIn(min = DS.minTouch).semantics { role = Role.Button },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("?  " + SProfile.whyWeAsk[lang], color = c.primary, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        when (q.kind) {
            SurveyKind.FREQ -> FreqAnswer(lang, q, survey.one(q.id), submit)
            SurveyKind.YES_NO, SurveyKind.CHOICE -> q.optionCodes.forEach { code ->
                AnswerCard(optionLabel(q, code)[lang], selected = survey.one(q.id) == code) { submit(listOf(code)) }
            }
            SurveyKind.MULTI -> MultiAnswer(lang, q, multi, onMulti, submit)
            SurveyKind.NUMBER -> NumberAnswer(lang, q, survey.number(q.id), submit)
        }
    }
}

/** A large tappable answer card (at least 64 dp), with a tick when selected. */
@Composable
fun AnswerCard(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = DS.colors
    Surface(
        onClick = onClick, shape = DS.button,
        color = if (selected) c.primarySoft else c.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) c.primary else c.hairline),
        modifier = modifier.fillMaxWidth().heightIn(min = DS.answerMin).semantics { this.selected = selected; role = Role.RadioButton },
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = c.ink)
            if (selected) Text("✓", color = c.primary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

/** Never / Sometimes / Often: a labelled three-stop slider plus "Next" (a tap on a label also answers). */
@Composable
private fun FreqAnswer(lang: Lang, q: SurveyQuestion, current: String?, submit: (List<String>) -> Unit) {
    val c = DS.colors
    var pos by remember(q.id) { mutableStateOf(current?.let { Freq.valueOf(it).ordinal }?.toFloat() ?: 0f) }
    val picked = Freq.entries[pos.roundToInt().coerceIn(0, 2)]
    Column(Modifier.fillMaxWidth().background(c.surface, DS.card).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(SProfile.freq(picked)[lang], style = MaterialTheme.typography.headlineSmall, color = c.primary, fontWeight = FontWeight.SemiBold)
        Slider(
            value = pos, onValueChange = { pos = it }, valueRange = 0f..2f, steps = 1,
            colors = SliderDefaults.colors(thumbColor = c.primary, activeTrackColor = c.primary, inactiveTrackColor = c.hairline),
            modifier = Modifier.semantics { stateDescription = SProfile.freq(picked)[lang] },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Freq.entries.forEach { f ->
                Text(SProfile.freq(f)[lang], style = MaterialTheme.typography.labelMedium,
                    color = if (f == picked) c.ink else c.inkSecondary,
                    modifier = Modifier.clip(DS.pill).clickable { pos = f.ordinal.toFloat() }.heightIn(min = DS.minTouch).padding(horizontal = 6.dp, vertical = 12.dp))
            }
        }
    }
    PrimaryButton(S.next[lang]) { submit(listOf(picked.name)) }
}

@Composable
private fun MultiAnswer(lang: Lang, q: SurveyQuestion, picked: Set<String>, onPicked: (Set<String>) -> Unit, submit: (List<String>) -> Unit) {
    Text(SProfile.chooseAll[lang], style = MaterialTheme.typography.bodyMedium, color = DS.colors.inkSecondary)
    q.optionCodes.forEach { code ->
        AnswerCard(optionLabel(q, code)[lang], selected = code in picked) { onPicked(if (code in picked) picked - code else picked + code) }
    }
    PrimaryButton(if (picked.isEmpty()) SProfile.noneOfThese[lang] else S.next[lang]) { submit(q.optionCodes.filter { it in picked }) }
}

@Composable
private fun NumberAnswer(lang: Lang, q: SurveyQuestion, current: Double?, submit: (List<String>) -> Unit) {
    val c = DS.colors
    var v by remember(q.id) { mutableStateOf((current ?: 0.0).toFloat()) }
    val n = (v / q.step).roundToInt() * q.step
    Column(Modifier.fillMaxWidth().background(c.surface, DS.card).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(n.toString(), style = MaterialTheme.typography.displayMedium, color = c.primary, fontWeight = FontWeight.SemiBold)
            Text(q.unit?.txt?.get(lang).orEmpty(), style = MaterialTheme.typography.titleMedium, color = c.inkSecondary, modifier = Modifier.padding(bottom = 8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StepButton("−") { v = (n - q.step).coerceAtLeast(q.min).toFloat() }
            Slider(
                value = v, onValueChange = { v = it }, valueRange = q.min.toFloat()..q.max.toFloat(),
                colors = SliderDefaults.colors(thumbColor = c.primary, activeTrackColor = c.primary, inactiveTrackColor = c.hairline),
                modifier = Modifier.weight(1f).semantics { stateDescription = "$n" },
            )
            StepButton("+") { v = (n + q.step).coerceAtMost(q.max).toFloat() }
        }
    }
    PrimaryButton(S.next[lang]) { submit(listOf(n.toString())) }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    val c = DS.colors
    Surface(onClick = onClick, shape = CircleShape, color = c.primarySoft, modifier = Modifier.size(DS.minTouch).semantics { contentDescription = label }) {
        Box(contentAlignment = Alignment.Center) { Text(label, color = c.primary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
    }
}

/** The main action of a screen: filled, full width, 64 dp. */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, colour: Color? = null, onClick: () -> Unit) {
    val c = DS.colors
    val bg = colour ?: c.primary
    Surface(
        onClick = onClick, enabled = enabled, shape = DS.button,
        color = if (enabled) bg else c.hairline,
        modifier = modifier.fillMaxWidth().heightIn(min = DS.answerMin).semantics { role = Role.Button },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = if (enabled) (if (colour == null) c.onPrimary else DS.on(bg)) else c.inkSecondary,
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
        }
    }
}

/** Listens for the options' keywords while a question is shown: a single answer submits, a multi option toggles. */
@Composable
private fun SurveyVoice(
    voice: VoiceController, q: SurveyQuestion, multi: Set<String>, onMulti: (Set<String>) -> Unit, submit: (List<String>) -> Unit,
) {
    val st by voice.state.collectAsState()
    val sub by rememberUpdatedState(submit)
    val tog by rememberUpdatedState(onMulti)
    val cur by rememberUpdatedState(multi)
    DisposableEffect(voice, q.id, st.canListen) {
        val opts = spokenOptions(q)
        if (opts.isNotEmpty()) {
            fun act(code: String) {
                if (q.kind == SurveyKind.MULTI) tog(if (code in cur) cur - code else cur + code) else sub(listOf(code))
            }
            val extra = opts.drop(1).map { (code, words) -> words to { act(code) } } +
                (if (q.kind == SurveyKind.MULTI) listOf(SProfile.sayNext to { sub(q.optionCodes.filter { it in cur }) }) else emptyList())
            voice.listenForKeyword(opts.first().second, extra = extra) { act(opts.first().first) }
        }
        onDispose { voice.stopKeyword() }
    }
}

/** 72 dp floating mic: a pulsing ring while listening; asks for the mic permission and the online consent when needed. */
@Composable
fun MicFab(voice: VoiceController, lang: Lang, modifier: Modifier = Modifier) {
    val c = DS.colors
    val st by voice.state.collectAsState()
    val context = LocalContext.current
    var askConsent by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        voice.setMicPermission(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        voice.refreshAvailability()
    }
    fun tap() { if (voice.onMicTapped() == MicAction.ASK_NETWORK_CONSENT) askConsent = true }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { voice.setMicPermission(true); tap() } else voice.onMicPermissionDenied()
    }
    val on = st.canListen
    val pulse by rememberInfiniteTransition(label = "mic").animateFloat(
        1f, 1.35f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "mic-pulse",
    )
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (on) Surface(shape = DS.pill, color = c.surface, border = BorderStroke(1.dp, c.hairline)) {
            Text(SProfile.listening[lang], color = c.ink, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
        }
        st.message?.let { Text(it[lang], color = c.urgent, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(220.dp)) }
        Box(contentAlignment = Alignment.Center) {
            if (on) Box(Modifier.size(72.dp).scale(pulse).background(c.primary.copy(alpha = 0.18f), CircleShape))
            Surface(
                onClick = {
                    when (voice.onMicTapped()) {
                        MicAction.REQUEST_PERMISSION -> perm.launch(Manifest.permission.RECORD_AUDIO)
                        MicAction.ASK_NETWORK_CONSENT -> askConsent = true
                        else -> {}
                    }
                },
                shape = CircleShape, color = if (on) c.primary else c.surface,
                border = if (on) null else BorderStroke(1.dp, c.hairline), shadowElevation = 4.dp,
                modifier = Modifier.size(72.dp).semantics { contentDescription = (if (on) SProfile.micOff else SProfile.micOn)[lang]; role = Role.Button },
            ) {
                MicIcon(if (on) c.onPrimary else c.primary)
            }
        }
    }
    if (askConsent) AlertDialog(
        onDismissRequest = { askConsent = false },
        title = { Text(SVoice.onlineConsentTitle[lang]) },
        text = { Text(SVoice.onlineConsentBody[lang]) },
        confirmButton = { TextButton({ askConsent = false; voice.setNetworkConsent(true); voice.onMicTapped() }) { Text(SVoice.onlineConsentAgree[lang]) } },
        dismissButton = { TextButton({ askConsent = false; voice.setNetworkConsent(false) }) { Text(SVoice.onlineConsentDecline[lang]) } },
    )
}

/** A rounded microphone drawn in vectors (no icon font needed). */
@Composable
private fun MicIcon(tint: Color) {
    Canvas(Modifier.fillMaxSize().padding(22.dp)) {
        val w = size.width; val h = size.height
        val body = w * 0.36f
        drawRoundRect(tint, androidx.compose.ui.geometry.Offset((w - body) / 2, 0f), androidx.compose.ui.geometry.Size(body, h * 0.62f),
            androidx.compose.ui.geometry.CornerRadius(body / 2, body / 2))
        drawArc(tint, 0f, 180f, false, androidx.compose.ui.geometry.Offset(w * 0.14f, h * 0.12f), androidx.compose.ui.geometry.Size(w * 0.72f, h * 0.66f),
            style = Stroke(w * 0.09f, cap = StrokeCap.Round))
        drawLine(tint, androidx.compose.ui.geometry.Offset(w / 2, h * 0.78f), androidx.compose.ui.geometry.Offset(w / 2, h), w * 0.09f, StrokeCap.Round)
    }
}
