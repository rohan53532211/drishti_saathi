package com.drishtisaathi.tryon

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drishtisaathi.engine.Frame
import com.drishtisaathi.engine.RankedFrame
import com.drishtisaathi.engine.config.TryOnConfig
import com.drishtisaathi.engine.face.FaceFrame
import com.drishtisaathi.engine.tryon.TryOnPlacement
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.STryOn
import com.drishtisaathi.ui.AppScaffold
import com.drishtisaathi.ui.Teal
import com.drishtisaathi.ui.TealLight
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Addition 8: live try-on. The camera preview (Track B) fills the top; the chosen frame is drawn over the face;
 * a horizontal picker below switches frames. Frames without a try-on picture are left out.
 *
 * @param frames ranked frames, best first (the picker keeps this order).
 * @param faceFrames latest tracked face (null when no face), in the preview's image space.
 * @param cameraPreview the preview composable; it must fill the box it is given.
 * @param mapping image → preview-view mapping matching [cameraPreview]'s crop/mirroring (Track B's mapper).
 * @param endpieceMmEachSide rules.json frameScoring.endpieceMmEachSide, to get the real front width.
 * @param faceWidthMm / [pdMm] measured values when known (better scale); otherwise rules defaults are used.
 */
@Composable
fun TryOnScreen(
    lang: Lang,
    frames: List<RankedFrame>,
    faceFrames: StateFlow<FaceFrame?>,
    cameraPreview: @Composable () -> Unit,
    config: TryOnConfig,
    endpieceMmEachSide: Double,
    onLang: (Lang) -> Unit,
    onBack: () -> Unit,
    mapping: TryOnMapping = FillCenterMapping,
    faceWidthMm: Double? = null,
    pdMm: Double? = null,
    initialFrameId: String? = null,
) {
    val l = lang
    val tryable = frames.map { it.frame }.filter { it.overlay != null }
    var selectedId by rememberSaveable { mutableStateOf(initialFrameId ?: tryable.firstOrNull()?.id) }
    val selected = tryable.firstOrNull { it.id == selectedId } ?: tryable.firstOrNull()
    val face by faceFrames.collectAsStateWithLifecycle()

    AppScaffold(l, onLang, title = STryOn.title[l], onBack = onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                cameraPreview()
                val ov = selected?.overlay
                if (selected != null && ov != null) {
                    val bmp by rememberFrameBitmap(ov.png)
                    TryOnOverlay(
                        frame = face, overlay = ov, bitmap = bmp, mapping = mapping,
                        frontWidthMm = TryOnPlacement.frontWidthMm(selected, endpieceMmEachSide),
                        faceWidthMm = faceWidthMm, pdMm = pdMm, config = config,
                    )
                    if (bmp == null) Hint(STryOn.loading[l], Modifier.align(Alignment.Center))
                }
                Hint(
                    if (face == null) STryOn.noFace[l] else STryOn.lookAtCamera[l],
                    Modifier.align(Alignment.TopCenter).padding(8.dp),
                )
                Hint(STryOn.demoNote[l], Modifier.align(Alignment.BottomCenter).padding(8.dp), small = true)
            }
            if (tryable.isEmpty()) {
                Text(STryOn.noFrames[l], Modifier.padding(16.dp))
            } else {
                selected?.let { f ->
                    Text(
                        "${f.name} · ${STryOn.sizeLine.format(f.sizeMark)[l]} · ₹" + String.format(Locale.US, "%,d", f.priceInr),
                        Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.titleMedium, color = Teal, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(STryOn.pickFrame[l], Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                FramePicker(tryable, selected?.id, Modifier.padding(vertical = 8.dp)) { selectedId = it.id }
            }
        }
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier, small: Boolean = false) {
    Text(
        text, modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = Color.White, textAlign = TextAlign.Center,
        style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
    )
}

/** Horizontal strip of frame thumbnails (1/4-size decodes, cached). */
@Composable
fun FramePicker(frames: List<Frame>, selectedId: String?, modifier: Modifier = Modifier, onPick: (Frame) -> Unit) {
    LazyRow(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(frames, key = { it.id }) { f ->
            val sel = f.id == selectedId
            Card(
                Modifier.width(120.dp).clickable { onPick(f) }
                    .border(if (sel) BorderStroke(3.dp, Teal) else BorderStroke(1.dp, Color.LightGray), RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = if (sel) TealLight else Color.White),
            ) {
                Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val thumb by rememberFrameBitmap(f.overlay?.png, sampleSize = 4)
                    Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
                        thumb?.let { Image(it, contentDescription = f.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                    }
                    Text(f.name, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center)
                }
            }
        }
    }
}
