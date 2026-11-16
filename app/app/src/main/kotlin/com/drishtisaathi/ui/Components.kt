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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.widthIn
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
    /** Icon actions before the language switch (e.g. Video, WhatsApp on the chat). */
    actions: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val c = DS.colors
    Scaffold(
        containerColor = c.background,
        // calm top bar: the page colour, the title in ink, a back arrow, the language switch, a hairline below;
        // with icon actions the switch becomes one compact button (the other language) so the title keeps its room
        topBar = {
            com.drishtisaathi.ui.designsystem.DsTopBar(title, S.back[lang], onBack) {
                if (actions != null) { actions(); CompactLangButton(lang, onLang) } else LangToggle(lang, onLang)
            }
        },
        content = content,
    )
}

/** Language switch: a two-way segmented control (हिंदी | EN) in the top bar; each half is a 44 dp target in a 52 dp bar. */
@Composable
fun LangToggle(lang: Lang, onLang: (Lang) -> Unit, modifier: Modifier = Modifier, long: Boolean = false) {
    val langs = listOf(Lang.HI, Lang.EN)
    com.drishtisaathi.ui.designsystem.SegmentedControl(
        listOf("हिंदी", if (long) "English" else "EN"), langs.indexOf(lang), { onLang(langs[it]) },
        modifier.padding(end = if (long) 0.dp else 8.dp).then(if (long) Modifier.fillMaxWidth() else Modifier.width(148.dp)),
    )
}

/** One 48 dp button that switches to the other language ("EN" in Hindi, "हिं" in English). */
@Composable
fun CompactLangButton(lang: Lang, onLang: (Lang) -> Unit) {
    val other = if (lang == Lang.HI) Lang.EN else Lang.HI
    val c = DS.colors
    androidx.compose.material3.Surface(
        onClick = { onLang(other) }, shape = DS.pill, color = c.surfaceAlt,
        modifier = Modifier.padding(end = 8.dp).heightIn(min = DS.minTouch).widthIn(min = DS.minTouch)
            .semantics { contentDescription = if (other == Lang.EN) "English" else "हिंदी" },
    ) {
        androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 12.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text(if (other == Lang.EN) "EN" else "हिं", style = MaterialTheme.typography.labelLarge, color = c.ink)
        }
    }
}

/**
 * A 56 dp button. [selected] or a [container] colour makes it the filled main action (PrimaryButton); otherwise it is
 * the outlined SecondaryButton.
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
    if (selected || container != null) com.drishtisaathi.ui.designsystem.PrimaryButton(text, onClick, modifier, enabled = enabled, container = container)
    else com.drishtisaathi.ui.designsystem.SecondaryButton(text, onClick, modifier, enabled = enabled)
}

/** A flat card with a title: surface, 20 dp corners, hairline border, no shadow. [container] tints it (warnings). */
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
        com.drishtisaathi.engine.MatchLabel.MODERATE -> DS.colors.primaryInk to TealLight
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
