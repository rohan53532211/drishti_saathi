package com.drishtisaathi.calibration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.drishtisaathi.camera.CameraPermissionGate
import com.drishtisaathi.camera.CameraPreview
import com.drishtisaathi.camera.rememberFaceTracker
import com.drishtisaathi.engine.config.CameraConfig
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.SCamera
import com.drishtisaathi.ui.BigButton
import com.drishtisaathi.ui.UrgentRed
import kotlinx.coroutines.launch

/**
 * M0 camera scale: one photo with a bank card on the forehead → this customer's iris size in mm, saved to
 * [repo] (in memory for this customer only). [onDone] gets the saved outcome; [onSkip] keeps the 11.7 mm prior
 * (also used when the camera permission is refused).
 */
@Composable
fun ForeheadCardCalibrationScreen(
    lang: Lang,
    repo: CalibrationRepository,
    config: CameraConfig,
    onDone: (ForeheadOutcome.Ok) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CameraPermissionGate(lang, onSkip = onSkip) {
        val context = LocalContext.current
        val tracker = rememberFaceTracker(config, enablePhoto = true)
        val calibrator = remember(config) { ForeheadCardCalibration(context, config) }
        val scope = rememberCoroutineScope()
        val error by tracker.error.collectAsState()
        var busy by remember { mutableStateOf(false) }
        var outcome by remember { mutableStateOf<ForeheadOutcome?>(null) }

        Column(modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(SCamera.foreheadHow[lang], style = MaterialTheme.typography.bodyMedium)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CameraPreview(tracker, Modifier.fillMaxSize())
                if (busy) CircularProgressIndicator()
            }
            error?.let { Text(SCamera.cameraError[lang] + " ($it)", color = UrgentRed) }
            when (val o = outcome) {
                is ForeheadOutcome.Ok -> {
                    Text(SCamera.foreheadResult.format(o.irisDiameterMm)[lang], style = MaterialTheme.typography.titleLarge)
                    BigButton(SCamera.useResult[lang], selected = true, onClick = {
                        ForeheadCardCalibration.save(repo, o)
                        onDone(o)
                    })
                }
                ForeheadOutcome.CardNotFound -> Text(SCamera.cardNotFound[lang], color = UrgentRed)
                ForeheadOutcome.FaceNotFound -> Text(SCamera.faceNotFound[lang], color = UrgentRed)
                is ForeheadOutcome.Implausible -> Text(SCamera.irisImplausible[lang], color = UrgentRed)
                null -> {}
            }
            BigButton(
                text = if (busy) SCamera.measuring[lang] else if (outcome == null) SCamera.takePhoto[lang] else SCamera.tryAgain[lang],
                enabled = !busy,
                selected = outcome !is ForeheadOutcome.Ok,
                onClick = {
                    busy = true
                    scope.launch {
                        outcome = try {
                            calibrator.measure(tracker.takePhoto()) // photo recycled inside measure()
                        } catch (e: Exception) {
                            ForeheadOutcome.CardNotFound
                        }
                        busy = false
                    }
                },
            )
            BigButton(SCamera.skipCard[lang], onClick = onSkip, modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}
