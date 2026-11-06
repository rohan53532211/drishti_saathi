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
import com.drishtisaathi.UiState
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S

/** Step 6 placeholder: proves the engine runs end to end. Replaced by the full M6 summary in step 7. */
@Composable
fun ResultScreen(state: UiState, onLang: (Lang) -> Unit, onDone: () -> Unit, onBack: () -> Unit) {
    val l = state.lang
    val r = state.result ?: return
    val a = r.assessment
    AppScaffold(l, onLang, title = S.shopSummary[l], onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(S.referralLevel(a.referral.level)[l], style = MaterialTheme.typography.titleLarge)
            a.advice?.let { adv ->
                Text("${S.lensType[l]}: ${S.lensType(adv.lensType.value)[l]}")
                Text("${S.material[l]}: ${S.material(adv.materialId.value)[l]}")
            } ?: Text(S.adviceWithheld[l])
            Text("${S.suggestedFrames[l]}: ${r.frames.joinToString { it.frame.name }}")
            BigButton(S.done[l], onDone, container = Teal)
            Disclaimer()
        }
    }
}
