package com.drishtisaathi.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.ui.DS

/** One screening result: a status dot (green / amber / red / grey), the label and the result, a note below. */
@Composable
fun ScreenLineRow(line: ScreenLine, lang: Lang) {
    val c = DS.colors
    val dot = when (line.status) {
        ScreenLine.Status.OK -> c.success
        ScreenLine.Status.WATCH -> c.warning
        ScreenLine.Status.URGENT -> c.urgent
        ScreenLine.Status.NOT_DONE -> c.hairline
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(top = 7.dp).size(10.dp).background(dot, CircleShape))
        Column(Modifier.weight(1f)) {
            Text(line.label[lang], style = MaterialTheme.typography.labelMedium, color = c.inkSecondary)
            Text(line.value[lang], style = MaterialTheme.typography.bodyLarge, color = c.ink)
            line.note?.let { Text(it[lang], style = MaterialTheme.typography.bodyMedium, color = c.warningInk) }
        }
    }
}
