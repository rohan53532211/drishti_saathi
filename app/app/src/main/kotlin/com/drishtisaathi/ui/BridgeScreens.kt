package com.drishtisaathi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.drishtisaathi.UiState
import com.drishtisaathi.bridge.BridgeConfig
import com.drishtisaathi.bridge.BridgeConsent
import com.drishtisaathi.bridge.BridgeHealth
import com.drishtisaathi.camera.InstructionSpeaker
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.SBridge
import com.drishtisaathi.i18n.Txt
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun time(ms: Long) = SimpleDateFormat("dd MMM, HH:mm", Locale.US).format(Date(ms))

/**
 * Consent before anything leaves the phone. The text is read aloud; staff tick that the customer said yes (spoken
 * consent), then the customer taps "I agree, send" (tapped consent). Both go with the profile; the server checks them.
 */
@Composable
fun BridgeConsentScreen(
    l: Lang,
    onLang: (Lang) -> Unit,
    speaker: InstructionSpeaker,
    busy: Boolean,
    error: Txt?,
    onSend: (BridgeConsent) -> Unit,
    onCancel: () -> Unit,
) {
    var spoken by remember { mutableStateOf(false) }
    LaunchedEffect(l) { speaker.speak(SBridge.consentBody, l) }
    AppScaffold(l, onLang, title = SBridge.cardTitle[l], onBack = { speaker.stop(); onCancel() }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(SBridge.consentTitle[l], style = MaterialTheme.typography.headlineMedium, color = Teal)
            Text(SBridge.consentBody[l], style = MaterialTheme.typography.bodyLarge)
            SectionCard(SBridge.cardTitle[l]) {
                Text("✓ " + SBridge.sentList[l], style = MaterialTheme.typography.bodyLarge)
                Text("✗ " + SBridge.notSentList[l], style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = OkGreen)
            }
            TextButton(onClick = { speaker.speak(SBridge.consentBody, l) }) { Text(SBridge.readAgain[l]) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = spoken, onCheckedChange = { spoken = it })
                Text(SBridge.spokenTick[l], style = MaterialTheme.typography.bodyLarge)
            }
            BigButton(
                if (busy) SBridge.sending[l] else SBridge.agreeSend[l],
                { speaker.stop(); onSend(BridgeConsent(System.currentTimeMillis(), spoken = spoken, tapped = true)) },
                enabled = spoken && !busy, container = Teal,
            )
            BigButton(SBridge.dontSend[l], { speaker.stop(); onCancel() })
            error?.let { Text(it[l], color = UrgentRed, style = MaterialTheme.typography.bodyLarge) }
            Disclaimer()
        }
    }
}

/**
 * Summary card: send button; once sent, the case number (large), where the optometrist reviews it, and Waiting until
 * the reply arrives. While the card is on screen it checks every 15 s ([onPoll], quiet); the view model posts a
 * "Reply received" notification with sound when the reply comes in.
 */
@Composable
fun RemoteReviewCard(
    state: UiState,
    configured: Boolean,
    onSend: () -> Unit,
    onCheck: () -> Unit,
    onPoll: () -> Unit = onCheck,
) {
    val l = state.lang
    val r = state.remote
    val poll by androidx.compose.runtime.rememberUpdatedState(onPoll)
    LaunchedEffect(r?.sessionId, r?.reviewed) {
        if (r != null && !r.reviewed) while (true) { poll(); kotlinx.coroutines.delay(15_000) }
    }
    // Android 13+: ask once for notifications so "Reply received" can alert the shop.
    val context = androidx.compose.ui.platform.LocalContext.current
    var askedNotif by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val notifLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { askedNotif = true }
    LaunchedEffect(r?.sessionId, r?.reviewed) {
        if (r != null && !r.reviewed && !askedNotif && android.os.Build.VERSION.SDK_INT >= 33 &&
            !com.drishtisaathi.bridge.ReplyNotifier.canNotify(context)) {
            askedNotif = true
            notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    SectionCard(SBridge.cardTitle[l], container = if (r?.needsInPersonExam == true) UrgentBg else androidx.compose.ui.graphics.Color.White) {
        when {
            r == null -> {
                if (configured) BigButton(SBridge.send[l], onSend, enabled = !state.bridgeBusy)
                else Text(SBridge.notConfigured[l], style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            }
            !r.reviewed -> {
                Text(SBridge.sentCase.format(r.caseId)[l], style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(SBridge.caseNo.format(r.caseId)[l], style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = Teal)
                Text(SBridge.reviewsAt.format(r.backendUrl)[l], style = MaterialTheme.typography.bodyLarge)
                Text(SBridge.waiting[l], style = MaterialTheme.typography.titleMedium, color = RoutineAmber, fontWeight = FontWeight.SemiBold)
                Text(SBridge.sentAt.format(time(r.sentAt))[l] + " · " + SBridge.autoCheck[l],
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                BigButton(if (state.bridgeBusy) SBridge.checking[l] else SBridge.checkReply[l], onCheck, enabled = !state.bridgeBusy)
            }
            else -> {
                Text(SBridge.reviewed[l], style = MaterialTheme.typography.headlineSmall, color = OkGreen, fontWeight = FontWeight.Bold)
                Text(SBridge.caseNo.format(r.caseId)[l] + (r.reviewedAt?.let { " · " + time(it) } ?: ""),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (r.needsInPersonExam == true) {
                    Text("! " + SBridge.needsInPerson[l], style = MaterialTheme.typography.titleMedium, color = UrgentRed, fontWeight = FontWeight.Bold)
                } else {
                    Text(SBridge.noInPerson[l], style = MaterialTheme.typography.bodyMedium)
                }
                Text(SBridge.advice[l], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                // The optometrist's own words, shown as written (not translated).
                Text(r.advice.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                Text(SBridge.adviceNote[l], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                TextButton(onClick = onCheck, enabled = !state.bridgeBusy) { Text(SBridge.checkReply[l]) }
            }
        }
        state.bridgeError?.let { Text(it[l], color = UrgentRed, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** Staff: server address and token, with a connection test. */
@Composable
fun BridgeSettingsScreen(
    l: Lang,
    onLang: (Lang) -> Unit,
    current: BridgeConfig,
    onSave: (BridgeConfig) -> Unit,
    onTest: suspend (BridgeConfig) -> Pair<BridgeHealth?, Txt?>,
    onBack: () -> Unit,
) {
    var url by remember { mutableStateOf(current.url) }
    var token by remember { mutableStateOf(current.token) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val scope = rememberCoroutineScope()
    val cfg = BridgeConfig(url, token)
    AppScaffold(l, onLang, title = SBridge.settingsTitle[l], onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(url, { url = it.trim(); message = null }, label = { Text(SBridge.urlLabel[l]) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                isError = url.isNotEmpty() && !cfg.isValid)
            OutlinedTextField(token, { token = it.trim(); message = null }, label = { Text(SBridge.tokenLabel[l]) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            if (url.isNotEmpty() && !cfg.isValid) Text(SBridge.badUrl[l], color = UrgentRed)
            BigButton(SBridge.save[l], { onSave(cfg); message = SBridge.saved[l] to true }, enabled = url.isEmpty() || cfg.isValid, container = Teal)
            BigButton(SBridge.test[l], {
                scope.launch {
                    val (h, err) = onTest(cfg)
                    message = if (h != null) SBridge.connected[l] to true else (err?.get(l) ?: "?") to false
                }
            }, enabled = cfg.isValid)
            message?.let { (m, ok) -> Text(m, color = if (ok) OkGreen else UrgentRed, style = MaterialTheme.typography.titleMedium) }
            Text(SBridge.settingsHint[l], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}
