package com.drishtisaathi.ui.designsystem

import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.navigation.NavBackStackEntry
import com.drishtisaathi.ui.DS

/**
 * Motion and haptics (docs/DESIGN.md §4). Every animation here snaps to its end when the phone asks for reduced
 * motion: Settings → Accessibility → "Remove animations" sets the animator duration scale to 0.
 */
object Motion {
    /** True when the phone's animator duration scale is 0. Read once per composition. */
    @Composable
    fun reduced(): Boolean {
        if (LocalInspectionMode.current) return false
        val resolver = LocalContext.current.contentResolver
        return remember(resolver) {
            runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
        }
    }

    /** The duration to use: [ms], or 0 under reduced motion. */
    @Composable
    fun ms(ms: Int): Int = if (reduced()) 0 else ms

    // Screen changes: 200 ms fade + a short slide (24 dp at 420 dp/in is about 64 px; the slide uses a fraction of the
    // width so it reads the same on every phone). Navigation passes these to NavHost.
    private const val SLIDE_FRACTION = 12
    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        fadeIn(tween(DS.stepMs)) + slideInHorizontally(tween(DS.stepMs)) { it / SLIDE_FRACTION }
    }
    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        fadeOut(tween(DS.stepMs / 2))
    }
    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        fadeIn(tween(DS.stepMs)) + slideInHorizontally(tween(DS.stepMs)) { -it / SLIDE_FRACTION }
    }
    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        fadeOut(tween(DS.stepMs / 2)) + slideOutHorizontally(tween(DS.stepMs)) { it / SLIDE_FRACTION }
    }
    val none: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = { EnterTransition.None }
    val noneOut: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = { ExitTransition.None }
}

/** Press feedback: scales to 0.97 over 120 ms while pressed (nothing under reduced motion). */
@Composable
fun Modifier.pressScale(interaction: InteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val reduced = Motion.reduced()
    val scale by animateFloatAsState(if (pressed && !reduced) DS.pressScale else 1f, tween(DS.pressMs), label = "press")
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Haptics: a light tick on a selection, a confirm pattern on success, a reject pattern for an urgent warning. */
class Haptics internal constructor(private val view: View) {
    fun tick() { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    fun success() {
        view.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    }
    fun warning() {
        view.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
