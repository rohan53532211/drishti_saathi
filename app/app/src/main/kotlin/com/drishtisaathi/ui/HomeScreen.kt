package com.drishtisaathi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.drishtisaathi.UiState
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S
import com.drishtisaathi.i18n.SShop

/** Home: two big buttons for the counter, a small "Shop tools" link, and a reminder while phone setup is not done. */
@Composable
fun HomeScreen(
    state: UiState,
    onLang: (Lang) -> Unit,
    onStart: () -> Unit,
    onHistory: () -> Unit,
    onShopTools: () -> Unit,
    setupDone: Boolean = true,
    onSetup: () -> Unit = {},
) {
    val l = state.lang
    AppScaffold(l, onLang) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Text(S.appName[l], style = MaterialTheme.typography.headlineMedium, color = Teal)
            Text(S.tagline[l], style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            if (!setupDone) {
                SectionCard(SShop.phoneSetup[l], container = RoutineBg,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onSetup)) {
                    Text(SShop.setupReminder[l], color = RoutineAmber, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(8.dp))
            BigButton(S.startNew[l], onStart, container = Teal)
            BigButton(S.history[l], onHistory)
            TextButton(onClick = onShopTools) { Text(SShop.shopTools[l], style = MaterialTheme.typography.titleMedium) }
            Text(S.privacyNote[l], style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            Disclaimer()
        }
    }
}

/** Shop tools: phone setup, demo mode, optometrist bridge settings, diagnostics. */
@Composable
fun ShopToolsScreen(
    lang: Lang, onLang: (Lang) -> Unit, setupDone: Boolean,
    onSetup: () -> Unit, onDemo: () -> Unit, onBridge: () -> Unit, onDiagnostics: () -> Unit, onBack: () -> Unit,
) {
    val l = lang
    AppScaffold(l, onLang, title = SShop.shopTools[l], onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BigButton(if (setupDone) SShop.phoneSetupDone[l] else SShop.phoneSetup[l], onSetup)
            BigButton(com.drishtisaathi.i18n.SFlow.demoMode[l], onDemo)
            BigButton(com.drishtisaathi.i18n.SBridge.settingsTitle[l], onBridge)
            BigButton(SShop.diagnostics[l], onDiagnostics)
        }
    }
}

/** Diagnostics: the camera check and the full staff test protocol (precision mode). */
@Composable
fun DiagnosticsScreen(lang: Lang, onLang: (Lang) -> Unit, onCamera: () -> Unit, onPrecision: () -> Unit, onBack: () -> Unit) {
    val l = lang
    AppScaffold(l, onLang, title = SShop.diagnostics[l], onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BigButton(com.drishtisaathi.i18n.SFlow.cameraCheck[l], onCamera)
            BigButton(SShop.precisionMode[l], onPrecision)
            Text(SShop.precisionNote[l], style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

/** Language step after consent: two big buttons. */
@Composable
fun LanguageScreen(lang: Lang, onPick: (Lang) -> Unit, onBack: () -> Unit) {
    AppScaffold(lang, onPick, title = SShop.chooseLanguage[lang], onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        ) {
            Text("भाषा चुनें / Choose language", style = MaterialTheme.typography.headlineSmall)
            BigButton("हिंदी", { onPick(Lang.HI) }, selected = lang == Lang.HI)
            BigButton("English", { onPick(Lang.EN) }, selected = lang == Lang.EN)
        }
    }
}
