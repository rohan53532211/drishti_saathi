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
import com.drishtisaathi.i18n.SFlow
import com.drishtisaathi.i18n.SAnamnesis
import com.drishtisaathi.i18n.SShop
import androidx.compose.foundation.clickable
import com.drishtisaathi.profile.gate

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
    /** Live try-on (addition 8); the argument is the frame to start with, null = first. */
    onTryOn: ((String?) -> Unit)? = null,
    /** Opens the 3D catalogue with recommended frames first (live 3D try-on and avatar from each frame). */
    onTry3D: (() -> Unit)? = null,
    /** Remote Optometrist Bridge card (send / waiting / reviewed). */
    remoteCard: @Composable () -> Unit = {},
    /** "For the shop" → Edit answers. */
    onEditAnswers: (() -> Unit)? = null,
    /** Frames this customer saved in 3D (with the session). */
    savedFrames: @Composable () -> Unit = {},
    /** Phase A safety gate: red flags and child myopia hide every lens suggestion. */
    gate: com.drishtisaathi.engine.profile.SafetyGate = state.gate(rules),
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
            // Order: functional vision profile, power and its source, referral, lens package, frames.
            // An urgent referral goes first.
            val urgent = a.referral.level == ReferralLevel.URGENT || gate.urgent
            // Urgent warning signs: ONE red banner at the very top (message and reasons), never hidden or softened.
            if (gate.urgent) com.drishtisaathi.profile.UrgentBanner(l, gate)
            else if (urgent) SectionCard(S.referral[l], container = UrgentBg) {
                Text(SShop.urgentTop[l], style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = UrgentRed)
                a.referral.reasons.forEach {
                    Text("• " + S.referralReason(it, rules)[l], style = MaterialTheme.typography.bodyLarge, color = UrgentRed)
                }
            }
            // The eye check runs for everyone: its ranges show even when lens advice is withheld.
            // With a red flag the power is for the optometrist only.
            val eyeLines = PowerPathText.quickLines(state)
            if (eyeLines.isNotEmpty()) SectionCard(SShop.eyeCheckTitle[l]) {
                if (gate.powerForOptometristOnly) Text(com.drishtisaathi.i18n.SProfile.forOptometristOnly[l],
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = UrgentRed)
                eyeLines.forEach { Text("• " + it[l], style = MaterialTheme.typography.bodyLarge) }
            }
            // A red flag or child myopia: the referral card replaces every lens and frame suggestion.
            if (!gate.lensAllowed) com.drishtisaathi.profile.ReferralCard(l, gate)
            SectionCard(SAnamnesis.profileTitle[l]) {
                PowerPathText.profileLines(state, rules).forEach { Text(it[l], style = MaterialTheme.typography.bodyLarge) }
            }

            val adv = a.advice.takeIf { gate.lensAllowed }
            if (!gate.lensAllowed) {
                if (!gate.urgent) ReferralBlock(a.referral, rules, l)
            } else if (adv == null) {
                SectionCard(S.lensPackage[l]) { Text(S.adviceWithheld[l], fontWeight = FontWeight.SemiBold) }
                if (!urgent) ReferralBlock(a.referral, rules, l)
            } else {
                // ---- Estimated band + starting trial lens ----
                SectionCard(S.startingTrialLens[l]) {
                    if (adv.eyes.isEmpty()) {
                        if (com.drishtisaathi.engine.StartingHint.DISTANCE_BLUR_START_MINUS !in adv.startingHints) Text(S.noStartingLens[l])
                        adv.startingHints.forEach {
                            Text("• " + S.startingHint(it)[l], style = MaterialTheme.typography.titleMedium, color = Teal)
                        }
                    } else {
                        adv.eyes.forEach { e ->
                            val eye = if (e.eye == Eye.RIGHT) "R" else "L"
                            Text("$eye  ${Fmt.trialLens(e.startingTrialLens.value)}", style = MaterialTheme.typography.titleLarge, color = Teal)
                            if (e.bandUncertain) Text(S.bandUncertain[l], style = MaterialTheme.typography.bodyMedium)
                            else Text("${S.estimatedBand[l]}: ${Fmt.band(e.band.value, l)} ${Fmt.meta(e.band, l)}",
                                style = MaterialTheme.typography.bodyMedium)
                            e.correctedWithShopRecords?.let {
                                Text(S.correctedWithRecords.format(it)[l], style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                        Text(S.startingLensHint[l], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    PowerPathText.sourceLine(adv)?.let { Text(it[l], style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
                    val compare = PowerPathText.compareLines(state, adv)
                    if (compare.isNotEmpty()) {
                        HorizontalDivider()
                        Text(SAnamnesis.compareHeading[l], style = MaterialTheme.typography.titleSmall, color = RoutineAmber)
                        compare.forEach { Text("• " + it[l], style = MaterialTheme.typography.bodyMedium) }
                    }
                    adv.add?.let { add ->
                        HorizontalDivider()
                        Text("${S.readingAdd[l]}: ${Fmt.band(add.value, l)}", style = MaterialTheme.typography.titleMedium)
                        Text(Fmt.meta(add, l), style = MaterialTheme.typography.bodySmall)
                        if (adv.confirmAddWithReadyReader) {
                            Text("• " + S.confirmReadyReader[l], style = MaterialTheme.typography.titleMedium, color = Teal)
                        }
                    }
                }

                if (!urgent) ReferralBlock(a.referral, rules, l)

                // ---- Lens package ----
                SectionCard(S.lensPackage[l]) {
                    Labeled(S.lensType[l], S.lensType(adv.lensType.value)[l], Fmt.meta(adv.lensType, l))
                    Labeled(S.material[l], S.material(adv.materialId.value)[l], Fmt.meta(adv.materialId, l))
                    Labeled(S.coatings[l],
                        if (adv.coatings.isEmpty()) S.noCoatings[l] else adv.coatings.joinToString("\n") { "• " + S.coating(it)[l] }, null)
                }

                // ---- Frames ----
                SectionCard(S.suggestedFrames[l]) {
                    if (onTry3D != null) BigButton(com.drishtisaathi.i18n.S3D.tryIn3d[l], onTry3D, container = Teal)
                    if (onTryOn != null && r.frames.any { it.frame.overlay != null }) {
                        BigButton(SFlow.tryOnAll[l], { onTryOn(null) }, container = if (onTry3D != null) null else Teal)
                    }
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
                        if (onTryOn != null && rf.frame.overlay != null) {
                            TextButton({ onTryOn(rf.frame.id) }) { Text(SFlow.tryOn[l]) }
                        }
                    }
                    if (r.frames.size >= 2) {
                        HorizontalDivider()
                        if (state.compareIds.size == 2) BigButton(S.compareTwo[l], onCompare, container = Teal)
                        else Text(S.compareHint[l], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
                savedFrames()
            }

            remoteCard()

            // ---- For the shop (folded: the customer may be looking at this screen) ----
            var shopOpen by remember { mutableStateOf(false) }
            SectionCard(SShop.forShop[l], modifier = Modifier.clickable { shopOpen = !shopOpen }) {
                Text((if (shopOpen) "− " else "+ ") + SShop.forShopHint[l], style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary)
            }
            if (shopOpen) {
            if (state.glassesOn) SectionCard(SFlow.glassesOnStaff[l], container = RoutineBg) {
                Text(SFlow.glassesOnNote[l], color = RoutineAmber)
            }
            if (a.notes.isNotEmpty()) {
                SectionCard(S.notesForStaff[l]) {
                    a.notes.forEach { Text("• " + S.note(it, rules.oldNumbers.freshMaxYears)[l]) }
                }
            }
            SectionCard(S.staffOnly[l]) {
                Labeled(S.pd[l], Fmt.pd(a, l), Fmt.meta(a.pdMm, l))
                Labeled(S.faceWidth[l], Fmt.mm(a.faceWidthMm.value), Fmt.meta(a.faceWidthMm, l))
                state.face?.let { f ->
                    f.bridgeWidthMm?.let { Labeled(SFlow.bridgeWidth[l], Fmt.mm(it.value), Fmt.meta(it, l)) }
                    f.faceShape?.let { shape ->
                        val suits = f.suggestedFrameShapes.joinToString(", ") { S.frameShape(it)[l] }
                        Labeled(
                            SFlow.faceShape[l], SFlow.faceShape(shape.value)[l],
                            if (suits.isEmpty()) null else "${SFlow.shapesThatSuit[l]}: $suits",
                        )
                    }
                    Labeled(SFlow.framesMeasured[l], f.framesUsed.toString(), null)
                }
                state.captureStatus?.takeIf { it != com.drishtisaathi.engine.face.FaceCapture.Status.OK }?.let {
                    Text(SFlow.captureStatus(it)[l], style = MaterialTheme.typography.bodySmall, color = RoutineAmber)
                }
                Labeled(SFlow.screenTests[l], if (state.testsDone) SFlow.testsDone[l] else SFlow.testsNotDone[l], null)
            }

            if (r.frames.isNotEmpty()) SectionCard(S.suggestedFrames[l]) {
                Text(S.demoCatalogue[l], style = MaterialTheme.typography.bodySmall, color = RoutineAmber, fontWeight = FontWeight.SemiBold)
                r.frames.forEach { rf ->
                    Text("${rf.frame.name}: ${S.score[l]} ${"%.2f".format(rf.score)} · ${S.confidence(rf.thicknessConfidence)[l]}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
            state.aiInterview?.let { AiTranscriptCard(it, l) }
            if (state.sessionId != null) VerificationCard(l, state.verification, onSaveVerification)
            onEditAnswers?.let { BigButton(SShop.editAnswers[l], it) }
            }

            extraActions()
            BigButton(SShop.finish[l], onDone, container = Teal)
            BigButton(S.deleteSession[l], { confirmDelete = true }, container = UrgentRed)
            if (!urgent) Text(SShop.phoneCheckNote[l], style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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

/** Referral status and its reasons. */
@Composable
private fun ReferralBlock(referral: com.drishtisaathi.engine.Referral, rules: RulesConfig, l: Lang) {
    val (fg, bg) = when (referral.level) {
        ReferralLevel.URGENT -> UrgentRed to UrgentBg
        ReferralLevel.ROUTINE -> RoutineAmber to RoutineBg
        ReferralLevel.NONE -> OkGreen to OkBg
    }
    SectionCard(S.referral[l], container = bg) {
        Text(S.referralLevel(referral.level)[l], color = fg, style = MaterialTheme.typography.titleMedium)
    }
    ReferralReasonGroups(referral, rules, l)
}
