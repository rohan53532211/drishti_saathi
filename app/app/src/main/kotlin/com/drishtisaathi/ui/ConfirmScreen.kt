package com.drishtisaathi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.drishtisaathi.UiState
import com.drishtisaathi.engine.EyeRx
import com.drishtisaathi.engine.KnownRx
import com.drishtisaathi.engine.StaffMeasurements
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S
import com.drishtisaathi.i18n.Txt
import com.drishtisaathi.i18n.fmtD
import com.drishtisaathi.interview.ChoiceContext
import com.drishtisaathi.interview.Question

/** Text shown for an answer on the confirmation screen and in summaries. */
fun answerText(q: Question, state: UiState, ctx: ChoiceContext): Txt {
    if (q.id !in state.answered) return S.notAnswered
    val a = state.answers
    return when (q) {
        is Question.YesNo -> if (q.get(a) == true) S.yes else S.no
        is Question.Number -> q.get(a)?.let { Txt(it.toString(), it.toString()) } ?: S.dontKnow
        is Question.Choice<*> -> choiceLabel(q, state, ctx) ?: S.notAnswered
        is Question.OldNumbers -> a.knownRx?.let { rx -> Txt(rxText(rx), rxText(rx)) } ?: S.no
    }
}

private fun <T : Any> choiceLabel(q: Question.Choice<T>, state: UiState, ctx: ChoiceContext): Txt? =
    q.get(state.answers)?.let { q.label(it, ctx) }

fun eyeRxText(e: EyeRx): String = buildString {
    append(fmtD(e.sph))
    if (e.cyl != 0.0) append(" / ${fmtD(e.cyl)} × ${e.axis ?: "?"}")
    e.add?.let { append("  ADD ${fmtD(it)}") }
}

fun rxText(rx: KnownRx): String =
    listOfNotNull(rx.right?.let { "R ${eyeRxText(it)}" }, rx.left?.let { "L ${eyeRxText(it)}" }).joinToString("   ")

@Composable
fun ConfirmScreen(
    state: UiState,
    choiceContext: ChoiceContext,
    onLang: (Lang) -> Unit,
    onEdit: (questionId: String) -> Unit,
    onStaffMeasurements: (StaffMeasurements) -> Unit,
    onSeeResults: () -> Unit,
    onBack: () -> Unit,
) {
    val l = state.lang
    AppScaffold(l, onLang, title = S.confirmTitle[l], onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(S.confirmHint[l], style = MaterialTheme.typography.bodyLarge)
            SectionCard(S.profile[l]) {
                state.visibleQuestions.forEach { q ->
                    Column(Modifier.fillMaxWidth().clickable { onEdit(q.id) }.padding(vertical = 8.dp)) {
                        Text(q.text[l], style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                        val ans = answerText(q, state, choiceContext)
                        Text(
                            ans[l], style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            color = if (ans == S.notAnswered) MaterialTheme.colorScheme.error else Teal,
                        )
                    }
                    HorizontalDivider()
                }
            }
            if (state.staffMode) StaffMeasurementsCard(l, state.staff, onStaffMeasurements)
            if (!state.allAnswered) Text(S.answerAllFirst[l], color = MaterialTheme.colorScheme.error)
            BigButton(S.seeResults[l], onSeeResults, enabled = state.allAnswered, container = Teal)
            Disclaimer()
        }
    }
}

@Composable
private fun StaffMeasurementsCard(l: Lang, staff: StaffMeasurements, onChange: (StaffMeasurements) -> Unit) {
    var pd by remember { mutableStateOf(staff.pdMm?.toString() ?: "") }
    var fw by remember { mutableStateOf(staff.faceWidthMm?.toString() ?: "") }
    fun push() = onChange(StaffMeasurements(
        pdMm = pd.toDoubleOrNull()?.takeIf { it in 40.0..90.0 },
        faceWidthMm = fw.toDoubleOrNull()?.takeIf { it in 90.0..200.0 },
    ))
    SectionCard(S.staffMeasurements[l]) {
        OutlinedTextField(
            value = pd, onValueChange = { pd = it.filter { c -> c.isDigit() || c == '.' }.take(5); push() },
            label = { Text(S.pdLabel[l]) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        OutlinedTextField(
            value = fw, onValueChange = { fw = it.filter { c -> c.isDigit() || c == '.' }.take(5); push() },
            label = { Text(S.faceWidthLabel[l]) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
    }
}
