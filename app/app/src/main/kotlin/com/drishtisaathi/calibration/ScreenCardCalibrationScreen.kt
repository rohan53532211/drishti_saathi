package com.drishtisaathi.calibration

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.drishtisaathi.engine.Source
import com.drishtisaathi.engine.config.CameraConfig
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.SCamera
import com.drishtisaathi.ui.BigButton
import com.drishtisaathi.ui.Teal
import com.drishtisaathi.ui.UrgentRed

/**
 * M0 screen scale: the customer (or staff) holds a bank card on the screen and drags the box's bottom edge
 * until it matches. The box is portrait (card's 85.60 mm side vertical) because phones are narrower than a
 * card is long. screenMmPerPx = 85.60 / boxLongSidePx (physical pixels). Fallback: DisplayMetrics.xdpi,
 * saved with a lower-confidence source.
 *
 * Saves into [repo] and calls [onDone]. The lead wires it into navigation (e.g. once per phone, from settings).
 */
@Composable
fun ScreenCardCalibrationScreen(
    lang: Lang,
    repo: CalibrationRepository,
    config: CameraConfig,
    onDone: (Calibration) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val xdpi = context.resources.displayMetrics.xdpi.toDouble().takeIf { it > 0 } ?: context.resources.displayMetrics.densityDpi.toDouble()
    val dpiMmPerPx = ScreenScale.mmPerPxFromDpi(xdpi)
    val startScale = repo.calibration.value.screenMmPerPx ?: dpiMmPerPx
    var longSidePx by remember { mutableFloatStateOf(ScreenScale.cardLongSidePx(startScale, config).toFloat()) }
    var areaHeight by remember { mutableFloatStateOf(0f) }
    var areaWidth by remember { mutableFloatStateOf(0f) }
    var badValue by remember { mutableStateOf(false) }
    val margin = with(androidx.compose.ui.platform.LocalDensity.current) { 12.dp.toPx() }

    fun clamp(v: Float): Float {
        val maxH = if (areaHeight > 0) areaHeight - 2 * margin else Float.MAX_VALUE
        val maxByWidth = if (areaWidth > 0) (areaWidth - 2 * margin) * config.cardAspect.toFloat() else Float.MAX_VALUE
        return v.coerceIn(100f, minOf(maxH, maxByWidth).coerceAtLeast(100f))
    }

    Column(modifier.fillMaxSize().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(SCamera.screenCalHow[lang], style = MaterialTheme.typography.bodyMedium)
        if (badValue) Text(SCamera.screenCalBadValue[lang], color = UrgentRed, style = MaterialTheme.typography.bodyMedium)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { areaHeight = it.height.toFloat(); areaWidth = it.width.toFloat(); longSidePx = clamp(longSidePx) }
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        longSidePx = clamp(longSidePx + drag.y)
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val h = longSidePx
                val w = h / config.cardAspect.toFloat()
                drawRoundRect(
                    color = com.drishtisaathi.ui.LightColors.primary.copy(alpha = 0.15f), topLeft = Offset(margin, margin), size = Size(w, h),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
                drawRoundRect(
                    color = com.drishtisaathi.ui.LightColors.primary, topLeft = Offset(margin, margin), size = Size(w, h),
                    cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(width = 2.dp.toPx()),
                )
                // Thick handle on the edge being matched.
                drawLine(com.drishtisaathi.ui.LightColors.urgent, Offset(margin, margin + h), Offset(margin + w, margin + h), strokeWidth = 4.dp.toPx())
                drawCircle(Color.White, radius = 10.dp.toPx(), center = Offset(margin + w / 2, margin + h))
                drawCircle(com.drishtisaathi.ui.LightColors.urgent, radius = 10.dp.toPx(), center = Offset(margin + w / 2, margin + h), style = Stroke(3.dp.toPx()))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton(SCamera.smaller[lang], onClick = { longSidePx = clamp(longSidePx - 2f) }, modifier = Modifier.weight(1f))
            BigButton(SCamera.bigger[lang], onClick = { longSidePx = clamp(longSidePx + 2f) }, modifier = Modifier.weight(1f))
        }
        BigButton(SCamera.screenCalSave[lang], selected = true, onClick = {
            val mm = ScreenScale.mmPerPxFromCardLongSide(longSidePx.toDouble(), config)
            if (!ScreenScale.plausible(mm, config)) {
                badValue = true
            } else {
                repo.update { it.copy(screenMmPerPx = mm, screenSource = Source.MEASURED) }
                onDone(repo.calibration.value)
            }
        })
        BigButton(SCamera.screenCalNoCard[lang], onClick = {
            // Maker-reported density: often rounded, so marked as an estimate rather than measured.
            repo.update { it.copy(screenMmPerPx = dpiMmPerPx, screenSource = Source.POPULATION_AVERAGE) }
            onDone(repo.calibration.value)
        }, modifier = Modifier.padding(bottom = 8.dp))
    }
}
