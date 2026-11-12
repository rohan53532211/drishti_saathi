package com.drishtisaathi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.drishtisaathi.engine.EyeRx
import com.drishtisaathi.engine.KnownRx
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S
import com.drishtisaathi.i18n.fmtD

/** Editable copy of one eye's numbers. Steppers instead of typing: no sign/decimal keyboard problems. */
internal data class EyeDraft(val sph: Double = 0.0, val cyl: Double = 0.0, val axis: Int = 180, val add: Double = 0.0) {
    fun toRx() = EyeRx(sph, cyl, if (cyl != 0.0) axis else null, add.takeIf { it > 0 })
    val allZero: Boolean get() = sph == 0.0 && cyl == 0.0 && add == 0.0
    companion object {
        fun from(rx: EyeRx?) = rx?.let { EyeDraft(it.sph, it.cyl, it.axis ?: 180, it.add ?: 0.0) } ?: EyeDraft()
        const val AXIS_STEP = 5
        /** Axis stepper: 0..180 in steps of 5, clamped at both ends. */
        fun stepAxis(axis: Int, up: Boolean): Int = (axis + if (up) AXIS_STEP else -AXIS_STEP).coerceIn(0, 180)
    }
}

@Composable
fun OldNumbersScreen(
    lang: Lang,
    initial: KnownRx?,
    onLang: (Lang) -> Unit,
    onSave: (KnownRx) -> Unit,
    onNone: () -> Unit,
    onBack: () -> Unit,
) {
    val l = lang
    var right by remember { mutableStateOf(EyeDraft.from(initial?.right)) }
    var left by remember { mutableStateOf(EyeDraft.from(initial?.left)) }
    var askAllZero by remember { mutableStateOf(false) }
    fun save() = onSave(KnownRx(right.toRx(), left.toRx()))

    AppScaffold(l, onLang, title = S.oldNumbersTitle[l], onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(S.oldNumbersHint[l], style = MaterialTheme.typography.bodyLarge)
            SectionCard(S.rightEye[l]) { EyeEditor(right) { right = it } }
            TextButton(onClick = { left = right }) { Text(S.sameAsRight[l]) }
            SectionCard(S.leftEye[l]) { EyeEditor(left) { left = it } }
            // All zeros usually means nothing was entered: ask before saving as plano.
            BigButton(S.saveNumbers[l], { if (right.allZero && left.allZero) askAllZero = true else save() }, container = Teal)
            BigButton(S.noNumbers[l], onNone)
        }
    }

    if (askAllZero) {
        AlertDialog(
            onDismissRequest = { askAllZero = false },
            title = { Text(S.allZeroTitle[l]) },
            text = { Text(S.allZeroBody[l]) },
            confirmButton = { TextButton(onClick = { askAllZero = false; onNone() }) { Text(S.allZeroNoNumbers[l]) } },
            dismissButton = { TextButton(onClick = { askAllZero = false; save() }) { Text(S.allZeroSavePlano[l]) } },
        )
    }
}

@Composable
private fun EyeEditor(d: EyeDraft, onChange: (EyeDraft) -> Unit) {
    PowerStepper("SPH", fmtD(d.sph)) { onChange(d.copy(sph = (d.sph + it).coerceIn(-20.0, 20.0))) }
    PowerStepper("CYL", fmtD(d.cyl)) { onChange(d.copy(cyl = (d.cyl + it).coerceIn(-8.0, 8.0))) }
    if (d.cyl != 0.0) {
        StepperRow("AXIS", "${d.axis}°", listOf(
            "−5" to { onChange(d.copy(axis = EyeDraft.stepAxis(d.axis, up = false))) },
            "+5" to { onChange(d.copy(axis = EyeDraft.stepAxis(d.axis, up = true))) },
        ))
    }
    PowerStepper("ADD", if (d.add == 0.0) "—" else fmtD(d.add)) { onChange(d.copy(add = (d.add + it).coerceIn(0.0, 4.0))) }
}

/** Dioptre stepper: −1 / −¼ / +¼ / +1. */
@Composable
private fun PowerStepper(label: String, value: String, onDelta: (Double) -> Unit) = StepperRow(label, value, listOf(
    "−1" to { onDelta(-1.0) }, "−¼" to { onDelta(-0.25) }, "+¼" to { onDelta(0.25) }, "+1" to { onDelta(1.0) },
))

/**
 * Label and value on one line (value in a fixed-width, single-line slot so "−1.75" never wraps into a column of
 * characters), buttons on their own row underneath sharing the width.
 */
@Composable
private fun StepperRow(label: String, value: String, buttons: List<Pair<String, () -> Unit>>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(64.dp), maxLines = 1, softWrap = false)
            Text(
                value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                modifier = Modifier.widthIn(min = 112.dp),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            buttons.forEach { (text, onClick) ->
                FilledTonalButton(
                    onClick = onClick, modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                ) { Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false) }
            }
        }
    }
}
