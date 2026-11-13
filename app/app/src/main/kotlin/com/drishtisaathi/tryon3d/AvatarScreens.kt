package com.drishtisaathi.tryon3d

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.drishtisaathi.camera.MediaPipeFaceTracker
import com.drishtisaathi.engine.face.FaceFrame
import com.drishtisaathi.engine.tryon.TryOnPlacement
import com.drishtisaathi.engine.tryon3d.AvatarMesh
import com.drishtisaathi.engine.tryon3d.Fit3D
import com.drishtisaathi.engine.tryon3d.Frame3D
import com.drishtisaathi.engine.tryon3d.Mat4
import com.drishtisaathi.engine.tryon3d.TryOn3DConfig
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S3D
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
import kotlin.math.abs

/** MediaPipe canonical face-mesh triangles (assets/face_mesh_triangles.json, Apache-2.0). */
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

/** Head straight enough for the front photo (yaw/pitch from the facial transformation matrix). */
fun frontalEnough(f: FaceFrame?): Boolean {
    if (f == null || f.landmarks.size < 474) return false
    val m = f.transformMatrix ?: return true
    val (yaw, pitch) = TryOnPlacement.yawPitch(m)
    return abs(Math.toDegrees(yaw)) < 10 && abs(Math.toDegrees(pitch)) < 12
}

/**
 * Front-view capture for the avatar (the required view; side views are not used in this version). The photo and
 * landmarks come from the same analysed frame. Spoken prompt once.
 */
@Composable
fun AvatarCaptureScreen(
    lang: Lang,
    onLang: (Lang) -> Unit,
    tracker: MediaPipeFaceTracker,
    cameraPreview: @Composable () -> Unit,
    /** Speaks the capture prompt once. */
    speak: () -> Unit,
    pdMm: Double?,
    store: AvatarStore,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val l = lang
    val face by tracker.frames.collectAsState()
    val ok = frontalEnough(face)
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { speak() }
    AppScaffold(l, onLang, title = S3D.avatarTitle[l], onBack = onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                cameraPreview()
                Hint(
                    when {
                        busy -> S3D.avatarMaking[l]
                        face == null -> com.drishtisaathi.i18n.STryOn.noFace[l]
                        !ok -> S3D.avatarTurnFace[l]
                        else -> S3D.avatarCapture[l]
                    },
                    Modifier.align(Alignment.TopCenter).padding(8.dp),
                )
                if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            }
            if (failed) Text(S3D.avatarFailed[l], Modifier.padding(12.dp), color = Color(0xFFB00020))
            BigButton(S3D.avatarTakeFront[l], {
                busy = true; failed = false
                scope.launch {
                    val okSave = runCatching {
                        val (bmp, frame) = tracker.grabFrame()
                        val avatar = withContext(Dispatchers.Default) { avatarFrom(bmp, frame, pdMm).also { bmp.recycle() } }
                        withContext(Dispatchers.IO) { store.save(avatar) }
                    }.isSuccess
                    busy = false
                    if (okSave) onDone() else failed = true
                }
            }, Modifier.padding(16.dp), enabled = ok && !busy, container = Teal)
        }
    }
}

/** Crop the photo to the face (+margin), JPEG it, sample the skin colour on the cheeks. */
fun avatarFrom(bmp: Bitmap, frame: FaceFrame, pdMm: Double?): AvatarStore.Avatar {
    val lm = frame.landmarks.take(AvatarMesh.MESH_POINTS)
    val x0 = lm.minOf { it.x }; val x1 = lm.maxOf { it.x }
    val y0 = lm.minOf { it.y }; val y1 = lm.maxOf { it.y }
    val mx = (x1 - x0) * 0.12f; val my = (y1 - y0) * 0.12f
    val crop = floatArrayOf((x0 - mx).coerceAtLeast(0f), (y0 - my).coerceAtLeast(0f), (x1 + mx).coerceAtMost(1f), (y1 + my).coerceAtMost(1f))
    val cx = (crop[0] * bmp.width).toInt(); val cy = (crop[1] * bmp.height).toInt()
    val cw = ((crop[2] - crop[0]) * bmp.width).toInt().coerceAtLeast(1)
    val ch = ((crop[3] - crop[1]) * bmp.height).toInt().coerceAtLeast(1)
    var face = Bitmap.createBitmap(bmp, cx, cy, minOf(cw, bmp.width - cx), minOf(ch, bmp.height - cy))
    if (face.width > 720) face = Bitmap.createScaledBitmap(face, 720, (face.height * 720f / face.width).toInt(), true)
    val jpeg = ByteArrayOutputStream().also { face.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
    // skin: cheeks and forehead, 5×5 patches
    val samples = mutableListOf<IntArray>()
    for (i in intArrayOf(50, 280, 101, 330, 151, 205, 425)) {
        val px = (lm[i].x * bmp.width).toInt(); val py = (lm[i].y * bmp.height).toInt()
        for (dx in -2..2) for (dy in -2..2) {
            val x = (px + dx).coerceIn(0, bmp.width - 1); val y = (py + dy).coerceIn(0, bmp.height - 1)
            val c = bmp.getPixel(x, y)
            samples += intArrayOf((c shr 16) and 255, (c shr 8) and 255, c and 255)
        }
    }
    val skin = IntArray(3) { k -> samples.map { it[k] }.sorted()[samples.size / 2] }
    return AvatarStore.Avatar(jpeg, crop, frame.landmarks, frame.imageWidthPx, frame.imageHeightPx, skin, pdMm)
}

/** One 3D pane: the avatar with one frame on it (same anchors as the live try-on), driven by a shared [orbit]. */
@Composable
fun AvatarPane(avatarGlb: ByteArray, faceWidthMm: Double, frame: Frame3D?, glb: suspend (Frame3D) -> ByteArray, orbit: Orbit, modifier: Modifier) {
    val render = rememberRender3D(transparent = false)
    if (render == null) { Box(modifier); return }
    var frameReady by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(render) {
        runCatching { render.load("avatar", avatarGlb); render.setSunDirection(-0.25f, -0.55f, 0.8f) }
    }
    LaunchedEffect(render, frame?.id) {
        frameReady = null
        if (frame == null) { render.remove("frame"); return@LaunchedEffect }
        if (runCatching { render.load("frame", glb(frame)) }.isSuccess) frameReady = frame.id
    }
    DisposableEffect(render, frame?.id) {
        val cfg = TryOn3DConfig()
        render.onFrame = {
            render.perspectiveFront(470.0 / orbit.zoom)
            val rot = Mat4.mul(Mat4.rotX(Math.toRadians(orbit.pitchDeg.toDouble())), Mat4.rotY(Math.toRadians(orbit.yawDeg.toDouble())))
            render.setRoot("avatar", Mat4.mul(rot, Mat4.translate(0.0, 0.0, -10.0)))
            val f = frame
            if (f != null && frameReady == f.id) {
                val n = f.anchors.noseRestMm
                // nose rest on the nose-bridge point (avatar origin), the front a few mm in front of the skin
                render.setRoot("frame", Mat4.mul(rot, Mat4.translate(0.0, 0.0, -10.0),
                    Mat4.translate(-n[0], -n[1] - cfg.bridgeDropMm, -n[2] - 3.0)))
                val (tr, tl) = Fit3D.templeTransforms(f, Fit3D.templeSplayDeg(f.hingeHalfWidthMm, faceWidthMm, cfg))
                render.setNode("frame", "temple_R", tr)
                render.setNode("frame", "temple_L", tl)
            }
        }
        onDispose { render.onFrame = null }
    }
    Box(modifier) { Render3DView(render, Modifier.fillMaxSize()) }
}

/**
 * Avatar viewer: turn ±60°, zoom, put any catalogue frame on it, compare two frames side by side, save favourites
 * for the session.
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
    onRetake: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    val l = lang
    val context = LocalContext.current
    val built by produceState<Pair<ByteArray, Double>?>(null) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val a = store.load() ?: return@runCatching null
                val g = AvatarMesh.geometry(a.landmarks, a.imageW, a.imageH, FaceMeshAsset.triangles(context), a.pdMm, a.crop)
                AvatarMesh.glb(g, a.jpeg, a.skinRgb) to g.faceWidthMm
            }.getOrNull()
        }
    }
    val orbit = remember { Orbit(maxYawDeg = 60f, maxPitchDeg = 20f).also { it.yawDeg = 20f } }
    var first by remember { mutableStateOf(initialId ?: frames.firstOrNull()?.id) }
    var second by remember { mutableStateOf<String?>(null) }
    var comparing by remember { mutableStateOf(false) }
    val f1 = frames.firstOrNull { it.id == first }
    val f2 = frames.firstOrNull { it.id == second }

    AppScaffold(l, onLang, title = S3D.avatarTitle[l], onBack = onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            val b = built
            Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFFEEF2F2))) {
                if (b == null) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else {
                    Row(Modifier.fillMaxSize()) {
                        AvatarPane(b.first, b.second, f1, glb, orbit, Modifier.weight(1f).fillMaxSize())
                        if (comparing) AvatarPane(b.first, b.second, f2, glb, orbit, Modifier.weight(1f).fillMaxSize())
                    }
                    Box(Modifier.fillMaxSize().orbitGestures(orbit))
                    Hint(S3D.avatarLabel[l], Modifier.align(Alignment.TopCenter).padding(6.dp), small = true)
                    Hint(S3D.avatarHint[l], Modifier.align(Alignment.BottomCenter).padding(6.dp), small = true)
                }
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                f1?.let { f ->
                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(f.name + " · " + rupees(f.priceInr), Modifier.weight(1f), color = Teal, fontWeight = FontWeight.SemiBold)
                        TextButton({ onFavourite(f.id) }) { Text(if (f.id in favourites) S3D.favourited[l] else S3D.favourite[l]) }
                    }
                }
                if (comparing) Text(if (f2 == null) S3D.pickSecond[l] else "↔ " + f2.name + " · " + rupees(f2.priceInr),
                    Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyMedium)
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
                Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton({ comparing = !comparing }) { Text(S3D.compare[l]) }
                    TextButton(onRetake) { Text(S3D.retake[l]) }
                    TextButton(onDelete) { Text(S3D.deleteAvatar[l], color = Color(0xFFB00020)) }
                }
            }
        }
    }
}
