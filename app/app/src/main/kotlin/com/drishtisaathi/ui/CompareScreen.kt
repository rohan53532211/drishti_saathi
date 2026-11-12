package com.drishtisaathi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.drishtisaathi.UiState
import com.drishtisaathi.engine.DrishtiEngine
import com.drishtisaathi.engine.RankedFrame
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S
import java.util.Locale
import kotlin.math.abs

/** Addition 3: two ranked frames side by side. In numeric rows the better value is shown in green. */
@Composable
fun CompareScreen(state: UiState, engine: DrishtiEngine, rules: RulesConfig, onLang: (Lang) -> Unit, onBack: () -> Unit) {
    val l = state.lang
    val result = state.result ?: return
    val pair = state.compareIds.mapNotNull { id -> result.frames.firstOrNull { it.frame.id == id } }
    if (pair.size != 2) return
    val (a, b) = pair
    val assessment = result.assessment
    val tA = engine.thicknessByMaterial(a.frame, assessment)
    val tB = engine.thicknessByMaterial(b.frame, assessment)
    val recommended = assessment.advice?.materialId?.value

    AppScaffold(l, onLang, title = S.compareTitle[l], onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Names
            Pair(
                { Text(a.frame.name, style = MaterialTheme.typography.titleLarge, color = Teal) },
                { Text(b.frame.name, style = MaterialTheme.typography.titleLarge, color = Teal) },
            )
            Section(S.rowLabel[l]) {
                Pair(
                    { a.label?.let { MatchChip(it, S.matchLabel(it)[l]) } },
                    { b.label?.let { MatchChip(it, S.matchLabel(it)[l]) } },
                )
            }
            Section(S.rowSize[l]) { TextPair(a.frame.sizeMark, b.frame.sizeMark) }
            if (state.staffMode) Text(S.demoCatalogue[l], style = MaterialTheme.typography.bodySmall, color = RoutineAmber)
            // rules_update.md rule 6: no "your face" claim when the camera measurement is not card-calibrated
            val widthRow = if (assessment.fitMeasurementsUncalibrated) S.rowWidthUncal else S.rowWidth
            Section(widthRow.format(assessment.faceWidthMm.value)[l]) {
                // closer to the ideal width is better
                TextPair(
                    widthText(a), widthText(b),
                    better = compareBy(abs(a.fitDeltaMm), abs(b.fitDeltaMm)),
                )
            }
            Section(S.rowShape[l]) {
                TextPair(shapeText(a, l), shapeText(b, l))
            }
            if (tA != null && tB != null) {
                rules.materials.forEach { m ->
                    val ta = tA.getValue(m.id)
                    val tb = tB.getValue(m.id)
                    val mark = if (m.id == recommended) "  (${S.recommended[l]})" else ""
                    Section(S.rowThickness.format(S.materialShort(m.id))[l] + mark) {
                        TextPair(Fmt.mm(ta), Fmt.mm(tb), better = compareBy(ta, tb), bold = m.id == recommended)
                    }
                }
            } else {
                // Said once for both frames, not repeated per frame
                Section(S.rowThickness.format("—")[l]) {
                    Text(S.note(com.drishtisaathi.engine.Note.THICKNESS_AFTER_TRIAL_LENS, rules.oldNumbers.freshMaxYears)[l])
                }
            }
            Section(S.rowPrice[l]) {
                TextPair(price(a, l), price(b, l), better = compareBy(a.frame.priceInr.toDouble(), b.frame.priceInr.toDouble()))
            }
            Section(S.rowWhy[l]) {
                Pair({ Column { ReasonBullets(a, l) } }, { Column { ReasonBullets(b, l) } })
            }
            if (state.staffMode) Section(S.rowScores[l]) { TextPair(scores(a), scores(b), better = compareBy(-a.score, -b.score)) }
            Disclaimer()
        }
    }
}

/** -1 → left better, 1 → right better, 0 → tie (lower value wins). */
private fun compareBy(left: Double, right: Double): Int = when {
    abs(left - right) < 0.05 -> 0
    left < right -> -1
    else -> 1
}

private fun widthText(rf: RankedFrame): String {
    val d = rf.fitDeltaMm
    val sign = if (d >= 0) "+" else "−"
    return String.format(Locale.US, "%.0f mm (%s%.0f)", rf.frameWidthMm, sign, abs(d))
}

private fun price(rf: RankedFrame, l: Lang): String =
    "₹" + String.format(Locale.US, "%,d", rf.frame.priceInr) + if (rf.withinBudget) "" else " · ${S.overBudget[l]}"

private fun shapeText(rf: RankedFrame, l: Lang) =
    "${S.frameShape(rf.frame.shape)[l]} · ${S.rim(rf.frame.rim)[l]} · ${S.frameMaterial(rf.frame.material)[l]}"

private fun scores(rf: RankedFrame) =
    String.format(Locale.US, "%.2f · %.2f · %.2f · %.2f · %.2f", rf.score, rf.fitScore, rf.thinScore, rf.styleScore, rf.budgetScore)

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    HorizontalDivider()
    Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    content()
}

@Composable
private fun Pair(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) { left() }
        Column(Modifier.weight(1f)) { right() }
    }
}

@Composable
private fun TextPair(left: String, right: String, better: Int = 0, bold: Boolean = false) {
    @Composable
    fun cell(text: String, isBetter: Boolean) = Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = if (bold || isBetter) FontWeight.Bold else FontWeight.Normal,
        color = if (isBetter) OkGreen else Color.Unspecified,
    )
    Pair({ cell(left, better == -1) }, { cell(right, better == 1) })
}
