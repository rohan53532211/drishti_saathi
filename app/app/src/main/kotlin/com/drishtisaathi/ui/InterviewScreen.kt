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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drishtisaathi.UiState
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S
import com.drishtisaathi.interview.ChoiceContext
import com.drishtisaathi.interview.Question

/**
 * One question per screen, big buttons. Typed/button input is always available
 * (voice comes in Phase 2 on top of this, never instead of it).
 */
@Composable
fun InterviewScreen(
    state: UiState,
    choiceContext: ChoiceContext,
    onLang: (Lang) -> Unit,
    onAnswer: (questionId: String, update: (InterviewAnswers) -> InterviewAnswers) -> Unit,
    onOldNumbersYes: () -> Unit,
    onBack: () -> Unit,
) {
    val l = state.lang
    val q = state.currentQuestion ?: return
    val total = state.visibleQuestions.size
    val idx = state.questionIndex

    AppScaffold(l, onLang, onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(S.questionOf.format(idx + 1, total)[l], style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { (idx + 1f) / total }, modifier = Modifier.fillMaxWidth())
            Text(q.text[l], style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 12.dp))

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
