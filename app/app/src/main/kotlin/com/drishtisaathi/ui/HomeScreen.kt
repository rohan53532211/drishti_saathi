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

/** Shop tools: phone setup, demo mode, optometrist bridge settings, diagnostics. */
@Composable
fun ShopToolsScreen(
    lang: Lang, onLang: (Lang) -> Unit, setupDone: Boolean,
    onSetup: () -> Unit, onDemo: () -> Unit, onBridge: () -> Unit, onDiagnostics: () -> Unit, onBack: () -> Unit,
    onCatalogue: () -> Unit = {},
    onSwitchRole: (() -> Unit)? = null,
) {
    val l = lang
    AppScaffold(l, onLang, title = SShop.shopTools[l], onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(DS.screen),
            verticalArrangement = Arrangement.spacedBy(DS.s4),
        ) {
            com.drishtisaathi.home.ToolsList(l, setupDone, onCatalogue, onDemo, onSetup, onBridge, onDiagnostics, onSwitchRole)
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
