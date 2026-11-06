package com.drishtisaathi.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    lang: Lang,
    onLang: (Lang) -> Unit,
    title: String = S.appName[lang],
    onBack: (() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    if (onBack != null) TextButton(onClick = onBack) { Text("‹ " + S.back[lang]) }
                },
                actions = { LangToggle(lang, onLang) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Teal, titleContentColor = Color.White,
                    navigationIconContentColor = Color.White, actionIconContentColor = Color.White),
            )
        },
        content = content,
    )
}

@Composable
fun LangToggle(lang: Lang, onLang: (Lang) -> Unit) {
    Row(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(Lang.HI to "हिंदी", Lang.EN to "EN").forEach { (l, label) ->
            val selected = l == lang
            OutlinedButton(
                onClick = { onLang(l) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                border = BorderStroke(1.dp, Color.White),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (selected) Color.White else Color.Transparent,
                    contentColor = if (selected) Teal else Color.White,
                ),
            ) { Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** Large touch target (≥ 64 dp) for the customer-facing interview. */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    container: Color? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    if (selected || container != null) {
        Button(
            onClick = onClick, enabled = enabled, shape = shape,
            modifier = modifier.fillMaxWidth().heightIn(min = 64.dp),
            colors = if (container != null) ButtonDefaults.buttonColors(containerColor = container) else ButtonDefaults.buttonColors(),
        ) { Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
    } else {
        OutlinedButton(
            onClick = onClick, enabled = enabled, shape = shape,
            modifier = modifier.fillMaxWidth().heightIn(min = 64.dp),
            border = BorderStroke(2.dp, Teal),
        ) { Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
    }
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, container: Color = Color.White, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
fun Disclaimer(modifier: Modifier = Modifier) {
    Text(
        S.disclaimerBoth,
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.secondary,
    )
}

@Composable
fun CenteredBox(content: @Composable () -> Unit) =
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }

/** Addition 2: coloured label chip for a ranked frame. */
@Composable
fun MatchChip(label: com.drishtisaathi.engine.MatchLabel, text: String) {
    val (fg, bg) = when (label) {
        com.drishtisaathi.engine.MatchLabel.HIGHLY_RECOMMENDED -> OkGreen to OkBg
        com.drishtisaathi.engine.MatchLabel.MODERATE -> Teal to TealLight
        com.drishtisaathi.engine.MatchLabel.LOWER -> RoutineAmber to RoutineBg
    }
    Text(
        text,
        color = fg,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .padding(vertical = 2.dp)
            .background(bg, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** Addition 2: "+" / "−" bullets generated from the score components. */
@Composable
fun ReasonBullets(rf: com.drishtisaathi.engine.RankedFrame, l: Lang) {
    Fmt.reasons(rf, l).forEach { (positive, text) ->
        Text(
            (if (positive) "+  " else "−  ") + text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (positive) OkGreen else RoutineAmber,
        )
    }
}
