package com.drishtisaathi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.drishtisaathi.calibration.CalibrationRepository
import com.drishtisaathi.calibration.ScreenCardCalibrationScreen
import com.drishtisaathi.camera.CameraDebugScreen
import com.drishtisaathi.engine.config.CameraConfig
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.SShop

/**
 * One-time phone setup: 1. bank-card screen size, 2. a quick camera test. "Skip for now" never blocks a demo;
 * home shows a reminder until it is done.
 */
@Composable
fun PhoneSetupScreen(
    lang: Lang,
    onLang: (Lang) -> Unit,
    calibration: CalibrationRepository,
    camera: CameraConfig,
    onFinish: () -> Unit,
    onSkip: () -> Unit,
) {
    val l = lang
    var step by rememberSaveable { mutableIntStateOf(1) }
    AppScaffold(l, onLang, title = SShop.phoneSetup[l], onBack = onSkip) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(SShop.setupReason[l], style = MaterialTheme.typography.bodyLarge, color = Teal, fontWeight = FontWeight.SemiBold)
                Text((if (step == 1) SShop.setupStep1 else SShop.setupStep2)[l], style = MaterialTheme.typography.titleMedium)
                if (step == 2) Text(SShop.setupCameraHint[l], style = MaterialTheme.typography.bodyMedium)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (step == 1) {
                    ScreenCardCalibrationScreen(l, calibration, camera, onDone = { step = 2 }, modifier = Modifier.fillMaxSize())
                } else {
                    CameraDebugScreen(l, camera, calibration, onSkip = onFinish, modifier = Modifier.fillMaxSize())
                }
            }
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step == 2) BigButton(SShop.setupFinish[l], onFinish, container = Teal)
                TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(SShop.skipForNow[l]) }
            }
        }
    }
}
