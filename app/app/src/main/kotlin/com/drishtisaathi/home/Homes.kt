package com.drishtisaathi.home

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Wc
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.drishtisaathi.data.RemoteReviewEntity
import com.drishtisaathi.data.SessionEntity
import com.drishtisaathi.engine.ReferralLevel
import com.drishtisaathi.engine.profile.VisionProfile
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S
import com.drishtisaathi.i18n.SAuth
import com.drishtisaathi.i18n.SHome
import com.drishtisaathi.i18n.SProfile
import com.drishtisaathi.i18n.SShop
import com.drishtisaathi.i18n.STele
import com.drishtisaathi.profile.ringColour
import com.drishtisaathi.teleconsult.ConsultationList
import com.drishtisaathi.teleconsult.ConsultationRow
import com.drishtisaathi.teleconsult.TeleController
import com.drishtisaathi.teleconsult.TeleText
import com.drishtisaathi.teleconsult.TeleUser
import com.drishtisaathi.teleconsult.rememberConsultations
import com.drishtisaathi.ui.DS
import com.drishtisaathi.ui.LangToggle
import com.drishtisaathi.ui.designsystem.Avatar
import com.drishtisaathi.ui.designsystem.Banner
import com.drishtisaathi.ui.designsystem.ConnectionPill
import com.drishtisaathi.ui.designsystem.DsBottomNav
import com.drishtisaathi.ui.designsystem.DsBottomSheet
import com.drishtisaathi.ui.designsystem.DsCard
import com.drishtisaathi.ui.designsystem.DsIconButton
import com.drishtisaathi.ui.designsystem.DsIcons
import com.drishtisaathi.ui.designsystem.EmptyState
import com.drishtisaathi.ui.designsystem.HeroCard
import com.drishtisaathi.ui.designsystem.IconCircle
import com.drishtisaathi.ui.designsystem.Illustration
import com.drishtisaathi.ui.designsystem.ListCard
import com.drishtisaathi.ui.designsystem.ListRow
import com.drishtisaathi.ui.designsystem.NavItem
import com.drishtisaathi.ui.designsystem.PrimaryButton
import com.drishtisaathi.ui.designsystem.SecondaryButton
import com.drishtisaathi.ui.designsystem.SectionTitle
import com.drishtisaathi.ui.designsystem.Spot
import com.drishtisaathi.ui.designsystem.StatusPill
import com.drishtisaathi.ui.designsystem.Tone
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun date(ms: Long): String = SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(ms))

/** A page under a bottom-nav tab: the screen title, the language switch, then the content. */
@Composable
private fun TabPage(title: String, lang: Lang, onLang: (Lang) -> Unit, pad: PaddingValues, content: @Composable () -> Unit) {
    val c = DS.colors
    Column(
        Modifier.padding(pad).fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = DS.screen, vertical = DS.s4),
        verticalArrangement = Arrangement.spacedBy(DS.s4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineSmall, color = c.ink)
            LangToggle(lang, onLang, Modifier.padding(start = 8.dp))
        }
        content()
        Spacer(Modifier.size(DS.s2))
    }
}

/** Bottom navigation shell: back on a tab other than the first goes to the first; back on the first leaves. */
@Composable
private fun Shell(items: List<NavItem>, tab: Int, onTab: (Int) -> Unit, onExit: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    BackHandler { if (tab != 0) onTab(0) else onExit() }
    Scaffold(containerColor = DS.colors.background, bottomBar = { DsBottomNav(items, tab, onTab) }, content = content)
}

/** Four concentric arcs: a small copy of the Vision Profile ring. */
@Composable
fun MiniRings(profile: VisionProfile, modifier: Modifier = Modifier) {
    val c = DS.colors
    Box(modifier.size(84.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.width * 0.075f
            profile.rings.forEachIndexed { i, (id, r) ->
                val inset = stroke / 2 + i * stroke * 1.45f
                val tl = Offset(inset, inset); val sz = Size(size.width - 2 * inset, size.height - 2 * inset)
                val col = ringColour(id, c)
                drawArc(col.copy(alpha = 0.16f), 0f, 360f, false, tl, sz, style = Stroke(stroke))
                if (r.known) drawArc(col, -90f, 360f * r.score / 100f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Text(profile.overall.toString(), style = MaterialTheme.typography.titleMedium, color = c.ink, fontWeight = FontWeight.SemiBold)
    }
}

// ============================================================================================ patient

/**
 * The patient's app: Home (greeting, "Start eye test", last result, my optometrist, tips) · Consultations · Profile
 * (details, language, role switch, log out, delete account with a confirm sheet, about).
 */
@Composable
fun PatientShell(
    lang: Lang, onLang: (Lang) -> Unit, user: TeleUser, tele: TeleController,
    last: Pair<SessionEntity, VisionProfile>?,
    onStartTest: () -> Unit, onOpenResult: (Long) -> Unit, onConsult: (Long) -> Unit, onOpenConsultation: (String) -> Unit,
    onServer: () -> Unit, onSwitchRole: () -> Unit, onExit: () -> Unit,
    startTab: Int = 0,
) {
    var tab by rememberSaveable { mutableIntStateOf(startTab) }
    val items = listOf(
        NavItem(SHome.tabHome[lang], Icons.Rounded.Home),
        NavItem(SHome.tabConsults[lang], Icons.Outlined.ChatBubbleOutline, Icons.Rounded.Chat),
        NavItem(SHome.tabProfile[lang], Icons.Rounded.Person),
    )
    Shell(items, tab, { tab = it }, onExit) { pad ->
        when (tab) {
            0 -> PatientHome(lang, onLang, user, tele, last, pad, onStartTest, onOpenResult, onConsult, onOpenConsultation) { tab = 1 }
            1 -> TabPage(STele.myConsultations[lang], lang, onLang, pad) {
                ConsultationList(lang, tele, onOpenConsultation, onEmptyAction = onStartTest, emptyAction = SHome.startTest[lang])
            }
            else -> PatientProfile(lang, onLang, user, tele, pad, onServer, onSwitchRole)
        }
    }
}

@Composable
private fun PatientHome(
    lang: Lang, onLang: (Lang) -> Unit, user: TeleUser, tele: TeleController, last: Pair<SessionEntity, VisionProfile>?, pad: PaddingValues,
    onStartTest: () -> Unit, onOpenResult: (Long) -> Unit, onConsult: (Long) -> Unit, onOpenConsultation: (String) -> Unit, onAllConsults: () -> Unit,
) {
    val c = DS.colors
    TabPage(STele.hello.format(user.name.substringBefore(' ').ifBlank { user.name })[lang], lang, onLang, pad) {
        HeroCard(onClick = onStartTest) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DS.s4)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(SHome.startTest[lang], style = MaterialTheme.typography.headlineSmall, color = c.onPrimary)
                    Text(SHome.startTestLine[lang], style = MaterialTheme.typography.bodyLarge, color = c.onPrimary)
                }
                Icon(DsIcons.EyeMark, contentDescription = null, tint = c.onPrimary, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.size(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(SProfile.homeHeroCta[lang], style = MaterialTheme.typography.labelLarge, color = c.onPrimary, fontWeight = FontWeight.SemiBold)
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = c.onPrimary, modifier = Modifier.size(18.dp))
            }
        }

        // last result
        SectionTitle(SHome.lastResult[lang])
        if (last == null) DsCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DS.s4)) {
                Illustration(Spot.EYE, size = 64.dp)
                Text(SHome.noResult[lang], Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = c.inkSecondary)
            }
        } else {
            val (session, profile) = last
            DsCard(onClick = { onOpenResult(session.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DS.s4),
                    modifier = Modifier.semantics(mergeDescendants = true) {}) {
                    MiniRings(profile, Modifier.semantics { contentDescription = SHome.visionScore.format(profile.overall)[lang] })
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(SProfile.profileTitle[lang], style = MaterialTheme.typography.titleMedium, color = c.ink)
                        Text(date(session.createdAt), style = MaterialTheme.typography.bodyMedium, color = c.inkSecondary)
                        val level = runCatching { ReferralLevel.valueOf(session.referralLevel) }.getOrDefault(ReferralLevel.NONE)
                        if (level == ReferralLevel.URGENT) StatusPill(S.referralLevel(level)[lang], Tone.URGENT)
                    }
                }
                Text(SHome.seeResult[lang], style = MaterialTheme.typography.labelLarge, color = c.primaryInk, fontWeight = FontWeight.SemiBold)
            }
        }

        // my optometrist: the newest consultation
        SectionTitle(SHome.myOptometrist[lang])
        val (consults, consultsError) = rememberConsultations(tele)
        val latest = consults?.firstOrNull()
        when {
            latest != null -> ConsultationRow(lang, latest) { onOpenConsultation(latest.id) }
            consults == null && consultsError == null -> com.drishtisaathi.ui.designsystem.Skeleton(height = 88.dp)
            else -> DsCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DS.s4)) {
                    IconCircle(Icons.Outlined.ChatBubbleOutline, c.primary)
                    Text(SHome.noOptometrist[lang], Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = c.inkSecondary)
                }
                if (last != null) SecondaryButton(STele.consult[lang], { onConsult(last.first.id) })
            }
        }
        if ((consults?.size ?: 0) > 1) com.drishtisaathi.ui.designsystem.DsTextButton(STele.myConsultations[lang], onAllConsults)

        // tips
        SectionTitle(SHome.tips[lang])
        LazyRow(horizontalArrangement = Arrangement.spacedBy(DS.s3)) {
            items(listOf(Spot.GLASSES to SHome.tipRest, Spot.LIGHT to SHome.tipLight, Spot.PHONE_DISTANCE to SHome.tipDistance), key = { it.first.name }) { (spot, tip) ->
                DsCard(Modifier.width(220.dp)) {
                    Illustration(spot, size = 72.dp)
                    Text(tip[lang], style = MaterialTheme.typography.bodyMedium, color = c.ink)
                }
            }
        }
        Text(S.disclaimerBoth, style = MaterialTheme.typography.bodySmall, color = c.inkSecondary)
    }
}

@Composable
private fun PatientProfile(lang: Lang, onLang: (Lang) -> Unit, user: TeleUser, tele: TeleController, pad: PaddingValues,
                           onServer: () -> Unit, onSwitchRole: () -> Unit) {
    val c = DS.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<com.drishtisaathi.i18n.Txt?>(null) }
    TabPage(SHome.tabProfile[lang], lang, onLang, pad) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Avatar(user.name, size = 88.dp)
            Text(user.name, style = MaterialTheme.typography.titleLarge, color = c.ink)
            user.phone?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.inkSecondary) }
        }
        SectionTitle(SHome.details[lang])
        val gender = when (user.gender) { "F" -> STele.genderF[lang]; "M" -> STele.genderM[lang]; "O" -> STele.genderO[lang]; else -> SHome.notGiven[lang] }
        ListCard(rows = listOf(
            { ListRow(SHome.mobile[lang], subtitle = user.phone ?: SHome.notGiven[lang], icon = Icons.Rounded.Phone) },
            { ListRow(STele.age[lang], subtitle = user.age?.toString() ?: SHome.notGiven[lang], icon = Icons.Rounded.Cake) },
            { ListRow(SHome.gender[lang], subtitle = gender, icon = Icons.Rounded.Wc) },
        ))
        SectionTitle(SHome.appLanguage[lang])
        LangToggle(lang, onLang, long = true)
        SectionTitle(SHome.settings[lang])
        ListCard(rows = listOf(
            { ListRow(SAuth.switchRole[lang], icon = Icons.Rounded.SwapHoriz, onClick = onSwitchRole) },
            { ListRow(STele.serverSettings[lang], icon = Icons.Rounded.CloudSync, accent = c.mobility, onClick = onServer) },
            { ListRow(STele.logout[lang], icon = Icons.AutoMirrored.Rounded.Logout, accent = c.mobility, onClick = { tele.logout() }) },
            { ListRow(STele.deleteAccount[lang], icon = Icons.Rounded.DeleteOutline, accent = c.urgent, titleColor = c.urgentInk, onClick = { confirm = true }) },
        ))
        error?.let { Banner(it[lang], Tone.URGENT) }
        SectionTitle(SHome.about[lang])
        DsCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DS.s3)) {
                IconCircle(Icons.Rounded.Info, c.primary)
                Text(SAuth.appName[lang] + " · " + SHome.version.format(appVersion(context))[lang], style = MaterialTheme.typography.titleSmall, color = c.ink)
            }
            Text(S.disclaimerBoth, style = MaterialTheme.typography.bodyMedium, color = c.ink)
            Text(STele.demoNote[lang], style = MaterialTheme.typography.bodySmall, color = c.inkSecondary)
        }
    }
    if (confirm) DsBottomSheet(onDismiss = { if (!busy) confirm = false }) {
        Text(STele.deleteAccount[lang], style = MaterialTheme.typography.titleLarge, color = c.ink, modifier = Modifier.semantics { heading() })
        Text(STele.deleteConfirm[lang], style = MaterialTheme.typography.bodyLarge, color = c.ink)
        PrimaryButton(SHome.deleteEverything[lang], busy = busy, container = c.urgent, icon = Icons.Rounded.DeleteOutline, onClick = {
            busy = true
            scope.launch {
                tele.deleteAccount()
                    .onSuccess { Toast.makeText(context, STele.deleted[lang], Toast.LENGTH_LONG).show(); confirm = false }
                    .onFailure { error = TeleText.error(it); confirm = false }
                busy = false
            }
        })
        SecondaryButton(S.cancel[lang], { confirm = false }, enabled = !busy)
    }
}

private fun appVersion(context: android.content.Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "–"

// =============================================================================================== shop

/**
 * The optical shop's app: Home ("Start new customer", connection pill, tiles) · Customers (saved checks) · Tools
 * (frames, demo customers; Advanced folds away the server, calibration and staff checks).
 */
@Composable
fun ShopShell(
    lang: Lang, onLang: (Lang) -> Unit,
    setupDone: Boolean, bridgeOnline: Boolean, queued: Int,
    sessions: List<SessionEntity>, remote: Map<Long, RemoteReviewEntity>,
    onStart: () -> Unit, onBridgeChip: () -> Unit, onSetup: () -> Unit, onFrames: () -> Unit, onDemo: () -> Unit,
    onServer: () -> Unit, onDiagnostics: () -> Unit, onOpenSession: (Long) -> Unit, onDeleteSession: (Long) -> Unit,
    onSwitchRole: () -> Unit, onExit: () -> Unit,
    startTab: Int = 0,
) {
    var tab by rememberSaveable { mutableIntStateOf(startTab) }
    val items = listOf(
        NavItem(SHome.tabHome[lang], Icons.Rounded.Home),
        NavItem(SHome.tabCustomers[lang], Icons.Rounded.Groups),
        NavItem(SHome.tabTools[lang], Icons.Rounded.Build),
    )
    Shell(items, tab, { tab = it }, onExit) { pad ->
        when (tab) {
            0 -> ShopHome(lang, onLang, pad, setupDone, bridgeOnline, queued, onStart, onBridgeChip, onSetup, onFrames, { tab = 1 }, { tab = 2 })
            1 -> CustomersTab(lang, onLang, pad, sessions, remote, onOpenSession, onDeleteSession, onStart)
            else -> TabPage(SHome.tabTools[lang], lang, onLang, pad) { ToolsList(lang, setupDone, onFrames, onDemo, onSetup, onServer, onDiagnostics, onSwitchRole) }
        }
    }
}

@Composable
private fun ShopHome(
    lang: Lang, onLang: (Lang) -> Unit, pad: PaddingValues, setupDone: Boolean, bridgeOnline: Boolean, queued: Int,
    onStart: () -> Unit, onBridgeChip: () -> Unit, onSetup: () -> Unit, onFrames: () -> Unit, onCustomers: () -> Unit, onTools: () -> Unit,
) {
    val c = DS.colors
    val P = SProfile
    TabPage(SAuth.appName[lang], lang, onLang, pad) {
        val pill = when {
            bridgeOnline && queued == 0 -> P.bridgeConnected[lang]
            bridgeOnline -> P.bridgeConnected[lang] + " · " + queued
            queued > 0 -> P.bridgeOffline.format(queued)[lang]
            else -> P.bridgeOfflineNone[lang]
        }
        ConnectionPill(com.drishtisaathi.i18n.SBridge.cardTitle[lang] + ": " + pill, bridgeOnline, onBridgeChip)
        if (!setupDone) Banner(SShop.phoneSetup[lang], Tone.WARNING, body = SShop.setupReminder[lang]) {
            com.drishtisaathi.ui.designsystem.DsTextButton(SHome.setupNow[lang], onSetup, color = c.warningInk)
        }
        HeroCard(onClick = onStart) {
            Text(S.startNew[lang], style = MaterialTheme.typography.headlineSmall, color = c.onPrimary)
            Text(P.homeHeroLine[lang], style = MaterialTheme.typography.bodyLarge, color = c.onPrimary)
            Spacer(Modifier.size(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(P.homeHeroCta[lang], style = MaterialTheme.typography.labelLarge, color = c.onPrimary, fontWeight = FontWeight.SemiBold)
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = c.onPrimary, modifier = Modifier.size(18.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(DS.s3)) {
            Tile(S.history[lang], Icons.Rounded.Groups, c.work, onCustomers, Modifier.weight(1f))
            Tile(P.homeFrames[lang], DsIcons.Lens, c.leisure, onFrames, Modifier.weight(1f))
            Tile(SHome.tabTools[lang], Icons.Rounded.Build, c.mobility, onTools, Modifier.weight(1f))
        }
        Text(S.privacyNote[lang], style = MaterialTheme.typography.bodySmall, color = c.inkSecondary)
        Text(S.disclaimerBoth, style = MaterialTheme.typography.bodySmall, color = c.inkSecondary)
    }
}

@Composable
private fun Tile(label: String, icon: ImageVector, accent: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = DS.colors
    DsCard(modifier.heightIn(min = 112.dp), onClick = onClick, padding = PaddingValues(16.dp)) {
        IconCircle(icon, accent, tint = if (accent == c.leisure) c.warningInk else if (accent == c.mobility) c.ink else null)
        Text(label, style = MaterialTheme.typography.labelLarge, color = c.ink, fontWeight = FontWeight.SemiBold)
    }
}

/** Saved checks, newest first: referral pill, demo tag, optometrist status; delete asks first (bottom sheet). */
@Composable
private fun CustomersTab(lang: Lang, onLang: (Lang) -> Unit, pad: PaddingValues, sessions: List<SessionEntity>, remote: Map<Long, RemoteReviewEntity>,
                         onOpen: (Long) -> Unit, onDelete: (Long) -> Unit, onStart: () -> Unit) {
    val c = DS.colors
    var pendingDelete by remember { mutableStateOf<Long?>(null) }
    Column(Modifier.padding(pad).fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(horizontal = DS.screen, vertical = DS.s4), verticalAlignment = Alignment.CenterVertically) {
            Text(SHome.tabCustomers[lang], Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineSmall, color = c.ink)
            LangToggle(lang, onLang, Modifier.padding(start = 8.dp))
        }
        if (sessions.isEmpty()) Box(Modifier.padding(horizontal = DS.screen)) {
            EmptyState(Spot.EYE, SHome.customersEmpty[lang], SHome.customersEmptyLine[lang], cta = S.startNew[lang], onCta = onStart)
        } else LazyColumn(
            contentPadding = PaddingValues(start = DS.screen, end = DS.screen, bottom = DS.s5),
            verticalArrangement = Arrangement.spacedBy(DS.s3),
        ) {
            items(sessions.sortedByDescending { it.createdAt }, key = { it.id }) { s -> SessionRow(lang, s, remote[s.id], { onOpen(s.id) }, { pendingDelete = s.id }) }
        }
    }
    pendingDelete?.let { id ->
        DsBottomSheet(onDismiss = { pendingDelete = null }) {
            Text(SHome.deleteSession[lang], style = MaterialTheme.typography.titleLarge, color = c.ink, modifier = Modifier.semantics { heading() })
            Text(S.deleteConfirm[lang], style = MaterialTheme.typography.bodyLarge, color = c.ink)
            PrimaryButton(S.delete[lang], { pendingDelete = null; onDelete(id) }, container = c.urgent, icon = Icons.Rounded.DeleteOutline)
            SecondaryButton(S.cancel[lang], { pendingDelete = null })
        }
    }
}

@Composable
fun SessionRow(lang: Lang, s: SessionEntity, r: RemoteReviewEntity?, onOpen: () -> Unit, onDelete: () -> Unit) {
    val c = DS.colors
    val level = runCatching { ReferralLevel.valueOf(s.referralLevel) }.getOrDefault(ReferralLevel.NONE)
    val demo = remember(s.id, s.measurementsJson) { runCatching { com.drishtisaathi.data.SessionCodec.measurements(s).demo }.getOrDefault(false) }
    val fmt = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US) }
    DsCard(onClick = onOpen, padding = PaddingValues(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 4.dp), spacing = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconCircle(Icons.Rounded.Visibility, c.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text((if (demo) "DEMO · " else "") + S.sessionNo.format(s.id.toInt())[lang], style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text(fmt.format(Date(s.createdAt)), style = MaterialTheme.typography.bodySmall, color = c.inkSecondary)
            }
            DsIconButton(Icons.Rounded.DeleteOutline, S.delete[lang], onDelete, tint = c.inkSecondary)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(end = 12.dp)) {
            StatusPill(S.referralLevel(level)[lang], when (level) { ReferralLevel.URGENT -> Tone.URGENT; ReferralLevel.ROUTINE -> Tone.WARNING; ReferralLevel.NONE -> Tone.SUCCESS })
            if (s.verifiedAt != null) StatusPill(S.verified[lang], Tone.NEUTRAL)
        }
        r?.let {
            val b = com.drishtisaathi.i18n.SBridge
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(end = 12.dp)) {
                StatusPill((if (it.reviewed) b.historyReviewed else b.historyWaiting).format(it.caseId)[lang], if (it.reviewed) Tone.SUCCESS else Tone.WARNING)
                if (it.needsInPersonExam == true) StatusPill(b.historyInPerson[lang], Tone.URGENT)
            }
        }
    }
}

/** Everyday tools on top; "Advanced" (server, calibration, staff checks, role switch) folded away. */
@Composable
fun ToolsList(lang: Lang, setupDone: Boolean, onFrames: () -> Unit, onDemo: () -> Unit, onSetup: () -> Unit, onServer: () -> Unit,
              onDiagnostics: () -> Unit, onSwitchRole: (() -> Unit)?) {
    val c = DS.colors
    var open by rememberSaveable { mutableStateOf(false) }
    ListCard(rows = listOf(
        { ListRow(com.drishtisaathi.i18n.S3D.frameCatalogue[lang], icon = DsIcons.Lens, accent = c.warning, onClick = onFrames) },
        { ListRow(com.drishtisaathi.i18n.SFlow.demoMode[lang], icon = Icons.Rounded.Groups, onClick = onDemo) },
    ))
    Surface(onClick = { open = !open }, shape = DS.card, color = c.surfaceAlt,
        modifier = Modifier.fillMaxWidth().semantics { stateDescription = if (open) "open" else "closed" }) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconCircle(Icons.Rounded.Tune, c.mobility, tint = c.ink)
            Column(Modifier.weight(1f)) {
                Text(SHome.advanced[lang], style = MaterialTheme.typography.titleSmall, color = c.ink, fontWeight = FontWeight.SemiBold)
                Text(SHome.advancedLine[lang], style = MaterialTheme.typography.bodySmall, color = c.inkSecondary)
            }
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, tint = c.inkSecondary)
        }
    }
    AnimatedVisibility(open) {
        ListCard(rows = listOfNotNull(
            { ListRow(if (setupDone) SShop.phoneSetupDone[lang] else SShop.phoneSetup[lang], icon = Icons.Rounded.PhoneAndroid, accent = c.mobility, onClick = onSetup) },
            { ListRow(com.drishtisaathi.i18n.SBridge.settingsTitle[lang], icon = Icons.Rounded.CloudSync, accent = c.mobility, onClick = onServer) },
            { ListRow(SShop.diagnostics[lang], icon = Icons.Rounded.Science, accent = c.mobility, onClick = onDiagnostics) },
            onSwitchRole?.let { f -> @Composable { ListRow(SAuth.switchRole[lang], icon = Icons.Rounded.SwapHoriz, accent = c.mobility, onClick = f) } },
        ))
    }
}
