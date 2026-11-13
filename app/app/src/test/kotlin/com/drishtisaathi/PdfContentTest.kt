package com.drishtisaathi

import com.drishtisaathi.engine.Budget
import com.drishtisaathi.engine.DrishtiEngine
import com.drishtisaathi.engine.EyeRx
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.KnownRx
import com.drishtisaathi.engine.Occupation
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.ScreenHours
import com.drishtisaathi.engine.StylePref
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S
import com.drishtisaathi.interview.ChoiceContext
import com.drishtisaathi.interview.Questions
import com.drishtisaathi.pdf.Block
import com.drishtisaathi.pdf.PdfContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** PDF text must carry the disclaimer and obey the Section 6 wording rules, in both copies. */
class PdfContentTest {
    private val assets = File("src/main/assets")
    private fun asset(n: String) = File(assets, n).readText()
    private val rules = RulesConfig.parse(asset("rules.json"))
    private val engine = DrishtiEngine.fromJson(asset("rules.json"), asset("anthro_priors.json"), asset("frames.json"))
    private val ctx = ChoiceContext(1500, 4000)

    private fun state(a: InterviewAnswers): UiState = UiState(
        answers = a,
        answered = Questions.visible(a).map { it.id }.toSet(),
        result = engine.evaluate(a),
        sessionId = 7,
        createdAt = 0,
    )

    private val myope = InterviewAnswers(
        age = 48, wearsGlasses = true, glassesAgeYears = 1, knownRx = KnownRx(EyeRx(-4.5, -0.75, 180), EyeRx(-4.0)),
        distanceBlur = true, nearBlur = true, screenHours = ScreenHours.H4_PLUS, nightDriving = true, outdoorWork = false,
        occupation = Occupation.OFFICE_COMPUTER, headacheStrain = false, diabetesOrBp = false,
        redFlagFlashes = false, redFlagOther = false, budget = Budget.MID, style = StylePref.ANY,
    )

    private fun text(blocks: List<Block>) = blocks.joinToString("\n") {
        when (it) {
            is Block.Title -> it.text; is Block.Heading -> it.text; is Block.Para -> it.text; is Block.Bullet -> it.text; is Block.FrameRow -> it.name + " " + it.line; Block.Rule -> "---"
        }
    }

    private val bannedEn = Regex("""\b(prescription|diagnos\w*|power is)\b""", RegexOption.IGNORE_CASE)

    /** Removes the allowed occurrences (disclaimer, and the brief's old-slip question) before checking. */
    private fun assertWording(t: String) {
        val stripped = t.replace(S.disclaimerBoth, "").replace(S.qKnownRx.hi, "").replace(S.qKnownRx.en, "")
        assertFalse("banned English in: $stripped", bannedEn.containsMatchIn(stripped))
        listOf("पर्चा", "निदान", "पावर है").forEach { assertFalse("banned Hindi '$it'", it in stripped) }
    }

    @Test fun `shop copy has disclaimer, starting lens, frames and passes wording rules`() {
        for (l in Lang.entries) {
            val t = text(PdfContent.shopCopy(state(myope), rules, ctx, l))
            assertTrue(t.contains(S.disclaimerBoth))
            assertTrue(t.contains("−4.50 / −0.75 × 180"))
            assertTrue(t.contains(S.startingTrialLens[l]))
            assertWording(t)
        }
    }

    @Test fun `customer copy is Hindi, has disclaimer, and carries no power numbers`() {
        val t = text(PdfContent.customerCopy(state(myope), rules))
        assertTrue(t.contains(S.disclaimerBoth))
        assertTrue(t.contains(S.customerCopy.hi))
        assertFalse(t.contains("−4.50"))
        assertFalse(t.contains(" D "))
        assertWording(t)
    }

    @Test fun `saved frames - shop and Hindi customer copies list name, size and price, no power numbers`() {
        val saved = listOf(
            com.drishtisaathi.data.SavedFrameEntity(9, "k-a1", "Round Black", "50-20-145", true, 899, "eyeglasses", 1),
            com.drishtisaathi.data.SavedFrameEntity(9, "shop-7", "Shop Aviator", "56-16-140", false, 2450, "eyeglasses", 2),
        )
        val shopBlocks = PdfContent.shopCopy(state(myope), rules, ctx, Lang.EN, saved)
        val rows = shopBlocks.filterIsInstance<Block.FrameRow>()
        assertEquals(listOf("k-a1", "shop-7"), rows.map { it.frameId })
        assertEquals("50-20-145* · ₹899", rows[0].line)
        assertEquals("56-16-140 · ₹2,450", rows[1].line)
        assertTrue(text(shopBlocks).contains(com.drishtisaathi.i18n.S3D.savedFrames.en))
        val cust = text(PdfContent.customerCopy(state(myope), rules, saved))
        assertTrue(cust.contains(com.drishtisaathi.i18n.S3D.savedFrames.hi))
        assertTrue(cust.contains("Shop Aviator 56-16-140 · ₹2,450"))
        assertFalse(cust.contains("−4.50"))
        assertFalse(cust.contains(" D "))
        assertWording(cust)
        // nothing saved: no section
        assertTrue(PdfContent.customerCopy(state(myope), rules).none { it is Block.FrameRow })
    }

    @Test fun `urgent referral - one red banner at the top, and no lens or frame is suggested (Phase A)`() {
        val urgent = myope.copy(redFlagFlashes = true)
        val st = state(urgent)
        assertTrue(st.result!!.frames.isNotEmpty())
        val shopBlocks = PdfContent.shopCopy(st, rules, ctx, Lang.EN)
        val shop = text(shopBlocks)
        val P = com.drishtisaathi.i18n.SProfile
        assertEquals(1, Regex(Regex.escape(P.seeDoctorToday.en)).findAll(shop).count())
        assertFalse(shop.contains(S.adviceWithheld.en))
        assertFalse(shop.contains(S.lensPackage.en))
        assertFalse(shop.contains(S.suggestedFrames.en))
        assertTrue(shop.contains(P.noLensToday.en))
        assertEquals(0, shopBlocks.count { it is Block.Heading && it.text == S.referral.en })   // no second referral section
        val cust = text(PdfContent.customerCopy(st, rules))
        assertFalse(cust.contains(S.urgentBody.hi))                                               // no duplicate urgent section
        assertEquals(1, Regex(Regex.escape(P.seeDoctorToday.hi)).findAll(cust).count())
        assertFalse(cust.contains(S.lensPackage.hi))
        assertFalse(cust.contains(S.suggestedFrames.hi))
        assertFalse(cust.contains(" D "))                                                         // still no power numbers
    }

    @Test fun `disclaimer appears at top and bottom`() {
        val blocks = PdfContent.shopCopy(state(myope), rules, ctx, Lang.HI)
        assertEquals(2, blocks.count { it is Block.Para && it.text == S.disclaimerBoth })
    }
}
