package com.drishtisaathi.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
            Thread.sleep(150)                         // let failed (offline) server calls land
            rule.mainClock.advanceTimeBy(500)
            rule.onRoot().captureRoboImage(File(out, "${name}_${variant.suffix}.png").path)
        }
    }

    /** No network in tests: an invalid server address makes every call fail at once ("set the server address first"). */
    private val tele by lazy {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val bridge = com.drishtisaathi.bridge.BridgeSettings(app).apply { save(com.drishtisaathi.bridge.BridgeConfig("offline", "")) }
        TeleController(app, bridge)
    }

    // ---- sample data ----
    private val user = com.drishtisaathi.teleconsult.TeleUser("u1", name = "Sunita Devi", phone = "9876543210", age = 52, gender = "F", language = "HI")
    private fun session(id: Long, daysAgo: Int, level: String = "NONE") = com.drishtisaathi.data.SessionEntity(
        id = id, createdAt = 1_789_000_000_000L - daysAgo * 86_400_000L, lang = "HI", phoneModel = "test",
        answersJson = "{}", staffJson = "{}", resultJson = "{}", referralLevel = level,
    )
    private val profile = com.drishtisaathi.engine.profile.VisionProfile(
        work = com.drishtisaathi.engine.profile.Ring(72, "", true), digital = com.drishtisaathi.engine.profile.Ring(88, "", true),
        mobility = com.drishtisaathi.engine.profile.Ring(45, "", true), leisure = com.drishtisaathi.engine.profile.Ring(60, "", true),
        nightGlareSensitive = true,
    )
    private val optometrist = com.drishtisaathi.teleconsult.TeleOptometrist("o1", name = "Dr Meera Sharma", qualification = "B.Optom",
        city = "Lucknow", languages = listOf("Hindi", "English"), fee = 0, available = true)
    private val consults = listOf(
        com.drishtisaathi.teleconsult.TeleConsultation("c1", caseId = "DS-1042", status = "IN_PROGRESS", createdAt = 1_789_000_000_000L,
            updatedAt = 1_789_003_600_000L, optometrist = optometrist, messageCount = 4),
        com.drishtisaathi.teleconsult.TeleConsultation("c2", caseId = "DS-1038", status = "REQUESTED", priority = "URGENT", createdAt = 1_788_900_000_000L),
        com.drishtisaathi.teleconsult.TeleConsultation("c3", caseId = "DS-0991", status = "COMPLETED", createdAt = 1_787_000_000_000L,
            optometrist = optometrist, hasPrescription = true, messageCount = 9),
    )
    private fun patient(tab: Int, l: Lang, on: (Lang) -> Unit) = @Composable {
        com.drishtisaathi.home.PatientShell(l, on, user, tele, session(7, 2) to profile, {}, {}, {}, {}, {}, {}, {}, startTab = tab)
    }
    private fun shop(tab: Int, l: Lang, on: (Lang) -> Unit, sessions: List<com.drishtisaathi.data.SessionEntity>) = @Composable {
        com.drishtisaathi.home.ShopShell(l, on, setupDone = false, bridgeOnline = true, queued = 0, sessions = sessions, remote = emptyMap(),
            onStart = {}, onBridgeChip = {}, onSetup = {}, onFrames = {}, onDemo = {}, onServer = {}, onDiagnostics = {},
            onOpenSession = {}, onDeleteSession = {}, onSwitchRole = {}, onExit = {}, startTab = tab)
    }

    // ---- phase 3: homes per role ----
    @Test fun patientHome() = shoot("p3_patient_home") { l, on -> patient(0, l, on)() }
    @Test fun patientProfile() = shoot("p3_patient_profile") { l, on -> patient(2, l, on)() }
    @Test fun consultations() = shoot("p3_consultations") { l, _ ->
        Bg { com.drishtisaathi.teleconsult.ConsultationListContent(l, consults, null, {}, {}) }
    }
    @Test fun consultationsEmpty() = shoot("p3_consultations_empty", matrix.take(2)) { l, _ ->
        Bg { com.drishtisaathi.teleconsult.ConsultationListContent(l, emptyList(), null, {}, {}, onEmptyAction = {}, emptyAction = "Start eye test") }
    }
    @Test fun shopHome() = shoot("p3_shop_home") { l, on -> shop(0, l, on, emptyList())() }
    @Test fun shopCustomers() = shoot("p3_shop_customers") { l, on -> shop(1, l, on, listOf(session(12, 0, "URGENT"), session(11, 1), session(10, 3, "ROUTINE")))() }
    @Test fun shopTools() = shoot("p3_shop_tools") { l, on -> shop(2, l, on, emptyList())() }

    /** A plain page: the theme background and the 24 dp screen padding. */
    @Composable private fun Bg(content: @Composable () -> Unit) =
        Box(Modifier.fillMaxSize().background(DS.colors.background).padding(DS.screen)) { content() }

    // ---- phase 2: first run and accounts ----
    @Test fun welcome() = shoot("p2_welcome") { l, on -> WelcomeScreen(l, on) {} }
    @Test fun role() = shoot("p2_role") { l, on -> RoleScreen(l, on, {}, {}, {}, null) }
    @Test fun patientStart() = shoot("p2_patient_start") { l, on -> PatientStartScreen(l, on, {}, {}, {}) }
    @Test fun signUp() = shoot("p2_signup_step1") { l, on -> SignUpScreen(l, on, tele, {}, {}) }
    @Test fun ready() = shoot("p2_profile_ready") { l, _ -> ProfileReadyScreen(l, "Sunita Devi", {}, {}) }
    @Test fun login() = shoot("p2_login") { l, on -> LoginScreen(l, on, tele, {}, {}, {}, {}) }
}
