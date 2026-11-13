package com.drishtisaathi.tryon3d

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.drishtisaathi.camera.PreviewTransform
import com.drishtisaathi.engine.face.FaceFrame
import com.drishtisaathi.engine.tryon3d.Fit3D
import com.drishtisaathi.engine.tryon3d.FitWord
import com.drishtisaathi.engine.tryon3d.Frame3D
import com.drishtisaathi.engine.tryon3d.GlbWriter
import com.drishtisaathi.engine.tryon3d.Mat4
import com.drishtisaathi.engine.tryon3d.PoseSmoother
import com.drishtisaathi.engine.tryon3d.Render3DFallback
import com.drishtisaathi.engine.tryon3d.Scale3DSource
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S3D
import com.drishtisaathi.i18n.STryOn
import com.drishtisaathi.ui.AppScaffold
import com.drishtisaathi.ui.Teal
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

fun fitWord(w: FitWord) = when (w) {
    FitWord.GOOD -> S3D.fitGood
    FitWord.SLIGHTLY_NARROW -> S3D.fitSlightlyNarrow
    FitWord.SLIGHTLY_WIDE -> S3D.fitSlightlyWide
    FitWord.TOO_NARROW -> S3D.fitTooNarrow
    FitWord.TOO_WIDE -> S3D.fitTooWide
}

/**
 * Live 3D try-on over the front-camera preview: the GLB frame posed from the face landmarks and the facial
 * transformation matrix, scaled with the PD, temples swung to the head width, an invisible head occluder, one-euro
 * smoothing. Falls back to [fallback2D] (the 2D try-on) when 3D cannot start, a GLB fails or the phone is slow.
 */
@Composable
fun LiveTryOn3DScreen(
    lang: Lang,
    onLang: (Lang) -> Unit,
    frames: List<Frame3D>,
    initialId: String?,
    faceFrames: StateFlow<FaceFrame?>,
    cameraPreview: @Composable () -> Unit,
    glb: suspend (Frame3D) -> ByteArray,
    thumb: suspend (Frame3D) -> Bitmap?,
    pdMm: Double?,
    faceWidthMm: Double?,
    fallback2D: @Composable () -> Unit,
    onBack: () -> Unit,
) {
    val l = lang
    val render = rememberRender3D(transparent = true)
    val fallback = remember { Render3DFallback().also { if (render == null) it.rendererFailed() } }
    var twoD by remember { mutableStateOf(render == null) }
    var selectedId by remember { mutableStateOf(initialId ?: frames.firstOrNull()?.id) }
    val selected = frames.firstOrNull { it.id == selectedId } ?: frames.firstOrNull()
    var loadedId by remember { mutableStateOf<String?>(null) }
    var headMm by remember { mutableDoubleStateOf(0.0) }
    var source by remember { mutableStateOf<Scale3DSource?>(null) }
    var hasFace by remember { mutableStateOf(false) }

    if (twoD) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { fallback2D() }
            Text(S3D.switchedTo2d[l], Modifier.fillMaxWidth().background(Color(0xFFFFF4D6)).padding(8.dp), textAlign = TextAlign.Center)
        }
        return
    }
    val r = render!!

    LaunchedEffect(r) {
        runCatching {
            r.load("head", GlbWriter.unitSphereGlb(), castShadows = false, receiveShadows = false)
            r.makeOccluder("head")
            r.setSunDirection(-0.2f, -0.6f, -0.75f)
        }
    }
    LaunchedEffect(selected?.id, r) {
        val f = selected ?: return@LaunchedEffect
        val ok = runCatching { r.load("frame", glb(f), castShadows = false) }.isSuccess
        if (ok) loadedId = f.id else { fallback.glbFailed(); twoD = true }
    }
    val smoother = remember { PoseSmoother() }
    DisposableEffect(r, selected?.id) {
        val hidden = Mat4.scale(0.0)
        r.onFrame = { t ->
            r.orthoPixels()
            val face = faceFrames.value
            val f = selected
            val measured = if (face != null && face.imageWidthPx > 0) {
                val tr = PreviewTransform(face.imageWidthPx, face.imageHeightPx, r.width.toFloat(), r.height.toFloat(), mirrored = false)
                Fit3D.measure(face, { x, y -> tr.map(x.toFloat(), y.toFloat()).let { it.x.toDouble() to it.y.toDouble() } }, pdMm, faceWidthMm)
            } else null
            val p = smoother.update(measured?.first, t)
            if (p == null || f == null || loadedId != f.id) {
                r.setRoot("frame", hidden); r.setRoot("head", hidden)
                if (hasFace) hasFace = false
            } else {
                val pose = Fit3D.pose(p, f, measured!!.second)
                r.setRoot("frame", pose.model)
                r.setNode("frame", "temple_R", pose.templeR)
                r.setNode("frame", "temple_L", pose.templeL)
                r.setRoot("head", pose.occluder)
                if (!hasFace) hasFace = true
                if (abs(pose.headWidthMm - headMm) > 1.0) headMm = pose.headWidthMm
                if (source != pose.scaleSource) source = pose.scaleSource
            }
            if (fallback.onFrame(t) == Render3DFallback.Mode.TWO_D) twoD = true
        }
        onDispose { r.onFrame = null }
    }

    AppScaffold(l, onLang, title = S3D.liveTitle[l], onBack = onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                cameraPreview()
                Render3DView(r, Modifier.fillMaxSize(), mirrored = true)
                Hint(if (!hasFace) STryOn.noFace[l] else STryOn.lookAtCamera[l], Modifier.align(Alignment.TopCenter).padding(8.dp))
                source?.let {
                    Hint(when (it) {
                        Scale3DSource.CARD_PD -> S3D.scaleCard[l]
                        Scale3DSource.FACE_WIDTH -> S3D.scaleFace[l]
                        Scale3DSource.DEFAULT_PD -> S3D.scaleDefault[l]
                    }, Modifier.align(Alignment.BottomCenter).padding(8.dp), small = true)
                }
            }
            selected?.let { f ->
                val fit = if (headMm > 0) " · " + S3D.fitText.format(f.frontWidthMm.roundToInt(), headMm.roundToInt())[l] +
                    " (" + fitWord(Fit3D.fitVerdict(f.frontWidthMm, headMm))[l] + ")" else ""
                Text(f.name + " · " + rupees(f.priceInr) + fit, Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.titleSmall, color = Teal, fontWeight = FontWeight.SemiBold)
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(frames, key = { it.id }) { f ->
                    val sel = f.id == selected?.id
                    FrameThumb(f, thumb, Modifier.width(110.dp).height(48.dp).clip(RoundedCornerShape(10.dp))
                        .border(BorderStroke(if (sel) 3.dp else 1.dp, if (sel) Teal else Color.LightGray), RoundedCornerShape(10.dp))
                        .clickable { selectedId = f.id })
                }
            }
        }
    }
}

@Composable
fun Hint(text: String, modifier: Modifier, small: Boolean = false) {
    Text(
        text, modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 4.dp),
        color = Color.White, textAlign = TextAlign.Center,
        style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
    )
}
