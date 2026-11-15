package com.drishtisaathi.ui.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.drishtisaathi.ui.ButtonText
import com.drishtisaathi.ui.DS

// The component library (docs/DESIGN.md §4). Every screen is built from these; colours come from DS.colors only.

// ---- buttons -------------------------------------------------------------------------------------

/**
 * The one filled action of a screen: 56 dp, full width, press scale. [container] replaces the primary blue (text
 * colour then follows [DS.on]); [busy] shows a spinner and blocks taps; [icon] leads the label.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    icon: ImageVector? = null,
    container: Color? = null,
) {
    val c = DS.colors
    val bg = when { !enabled -> c.hairline; else -> container ?: c.primary }
    val fg = when { !enabled -> c.inkSecondary; container != null -> DS.on(container); else -> c.onPrimary }
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = { if (!busy) onClick() }, enabled = enabled, shape = DS.button, color = bg, interactionSource = interaction,
        modifier = modifier.fillMaxWidth().heightIn(min = DS.buttonHeight).pressScale(interaction).semantics { role = Role.Button },
    ) {
        ButtonContent(text, fg, icon, busy)
    }
}

/** Outlined second action: 56 dp, surface with a hairline. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    contentColor: Color? = null,
) {
    val c = DS.colors
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick, enabled = enabled, shape = DS.button, color = c.surface, interactionSource = interaction,
        border = BorderStroke(1.dp, if (enabled) c.hairline else c.hairline.copy(alpha = 0.5f)),
        modifier = modifier.fillMaxWidth().heightIn(min = DS.buttonHeight).pressScale(interaction).semantics { role = Role.Button },
    ) {
        ButtonContent(text, if (enabled) contentColor ?: c.ink else c.inkSecondary, icon, false)
    }
}

@Composable
private fun ButtonContent(text: String, fg: Color, icon: ImageVector?, busy: Boolean) {
    Row(
        Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), color = fg, strokeWidth = 2.5.dp)
            Spacer(Modifier.width(10.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, color = fg, style = ButtonText, textAlign = TextAlign.Center)
    }
}

/** A quiet text action (48 dp target). */
@Composable
fun DsTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color? = null, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = DS.minTouch)) {
        Text(text, color = color ?: DS.colors.primary, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
    }
}

/** A 48 dp icon button; [label] is what TalkBack reads. */
@Composable
fun DsIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color? = null, container: Color = Color.Transparent) {
    Surface(onClick = onClick, shape = CircleShape, color = container, modifier = modifier.size(DS.minTouch).semantics { contentDescription = label; role = Role.Button }) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = tint ?: DS.colors.ink, modifier = Modifier.size(DS.icon)) }
    }
}

// ---- cards and rows ------------------------------------------------------------------------------

/** A flat card: surface, 20 dp corners, 1 dp hairline, no shadow. [container] tints it (then no hairline). */
@Composable
fun DsCard(
    modifier: Modifier = Modifier,
    container: Color? = null,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(20.dp),
    spacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = DS.colors
    val border = if (container == null) BorderStroke(1.dp, c.hairline) else null
    val body: @Composable () -> Unit = { Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(spacing), content = content) }
    if (onClick != null) {
        val interaction = remember { MutableInteractionSource() }
        Surface(onClick = onClick, shape = DS.card, color = container ?: c.surface, border = border, interactionSource = interaction,
            modifier = modifier.fillMaxWidth().pressScale(interaction), content = body)
    } else Surface(shape = DS.card, color = container ?: c.surface, border = border, modifier = modifier.fillMaxWidth(), content = body)
}

/** The hero card: the one soft shadow level. Primary blue by default (text in on-primary). */
@Composable
fun HeroCard(
    modifier: Modifier = Modifier,
    container: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = DS.colors
    val bg = container ?: c.primary
    val shadow = Modifier.shadow(DS.heroShadow, DS.card, ambientColor = Color(0x1A0E1A2B), spotColor = Color(0x1A0E1A2B))
    val body: @Composable () -> Unit = { Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
    if (onClick != null) {
        val interaction = remember { MutableInteractionSource() }
        Surface(onClick = onClick, shape = DS.card, color = bg, interactionSource = interaction,
            modifier = modifier.fillMaxWidth().pressScale(interaction).then(shadow).semantics { role = Role.Button }, content = body)
    } else Surface(shape = DS.card, color = bg, modifier = modifier.fillMaxWidth().then(shadow), content = body)
}

/** An icon in a 40 dp circle tinted with its accent (14 %). */
@Composable
fun IconCircle(icon: ImageVector, accent: Color, modifier: Modifier = Modifier, size: Dp = DS.iconCircle, tint: Color? = null) {
    Box(modifier.size(size).background(accent.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint ?: accent, modifier = Modifier.size(size * 0.6f))
    }
}

/** A list row: icon circle, title, optional subtitle, and a trailing chevron (when tappable) or [trailing]. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    accent: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
    titleColor: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = DS.colors
    val row: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (icon != null) IconCircle(icon, accent ?: c.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = titleColor ?: c.ink, fontWeight = FontWeight.SemiBold)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.inkSecondary)
            }
            when {
                trailing != null -> trailing()
                onClick != null -> Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = c.inkSecondary)
            }
        }
    }
    if (onClick != null) Surface(onClick = onClick, color = Color.Transparent, modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { role = Role.Button }) { row() }
    else Box(modifier.fillMaxWidth().semantics(mergeDescendants = true) { }) { row() }
}

/** Rows in one flat card, separated by hairlines. */
@Composable
fun ListCard(modifier: Modifier = Modifier, rows: List<@Composable () -> Unit>) {
    val c = DS.colors
    Surface(shape = DS.card, color = c.surface, border = BorderStroke(1.dp, c.hairline), modifier = modifier.fillMaxWidth()) {
        Column {
            rows.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 70.dp), color = c.hairline)
                r()
            }
        }
    }
}

/** A section heading above cards. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge, color = DS.colors.ink)
        action?.invoke()
    }
}

// ---- choices ------------------------------------------------------------------------------------

/**
 * A big tappable answer (at least 64 dp). Selected: primary border, the primary container and a check circle. A
 * light haptic tick on selection.
 */
@Composable
fun ChoiceCard(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    accent: Color? = null,
) {
    val c = DS.colors
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = { haptics.tick(); onClick() }, shape = DS.card, interactionSource = interaction,
        color = if (selected) c.primarySoft else c.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) c.primary else c.hairline),
        modifier = modifier.fillMaxWidth().heightIn(min = DS.answerMin).pressScale(interaction)
            .semantics { this.selected = selected; role = Role.RadioButton },
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (icon != null) IconCircle(icon, accent ?: c.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text, style = MaterialTheme.typography.titleMedium, color = if (selected) c.primaryInk else c.ink)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.inkSecondary)
            }
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = c.primary, modifier = Modifier.size(28.dp))
                else Box(Modifier.size(22.dp).border(2.dp, c.hairline, CircleShape))
            }
        }
    }
}

/** Two or three options side by side (language, mode). */
@Composable
fun SegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = DS.colors
    val haptics = rememberHaptics()
    Row(modifier.background(c.surfaceAlt, DS.pill).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Surface(
                onClick = { if (!on) { haptics.tick(); onSelect(i) } }, shape = DS.pill,
                color = if (on) c.surface else Color.Transparent,
                shadowElevation = if (on) 1.dp else 0.dp,
                modifier = Modifier.weight(1f).heightIn(min = 44.dp).semantics { this.selected = on; role = Role.Tab },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(label, color = if (on) c.ink else c.inkSecondary, style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
    }
}

/** A three-stop slider (Never / Sometimes / Often); the picked label shows large above it. */
@Composable
fun FrequencySlider(labels: List<String>, value: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = DS.colors
    val haptics = rememberHaptics()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(labels.getOrElse(value) { "" }, style = MaterialTheme.typography.headlineSmall, color = c.primary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Slider(
            value = value.toFloat(), onValueChange = { v -> val i = v.toInt(); if (i != value) { haptics.tick(); onChange(i) } },
            valueRange = 0f..(labels.size - 1).toFloat(), steps = (labels.size - 2).coerceAtLeast(0),
            colors = SliderDefaults.colors(thumbColor = c.primary, activeTrackColor = c.primary, inactiveTrackColor = c.primarySoft,
                activeTickColor = c.onPrimary, inactiveTickColor = c.primary),
            modifier = Modifier.semantics { stateDescription = labels.getOrElse(value) { "" } },
        )
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, l ->
                Text(l, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = if (i == value) c.ink else c.inkSecondary,
                    textAlign = when (i) { 0 -> TextAlign.Start; labels.size - 1 -> TextAlign.End; else -> TextAlign.Center })
            }
        }
    }
}

/** Step k of n: the current dot is a wider pill. [label] is read by TalkBack ("Step 2 of 3"). */
@Composable
fun ProgressDots(total: Int, current: Int, label: String, modifier: Modifier = Modifier, colour: Color? = null) {
    val c = DS.colors
    val on = colour ?: c.primary
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = label }, horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until total) {
            Box(Modifier.height(8.dp).width(if (i == current) 24.dp else 8.dp)
                .background(if (i <= current) on else on.copy(alpha = 0.22f), DS.pill))
        }
    }
}

/** Per-eye progress for the eye tests: R ●○ L. [rightDone] / [leftDone] fill the dots; [current] rings one. */
@Composable
fun EyeProgress(rightLabel: String, leftLabel: String, rightDone: Boolean, leftDone: Boolean, currentRight: Boolean, description: String,
                modifier: Modifier = Modifier) {
    val c = DS.colors
    @Composable fun dot(done: Boolean, current: Boolean) = Box(
        Modifier.size(14.dp).background(if (done) c.primary else Color.Transparent, CircleShape)
            .border(2.dp, if (done || current) c.primary else c.hairline, CircleShape))
    Row(modifier.background(c.surface, DS.pill).border(1.dp, c.hairline, DS.pill).padding(horizontal = 14.dp, vertical = 8.dp)
        .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(rightLabel, style = MaterialTheme.typography.labelLarge, color = c.ink, fontWeight = FontWeight.SemiBold)
        dot(rightDone, currentRight && !rightDone)
        dot(leftDone, !currentRight && !leftDone)
        Text(leftLabel, style = MaterialTheme.typography.labelLarge, color = c.ink, fontWeight = FontWeight.SemiBold)
    }
}

// ---- bars ---------------------------------------------------------------------------------------

/** The top bar: page colour, title, back arrow, actions, hairline below. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DsTopBar(title: String, backLabel: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    val c = DS.colors
    Column {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() }) },
            navigationIcon = { if (onBack != null) DsIconButton(Icons.AutoMirrored.Rounded.ArrowBack, backLabel, onBack) },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(containerColor = c.background, titleContentColor = c.ink,
                navigationIconContentColor = c.ink, actionIconContentColor = c.ink),
        )
        HorizontalDivider(color = c.hairline)
    }
}

data class NavItem(val label: String, val icon: ImageVector, val selectedIcon: ImageVector = icon)

/** Bottom navigation: three items, icon + label, the selected one in the primary container. */
@Composable
fun DsBottomNav(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit) {
    val c = DS.colors
    val haptics = rememberHaptics()
    Column {
        HorizontalDivider(color = c.hairline)
        NavigationBar(containerColor = c.surface, tonalElevation = 0.dp) {
            items.forEachIndexed { i, it ->
                NavigationBarItem(
                    selected = i == selected, onClick = { if (i != selected) { haptics.tick(); onSelect(i) } },
                    icon = { Icon(if (i == selected) it.selectedIcon else it.icon, contentDescription = null) },
                    label = { Text(it.label, style = MaterialTheme.typography.labelLarge, maxLines = 1) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = c.primaryInk, selectedTextColor = c.primaryInk,
                        indicatorColor = c.primarySoft, unselectedIconColor = c.inkSecondary, unselectedTextColor = c.inkSecondary),
                )
            }
        }
    }
}

// ---- status -------------------------------------------------------------------------------------

enum class Tone { INFO, SUCCESS, WARNING, URGENT, NEUTRAL }

private data class ToneColours(val fg: Color, val bg: Color, val solid: Color, val icon: ImageVector)

@Composable
private fun tone(t: Tone): ToneColours {
    val c = DS.colors
    return when (t) {
        Tone.INFO -> ToneColours(c.primaryInk, c.primarySoft, c.primary, Icons.Rounded.Info)
        Tone.SUCCESS -> ToneColours(c.successInk, c.successSoft, c.success, Icons.Rounded.CheckCircle)
        Tone.WARNING -> ToneColours(c.warningInk, c.warningSoft, c.warning, Icons.Rounded.WarningAmber)
        Tone.URGENT -> ToneColours(c.urgentInk, c.urgentSoft, c.urgent, Icons.Rounded.ErrorOutline)
        Tone.NEUTRAL -> ToneColours(c.inkSecondary, c.surfaceAlt, c.inkSecondary, Icons.Rounded.Schedule)
    }
}

/** A status pill: always an icon and text (never colour alone). */
@Composable
fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: (() -> Unit)? = null) {
    val t = tone(tone)
    val body: @Composable () -> Unit = {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon ?: t.icon, contentDescription = null, tint = t.fg, modifier = Modifier.size(16.dp))
            Text(text, color = t.fg, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
    if (onClick != null) Surface(onClick = onClick, shape = DS.pill, color = t.bg, modifier = modifier.heightIn(min = DS.minTouch)) {
        Box(contentAlignment = Alignment.Center) { body() }
    } else Surface(shape = DS.pill, color = t.bg, modifier = modifier) { body() }
}

/** The connection pill for the backend: Connected (success) or Offline (warning), with its own icon. */
@Composable
fun ConnectionPill(text: String, online: Boolean, onClick: (() -> Unit)? = null, modifier: Modifier = Modifier) =
    StatusPill(text, if (online) Tone.SUCCESS else Tone.WARNING, modifier, icon = if (online) Icons.Rounded.Wifi else Icons.Rounded.WifiOff, onClick = onClick)

/**
 * A banner: info / warning / urgent, with its icon, a title, an optional body and lines. [solid] fills it with the
 * tone colour (the urgent banner on results), else the soft container.
 */
@Composable
fun Banner(
    title: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    body: String? = null,
    lines: List<String> = emptyList(),
    solid: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val t = tone(tone)
    val bg = if (solid) t.solid else t.bg
    val fg = if (solid) DS.on(t.solid) else t.fg
    val text = if (solid) fg else DS.colors.ink
    Row(
        modifier.fillMaxWidth().background(bg, DS.card).padding(18.dp)
            .semantics(mergeDescendants = true) { liveRegion = if (tone == Tone.URGENT) LiveRegionMode.Assertive else LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(t.icon, contentDescription = null, tint = fg, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = fg)
            if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = text)
            lines.forEach { l ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", style = MaterialTheme.typography.bodyLarge, color = fg)
                    Text(l, style = MaterialTheme.typography.bodyLarge, color = text)
                }
            }
            action?.invoke()
        }
    }
}

/** An empty state: illustration, a title, one line and one CTA. */
@Composable
fun EmptyState(spot: Spot, title: String, line: String, modifier: Modifier = Modifier, cta: String? = null, onCta: (() -> Unit)? = null) {
    val c = DS.colors
    Column(modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Illustration(spot, size = 140.dp)
        Text(title, style = MaterialTheme.typography.titleLarge, color = c.ink, textAlign = TextAlign.Center)
        Text(line, style = MaterialTheme.typography.bodyLarge, color = c.inkSecondary, textAlign = TextAlign.Center)
        if (cta != null && onCta != null) { Spacer(Modifier.height(4.dp)); PrimaryButton(cta, onCta) }
    }
}

/** A friendly error with Retry. */
@Composable
fun ErrorState(text: String, retry: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val c = DS.colors
    Column(modifier.fillMaxWidth().background(c.urgentSoft, DS.card).padding(20.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = c.urgentInk)
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = c.ink)
        }
        SecondaryButton(retry, onRetry, icon = Icons.Rounded.Refresh)
    }
}

/** A loading placeholder block that shimmers (still under reduced motion). */
@Composable
fun Skeleton(modifier: Modifier = Modifier, height: Dp = 72.dp, shape: RoundedCornerShape = DS.card) {
    val c = DS.colors
    val reduced = Motion.reduced()
    val a = if (reduced) 0.7f else {
        val t = rememberInfiniteTransition(label = "skeleton")
        t.animateFloat(0.45f, 0.9f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "alpha").value
    }
    Box(modifier.fillMaxWidth().height(height).clip(shape).alpha(a).background(c.surfaceAlt).clearAndSetSemantics { })
}

/** Skeleton rows for a list that is loading; [label] tells TalkBack ("Loading"). */
@Composable
fun SkeletonList(label: String, rows: Int = 3) {
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = label }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(rows) { Skeleton() }
    }
}

/** Snackbars in the design language (ink on the page in light, surface in dark). */
@Composable
fun DsSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    val c = DS.colors
    SnackbarHost(state, modifier) { data ->
        Snackbar(data, shape = DS.button, containerColor = c.ink, contentColor = c.background, actionColor = c.primarySoft)
    }
}

/** A modal bottom sheet with the sheet shape and the one soft shadow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DsBottomSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = DS.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = DS.sheet, containerColor = c.surface, contentColor = c.ink, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

// ---- people and chat ----------------------------------------------------------------------------

/** Initials (up to two) on the primary container. */
@Composable
fun Avatar(name: String?, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val c = DS.colors
    val initials = initials(name)
    Box(modifier.size(size).background(c.primarySoft, CircleShape).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Text(initials, color = c.primaryInk, style = if (size >= 64.dp) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium)
    }
}

/** "Meera Sharma" → "MS"; "DEMO Dr. Meera Sharma" → "MS"; Hindi names keep their first letters. */
fun initials(name: String?): String {
    val words = name.orEmpty().split(' ', '.').map { it.trim() }.filter { it.isNotEmpty() && it.uppercase() != "DEMO" && it.lowercase() != "dr" }
    return when {
        words.isEmpty() -> "+"
        words.size == 1 -> words[0].take(1).uppercase()
        else -> (words.first().take(1) + words.last().take(1)).uppercase()
    }
}

/** A chat bubble: mine on the right in primary, theirs on the left on the surface, with the time. */
@Composable
fun ChatBubble(mine: Boolean, time: String, modifier: Modifier = Modifier, who: String? = null, content: @Composable ColumnScope.(ink: Color) -> Unit) {
    val c = DS.colors
    val shape = if (mine) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    val ink = if (mine) c.onPrimary else c.ink
    Box(modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 300.dp).background(if (mine) c.primary else c.surface, shape)
                .then(if (mine) Modifier else Modifier.border(1.dp, c.hairline, shape))
                .padding(horizontal = 14.dp, vertical = 10.dp).semantics(mergeDescendants = true) { },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (who != null) Text(who, style = MaterialTheme.typography.labelMedium, color = if (mine) ink.copy(alpha = 0.85f) else c.primaryInk)
            content(ink)
            Text(time, style = MaterialTheme.typography.labelSmall, color = if (mine) ink.copy(alpha = 0.8f) else c.inkSecondary, modifier = Modifier.align(Alignment.End))
        }
    }
}

/** "Today", "Yesterday" or a date between chat messages. */
@Composable
fun DaySeparator(text: String) {
    val c = DS.colors
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = c.inkSecondary,
            modifier = Modifier.background(c.surfaceAlt, DS.pill).padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

// ---- input --------------------------------------------------------------------------------------

/**
 * A text field: the label above, 56 dp, the helper or the error below (the error with its icon). [password] adds a
 * show / hide button ([showLabel] / [hideLabel] for TalkBack).
 */
@Composable
fun DsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    helper: String? = null,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    showLabel: String = "Show",
    hideLabel: String = "Hide",
    placeholder: String? = null,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else 4,
) {
    val c = DS.colors
    var shown by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = c.ink, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = value, onValueChange = onValueChange, singleLine = singleLine, maxLines = maxLines, isError = error != null,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.ink),
            placeholder = placeholder?.let { { Text(it, style = MaterialTheme.typography.bodyLarge, color = c.inkSecondary) } },
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType),
            visualTransformation = if (password && !shown) PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = if (password) { {
                DsIconButton(if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (shown) hideLabel else showLabel, { shown = !shown }, tint = c.inkSecondary)
            } } else null,
            shape = DS.field,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = c.surface, unfocusedContainerColor = c.surface, errorContainerColor = c.surface,
                focusedBorderColor = c.primary, unfocusedBorderColor = c.hairline, errorBorderColor = c.urgent,
                cursorColor = c.primary, focusedTextColor = c.ink, unfocusedTextColor = c.ink,
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = DS.buttonHeight).semantics { contentDescription = label },
        )
        when {
            error != null -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = c.urgentInk, modifier = Modifier.size(18.dp))
                Text(error, style = MaterialTheme.typography.bodySmall, color = c.urgentInk)
            }
            helper != null -> Text(helper, style = MaterialTheme.typography.bodySmall, color = c.inkSecondary)
        }
    }
}

/** OTP-style boxes for a short code (digits only). Ready for OTP login; not used while login is by password. */
@Composable
fun PinField(value: String, onValueChange: (String) -> Unit, length: Int, label: String, modifier: Modifier = Modifier) {
    val c = DS.colors
    BasicTextField(
        value = value, onValueChange = { v -> onValueChange(v.filter(Char::isDigit).take(length)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = modifier.semantics { contentDescription = label },
        decorationBox = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (i in 0 until length) {
                    val ch = value.getOrNull(i)?.toString() ?: ""
                    val focus = i == value.length
                    Box(Modifier.size(52.dp, 60.dp).background(c.surface, DS.field).border(if (focus) 2.dp else 1.dp, if (focus) c.primary else c.hairline, DS.field),
                        contentAlignment = Alignment.Center) {
                        Text(ch, style = MaterialTheme.typography.headlineSmall, color = c.ink)
                    }
                }
            }
        },
    )
}

// ---- feedback -----------------------------------------------------------------------------------

/** The success check: a filled circle, the tick draws in over 400 ms (at once under reduced motion). A success haptic. */
@Composable
fun SuccessCheck(modifier: Modifier = Modifier, size: Dp = 96.dp, haptic: Boolean = true) {
    val c = DS.colors
    val reduced = Motion.reduced()
    val haptics = rememberHaptics()
    val t = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (haptic) haptics.success()
        if (!reduced) t.animateTo(1f, tween(DS.successMs, easing = FastOutSlowInEasing))
    }
    val tickColour = DS.on(c.success)
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        val r = this.size.minDimension / 2
        drawCircle(c.success, r * (0.85f + 0.15f * t.value))
        val p = Path().apply { moveTo(r * 0.55f, r * 1.02f); lineTo(r * 0.88f, r * 1.33f); lineTo(r * 1.47f, r * 0.7f) }
        val m = PathMeasure().apply { setPath(p, false) }
        val part = Path()
        m.getSegment(0f, m.length * t.value, part, true)
        drawPath(part, tickColour, style = Stroke(r * 0.16f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** 3-2-1 before a test starts; [onDone] runs after "1" (at once under reduced motion). */
@Composable
fun Countdown(onDone: () -> Unit, modifier: Modifier = Modifier, from: Int = 3, label: (Int) -> String = { it.toString() }) {
    val c = DS.colors
    val reduced = Motion.reduced()
    var n by remember { mutableIntStateOf(from) }
    LaunchedEffect(Unit) {
        if (!reduced) while (n > 1) { kotlinx.coroutines.delay(800); n-- }
        if (!reduced) kotlinx.coroutines.delay(800)
        onDone()
    }
    Box(modifier.size(160.dp).background(c.primarySoft, CircleShape).semantics { liveRegion = LiveRegionMode.Assertive; contentDescription = label(n) },
        contentAlignment = Alignment.Center) {
        Text(n.toString(), style = MaterialTheme.typography.displayLarge, color = c.primaryInk)
    }
}

/** A small live pill for the eye tests: the distance and whether it is right ("45 cm ✓" / "Move closer"). */
@Composable
fun DistancePill(text: String, ok: Boolean, modifier: Modifier = Modifier) =
    StatusPill(text, if (ok) Tone.SUCCESS else Tone.WARNING, modifier)

/** A soft dot used inside rows (status without words next to a worded label). */
@Composable
fun Dot(colour: Color, modifier: Modifier = Modifier, size: Dp = 10.dp) = Box(modifier.size(size).background(colour, CircleShape))

/** Draws nothing; keeps a fixed gap in a Column (spacing tokens). */
@Composable
fun Gap(height: Dp) = Spacer(Modifier.height(height))
