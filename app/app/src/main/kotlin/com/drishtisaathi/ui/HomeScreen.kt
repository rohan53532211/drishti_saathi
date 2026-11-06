package com.drishtisaathi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.drishtisaathi.UiState
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S

@Composable
fun HomeScreen(
    state: UiState,
    onLang: (Lang) -> Unit,
    onStaffMode: (Boolean) -> Unit,
    onStart: () -> Unit,
    onHistory: () -> Unit,
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
            Spacer(Modifier.height(16.dp))
            BigButton(S.startNew[l], onStart, container = Teal)
            if (state.staffMode) BigButton(S.history[l], onHistory)

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(S.staffMode[l], style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = state.staffMode, onCheckedChange = onStaffMode)
            }
            Text(S.phase1Note[l], style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            Text(S.privacyNote[l], style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            Disclaimer()
        }
    }
}
