package com.drishtisaathi.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.drishtisaathi.engine.config.LensAssistedConfig
import com.drishtisaathi.engine.profile.LensAssisted
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.SProfile
import com.drishtisaathi.i18n.Txt
import com.drishtisaathi.i18n.fmtD
import com.drishtisaathi.ui.DS

/** The minus lens typed on the pad: digits and one decimal point; the sign is always minus. Null = not a number yet. */
fun padLensPower(typed: String): Double? = typed.toDoubleOrNull()?.let { if (it == 0.0) 0.0 else -it }

/** Adds [key] ("0".."9", ".", or "<" = delete) to what was [typed], keeping it a number of at most 2 + 2 digits. */
fun padType(typed: String, key: String): String = when {
    key == "<" -> typed.dropLast(1)
    key == "." -> if ("." in typed || typed.isEmpty()) typed else "$typed."
    "." in typed -> if (typed.substringAfter(".").length >= 2) typed else typed + key
    else -> if (typed.length >= 2) typed else typed + key
}

/**
 * Lens-assisted mode, step 1: staff are told why, which lens to hold (about 2 D weaker than the first reading) and
 * type the power of the lens they actually hold (a trial lens or the customer's old glasses) on a number pad.
 * [tooStrong]: the last lens left the letters sharp at every distance, so a weaker one is asked for.
 */
@Composable
fun LensEntryScreen(
    lang: Lang, title: Txt, firstPassSeD: Double, cfg: LensAssistedConfig, tooStrong: Boolean, speak: (Txt) -> Unit,
    onStart: (lensD: Double) -> Unit, onNoLens: () -> Unit,
) {
    val c = DS.colors
    val suggested = remember(firstPassSeD) { LensAssisted.suggestLens(firstPassSeD, cfg) }
    var typed by remember { mutableStateOf("%.2f".format(java.util.Locale.US, -suggested)) }
    val lens = padLensPower(typed)
    val valid = LensAssisted.validLens(lens, cfg) && lens != 0.0
    val suggestLine = SProfile.lensSuggest.format(fmtD(suggested))
    LaunchedEffect(tooStrong) { speak(if (tooStrong) SProfile.lensTooStrong else Txt("${SProfile.lensModeWhy.en} ${suggestLine.en}", "${SProfile.lensModeWhy.hi} ${suggestLine.hi}")) }

    Column(
        Modifier.fillMaxSize().background(c.background).verticalScroll(rememberScrollState()).padding(horizontal = DS.screen, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title[lang], style = MaterialTheme.typography.headlineSmall, color = c.ink, fontWeight = FontWeight.SemiBold)
        if (tooStrong) Text(SProfile.lensTooStrong[lang], color = c.urgentInk, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth().background(c.urgentSoft, DS.button).padding(16.dp))
        else Text(SProfile.lensModeWhy[lang], style = MaterialTheme.typography.bodyLarge, color = c.inkSecondary)
        Text(SProfile.lensFirstPass.format(fmtD(firstPassSeD))[lang], style = MaterialTheme.typography.bodyMedium, color = c.inkSecondary)
        if (LensAssisted.highMyopia(firstPassSeD, cfg)) Text(SProfile.highMyopiaNote[lang], color = c.warningInk, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold)
        Text(suggestLine[lang], style = MaterialTheme.typography.titleMedium, color = c.ink)

        // ---- the typed lens power ----
        Column(Modifier.fillMaxWidth().background(c.surface, DS.card).padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(SProfile.lensEnter[lang], style = MaterialTheme.typography.labelMedium, color = c.inkSecondary)
            Text("−" + typed.ifEmpty { "0" } + " D", style = MaterialTheme.typography.displayMedium, color = if (valid) c.primary else c.inkSecondary,
                fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (!valid) Text(SProfile.lensInvalid[lang], style = MaterialTheme.typography.bodyMedium, color = c.warningInk)
        }
        NumberPad(lang) { key -> typed = padType(typed, key) }
        PrimaryButton(SProfile.lensStart[lang], enabled = valid) { lens?.let(onStart) }
        TextButton(onClick = onNoLens, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(SProfile.lensNone[lang], color = c.inkSecondary) }
    }
}

/** A plain number pad: 1–9, ".", 0, delete. Keys are 64 dp high. */
@Composable
fun NumberPad(lang: Lang, onKey: (String) -> Unit) {
    val c = DS.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(".", "0", "<")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { k ->
                    Surface(
                        onClick = { onKey(k) }, shape = DS.button, color = c.surface, border = BorderStroke(1.dp, c.hairline),
                        modifier = Modifier.weight(1f).height(DS.answerMin).semantics {
                            role = Role.Button
                            contentDescription = if (k == "<") SProfile.padDelete[lang] else k
                        },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(if (k == "<") "←" else k, style = MaterialTheme.typography.headlineSmall, color = c.ink)
                        }
                    }
                }
            }
        }
    }
}
