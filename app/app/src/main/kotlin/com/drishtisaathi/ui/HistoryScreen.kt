package com.drishtisaathi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.drishtisaathi.data.SessionEntity
import com.drishtisaathi.engine.ReferralLevel
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Staff-only list of stored sessions: open or delete each one. */
@Composable
fun HistoryScreen(
    lang: Lang,
    sessions: List<SessionEntity>,
    onLang: (Lang) -> Unit,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val l = lang
    var pendingDelete by remember { mutableStateOf<Long?>(null) }
    val fmt = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US) }

    AppScaffold(l, onLang, title = S.history[l], onBack = onBack) { pad ->
        if (sessions.isEmpty()) {
            Column(Modifier.padding(pad).padding(24.dp)) { Text(S.noHistory[l], style = MaterialTheme.typography.bodyLarge) }
        } else {
            LazyColumn(
                Modifier.padding(pad).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(sessions, key = { it.id }) { s ->
                    val level = runCatching { ReferralLevel.valueOf(s.referralLevel) }.getOrDefault(ReferralLevel.NONE)
                    val color = when (level) {
                        ReferralLevel.URGENT -> UrgentRed
                        ReferralLevel.ROUTINE -> RoutineAmber
                        ReferralLevel.NONE -> OkGreen
                    }
                    Card(Modifier.fillMaxWidth().clickable { onOpen(s.id) }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(S.sessionNo.format(s.id.toInt())[l], style = MaterialTheme.typography.titleMedium)
                                Text(fmt.format(Date(s.createdAt)), style = MaterialTheme.typography.bodyMedium)
                                Text(S.referralLevel(level)[l], style = MaterialTheme.typography.bodySmall, color = color)
                                if (s.verifiedAt != null) Text("✓ " + S.verified[l], style = MaterialTheme.typography.bodySmall, color = OkGreen)
                            }
                            TextButton(onClick = { pendingDelete = s.id }) { Text(S.delete[l], color = UrgentRed) }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            text = { Text(S.deleteConfirm[l], style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { TextButton({ pendingDelete = null; onDelete(id) }) { Text(S.delete[l], color = UrgentRed) } },
            dismissButton = { TextButton({ pendingDelete = null }) { Text(S.cancel[l]) } },
        )
    }
}
