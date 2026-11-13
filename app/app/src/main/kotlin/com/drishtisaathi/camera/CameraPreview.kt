package com.drishtisaathi.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.drishtisaathi.engine.config.CameraConfig

fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/**
 * A [MediaPipeFaceTracker] tied to the composition: started while the host is at least STARTED (and
 * [active] is true), stopped when the app goes to the background or the composable leaves. Call only after
 * the camera permission is granted (see [CameraPermissionGate]).
 */
@Composable
fun rememberFaceTracker(
    config: CameraConfig, enablePhoto: Boolean = false, active: Boolean = true, avatarPhoto: Boolean = false, backCamera: Boolean = false,
): MediaPipeFaceTracker {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val tracker = remember(config, enablePhoto, avatarPhoto, backCamera) { MediaPipeFaceTracker(context, config, enablePhoto, avatarPhoto, backCamera) }
    DisposableEffect(tracker, owner, active) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (active) tracker.start()
                Lifecycle.Event.ON_STOP -> tracker.stop()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(observer) // replays ON_START if already started
        onDispose {
            owner.lifecycle.removeObserver(observer)
            tracker.stop()
        }
    }
    return tracker
}

/**
 * Live front-camera preview for [tracker] (PreviewView, FILL_CENTER, mirrored by CameraX for the front
 * camera). Overlays drawn on top in the same box should place landmarks with [PreviewTransform] using the
 * box's size in pixels. Try-on (Track E) and the capture screens host this.
 */
@Composable
fun CameraPreview(tracker: MediaPipeFaceTracker, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // COMPATIBLE (TextureView) composes correctly inside Compose layouts and under overlays.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    DisposableEffect(tracker, view) {
        tracker.setPreviewSurfaceProvider(view.surfaceProvider)
        onDispose { tracker.setPreviewSurfaceProvider(null) }
    }
    AndroidView(factory = { view }, modifier = modifier)
}
