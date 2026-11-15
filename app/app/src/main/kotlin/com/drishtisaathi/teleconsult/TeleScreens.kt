package com.drishtisaathi.teleconsult

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.material.icons.rounded.Warning
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
    val fg = DS.on(DS.colors.urgent)          // white on the light theme's red, dark ink on the dark theme's
    Column(
        Modifier.fillMaxWidth().background(DS.colors.urgent, DS.card).padding(20.dp).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val lines = TeleText.urgentLines(reasons)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Warning, contentDescription = null, tint = fg)
            Text(lines.first()[lang], color = fg, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        lines.drop(1).dropLast(1).forEach { Text("•  ${it[lang]}", color = fg, style = MaterialTheme.typography.bodyMedium) }
        Text(lines.last()[lang], color = fg, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The card on the result screen: "Consult an optometrist"; an urgent report shows the doctor-today lines first. */
@Composable
fun ConsultCard(lang: Lang, urgent: Boolean, onConsult: () -> Unit) {
    val c = DS.colors
    SoftCard {
        Text(STele.consult[lang], style = MaterialTheme.typography.titleLarge, color = c.ink)
        if (urgent) {
            val lines = TeleText.urgentLines()
            Text(lines.first()[lang], color = c.urgentInk, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(lines.last()[lang], color = c.urgentInk, style = MaterialTheme.typography.bodyMedium)
        }
        Text(STele.consultLine[lang], style = MaterialTheme.typography.bodyMedium, color = c.inkSecondary)
        PrimaryButton(STele.consult[lang], onClick = onConsult)
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
        else -> c.primarySoft to c.primaryInk
    }
    Text(STele.status(k.status)[lang], color = fg, style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.background(bg, DS.pill).padding(horizontal = 12.dp, vertical = 6.dp))
}

/** Waiting / In progress / Completed: the status as a pill with an icon and a word (never colour alone). */
fun statusTone(status: String): com.drishtisaathi.ui.designsystem.Tone = when (status) {
    "COMPLETED" -> com.drishtisaathi.ui.designsystem.Tone.SUCCESS
    "CLOSED" -> com.drishtisaathi.ui.designsystem.Tone.NEUTRAL
    "REQUESTED" -> com.drishtisaathi.ui.designsystem.Tone.WARNING
    else -> com.drishtisaathi.ui.designsystem.Tone.INFO
}

/** The patient's consultations, newest first; fetched again every 15 seconds while shown. */
@Composable
fun rememberConsultations(tele: TeleController): Pair<List<TeleConsultation>?, Txt?> {
    var list by remember { mutableStateOf<List<TeleConsultation>?>(null) }
    var error by remember { mutableStateOf<Txt?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            tele.call { it.consultations() }.onSuccess { r -> list = r.sortedByDescending { it.updatedAt.coerceAtLeast(it.createdAt) }; error = null }
                .onFailure { error = TeleText.error(it) }
            delay(15_000)
        }
    }
    return list to error
}

/**
 * "My consultations" (UI phase 3/5): rows with the optometrist's avatar, name, case number and time, a status pill and
 * an URGENT pill; a skeleton while loading, a friendly error with retry, an illustrated empty state with one action.
 */
@Composable
fun ConsultationList(lang: Lang, tele: TeleController, onOpen: (String) -> Unit, modifier: Modifier = Modifier,
                     onEmptyAction: (() -> Unit)? = null, emptyAction: String? = null) {
    var attempt by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.key(attempt) {
        val (list, error) = rememberConsultations(tele)
        ConsultationListContent(lang, list, error, { attempt++ }, onOpen, modifier, onEmptyAction, emptyAction)
    }
}

/** The list itself: [list] null = still loading (or failed, with [error]). */
@Composable
fun ConsultationListContent(lang: Lang, list: List<TeleConsultation>?, error: Txt?, onRetry: () -> Unit, onOpen: (String) -> Unit,
                            modifier: Modifier = Modifier, onEmptyAction: (() -> Unit)? = null, emptyAction: String? = null) {
    val c = DS.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            list == null && error != null -> com.drishtisaathi.ui.designsystem.ErrorState(error[lang], com.drishtisaathi.i18n.SHome.tryAgain[lang], onRetry)
            list == null -> com.drishtisaathi.ui.designsystem.SkeletonList(com.drishtisaathi.i18n.SHome.loading[lang])
            list.isEmpty() -> com.drishtisaathi.ui.designsystem.EmptyState(
                com.drishtisaathi.ui.designsystem.Spot.CHAT, com.drishtisaathi.i18n.SHome.consultsEmpty[lang],
                com.drishtisaathi.i18n.SHome.consultsEmptyLine[lang], cta = emptyAction, onCta = onEmptyAction)
            else -> {
                if (error != null) com.drishtisaathi.ui.designsystem.Banner(error[lang], com.drishtisaathi.ui.designsystem.Tone.WARNING)
                list.forEach { k -> androidx.compose.runtime.key(k.id) { ConsultationRow(lang, k) { onOpen(k.id) } } }
                Text(STele.autoRefresh[lang], style = MaterialTheme.typography.bodySmall, color = c.inkSecondary)
            }
        }
    }
}

@Composable
fun ConsultationRow(lang: Lang, k: TeleConsultation, onClick: () -> Unit) {
    val c = DS.colors
    com.drishtisaathi.ui.designsystem.DsCard(onClick = onClick, container = if (k.urgent) c.urgentSoft else null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            com.drishtisaathi.ui.designsystem.Avatar(k.optometrist?.name)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(k.optometrist?.name ?: com.drishtisaathi.i18n.SHome.waitingOptometrist[lang], style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text(listOfNotNull(STele.caseNo.format(k.caseId)[lang], time(maxOf(k.updatedAt, k.createdAt)),
                    k.messageCount.takeIf { it > 0 }?.let { com.drishtisaathi.i18n.SHome.messages.format(it)[lang] }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = c.inkSecondary)
            }
        }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (k.urgent) com.drishtisaathi.ui.designsystem.StatusPill(STele.urgentTag[lang], com.drishtisaathi.ui.designsystem.Tone.URGENT)
            com.drishtisaathi.ui.designsystem.StatusPill(STele.status(k.status)[lang], statusTone(k.status))
        }
    }
}

/** "My consultations" as its own screen (from the result screen). */
@Composable
fun ConsultationsScreen(lang: Lang, onLang: (Lang) -> Unit, tele: TeleController, onOpen: (String) -> Unit, onBack: () -> Unit) {
    AppScaffold(lang, onLang, title = STele.myConsultations[lang], onBack = onBack) { pad ->
        ConsultationList(lang, tele, onOpen, Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(DS.screen))
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
