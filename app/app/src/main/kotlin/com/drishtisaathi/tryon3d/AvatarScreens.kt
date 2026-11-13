package com.drishtisaathi.tryon3d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drishtisaathi.camera.MediaPipeFaceTracker
import com.drishtisaathi.camera.StillFaceLandmarker
import com.drishtisaathi.engine.config.TryOnConfig
import com.drishtisaathi.engine.face.FaceFrame
import com.drishtisaathi.engine.tryon3d.AvatarBuilder
import com.drishtisaathi.engine.tryon3d.AvatarCaptureGate
import com.drishtisaathi.engine.tryon3d.AvatarStep
import com.drishtisaathi.engine.tryon3d.AvatarView
import com.drishtisaathi.engine.tryon3d.CaptureMode
import com.drishtisaathi.engine.tryon3d.FaceAnchors
import com.drishtisaathi.engine.tryon3d.Frame3D
import com.drishtisaathi.engine.tryon3d.GlassesFit
import com.drishtisaathi.engine.tryon3d.GlassesFitConfig
import com.drishtisaathi.engine.tryon3d.Mat4
import com.drishtisaathi.engine.tryon3d.Pixels
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S3D
import com.drishtisaathi.i18n.Txt
import com.drishtisaathi.ui.AppScaffold
import com.drishtisaathi.ui.BigButton
import com.drishtisaathi.ui.Teal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream

/** MediaPipe canonical face model: topology (898 triangles) and the 468 canonical vertices (cm). */
object FaceMeshAsset {
    @Volatile private var cached: Pair<IntArray, FloatArray>? = null
    private fun load(context: Context): Pair<IntArray, FloatArray> = cached ?: synchronized(this) {
        cached ?: run {
            val o = Json.parseToJsonElement(context.assets.open("face_mesh_triangles.json").bufferedReader().use { it.readText() }).jsonObject
            val t = o["triangles"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }.toIntArray()
            val v = o["vertices"]!!.jsonArray.map { it.jsonPrimitive.content.toFloat() }.toFloatArray()
            (t to v).also { cached = it }
        }
    }
    fun triangles(context: Context): IntArray = load(context).first
    fun vertices(context: Context): FloatArray = load(context).second
}

/** The full-head template (MakeHuman CC0 head fitted to the canonical face; tools/head_template.py). */
object HeadTemplateAsset {
    @Volatile private var cached: com.drishtisaathi.engine.tryon3d.HeadTemplate? = null
    fun get(context: Context): com.drishtisaathi.engine.tryon3d.HeadTemplate? = cached ?: synchronized(this) {
        cached ?: runCatching {
            com.drishtisaathi.engine.tryon3d.HeadTemplate.parse(context.assets.open("head_template.json").bufferedReader().use { it.readText() })
        }.getOrNull()?.also { cached = it }
    }
}

@Composable
fun AvatarConsentScreen(lang: Lang, onLang: (Lang) -> Unit, onAgree: () -> Unit, onNo: () -> Unit) {
    val l = lang
    AppScaffold(l, onLang, title = S3D.avatarTitle[l], onBack = onNo) { pad ->
        Column(Modifier.padding(pad).padding(20.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(S3D.avatarConsentTitle[l], style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(S3D.avatarConsentBody[l], style = MaterialTheme.typography.bodyLarge)
            Text(S3D.avatarLabel[l], style = MaterialTheme.typography.bodyMedium)
            BigButton(S3D.avatarAgree[l], onAgree, container = Teal)
            BigButton(S3D.avatarNo[l], onNo)
        }
    }
}

fun stepPrompt(s: AvatarStep, mode: CaptureMode = CaptureMode.SELF): Txt = if (mode == CaptureMode.SELF) when (s) {
    AvatarStep.FRONT -> S3D.stepFront
    AvatarStep.LEFT -> S3D.selfLeft30
    AvatarStep.LEFT_60 -> S3D.selfLeft60
    AvatarStep.LEFT_90 -> S3D.selfLeft90
    AvatarStep.RIGHT -> S3D.selfRight30
    AvatarStep.RIGHT_60 -> S3D.selfRight60
    AvatarStep.RIGHT_90 -> S3D.selfRight90
    AvatarStep.TOP -> S3D.stepTop
} else when (s) {
    // the face turned to the customer's left (yaw > 0) = the phone moved to the customer's right
    AvatarStep.FRONT -> S3D.staffFront
    AvatarStep.LEFT -> S3D.staffRight30
    AvatarStep.LEFT_60 -> S3D.staffRight60
    AvatarStep.LEFT_90 -> S3D.staffRight90
    AvatarStep.RIGHT -> S3D.staffLeft30
    AvatarStep.RIGHT_60 -> S3D.staffLeft60
    AvatarStep.RIGHT_90 -> S3D.staffLeft90
    AvatarStep.TOP -> S3D.stepTop
}

private fun stepLabel(s: AvatarStep): String = when (s) {
    AvatarStep.FRONT -> "0°"
    AvatarStep.TOP -> "Top"
    else -> (if (s.side > 0) "L " else "R ") + kotlin.math.abs(s.yawDeg ?: 0.0).toInt() + "°"
}

/** One captured photo: what is stored (encrypted) and a small thumbnail for the screen. */
private class Shot(val view: AvatarStore.View, val thumb: ImageBitmap)

/** Every face point inside the picture (with a margin), so no part of the face is cut off. */
fun faceInFrame(f: FaceFrame, margin: Float = 0.02f): Boolean =
    f.landmarks.size >= 468 && f.landmarks.take(468).all { it.x in margin..(1 - margin) && it.y in margin..(1 - margin) }

/**
 * A shot from one full-resolution colour photo: cropped to the head (face box + [side]× its width at the sides, 90%
 * above for hair, 70% below for the neck), at most [MAX_CROP_PX] on the long side, JPEG 92. The landmarks stay in
 * full-photo coordinates. Without a face (profile, top of the head) the whole photo is kept (downscaled).
 */
private fun shotFrom(step: AvatarStep, bmp: Bitmap, f: FaceFrame?, focalPx: Double, side: Float = 0.8f): Shot {
    var cx0 = 0; var cy0 = 0; var cx1 = bmp.width; var cy1 = bmp.height
    if (f != null && f.landmarks.size >= 468) {
        val lm = f.landmarks.take(468)
        val x0 = lm.minOf { it.x } * bmp.width; val x1 = lm.maxOf { it.x } * bmp.width
        val y0 = lm.minOf { it.y } * bmp.height; val y1 = lm.maxOf { it.y } * bmp.height
        // room for big hair at the sides and on top, and the neck down to the collar
        val mx = (x1 - x0) * side; val myTop = (y1 - y0) * 0.9f; val myBottom = (y1 - y0) * 0.7f
        cx0 = (x0 - mx).toInt().coerceIn(0, bmp.width - 1); cy0 = (y0 - myTop).toInt().coerceIn(0, bmp.height - 1)
        cx1 = (x1 + mx).toInt().coerceIn(cx0 + 1, bmp.width); cy1 = (y1 + myBottom).toInt().coerceIn(cy0 + 1, bmp.height)
    }
    val crop = Bitmap.createBitmap(bmp, cx0, cy0, cx1 - cx0, cy1 - cy0)
    val sc = minOf(1f, MAX_CROP_PX.toFloat() / maxOf(crop.width, crop.height))
    val img = if (sc < 1f) Bitmap.createScaledBitmap(crop, (crop.width * sc).toInt(), (crop.height * sc).toInt(), true).also { if (crop !== bmp) crop.recycle() } else crop
    val jpeg = ByteArrayOutputStream().also { img.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
    val th = Bitmap.createScaledBitmap(img, 96, (96f * img.height / img.width).toInt().coerceAtLeast(1), true)
    if (img !== bmp) img.recycle()
    val withFace = f != null && f.landmarks.size >= 468
    return Shot(
        AvatarStore.View(step, jpeg, if (withFace) f!!.landmarks else emptyList(), bmp.width, bmp.height, if (withFace) f!!.transformMatrix else null,
            focalPx, cx0.toDouble(), cy0.toDouble(), sc.toDouble()),
        th.asImageBitmap(),
    )
}

private const val MAX_CROP_PX = 2000

/** ARGB pixels of a bitmap (for the colour check). */
private fun pixelsOf(b: Bitmap, step: Int = 1): Pixels {
    val w = b.width / step; val h = b.height / step
    val small = if (step > 1) Bitmap.createScaledBitmap(b, w, h, false) else b
    val px = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
    if (small !== b) small.recycle()
    return Pixels(w, h, px)
}

/**
 * Guided capture over 180°: front, 30°, 60° and 90° (profile) to each side, and an optional photo from above for the
 * hair. Two modes: Self (the customer turns the head in front of the front camera) and Staff (the customer looks
 * straight ahead and staff walk the phone round the head at the same height, back camera). Each photo is taken
 * automatically once the pose is held for 0.5 s and the quality gates pass (face fully in the picture, enough light,
 * colour). For the profiles, where MediaPipe may lose the face, the last good head pose is used (the placement is
 * checked later against the segmentation, and a view that does not fit is left out). The prompt is spoken once per
 * step; every photo can be retaken; the profiles and the top can be skipped (the 60° photos are enough). The photos
 * are stored encrypted on this phone only.
 */
@Composable
fun AvatarCaptureScreen(
    lang: Lang,
    onLang: (Lang) -> Unit,
    tracker: MediaPipeFaceTracker,
    mode: CaptureMode,
    onMode: (CaptureMode) -> Unit,
    cameraPreview: @Composable () -> Unit,
    speak: (Txt) -> Unit,
    pdMm: Double?,
    minBrightness: Double,
    maxBrightness: Double,
    defaultFocalFraction: Double,
    store: AvatarStore,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val l = lang
    val gate = remember { AvatarCaptureGate() }
    val shots = remember { mutableStateMapOf<AvatarStep, Shot>() }
    val skipped = remember { mutableStateListOf<AvatarStep>() }
    var step by remember { mutableStateOf<AvatarStep?>(AvatarStep.FRONT) }
    var gs by remember { mutableStateOf<AvatarCaptureGate.State?>(null) }
    var yawNow by remember { mutableStateOf<Double?>(null) }
    var dark by remember { mutableStateOf(false) }
    var hasFace by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var shooting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val still = remember { lazy { StillFaceLandmarker(context) } }
    DisposableEffect(Unit) { onDispose { if (still.isInitialized()) runCatching { still.value.close() } } }
    fun next() { step = AvatarStep.ORDER.firstOrNull { it !in shots && it !in skipped } }
    val ready = AvatarStep.REQUIRED.all { it in shots }

    LaunchedEffect(step, mode) { step?.let { speak(stepPrompt(it, mode)) } }
    LaunchedEffect(step, tracker) {
        val s = step ?: return@LaunchedEffect
        if (s == AvatarStep.TOP) return@LaunchedEffect          // no face to track: taken by hand
        gate.reset()
        tracker.analysis.collect { a ->
            val face = a?.face
            val ang = AvatarCaptureGate.angles(face)
            hasFace = face != null
            yawNow = ang?.first
            val light = a?.stats?.meanBrightness?.let { it in minBrightness..maxBrightness } ?: true
            dark = face != null && !light
            val quality = if (s.isProfile) light else face != null && light && faceInFrame(face)
            val st = gate.update(s, ang?.first, ang?.second, quality, a?.timestampMs ?: 0L)
            gs = st
            if (st.ready) {
                // a full-resolution colour photo from ImageCapture; the landmarks come from that same photo
                val shot = runCatching {
                    val bmp = tracker.takePhoto()
                    try {
                        withContext(Dispatchers.Default) {
                            val focal = tracker.focalPxFor(bmp.width, bmp.height)
                            val fr = still.value.detect(bmp, focal)
                            val ang2 = AvatarCaptureGate.angles(fr)
                            val colour = AvatarBuilder.chroma(pixelsOf(bmp, 8)) >= AvatarBuilder.MIN_CHROMA
                            when {
                                !colour -> null
                                s.isProfile -> {
                                    // a profile: kept whole; refused only when the face is clearly turned back towards the phone
                                    if (ang2 != null && kotlin.math.abs(ang2.first) < 45.0) null
                                    else shotFrom(s, bmp, null, focal)
                                }
                                fr != null && ang2 != null && gate.poseOk(s, ang2.first, ang2.second) && faceInFrame(fr) ->
                                    shotFrom(s, bmp, fr, focal, side = if (kotlin.math.abs(s.yawDeg ?: 0.0) >= 50.0) 1.4f else 0.8f)
                                else -> null
                            }
                        }
                    } finally { bmp.recycle() }
                }.getOrNull()
                if (shot != null) {
                    shots[s] = shot
                    next()
                } else gate.reset()
            }
        }
    }

    AppScaffold(l, onLang, title = S3D.avatarTitle[l], onBack = onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // Self / Staff
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in CaptureMode.entries) {
                    val sel = m == mode
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (sel) Teal else Color(0xFFE6ECEC))
                        .clickable(enabled = !busy) { onMode(m) }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text((if (m == CaptureMode.SELF) S3D.modeSelf else S3D.modeStaff)[l], color = if (sel) Color.White else Color.Black,
                            style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                cameraPreview()
                val s = step
                if (s != null && !busy) {
                    val st = gs
                    val target = gate.targetYaw(s)
                    val y = yawNow
                    val msg = when {
                        s == AvatarStep.TOP -> stepPrompt(s, mode)[l]
                        st?.fromLastPose == true -> S3D.profileLastPose[l]
                        !hasFace && !s.isProfile -> com.drishtisaathi.i18n.STryOn.noFace[l]
                        dark -> S3D.moreLight[l]
                        st?.poseOk == true -> S3D.avatarHold[l]
                        s != AvatarStep.FRONT && y != null && kotlin.math.abs(y) < kotlin.math.abs(target) -> stepPrompt(s, mode)[l] + " · " + S3D.turnMore[l]
                        s != AvatarStep.FRONT && y != null && gate.zone(s)?.let { y in it } == false -> S3D.turnLess[l]
                        else -> stepPrompt(s, mode)[l]
                    }
                    Hint(msg, Modifier.align(Alignment.TopCenter).padding(8.dp))
                    if (s.side != 0) GuideArrow(pointLeft = s.side > 0, Modifier.align(Alignment.Center).size(110.dp))
                    if (s != AvatarStep.TOP) YawIndicator(y, s, gate, ok = st?.poseOk == true, held = st?.held ?: 0.0,
                        Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth().height(40.dp))
                    Row(Modifier.align(Alignment.BottomEnd).padding(bottom = 60.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (s == AvatarStep.TOP) BigButton(S3D.takePhoto[l], {
                            shooting = true
                            scope.launch {
                                val shot = runCatching {
                                    val bmp = tracker.takePhoto()
                                    try { withContext(Dispatchers.Default) { shotFrom(s, bmp, null, tracker.focalPxFor(bmp.width, bmp.height)) } } finally { bmp.recycle() }
                                }.getOrNull()
                                shooting = false
                                if (shot != null) { shots[s] = shot; next() }
                            }
                        }, enabled = !shooting, container = Teal)
                        if (s.optional) BigButton(S3D.skip[l], { skipped += s; next() }, enabled = !shooting)
                    }
                }
                if (busy || shooting) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            }
            // the photos, each with its own retake
            LazyRow(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(AvatarStep.ORDER, key = { it.name }) { s ->
                    val shot = shots[s]
                    Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.height(64.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFFE6ECEC))
                            .border(BorderStroke(if (s == step) 3.dp else 1.dp, if (s == step) Teal else Color.LightGray), RoundedCornerShape(8.dp))
                            .clickable(enabled = !busy && shot == null && s in skipped) { skipped.remove(s); step = s }) {
                            if (shot != null) Image(shot.thumb, null, Modifier.fillMaxSize().graphicsLayer { scaleX = if (mode == CaptureMode.SELF) -1f else 1f }, contentScale = ContentScale.Crop)
                            else if (s in skipped) Text(S3D.skip[l], Modifier.align(Alignment.Center), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                        Text(if (s == AvatarStep.TOP) S3D.photoTop[l] else stepLabel(s), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        if (shot != null) TextButton({ shots.remove(s); step = s; failed = false }, enabled = !busy,
                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)) {
                            Text(S3D.retake[l], maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            if (failed) Text(S3D.avatarFailed[l], Modifier.padding(horizontal = 12.dp), color = Color(0xFFB00020))
            if (ready) {
                if (step != null) Text(S3D.enoughPhotos[l], Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
                BigButton(S3D.makeAvatar[l], {
                    busy = true; failed = false
                    scope.launch {
                        val ok = runCatching {
                            val views = AvatarStep.ORDER.mapNotNull { shots[it]?.view }
                            withContext(Dispatchers.IO) { store.save(AvatarStore.Avatar(views, pdMm)) }
                        }.isSuccess
                        busy = false
                        if (ok) onDone() else failed = true
                    }
                }, Modifier.padding(12.dp), enabled = !busy && !shooting, container = Teal)
            }
        }
    }
}

/** Big arrow showing which way to turn (Self, mirror-like preview) or to walk (Staff: the customer's right is on your left). */
@Composable
private fun GuideArrow(pointLeft: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val dir = if (pointLeft) -1f else 1f
        val cx = w / 2
        val p = Path().apply {
            moveTo(cx + dir * w * 0.42f, h / 2)
            lineTo(cx + dir * w * 0.05f, h * 0.18f)
            lineTo(cx + dir * w * 0.05f, h * 0.36f)
            lineTo(cx - dir * w * 0.40f, h * 0.36f)
            lineTo(cx - dir * w * 0.40f, h * 0.64f)
            lineTo(cx + dir * w * 0.05f, h * 0.64f)
            lineTo(cx + dir * w * 0.05f, h * 0.82f)
            close()
        }
        drawPath(p, Color.White.copy(alpha = 0.75f))
        drawPath(p, Teal, style = Stroke(4f))
    }
}

/**
 * Live yaw: a bar from −100° to +100° with the target zone of every step (the current one bright) and a dot for the
 * current head turn (turning to your left, or walking to the customer's right, moves the dot left). The dot fills
 * while the pose is held.
 */
@Composable
private fun YawIndicator(yawDeg: Double?, step: AvatarStep, gate: AvatarCaptureGate, ok: Boolean, held: Double, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.5f))) {
        val w = size.width; val h = size.height
        fun x(deg: Double) = (w / 2 - (deg / 100.0).coerceIn(-1.0, 1.0) * (w / 2 - 20)).toFloat()
        drawLine(Color.White.copy(alpha = 0.6f), Offset(20f, h / 2), Offset(w - 20f, h / 2), strokeWidth = 4f)
        for (s in AvatarStep.ORDER) {
            val z = gate.zone(s) ?: continue
            val cur = s == step
            drawLine(if (cur) Color(0xFF7CE0C8) else Color.White.copy(alpha = 0.25f), Offset(x(z.start), h / 2), Offset(x(z.endInclusive.coerceAtMost(100.0)), h / 2),
                strokeWidth = if (cur) 16f else 8f)
        }
        yawDeg?.let {
            val c = Offset(x(it), h / 2)
            drawCircle(if (ok) Color(0xFF2ECC71) else Color.White, h * 0.3f, c)
            if (ok) drawArc(Teal, -90f, (360 * held).toFloat(), false, Offset(c.x - h * 0.4f, c.y - h * 0.4f),
                androidx.compose.ui.geometry.Size(h * 0.8f, h * 0.8f), style = Stroke(5f))
        }
    }
}

/** Icon above a short one-line label; the whole cell is the button. */
@Composable
private fun IconAction(label: String, colour: Color, icon: DrawScope.(Color) -> Unit, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(24.dp)) { icon(colour) }
        Text(label, color = colour, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium)
    }
}

private fun drawCompareIcon(d: DrawScope, c: Color) = with(d) {
    val w = size.width; val h = size.height; val st = Stroke(w * 0.09f)
    drawRoundRect(c, Offset(w * 0.04f, h * 0.2f), androidx.compose.ui.geometry.Size(w * 0.4f, h * 0.6f), CornerRadius(w * 0.08f), style = st)
    drawRoundRect(c, Offset(w * 0.56f, h * 0.2f), androidx.compose.ui.geometry.Size(w * 0.4f, h * 0.6f), CornerRadius(w * 0.08f), style = st)
}

private fun drawRetakeIcon(d: DrawScope, c: Color) = with(d) {
    val w = size.width; val st = Stroke(w * 0.1f)
    drawArc(c, 40f, 290f, false, Offset(w * 0.15f, w * 0.15f), androidx.compose.ui.geometry.Size(w * 0.7f, w * 0.7f), style = st)
    val head = Path().apply { moveTo(w * 0.95f, w * 0.38f); lineTo(w * 0.62f, w * 0.38f); lineTo(w * 0.86f, w * 0.1f); close() }
    drawPath(head, c)
}

private fun drawDeleteIcon(d: DrawScope, c: Color) = with(d) {
    val w = size.width; val st = Stroke(w * 0.09f)
    drawLine(c, Offset(w * 0.15f, w * 0.25f), Offset(w * 0.85f, w * 0.25f), strokeWidth = w * 0.1f)
    drawLine(c, Offset(w * 0.4f, w * 0.12f), Offset(w * 0.6f, w * 0.12f), strokeWidth = w * 0.1f)
    drawRoundRect(c, Offset(w * 0.25f, w * 0.32f), androidx.compose.ui.geometry.Size(w * 0.5f, w * 0.58f), CornerRadius(w * 0.06f), style = st)
}

/** The avatar built from the stored photos (in memory only), its face anchors and how long it took to make. */
class BuiltAvatar(val glb: ByteArray, val anchors: FaceAnchors, val pdMm: Double?, val buildMs: Long = 0, val photos: Int = 0)

/**
 * Builds the avatar from the encrypted store: shape from the front and 30° views, texture and hair outline from every
 * photo with a pose (the profiles placed from the 60° views and the segmentation), hair texture also from the photo
 * above the head; GLB. Null if it cannot.
 */
fun buildAvatar(context: Context, store: AvatarStore): BuiltAvatar? = runCatching {
    val t0 = android.os.SystemClock.elapsedRealtime()
    val stored = store.load() ?: return null
    val a = AvatarStore.Avatar(stored.views.filter { it.step != AvatarStep.TOP }, stored.pdMm)
    val tops = stored.views.filter { it.step == AvatarStep.TOP }
    val views = a.views.map { AvatarView(it.step, it.landmarks, it.imageW, it.imageH, it.matrix, it.focalPx) }
    // each photo with its segmentation (hair / face-skin / body-skin / background / clothes), on the phone only
    val segmenter = runCatching { com.drishtisaathi.camera.PhotoSegmenter(context) }.getOrNull()
    fun decode(v: AvatarStore.View): Pixels {
        val b = BitmapFactory.decodeByteArray(v.jpeg, 0, v.jpeg.size)
        val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        val seg = segmenter?.classes(b)
        return Pixels(b.width, b.height, px, v.cropX, v.cropY, v.cropScale, seg?.first, seg?.second ?: 0, seg?.third ?: 0).also { b.recycle() }
    }
    val (photos, topPhotos) = try {
        a.views.map(::decode) to tops.map(::decode)
    } finally {
        segmenter?.close()
    }
    val fi = a.views.indexOfFirst { it.step == AvatarStep.FRONT }.takeIf { it >= 0 } ?: return null
    val fv = a.views[fi]
    val skin = AvatarBuilder.medianColour(photos[fi], fv.imageW, fv.imageH, AvatarBuilder.skinPoints(fv.landmarks))
    // hair colour: the hair pixels of all three photos (segmentation), else above the forehead in each photo
    val hair = AvatarBuilder.classMedian(photos + topPhotos, com.drishtisaathi.engine.tryon3d.SegClass.HAIR_BITS)
        ?: AvatarBuilder.medianColour(a.views.indices.filter { a.views[it].landmarks.size >= 468 }.map { k ->
            Triple(photos[k], a.views[k].imageW to a.views[k].imageH, AvatarBuilder.hairPoints(a.views[k].landmarks))
        })
    val canonical = FaceMeshAsset.vertices(context)
    val tris = FaceMeshAsset.triangles(context)
    // the real head shape from the template; the face-only avatar if the template cannot be used
    val model = HeadTemplateAsset.get(context)?.let { t -> runCatching { AvatarBuilder.buildHead(views, photos, canonical, tris, t, skin, hair, topPhotos) }.getOrNull() }
        ?: AvatarBuilder.build(views, photos, canonical, tris, skin) ?: return null
    val tex = Bitmap.createBitmap(model.texture, AvatarBuilder.TEX, AvatarBuilder.TEX, Bitmap.Config.ARGB_8888)
    val jpeg = ByteArrayOutputStream().also { tex.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    tex.recycle()
    val ms = android.os.SystemClock.elapsedRealtime() - t0
    android.util.Log.i("Avatar3D", "avatar built from ${stored.views.size} photos in $ms ms")
    BuiltAvatar(AvatarBuilder.glb(model, jpeg, skin, hair), model.anchors, a.pdMm, ms, stored.views.size)
}.getOrNull()

/**
 * One 3D pane: the avatar with one frame on it, placed with [GlassesFit.place] (the same canonical-space placement as
 * the live try-on), turned by the shared [orbit].
 */
@Composable
fun AvatarPane(avatar: BuiltAvatar, frame: Frame3D?, glb: suspend (Frame3D) -> ByteArray, orbit: Orbit, fitCfg: GlassesFitConfig, modifier: Modifier) {
    val render = rememberRender3D(transparent = false)
    if (render == null) { Box(modifier); return }
    var frameReady by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(render) {
        runCatching { render.load("avatar", avatar.glb); render.setSunDirection(-0.25f, -0.55f, -0.8f) }
    }
    LaunchedEffect(render, frame?.id) {
        frameReady = null
        if (frame == null) { render.remove("frame"); return@LaunchedEffect }
        if (runCatching { render.load("frame", glb(frame)) }.isSuccess) frameReady = frame.id
    }
    val placement = remember(frame?.id, avatar, fitCfg) { frame?.let { GlassesFit.place(it, avatar.pdMm, avatar.anchors, fitCfg) } }
    DisposableEffect(render, frame?.id, placement) {
        val a = avatar.anchors
        val eyeY = (a.pupilR.y + a.pupilL.y) / 2
        // turn about the middle of the head; look at it from the front (+z)
        val c = doubleArrayOf(0.0, eyeY - 1.5, a.nasion.z - 8.0)
        render.onFrame = {
            val d = 66.0 / orbit.zoom
            render.perspectiveLookAt(doubleArrayOf(c[0], c[1], c[2] + d), c, fovDeg = 30.0, near = 5.0, far = 400.0)
            val rot = Mat4.mul(
                Mat4.translate(c[0], c[1], c[2]),
                Mat4.rotX(Math.toRadians(orbit.pitchDeg.toDouble())), Mat4.rotY(Math.toRadians(orbit.yawDeg.toDouble())),
                Mat4.translate(-c[0], -c[1], -c[2]),
            )
            render.setRoot("avatar", rot)
            val f = frame
            val pl = placement
            if (f != null && pl != null && frameReady == f.id) {
                render.setRoot("frame", Mat4.mul(rot, pl.model))
                render.setNode("frame", "temple_R", pl.templeR)
                render.setNode("frame", "temple_L", pl.templeL)
            }
        }
        onDispose { render.onFrame = null }
    }
    Box(modifier) { Render3DView(render, Modifier.fillMaxSize()) }
}

/**
 * Avatar viewer: the 3D view takes the top 60% of the screen (labels below it, never over it); drag turns ±90°,
 * pinch zooms; frame carousel; compare two frames side by side (rotated together); session favourites.
 */
@Composable
fun AvatarViewerScreen(
    lang: Lang,
    onLang: (Lang) -> Unit,
    store: AvatarStore,
    frames: List<Frame3D>,
    initialId: String?,
    glb: suspend (Frame3D) -> ByteArray,
    thumb: suspend (Frame3D) -> Bitmap?,
    favourites: Set<String>,
    onFavourite: (String) -> Unit,
    config: TryOnConfig,
    onRetake: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    val l = lang
    val context = LocalContext.current
    val built by produceState<Pair<BuiltAvatar?, Boolean>>(null to false) {
        value = withContext(Dispatchers.Default) { buildAvatar(context, store) } to true
    }
    val fitCfg = remember(config) { GlassesFitConfig.from(config) }
    val orbit = remember { Orbit(maxYawDeg = 90f, maxPitchDeg = 25f).also { it.yawDeg = 20f } }
    var first by remember { mutableStateOf(initialId ?: frames.firstOrNull()?.id) }
    var second by remember { mutableStateOf<String?>(null) }
    var comparing by remember { mutableStateOf(false) }
    val f1 = frames.firstOrNull { it.id == first }
    val f2 = frames.firstOrNull { it.id == second }

    AppScaffold(l, onLang, title = S3D.avatarTitle[l], onBack = onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            val (b, done) = built
            Box(Modifier.fillMaxWidth().fillMaxHeight(0.6f).background(Color(0xFFEEF2F2))) {
                when {
                    b != null -> {
                        Row(Modifier.fillMaxSize()) {
                            AvatarPane(b, f1, glb, orbit, fitCfg, Modifier.weight(1f).fillMaxHeight())
                            if (comparing) AvatarPane(b, f2, glb, orbit, fitCfg, Modifier.weight(1f).fillMaxHeight())
                        }
                        Box(Modifier.fillMaxSize().orbitGestures(orbit))
                    }
                    done -> Text(S3D.avatarFailed[l], Modifier.align(Alignment.Center).padding(24.dp), textAlign = TextAlign.Center)
                    else -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(S3D.avatarMaking[l], Modifier.padding(8.dp))
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                val made = b?.takeIf { it.buildMs > 0 }?.let { " · ${it.photos} 📷 · %.1f s".format(java.util.Locale.US, it.buildMs / 1000.0) } ?: ""
                Text(S3D.avatarLabel[l] + " · " + S3D.avatarHint[l] + made, Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                f1?.let { f ->
                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(f.name + " · " + rupees(f.priceInr), Modifier.weight(1f), color = Teal, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton({ onFavourite(f.id) }) {
                            Text(if (f.id in favourites) S3D.favourited[l] else S3D.favourite[l], maxLines = 1, softWrap = false)
                        }
                    }
                }
                if (comparing) Text(if (f2 == null) S3D.pickSecond[l] else S3D.compare[l] + ": " + f2.name + " · " + rupees(f2.priceInr),
                    Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(frames, key = { it.id }) { f ->
                        val sel = f.id == first || (comparing && f.id == second)
                        FrameThumb(f, thumb, Modifier.width(110.dp).height(48.dp).clip(RoundedCornerShape(10.dp))
                            .border(BorderStroke(if (sel) 3.dp else 1.dp, if (sel) Teal else Color.LightGray), RoundedCornerShape(10.dp))
                            .clickable { if (comparing) second = f.id else first = f.id })
                    }
                }
                if (favourites.isNotEmpty()) Text(S3D.favourites[l] + ": " + frames.filter { it.id in favourites }.joinToString { it.name },
                    Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
                    IconAction(if (comparing) S3D.compareOff[l] else S3D.compare[l], Teal, ::drawCompareIcon, Modifier.weight(1f)) { comparing = !comparing }
                    IconAction(S3D.retake[l], Teal, ::drawRetakeIcon, Modifier.weight(1f), onRetake)
                    IconAction(S3D.deleteShort[l], Color(0xFFB00020), ::drawDeleteIcon, Modifier.weight(1f), onDelete)
                }
            }
        }
    }
}
