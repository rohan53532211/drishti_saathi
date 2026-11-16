package com.drishtisaathi.guide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.SGuide
import com.drishtisaathi.i18n.Txt
import com.drishtisaathi.ui.AppScaffold
import com.drishtisaathi.ui.DS
import com.drishtisaathi.ui.designsystem.DsCard
import com.drishtisaathi.ui.designsystem.Illustration
import com.drishtisaathi.ui.designsystem.PrimaryButton
import com.drishtisaathi.ui.designsystem.Spot
import com.drishtisaathi.ui.designsystem.SuccessCheck

/** Before the test (docs/DESIGN.md §5.3): three illustrated items and one "I'm ready". */
@Composable
fun ChecklistScreen(lang: Lang, onLang: (Lang) -> Unit, onReady: () -> Unit, onBack: () -> Unit) {
    val c = DS.colors
    AppScaffold(lang, onLang, title = SGuide.checklistTitle[lang], onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(DS.screen),
            verticalArrangement = Arrangement.spacedBy(DS.s3),
        ) {
            Text(SGuide.checklistTitle[lang], style = MaterialTheme.typography.headlineSmall, color = c.ink, modifier = Modifier.semantics { heading() })
            listOf(
                Triple(Spot.LIGHT, SGuide.checkLight, SGuide.checkLightLine),
                Triple(Spot.BATTERY, SGuide.checkBattery, SGuide.checkBatteryLine),
                Triple(Spot.CARD_ON_SCREEN, SGuide.checkCard, SGuide.checkCardLine),
            ).forEach { (spot, title, line) -> ChecklistItem(lang, spot, title, line) }
            Spacer(Modifier.size(DS.s2))
            PrimaryButton(SGuide.ready[lang], onReady)
        }
    }
}

@Composable
private fun ChecklistItem(lang: Lang, spot: Spot, title: Txt, line: Txt) {
    val c = DS.colors
    DsCard(Modifier.semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DS.s4)) {
            Illustration(spot, size = 72.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title[lang], style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text(line[lang], style = MaterialTheme.typography.bodyMedium, color = c.inkSecondary)
            }
        }
    }
}

/**
 * The screening template's small success state: a check that draws in, the test's name and "Done", one "Next test".
 * (Illustration → instruction → test → this.)
 */
@Composable
fun TestDoneState(lang: Lang, testName: Txt, onNext: () -> Unit, modifier: Modifier = Modifier, next: Txt = SGuide.nextTest) {
    val c = DS.colors
    Column(modifier.fillMaxWidth().padding(vertical = DS.s5), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DS.s3)) {
        SuccessCheck(size = 88.dp)
        Text(testName[lang], style = MaterialTheme.typography.titleLarge, color = c.ink, textAlign = TextAlign.Center)
        Text(SGuide.testDone[lang], style = MaterialTheme.typography.bodyLarge, color = c.inkSecondary)
        Spacer(Modifier.size(DS.s2))
        PrimaryButton(next[lang], onNext)
    }
}
