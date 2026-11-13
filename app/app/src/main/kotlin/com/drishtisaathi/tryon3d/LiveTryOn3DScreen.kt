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
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import com.drishtisaathi.engine.config.TryOnConfig
import com.drishtisaathi.engine.tryon3d.FaceAnchors
import com.drishtisaathi.engine.tryon3d.FacePose
import com.drishtisaathi.engine.tryon3d.GlassesFit
import com.drishtisaathi.engine.tryon3d.GlassesFitConfig
import com.drishtisaathi.engine.tryon3d.GlassesPlacement
import com.drishtisaathi.engine.tryon3d.HeadOccluder
import com.drishtisaathi.engine.tryon3d.PoseMatrixSmoother
import com.drishtisaathi.engine.tryon3d.V3
import com.drishtisaathi.engine.tryon3d.ViewIntrinsics
import com.drishtisaathi.engine.face.FaceFrame
import com.drishtisaathi.engine.tryon3d.Fit3D
import com.drishtisaathi.engine.tryon3d.FitWord
import com.drishtisaathi.engine.tryon3d.Frame3D
import com.drishtisaathi.engine.tryon3d.Mat4
import com.drishtisaathi.engine.tryon3d.Render3DFallback
import com.drishtisaathi.engine.tryon3d.Scale3DSource
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S3D
import com.drishtisaathi.i18n.STryOn
import com.drishtisaathi.ui.AppScaffold
import com.drishtisaathi.ui.Teal
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

fun fitWord(w: FitWord) = when (w) {
    FitWord.GOOD -> S3D.fitGood
    FitWord.SLIGHTLY_NARROW -> S3D.fitSlightlyNarrow
    FitWord.SLIGHTLY_WIDE -> S3D.fitSlightlyWide
    FitWord.TOO_NARROW -> S3D.fitTooNarrow
    FitWord.TOO_WIDE -> S3D.fitTooWide
}

/** Debug points for the alignment overlay (view pixels, as seen on the mirrored preview). */
class AlignDebug(
    val pupils: List<Offset>, val nasion: Offset, val lensCentres: List<Offset>, val pupilTarget: Offset?,
    val yaw: Double, val pitch: Double, val roll: Double,
)

/**
 * Live 3D try-on over the front-camera preview, done the way MediaPipe's face_effect sample does it: the glasses are
 * placed once in the canonical face space ([GlassesFit.place]); every frame only moves that space with the face pose
 * ([FacePose.solve]: rotation from the facial transformation matrix, translation solved with the real focal length),
 * rendered through a projection built from the camera intrinsics. An invisible head (face mesh + head ellipsoid +
 * ears) hides what is behind the head; one-euro smoothing on the pose. The renderer draws the un-mirrored image and
 * the whole view is mirrored like the preview, so the glasses turn with the head. Falls back to [fallback2D] when 3D
 * cannot start, a GLB fails or the phone is slow. [debug] (Device checks) draws the alignment dots.
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
    config: TryOnConfig,
    fallback2D: @Composable () -> Unit,
    onBack: () -> Unit,
    debug: Boolean = false,
) {
    val l = lang
    val context = LocalContext.current
    val render = rememberRender3D(transparent = true)
    val fallback = remember { Render3DFallback().also { if (render == null) it.rendererFailed() } }
    var twoD by remember { mutableStateOf(render == null) }
    var selectedId by remember { mutableStateOf(initialId ?: frames.firstOrNull()?.id) }
    val selected = frames.firstOrNull { it.id == selectedId } ?: frames.firstOrNull()
    var loadedId by remember { mutableStateOf<String?>(null) }
    var hasFace by remember { mutableStateOf(false) }
    var dbg by remember { mutableStateOf<AlignDebug?>(null) }
    val anchors = FaceAnchors.CANONICAL
    val fitCfg = remember(config) { GlassesFitConfig.from(config) }
    val placement = remember(selected?.id, pdMm, fitCfg) { selected?.let { GlassesFit.place(it, pdMm, anchors, fitCfg) } }
    val headMm = remember(pdMm) { GlassesFit.headWidthMm(anchors, pdMm) }
    val source = if (pdMm != null) Scale3DSource.CARD_PD else Scale3DSource.DEFAULT_PD

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
            r.load("head", HeadOccluder.glb(FaceMeshAsset.vertices(context), FaceMeshAsset.triangles(context)), castShadows = false, receiveShadows = false)
            r.makeOccluder("head")
            r.setSunDirection(-0.2f, -0.6f, -0.75f)
        }
    }
    LaunchedEffect(selected?.id, r) {
        val f = selected ?: return@LaunchedEffect
        val ok = runCatching { r.load("frame", glb(f), castShadows = false) }.isSuccess
        if (ok) loadedId = f.id else { fallback.glbFailed(); twoD = true }
    }
    val smoother = remember { PoseMatrixSmoother() }
    DisposableEffect(r, selected?.id, placement) {
        val hidden = Mat4.scale(0.0)
        r.onFrame = { t ->
            val face = faceFrames.value
            val f = selected
            val pl = placement
            var pose: FloatArray? = null
            var intr: ViewIntrinsics? = null
            if (face != null && face.imageWidthPx > 0) {
                val focal = face.focalLengthPx ?: (config.defaultFocalLengthFraction * maxOf(face.imageWidthPx, face.imageHeightPx))
                val vi = ViewIntrinsics(focal, face.imageWidthPx, face.imageHeightPx, r.width.toDouble(), r.height.toDouble())
                intr = vi
                r.intrinsicsCamera(vi.projection(NEAR_CM, FAR_CM), NEAR_CM, FAR_CM)
                pose = smoother.update(FacePose.solve(face, focal, anchors), t)
            } else smoother.update(null, t)
            if (pose == null || f == null || pl == null || loadedId != f.id) {
                r.setRoot("frame", hidden); r.setRoot("head", hidden)
                if (hasFace) hasFace = false
                if (debug && dbg != null) dbg = null
            } else {
                r.setRoot("frame", Mat4.mul(pose, pl.model))
                r.setNode("frame", "temple_R", pl.templeR)
                r.setNode("frame", "temple_L", pl.templeL)
                r.setRoot("head", pose)
                if (!hasFace) hasFace = true
                if (debug && face != null && intr != null) dbg = alignDebug(face, intr, pose, pl)
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
                if (debug) dbg?.let { AlignOverlay(it, Modifier.fillMaxSize()) }
                Hint(if (!hasFace) STryOn.noFace[l] else STryOn.lookAtCamera[l], Modifier.align(Alignment.TopCenter).padding(8.dp))
                Hint(when (source) {
                    Scale3DSource.CARD_PD -> S3D.scaleCard[l]
                    Scale3DSource.FACE_WIDTH -> S3D.scaleFace[l]
                    Scale3DSource.DEFAULT_PD -> S3D.scaleDefault[l]
                }, Modifier.align(Alignment.BottomCenter).padding(8.dp), small = true)
            }
            selected?.let { f ->
                val fit = " · " + S3D.fitText.format(f.frontWidthMm.roundToInt(), headMm.roundToInt())[l] +
                    " (" + fitWord(Fit3D.fitVerdict(f.frontWidthMm, headMm))[l] + ")"
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

private const val NEAR_CM = 1.0
private const val FAR_CM = 1000.0

private fun alignDebug(face: FaceFrame, intr: ViewIntrinsics, pose: FloatArray, pl: GlassesPlacement): AlignDebug {
    fun lm(i: Int) = intr.imageToView(face.landmarks[i].x.toDouble(), face.landmarks[i].y.toDouble(), face.mirrored)
        .let { Offset(it.first.toFloat(), it.second.toFloat()) }
    fun proj(p: V3) = Mat4.apply(pose, p.x, p.y, p.z).let { intr.project(V3(it[0], it[1], it[2]), face.mirrored) }
        .let { Offset(it.first.toFloat(), it.second.toFloat()) }
    val (yaw, pitch, roll) = FacePose.angles(pose)
    return AlignDebug(listOf(lm(468), lm(473)), lm(FaceAnchors.NASION), pl.lensCentres.map { proj(it) }, proj(pl.pupilTarget), yaw, pitch, roll)
}

/** Device checks only: green = observed pupils and nasion (landmarks); magenta = where the lens centres and the pupil point of the frame are drawn. */
@Composable
private fun AlignOverlay(d: AlignDebug, modifier: Modifier) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            d.pupils.forEach { drawCircle(Color.Green, 6f, it) }
            drawCircle(Color.Green, 6f, d.nasion)
            d.lensCentres.forEach { drawCircle(Color.Magenta, 6f, it, style = Stroke(3f)) }
            d.pupilTarget?.let { drawCircle(Color.Magenta, 9f, it, style = Stroke(3f)) }
        }
        Text(
            "yaw %.0f°  pitch %.0f°  roll %.0f°".format(d.yaw, d.pitch, d.roll),
            Modifier.align(Alignment.TopStart).padding(top = 44.dp, start = 8.dp).background(Color.Black.copy(alpha = 0.6f)).padding(4.dp),
            color = Color.White, style = MaterialTheme.typography.bodySmall,
        )
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
