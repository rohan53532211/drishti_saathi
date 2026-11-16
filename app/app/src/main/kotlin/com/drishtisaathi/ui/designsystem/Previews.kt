package com.drishtisaathi.ui.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.drishtisaathi.ui.DS
import com.drishtisaathi.ui.DrishtiTheme

/** Android Studio previews of the design system, light and dark (docs/DESIGN.md §4). */
@Composable
private fun Sheet(dark: Boolean, content: @Composable () -> Unit) = DrishtiTheme(dark) {
    Column(Modifier.background(DS.colors.background).padding(DS.screen), verticalArrangement = Arrangement.spacedBy(DS.s4)) { content() }
}

@Preview(name = "Buttons", widthDp = 380)
@Preview(name = "Buttons dark", widthDp = 380, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ButtonsPreview() = Sheet(false) {
    PrimaryButton("आँखों की जाँच शुरू करें", {})
    PrimaryButton("Sending…", {}, busy = true)
    SecondaryButton("Back", {})
    DsTextButton("Forgot password?", {})
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { DsIconButton(Icons.Rounded.Chat, "Chat", {}) }
}

@Preview(name = "Cards and rows", widthDp = 380)
@Composable
private fun CardsPreview() = Sheet(false) {
    HeroCard {
        IconCircle(DsIcons.EyeMark, DS.colors.primary)
        Text("Start eye test (10 min)")
    }
    ListCard(rows = listOf(
        { ListRow("Past sessions", subtitle = "12 customers", icon = Icons.Rounded.Person, onClick = {}) },
        { ListRow("Frames", icon = Icons.Rounded.Visibility, onClick = {}) },
    ))
    DsCard { Text("A flat card with a hairline") }
}

@Preview(name = "Choices", widthDp = 380)
@Preview(name = "Choices dark", widthDp = 380, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ChoicesPreview() = Sheet(true) {
    ChoiceCard("हाँ / Yes", selected = true, onClick = {})
    ChoiceCard("नहीं / No", selected = false, onClick = {})
    SegmentedControl(listOf("हिंदी", "English"), 0, {})
    FrequencySlider(listOf("Never", "Sometimes", "Often"), 1, {})
    ProgressDots(3, 1, "Step 2 of 3")
    EyeProgress("R", "L", rightDone = true, leftDone = false, currentRight = false, description = "Right eye done, left eye now")
}

@Preview(name = "Status", widthDp = 380)
@Composable
private fun StatusPreview() = Sheet(false) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ConnectionPill("Connected", online = true)
        StatusPill("Urgent", Tone.URGENT)
        StatusPill("Waiting", Tone.WARNING)
    }
    Banner("Go to an eye hospital today", Tone.URGENT, solid = true, lines = listOf("Do not wait", "Take this report with you"))
    Banner("Keep the phone at arm's length", Tone.INFO)
    EmptyState(Spot.CHAT, "No consultations yet", "Ask an optometrist about your result.", cta = "Consult optometrist", onCta = {})
    ErrorState("Could not reach the server.", "Try again", {})
    Skeleton()
}

@Preview(name = "Chat and input", widthDp = 380)
@Composable
private fun ChatPreview() = Sheet(false) {
    DaySeparator("Today")
    ChatBubble(mine = false, time = "10:02", who = "Dr Meera") { ink -> Text("Namaste! How are your eyes?", color = ink) }
    ChatBubble(mine = true, time = "10:03") { ink -> Text("Blurry at night.", color = ink) }
    DsTextField("98765", {}, "Mobile number", helper = "10 digits")
    DsTextField("", {}, "Password", error = "Enter your password", password = true)
    PinField("12", {}, 4, "PIN")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Avatar("Meera Sharma"); Avatar(null) }
}

@Preview(name = "Bars", widthDp = 380)
@Composable
private fun BarsPreview() = Sheet(false) {
    DsTopBar("Results", "Back", onBack = {})
    DsBottomNav(listOf(NavItem("Home", Icons.Rounded.Home), NavItem("Consultations", Icons.Rounded.Chat), NavItem("Profile", Icons.Rounded.Person)), 0, {})
}

@Preview(name = "Illustrations", widthDp = 380, heightDp = 900)
@Composable
private fun IllustrationsPreview() = Sheet(false) {
    Spot.entries.chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { Illustration(it, size = 100.dp) } } }
    SuccessCheck(haptic = false)
}
