package com.drishtisaathi.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.drishtisaathi.UiState
import com.drishtisaathi.engine.ReferralLevel
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S

/** Shown before the summary whenever any referral trigger fired (Section 6). */
@Composable
fun ReferralScreen(state: UiState, rules: com.drishtisaathi.engine.RulesConfig, onLang: (Lang) -> Unit, onContinue: () -> Unit, onBack: () -> Unit) {
    val l = state.lang
    val referral = state.result?.assessment?.referral ?: return
    val urgent = referral.level == ReferralLevel.URGENT
    val fg = if (urgent) UrgentRed else RoutineAmber
    val bg = if (urgent) UrgentBg else RoutineBg

    AppScaffold(l, onLang, title = S.referral[l], onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().background(bg).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text((if (urgent) S.urgentTitle else S.routineTitle)[l], style = MaterialTheme.typography.headlineMedium, color = fg)
            Text((if (urgent) S.urgentBody else S.routineBody)[l], style = MaterialTheme.typography.bodyLarge)
            Text(S.reasons[l], style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            referral.reasons.forEach { r ->
                Text("• " + S.referralReason(r, rules)[l], style = MaterialTheme.typography.bodyLarge, color = fg)
            }
            if (referral.adviceWithheld) Text(S.adviceWithheld[l], style = MaterialTheme.typography.titleMedium)
            BigButton(S.continueToSummary[l], onContinue, container = fg)
            Disclaimer()
        }
    }
}
