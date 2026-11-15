package com.drishtisaathi.profile

import com.drishtisaathi.UiState
import com.drishtisaathi.bridge.BridgeProfile
import com.drishtisaathi.bridge.QueueStore
import com.drishtisaathi.bridge.ReportQueue
import com.drishtisaathi.bridge.SendResult
import com.drishtisaathi.engine.Eye
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.QuickEstimate
import com.drishtisaathi.engine.VisionProblem
import com.drishtisaathi.engine.profile.AmslerCell
import com.drishtisaathi.engine.profile.AmslerEyeResult
import com.drishtisaathi.engine.profile.LensAssisted
import com.drishtisaathi.engine.profile.LensCatalog
import com.drishtisaathi.engine.profile.LensEngine
import com.drishtisaathi.engine.profile.RedFlag
import com.drishtisaathi.engine.profile.Safety
import com.drishtisaathi.engine.profile.ScreeningResults
import com.drishtisaathi.engine.profile.SurveyAnswers
import com.drishtisaathi.engine.profile.SurveyQuestion
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S
import com.drishtisaathi.i18n.SProfile
import com.drishtisaathi.i18n.STele
import com.drishtisaathi.i18n.TxtRegistry
import com.drishtisaathi.pdf.PdfContent
import com.drishtisaathi.teleconsult.TeleText
import com.drishtisaathi.vision.PowerRange
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Test pass before submission, app side: what every red flag does to the PDFs, the QR, the optometrist report and
 * the report queue; the power from a blur distance for 10 000 random and faulty distances; both languages.
 */
class FullPassAppTest {
    private val f = ProfileFixtures
    private val lensEngine = LensEngine(LensCatalog.parse(File("src/main/assets/lens_catalog.json").readText()), f.rules.lensEngine)
    private val adult = InterviewAnswers(age = 40, visionProblem = VisionProblem.DISTANCE, distanceBlur = true, redFlagFlashes = false, redFlagOther = false)
    private fun signs(flag: RedFlag) = SurveyAnswers().with(Safety.SIGNS_QUESTION, listOf(flag.name))
    private fun grid(eye: Eye) = ScreeningResults(amsler = listOf(AmslerEyeResult(eye, true, listOf(AmslerCell(3, 4)), 12, 20)))

    private data class Row(val name: String, val flag: RedFlag, val state: UiState)

    private val rows: List<Row> = listOf(
        Row("sudden vision loss", RedFlag.SUDDEN_VISION_LOSS, f.state(adult.copy(redFlagOther = true), signs(RedFlag.SUDDEN_VISION_LOSS))),
        Row("pain / redness", RedFlag.EYE_PAIN_REDNESS, f.state(adult.copy(redFlagOther = true), signs(RedFlag.EYE_PAIN_REDNESS))),
        Row("new flashes", RedFlag.NEW_FLASHES, f.state(adult.copy(redFlagFlashes = true), signs(RedFlag.NEW_FLASHES))),
        Row("new floaters", RedFlag.NEW_FLOATERS, f.state(adult.copy(redFlagFlashes = true), signs(RedFlag.NEW_FLOATERS))),
        Row("curtain / shadow", RedFlag.CURTAIN_SHADOW, f.state(adult.copy(redFlagFlashes = true), signs(RedFlag.CURTAIN_SHADOW))),
        Row("double vision", RedFlag.DOUBLE_VISION, f.state(adult, SurveyAnswers().with(Safety.DOUBLE_VISION_QUESTION, listOf(SurveyQuestion.YES)))),
        Row("grid distortion, right eye", RedFlag.AMSLER_DISTORTION, f.state(adult).copy(screening = grid(Eye.RIGHT))),
        Row("grid distortion, left eye", RedFlag.AMSLER_DISTORTION, f.state(adult).copy(screening = grid(Eye.LEFT))),
        Row("bent lines reported", RedFlag.BENT_LINES_REPORTED, f.state(adult.copy(straightLinesBent = true))),
    )

    private class MemoryStore(var ids: Set<Long> = emptySet()) : QueueStore {
        override fun load() = ids
        override fun save(ids: Set<Long>) { this.ids = ids }
    }

    // ---- safety matrix: outputs ----

    @Test fun `every red flag alone - doctor today, no lens and no frame anywhere, power for the optometrist only, report URGENT`() {
        for (r in rows) {
            val st = r.state
            val gate = st.gate(f.rules)
            assertEquals(r.name, listOf(r.flag), gate.redFlags)
            val view = ProfileView.build(st, f.rules, lensEngine)
            assertFalse(r.name, view.plan.allowed); assertEquals(r.name, emptyList<String>(), view.plan.ids)

            // shop PDF and customer PDF
            val shop = f.texts(PdfContent.shopCopy(st, f.rules, f.ctx, Lang.EN))
            assertEquals("${r.name}: doctor today once", 1, shop.count { it == SProfile.seeDoctorToday.en })
            assertTrue("${r.name}: reason", shop.any { SProfile.redFlag(r.flag).en in it })
            assertTrue("${r.name}: label", shop.any { it.startsWith(SProfile.forOptometristOnly.en) })
            assertFalse("${r.name}: lens package", S.lensPackage.en in shop)
            assertFalse("${r.name}: frames", S.suggestedFrames.en in shop)
            assertTrue(shop.any { "URGENT" in it })
            // the customer's copy is always in Hindi
            val customer = f.texts(PdfContent.customerCopy(st, f.rules))
            assertTrue(r.name, SProfile.seeDoctorToday.hi in customer)
            assertTrue("${r.name}: doctor today comes before anything else but the title, date and disclaimer", customer.indexOf(SProfile.seeDoctorToday.hi) <= 3)
            assertFalse("${r.name}: lens package", S.lensPackage.hi in customer)
            assertFalse("${r.name}: frames", S.suggestedFrames.hi in customer)

            // the words shared by QR / WhatsApp: no lens line, the power only as "for the optometrist"
            val share = QrPayload.shareLines(st, view, f.rules).map { it.en }
            assertTrue("${r.name}: share starts the urgent part", share.any { SProfile.seeDoctorToday.en in it })
            assertFalse("${r.name}: share has a lens", share.any { "ZEISS" in it })
            val qr = QrPayload.json(st, view, f.rules, "t")
            assertEquals(emptyList<String>(), BridgeProfile.forbiddenPaths(qr))

            // the optometrist report: triage first, URGENT, the flag, no lens, no frames
            val p = BridgeProfile.build(st, f.rules, f.consent, "t")
            assertEquals("triage", p.keys.first())
            val t = p["triage"]!!.jsonObject
            assertEquals(r.name, "URGENT", t["tag"]!!.jsonPrimitive.content)
            assertEquals(listOf(r.flag.name), t["redFlags"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("false", t["lensSuggested"]!!.jsonPrimitive.content)
            assertEquals(0, p["estimates"]!!.jsonObject["suggestedFrames"]!!.jsonArray.size)
            assertEquals(gate.emergency.toString(), t["emergency"]!!.jsonPrimitive.content)
            assertEquals(emptyList<String>(), BridgeProfile.forbiddenPaths(p))
        }
    }

    @Test fun `every urgent report is queued, kept while offline and sent after reconnect`() = runBlocking {
        val store = MemoryStore()
        val q = ReportQueue(store)
        rows.forEachIndexed { i, _ -> q.enqueue(100L + i) }
        assertEquals(rows.size, q.pending.value)
        assertEquals("offline: nothing leaves the queue", 0, q.flush { SendResult.RETRY_LATER })
        assertEquals(rows.size, ReportQueue(store).pending.value)                      // also after the app restarts
        val sent = mutableListOf<Long>()
        assertEquals(rows.size, ReportQueue(store).flush { sent += it; SendResult.SENT })
        assertEquals(rows.indices.map { 100L + it }, sent)
        assertEquals(emptySet<Long>(), store.ids)
    }

    @Test fun `children 3, 8, 12 and 17 with a minus power - referral only, power for the optometrist, tag CHILD_MYOPIA - and a red flag on top wins`() {
        for (age in listOf(3, 8, 12, 17)) {
            val st = f.state(adult.copy(age = age))
            val view = ProfileView.build(st, f.rules, lensEngine)
            assertFalse("age $age", view.plan.allowed); assertEquals(emptyList<String>(), view.plan.ids)
            val shop = f.texts(PdfContent.shopCopy(st, f.rules, f.ctx, Lang.EN))
            assertTrue("age $age", SProfile.childMyopiaTitle.en in shop)
            assertFalse(S.lensPackage.en in shop)
            assertTrue(shop.any { it.startsWith("For the optometrist only: R ") })
            val t = BridgeProfile.build(st, f.rules, f.consent, "t")["triage"]!!.jsonObject
            assertEquals("CHILD_MYOPIA", t["tag"]!!.jsonPrimitive.content); assertEquals("true", t["childMyopia"]!!.jsonPrimitive.content)

            val both = f.state(adult.copy(age = age, redFlagOther = true), signs(RedFlag.EYE_PAIN_REDNESS))
            val tri = BridgeProfile.build(both, f.rules, f.consent, "t")["triage"]!!.jsonObject
            assertEquals("age $age: urgent wins", "URGENT", tri["tag"]!!.jsonPrimitive.content)
            assertEquals("age $age: still marked as child myopia", "true", tri["childMyopia"]!!.jsonPrimitive.content)
            assertTrue(SProfile.seeDoctorToday.en in f.texts(PdfContent.shopCopy(both, f.rules, f.ctx, Lang.EN)))
        }
        // 17 without a minus power: a lens for children, and every card says the optometrist confirms
        val clear = f.state(adult.copy(age = 17, visionProblem = VisionProblem.NONE, distanceBlur = false), quick = QuickEstimate(f.eye(null), f.eye(null)))
        val view = ProfileView.build(clear, f.rules, lensEngine)
        assertTrue(view.plan.allowed); assertTrue(view.plan.optometristConfirms); assertEquals("smartlife_young", view.plan.design?.id)
    }

    @Test fun `an adult without a red flag gets the lens package and every suggestion has a why line in both languages`() {
        val st = f.state(adult)
        val view = ProfileView.build(st, f.rules, lensEngine)
        assertTrue(view.plan.allowed); assertTrue(view.plan.all.size >= 4)
        for (s in view.plan.all) {
            val why = LensText.why(s)
            assertTrue("${s.id} has no why", why.isNotEmpty())
            assertTrue(s.id, why.all { it.en.isNotBlank() && it.hi.isNotBlank() })
        }
        assertTrue(S.lensPackage.en in f.texts(PdfContent.shopCopy(st, f.rules, f.ctx, Lang.EN)))
        assertEquals("ROUTINE", BridgeProfile.build(st, f.rules, f.consent, "t")["triage"]!!.jsonObject["tag"]!!.jsonPrimitive.content)
    }

    @Test fun `teleconsult - an urgent case shows 'see an eye doctor today' first, then the reasons, then 'never replaces an urgent visit'`() {
        val lines = TeleText.urgentLines(listOf("New flashes of light", " "))
        assertEquals(listOf("Please see an eye doctor today", "New flashes of light", "A teleconsult never replaces an urgent visit."), lines.map { it.en })
        assertEquals(SProfile.seeDoctorToday, lines.first()); assertEquals(STele.neverReplaces, lines.last())
        assertTrue(lines.first().hi.isNotBlank() && lines.last().hi.isNotBlank())
        assertEquals(2, TeleText.urgentLines().size)
        // the screens take their urgent text from this one place
        val screens = File("src/main/kotlin/com/drishtisaathi/teleconsult/TeleScreens.kt").readText()
        assertEquals("the urgent note, the consult card", 2, Regex("""TeleText\.urgentLines\(""").findAll(screens).count())
        assertFalse("no second copy of the wording in the screens", "SProfile.seeDoctorToday" in screens || "STele.neverReplaces" in screens)
        // in both places the urgent note is the first thing in the column
        assertTrue(Regex("""if \(urgent\) UrgentTeleNote\(lang, redFlagReasons\)\s+Text\(STele\.shared""").containsMatchIn(screens))
        assertTrue(Regex("""if \(cur\.urgent\) UrgentTeleNote\(lang, cur\.redFlagReasons\)\s+SoftCard""").containsMatchIn(screens))
    }

    // ---- shop mode: nothing personal in the QR or the report ----

    @Test fun `shop mode - the QR and the optometrist report carry no name, photo or phone for any session`() {
        val states = rows.map { it.state } + f.state(adult) + f.state(adult.copy(age = 10)) + f.state(adult.copy(age = 64, visionProblem = VisionProblem.BOTH))
        val personal = Regex("""(?i)"(name|customerName|patientName|phone|mobile|photo|image|selfie|email|address)"\s*:""")
        for (st in states) {
            val view = ProfileView.build(st, f.rules, lensEngine)
            val qr = QrPayload.json(st, view, f.rules, "1.2")
            val report = BridgeProfile.build(st, f.rules, f.consent, "1.2")
            for ((what, json) in listOf("QR" to qr, "report" to report)) {
                assertEquals(what, emptyList<String>(), BridgeProfile.forbiddenPaths(json))
                assertNull("$what has a personal field: ${personal.find(json.toString())?.value}", personal.find(json.toString()))
                assertFalse("$what carries a picture", "data:image" in json.toString() || "base64" in json.toString())
            }
            assertTrue("the QR stays small enough to scan", qr.toString().length < 2900)
        }
        for (key in listOf("name", "phone", "photo")) assertTrue(key, BridgeProfile.forbiddenKeys.any { key in it.lowercase() })
    }

    // ---- power from a blur distance ----

    @Test fun `power - 10 000 blur distances from 5 cm to 600 cm give a finite minus power on the 0_25 grid with an ordered range`() {
        val rnd = Random(20261115)
        repeat(10_000) { n ->
            val mm = when (n) { 0 -> 50.0; 1 -> 6000.0; 2 -> 1000.0; 3 -> 267.0; else -> rnd.nextDouble(50.0, 6000.0) }
            val e = PowerRange.farEye(mm)
            val se = e.seD!!
            assertTrue("$mm mm -> $se", se.isFinite() && e.rangeFromD!!.isFinite() && e.rangeToD!!.isFinite())
            assertTrue("$mm mm -> $se is never plus", se <= 0.0)
            assertTrue("$mm mm -> $se on the grid", abs(se / 0.25 - (se / 0.25).roundToInt()) < 1e-9)
            assertEquals("$mm mm: -1/d within half a step", -1000.0 / mm, se, 0.125 + 1e-9)
            assertTrue("$mm mm: range ordered", minOf(e.rangeFromD!!, e.rangeToD!!) <= se && se <= maxOf(e.rangeFromD!!, e.rangeToD!!))
            assertEquals(0.5, abs(e.rangeFromD!! - e.rangeToD!!), 1e-9)
            assertTrue(se >= -20.0)
            // the same distance twice is the same power; the mean of two distances lies between them
            val other = rnd.nextDouble(50.0, 6000.0)
            val mean = PowerRange.farEyeMedian(mm, other).seD!!
            assertTrue("$mm and $other mm -> $mean", mean <= maxOf(se, PowerRange.farEye(other).seD!!) + 1e-9 && mean >= minOf(se, PowerRange.farEye(other).seD!!) - 1e-9)
        }
    }

    @Test fun `power - zero, negative, NaN and infinite distances are refused, never turned into a number`() {
        val faulty = listOf(0.0, -0.0, -1.0, -300.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e-9, 9.99, Double.MIN_VALUE, -Double.MAX_VALUE)
        for (mm in faulty) {
            assertFalse("$mm", PowerRange.validBlurMm(mm))
            assertNull("$mm", PowerRange.farEyeOrNull(mm))
            assertTrue("$mm", runCatching { PowerRange.farEye(mm) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue("$mm", runCatching { PowerRange.far(mm) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertFalse(PowerRange.validBlurMm(null)); assertNull(PowerRange.farEyeOrNull(null))
        assertNotNull(PowerRange.farEyeOrNull(300.0)); assertEquals(-3.25, PowerRange.farEyeOrNull(300.0)!!.seD!!, 0.0)
        // the screen drops such a reading before it reaches the power ("distance not caught")
        val flow = File("src/main/kotlin/com/drishtisaathi/vision/QuickEstimateFlow.kt").readText()
        assertTrue("?.takeIf { PowerRange.validBlurMm(it) }" in flow)
    }

    @Test fun `power through a lens - 10 000 cases - a faulty or missing distance is 'use a weaker lens', a real one is lens plus residual`() {
        val cfg = f.rules.lensAssisted
        val rnd = Random(99)
        val first = PowerRange.farEye(200.0)
        repeat(10_000) { n ->
            val lensD = -rnd.nextInt(0, 41) * 0.25
            val faulty = n % 4 == 0
            val cm = if (faulty) listOf(0.0, -10.0, Double.NaN, Double.NEGATIVE_INFINITY)[n / 4 % 4] else rnd.nextDouble(5.0, 600.0)
            val through = first.copy(seD = null, blurAtCm = cm)
            val e = PowerRange.lensAssistedEye(first, through, lensD, cfg)
            if (faulty) { assertNull("lens $lensD at $cm cm", e); return@repeat }
            val se = e!!.seD!!
            assertTrue("lens $lensD at $cm cm -> $se", se.isFinite() && se <= 0.0 && abs(se / 0.25 - (se / 0.25).roundToInt()) < 1e-9)
            assertTrue(e.rangeToD!! <= se && se <= e.rangeFromD!!)
            assertEquals(LensAssisted.effectiveLens(lensD, cfg) - 100.0 / cm, se, 0.125 + 1e-9)
            assertEquals(lensD, e.lensD!!, 0.0); assertTrue(e.residualD!! <= 0.0)
        }
        // still sharp at arm's length through the lens: too strong, no number
        assertNull(PowerRange.lensAssistedEye(first, first.copy(seD = null, blurAtCm = null, noMyopiaAtReach = true), -4.0, cfg))
    }

    // ---- both languages ----

    @Test fun `every string exists in Hindi and English, with the same placeholders, and Hindi is written in Devanagari`() {
        val all = (S.all + TxtRegistry.loadAll()).distinct()
        assertTrue(all.size > 400)
        val holes = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z]""")
        val blank = all.filter { it.en.isBlank() || it.hi.isBlank() }
        assertEquals("a language is missing", emptyList<Any>(), blank)
        // the same values in both languages; Hindi may put them in another order with %2$s, %1$.1f
        fun kinds(text: String) = holes.findAll(text).map { it.value.replace(Regex("""^%\d+\$"""), "%") }.sorted().toList()
        val mismatched = all.filter { kinds(it.en) != kinds(it.hi) }
        assertEquals("placeholders differ between the languages", emptyList<Any>(), mismatched.map { it.en })
        // a Hindi text with letters in it has Devanagari letters (brand names, units and codes alone are fine)
        val latinOnly = all.filter { t -> t.hi.count { it.isLetter() } >= 12 && t.hi.none { it in 'ऀ'..'ॿ' } }
        assertEquals("Hindi text left in English", emptyList<Any>(), latinOnly.map { it.hi })
        // the questions and the survey: every text, why line and option in both languages
        val survey = com.drishtisaathi.engine.profile.SurveyFile.serializer().let { ser ->
            val root = kotlinx.serialization.json.Json.parseToJsonElement(File("src/main/assets/anamnesis_questions.json").readText()).jsonObject
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromJsonElement(ser, root.getValue("survey"))
        }
        for (q in survey.questions) {
            for (t in listOf(q.text, q.why) + q.options.values + listOfNotNull(q.unit)) assertTrue(q.id, t.en.isNotBlank() && t.hi.isNotBlank() && t.hi.any { it in 'ऀ'..'ॿ' })
        }
        for (s in survey.sections) assertTrue(s.id, s.title.hi.isNotBlank() && s.why.hi.isNotBlank() && s.title.en.isNotBlank() && s.why.en.isNotBlank())
        val catalog = LensCatalog.parse(File("src/main/assets/lens_catalog.json").readText())
        for (t in catalog.lensTypes) assertTrue(t.id, t.whyEn.isNotBlank() && t.whyHi.any { it in 'ऀ'..'ॿ' })
        for (m in catalog.materials) assertTrue(m.id, m.whyEn.isNotBlank() && m.whyHi.any { it in 'ऀ'..'ॿ' })
        for (flag in RedFlag.entries) assertTrue(flag.name, SProfile.redFlag(flag).let { it.en.isNotBlank() && it.hi.any { c -> c in 'ऀ'..'ॿ' } })
    }
}
