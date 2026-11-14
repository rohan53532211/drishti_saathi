package com.drishtisaathi.profile

import com.drishtisaathi.bridge.BridgeProfile
import com.drishtisaathi.engine.Budget
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.QuickEstimate
import com.drishtisaathi.engine.VisionProblem
import com.drishtisaathi.engine.profile.LensCatalog
import com.drishtisaathi.engine.profile.LensEngine
import com.drishtisaathi.engine.profile.Reason
import com.drishtisaathi.engine.profile.SurveyAnswers
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.SProfile
import com.drishtisaathi.pdf.PdfContent
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Phase E in the app: the profile and lens suggestion in words, on the PDF and in the optometrist report. */
class ProfileViewTest {
    private val f = ProfileFixtures
    private val engine = LensEngine(LensCatalog.parse(File("src/main/assets/lens_catalog.json").readText()), f.rules.lensEngine)
    private fun survey(vararg kv: Pair<String, List<String>>) = kv.fold(SurveyAnswers()) { s, (k, v) -> s.with(k, v) }
    private val itWorker = InterviewAnswers(age = 35, visionProblem = VisionProblem.DISTANCE, distanceBlur = true, budget = Budget.MID, redFlagFlashes = false, redFlagOther = false)
    private val itSurvey = survey("tiredEyes" to listOf("OFTEN"), "neckPain" to listOf("SOMETIMES"), "workHours" to listOf("9"), "workActivities" to listOf("DESKTOP"),
        "screenHoursDay" to listOf("10"), "driveFrequency" to listOf("DAILY"), "glareOncoming" to listOf("OFTEN"), "outdoorHours" to listOf("1"))

    @Test fun `every reason code the engine can give has wording in both languages`() {
        val src = File("../engine/src/main/kotlin/com/drishtisaathi/engine/profile/LensEngine.kt").readText()
        val codes = Regex("""Reason\("([A-Z_]+)"""").findAll(src).map { it.groupValues[1] }.toSet()
        assertTrue(codes.size >= 15)
        assertEquals(codes, SProfile.reasonCodes)
        assertEquals("Fits your budget (medium)", SProfile.reason("BUDGET", listOf("MID")).en)
        assertEquals("You drive every day", SProfile.reason("DRIVES", listOf("DAILY")).en)
        assertEquals("आप रोज़ 6 घंटे स्क्रीन देखते हैं", SProfile.reason("DIGITAL", listOf("6")).hi)
        assertEquals(listOf("You do outdoor sport"), LensText.why(com.drishtisaathi.engine.profile.Suggestion(
            com.drishtisaathi.engine.profile.SuggestionKind.SECOND_PAIR, "sun_polarised", "x", "y", reasons = listOf(Reason("OUTDOOR_SPORT")))).map { it.en })
    }

    @Test fun `the shop PDF carries the rings and the lens cards with this customer's reasons`() {
        val st = f.state(itWorker, itSurvey)
        val view = ProfileView.build(st, f.rules, engine)
        assertEquals("anti_fatigue", view.plan.type!!.id)
        val shop = f.texts(PdfContent.shopCopy(st, f.rules, f.ctx, Lang.EN, view = view))
        assertTrue(SProfile.profileTitle.en in shop)
        assertTrue(shop.toString(), "Digital: Digital Native (100)" in shop)
        assertTrue("Mobility: Daily (100) · Night-glare sensitive" in shop)
        assertTrue("Suggested ZEISS lens family – optician confirms" in shop)
        assertTrue("Your lens: ZEISS Anti-fatigue (Digital) lenses" in shop)
        assertTrue("You are 35 and your eyes get tired after 10 h of screens; the small boost at the bottom eases phone-to-far switching." in shop)
        assertTrue("In the lens: ZEISS BlueGuard" in shop)
        assertTrue(shop.any { it.startsWith("A second pair: ZEISS Office lenses") })
        // Hindi: the customer's own reasons are in Hindi
        val hi = f.texts(PdfContent.shopCopy(st, f.rules, f.ctx, Lang.HI, view = view))
        assertTrue(hi.any { it.contains("आपकी उम्र 35 है") })
        assertTrue(SProfile.lensLabel.hi in hi)
    }

    @Test fun `the optometrist report carries the rings, the lens ids and the reasons`() {
        val st = f.state(itWorker, itSurvey)
        val p = BridgeProfile.build(st, f.rules, f.consent, "t", ProfileView.build(st, f.rules, engine))
        val vp = p["visionProfile"]!!.jsonObject
        assertEquals("Digital Native", vp["digital"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals("true", vp["nightGlareSensitive"]!!.jsonPrimitive.content)
        val lens = p["lens"]!!.jsonObject
        assertEquals("Suggested ZEISS lens family – optician confirms", lens["label"]!!.jsonPrimitive.content)
        val ids = lens["ids"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(ids.toString(), ids.containsAll(listOf("anti_fatigue", "smartlife", "uvprotect", "blueguard", "office")))
        assertEquals("10", p["survey"]!!.jsonObject["values"]!!.jsonObject["screenHoursDay"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(emptyList<String>(), BridgeProfile.forbiddenPaths(p))
        assertTrue(p.toString().toByteArray().size < 256 * 1024)
    }

    @Test fun `a red flag or child myopia - rings stay, lens cards go`() {
        for (a in listOf(itWorker.copy(redFlagFlashes = true), itWorker.copy(age = 10))) {
            val st = f.state(a, itSurvey)
            val view = ProfileView.build(st, f.rules, engine)
            assertFalse(view.plan.allowed); assertTrue(view.plan.all.isEmpty())
            val shop = f.texts(PdfContent.shopCopy(st, f.rules, f.ctx, Lang.EN, view = view))
            assertTrue(SProfile.profileTitle.en in shop)
            assertFalse(SProfile.lensLabel.en in shop)
            assertFalse(shop.any { it.contains("ZEISS") })
            val lens = BridgeProfile.build(st, f.rules, f.consent, "t", view)["lens"]!!.jsonObject
            assertEquals("false", lens["suggested"]!!.jsonPrimitive.content)
            assertEquals(0, lens["ids"]!!.jsonArray.size)
        }
    }

    @Test fun `a child without myopia gets a children's lens family labelled optometrist confirms`() {
        val clear = QuickEstimate(f.eye(null), f.eye(null))
        val st = f.state(itWorker.copy(age = 10), SurveyAnswers(), clear)
        val view = ProfileView.build(st, f.rules, engine)
        assertTrue(view.plan.allowed && view.plan.optometristConfirms)
        assertEquals("smartlife_young", view.plan.design!!.id)
        val shop = f.texts(PdfContent.shopCopy(st, f.rules, f.ctx, Lang.EN, view = view))
        assertTrue(SProfile.optometristConfirms.en in shop)
    }

    @Test fun `rings nobody answered say so`() {
        val view = ProfileView.build(f.state(itWorker), f.rules, engine)
        val lines = LensText.ringLines(view.profile).map { it.en }
        assertTrue(lines.toString(), "Digital: not answered" in lines)
    }
}
