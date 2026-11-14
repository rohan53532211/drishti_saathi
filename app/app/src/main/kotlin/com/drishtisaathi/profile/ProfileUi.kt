package com.drishtisaathi.profile

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.drishtisaathi.engine.profile.LensPlan
import com.drishtisaathi.engine.profile.Ring
import com.drishtisaathi.engine.profile.Suggestion
import com.drishtisaathi.engine.profile.VisionProfile
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.SProfile
import com.drishtisaathi.ui.DS
import com.drishtisaathi.ui.DrishtiColors

fun ringColour(id: String, c: DrishtiColors): Color = when (id) {
    "WORK" -> c.work; "DIGITAL" -> c.digital; "MOBILITY" -> c.mobility; else -> c.leisure
}

/** A soft card: surface, 24 dp corners, hairline border; [hero] adds the soft shadow. */
@Composable
fun SoftCard(modifier: Modifier = Modifier, hero: Boolean = false, content: @Composable () -> Unit) {
    val c = DS.colors
    Surface(
        shape = DS.card, color = c.surface, border = BorderStroke(1.dp, c.hairline),
        modifier = modifier.fillMaxWidth().then(if (hero) Modifier.shadow(12.dp, DS.card, ambientColor = Color(0x14000000), spotColor = Color(0x14000000)) else Modifier),
    ) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() } }
}

/**
 * "Your Vision Profile": one big ring of four concentric arcs (work, digital, mobility, leisure) and a 2 × 2 grid of
 * small rings with their labels. The arcs draw in over 900 ms.
 */
@Composable
fun VisionProfileCard(lang: Lang, profile: VisionProfile, modifier: Modifier = Modifier, emergency: Boolean = false) {
    val c = DS.colors
    val t = remember { Animatable(0f) }
    LaunchedEffect(profile) { t.snapTo(0f); t.animateTo(1f, tween(DS.ringsMs, easing = FastOutSlowInEasing)) }
    val summary = LensText.ringLines(profile, emergency).joinToString(". ") { it[lang] }
    SoftCard(modifier, hero = true) {
        Text(SProfile.profileTitle[lang], style = MaterialTheme.typography.titleLarge, color = c.ink, modifier = Modifier.semantics { heading() })
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(200.dp).semantics { contentDescription = summary }) {
                val stroke = size.width * 0.075f
                profile.rings.forEachIndexed { i, (id, r) ->
                    val inset = stroke / 2 + i * stroke * 1.45f
                    val tl = Offset(inset, inset); val sz = Size(size.width - 2 * inset, size.height - 2 * inset)
                    val col = ringColour(id, c)
                    drawArc(col.copy(alpha = 0.16f), 0f, 360f, false, tl, sz, style = Stroke(stroke))
                    if (r.known) drawArc(col, -90f, 360f * (r.score / 100f) * t.value, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
            Text((profile.overall * t.value).toInt().toString(), style = MaterialTheme.typography.displaySmall, color = c.ink, fontWeight = FontWeight.SemiBold)
        }
        profile.rings.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (id, r) ->
                    SmallRing(lang, id, r, t.value, glare = id == "MOBILITY" && profile.nightGlareSensitive, modifier = Modifier.weight(1f), emergency = emergency)
                }
            }
        }
    }
}

@Composable
private fun SmallRing(lang: Lang, id: String, r: Ring, t: Float, glare: Boolean, modifier: Modifier = Modifier, emergency: Boolean = false) {
    val c = DS.colors
    val col = ringColour(id, c)
    Row(modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(52.dp)) {
                val s = size.width * 0.14f
                val tl = Offset(s / 2, s / 2); val sz = Size(size.width - s, size.height - s)
                drawArc(col.copy(alpha = 0.16f), 0f, 360f, false, tl, sz, style = Stroke(s))
                if (r.known) drawArc(col, -90f, 360f * (r.score / 100f) * t, false, tl, sz, style = Stroke(s, cap = StrokeCap.Round))
            }
            Text(if (r.known) r.score.toString() else "–", style = MaterialTheme.typography.labelMedium, color = c.ink)
        }
        Column {
            Text(LensText.ringTitle(id)[lang], style = MaterialTheme.typography.labelMedium, color = c.inkSecondary)
            Text(LensText.ringLabel(r, emergency)[lang], style = MaterialTheme.typography.bodyMedium, color = c.ink, fontWeight = FontWeight.SemiBold)
            if (glare) Text(SProfile.nightGlare[lang], style = MaterialTheme.typography.bodySmall, color = c.warningInk)
        }
    }
}

/** A simple lens drawing: a clear disc; blue tint for BlueGuard; a grey gradient for PhotoFusion X; an amber edge for DriveSafe. */
@Composable
fun LensIllustration(ids: List<String>, size: Dp = 88.dp) {
    val c = DS.colors
    val blue = "blueguard" in ids
    val photo = "photofusion_x" in ids
    val drive = "drivesafe" in ids || "dv_drivesafe" in ids
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2 * 0.92f
        val centre = Offset(this.size.width / 2, this.size.height / 2)
        val fill = when {
            photo -> Brush.verticalGradient(listOf(Color(0xFF6B7785), Color(0xFFDDE2E8)), startY = centre.y - r, endY = centre.y + r)
            blue -> Brush.radialGradient(listOf(Color(0x332F80ED), Color(0x662F80ED)), centre, r)
            else -> Brush.radialGradient(listOf(Color(0x10FFFFFF), c.primarySoft), centre, r)
        }
        drawCircle(fill, r, centre)
        drawCircle(if (drive) Color(0xFFF2A93B) else c.hairline, r, centre, style = Stroke(if (drive) r * 0.12f else r * 0.05f))
        // a soft highlight
        drawArc(Color.White.copy(alpha = 0.7f), 200f, 50f, false, Offset(centre.x - r * 0.72f, centre.y - r * 0.72f), Size(r * 1.44f, r * 1.44f),
            style = Stroke(r * 0.07f, cap = StrokeCap.Round))
    }
}

@Composable
private fun WhyBullets(lang: Lang, s: Suggestion) {
    val c = DS.colors
    LensText.why(s).forEach {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("✓", color = c.success, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(it[lang], style = MaterialTheme.typography.bodyLarge, color = c.ink)
        }
    }
}

@Composable
private fun Link(lang: Lang, url: String?) {
    val uri = LocalUriHandler.current
    if (url != null) TextButton(onClick = { runCatching { uri.openUri(url) } }) { Text(SProfile.lensMore[lang], color = DS.colors.primary) }
}

/**
 * The lens cards: the main lens (type, design, what is in the lens, coating) with its drawing and "Why for you"
 * bullets, then one card per second pair. ZEISS product names appear only here. Nothing is shown when no lens is
 * allowed (the referral card is shown instead).
 */
@Composable
fun LensCards(lang: Lang, plan: LensPlan) {
    val c = DS.colors
    if (!plan.allowed) return
    val type = plan.type ?: return
    SoftCard(hero = true) {
        Text(SProfile.lensLabel[lang], style = MaterialTheme.typography.labelMedium, color = c.inkSecondary)
        if (plan.optometristConfirms) Text(SProfile.optometristConfirms[lang], style = MaterialTheme.typography.labelLarge, color = c.warningInk,
            modifier = Modifier.background(c.warningSoft, DS.pill).padding(horizontal = 12.dp, vertical = 6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LensIllustration(plan.ids)
            Column(Modifier.weight(1f)) {
                Text(plan.design?.name ?: type.name, style = MaterialTheme.typography.headlineSmall, color = c.ink, fontWeight = FontWeight.SemiBold)
                Text(type.name, style = MaterialTheme.typography.bodyMedium, color = c.inkSecondary)
            }
        }
        Text(type.what, style = MaterialTheme.typography.bodyMedium, color = c.inkSecondary)
        Text(SProfile.whyForYou[lang], style = MaterialTheme.typography.titleMedium, color = c.ink)
        WhyBullets(lang, type)
        plan.design?.let { WhyBullets(lang, it) }
        (plan.materials + listOfNotNull(plan.coating)).forEach { s ->
            Column(Modifier.fillMaxWidth().background(c.background, DS.button).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(LensText.kind(s.kind)[lang], style = MaterialTheme.typography.labelMedium, color = c.inkSecondary)
                Text(s.name, style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text(s.what, style = MaterialTheme.typography.bodySmall, color = c.inkSecondary)
                WhyBullets(lang, s)
            }
        }
        Link(lang, type.url)
    }
    plan.secondPairs.forEach { s ->
        SoftCard {
            Text(SProfile.lensSecond[lang], style = MaterialTheme.typography.labelMedium, color = c.inkSecondary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LensIllustration(listOf(s.id), size = 56.dp)
                Text(s.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = c.ink, textAlign = TextAlign.Start)
            }
            Text(s.what, style = MaterialTheme.typography.bodyMedium, color = c.inkSecondary)
            WhyBullets(lang, s)
            Link(lang, s.url)
        }
    }
}
