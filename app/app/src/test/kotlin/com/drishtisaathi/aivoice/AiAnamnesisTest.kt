package com.drishtisaathi.aivoice

import com.drishtisaathi.engine.DistanceSituation
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.PowerKnown
import com.drishtisaathi.engine.ReadingFrequency
import com.drishtisaathi.engine.ScreenHours
import com.drishtisaathi.engine.VisionProblem
import com.drishtisaathi.engine.anamnesis.FunctionalProfile
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.interview.Questions
import com.drishtisaathi.pdf.PdfContent
import com.drishtisaathi.UiState
import com.drishtisaathi.bridge.BridgeConsent
import com.drishtisaathi.bridge.BridgeProfile
import com.drishtisaathi.demo.DemoCustomers
import com.drishtisaathi.engine.DrishtiEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiAnamnesisTest {
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun profile(answers: String, redAsked: Boolean = true, unclear: Boolean = false) =
        AiProfile("hi-IN", 8, obj(answers), redFlagsAsked = redAsked, redFlagUnclear = unclear)

    // ---------------------------------------------------------------- mapping to InterviewAnswers

    @Test fun `answers map into the existing InterviewAnswers and count as answered`() {
        val m = AiProfileMapper.map(InterviewAnswers(), profile("""{"age": 52, "visionProblem": "BOTH", "distanceSituation": "TV",
            "nearTask": "NEWSPAPER", "screenHours": "H4_PLUS", "readingFrequency": "DAILY_LONG", "outdoorWork": false,
            "nightDriving": true, "headacheStrain": true, "wearsGlasses": true, "glassesAgeYears": 3, "redFlagAny": false}"""))
        val a = m.answers
        assertEquals(52, a.age)
        assertEquals(VisionProblem.BOTH, a.visionProblem)
        assertEquals(true, a.distanceBlur); assertEquals(true, a.nearBlur) // the old fields the rules use
        assertEquals(DistanceSituation.TV, a.distanceSituation)
        assertEquals(ScreenHours.H4_PLUS, a.screenHours)
        assertEquals(ReadingFrequency.DAILY_LONG, a.readingFrequency)
        assertEquals(true, a.nightDriving); assertEquals(false, a.outdoorWork); assertEquals(true, a.headacheStrain)
        assertEquals(3, a.glassesAgeYears)
        assertEquals(false, a.redFlagFlashes); assertEquals(false, a.redFlagOther)
        for (id in listOf("age", "visionProblem", "distanceSituation", "nearTask", "screenHours", "readingFrequency",
                "outdoorWork", "nightDriving", "headacheStrain", "wearsGlasses", "glassesAgeYears", "redFlagFlashes", "redFlagOther")) {
            assertTrue(id, id in m.answered)
        }
        // the functional vision profile is the existing one, built from the mapped answers
        val rules = RulesConfig.parse(File("src/main/assets/rules.json").readText())
        val fp = Json.encodeToString(FunctionalProfile.serializer(), FunctionalProfile.from(a, rules))
        assertTrue(fp, fp.contains("dominant"))
    }

    @Test fun `invalid or unknown values are ignored`() {
        val m = AiProfileMapper.map(InterviewAnswers(), profile("""{"age": 500, "visionProblem": "FAR", "screenHours": 3,
            "diagnosis": "x", "wearsGlasses": "maybe"}""", redAsked = false))
        assertEquals(InterviewAnswers(), m.answers)
        assertTrue(m.answered.isEmpty())
    }

    @Test fun `follow-ups that do not apply are dropped`() {
        val m = AiProfileMapper.map(InterviewAnswers(), profile("""{"visionProblem": "NEAR", "distanceSituation": "DRIVING",
            "wearsGlasses": false, "glassesAgeYears": 5}"""))
        assertNull(m.answers.distanceSituation)
        assertNull(m.answers.glassesAgeYears)
        assertFalse("distanceSituation" in m.answered)
    }

    @Test fun `headache and eye strain fold into the one app question`() {
        fun h(s: String) = AiProfileMapper.map(InterviewAnswers(), profile(s)).answers.headacheStrain
        assertEquals(true, h("""{"headacheStrain": false, "eyeStrain": true}"""))
        assertEquals(false, h("""{"headacheStrain": false, "eyeStrain": false}"""))
        assertNull(h("""{"headacheStrain": false}"""))
    }

    @Test fun `power question is pre-selected but left for the phone so the power path still runs`() {
        val m = AiProfileMapper.map(InterviewAnswers(), profile("""{"age": 45, "visionProblem": "NEAR", "wearsGlasses": true, "powerKnown": "YES"}"""))
        assertEquals(PowerKnown.YES, m.answers.powerKnown)
        assertFalse(Questions.POWER_KNOWN in m.answered)
        assertTrue(Questions.visible(m.answers).any { it.id == Questions.POWER_KNOWN })
    }

    @Test fun `a red flag yes is kept and makes the session urgent for the rules engine`() {
        val m = AiProfileMapper.map(InterviewAnswers(), profile("""{"age": 60, "visionProblem": "DISTANCE", "redFlagFlashes": true}"""))
        assertTrue(m.answers.urgentRedFlag)
        assertTrue("redFlagFlashes" in m.answered)
        // the existing interview still asks onset and eye; nothing after the red flags is shown
        val visible = Questions.visible(m.answers).map { it.id }
        assertTrue("redFlagOnset" in visible && "redFlagEye" in visible)
        assertFalse(Questions.POWER_KNOWN in visible)
        assertFalse("budget" in visible)

        val engine = DrishtiEngine.fromJson(File("src/main/assets/rules.json").readText(), File("src/main/assets/anthro_priors.json").readText(),
            File("src/main/assets/frames.json").readText(), File("src/main/assets/frames/more_frames.json").readText())
        val r = engine.evaluate(m.answers)
        assertEquals(com.drishtisaathi.engine.ReferralLevel.URGENT, r.assessment.referral.level)
        assertNull(r.assessment.advice) // urgent stops advice, exactly as today
    }

    @Test fun `a general yes to the warning signs counts as a red flag`() {
        val m = AiProfileMapper.map(InterviewAnswers(), profile("""{"redFlagAny": true}"""))
        assertEquals(true, m.answers.redFlagOther)
        assertTrue(m.answers.urgentRedFlag)
    }

    @Test fun `an unclear or unasked red flag is left for the phone to ask`() {
        for (p in listOf(profile("""{"redFlagAny": false}""", unclear = true), profile("""{"redFlagFlashes": false}""", redAsked = false))) {
            val m = AiProfileMapper.map(InterviewAnswers(), p)
            assertNull(m.answers.redFlagFlashes); assertNull(m.answers.redFlagOther)
            assertFalse("redFlagFlashes" in m.answered || "redFlagOther" in m.answered)
        }
    }

    @Test fun `red flag yes stays yes when merging later turns`() {
        val merged = AiProfileMapper.mergeExtracted(obj("""{"redFlagOther": true, "age": 40}"""), obj("""{"redFlagOther": false, "age": 41}"""))
        assertEquals("true", merged["redFlagOther"].toString())
        assertEquals("41", merged["age"].toString())
    }

    @Test fun `backend profile JSON decodes`() {
        val json = """{"session_id":"s","language":"en-IN","questions_asked":3,"answers":{"age":30},"red_flags_asked":true,
            "red_flag_unclear":false,"urgent":false,"coverage":{"age_group":true},
            "transcript":[{"n":1,"topic":"age_group","question":"How old are you?","answer":"thirty","source":"scripted"}]}"""
        val p = Json { ignoreUnknownKeys = true }.decodeFromString(AiProfile.serializer(), json)
        assertEquals(3, p.questionsAsked); assertTrue(p.redFlagsAsked); assertEquals("thirty", p.transcript[0].answer)
    }

    // ---------------------------------------------------------------- fallback switching

    private class FakeApi : AiAnamnesisApi {
        val startResults = ArrayDeque<Any>()
        val turnResults = ArrayDeque<Any>()
        var finishResult: Any = AiException("NETWORK")
        var finishCalls = 0
        override fun start(language: AiLanguage): AiStartResponse = when (val r = startResults.removeFirst()) {
            is AiException -> throw r
            else -> r as AiStartResponse
        }
        override fun turn(sessionId: String, wavBase64: String): AiTurnResponse = when (val r = turnResults.removeFirst()) {
            is AiException -> throw r
            else -> r as AiTurnResponse
        }
        override fun finish(sessionId: String): AiProfile { finishCalls++; return (finishResult as? AiProfile) ?: throw finishResult as AiException }
    }

    private val started = AiStartResponse("sid-123456", "How old are you?", "age_group", progress = AiProgress(1, 8, 8))
    private fun turned(extracted: String, cont: Boolean = true) =
        AiTurnResponse("heard", obj(extracted), "Next?", "main_difficulty", shouldContinue = cont, progress = AiProgress(2, 8, 8))

    @Test fun `two backend failures in a row switch to the standard interview and keep the answers so far`() {
        val api = FakeApi().apply {
            startResults += started
            turnResults += turned("""{"age": 47}""")
            turnResults += AiException("NETWORK")
            turnResults += AiException("SARVAM_UNAVAILABLE")
        }
        val c = AiInterviewController(api, AiLanguage.ENGLISH)
        c.start(); assertEquals(AiUiState.Phase.ASKING, c.state.value.phase)
        c.answer(ByteArray(10)); assertEquals(AiUiState.Phase.ASKING, c.state.value.phase)
        c.answer(ByteArray(10)); assertEquals(AiUiState.Phase.RETRY, c.state.value.phase)
        c.retry()
        c.answer(ByteArray(10)); assertEquals(AiUiState.Phase.FALLBACK, c.state.value.phase)

        val p = c.profileSoFar()
        assertEquals(1, p.transcript.size)
        assertTrue(p.redFlagUnclear) // the phone asks the red-flag questions again
        val m = AiProfileMapper.map(InterviewAnswers(), p)
        assertEquals(47, m.answers.age)
        assertNull(m.answers.redFlagFlashes)
    }

    @Test fun `a success in between resets the failure count`() {
        val api = FakeApi().apply {
            startResults += started
            turnResults += AiException("NETWORK"); turnResults += turned("{}"); turnResults += AiException("NETWORK")
        }
        val c = AiInterviewController(api, AiLanguage.HINDI)
        c.start()
        c.answer(ByteArray(1)); c.retry()
        c.answer(ByteArray(1))
        c.answer(ByteArray(1))
        assertEquals(AiUiState.Phase.RETRY, c.state.value.phase)
    }

    @Test fun `customer-side errors never switch to the standard interview`() {
        val api = FakeApi().apply {
            startResults += started
            repeat(4) { turnResults += AiException("EMPTY_TRANSCRIPT") }
        }
        val c = AiInterviewController(api, AiLanguage.HINDI)
        c.start()
        repeat(4) { c.answer(ByteArray(1)); assertEquals(AiUiState.Phase.RETRY, c.state.value.phase); c.retry() }
        c.micFailed(); c.micFailed()
        assertEquals(AiUiState.Phase.RETRY, c.state.value.phase)
    }

    @Test fun `a permanent error like a missing key or no server switches at once`() {
        for (code in listOf("NOT_CONFIGURED", "NO_SERVER", "SARVAM_AUTH", "UNAUTHORIZED")) {
            val c = AiInterviewController(FakeApi().apply { startResults += AiException(code) }, AiLanguage.ENGLISH)
            c.start()
            assertEquals(code, AiUiState.Phase.FALLBACK, c.state.value.phase)
        }
    }

    @Test fun `start failing twice falls back`() {
        val c = AiInterviewController(FakeApi().apply { startResults += AiException("NETWORK"); startResults += AiException("NETWORK") }, AiLanguage.ENGLISH)
        c.start(); assertEquals(AiUiState.Phase.RETRY, c.state.value.phase)
        c.retry(); assertEquals(AiUiState.Phase.FALLBACK, c.state.value.phase)
    }

    @Test fun `finish uses the backend profile, or the phone's own when the backend is gone`() {
        val api = FakeApi().apply {
            startResults += started; startResults += started
            turnResults += turned("""{"age": 33}""", cont = false)
            finishResult = profile("""{"age": 33}""")
        }
        val c = AiInterviewController(api, AiLanguage.ENGLISH)
        c.start(); c.answer(ByteArray(1))
        assertEquals(AiUiState.Phase.DONE, c.state.value.phase)
        assertEquals(obj("""{"age": 33}"""), c.finish().answers)

        api.finishResult = AiException("NETWORK")
        val c2 = AiInterviewController(api, AiLanguage.ENGLISH)
        c2.start()
        val p = c2.finish()
        assertTrue(p.redFlagUnclear)
        assertEquals(0, p.transcript.size)
    }

    @Test fun `the done turn records the last answer and no new question`() {
        val api = FakeApi().apply { startResults += started; turnResults += turned("{}", cont = false) }
        val c = AiInterviewController(api, AiLanguage.ENGLISH)
        c.start(); c.answer(ByteArray(1))
        assertEquals(listOf(AiTurn(1, "age_group", "How old are you?", "heard")), c.transcript)
    }

    // ---------------------------------------------------------------- privacy: transcript stays in the shop

    private fun stateWithTranscript(): UiState {
        val c = DemoCustomers.youngMyope
        val engine = DrishtiEngine.fromJson(File("src/main/assets/rules.json").readText(), File("src/main/assets/anthro_priors.json").readText(),
            File("src/main/assets/frames.json").readText(), File("src/main/assets/frames/more_frames.json").readText())
        val rec = AiInterviewRecord("en-IN", 1L, listOf(AiTurn(1, "age_group", "How old are you?", "UNIQUE_SPOKEN_ANSWER")))
        return UiState(answers = c.answers, face = c.face, tests = c.tests ?: com.drishtisaathi.engine.VisionTests(), testsDone = c.tests != null,
            result = engine.evaluate(c.answers, tests = c.tests ?: com.drishtisaathi.engine.VisionTests(), face = c.face), aiInterview = rec)
    }

    @Test fun `transcript is on the shop PDF only, never the customer copy or the optometrist profile`() {
        val s = stateWithTranscript()
        val rules = RulesConfig.parse(File("src/main/assets/rules.json").readText())
        val ctx = com.drishtisaathi.interview.ChoiceContext(rules.frameScoring.budgetMaxPriceInrLow, rules.frameScoring.budgetMaxPriceInrMid)
        assertTrue(PdfContent.shopCopy(s, rules, ctx, com.drishtisaathi.i18n.Lang.EN).toString().contains("UNIQUE_SPOKEN_ANSWER"))
        assertFalse(PdfContent.customerCopy(s, rules).toString().contains("UNIQUE_SPOKEN_ANSWER"))
        val bridge = BridgeProfile.build(s, rules, BridgeConsent(1L, spoken = true, tapped = true), "t")
        assertFalse(bridge.toString().contains("UNIQUE_SPOKEN_ANSWER"))
    }

    @Test fun `transcript is stored with the session and read back`() {
        val m = com.drishtisaathi.data.Measurements(aiInterview = stateWithTranscript().aiInterview)
        val e = com.drishtisaathi.data.SessionEntity(createdAt = 1, lang = "EN", phoneModel = "x", answersJson = "{}", staffJson = "{}",
            resultJson = "{}", referralLevel = "NONE", measurementsJson = com.drishtisaathi.data.SessionCodec.encode(m))
        assertEquals("UNIQUE_SPOKEN_ANSWER", com.drishtisaathi.data.SessionCodec.measurements(e).aiInterview!!.turns[0].answer)
    }

    @Test fun `WAV header is 16 kHz mono 16-bit`() {
        val w = Wav.wrap(ByteArray(3200))
        val b = java.nio.ByteBuffer.wrap(w).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(w, 0, 4)); assertEquals("WAVE", String(w, 8, 4)); assertEquals("data", String(w, 36, 4))
        assertEquals(1, b.getShort(20).toInt())      // PCM
        assertEquals(1, b.getShort(22).toInt())      // mono
        assertEquals(16000, b.getInt(24))            // 16 kHz
        assertEquals(16, b.getShort(34).toInt())     // 16-bit
        assertEquals(3200, b.getInt(40))
        assertEquals(44 + 3200, w.size)
    }
}
