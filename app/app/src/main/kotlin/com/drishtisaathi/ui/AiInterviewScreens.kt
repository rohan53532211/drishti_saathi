package com.drishtisaathi.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.drishtisaathi.aivoice.AiInterviewRecord
import com.drishtisaathi.aivoice.AiLanguage
import com.drishtisaathi.aivoice.AiUiState
import com.drishtisaathi.aivoice.QuestionPlayer
import com.drishtisaathi.aivoice.Wav
import com.drishtisaathi.aivoice.WavRecorder
import com.drishtisaathi.camera.InstructionSpeaker
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.SAiVoice
import com.drishtisaathi.i18n.Txt
import kotlinx.coroutines.delay

/** Consent before any audio leaves the phone, with the interview language. "Use buttons instead" = standard interview. */
@Composable
fun AiConsentScreen(
    l: Lang,
    onLang: (Lang) -> Unit,
    speaker: InstructionSpeaker,
    onContinue: (AiLanguage) -> Unit,
    onUseButtons: () -> Unit,
) {
    var language by remember { mutableStateOf(AiLanguage.forApp(l)) }
    val body = Txt(SAiVoice.consentBody[language.appLang] + " " + SAiVoice.consentMore[language.appLang],
        SAiVoice.consentBody[language.appLang] + " " + SAiVoice.consentMore[language.appLang])
    LaunchedEffect(language) { speaker.speak(body, language.appLang) }
    AppScaffold(l, onLang, title = SAiVoice.title[l], onBack = { speaker.stop(); onUseButtons() }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(SAiVoice.language[l], style = MaterialTheme.typography.titleMedium)
            AiLanguage.entries.forEach { lang ->
                val label = when (lang) { AiLanguage.HINDI -> SAiVoice.hindi; AiLanguage.ENGLISH -> SAiVoice.english }
                BigButton(label[l], { language = lang; onLang(lang.appLang) }, selected = lang == language)
            }
            SectionCard(SAiVoice.title[l]) {
                Text(SAiVoice.consentBody[l], style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(SAiVoice.consentMore[l], style = MaterialTheme.typography.bodyLarge)
            }
            BigButton(SAiVoice.continueAi[l], { speaker.stop(); onContinue(language) }, container = Teal)
            BigButton(SAiVoice.useButtons[l], { speaker.stop(); onUseButtons() })
            Disclaimer()
        }
    }
}

private fun errorText(code: String?): Txt = when (code) {
    "EMPTY_TRANSCRIPT", "BAD_AUDIO" -> SAiVoice.errNotHeard
    "AUDIO_TOO_LONG" -> SAiVoice.errTooLong
    "MIC" -> SAiVoice.errMic
    else -> SAiVoice.errBackend.format(code ?: "?")
}

/**
 * One question at a time: the backend's audio plays (or the phone's voice reads the text if that failed), the customer
 * taps the mic, speaks, taps again (or it stops at 15 s), and sees what was heard.
 */
@Composable
fun AiInterviewScreen(
    l: Lang,
    onLang: (Lang) -> Unit,
    ui: AiUiState,
    speaker: InstructionSpeaker,
    onAnswer: (ByteArray) -> Unit,
    onMicFailed: () -> Unit,
    onRetry: () -> Unit,
    onEnd: () -> Unit,
    onContinue: (fellBack: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val recorder = remember { WavRecorder() }
    val player = remember { QuestionPlayer(context) }
    var recording by remember { mutableStateOf(false) }
    var micGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val answer by rememberUpdatedState(onAnswer)
    val micFailed by rememberUpdatedState(onMicFailed)

    fun stopAndSend() {
        if (!recording) return
        recording = false
        val wav = recorder.stop()
        if (wav == null) micFailed() else answer(wav)
    }
    fun startRecording() {
        player.stop(); speaker.stop()
        recording = recorder.start(onAutoStop = {})
        if (!recording) micFailed()
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        micGranted = ok
        if (ok) startRecording()
    }

    DisposableEffect(Unit) { onDispose { recorder.release(); player.stop(); speaker.stop() } }

    // Read each new question (and the closing line) aloud.
    LaunchedEffect(ui.question, ui.phase == AiUiState.Phase.ASKING || ui.phase == AiUiState.Phase.DONE) {
        val q = ui.question ?: return@LaunchedEffect
        if (ui.phase != AiUiState.Phase.ASKING && ui.phase != AiUiState.Phase.DONE) return@LaunchedEffect
        if (ui.audio == null || !player.play(ui.audio)) speaker.speak(Txt(q, q), l)
    }
    // Auto-stop at 15 s.
    LaunchedEffect(recording) {
        if (recording) { delay(Wav.MAX_SECONDS * 1000L); stopAndSend() }
    }
    if (ui.phase == AiUiState.Phase.FALLBACK) LaunchedEffect(Unit) { speaker.speak(SAiVoice.unavailable, l) }

    AppScaffold(l, onLang, title = SAiVoice.title[l], onBack = { recorder.release(); recording = false; onEnd() }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val p = ui.progress
            if (p.question > 0) {
                Text(SAiVoice.questionOf.format(p.question, p.expected)[l], style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { (p.question.toFloat() / p.expected.coerceAtLeast(1)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth())
            }
            when (ui.phase) {
                AiUiState.Phase.IDLE, AiUiState.Phase.STARTING -> Busy(SAiVoice.starting[l])
                AiUiState.Phase.FALLBACK -> {
                    Text(SAiVoice.unavailable[l], style = MaterialTheme.typography.headlineSmall, color = RoutineAmber, fontWeight = FontWeight.SemiBold)
                    BigButton(SAiVoice.continueButton[l], { onContinue(true) }, container = Teal)
                }
                AiUiState.Phase.DONE -> {
                    ui.lastTranscript?.let { Text(SAiVoice.youSaid.format(it)[l], style = MaterialTheme.typography.bodyLarge) }
                    Text(ui.question.orEmpty(), style = MaterialTheme.typography.headlineSmall)
                    Text(SAiVoice.finished[l], style = MaterialTheme.typography.titleMedium, color = OkGreen)
                    BigButton(SAiVoice.continueButton[l], { player.stop(); onContinue(false) }, container = Teal)
                }
                else -> {
                    Text(ui.question.orEmpty(), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 8.dp))
                    TextButton(onClick = {
                        val q = ui.question ?: return@TextButton
                        if (ui.audio == null || !player.play(ui.audio)) speaker.speak(Txt(q, q), l)
                    }, enabled = !recording && ui.phase == AiUiState.Phase.ASKING) { Text(SAiVoice.playAgain[l]) }

                    if (ui.phase == AiUiState.Phase.THINKING) Busy(SAiVoice.thinking[l])
                    else if (ui.phase == AiUiState.Phase.ASKING) {
                        BigButton(
                            if (recording) SAiVoice.tapToStop[l] else SAiVoice.tapToSpeak[l],
                            {
                                when {
                                    recording -> stopAndSend()
                                    !micGranted -> permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    else -> startRecording()
                                }
                            },
                            modifier = Modifier.heightIn(min = 120.dp),
                            container = if (recording) UrgentRed else Teal,
                        )
                        if (recording) Text(SAiVoice.recording[l], color = UrgentRed, style = MaterialTheme.typography.titleMedium)
                        if (!micGranted) Text(SAiVoice.micNeeded[l], style = MaterialTheme.typography.bodyMedium)
                    } else if (ui.phase == AiUiState.Phase.RETRY) {
                        Text(errorText(ui.errorCode)[l], color = UrgentRed, style = MaterialTheme.typography.titleMedium)
                        BigButton(SAiVoice.retry[l], onRetry, container = Teal)
                    }
                    ui.lastTranscript?.let { Text(SAiVoice.youSaid.format(it)[l], style = MaterialTheme.typography.bodyLarge) }
                    BigButton(SAiVoice.endInterview[l], { recorder.release(); recording = false; player.stop(); onEnd() },
                        enabled = ui.phase != AiUiState.Phase.THINKING)
                }
            }
        }
    }
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(28.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** Staff view and shop PDF: what was asked and heard. Never on the customer copy or sent to the optometrist. */
@Composable
fun AiTranscriptCard(record: AiInterviewRecord, l: Lang) {
    SectionCard(SAiVoice.transcriptTitle[l]) {
        record.turns.forEach { t ->
            Text(SAiVoice.transcriptQ.format(t.n, t.question)[l], style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(SAiVoice.transcriptA.format(t.answer ?: SAiVoice.transcriptNoAnswer[l])[l], style = MaterialTheme.typography.bodyMedium)
        }
        if (record.fellBack) Text(SAiVoice.transcriptFellBack[l], style = MaterialTheme.typography.bodySmall, color = RoutineAmber)
        Text(SAiVoice.transcriptNote[l], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    }
}
