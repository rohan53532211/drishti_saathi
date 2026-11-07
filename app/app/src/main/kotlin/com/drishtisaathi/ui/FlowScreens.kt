package com.drishtisaathi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
