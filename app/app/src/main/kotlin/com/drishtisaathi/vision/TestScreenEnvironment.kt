package com.drishtisaathi.vision

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext

private fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * While in composition: window brightness forced to maximum and the screen kept on (M0). Both are restored on
 * dispose (the keep-on flag only if it was not already set by someone else).
 */
@Composable
fun MaxBrightnessKeepScreenOn() {
    val activity = LocalContext.current.activity()
    DisposableEffect(activity) {
        val window = activity?.window
        if (window == null) {
            onDispose { }
        } else {
            val previousBrightness = window.attributes.screenBrightness
            val hadKeepOn = (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
            window.attributes = window.attributes.also { it.screenBrightness = 1f }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose {
                window.attributes = window.attributes.also { it.screenBrightness = previousBrightness }
                if (!hadKeepOn) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

/**
 * Tumbling E on a 5 × 5 grid: three bars and the spine, stroke = gap = height / 5 (Snellen construction).
 * [heightPx] is the true letter height from [Sizing.letterHeightPx]; [dir] is where the bars point.
 */
fun DrawScope.drawTumblingE(center: Offset, heightPx: Float, dir: EDirection, color: Color = Color.Black) {
    val u = heightPx / 5f
    val left = center.x - heightPx / 2f
    val top = center.y - heightPx / 2f
    rotate(dir.rotationDeg, pivot = center) {
        drawRect(color, Offset(left, top), Size(u, heightPx)) // spine
        for (row in intArrayOf(0, 2, 4)) drawRect(color, Offset(left, top + row * u), Size(heightPx, u))
    }
}

/** "#RRGGBB" from rules.json → Compose colour. */
fun rgbColor(hex: String): Color = Color(0xFF000000 or hex.removePrefix("#").toLong(16))
