package com.drishtisaathi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.drishtisaathi.UiState
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S
import com.drishtisaathi.interview.ChoiceContext
import com.drishtisaathi.interview.Question
import com.drishtisaathi.voice.VoiceAnswers
import com.drishtisaathi.voice.VoiceController
import com.drishtisaathi.voice.VoicePanel

/**
 * One question per screen, big buttons. Typed/button input is always available; voice ([voice], optional)
 * is an accelerator on top: it reads each question aloud, listens, reads the answer back for ✓ / ✗ and then
 * calls the same [onAnswer] / [onOldNumbersYes] the buttons use. Pass the app's single controller from
 * `rememberVoiceController()`; null = no voice strip.
 */
@Composable
fun InterviewScreen(
    state: UiState,
    choiceContext: ChoiceContext,
    onLang: (Lang) -> Unit,
    onAnswer: (questionId: String, update: (InterviewAnswers) -> InterviewAnswers) -> Unit,
    onOldNumbersYes: () -> Unit,
    onBack: () -> Unit,
    voice: VoiceController? = null,
    /** Shown under the first question before anything is answered (the AI voice interview option). */
    startExtra: (@Composable () -> Unit)? = null,
) {
    val l = state.lang
    val q = state.currentQuestion ?: return
    val idx = state.questionIndex

    if (voice != null) VoiceEffects(voice, q, l, choiceContext, onAnswer, onOldNumbersYes)

    AppScaffold(l, onLang, onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // No numbering: follow-ups depend on the answers, so a count would only jump around.
            Text((if (state.stage == com.drishtisaathi.interview.Stage.LATER) com.drishtisaathi.i18n.SShop.fewMoreQuestions
                else com.drishtisaathi.i18n.SShop.aboutYourEyes)[l], style = MaterialTheme.typography.titleMedium, color = Teal)
            Text(q.text[l], style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 12.dp))
            if (voice != null) VoicePanel(voice, l, showDiagnostics = false)

            when (q) {
                is Question.YesNo -> {
                    val current = q.get(state.answers).takeIf { q.id in state.answered }
                    BigButton(S.yes[l], { onAnswer(q.id) { a -> q.set(a, true) } }, selected = current == true)
                    BigButton(S.no[l], { onAnswer(q.id) { a -> q.set(a, false) } }, selected = current == false)
                }
                is Question.Number -> NumberAnswer(q, state, l, onAnswer)
                is Question.Choice<*> -> ChoiceAnswer(q, state, l, choiceContext, onAnswer)
                is Question.OldNumbers -> {
                    val answeredNo = q.id in state.answered && state.answers.knownRx == null
                    BigButton(S.yes[l], onOldNumbersYes, selected = state.answers.knownRx != null)
                    BigButton(S.no[l], { onAnswer(q.id) { a -> a.copy(knownRx = null) } }, selected = answeredNo)
                }
            }
            if (startExtra != null && idx == 0 && state.answered.isEmpty()) startExtra()
        }
    }
}

@Composable
private fun NumberAnswer(
    q: Question.Number, state: UiState, l: Lang,
    onAnswer: (String, (InterviewAnswers) -> InterviewAnswers) -> Unit,
) {
    var text by remember(q.id) { mutableStateOf(q.get(state.answers)?.toString() ?: "") }
    var error by remember(q.id) { mutableStateOf(false) }
    fun submit() {
        val v = text.trim().toIntOrNull()
        if (v == null || v !in q.range) error = true else onAnswer(q.id) { a -> q.set(a, v) }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.filter(Char::isDigit).take(3); error = false },
        label = { Text(q.hint[l]) },
        isError = error,
        supportingText = { if (error) Text(S.invalidNumber[l]) },
        singleLine = true,
        textStyle = MaterialTheme.typography.headlineMedium.copy(fontSize = 32.sp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onNext = { submit() }),
        modifier = Modifier.fillMaxWidth(),
    )
    BigButton(S.next[l], { submit() }, container = Teal)
    if (q.allowUnknown) BigButton(S.dontKnow[l], { onAnswer(q.id) { a -> q.set(a, null) } })
}

@Composable
private fun <T : Any> ChoiceAnswer(
    q: Question.Choice<T>, state: UiState, l: Lang, ctx: ChoiceContext,
    onAnswer: (String, (InterviewAnswers) -> InterviewAnswers) -> Unit,
) {
    val current = q.get(state.answers)
    q.options.forEach { opt ->
        BigButton(q.label(opt, ctx)[l], { onAnswer(q.id) { a -> q.set(a, opt) } }, selected = opt == current)
    }
}

/** Starts the voice dialogue for each question shown, and stops it when the screen pauses or leaves. */
@Composable
private fun VoiceEffects(
    voice: VoiceController, q: Question, l: Lang, ctx: ChoiceContext,
    onAnswer: (questionId: String, update: (InterviewAnswers) -> InterviewAnswers) -> Unit,
    onOldNumbersYes: () -> Unit,
) {
    val answer by rememberUpdatedState(onAnswer)
    val oldNumbersYes by rememberUpdatedState(onOldNumbersYes)
    LaunchedEffect(voice, q.id, l) {
        voice.begin(q, l, ctx) { action ->
            when (action) {
                is VoiceAnswers.Action.Answer -> answer(action.questionId, action.update)
                VoiceAnswers.Action.OpenOldNumbers -> oldNumbersYes()
            }
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(voice, owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> voice.stop()
                Lifecycle.Event.ON_START -> voice.resume()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            voice.detach()
        }
    }
}
