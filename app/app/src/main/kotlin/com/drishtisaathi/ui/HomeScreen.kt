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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

/**
 * Home: a large hero card "Start new customer", three tiles (past sessions, shop tools, frames), the optometrist
 * bridge chip (Connected / Offline – queued N), and a reminder while phone setup is not done.
 */
@Composable
fun HomeScreen(
    state: UiState,
    onLang: (Lang) -> Unit,
    onStart: () -> Unit,
    onHistory: () -> Unit,
    onShopTools: () -> Unit,
    setupDone: Boolean = true,
    onSetup: () -> Unit = {},
    onFrames: () -> Unit = {},
    bridgeOnline: Boolean = false,
    queued: Int = 0,
    onBridgeChip: () -> Unit = {},
) {
    val l = state.lang
    val c = DS.colors
    val P = com.drishtisaathi.i18n.SProfile
    AppScaffold(l, onLang) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(DS.screen),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(S.appName[l], style = MaterialTheme.typography.displaySmall, color = c.ink)
            Text(S.tagline[l], style = MaterialTheme.typography.bodyMedium, color = c.inkSecondary)
            // optometrist bridge status
            val chipText = when {
                bridgeOnline && queued == 0 -> P.bridgeConnected[l]
                bridgeOnline -> P.bridgeConnected[l] + " · " + queued
                queued > 0 -> P.bridgeOffline.format(queued)[l]
                else -> P.bridgeOfflineNone[l]
            }
            androidx.compose.material3.Surface(
                onClick = onBridgeChip, shape = DS.pill, color = if (bridgeOnline) c.successSoft else c.warningSoft,
                modifier = Modifier.heightIn(min = DS.minTouch),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).background(if (bridgeOnline) c.success else c.warning, CircleShape))
                    Text(com.drishtisaathi.i18n.SBridge.cardTitle[l] + ": " + chipText, color = if (bridgeOnline) c.successInk else c.warningInk,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
            if (!setupDone) {
                SectionCard(SShop.phoneSetup[l], container = RoutineBg,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onSetup)) {
                    Text(SShop.setupReminder[l], color = RoutineAmber, fontWeight = FontWeight.SemiBold)
                }
            }
            // hero card
            androidx.compose.material3.Surface(
                onClick = onStart, shape = DS.card, color = c.primary,
                modifier = Modifier.fillMaxWidth().shadow(14.dp, DS.card, ambientColor = Color(0x14000000), spotColor = Color(0x14000000))
                    .semantics { role = Role.Button },
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(S.startNew[l], style = MaterialTheme.typography.headlineMedium, color = c.onPrimary)
                    Text(P.homeHeroLine[l], style = MaterialTheme.typography.bodyLarge, color = c.onPrimary.copy(alpha = 0.85f))
                    Spacer(Modifier.height(8.dp))
                    Text(P.homeHeroCta[l] + "  →", style = MaterialTheme.typography.labelLarge, color = c.onPrimary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeTile(S.history[l], c.work, onHistory, Modifier.weight(1f))
                HomeTile(SShop.shopTools[l], c.mobility, onShopTools, Modifier.weight(1f))
                HomeTile(P.homeFrames[l], c.leisure, onFrames, Modifier.weight(1f))
            }
            Text(S.privacyNote[l], style = MaterialTheme.typography.bodyMedium, color = c.inkSecondary)
            Disclaimer()
        }
    }
}

@Composable
private fun HomeTile(label: String, accent: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = DS.colors
    androidx.compose.material3.Surface(
        onClick = onClick, shape = DS.card, color = c.surface, border = BorderStroke(1.dp, c.hairline),
        modifier = modifier.heightIn(min = 112.dp).semantics { role = Role.Button },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(28.dp).background(accent.copy(alpha = 0.22f), CircleShape), contentAlignment = Alignment.Center) {
                Box(Modifier.size(12.dp).background(accent, CircleShape))
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = c.ink)
        }
    }
}

/** Shop tools: phone setup, demo mode, optometrist bridge settings, diagnostics. */
@Composable
fun ShopToolsScreen(
    lang: Lang, onLang: (Lang) -> Unit, setupDone: Boolean,
    onSetup: () -> Unit, onDemo: () -> Unit, onBridge: () -> Unit, onDiagnostics: () -> Unit, onBack: () -> Unit,
    onCatalogue: () -> Unit = {},
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
            BigButton(com.drishtisaathi.i18n.S3D.frameCatalogue[l], onCatalogue)
            BigButton(SShop.diagnostics[l], onDiagnostics)
        }
    }
}

/** Diagnostics: the camera check and the full staff test protocol (precision mode). */
@Composable
fun DiagnosticsScreen(lang: Lang, onLang: (Lang) -> Unit, onCamera: () -> Unit, onPrecision: () -> Unit, onAlign3D: () -> Unit, onBack: () -> Unit) {
    val l = lang
    AppScaffold(l, onLang, title = SShop.diagnostics[l], onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BigButton(com.drishtisaathi.i18n.SFlow.cameraCheck[l], onCamera)
            BigButton(SShop.precisionMode[l], onPrecision)
            BigButton(com.drishtisaathi.i18n.S3D.alignCheck[l], onAlign3D)
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
