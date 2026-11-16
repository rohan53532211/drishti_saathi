package com.drishtisaathi.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.teleconsult.TeleController
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Accessibility pass (docs/DESIGN.md §6): on the key screens, in Hindi and English at font scale 1.0, 1.3 and 1.5,
 * every tappable element has something for TalkBack to read (its text or a content description) and a touch target of
 * at least 48 dp.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi", application = Application::class)
class AccessibilityTest {
    @get:Rule val rule = createComposeRule()

    private val tele by lazy {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val bridge = com.drishtisaathi.bridge.BridgeSettings(app).apply { save(com.drishtisaathi.bridge.BridgeConfig("offline", "")) }
        TeleController(app, bridge)
    }

    private fun label(n: SemanticsNode): String? {
        val c = n.config
        val parts = listOfNotNull(
            c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
            c.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text },
            c.getOrNull(SemanticsProperties.EditableText)?.text,
            c.getOrNull(SemanticsActions.OnClick)?.label,
        )
        return parts.joinToString(" ").trim().ifEmpty { null }
    }

    /** Draws [content] in each language and font scale and checks every clickable node. */
    private fun check(name: String, content: @Composable (Lang) -> Unit) {
        var lang by mutableStateOf(Lang.HI)
        var scale by mutableStateOf(1f)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) { DrishtiTheme(dark = false) { content(lang) } }
        }
        val problems = mutableListOf<String>()
        for (l in listOf(Lang.HI, Lang.EN)) for (s in listOf(1f, 1.3f, 1.5f)) {
            lang = l; scale = s
            rule.mainClock.advanceTimeBy(1_500)
            val density = rule.density.density
            rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach { n ->
                val text = label(n)
                if (text == null) problems += "$name $l ×$s: a tappable element has no label (TalkBack reads nothing)"
                // the element's own size (a list row scrolled out of view is clipped to 0 in root bounds), or the larger
                // touch area Material adds around small controls
                val b = n.touchBoundsInRoot
                val w = maxOf(n.size.width.toFloat(), b.width) / density; val h = maxOf(n.size.height.toFloat(), b.height) / density
                if (w < 47.5f || h < 47.5f) problems += "$name $l ×$s: \"${text ?: "?"}\" is ${w.toInt()}×${h.toInt()} dp"
            }
        }
        assertTrue(problems.distinct().joinToString("\n"), problems.isEmpty())
    }

    private val user = com.drishtisaathi.teleconsult.TeleUser("u1", name = "Sunita Devi", phone = "9876543210", age = 52, gender = "F", language = "HI")
    private val optometrist = com.drishtisaathi.teleconsult.TeleOptometrist("o1", name = "Dr Meera Sharma", qualification = "B.Optom",
        city = "Lucknow", languages = listOf("Hindi", "English"), fee = 0, available = true)

    @Test fun welcome() = check("welcome") { l -> com.drishtisaathi.firstrun.WelcomeScreen(l, {}) {} }
    @Test fun role() = check("role") { l -> com.drishtisaathi.firstrun.RoleScreen(l, {}, {}, {}, {}, null) }
    @Test fun login() = check("login") { l -> com.drishtisaathi.firstrun.LoginScreen(l, {}, tele, {}, {}, {}, {}) }
    @Test fun patientHome() = check("patient home") { l ->
        com.drishtisaathi.home.PatientShell(l, {}, user, tele, null, {}, {}, {}, {}, {}, {}, {})
    }
    @Test fun shopHome() = check("shop home") { l ->
        com.drishtisaathi.home.ShopShell(l, {}, setupDone = false, bridgeOnline = true, queued = 0, sessions = emptyList(), remote = emptyMap(),
            onStart = {}, onBridgeChip = {}, onSetup = {}, onFrames = {}, onDemo = {}, onServer = {}, onDiagnostics = {},
            onOpenSession = {}, onDeleteSession = {}, onSwitchRole = {}, onExit = {})
    }
    @Test fun checklist() = check("checklist") { l -> com.drishtisaathi.guide.ChecklistScreen(l, {}, {}, {}) }
    @Test fun redFlag() = check("red flag") { l ->
        com.drishtisaathi.profile.RedFlagBannerScreen(l, com.drishtisaathi.engine.profile.SafetyGate(
            redFlags = listOf(com.drishtisaathi.engine.profile.RedFlag.SUDDEN_VISION_LOSS), emergency = true), {}, {})
    }
    @Test fun chooseOptometrist() = check("choose optometrist") { l ->
        com.drishtisaathi.teleconsult.ChooseOptometristContent(l, {}, false, emptyList(), listOf(optometrist), null, null, null, false, {}, {}, {}, {})
    }
    @Test fun chat() = check("chat") { l ->
        val k = com.drishtisaathi.teleconsult.TeleConsultation("c1", caseId = "DS-1", status = "IN_PROGRESS", optometrist = optometrist,
            videoUrl = "https://meet.jit.si/x", whatsappUrl = "https://wa.me/91", createdAt = 1_789_000_000_000L)
        val msgs = listOf(com.drishtisaathi.teleconsult.TeleMessage(1, "optometrist", text = "Namaste", createdAt = 1_789_000_000_000L))
        com.drishtisaathi.teleconsult.ConsultationContent(l, {}, k, null, msgs, null, "Hello", {}, {}, {}, {}, {}, {}, {}, now = 1_789_000_100_000L)
    }
}
