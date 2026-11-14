package com.drishtisaathi.teleconsult

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S
import com.drishtisaathi.i18n.SProfile
import com.drishtisaathi.i18n.STele
import com.drishtisaathi.i18n.Txt
import com.drishtisaathi.profile.AnswerCard
import com.drishtisaathi.profile.PrimaryButton
import com.drishtisaathi.profile.SoftCard
import com.drishtisaathi.ui.AppScaffold
import com.drishtisaathi.ui.BigButton
import com.drishtisaathi.ui.DS
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun time(ms: Long): String = SimpleDateFormat("dd MMM, HH:mm", Locale.US).format(Date(ms))

/** Opens a call or WhatsApp link the server made (Jitsi Meet or wa.me only) in the matching app or the browser. */
private fun openLink(context: Context, url: String?) {
    val safe = TeleText.safeLink(url) ?: return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safe)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun ErrorLine(lang: Lang, error: Txt?) {
    if (error != null) Text(
        error[lang], color = DS.colors.urgentInk, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().background(DS.colors.urgentSoft, DS.button).padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** "See an eye doctor today" first, then: a teleconsult never replaces an urgent visit. */
@Composable
fun UrgentTeleNote(lang: Lang, reasons: List<String> = emptyList()) {
    Column(
        Modifier.fillMaxWidth().background(DS.colors.urgent, DS.card).padding(20.dp).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(SProfile.seeDoctorToday[lang], color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        reasons.forEach { Text("•  $it", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.bodyMedium) }
        Text(STele.neverReplaces[lang], color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The card on the result screen: "Consult an optometrist"; an urgent report shows the doctor-today lines first. */
@Composable
fun ConsultCard(lang: Lang, urgent: Boolean, onConsult: () -> Unit) {
    val c = DS.colors
    SoftCard {
        Text(STele.consult[lang], style = MaterialTheme.typography.titleLarge, color = c.ink)
        if (urgent) {
            Text(SProfile.seeDoctorToday[lang], color = c.urgentInk, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(STele.neverReplaces[lang], color = c.urgentInk, style = MaterialTheme.typography.bodyMedium)
        }
        Text(STele.consultLine[lang], style = MaterialTheme.typography.bodyMedium, color = c.inkSecondary)
        PrimaryButton(STele.consult[lang], onClick = onConsult)
    }
}

/** The "Patient mode" card on Home. */
@Composable
fun PatientModeCard(lang: Lang, userName: String?, onClick: () -> Unit) {
    val c = DS.colors
    Surface(onClick = onClick, shape = DS.card, color = c.primarySoft, modifier = Modifier.fillMaxWidth().heightIn(min = DS.answerMin)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(40.dp).background(c.primary, CircleShape), contentAlignment = Alignment.Center) {
                Text((userName?.trim()?.firstOrNull()?.uppercase() ?: "+"), color = c.onPrimary, style = MaterialTheme.typography.titleMedium)
            }
            Column(Modifier.weight(1f)) {
                Text(if (userName != null) STele.hello.format(userName)[lang] else STele.patientMode[lang], style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text(STele.patientModeLine[lang], style = MaterialTheme.typography.bodyMedium, color = c.inkSecondary)
            }
        }
    }
}

// ---- account -------------------------------------------------------------------------------------

/**
 * Patient mode: register or log in (mobile number + password; the consent box is required to register), then the
 * profile with "Take the eye test", "My consultations", log out and "Delete my account and data".
 */
@Composable
fun PatientScreen(
    lang: Lang, onLang: (Lang) -> Unit, tele: TeleController,
    onStartTest: () -> Unit, onConsultations: () -> Unit, onServer: () -> Unit, onBack: () -> Unit,
    /** Called right after a login or registration (the result screen sends the patient back to its "Consult" step). */
    onSignedIn: () -> Unit = {},
) {
    val user by tele.user.collectAsState()
    val c = DS.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    AppScaffold(lang, onLang, title = STele.patientMode[lang], onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(DS.screen),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val u = user
            if (u == null) {
                var registering by rememberSaveable { mutableStateOf(false) }
                var name by rememberSaveable { mutableStateOf("") }
                var phone by rememberSaveable { mutableStateOf("") }
                var password by remember { mutableStateOf("") }          // never saved with the screen state
                var age by rememberSaveable { mutableStateOf("") }
                var gender by rememberSaveable { mutableStateOf<String?>(null) }
                var consent by rememberSaveable { mutableStateOf(false) }
                var busy by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<Txt?>(null) }

                Text((if (registering) STele.register else STele.login)[lang], style = MaterialTheme.typography.headlineMedium, color = c.ink,
                    modifier = Modifier.semantics { heading() })
                if (registering) OutlinedTextField(name, { name = it.take(80) }, label = { Text(STele.name[lang]) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(phone, { phone = it.filter { ch -> ch.isDigit() || ch == '+' }.take(13) }, label = { Text(STele.phone[lang]) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it.take(128) }, label = { Text(STele.password[lang]) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth())
                if (registering) {
                    OutlinedTextField(age, { age = it.filter(Char::isDigit).take(3) }, label = { Text(STele.age[lang]) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    Text(STele.gender[lang], style = MaterialTheme.typography.labelMedium, color = c.inkSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("F" to STele.genderF, "M" to STele.genderM, "O" to STele.genderO).forEach { (code, label) ->
                            AnswerCard(label[lang], selected = gender == code, modifier = Modifier.weight(1f)) { gender = if (gender == code) null else code }
                        }
                    }
                    Row(Modifier.fillMaxWidth().background(c.surface, DS.button).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = consent, onCheckedChange = { consent = it })
                        Text(STele.consent[lang], style = MaterialTheme.typography.bodyLarge, color = c.ink)
                    }
                }
                ErrorLine(lang, error)
                PrimaryButton((if (registering) STele.register else STele.login)[lang], enabled = !busy) {
                    val problem = if (registering) TeleText.registrationProblem(name, phone, password, age, consent) else TeleText.loginProblem(phone, password)
                    if (problem != null) { error = problem; return@PrimaryButton }
                    busy = true; error = null
                    scope.launch {
                        val digits = TeleText.phoneDigits(phone)!!
                        val r = if (registering) tele.register(name, digits, password, age.trim().toInt(), gender, lang.name) else tele.login(digits, password)
                        busy = false
                        r.onSuccess { password = ""; onSignedIn() }.onFailure { error = TeleText.error(it, login = true) }
                    }
                }
                TextButton(onClick = { registering = !registering; error = null }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text((if (registering) STele.haveAccount else STele.newHere)[lang], color = c.primary)
                }
                Text(STele.demoNote[lang], style = MaterialTheme.typography.bodySmall, color = c.inkSecondary)
                TextButton(onClick = onServer, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(STele.serverSettings[lang], color = c.inkSecondary) }
            } else {
                var confirmDelete by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<Txt?>(null) }
                SoftCard(hero = true) {
                    Text(STele.hello.format(u.name)[lang], style = MaterialTheme.typography.headlineSmall, color = c.ink)
                    Text(listOfNotNull(u.phone, u.age?.toString(), u.language).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = c.inkSecondary)
                    Text("✓ " + STele.consent[lang], style = MaterialTheme.typography.bodySmall, color = c.successInk)
                }
                PrimaryButton(STele.takeTest[lang], onClick = onStartTest)
                BigButton(STele.myConsultations[lang], onConsultations)
                ErrorLine(lang, error)
                TextButton(onClick = { tele.logout() }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(STele.logout[lang], color = c.inkSecondary) }
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(STele.deleteAccount[lang], color = c.urgentInk) }
                Text(STele.demoNote[lang], style = MaterialTheme.typography.bodySmall, color = c.inkSecondary)
                if (confirmDelete) AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text(STele.deleteAccount[lang]) },
                    text = { Text(STele.deleteConfirm[lang], style = MaterialTheme.typography.bodyLarge) },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmDelete = false
                            scope.launch {
                                tele.deleteAccount()
                                    .onSuccess { Toast.makeText(context, STele.deleted[lang], Toast.LENGTH_LONG).show() }
                                    .onFailure { error = TeleText.error(it) }
                            }
                        }) { Text(S.delete[lang], color = c.urgentInk) }
                    },
                    dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(S.cancel[lang]) } },
                )
            }
        }
    }
}

// ---- asking for a consultation -------------------------------------------------------------------

/**
 * After a test: the approved, available optometrists (name, qualification, languages, city, fee) or "Any available",
 * then the request is sent with the session's report. An urgent report shows the doctor-today lines first.
 */
@Composable
fun ChooseOptometristScreen(
    lang: Lang, onLang: (Lang) -> Unit, tele: TeleController, urgent: Boolean, redFlagReasons: List<String>,
    /** The session's report; null = there is no finished test to send. */
    report: () -> JsonObject?,
    onSent: (consultationId: String) -> Unit, onBack: () -> Unit,
) {
    val c = DS.colors
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<TeleOptometrist>?>(null) }
    var error by remember { mutableStateOf<Txt?>(null) }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }      // null = any available
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { tele.call { it.optometrists() }.onSuccess { list = it }.onFailure { error = TeleText.error(it); list = emptyList() } }

    AppScaffold(lang, onLang, title = STele.choose[lang], onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(DS.screen),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (urgent) UrgentTeleNote(lang, redFlagReasons)
            Text(STele.shared[lang], style = MaterialTheme.typography.bodyMedium, color = c.inkSecondary)
            OptometristChoice(lang, STele.anyAvailable[lang], STele.anyAvailableLine[lang], null, picked == null) { picked = null }
            val shown = list
            if (shown == null) Text(SProfile.loading[lang], color = c.inkSecondary)
            else if (shown.isEmpty() && error == null) Text(STele.noneAvailable[lang], style = MaterialTheme.typography.bodyMedium, color = c.warningInk)
            shown.orEmpty().forEach { o ->
                val fee = STele.fee.format(o.fee ?: 0)[lang] + " · " + STele.noPayment[lang]
                OptometristChoice(lang, o.name, TeleText.optometristLine(o), listOfNotNull(o.registrationNo?.let { STele.regNo.format(it)[lang] }, fee).joinToString(" · "),
                    picked == o.id) { picked = o.id }
            }
            ErrorLine(lang, error)
            PrimaryButton((if (busy) STele.sending else STele.sendRequest)[lang], enabled = !busy) {
                val body = report()
                if (body == null) { error = STele.noReport; return@PrimaryButton }
                busy = true; error = null
                scope.launch {
                    tele.call { it.requestConsultation(body, picked) }.onSuccess { onSent(it.id) }.onFailure { error = TeleText.error(it); busy = false }
                }
            }
            Text(STele.payment[lang], style = MaterialTheme.typography.bodySmall, color = c.inkSecondary)
        }
    }
}

@Composable
private fun OptometristChoice(lang: Lang, title: String, line: String, extra: String?, selected: Boolean, onClick: () -> Unit) {
    val c = DS.colors
    Surface(
        onClick = onClick, shape = DS.card, color = if (selected) c.primarySoft else c.surface,
        border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) c.primary else c.hairline),
        modifier = Modifier.fillMaxWidth().heightIn(min = DS.answerMin).semantics { this.selected = selected },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.ink)
            if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodyMedium, color = c.inkSecondary)
            extra?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.inkSecondary) }
        }
    }
}

// ---- my consultations ----------------------------------------------------------------------------

@Composable
private fun StatusPill(lang: Lang, k: TeleConsultation) {
    val c = DS.colors
    val (bg, fg) = when (k.status) {
        "COMPLETED" -> c.successSoft to c.successInk
        "CLOSED" -> c.background to c.inkSecondary
        "REQUESTED" -> c.warningSoft to c.warningInk
        else -> c.primarySoft to c.primary
    }
    Text(STele.status(k.status)[lang], color = fg, style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.background(bg, DS.pill).padding(horizontal = 12.dp, vertical = 6.dp))
}

/** "My consultations": each with its status; the list is fetched again every 15 seconds while the screen is open. */
@Composable
fun ConsultationsScreen(lang: Lang, onLang: (Lang) -> Unit, tele: TeleController, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val c = DS.colors
    var list by remember { mutableStateOf<List<TeleConsultation>?>(null) }
    var error by remember { mutableStateOf<Txt?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            tele.call { it.consultations() }.onSuccess { list = it; error = null }.onFailure { error = TeleText.error(it) }
            delay(15_000)
        }
    }
    AppScaffold(lang, onLang, title = STele.myConsultations[lang], onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(DS.screen),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ErrorLine(lang, error)
            val shown = list
            if (shown == null && error == null) Text(SProfile.loading[lang], color = c.inkSecondary)
            if (shown != null && shown.isEmpty()) SoftCard { Text(STele.none[lang], style = MaterialTheme.typography.bodyLarge, color = c.inkSecondary) }
            shown.orEmpty().forEach { k ->
                Surface(onClick = { onOpen(k.id) }, shape = DS.card, color = c.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (k.urgent) c.urgent else c.hairline), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(STele.caseNo.format(k.caseId)[lang], Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = c.ink)
                            if (k.urgent) Text(STele.urgentTag[lang], color = c.urgentInk, style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.background(c.urgentSoft, DS.pill).padding(horizontal = 10.dp, vertical = 4.dp))
                        }
                        StatusPill(lang, k)
                        Text((k.optometrist?.let { STele.withOptometrist.format(it.name)[lang] + " · " } ?: "") + time(k.createdAt),
                            style = MaterialTheme.typography.bodySmall, color = c.inkSecondary)
                    }
                }
            }
            Text(STele.autoRefresh[lang], style = MaterialTheme.typography.bodySmall, color = c.inkSecondary)
        }
    }
}

// ---- one consultation ----------------------------------------------------------------------------

/** Saves the advice PDF next to the app's other PDFs (cache, shared only through the FileProvider) and opens it. */
private fun openPdf(context: Context, bytes: ByteArray, caseId: String): Boolean = runCatching {
    val file = File(com.drishtisaathi.pdf.PdfExporter.dir(context), "drishti_advice_$caseId.pdf")
    file.writeBytes(bytes)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
}.isSuccess

/**
 * One consultation: status, the urgent lines first when the report is urgent, the optometrist, "Join video call",
 * WhatsApp (only if the optometrist allowed it), the advice with its PDF, and the chat. Everything is fetched again
 * every 15 seconds while the screen is open (the chat every 5).
 */
@Composable
fun ConsultationScreen(lang: Lang, onLang: (Lang) -> Unit, tele: TeleController, id: String, onBack: () -> Unit) {
    val c = DS.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var k by remember { mutableStateOf<TeleConsultation?>(null) }
    var advice by remember { mutableStateOf<TeleAdvice?>(null) }
    var messages by remember { mutableStateOf(emptyList<TeleMessage>()) }
    var error by remember { mutableStateOf<Txt?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var confirmClose by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    suspend fun loadCase() {
        tele.call { it.consultation(id) }.onSuccess { got ->
            k = got; error = null
            if (got.hasPrescription) tele.call { it.advice(id) }.onSuccess { advice = it }
        }.onFailure { error = TeleText.error(it) }
    }
    suspend fun loadMessages() {
        val cur = k ?: return
        if (cur.status == "REQUESTED") return
        tele.call { it.messages(id, messages.lastOrNull()?.id ?: 0) }.onSuccess { more ->
            if (more.isNotEmpty()) {
                messages = messages + more
                // a new line from the optometrist can change the status, the call link or the advice
                if (more.any { it.kind != "TEXT" || it.senderRole != "patient" }) loadCase()
            }
        }
    }
    LaunchedEffect(id) {
        var tick = 0
        while (true) {
            if (tick % 3 == 0) loadCase()       // every 15 s
            loadMessages()                      // every 5 s
            tick++
            delay(5_000)
        }
    }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) scroll.animateScrollTo(scroll.maxValue) }

    val cur = k
    AppScaffold(lang, onLang, title = cur?.let { STele.caseNo.format(it.caseId)[lang] } ?: STele.myConsultations[lang], onBack = onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(DS.screen),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ErrorLine(lang, error)
                if (cur == null) { if (error == null) Text(SProfile.loading[lang], color = c.inkSecondary) }
                else {
                    if (cur.urgent) UrgentTeleNote(lang, cur.redFlagReasons)
                    SoftCard {
                        StatusPill(lang, cur)
                        val o = cur.optometrist
                        if (o != null) {
                            Text(o.name, style = MaterialTheme.typography.titleLarge, color = c.ink)
                            Text(TeleText.optometristLine(o), style = MaterialTheme.typography.bodyMedium, color = c.inkSecondary)
                            o.registrationNo?.let { Text(STele.regNo.format(it)[lang], style = MaterialTheme.typography.bodySmall, color = c.inkSecondary) }
                        }
                        Text(listOfNotNull(cur.fee?.let { STele.fee.format(it)[lang] }, STele.payment[lang]).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = c.inkSecondary)
                        if (!cur.closed && TeleText.safeLink(cur.videoUrl) != null) PrimaryButton(STele.joinVideo[lang]) { openLink(context, cur.videoUrl) }
                        if (!cur.closed && TeleText.safeLink(cur.whatsappUrl) != null) BigButton(STele.whatsapp[lang], { openLink(context, cur.whatsappUrl) })
                    }
                    advice?.let { a ->
                        SoftCard(hero = true) {
                            Text(STele.advice[lang], style = MaterialTheme.typography.titleLarge, color = c.ink)
                            TeleText.adviceLines(a).forEach { Text(it[lang], style = MaterialTheme.typography.bodyLarge, color = c.ink) }
                            Text(STele.confirmTrial[lang], style = MaterialTheme.typography.bodySmall, color = c.inkSecondary)
                            PrimaryButton(STele.openPdf[lang]) {
                                scope.launch {
                                    val ok = tele.call { it.advicePdf(id) }.map { openPdf(context, it, cur.caseId) }.getOrDefault(false)
                                    if (!ok) Toast.makeText(context, STele.pdfFailed[lang], Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                    Text(STele.chat[lang], style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.semantics { heading() })
                    if (cur.status == "REQUESTED") Text(STele.chatAfterAccept[lang], style = MaterialTheme.typography.bodyMedium, color = c.inkSecondary)
                    else if (messages.isEmpty()) Text(STele.noMessages[lang], style = MaterialTheme.typography.bodyMedium, color = c.inkSecondary)
                    messages.forEach { m -> MessageBubble(lang, tele, id, m) { openLink(context, m.text) } }
                    if (!cur.closed) TextButton(onClick = { confirmClose = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text((if (cur.status == "REQUESTED") STele.cancelRequest else STele.closeConsultation)[lang], color = c.inkSecondary)
                    }
                }
            }
            if (cur != null && cur.canChat) Row(
                Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(draft, { draft = it.take(2000) }, Modifier.weight(1f), placeholder = { Text(STele.messageHint[lang]) }, maxLines = 4)
                Surface(
                    onClick = {
                        val text = draft.trim()
                        if (text.isNotEmpty()) {
                            draft = ""
                            scope.launch { tele.call { it.sendMessage(id, text) }.onSuccess { loadMessages() }.onFailure { error = TeleText.error(it); draft = text } }
                        }
                    },
                    shape = DS.button, color = c.primary, modifier = Modifier.heightIn(min = DS.minTouch),
                ) { Box(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), contentAlignment = Alignment.Center) { Text(STele.send[lang], color = c.onPrimary, style = MaterialTheme.typography.labelLarge) } }
            }
        }
    }
    if (confirmClose) AlertDialog(
        onDismissRequest = { confirmClose = false },
        text = { Text(STele.closeConfirm[lang], style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            TextButton(onClick = {
                confirmClose = false
                scope.launch { tele.call { it.close(id) }.onSuccess { k = it }.onFailure { error = TeleText.error(it) } }
            }) { Text(STele.yes[lang]) }
        },
        dismissButton = { TextButton(onClick = { confirmClose = false }) { Text(STele.no[lang]) } },
    )
}

@Composable
private fun MessageBubble(lang: Lang, tele: TeleController, consultationId: String, m: TeleMessage, onOpenVideo: () -> Unit) {
    val c = DS.colors
    val mine = m.senderRole == "patient"
    if (m.senderRole == "system") {
        Text(m.text.orEmpty(), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = c.inkSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        return
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 300.dp).background(if (mine) c.primary else c.surface, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val ink = if (mine) c.onPrimary else c.ink
            Text((if (mine) STele.you else STele.optometrist)[lang] + " · " + time(m.createdAt), style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = 0.75f))
            if (m.kind == "VIDEO") {
                Text(STele.videoMessage[lang], color = ink, style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = onOpenVideo) { Text(STele.joinVideo[lang], color = if (mine) c.onPrimary else c.primary) }
            } else m.text?.let { Text(it, color = ink, style = MaterialTheme.typography.bodyLarge) }
            if (m.hasImage) {
                val bmp by produceState<android.graphics.Bitmap?>(null, m.id) {
                    value = tele.call { it.image(consultationId, m.id) }.map { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
                }
                bmp?.let { Image(it.asImageBitmap(), contentDescription = STele.picture[lang], modifier = Modifier.widthIn(max = 260.dp)) }
                    ?: Text(STele.picture[lang], color = ink, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
