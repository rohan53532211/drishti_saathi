package com.drishtisaathi.profile

import com.drishtisaathi.UiState
import com.drishtisaathi.bridge.BridgeConsent
import com.drishtisaathi.bridge.BridgeProfile
import com.drishtisaathi.bridge.QueueStore
import com.drishtisaathi.bridge.ReportQueue
import com.drishtisaathi.bridge.SendResult
import com.drishtisaathi.engine.DrishtiEngine
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.QuickEstimate
import com.drishtisaathi.engine.QuickEye
import com.drishtisaathi.engine.QuickTarget
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.VisionProblem
import com.drishtisaathi.engine.profile.RedFlag
import com.drishtisaathi.engine.profile.ReportTag
import com.drishtisaathi.engine.profile.Safety
import com.drishtisaathi.engine.profile.SurveyAnswers
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S
import com.drishtisaathi.i18n.SProfile
import com.drishtisaathi.interview.ChoiceContext
import com.drishtisaathi.pdf.Block
import com.drishtisaathi.pdf.PdfContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Shared fixtures for the vision-profile tests. */
object ProfileFixtures {
    private const val A = "src/main/assets/"
    val rules: RulesConfig by lazy { RulesConfig.parse(File(A + "rules.json").readText()) }
    val engine: DrishtiEngine by lazy {
        DrishtiEngine.fromJson(File(A + "rules.json").readText(), File(A + "anthro_priors.json").readText(),
            File(A + "frames.json").readText(), File(A + "frames/more_frames.json").readText())
    }
    val ctx = ChoiceContext(1500, 4000)
    val consent = BridgeConsent(1_762_934_400_000, spoken = true, tapped = true)
    fun eye(se: Double?) = QuickEye(se, se?.let { -100.0 / it }, QuickTarget.WHITE, noMyopiaAtReach = se == null,
        rangeFromD = se?.plus(0.25), rangeToD = se?.minus(0.25))

    fun state(a: InterviewAnswers, survey: SurveyAnswers = SurveyAnswers(), quick: QuickEstimate? = QuickEstimate(eye(-2.0), eye(-1.75))): UiState =
        UiState(answers = a, survey = survey, quick = quick, result = engine.evaluate(a, quick = quick), lang = Lang.EN)

    fun texts(blocks: List<Block>): List<String> = blocks.mapNotNull {
        when (it) { is Block.Para -> it.text; is Block.Bullet -> it.text; is Block.Heading -> it.text; is Block.Title -> it.text; else -> null }
    }
}

/** Phase A: what the red flag and child-myopia rules do to the PDFs and the optometrist report, and the report queue. */
class SafetyOutputTest {
    private val f = ProfileFixtures
    private val adult = InterviewAnswers(age = 34, visionProblem = VisionProblem.DISTANCE, distanceBlur = true, redFlagFlashes = false, redFlagOther = false)

    @Test fun `every red flag - no lens on the shop PDF, power for the optometrist only, report URGENT with the reasons first`() {
        for (flag in RedFlag.pickable) {
            val flashes = flag in setOf(RedFlag.NEW_FLASHES, RedFlag.NEW_FLOATERS, RedFlag.CURTAIN_SHADOW)
            val a = adult.copy(redFlagFlashes = flashes, redFlagOther = !flashes)
            val st = f.state(a, SurveyAnswers().with(Safety.SIGNS_QUESTION, listOf(flag.name)))
            val shop = f.texts(PdfContent.shopCopy(st, f.rules, f.ctx, Lang.EN))
            assertEquals("$flag", 1, shop.count { it == SProfile.seeDoctorToday.en })
            assertTrue("$flag reason", SProfile.redFlag(flag).en in shop)
            assertTrue("$flag label", SProfile.forOptometristOnly.en in shop)
            assertFalse("$flag: no lens package", S.lensPackage.en in shop)
            assertFalse("$flag: no frames", S.suggestedFrames.en in shop)
            assertTrue(shop.any { it.contains("URGENT") })

            val customer = f.texts(PdfContent.customerCopy(st, f.rules))
            assertFalse(S.lensPackage.hi in customer)
            assertTrue(SProfile.seeDoctorToday.hi in customer)

            val p = BridgeProfile.build(st, f.rules, f.consent, "t")
            assertEquals("the triage block is the first key", "triage", p.keys.first())
            val t = p["triage"]!!.jsonObject
            assertEquals("URGENT", t["tag"]!!.jsonPrimitive.content)
            assertEquals(listOf(flag.name), t["redFlags"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("false", t["lensSuggested"]!!.jsonPrimitive.content)
            assertEquals(0, p["estimates"]!!.jsonObject["suggestedFrames"]!!.jsonArray.size)
            assertEquals(emptyList<String>(), BridgeProfile.forbiddenPaths(p))
        }
    }

    @Test fun `child with minus power - measured, referral only, tag CHILD_MYOPIA`() {
        val st = f.state(adult.copy(age = 10))
        val gate = st.gate(f.rules)
        assertEquals(ReportTag.CHILD_MYOPIA, gate.tag)
        val shop = f.texts(PdfContent.shopCopy(st, f.rules, f.ctx, Lang.EN))
        assertTrue(SProfile.childMyopiaTitle.en in shop)
        assertFalse(S.lensPackage.en in shop)
        assertTrue("the measured power is still printed for the optometrist", shop.any { it.startsWith("For the optometrist only: R ") })
        val t = BridgeProfile.build(st, f.rules, f.consent, "t")["triage"]!!.jsonObject
        assertEquals("CHILD_MYOPIA", t["tag"]!!.jsonPrimitive.content)
        assertEquals("true", t["childMyopia"]!!.jsonPrimitive.content)
    }

    @Test fun `a routine adult keeps the lens package and is tagged ROUTINE`() {
        val st = f.state(adult)
        val shop = f.texts(PdfContent.shopCopy(st, f.rules, f.ctx, Lang.EN))
        assertTrue(S.lensPackage.en in shop)
        assertFalse(SProfile.seeDoctorToday.en in shop)
        assertEquals("ROUTINE", BridgeProfile.build(st, f.rules, f.consent, "t")["triage"]!!.jsonObject["tag"]!!.jsonPrimitive.content)
    }

    // ---- the report is always queued for the bridge ----

    private class MemoryStore(var ids: Set<Long> = emptySet()) : QueueStore {
        override fun load() = ids
        override fun save(ids: Set<Long>) { this.ids = ids }
    }

    @Test fun `a report is queued at once, survives a restart and is sent when the backend connects`() = runBlocking {
        val store = MemoryStore()
        val q = ReportQueue(store)
        q.enqueue(7); q.enqueue(9); q.enqueue(7)
        assertEquals(2, q.pending.value)

        // offline: nothing is lost, the pass stops at the first failure
        val tried = mutableListOf<Long>()
        assertEquals(0, q.flush { tried += it; SendResult.RETRY_LATER })
        assertEquals(listOf(7L), tried)
        assertEquals(2, q.pending.value)

        // the app restarts: the queue is read back from the store
        val again = ReportQueue(store)
        assertEquals(2, again.pending.value)
        assertTrue(again.isQueued(9))

        // the backend connects: everything goes, oldest first
        val sent = mutableListOf<Long>()
        assertEquals(2, again.flush { sent += it; SendResult.SENT })
        assertEquals(listOf(7L, 9L), sent)
        assertEquals(0, again.pending.value)
        assertEquals(emptySet<Long>(), store.ids)
    }

    @Test fun `a deleted session leaves the queue, a rejected one is dropped, later ones still go`() = runBlocking {
        val q = ReportQueue(MemoryStore())
        listOf(1L, 2L, 3L).forEach(q::enqueue)
        q.remove(1)
        val sent = q.flush { if (it == 2L) SendResult.DROP else SendResult.SENT }
        assertEquals(1, sent)
        assertEquals(0, q.pending.value)
    }

    @Test fun `urgent, child and routine sessions are all queued - nothing is held back`() {
        // the view model queues every non-demo result; the tag only marks the report
        val q = ReportQueue(MemoryStore())
        val gates = listOf(adult.copy(redFlagFlashes = true), adult.copy(age = 10), adult).map { f.state(it).gate(f.rules) }
        assertEquals(listOf(ReportTag.URGENT, ReportTag.CHILD_MYOPIA, ReportTag.ROUTINE), gates.map { it.tag })
        gates.forEachIndexed { i, _ -> q.enqueue(i + 1L) }
        assertEquals(3, q.pending.value)
    }
}
