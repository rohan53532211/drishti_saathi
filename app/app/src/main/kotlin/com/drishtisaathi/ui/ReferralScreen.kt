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
import com.drishtisaathi.engine.Referral
import com.drishtisaathi.engine.ReferralLevel
import com.drishtisaathi.engine.ReferralReason
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S

/** Referral reasons split by urgency: urgent ones in the red box, routine ones in their own amber group. */
data class ReferralGroups(val urgent: List<ReferralReason>, val routine: List<ReferralReason>) {
    companion object {
        fun of(r: Referral) = ReferralGroups(r.reasons.filter { it.urgent }, r.reasons.filterNot { it.urgent })
    }
}

/** Shown before the summary whenever any referral trigger fired (Section 6). */
@Composable
fun ReferralScreen(state: UiState, rules: RulesConfig, onLang: (Lang) -> Unit, onContinue: () -> Unit, onBack: () -> Unit) {
    val l = state.lang
    val referral = state.result?.assessment?.referral ?: return
    val urgent = referral.level == ReferralLevel.URGENT

    AppScaffold(l, onLang, title = S.referral[l], onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text((if (urgent) S.urgentTitle else S.routineTitle)[l], style = MaterialTheme.typography.headlineMedium,
                color = if (urgent) UrgentRed else RoutineAmber)
            Text((if (urgent) S.urgentBody else S.routineBody)[l], style = MaterialTheme.typography.bodyLarge)
            ReferralReasonGroups(referral, rules, l)
            if (referral.adviceWithheld) Text(S.adviceWithheld[l], style = MaterialTheme.typography.titleMedium)
            BigButton(S.continueToSummary[l], onContinue, container = if (urgent) UrgentRed else RoutineAmber)
            Disclaimer()
        }
    }
}

/** Red "Urgent" box (urgent reasons only), then an amber "Routine" box; each only when it has reasons. */
@Composable
fun ReferralReasonGroups(referral: Referral, rules: RulesConfig, l: Lang) {
    val g = ReferralGroups.of(referral)
    if (g.urgent.isNotEmpty()) SectionCard(S.urgentGroup[l], container = UrgentBg) {
        g.urgent.forEach { Text("• " + S.referralReason(it, rules)[l], style = MaterialTheme.typography.bodyLarge, color = UrgentRed) }
    }
    if (g.routine.isNotEmpty()) SectionCard(S.routineGroup[l], container = RoutineBg) {
        g.routine.forEach { Text("• " + S.referralReason(it, rules)[l], style = MaterialTheme.typography.bodyLarge, color = RoutineAmber) }
    }
}
