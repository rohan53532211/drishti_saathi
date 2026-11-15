package com.drishtisaathi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.drishtisaathi.data.SessionEntity
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S
import com.drishtisaathi.i18n.SHome
import com.drishtisaathi.ui.designsystem.DsBottomSheet
import com.drishtisaathi.ui.designsystem.EmptyState
import com.drishtisaathi.ui.designsystem.PrimaryButton
import com.drishtisaathi.ui.designsystem.SecondaryButton
import com.drishtisaathi.ui.designsystem.Spot

/** Staff-only list of stored sessions (newest first): open or delete each one; delete asks first. */
@Composable
fun HistoryScreen(
    lang: Lang,
    sessions: List<SessionEntity>,
    onLang: (Lang) -> Unit,
    /** Sent to the optometrist: sessionId to status (Waiting / Reviewed). */
    remote: Map<Long, com.drishtisaathi.data.RemoteReviewEntity> = emptyMap(),
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val l = lang
    val c = DS.colors
    var pendingDelete by remember { mutableStateOf<Long?>(null) }

    AppScaffold(l, onLang, title = S.history[l], onBack = onBack) { pad ->
        if (sessions.isEmpty()) Box(Modifier.padding(pad).padding(DS.screen)) {
            EmptyState(Spot.EYE, SHome.customersEmpty[l], SHome.customersEmptyLine[l])
        } else LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(DS.screen),
            verticalArrangement = Arrangement.spacedBy(DS.s3),
        ) {
            items(sessions.sortedByDescending { it.createdAt }, key = { it.id }) { s ->
                com.drishtisaathi.home.SessionRow(l, s, remote[s.id], { onOpen(s.id) }, { pendingDelete = s.id })
            }
        }
    }

    pendingDelete?.let { id ->
        DsBottomSheet(onDismiss = { pendingDelete = null }) {
            Text(SHome.deleteSession[l], style = MaterialTheme.typography.titleLarge, color = c.ink, modifier = Modifier.semantics { heading() })
            Text(S.deleteConfirm[l], style = MaterialTheme.typography.bodyLarge, color = c.ink)
            PrimaryButton(S.delete[l], { pendingDelete = null; onDelete(id) }, container = c.urgent, icon = Icons.Rounded.DeleteOutline)
            SecondaryButton(S.cancel[l], { pendingDelete = null })
        }
    }
}
