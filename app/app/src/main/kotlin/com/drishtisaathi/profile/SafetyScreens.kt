package com.drishtisaathi.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.drishtisaathi.UiState
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.profile.Safety
import com.drishtisaathi.engine.profile.SafetyGate
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.SProfile
import com.drishtisaathi.i18n.Txt
import com.drishtisaathi.ui.DS

/** The safety gate for everything this session holds (answers, survey, power, screening). */
fun UiState.gate(rules: RulesConfig): SafetyGate =
    Safety.gate(answers, survey, rules.safety, quick, tests.takeIf { testsDone }, screening)

/** What the red banner says aloud: the headline and every reason. */
fun bannerSpeech(gate: SafetyGate): Txt {
    val en = gate.redFlags.joinToString(". ") { SProfile.redFlag(it).en }
    val hi = gate.redFlags.joinToString("। ") { SProfile.redFlag(it).hi }
    return Txt("${SProfile.seeDoctorToday.en}. $en.", "${SProfile.seeDoctorToday.hi}। $hi।")
}

/**
 * The red banner, shown first as soon as a red flag is found: "Please see an eye doctor today" and the reasons, in
 * the chosen language and spoken. The check then continues (short path for emergency signs); no lens is suggested.
 */
@Composable
fun RedFlagBannerScreen(lang: Lang, gate: SafetyGate, speak: (Txt) -> Unit, onContinue: () -> Unit) {
    val c = DS.colors
    LaunchedEffect(gate.redFlags, lang) { speak(bannerSpeech(gate)) }
    Column(
        Modifier.fillMaxSize().background(c.urgent).systemBarsPadding().verticalScroll(rememberScrollState()).padding(DS.screen),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("!", color = Color.White, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
        Text(SProfile.seeDoctorToday[lang], color = Color.White, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Assertive })
        Text(SProfile.seeDoctorWhy[lang], color = Color.White, style = MaterialTheme.typography.titleMedium)
        gate.redFlags.forEach { Text("•  " + SProfile.redFlag(it)[lang], color = Color.White, style = MaterialTheme.typography.titleLarge) }
        Spacer(Modifier.height(8.dp))
        Text(SProfile.bannerNote[lang], color = Color.White, style = MaterialTheme.typography.bodyLarge)
        if (gate.emergency) Text(SProfile.shortPathNote[lang], color = Color.White, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))
        PrimaryButton(SProfile.bannerContinue[lang], colour = Color.White, onClick = onContinue)
    }
}

/** The urgent banner at the very top of the results. */
@Composable
fun UrgentBanner(lang: Lang, gate: SafetyGate, modifier: Modifier = Modifier) {
    val c = DS.colors
    Column(
        modifier.fillMaxWidth().background(c.urgent, DS.card).padding(20.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(SProfile.seeDoctorToday[lang], color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        gate.redFlags.forEach { Text("•  " + SProfile.redFlag(it)[lang], color = Color.White, style = MaterialTheme.typography.bodyLarge) }
        Text(SProfile.noLensToday[lang], color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Shown instead of the lens cards for a red flag or child myopia. */
@Composable
fun ReferralCard(lang: Lang, gate: SafetyGate, modifier: Modifier = Modifier) {
    val c = DS.colors
    val child = gate.childMyopia && !gate.urgent
    Column(
        modifier.fillMaxWidth().background(if (child) c.warningSoft else c.urgentSoft, DS.card).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(SProfile.referralCard[lang], style = MaterialTheme.typography.labelMedium, color = c.inkSecondary)
        if (gate.urgent) {
            Text(SProfile.seeDoctorToday[lang], style = MaterialTheme.typography.titleLarge, color = c.urgent, fontWeight = FontWeight.SemiBold)
            Text(SProfile.noLensToday[lang], style = MaterialTheme.typography.bodyLarge, color = c.ink)
        }
        if (gate.childMyopia) {
            Text(SProfile.childMyopiaTitle[lang], style = MaterialTheme.typography.titleLarge, color = if (child) c.warningInk else c.urgent, fontWeight = FontWeight.SemiBold)
            Text(SProfile.childMyopiaBody[lang], style = MaterialTheme.typography.bodyLarge, color = c.ink)
        }
        Text(SProfile.reportTagLine.format(SProfile.tag(gate.tag)[lang])[lang], style = MaterialTheme.typography.labelMedium, color = c.inkSecondary)
    }
}
