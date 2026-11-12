package com.drishtisaathi.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.SCamera
import com.drishtisaathi.ui.BigButton

/**
 * Shows [content] once the CAMERA permission is granted. Before that: explains why (frames stay in memory),
 * asks, and on denial offers [onSkip] ("continue without camera") so the typed flow keeps working, plus
 * "open settings" when Android will no longer show the dialog.
 */
@Composable
fun CameraPermissionGate(lang: Lang, onSkip: () -> Unit, autoRequest: Boolean = false, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        askedOnce = true
    }
    // Re-check when returning from the system settings screen.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) granted = hasCameraPermission(context) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // Power path: show the system dialog straight away instead of waiting for "Allow camera".
    androidx.compose.runtime.LaunchedEffect(autoRequest) {
        if (autoRequest && !granted && !askedOnce) launcher.launch(Manifest.permission.CAMERA)
    }
    if (granted) {
        content()
        return
    }
    val activity = context.findActivity()
    val permanentlyDenied = askedOnce && activity != null &&
        !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(SCamera.permTitle[lang], style = MaterialTheme.typography.headlineMedium)
        Text(if (askedOnce) SCamera.permDenied[lang] else SCamera.permWhy[lang], style = MaterialTheme.typography.bodyLarge)
        if (permanentlyDenied) {
            BigButton(SCamera.permSettings[lang], onClick = { context.openAppSettings() }, selected = true)
        } else {
            BigButton(SCamera.permAllow[lang], onClick = { launcher.launch(Manifest.permission.CAMERA) }, selected = true)
        }
        BigButton(SCamera.skipCamera[lang], onClick = onSkip)
    }
}

internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
