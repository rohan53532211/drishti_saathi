package com.drishtisaathi.teleconsult

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.role
import com.drishtisaathi.i18n.SConsult
import com.drishtisaathi.i18n.fmtD
import com.drishtisaathi.ui.designsystem.Avatar
import com.drishtisaathi.ui.designsystem.Banner
import com.drishtisaathi.ui.designsystem.ChatBubble
import com.drishtisaathi.ui.designsystem.DaySeparator
import com.drishtisaathi.ui.designsystem.DsCard
import com.drishtisaathi.ui.designsystem.DsIconButton
import com.drishtisaathi.ui.designsystem.DsTextButton
import com.drishtisaathi.ui.designsystem.EmptyState
import com.drishtisaathi.ui.designsystem.ErrorState
import com.drishtisaathi.ui.designsystem.IconCircle
import com.drishtisaathi.ui.designsystem.SkeletonList
import com.drishtisaathi.ui.designsystem.Spot
import com.drishtisaathi.ui.designsystem.StatusPill
import com.drishtisaathi.ui.designsystem.Tone
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
 * After a test: "Any available" first and highlighted, then the approved, available optometrists (avatar, name,
 * qualification, city, language chips, fee); the request is sent with the session's report. An urgent report shows
 * the doctor-today lines first.
 */
@Composable
fun ChooseOptometristScreen(
    lang: Lang, onLang: (Lang) -> Unit, tele: TeleController, urgent: Boolean, redFlagReasons: List<String>,
    /** The session's report; null = there is no finished test to send. */
    report: () -> JsonObject?,
    onSent: (consultationId: String) -> Unit, onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var attempt by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf<List<TeleOptometrist>?>(null) }
    var loadError by remember { mutableStateOf<Txt?>(null) }
    var error by remember { mutableStateOf<Txt?>(null) }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }      // null = any available
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        list = null; loadError = null
        tele.call { it.optometrists() }.onSuccess { list = it }.onFailure { loadError = TeleText.error(it); list = emptyList() }
    }
    ChooseOptometristContent(
        lang, onLang, urgent, redFlagReasons, list, loadError, error, picked, busy,
        onPick = { picked = it }, onRetry = { attempt++ }, onBack = onBack,
        onSend = {
            val body = report()
            if (body == null) error = STele.noReport
            else {
                busy = true; error = null
                scope.launch {
                    tele.call { it.requestConsultation(body, picked) }.onSuccess { onSent(it.id) }.onFailure { error = TeleText.error(it); busy = false }
                }
            }
        },
    )
}

/** The choose-optometrist page without the server (screenshot tests draw this). */
@Composable
fun ChooseOptometristContent(
    lang: Lang, onLang: (Lang) -> Unit, urgent: Boolean, redFlagReasons: List<String>,
    list: List<TeleOptometrist>?, loadError: Txt?, error: Txt?, picked: String?, busy: Boolean,
    onPick: (String?) -> Unit, onRetry: () -> Unit, onSend: () -> Unit, onBack: () -> Unit,
) {
    val c = DS.colors
    AppScaffold(lang, onLang, title = STele.choose[lang], onBack = onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(DS.screen),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // the urgent note is always the first thing on the page
                item("top") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (urgent) UrgentTeleNote(lang, redFlagReasons)
                        Text(STele.shared[lang], style = MaterialTheme.typography.bodyMedium, color = c.inkSecondary)
                    }
                }
                item("any") { AnyAvailableRow(lang, picked == null) { onPick(null) } }
                when {
                    loadError != null -> item("error") { ErrorState(loadError[lang], SConsult.tryAgain[lang], onRetry) }
                    list == null -> item("loading") { SkeletonList(SProfile.loading[lang]) }
                    list.isEmpty() -> item("none") {
                        Banner(SConsult.noneAvailableTitle[lang], Tone.INFO, body = SConsult.noneAvailableLine[lang])
                    }
                    else -> items(list, key = { it.id }) { o -> OptometristRow(lang, o, picked == o.id) { onPick(o.id) } }
                }
            }
            Column(
                Modifier.fillMaxWidth().background(c.surface).padding(horizontal = DS.screen, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ErrorLine(lang, error)
                com.drishtisaathi.ui.designsystem.PrimaryButton((if (busy) STele.sending else STele.sendRequest)[lang], onSend, busy = busy)
                Text(STele.payment[lang], style = MaterialTheme.typography.bodySmall, color = c.inkSecondary)
            }
        }
    }
}

/** "Any available": the first, highlighted choice (fastest). */
@Composable
private fun AnyAvailableRow(lang: Lang, selected: Boolean, onClick: () -> Unit) {
    val c = DS.colors
    SelectableRow(selected, onClick) {
        Box(Modifier.size(48.dp).background(c.primary, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Bolt, contentDescription = null, tint = c.onPrimary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(STele.anyAvailable[lang], style = MaterialTheme.typography.titleMedium, color = c.ink)
            Text(STele.anyAvailableLine[lang], style = MaterialTheme.typography.bodyMedium, color = c.inkSecondary)
            StatusPill(SConsult.recommended[lang], Tone.SUCCESS, icon = Icons.Rounded.Bolt)
        }
    }
}

/** One optometrist: avatar, name, qualification · city, language chips, fee. */
@Composable
private fun OptometristRow(lang: Lang, o: TeleOptometrist, selected: Boolean, onClick: () -> Unit) {
    val c = DS.colors
    SelectableRow(selected, onClick) {
        Avatar(o.name)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(o.name, style = MaterialTheme.typography.titleMedium, color = c.ink)
            val line = listOfNotNull(o.qualification, listOfNotNull(o.clinicName, o.city).joinToString(", ").ifBlank { null }).joinToString(" · ")
            if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodyMedium, color = c.inkSecondary)
            if (o.languages.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                o.languages.forEach { l ->
                    Text(l, style = MaterialTheme.typography.labelMedium, color = c.ink,
                        modifier = Modifier.background(c.surfaceAlt, DS.pill).padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
            val fee = o.fee?.takeIf { it > 0 }?.let { STele.fee.format(it)[lang] } ?: SConsult.free[lang]
            Text(listOfNotNull(fee, o.registrationNo?.let { STele.regNo.format(it)[lang] }).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = c.inkSecondary)
        }
    }
}

/** A choice row: surface card, a 2 dp primary border and a check when selected (never colour alone). */
@Composable
private fun SelectableRow(selected: Boolean, onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val c = DS.colors
    Surface(
        onClick = onClick, shape = DS.card, color = if (selected) c.primarySoft else c.surface,
        border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) c.primary else c.hairline),
        modifier = Modifier.fillMaxWidth().heightIn(min = DS.answerMin).semantics(mergeDescendants = true) {
            this.selected = selected; role = androidx.compose.ui.semantics.Role.RadioButton
        },
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            content()
            Icon(
                if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, contentDescription = null,
                tint = if (selected) c.primary else c.hairline, modifier = Modifier.size(26.dp),
            )
        }
    }
}

// ---- my consultations ----------------------------------------------------------------------------

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
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var k by remember { mutableStateOf<TeleConsultation?>(null) }
    var advice by remember { mutableStateOf<TeleAdvice?>(null) }
    var messages by remember { mutableStateOf(emptyList<TeleMessage>()) }
    var error by remember { mutableStateOf<Txt?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var confirmClose by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }

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
    LaunchedEffect(id, attempt) {
        var tick = 0
        while (true) {
            if (tick % 3 == 0) loadCase()       // every 15 s
            loadMessages()                      // every 5 s
            tick++
            delay(5_000)
        }
    }

    ConsultationContent(
        lang, onLang, k, advice, messages, error, draft,
        onDraft = { draft = it.take(2000) },
        onSend = {
            val text = draft.trim()
            if (text.isNotEmpty()) {
                draft = ""
                scope.launch { tele.call { it.sendMessage(id, text) }.onSuccess { loadMessages() }.onFailure { error = TeleText.error(it); draft = text } }
            }
        },
        onOpenLink = { openLink(context, it) },
        onPdf = {
            val caseId = k?.caseId.orEmpty()
            scope.launch {
                val ok = tele.call { it.advicePdf(id) }.map { openPdf(context, it, caseId) }.getOrDefault(false)
                if (!ok) Toast.makeText(context, STele.pdfFailed[lang], Toast.LENGTH_LONG).show()
            }
        },
        onClose = { confirmClose = true }, onRetry = { attempt++ }, onBack = onBack,
        image = { mid -> tele.call { it.image(id, mid) }.getOrNull() },
    )
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

/** Chat items in order, with a day separator before the first message of each day. */
private sealed interface ChatItem {
    data class Day(val key: Long, val label: Txt) : ChatItem
    data class Msg(val m: TeleMessage) : ChatItem
}

private fun dayStart(ms: Long): Long = java.util.Calendar.getInstance().apply {
    timeInMillis = ms; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

private fun dayLabel(day: Long, now: Long): Txt {
    val today = dayStart(now)
    return when {
        day == today -> SConsult.today
        today - day in 1..(26 * 3_600_000L) -> SConsult.yesterday
        else -> SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(day)).let { Txt(it, it) }
    }
}

private fun chatItems(messages: List<TeleMessage>, now: Long): List<ChatItem> = buildList {
    var last = Long.MIN_VALUE
    for (m in messages) {
        val d = dayStart(m.createdAt)
        if (d != last) { add(ChatItem.Day(d, dayLabel(d, now))); last = d }
        add(ChatItem.Msg(m))
    }
}

private fun clock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))

/** The consultation page without the server (screenshot tests draw this). */
@Composable
fun ConsultationContent(
    lang: Lang, onLang: (Lang) -> Unit, cur: TeleConsultation?, advice: TeleAdvice?, messages: List<TeleMessage>, error: Txt?,
    draft: String, onDraft: (String) -> Unit, onSend: () -> Unit, onOpenLink: (String?) -> Unit, onPdf: () -> Unit,
    onClose: () -> Unit, onRetry: () -> Unit, onBack: () -> Unit,
    image: suspend (Long) -> ByteArray? = { null }, now: Long = System.currentTimeMillis(),
) {
    val c = DS.colors
    val list = rememberLazyListState()
    val items = remember(messages, now) { chatItems(messages, now) }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) list.animateScrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1) }
    val video = cur?.takeIf { !it.closed }?.videoUrl?.takeIf { TeleText.safeLink(it) != null }
    val whatsapp = cur?.takeIf { !it.closed }?.whatsappUrl?.takeIf { TeleText.safeLink(it) != null }

    AppScaffold(
        lang, onLang, title = cur?.let { STele.caseNo.format(it.caseId)[lang] } ?: STele.myConsultations[lang], onBack = onBack,
        actions = {
            if (video != null) DsIconButton(Icons.Rounded.Videocam, STele.joinVideo[lang], { onOpenLink(video) }, tint = c.primary)
            if (whatsapp != null) DsIconButton(Icons.AutoMirrored.Rounded.Chat, STele.whatsapp[lang], { onOpenLink(whatsapp) }, tint = c.success)
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            // a call that has started stays pinned above the chat
            if (video != null) JoinCallCard(lang) { onOpenLink(video) }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(DS.screen),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when {
                    cur == null && error != null -> item("error") { ErrorState(error[lang], SConsult.tryAgain[lang], onRetry) }
                    cur == null -> item("loading") { SkeletonList(SProfile.loading[lang]) }
                    else -> {
                        if (error != null) item("errorLine") { ErrorLine(lang, error) }
                        item("who") {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (cur.urgent) UrgentTeleNote(lang, cur.redFlagReasons)
                                SoftCard { CaseHeader(lang, cur) }
                            }
                        }
                        advice?.let { a -> item("advice") { AdviceCard(lang, a, onPdf) } }
                        item("chatTitle") {
                            Text(STele.chat[lang], style = MaterialTheme.typography.titleMedium, color = c.ink,
                                modifier = Modifier.padding(top = 8.dp).semantics { heading() })
                        }
                        when {
                            cur.status == "REQUESTED" -> item("waiting") {
                                EmptyState(Spot.CHAT, SConsult.waitingTitle[lang], STele.chatAfterAccept[lang])
                            }
                            messages.isEmpty() -> item("empty") {
                                Text(STele.noMessages[lang], Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, color = c.inkSecondary,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                        }
                        items(items, key = { when (it) { is ChatItem.Day -> "d${it.key}"; is ChatItem.Msg -> "m${it.m.id}" } }) { it ->
                            when (it) {
                                is ChatItem.Day -> DaySeparator(it.label[lang])
                                is ChatItem.Msg -> MessageBubble(lang, it.m, image) { onOpenLink(it.m.text) }
                            }
                        }
                        if (!cur.closed) item("close") {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                DsTextButton((if (cur.status == "REQUESTED") STele.cancelRequest else STele.closeConsultation)[lang], onClose, color = c.inkSecondary)
                            }
                        }
                    }
                }
            }
            if (cur != null && cur.canChat) ChatInput(lang, draft, onDraft, onSend)
        }
    }
}

/** Who has the case: avatar, name, qualification, registration, the status pill and the fee line. */
@Composable
private fun CaseHeader(lang: Lang, k: TeleConsultation) {
    val c = DS.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val o = k.optometrist
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Avatar(o?.name)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(o?.name ?: STele.anyAvailable[lang], style = MaterialTheme.typography.titleMedium, color = c.ink)
                if (o != null) {
                    val line = listOfNotNull(o.qualification, o.city).joinToString(" · ")
                    if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodySmall, color = c.inkSecondary)
                    o.registrationNo?.let { Text(STele.regNo.format(it)[lang], style = MaterialTheme.typography.bodySmall, color = c.inkSecondary) }
                }
            }
        }
        StatusPill(STele.status(k.status)[lang], statusTone(k.status))
        Text(listOfNotNull(k.fee?.let { STele.fee.format(it)[lang] }, STele.payment[lang]).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = c.inkSecondary)
    }
}

/** "Your optometrist started a video call" + Join, pinned above the chat while a call link is live. */
@Composable
private fun JoinCallCard(lang: Lang, onJoin: () -> Unit) {
    val c = DS.colors
    Row(
        Modifier.fillMaxWidth().background(c.primarySoft).padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.Videocam, contentDescription = null, tint = c.primaryInk)
        Text(SConsult.videoStarted[lang], Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = c.ink)
        Surface(onClick = onJoin, shape = DS.pill, color = c.primary, modifier = Modifier.heightIn(min = DS.minTouch)) {
            Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Text(SConsult.videoCall[lang], color = c.onPrimary, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** The sticky input: a rounded field and a round send button (TalkBack: "Send"). */
@Composable
private fun ChatInput(lang: Lang, draft: String, onDraft: (String) -> Unit, onSend: () -> Unit) {
    val c = DS.colors
    Row(
        Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            draft, onDraft, Modifier.weight(1f), placeholder = { Text(STele.messageHint[lang]) }, maxLines = 4,
            shape = RoundedCornerShape(24.dp), textStyle = MaterialTheme.typography.bodyLarge,
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = c.primary, unfocusedBorderColor = c.hairline,
                focusedContainerColor = c.background, unfocusedContainerColor = c.background,
            ),
        )
        DsIconButton(
            Icons.AutoMirrored.Rounded.Send, STele.send[lang], onSend, modifier = Modifier.padding(bottom = 4.dp),
            tint = if (draft.isBlank()) c.inkSecondary else c.onPrimary, container = if (draft.isBlank()) c.surfaceAlt else c.primary,
        )
    }
}

/**
 * The advice, laid out like an optician's slip: the R / L table (SPH, CYL, AXIS), ADD and PD, lens advice, referral,
 * follow-up date and notes, then who wrote it (name, qualification, registration no.) and "Download PDF".
 */
@Composable
fun AdviceCard(lang: Lang, a: TeleAdvice, onPdf: () -> Unit) {
    val c = DS.colors
    DsCard(spacing = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconCircle(Icons.Rounded.Description, c.primary)
            Column(Modifier.weight(1f)) {
                Text(SConsult.adviceTitle[lang], style = MaterialTheme.typography.titleLarge, color = c.ink, modifier = Modifier.semantics { heading() })
                if (a.createdAt > 0) Text(SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(a.createdAt)),
                    style = MaterialTheme.typography.labelMedium, color = c.inkSecondary)
            }
        }
        if (a.right.sph != null || a.right.cyl != null || a.left.sph != null || a.left.cyl != null) {
            Column(Modifier.fillMaxWidth().background(c.surfaceAlt, DS.button).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RxRow(listOf(SConsult.colEye[lang], SConsult.sph[lang], SConsult.cyl[lang], SConsult.axis[lang]), header = true)
                RxRow(listOf(SConsult.right[lang]) + rxCells(a.right))
                RxRow(listOf(SConsult.left[lang]) + rxCells(a.left))
            }
        }
        val facts = listOfNotNull(
            a.add?.let { SConsult.add[lang] to "${fmtD(it)} D" },
            a.pdMm?.let { SConsult.pd[lang] to String.format(Locale.US, "%.1f mm", it) },
            a.lensAdvice.takeIf { it.isNotEmpty() }?.let { SConsult.lensAdvice[lang] to it.joinToString(", ") },
            a.referralAdvice?.takeIf { it.isNotBlank() }?.let { SConsult.referral[lang] to it },
            a.followUpDate?.takeIf { it.isNotBlank() }?.let { SConsult.followUp[lang] to it },
            a.notes?.takeIf { it.isNotBlank() }?.let { SConsult.notes[lang] to it },
        )
        facts.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(k, Modifier.weight(0.42f), style = MaterialTheme.typography.bodyMedium, color = c.inkSecondary)
                Text(v, Modifier.weight(0.58f), style = MaterialTheme.typography.bodyLarge, color = c.ink, fontWeight = FontWeight.SemiBold)
            }
        }
        if (a.needsInPersonExam) Banner(STele.inPerson[lang], Tone.WARNING)
        androidx.compose.material3.HorizontalDivider(color = c.hairline)
        if (a.optometristName.isNotBlank()) Column(Modifier.semantics(mergeDescendants = true) {}) {
            Text(a.optometristName, style = MaterialTheme.typography.titleMedium, color = c.ink)
            val line = listOfNotNull(a.qualification, a.clinic, a.registrationNo.takeIf { it.isNotBlank() }?.let { STele.regNo.format(it)[lang] }).joinToString(" · ")
            if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodySmall, color = c.inkSecondary)
        }
        Text(STele.confirmTrial[lang], style = MaterialTheme.typography.bodySmall, color = c.inkSecondary)
        com.drishtisaathi.ui.designsystem.SecondaryButton(SConsult.downloadPdf[lang], onPdf, icon = Icons.Rounded.Download)
    }
}

private fun rxCells(e: TeleEye): List<String> = listOf(
    e.sph?.let { fmtD(it) } ?: "—",
    e.cyl?.takeIf { it != 0.0 }?.let { fmtD(it) } ?: "—",
    e.axis?.takeIf { e.cyl != null && e.cyl != 0.0 }?.let { "$it°" } ?: "—",
)

@Composable
private fun RxRow(cells: List<String>, header: Boolean = false) {
    val c = DS.colors
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        cells.forEachIndexed { i, s ->
            Text(
                s, Modifier.weight(if (i == 0) 1.3f else 1f),
                style = if (header) MaterialTheme.typography.labelMedium else if (i == 0) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
                color = if (header || i == 0) c.inkSecondary else c.ink, fontWeight = if (header || i == 0) FontWeight.Normal else FontWeight.SemiBold,
                textAlign = if (i == 0) androidx.compose.ui.text.style.TextAlign.Start else androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}

@Composable
private fun MessageBubble(lang: Lang, m: TeleMessage, image: suspend (Long) -> ByteArray?, onOpenVideo: () -> Unit) {
    val c = DS.colors
    if (m.senderRole == "system") {
        Text(m.text.orEmpty(), Modifier.fillMaxWidth().padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall, color = c.inkSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        return
    }
    val mine = m.senderRole == "patient"
    ChatBubble(mine, clock(m.createdAt), who = if (mine) null else STele.optometrist[lang]) { ink ->
        if (m.kind == "VIDEO") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Videocam, contentDescription = null, tint = ink)
                Text(STele.videoMessage[lang], color = ink, style = MaterialTheme.typography.bodyLarge)
            }
            DsTextButton(STele.joinVideo[lang], onOpenVideo, color = if (mine) c.onPrimary else c.primary)
        } else m.text?.let { Text(it, color = ink, style = MaterialTheme.typography.bodyLarge) }
        if (m.hasImage) {
            val bmp by produceState<android.graphics.Bitmap?>(null, m.id) {
                value = image(m.id)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
            bmp?.let { Image(it.asImageBitmap(), contentDescription = STele.picture[lang], modifier = Modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(12.dp))) }
                ?: Text(STele.picture[lang], color = ink, style = MaterialTheme.typography.bodySmall)
        }
    }
}
