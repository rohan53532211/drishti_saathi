package com.drishtisaathi.firstrun

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.SAuth
import com.drishtisaathi.i18n.STele
import com.drishtisaathi.i18n.Txt
import com.drishtisaathi.teleconsult.TeleController
import com.drishtisaathi.teleconsult.TeleText
import com.drishtisaathi.ui.AppScaffold
import com.drishtisaathi.ui.DS
import com.drishtisaathi.ui.LangToggle
import com.drishtisaathi.ui.designsystem.Banner
import com.drishtisaathi.ui.designsystem.ChoiceCard
import com.drishtisaathi.ui.designsystem.DsCard
import com.drishtisaathi.ui.designsystem.DsIcons
import com.drishtisaathi.ui.designsystem.DsTextButton
import com.drishtisaathi.ui.designsystem.DsTextField
import com.drishtisaathi.ui.designsystem.IconCircle
import com.drishtisaathi.ui.designsystem.Illustration
import com.drishtisaathi.ui.designsystem.PrimaryButton
import com.drishtisaathi.ui.designsystem.ProgressDots
import com.drishtisaathi.ui.designsystem.SecondaryButton
import com.drishtisaathi.ui.designsystem.Spot
import com.drishtisaathi.ui.designsystem.SuccessCheck
import com.drishtisaathi.ui.designsystem.Tone
import com.drishtisaathi.ui.designsystem.rememberHaptics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Who uses this phone (docs/DESIGN.md §2). Optometrists use the web portal, so they are not a role on the phone. */
enum class AppRole { PATIENT, SHOP }

/** Remembers the role chosen on first run; "Change who uses this phone" clears it. */
class RoleStore(context: Context) {
    private val prefs = context.getSharedPreferences("app_role", Context.MODE_PRIVATE)
    private val _role = MutableStateFlow(prefs.getString("role", null)?.let { r -> AppRole.entries.firstOrNull { it.name == r } })
    val role: StateFlow<AppRole?> = _role.asStateFlow()
    fun set(role: AppRole?) {
        prefs.edit().apply { if (role == null) remove("role") else putString("role", role.name) }.apply()
        _role.value = role
    }
}

/** The optometrist portal on the hosted server. */
const val PORTAL_URL = com.drishtisaathi.bridge.BridgeSettings.DEFAULT_URL + "/portal"

fun openPortal(context: Context, lang: Lang) {
    val ok = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PORTAL_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    Toast.makeText(context, if (ok) SAuth.portalOpening[lang] else SAuth.noBrowser.format(PORTAL_URL)[lang], Toast.LENGTH_LONG).show()
}

/** A plain scrolling page with the 24 dp screen padding. */
@Composable
private fun Page(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) = Column(
    modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(DS.screen),
    verticalArrangement = Arrangement.spacedBy(DS.s4),
    content = content,
)

@Composable
private fun Heading(text: String, line: String? = null) {
    val c = DS.colors
    Text(text, style = MaterialTheme.typography.headlineSmall, color = c.ink, modifier = Modifier.semantics { heading() })
    if (line != null) Text(line, style = MaterialTheme.typography.bodyLarge, color = c.inkSecondary)
}

/** The eye mark in a brand circle. */
@Composable
fun BrandMark(size: androidx.compose.ui.unit.Dp = 96.dp) {
    val c = DS.colors
    Box(Modifier.size(size).background(c.primary, CircleShape), contentAlignment = Alignment.Center) {
        Icon(DsIcons.EyeMark, contentDescription = null, tint = c.onPrimary, modifier = Modifier.size(size * 0.56f))
    }
}

// ---- welcome ----------------------------------------------------------------------------------------

/** Logo, name, the tagline in both languages, the language switch at the top, one "Get started". */
@Composable
fun WelcomeScreen(lang: Lang, onLang: (Lang) -> Unit, onStart: () -> Unit) {
    val c = DS.colors
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding().navigationBarsPadding().padding(DS.screen)) {
        LangToggle(lang, onLang, Modifier.align(Alignment.End))
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(DS.s3)) {
            BrandMark()
            Spacer(Modifier.size(DS.s2))
            Text(SAuth.appName[lang], style = MaterialTheme.typography.displaySmall, color = c.ink, textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() })
            Text(SAuth.tagline.hi, style = MaterialTheme.typography.titleLarge, color = c.ink, textAlign = TextAlign.Center)
            Text(SAuth.tagline.en, style = MaterialTheme.typography.bodyLarge, color = c.inkSecondary, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.weight(1.2f))
        PrimaryButton(SAuth.getStarted[lang], onStart)
    }
}

// ---- role choice ------------------------------------------------------------------------------------

/** Three large cards: patient, optical shop, optometrist (opens the web portal). */
@Composable
fun RoleScreen(lang: Lang, onLang: (Lang) -> Unit, onPatient: () -> Unit, onShop: () -> Unit, onOptometrist: () -> Unit, onBack: (() -> Unit)?) {
    val c = DS.colors
    AppScaffold(lang, onLang, title = SAuth.appName[lang], onBack = onBack) { pad ->
        Page(Modifier.padding(pad)) {
            Heading(SAuth.whoTitle[lang])
            RoleCard(Icons.Rounded.Person, c.primary, SAuth.rolePatient[lang], SAuth.rolePatientLine[lang], onPatient)
            RoleCard(Icons.Rounded.Storefront, c.leisure, SAuth.roleShop[lang], SAuth.roleShopLine[lang], onShop)
            RoleCard(Icons.Rounded.MedicalServices, c.success, SAuth.roleOptometrist[lang], SAuth.roleOptometristLine[lang], onOptometrist)
        }
    }
}

@Composable
private fun RoleCard(icon: androidx.compose.ui.graphics.vector.ImageVector, accent: androidx.compose.ui.graphics.Color, title: String, line: String, onClick: () -> Unit) {
    val c = DS.colors
    val haptics = rememberHaptics()
    DsCard(onClick = { haptics.tick(); onClick() }, modifier = Modifier.heightIn(min = 96.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DS.s4)) {
            IconCircle(icon, accent, size = 56.dp, tint = if (accent == c.leisure) c.warningInk else null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = c.ink)
                Text(line, style = MaterialTheme.typography.bodyMedium, color = c.inkSecondary)
            }
        }
    }
}

// ---- patient: create account or log in --------------------------------------------------------------

@Composable
fun PatientStartScreen(lang: Lang, onLang: (Lang) -> Unit, onCreate: () -> Unit, onLogin: () -> Unit, onBack: (() -> Unit)?) {
    AppScaffold(lang, onLang, title = SAuth.rolePatient[lang], onBack = onBack) { pad ->
        Page(Modifier.padding(pad)) {
            Illustration(Spot.EYE, Modifier.align(Alignment.CenterHorizontally), size = 180.dp)
            Heading(SAuth.patientTitle[lang], SAuth.patientLine[lang])
            Spacer(Modifier.size(DS.s2))
            PrimaryButton(SAuth.createAccount[lang], onCreate)
            SecondaryButton(SAuth.haveAccount[lang], onLogin)
        }
    }
}

// ---- sign-up wizard ---------------------------------------------------------------------------------

/**
 * One step per screen with progress dots: (1) name + mobile, (2) age + gender (optional), (3) password + consent with
 * an expandable "What we share". The same checks as before ([TeleText.registrationProblem]); the account is created
 * on the server at the end.
 */
@Composable
fun SignUpScreen(lang: Lang, onLang: (Lang) -> Unit, tele: TeleController, onCreated: () -> Unit, onBack: () -> Unit) {
    val c = DS.colors
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var age by rememberSaveable { mutableStateOf("") }
    var gender by rememberSaveable { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }              // never saved with the screen state
    var consent by rememberSaveable { mutableStateOf(false) }
    var share by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var fieldError by remember { mutableStateOf<Txt?>(null) }
    var serverError by remember { mutableStateOf<Txt?>(null) }
    val back = { if (step > 0) { step -= 1; fieldError = null; serverError = null } else onBack() }
    BackHandler(onBack = back)

    val titles = listOf(SAuth.step1, SAuth.step2, SAuth.step3)
    AppScaffold(lang, onLang, title = SAuth.createAccount[lang], onBack = back) { pad ->
        Page(Modifier.padding(pad)) {
            ProgressDots(3, step, SAuth.stepOf.format(step + 1, 3)[lang])
            Heading(titles[step][lang])
            when (step) {
                0 -> {
                    DsTextField(name, { name = it.take(80); fieldError = null }, SAuth.nameLabel[lang],
                        error = fieldError?.takeIf { it == STele.errName }?.get(lang))
                    DsTextField(phone, { phone = it.filter { ch -> ch.isDigit() || ch == '+' }.take(13); fieldError = null }, SAuth.phoneLabel[lang],
                        helper = SAuth.phoneHelper[lang], error = fieldError?.takeIf { it == STele.errPhone }?.get(lang), keyboardType = KeyboardType.Phone)
                }
                1 -> {
                    DsTextField(age, { age = it.filter(Char::isDigit).take(3); fieldError = null }, SAuth.ageLabel[lang],
                        helper = SAuth.ageHelper[lang], error = fieldError?.get(lang), keyboardType = KeyboardType.Number)
                    Text(SAuth.genderLabel[lang], style = MaterialTheme.typography.labelLarge, color = c.ink, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(DS.s2)) {
                        listOf("F" to STele.genderF, "M" to STele.genderM, "O" to STele.genderO).forEach { (code, label) ->
                            ChoiceCard(label[lang], gender == code, { gender = if (gender == code) null else code }, Modifier.weight(1f))
                        }
                    }
                }
                else -> {
                    DsTextField(password, { password = it.take(128); fieldError = null }, SAuth.passwordLabel[lang],
                        helper = SAuth.passwordHelper[lang], error = fieldError?.takeIf { it == STele.errPassword }?.get(lang),
                        password = true, showLabel = SAuth.show[lang], hideLabel = SAuth.hide[lang])
                    ConsentBox(lang, consent, { consent = it; fieldError = null }, error = fieldError?.takeIf { it == STele.consentNeeded }?.get(lang))
                    ShareDetails(lang, share) { share = !share }
                }
            }
            serverError?.let { Banner(it[lang], Tone.URGENT) }
            Spacer(Modifier.size(DS.s2))
            PrimaryButton((if (step < 2) SAuth.next else if (busy) SAuth.creating else SAuth.createAccount)[lang], busy = busy, onClick = {
                val problem = when (step) {
                    0 -> when { name.isBlank() -> STele.errName; TeleText.phoneDigits(phone) == null -> STele.errPhone; else -> null }
                    1 -> if ((age.trim().toIntOrNull() ?: 0) !in 1..120) STele.errAge else null
                    else -> TeleText.registrationProblem(name, phone, password, age, consent)
                }
                if (problem != null) { fieldError = problem; return@PrimaryButton }
                fieldError = null
                if (step < 2) { step += 1; return@PrimaryButton }
                busy = true; serverError = null
                scope.launch {
                    val r = tele.register(name, TeleText.phoneDigits(phone)!!, password, age.trim().toInt(), gender, lang.name)
                    busy = false
                    r.onSuccess { password = ""; onCreated() }.onFailure { serverError = TeleText.error(it, login = true) }
                }
            })
        }
    }
}

/** The consent tick, the whole row is the touch target. */
@Composable
private fun ConsentBox(lang: Lang, checked: Boolean, onChange: (Boolean) -> Unit, error: String?) {
    val c = DS.colors
    Surface(
        onClick = { onChange(!checked) }, shape = DS.card, color = c.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (error != null) c.urgent else if (checked) c.primary else c.hairline),
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            role = Role.Checkbox; toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
        },
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked, onCheckedChange = null, modifier = Modifier.padding(12.dp),
                colors = CheckboxDefaults.colors(checkedColor = c.primary, uncheckedColor = c.inkSecondary, checkmarkColor = c.onPrimary))
            Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(SAuth.consentTitle[lang], style = MaterialTheme.typography.labelLarge, color = c.inkSecondary)
                Text(STele.consent[lang], style = MaterialTheme.typography.bodyLarge, color = c.ink)
                if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = c.urgentInk)
            }
        }
    }
}

/** "What we share": three plain lines, folded away until tapped. */
@Composable
private fun ShareDetails(lang: Lang, open: Boolean, onToggle: () -> Unit) {
    val c = DS.colors
    Column(Modifier.fillMaxWidth()) {
        Surface(onClick = onToggle, shape = DS.button, color = c.background,
            modifier = Modifier.fillMaxWidth().heightIn(min = DS.minTouch).semantics { stateDescription = if (open) "open" else "closed" }) {
            Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(SAuth.whatWeShare[lang], Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = c.primaryInk)
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, tint = c.primaryInk)
            }
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(SAuth.share1, SAuth.share2, SAuth.share3).forEach {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.padding(top = 9.dp).size(6.dp).background(c.primary, CircleShape))
                        Text(it[lang], style = MaterialTheme.typography.bodyMedium, color = c.ink)
                    }
                }
            }
        }
    }
}

// ---- profile ready ----------------------------------------------------------------------------------

@Composable
fun ProfileReadyScreen(lang: Lang, name: String?, onStartTest: () -> Unit, onLater: () -> Unit) {
    val c = DS.colors
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding().navigationBarsPadding().padding(DS.screen),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        SuccessCheck()
        Spacer(Modifier.size(DS.s5))
        if (!name.isNullOrBlank()) Text(STele.hello.format(name)[lang], style = MaterialTheme.typography.titleMedium, color = c.inkSecondary,
            textAlign = TextAlign.Center)
        Text(SAuth.ready[lang], style = MaterialTheme.typography.headlineSmall, color = c.ink, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() })
        Text(SAuth.readyLine[lang], style = MaterialTheme.typography.bodyLarge, color = c.inkSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.weight(1.2f))
        PrimaryButton(SAuth.startFirstTest[lang], onStartTest)
        DsTextButton(SAuth.later[lang], onLater, color = c.inkSecondary)
    }
}

// ---- login ------------------------------------------------------------------------------------------

/** Mobile + password (show / hide), clear errors, "Forgot password?" points to the shop or optometrist (no OTP yet). */
@Composable
fun LoginScreen(lang: Lang, onLang: (Lang) -> Unit, tele: TeleController, onSignedIn: () -> Unit, onCreate: () -> Unit, onServer: () -> Unit, onBack: () -> Unit) {
    val c = DS.colors
    val scope = rememberCoroutineScope()
    var phone by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var fieldError by remember { mutableStateOf<Txt?>(null) }
    var serverError by remember { mutableStateOf<Txt?>(null) }
    AppScaffold(lang, onLang, title = STele.login[lang], onBack = onBack) { pad ->
        Page(Modifier.padding(pad)) {
            Heading(SAuth.welcomeBack[lang])
            DsTextField(phone, { phone = it.filter { ch -> ch.isDigit() || ch == '+' }.take(13); fieldError = null }, SAuth.phoneLabel[lang],
                error = fieldError?.takeIf { it == STele.errPhone }?.get(lang), keyboardType = KeyboardType.Phone)
            DsTextField(password, { password = it.take(128); fieldError = null }, SAuth.passwordLabel[lang],
                error = fieldError?.takeIf { it == STele.errPassword }?.get(lang), password = true, showLabel = SAuth.show[lang], hideLabel = SAuth.hide[lang])
            serverError?.let { Banner(it[lang], Tone.URGENT) }
            PrimaryButton((if (busy) SAuth.loggingIn else STele.login)[lang], busy = busy, onClick = {
                val problem = TeleText.loginProblem(phone, password)
                if (problem != null) { fieldError = problem; return@PrimaryButton }
                busy = true; fieldError = null; serverError = null
                scope.launch {
                    val r = tele.login(TeleText.phoneDigits(phone)!!, password)
                    busy = false
                    r.onSuccess { password = ""; onSignedIn() }.onFailure { serverError = TeleText.error(it, login = true) }
                }
            })
            Text(SAuth.forgot[lang], style = MaterialTheme.typography.bodyMedium, color = c.inkSecondary)
            DsTextButton(SAuth.noAccount[lang], onCreate, Modifier.align(Alignment.CenterHorizontally))
            DsTextButton(STele.serverSettings[lang], onServer, Modifier.align(Alignment.CenterHorizontally), color = c.inkSecondary)
        }
    }
}
