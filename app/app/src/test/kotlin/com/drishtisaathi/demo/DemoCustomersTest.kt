package com.drishtisaathi.demo

import com.drishtisaathi.UiState
import com.drishtisaathi.data.Measurements
import com.drishtisaathi.data.SessionCodec
import com.drishtisaathi.data.SessionEntity
import com.drishtisaathi.engine.DrishtiEngine
import com.drishtisaathi.engine.ReferralLevel
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.VisionTests
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.interview.ChoiceContext
import com.drishtisaathi.interview.Questions
import com.drishtisaathi.pdf.Block
import com.drishtisaathi.pdf.PdfContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DemoCustomersTest {
    private fun asset(name: String) = File("src/main/assets/$name").readText()
    private val rules = RulesConfig.parse(asset("rules.json"))
    private val engine = DrishtiEngine.fromJson(
        asset("rules.json"), asset("anthro_priors.json"), asset("frames.json"), asset("frames/more_frames.json"),
    )
    private val ctx = ChoiceContext(rules.frameScoring.budgetMaxPriceInrLow, rules.frameScoring.budgetMaxPriceInrMid)

    /** Mirrors SessionViewModel.loadDemo + computeResult. */
    private fun state(c: DemoCustomer, demo: Boolean = true) = UiState(
        answers = c.answers,
        answered = Questions.visible(c.answers).map { it.id }.toSet(),
        face = c.face,
        tests = c.tests ?: VisionTests(),
        testsDone = c.tests != null,
        demo = demo,
        sessionId = 7,
        createdAt = 0,
        result = engine.evaluate(c.answers, tests = c.tests ?: VisionTests(), face = c.face),
    )

    private fun text(blocks: List<Block>) = blocks.joinToString("\n") {
        when (it) {
            is Block.Title -> it.text; is Block.Heading -> it.text; is Block.Para -> it.text; is Block.Bullet -> it.text; Block.Rule -> "---"
        }
    }

    @Test fun `three sample customers, each answers every visible question`() {
        assertEquals(3, DemoCustomers.all.size)
        assertEquals(3, DemoCustomers.all.map { it.id }.toSet().size)
        DemoCustomers.all.forEach { c -> assertTrue(c.id, state(c).allAnswered) }
    }

    @Test fun `21-year-old myope gets a measured minus band and frames, no referral`() {
        val r = state(DemoCustomers.youngMyope).result!!
        assertEquals(ReferralLevel.NONE, r.assessment.referral.level)
        val adv = r.assessment.advice!!
        assertEquals(2, adv.eyes.size)
        adv.eyes.forEach { assertTrue(it.startingTrialLens.value.sph < -1.0 && it.startingTrialLens.value.sph > -3.0) }
        assertNull(adv.add)
        assertTrue(r.frames.isNotEmpty())
        assertEquals(61.5, r.assessment.pdMm.value, 1e-9) // card-calibrated camera PD used
    }

    @Test fun `48-year-old gets a reading add with the ready-reader check`() {
        val adv = state(DemoCustomers.readingNeed).result!!.assessment.advice!!
        assertNotNull(adv.add)
        assertTrue(adv.add!!.value.lowD >= 0.75)
        assertTrue(adv.confirmAddWithReadyReader)
    }

    @Test fun `flashes case is an urgent referral with advice withheld`() {
        val a = state(DemoCustomers.urgentFlashes).result!!.assessment
        assertEquals(ReferralLevel.URGENT, a.referral.level)
        assertNull(a.advice)
    }

    @Test fun `both PDFs are marked DEMO, real sessions are not`() {
        for (c in DemoCustomers.all) {
            val s = state(c)
            val shop = text(PdfContent.shopCopy(s, rules, ctx, Lang.EN))
            val customer = text(PdfContent.customerCopy(s, rules))
            assertTrue(shop.startsWith("DEMO"))
            assertTrue(customer.startsWith("DEMO"))
            assertTrue(shop.contains(com.drishtisaathi.i18n.SFlow.demoPdfLine.en))
            assertTrue(customer.contains(com.drishtisaathi.i18n.SFlow.demoPdfLine.hi))
        }
        val real = state(DemoCustomers.youngMyope, demo = false)
        assertFalse(text(PdfContent.shopCopy(real, rules, ctx, Lang.EN)).contains("DEMO"))
        assertFalse(text(PdfContent.customerCopy(real, rules)).contains("DEMO"))
    }

    @Test fun `demo flag survives storage, old sessions decode as not demo`() {
        val e = SessionEntity(
            createdAt = 0, lang = "EN", phoneModel = "x", answersJson = "{}", staffJson = "{}", resultJson = "{}",
            referralLevel = "NONE", measurementsJson = SessionCodec.encode(Measurements(demo = true)),
        )
        assertTrue(SessionCodec.measurements(e).demo)
        assertFalse(SessionCodec.measurements(e.copy(measurementsJson = null)).demo)
        assertFalse(SessionCodec.measurements(e.copy(measurementsJson = """{"face":null,"tests":null}""")).demo)
    }
}
