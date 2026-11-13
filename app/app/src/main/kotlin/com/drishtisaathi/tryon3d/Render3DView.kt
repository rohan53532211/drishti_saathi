package com.drishtisaathi.tryon3d

import android.view.TextureView
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView

/**
 * A Filament [Render3D] in a transparent TextureView. Returns null (and draws nothing) when this phone cannot run
 * the 3D renderer; callers then show the 2D version without an error dialog.
 *
 * @param mirrored draws the view mirrored left-right (front-camera preview), so the model itself never needs a
 *   mirror transform (that would flip triangle winding).
 */
@Composable
fun rememberRender3D(transparent: Boolean = true): Render3D? {
    val r = remember {
        if (!Render3D.available()) null else runCatching { Render3D(transparent) }.getOrNull()
    }
    DisposableEffect(r) {
        r?.start()
        onDispose { r?.destroy() }
    }
    return r
}

@Composable
fun Render3DView(render: Render3D, modifier: Modifier = Modifier, mirrored: Boolean = false) {
    AndroidView(
        factory = { ctx -> TextureView(ctx).also { render.attach(it) } },
        modifier = modifier.graphicsLayer { scaleX = if (mirrored) -1f else 1f },
    )
}

/** Touch orbit for the viewer and the avatar: drag turns (yaw clamped to ±[maxYawDeg]), pinch zooms. */
class Orbit(private val maxYawDeg: Float = 180f, private val maxPitchDeg: Float = 30f) {
    var yawDeg by mutableFloatStateOf(0f)
    var pitchDeg by mutableFloatStateOf(0f)
    var zoom by mutableFloatStateOf(1f)

    fun drag(dx: Float, dy: Float) {
        yawDeg = if (maxYawDeg >= 180f) (yawDeg + dx * 0.4f) % 360f else (yawDeg + dx * 0.4f).coerceIn(-maxYawDeg, maxYawDeg)
        pitchDeg = (pitchDeg + dy * 0.3f).coerceIn(-maxPitchDeg, maxPitchDeg)
    }

    fun pinch(f: Float) { zoom = (zoom * f).coerceIn(0.6f, 2.5f) }
}

fun Modifier.orbitGestures(orbit: Orbit): Modifier = pointerInput(orbit) {
    detectTransformGestures { _, pan, z, _ ->
        orbit.drag(pan.x, pan.y)
        if (z != 1f) orbit.pinch(z)
    }
}
