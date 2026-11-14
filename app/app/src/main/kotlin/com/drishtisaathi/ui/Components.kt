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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    lang: Lang,
    onLang: (Lang) -> Unit,
    title: String = S.appName[lang],
    onBack: (() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val c = DS.colors
    Scaffold(
        containerColor = c.background,
        topBar = {
            // calm top bar: the page colour, the title in ink, a hairline below
            Column {
                TopAppBar(
                    title = { Text(title, maxLines = 1, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                    navigationIcon = {
                        if (onBack != null) TextButton(onClick = onBack, modifier = Modifier.heightIn(min = DS.minTouch)) {
                            Text("‹ " + S.back[lang], color = c.primary, style = MaterialTheme.typography.labelMedium)
                        }
                    },
                    actions = { LangToggle(lang, onLang) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = c.background, titleContentColor = c.ink,
                        navigationIconContentColor = c.primary, actionIconContentColor = c.ink),
                )
                androidx.compose.material3.HorizontalDivider(color = c.hairline)
            }
        },
        content = content,
    )
}

/** Language switch: two pill chips, each a 48 dp touch target. */
@Composable
fun LangToggle(lang: Lang, onLang: (Lang) -> Unit) {
    val c = DS.colors
    Row(Modifier.padding(end = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(Lang.HI to "हिंदी", Lang.EN to "EN").forEach { (l, label) ->
            val selected = l == lang
            androidx.compose.material3.Surface(
                onClick = { onLang(l) }, shape = DS.pill,
                color = if (selected) c.primary else c.surface,
                border = if (selected) null else BorderStroke(1.dp, c.hairline),
                modifier = Modifier.heightIn(min = DS.minTouch).semantics { this.selected = selected; role = Role.Tab },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(label, color = if (selected) c.onPrimary else c.ink, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
        }
    }
}

/**
 * Large touch target (at least 64 dp). [selected] or a [container] colour makes it the filled main action; otherwise it
 * is a quiet card-like button (surface, hairline border).
 */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    container: Color? = null,
) {
    val c = DS.colors
    val filled = selected || container != null
    val bg = when {
        !enabled -> c.hairline
        filled -> container ?: c.primary
        else -> c.surface
    }
    val fg = when {
        !enabled -> c.inkSecondary
        filled -> if (container == null) c.onPrimary else DS.on(bg)
        else -> c.ink
    }
    androidx.compose.material3.Surface(
        onClick = onClick, enabled = enabled, shape = DS.button, color = bg,
        border = if (filled || !enabled) null else BorderStroke(1.dp, c.hairline),
        modifier = modifier.fillMaxWidth().heightIn(min = DS.answerMin).semantics { role = Role.Button },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = fg, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
        }
    }
}

/** A soft card with a title: surface, 24 dp corners, hairline border, no shadow. [container] tints it (warnings). */
@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, container: Color = Color.Unspecified, content: @Composable () -> Unit) {
    val c = DS.colors
    val tinted = container != Color.Unspecified && container != Color.White
    androidx.compose.material3.Surface(
        modifier = modifier.fillMaxWidth(), shape = DS.card,
        color = if (tinted) container else c.surface,
        border = if (tinted) null else BorderStroke(1.dp, c.hairline),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.ink, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
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
            .background(bg, DS.pill)
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
