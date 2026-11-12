package com.drishtisaathi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.drishtisaathi.camera.InstructionSpeaker
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.SFlow

/**
 * Before the card check, face capture and screen tests: the customer takes their glasses off (read aloud too).
 * Glasses change the measured far point and can hide the eyes from the camera. Staff mode may override.
 */
@Composable
fun GlassesOffScreen(
    l: Lang,
    onLang: (Lang) -> Unit,
    staffMode: Boolean,
    speaker: InstructionSpeaker,
    onOff: () -> Unit,
    onKeepOn: () -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(l) { speaker.speak(SFlow.glassesOffBody, l) }
    AppScaffold(l, onLang, title = SFlow.glassesOffTitle[l], onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(SFlow.glassesOffTitle[l], style = MaterialTheme.typography.headlineMedium, color = Teal)
            Text(SFlow.glassesOffBody[l], style = MaterialTheme.typography.bodyLarge)
            BigButton(SFlow.glassesOffDone[l], { speaker.stop(); onOff() }, container = Teal)
            if (staffMode) TextButton(onClick = { speaker.stop(); onKeepOn() }) { Text(SFlow.glassesOnStaff[l]) }
        }
    }
}

/** Power path YES with old glasses or blur still reported: offer the optional quick check (shown to staff). */
@Composable
fun CompareOfferScreen(
    l: Lang,
    onLang: (Lang) -> Unit,
    speaker: InstructionSpeaker,
    onCheck: () -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(l) { speaker.speak(com.drishtisaathi.i18n.SAnamnesis.compareBody, l) }
    val s = com.drishtisaathi.i18n.SAnamnesis
    AppScaffold(l, onLang, title = s.compareTitle[l], onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(s.compareTitle[l], style = MaterialTheme.typography.headlineMedium, color = Teal)
            Text(s.compareBody[l], style = MaterialTheme.typography.bodyLarge)
            BigButton(s.compareYes[l], { speaker.stop(); onCheck() }, container = Teal)
            BigButton(s.compareNo[l], { speaker.stop(); onSkip() })
        }
    }
}
