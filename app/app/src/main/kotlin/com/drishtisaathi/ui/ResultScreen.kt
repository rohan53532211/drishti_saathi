package com.drishtisaathi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.drishtisaathi.Verification
import com.drishtisaathi.engine.Eye
import com.drishtisaathi.engine.ReferralLevel
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S

/** M6: one screen with profile, bands + confidence, starting trial lens, lens package, frames, referral status. */
@Composable
fun ResultScreen(
    state: UiState,
    rules: RulesConfig,
    onLang: (Lang) -> Unit,
    onSaveVerification: (Verification) -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
    extraActions: @Composable () -> Unit = {},
    onToggleCompare: (String) -> Unit = {},
    onCompare: () -> Unit = {},
) {
    val l = state.lang
    val r = state.result ?: return
    val a = r.assessment
    var confirmDelete by remember { mutableStateOf(false) }
    val title = state.sessionId?.let { "${S.shopSummary[l]} · #$it" } ?: S.shopSummary[l]

    AppScaffold(l, onLang, title = title, onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- Referral status ----
            val (fg, bg) = when (a.referral.level) {
                ReferralLevel.URGENT -> UrgentRed to UrgentBg
                ReferralLevel.ROUTINE -> RoutineAmber to RoutineBg
                ReferralLevel.NONE -> OkGreen to OkBg
            }
            SectionCard(S.referral[l], container = bg) {
                Text(S.referralLevel(a.referral.level)[l], color = fg, style = MaterialTheme.typography.titleMedium)
                a.referral.reasons.forEach { Text("• " + S.referralReason(it, rules.ages.childReferralUnder)[l], color = fg) }
            }

            val adv = a.advice
            if (adv == null) {
                SectionCard(S.lensPackage[l]) { Text(S.adviceWithheld[l], fontWeight = FontWeight.SemiBold) }
            } else {
                // ---- Estimated band + starting trial lens ----
                SectionCard(S.startingTrialLens[l]) {
                    if (adv.eyes.isEmpty()) {
                        Text(S.noStartingLens[l])
                    } else {
                        adv.eyes.forEach { e ->
                            val eye = if (e.eye == Eye.RIGHT) "R" else "L"
                            Text("$eye  ${Fmt.trialLens(e.startingTrialLens.value)}", style = MaterialTheme.typography.titleLarge, color = Teal)
                            Text("${S.estimatedBand[l]}: ${Fmt.band(e.band.value, l)} ${Fmt.meta(e.band, l)}",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(S.startingLensHint[l], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    adv.add?.let { add ->
                        HorizontalDivider()
                        Text("${S.readingAdd[l]}: ${Fmt.band(add.value, l)}", style = MaterialTheme.typography.titleMedium)
                        Text(Fmt.meta(add, l), style = MaterialTheme.typography.bodySmall)
                    }
                }

                // ---- Lens package ----
                SectionCard(S.lensPackage[l]) {
                    Labeled(S.lensType[l], S.lensType(adv.lensType.value)[l], Fmt.meta(adv.lensType, l))
                    Labeled(S.material[l], S.material(adv.materialId.value)[l], Fmt.meta(adv.materialId, l))
                    Labeled(S.coatings[l],
                        if (adv.coatings.isEmpty()) S.noCoatings[l] else adv.coatings.joinToString("\n") { "• " + S.coating(it)[l] }, null)
                }

                // ---- Frames ----
                SectionCard(S.suggestedFrames[l]) {
                    r.frames.forEachIndexed { i, rf ->
                        if (i > 0) HorizontalDivider()
                        Text("${i + 1}. ${rf.frame.name}", style = MaterialTheme.typography.titleMedium)
                        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Fmt.label(rf, l)?.let { MatchChip(rf.label!!, it) }
                            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                            androidx.compose.material3.Checkbox(checked = rf.frame.id in state.compareIds, onCheckedChange = { onToggleCompare(rf.frame.id) })
                            Text(S.compareTick[l], style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(Fmt.frameLine(rf, l), style = MaterialTheme.typography.bodyMedium)
                        ReasonBullets(rf, l)
                        if (state.staffMode) Text(
                            "${S.score[l]} ${"%.2f".format(rf.score)} · ${S.confidence(rf.thicknessConfidence)[l]}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    if (r.frames.size >= 2) {
                        HorizontalDivider()
                        if (state.compareIds.size == 2) BigButton(S.compareTwo[l], onCompare, container = Teal)
                        else Text(S.compareHint[l], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }

            // ---- Notes + measurements ----
            if (a.notes.isNotEmpty()) {
                SectionCard(S.notesForStaff[l]) {
                    a.notes.forEach { Text("• " + S.note(it, rules.oldNumbers.freshMaxYears)[l]) }
                }
            }
            SectionCard(S.staffOnly[l]) {
                Labeled(S.pd[l], Fmt.mm(a.pdMm.value), Fmt.meta(a.pdMm, l))
                Labeled(S.faceWidth[l], Fmt.mm(a.faceWidthMm.value), Fmt.meta(a.faceWidthMm, l))
            }

            if (state.staffMode && state.sessionId != null) VerificationCard(l, state.verification, onSaveVerification)

            extraActions()
            BigButton(S.done[l], onDone, container = Teal)
            BigButton(S.deleteSession[l], { confirmDelete = true }, container = UrgentRed)
            Disclaimer()
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(S.deleteConfirm[l], style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { TextButton({ confirmDelete = false; onDelete() }) { Text(S.delete[l], color = UrgentRed) } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text(S.cancel[l]) } },
        )
    }
}

@Composable
private fun Labeled(label: String, value: String, meta: String?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        Text(value, style = MaterialTheme.typography.titleMedium)
        meta?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Learning loop (innovation 8): final trial lens + PD meter, stored as a paired record. */
@Composable
private fun VerificationCard(l: Lang, v: Verification, onSave: (Verification) -> Unit) {
    var right by remember(v) { mutableStateOf(v.finalTrialRight) }
    var left by remember(v) { mutableStateOf(v.finalTrialLeft) }
    var add by remember(v) { mutableStateOf(v.finalAdd) }
    var pd by remember(v) { mutableStateOf(v.pdMeterMm?.toString() ?: "") }
    SectionCard(S.verification[l]) {
        Text(S.verificationHint[l], style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(right, { right = it.take(40) }, label = { Text(S.finalTrialRight[l]) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(left, { left = it.take(40) }, label = { Text(S.finalTrialLeft[l]) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(add, { add = it.take(10) }, label = { Text(S.finalAdd[l]) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            pd, { pd = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, label = { Text(S.pdMeter[l]) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
        )
        BigButton(S.saveVerification[l], { onSave(Verification(right.trim(), left.trim(), add.trim(), pd.toDoubleOrNull())) })
        v.verifiedAt?.let { Text("✓ " + S.verified[l], color = OkGreen, fontWeight = FontWeight.SemiBold) }
    }
}
