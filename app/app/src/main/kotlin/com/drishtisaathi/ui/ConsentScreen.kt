package com.drishtisaathi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S

/**
 * Addition 1: consent before anything is collected. Phase 2 shows it again before the camera,
 * adding that face frames are processed in memory only.
 */
@Composable
fun ConsentScreen(lang: Lang, onLang: (Lang) -> Unit, onContinue: () -> Unit, onCancel: () -> Unit) {
    val l = lang
    AppScaffold(l, onLang, title = S.consentTitle[l], onBack = onCancel) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(S.consentTitle[l], style = MaterialTheme.typography.headlineMedium, color = Teal)
            listOf(
                S.consentWhatH to S.consentWhat,
                S.consentWhyH to S.consentWhy,
                S.consentWhereH to S.consentWhere,
                S.consentDeleteH to S.consentDelete,
            ).forEach { (h, body) ->
                SectionCard(h[l]) { Text(body[l], style = MaterialTheme.typography.bodyLarge) }
            }
            BigButton(S.consentContinue[l], onContinue, container = Teal)
            BigButton(S.consentCancel[l], onCancel)
            Disclaimer()
        }
    }
}
