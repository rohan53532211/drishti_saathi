package com.drishtisaathi.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.SVoice
import com.drishtisaathi.ui.BigButton
import com.drishtisaathi.ui.Teal
import com.drishtisaathi.ui.TealLight

/** Builds the app's single voice controller (Android TTS + SpeechRecognizer + lexicon from assets). */
fun createVoiceController(context: Context, readAloudByDefault: Boolean = true): VoiceController {
    val app = context.applicationContext
    val lexicon = VoiceLexicon.parse(app.assets.open(VoiceLexicon.ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() })
    return VoiceController(AndroidTtsSpeaker(app), AndroidSpeechInput(app), VoiceParser(lexicon), readAloudByDefault)
}

/**
 * For MainActivity: one controller for the whole app, released when the composition leaves.
 * `val voice = rememberVoiceController()` at the top of `DrishtiApp`, then `InterviewScreen(…, voice = voice)`.
 */
@Composable
fun rememberVoiceController(readAloudByDefault: Boolean = true): VoiceController {
    val context = LocalContext.current
    val voice = remember { createVoiceController(context, readAloudByDefault) }
    DisposableEffect(voice) { onDispose { voice.release() } }
    return voice
}

private fun hasMic(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** Voice strip for the interview: read-aloud switch, mic button, listening/read-back status, ✓ / ✗. */
@Composable
fun VoicePanel(voice: VoiceController, lang: Lang, modifier: Modifier = Modifier) {
    val st by voice.state.collectAsState()
    val tts by voice.ttsStatus.collectAsState()
    val mode by voice.recognitionMode.collectAsState()
    val context = LocalContext.current
    var askConsent by remember { mutableStateOf(false) }

    LaunchedEffect(tts, mode) { voice.refreshAvailability() }
    // Permission can change in system settings while we are away.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        voice.setMicPermission(hasMic(context))
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) voice.setMicPermission(hasMic(context)) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    /** Second tap after the permission was granted: may still need the online consent. */
    fun tapMic() {
        if (voice.onMicTapped() == MicAction.ASK_NETWORK_CONSENT) askConsent = true
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            voice.setMicPermission(true)
            tapMic()
        } else {
            voice.onMicPermissionDenied()
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = TealLight),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔊 " + SVoice.readAloud[lang], Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = st.readAloud, onCheckedChange = { voice.setReadAloud(it) })
            }
            BigButton(
                text = "🎤 " + (if (st.listenEnabled) SVoice.micStop else SVoice.micStart)[lang],
                onClick = {
                    when (voice.onMicTapped()) {
                        MicAction.REQUEST_PERMISSION -> permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        MicAction.ASK_NETWORK_CONSENT -> askConsent = true
                        else -> {}
                    }
                },
                selected = st.listenEnabled,
            )

            when (val d = st.dialogue) {
                is DialogueState.Asking -> Text(SVoice.speaking[lang], style = MaterialTheme.typography.bodyLarge)
                is DialogueState.Listening -> {
                    Text(SVoice.listening[lang], style = MaterialTheme.typography.titleMedium, color = Teal, fontWeight = FontWeight.SemiBold)
                    if (d.partial.isNotBlank()) Text(SVoice.heard.format(d.partial)[lang], style = MaterialTheme.typography.bodyLarge)
                }
                is DialogueState.Confirming -> {
                    if (d.heard.isNotBlank()) Text(SVoice.heard.format(d.heard)[lang], style = MaterialTheme.typography.bodyMedium)
                    Text(d.readBack[lang], style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BigButton(SVoice.confirmYes[lang], { voice.confirm(true) }, Modifier.weight(1f), container = Teal)
                        BigButton(SVoice.confirmNo[lang], { voice.confirm(false) }, Modifier.weight(1f))
                    }
                }
                else -> {}
            }
            st.message?.let { Text(it[lang], style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error) }

            if (st.readAloud && lang == Lang.HI && tts == TtsStatus.ENGLISH_ONLY) {
                Text(SVoice.noHindiVoice[lang], style = MaterialTheme.typography.bodySmall)
            }
            if (st.readAloud && tts == TtsStatus.UNAVAILABLE) Text(SVoice.ttsUnavailable[lang], style = MaterialTheme.typography.bodySmall)
            if (st.listenEnabled) {
                Text(
                    (if (mode == RecognitionMode.ON_DEVICE) SVoice.onDeviceNote else SVoice.onlineNote)[lang],
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(SVoice.buttonsAlways[lang], style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (askConsent) {
        AlertDialog(
            onDismissRequest = { askConsent = false },
            title = { Text(SVoice.onlineConsentTitle[lang]) },
            text = { Text(SVoice.onlineConsentBody[lang]) },
            confirmButton = {
                TextButton(onClick = {
                    askConsent = false
                    voice.setNetworkConsent(true)
                    voice.onMicTapped()
                }) { Text(SVoice.onlineConsentAgree[lang]) }
            },
            dismissButton = {
                TextButton(onClick = {
                    askConsent = false
                    voice.setNetworkConsent(false)
                }) { Text(SVoice.onlineConsentDecline[lang]) }
            },
        )
    }
}
