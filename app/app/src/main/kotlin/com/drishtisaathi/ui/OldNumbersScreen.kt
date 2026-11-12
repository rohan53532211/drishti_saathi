package com.drishtisaathi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
private data class EyeDraft(val sph: Double = 0.0, val cyl: Double = 0.0, val axis: Int = 180, val add: Double = 0.0) {
    fun toRx() = EyeRx(sph, cyl, if (cyl != 0.0) axis else null, add.takeIf { it > 0 })
    companion object {
        fun from(rx: EyeRx?) = rx?.let { EyeDraft(it.sph, it.cyl, it.axis ?: 180, it.add ?: 0.0) } ?: EyeDraft()
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

    AppScaffold(l, onLang, title = S.oldNumbersTitle[l], onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(S.oldNumbersHint[l], style = MaterialTheme.typography.bodyLarge)
            SectionCard(S.rightEye[l]) { EyeEditor(right) { right = it } }
            TextButton(onClick = { left = right }) { Text(S.sameAsRight[l]) }
            SectionCard(S.leftEye[l]) { EyeEditor(left) { left = it } }
            BigButton(S.saveNumbers[l], { onSave(KnownRx(right.toRx(), left.toRx())) }, container = Teal)
            BigButton(S.noNumbers[l], onNone)
        }
    }
}

@Composable
private fun EyeEditor(d: EyeDraft, onChange: (EyeDraft) -> Unit) {
    Stepper("SPH", fmtD(d.sph), { onChange(d.copy(sph = (d.sph + it).coerceIn(-20.0, 20.0))) })
    Stepper("CYL", fmtD(d.cyl), { onChange(d.copy(cyl = (d.cyl + it).coerceIn(-8.0, 8.0))) })
    if (d.cyl != 0.0) {
        Stepper("AXIS", "${d.axis}°", { delta ->
            val step = if (kotlin.math.abs(delta) >= 1.0) 10 else 5
            val next = d.axis + if (delta > 0) step else -step
            onChange(d.copy(axis = ((next - 1).mod(180)) + 1)) // keep 1..180
        })
    }
    Stepper("ADD", if (d.add == 0.0) "—" else fmtD(d.add), { onChange(d.copy(add = (d.add + it).coerceIn(0.0, 4.0))) })
}

/** Label, value and −1 / −0.25 / +0.25 / +1 buttons (axis uses ±5 / ±10). */
@Composable
private fun Stepper(label: String, value: String, onDelta: (Double) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(52.dp))
        StepBtn("−1") { onDelta(-1.0) }
        StepBtn("−¼") { onDelta(-0.25) }
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        StepBtn("+¼") { onDelta(0.25) }
        StepBtn("+1") { onDelta(1.0) }
    }
}

@Composable
private fun StepBtn(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
