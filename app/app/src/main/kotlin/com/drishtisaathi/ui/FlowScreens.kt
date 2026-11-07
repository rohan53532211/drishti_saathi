package com.drishtisaathi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.drishtisaathi.demo.DemoCustomer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.drishtisaathi.engine.face.FaceCapture
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.SFlow

/** A flow step (capture, tests) inside the app scaffold, titled with the step. */
@Composable
fun StepFrame(lang: Lang, onLang: (Lang) -> Unit, title: String, onBack: (() -> Unit)?, content: @Composable () -> Unit) {
    AppScaffold(lang, onLang, title = title, onBack = onBack) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) { content() }
    }
}

/** After face capture: what happened, with retry when the measurement was discarded or flagged. */
@Composable
fun CaptureOutcomeScreen(
    lang: Lang,
    onLang: (Lang) -> Unit,
    status: FaceCapture.Status,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
) {
    val l = lang
    AppScaffold(l, onLang, title = SFlow.stepCapture[l]) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val color = when (status) {
                FaceCapture.Status.OK -> OkGreen
                FaceCapture.Status.LOW_PLAUSIBILITY -> RoutineAmber
                else -> UrgentRed
            }
            Text(SFlow.captureStatus(status)[l], style = MaterialTheme.typography.titleMedium, color = color)
            if (status != FaceCapture.Status.OK) BigButton(SFlow.tryAgain[l], onRetry)
            BigButton(SFlow.continueNext[l], onContinue, container = Teal)
        }
    }
}

/** Staff demo mode: pick a sample customer; the flow then runs with their pre-filled data. */
@Composable
fun DemoScreen(lang: Lang, onLang: (Lang) -> Unit, customers: List<DemoCustomer>, onPick: (DemoCustomer) -> Unit, onBack: () -> Unit) {
    val l = lang
    AppScaffold(l, onLang, title = SFlow.demoMode[l], onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(SFlow.demoIntro[l], style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            customers.forEach { c ->
                Card(Modifier.fillMaxWidth().clickable { onPick(c) }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("DEMO", color = UrgentRed, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                        Text(c.title[l], style = MaterialTheme.typography.titleMedium)
                        Text(c.summary[l], style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** Shown over every screen while a demo session is open, so recordings can never pass as real customers. */
@Composable
fun DemoBadge(lang: Lang, modifier: Modifier = Modifier) {
    Text(
        SFlow.demoBadge[lang],
        color = Color.White,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .statusBarsPadding()
            .padding(top = 4.dp)
            .background(UrgentRed, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}
