package com.drishtisaathi.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.drishtisaathi.firstrun.LoginScreen
import com.drishtisaathi.firstrun.PatientStartScreen
import com.drishtisaathi.firstrun.ProfileReadyScreen
import com.drishtisaathi.firstrun.RoleScreen
import com.drishtisaathi.firstrun.SignUpScreen
import com.drishtisaathi.firstrun.WelcomeScreen
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.teleconsult.TeleController
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Screenshot tests (docs/DESIGN.md §6): key screens in light / dark × Hindi / English × font scale 1.0 / 1.3, rendered
 * by Robolectric (native graphics) and saved by Roborazzi to docs/screens/. A screen that crashes or throws while
 * drawing in any variant fails the test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi", application = Application::class)
class ScreensTest {
    @get:Rule val rule = createComposeRule()

    private data class Variant(val dark: Boolean, val lang: Lang, val scale: Float) {
        val suffix get() = "${if (dark) "dark" else "light"}_${lang.name.lowercase()}_${(scale * 100).toInt()}"
    }

    private val matrix = listOf(false, true).flatMap { d -> listOf(Lang.HI, Lang.EN).flatMap { l -> listOf(1f, 1.3f).map { Variant(d, l, it) } } }

    private val out = File("../../docs/screens").apply { mkdirs() }

    /** Draws [content] in every variant and saves `<name>_<theme>_<lang>_<scale>.png`. */
    private fun shoot(name: String, variants: List<Variant> = matrix, content: @Composable (Lang, (Lang) -> Unit) -> Unit) {
        var v by mutableStateOf(variants.first())
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, v.scale)) {
                DrishtiTheme(dark = v.dark) { content(v.lang) { } }
            }
        }
        for (variant in variants) {
            v = variant
            rule.mainClock.advanceTimeBy(1_500)
            rule.onRoot().captureRoboImage(File(out, "${name}_${variant.suffix}.png").path)
        }
    }

    private val tele by lazy {
        val app = ApplicationProvider.getApplicationContext<Application>()
        TeleController(app, com.drishtisaathi.bridge.BridgeSettings(app))
    }

    // ---- phase 2: first run and accounts ----
    @Test fun welcome() = shoot("p2_welcome") { l, on -> WelcomeScreen(l, on) {} }
    @Test fun role() = shoot("p2_role") { l, on -> RoleScreen(l, on, {}, {}, {}, null) }
    @Test fun patientStart() = shoot("p2_patient_start") { l, on -> PatientStartScreen(l, on, {}, {}, {}) }
    @Test fun signUp() = shoot("p2_signup_step1") { l, on -> SignUpScreen(l, on, tele, {}, {}) }
    @Test fun ready() = shoot("p2_profile_ready") { l, _ -> ProfileReadyScreen(l, "Sunita Devi", {}, {}) }
    @Test fun login() = shoot("p2_login") { l, on -> LoginScreen(l, on, tele, {}, {}, {}, {}) }
}
