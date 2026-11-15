package com.drishtisaathi.profile

import androidx.compose.foundation.background
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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
    val en = SProfile.redFlagLines(gate).joinToString(". ") { it.en }
    val hi = SProfile.redFlagLines(gate).joinToString("। ") { it.hi }
    return Txt("${SProfile.seeDoctorToday.en}. $en.", "${SProfile.seeDoctorToday.hi}। $hi।")
}

/**
 * The red banner, shown first as soon as a red flag is found: "Please see an eye doctor today" and the reasons, in
 * the chosen language and spoken. The check then continues (short path for emergency signs); no lens is suggested.
 */
@Composable
fun RedFlagBannerScreen(lang: Lang, gate: SafetyGate, speak: (Txt) -> Unit, onContinue: () -> Unit) {
    val c = DS.colors
    val haptics = com.drishtisaathi.ui.designsystem.rememberHaptics()
    LaunchedEffect(gate.redFlags, lang) { haptics.warning(); speak(bannerSpeech(gate)) }
    Column(
        Modifier.fillMaxSize().background(c.background).systemBarsPadding().verticalScroll(rememberScrollState()).padding(DS.screen),
        verticalArrangement = Arrangement.spacedBy(DS.s4),
    ) {
        UrgentBlock(lang, gate, headline = true)
        com.drishtisaathi.ui.designsystem.SecondaryButton(com.drishtisaathi.i18n.SGuide.readAloud[lang], { speak(bannerSpeech(gate)) },
            icon = androidx.compose.material.icons.Icons.AutoMirrored.Rounded.VolumeUp)
        WhatToDo(lang, gate)
        Spacer(Modifier.height(DS.s2))
        com.drishtisaathi.ui.designsystem.PrimaryButton(SProfile.bannerContinue[lang], onContinue)
    }
}

/**
 * The urgent block: full width, solid urgent colour, a warning icon in a circle, "Please see an eye doctor today" and
 * the reasons. Text colour is picked for contrast on the urgent colour (white in light, dark ink in dark).
 */
@Composable
fun UrgentBlock(lang: Lang, gate: SafetyGate, modifier: Modifier = Modifier, headline: Boolean = false) {
    val c = DS.colors
    val fg = DS.on(c.urgent)
    Column(
        modifier.fillMaxWidth().background(c.urgent, DS.card).padding(20.dp)
            .semantics(mergeDescendants = true) { liveRegion = if (headline) LiveRegionMode.Assertive else LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            androidx.compose.foundation.layout.Box(Modifier.padding(top = 2.dp).background(fg, androidx.compose.foundation.shape.CircleShape).padding(10.dp)) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Warning, contentDescription = null, tint = c.urgent,
                    modifier = Modifier.size(28.dp))
            }
            Text(SProfile.seeDoctorToday[lang], color = fg, fontWeight = FontWeight.SemiBold,
                style = if (headline) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).semantics { heading() })
        }
        if (gate.emergency) Text(SProfile.shortPathHeader[lang], color = fg, style = MaterialTheme.typography.labelLarge)
        val reasons = SProfile.redFlagLines(gate)
        if (reasons.isNotEmpty()) Text(SProfile.seeDoctorWhy[lang], color = fg, style = MaterialTheme.typography.bodyMedium)
        reasons.forEach { r ->
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.foundation.layout.Box(Modifier.padding(top = 10.dp).size(7.dp).background(fg, androidx.compose.foundation.shape.CircleShape))
                Text(r[lang], color = fg, style = if (headline) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** "What to do now": numbered, plain steps (the same wording the banner always used). */
@Composable
private fun WhatToDo(lang: Lang, gate: SafetyGate) {
    val c = DS.colors
    val steps = listOfNotNull(SProfile.noLensToday, SProfile.bannerNote, SProfile.shortPathNote.takeIf { gate.emergency })
    com.drishtisaathi.ui.designsystem.DsCard {
        Text(com.drishtisaathi.i18n.SGuide.whatToDo[lang], style = MaterialTheme.typography.titleLarge, color = c.ink, modifier = Modifier.semantics { heading() })
        steps.forEachIndexed { i, s ->
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.foundation.layout.Box(
                    Modifier.background(c.urgentSoft, androidx.compose.foundation.shape.CircleShape).padding(horizontal = 11.dp, vertical = 4.dp),
                ) { Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = c.urgentInk, fontWeight = FontWeight.Bold) }
                Text(s[lang], Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = c.ink)
            }
        }
    }
}

/** The urgent banner at the very top of the results. */
@Composable
fun UrgentBanner(lang: Lang, gate: SafetyGate, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        UrgentBlock(lang, gate)
        Text(SProfile.noLensToday[lang], color = DS.colors.urgentInk, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
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
            Text(SProfile.seeDoctorToday[lang], style = MaterialTheme.typography.titleLarge, color = c.urgentInk, fontWeight = FontWeight.SemiBold)
            Text(SProfile.noLensToday[lang], style = MaterialTheme.typography.bodyLarge, color = c.ink)
        }
        if (gate.childMyopia) {
            Text(SProfile.childMyopiaTitle[lang], style = MaterialTheme.typography.titleLarge, color = if (child) c.warningInk else c.urgentInk, fontWeight = FontWeight.SemiBold)
            Text(SProfile.childMyopiaBody[lang], style = MaterialTheme.typography.bodyLarge, color = c.ink)
        }
        Text(SProfile.reportTagLine.format(SProfile.tag(gate.tag)[lang])[lang], style = MaterialTheme.typography.labelMedium, color = c.inkSecondary)
    }
}
