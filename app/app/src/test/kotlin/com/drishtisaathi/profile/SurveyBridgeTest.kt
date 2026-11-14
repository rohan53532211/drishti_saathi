package com.drishtisaathi.profile

import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.Occupation
import com.drishtisaathi.engine.ScreenHours
import com.drishtisaathi.engine.profile.Survey
import com.drishtisaathi.engine.profile.SurveyAnswers
import com.drishtisaathi.engine.profile.SurveyKind
import com.drishtisaathi.interview.QuestionBank
import com.drishtisaathi.interview.Questions
import com.drishtisaathi.interview.Stage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase D in the app: the survey file as the app loads it, no question asked twice, and the AI interview's answers. */
class SurveyBridgeTest {
    private val f = ProfileFixtures
    private val file = QuestionBank.survey

    @Test fun `the app loads and validates the survey - every option of a choice can be spoken`() {
        assertEquals(22, file.questions.size)
        for (q in file.questions.filter { it.kind == SurveyKind.CHOICE || it.kind == SurveyKind.MULTI }) {
            assertEquals(q.id, q.optionCodes.toSet(), q.say.keys)
            assertTrue(q.id, spokenOptions(q).all { it.second.isNotEmpty() })
        }
        // never / sometimes / often and yes / no have fixed labels and keywords
        val freq = file.questions.first { it.kind == SurveyKind.FREQ }
        assertEquals(listOf("NEVER", "SOMETIMES", "OFTEN"), spokenOptions(freq).map { it.first })
        assertEquals("Sometimes", optionLabel(freq, "SOMETIMES").en)
        assertEquals("कभी-कभी", optionLabel(freq, "SOMETIMES").hi)
        val yn = file.questions.first { it.kind == SurveyKind.YES_NO }
        assertEquals(listOf("YES", "NO"), spokenOptions(yn).map { it.first })
    }

    @Test fun `survey answers fill the older interview questions so nobody is asked twice`() {
        val a = InterviewAnswers(age = 35, redFlagFlashes = false, redFlagOther = false)
        val s = SurveyAnswers().with("tiredEyes", listOf("OFTEN")).with("headacheFreq", listOf("NEVER"))
            .with("screenHoursDay", listOf("7")).with("indoorOutdoor", listOf("INDOOR")).with("workActivities", listOf("DESKTOP", "MEETINGS"))
        val (out, ids) = SurveyBridge.apply(a, setOf("age"), s, f.rules)
        assertEquals(true, out.headacheStrain)                      // tired eyes often counts
        assertEquals(ScreenHours.H4_PLUS, out.screenHours)
        assertEquals(false, out.outdoorWork)
        assertEquals(Occupation.OFFICE_COMPUTER, out.occupation)
        assertTrue(ids.containsAll(listOf("headacheStrain", "screenHours", "outdoorWork", "occupation")))
        val later = Questions.visibleIn(Stage.LATER, out).map { it.id }.filter { it !in ids }
        assertFalse("screenHours" in later); assertFalse("occupation" in later)
        assertTrue("the budget is still asked", "budget" in later)

        assertEquals(ScreenHours.H2_4, SurveyBridge.apply(a, emptySet(), SurveyAnswers().with("screenHoursDay", listOf("3")), f.rules).first.screenHours)
        assertEquals(ScreenHours.H0_2, SurveyBridge.apply(a, emptySet(), SurveyAnswers().with("screenHoursDay", listOf("1")), f.rules).first.screenHours)
    }

    @Test fun `an answer the customer gave directly is never overwritten, and unclear work keeps its question`() {
        val a = InterviewAnswers(age = 35, screenHours = ScreenHours.H0_2)
        val (out, _) = SurveyBridge.apply(a, setOf("screenHours"), SurveyAnswers().with("screenHoursDay", listOf("9")), f.rules)
        assertEquals(ScreenHours.H0_2, out.screenHours)
        val (o2, ids2) = SurveyBridge.apply(InterviewAnswers(), emptySet(), SurveyAnswers().with("workActivities", listOf("READING_WRITING")), f.rules)
        assertNull(o2.occupation); assertFalse("occupation" in ids2)
    }

    @Test fun `the AI interview's survey answers are checked against the file and the survey asks only the rest`() {
        val heard = Json.parseToJsonElement(
            """{"driveFrequency":"DAILY","glareOncoming":"OFTEN","screenHoursDay":6,"outdoorHours":99,"indoorOutdoor":"UNDERWATER","tiredEyes":"SOMETIMES","age":40}"""
        ).jsonObject
        val s = SurveyBridge.fromAi(heard, file)
        assertEquals("DAILY", s.one("driveFrequency")); assertEquals("OFTEN", s.one("glareOncoming"))
        assertEquals(6.0, s.number("screenHoursDay")!!, 0.0)
        assertFalse("out of range", s.answered("outdoorHours"))
        assertFalse("not an option", s.answered("indoorOutdoor"))
        val open = Survey.visible(file, InterviewAnswers(age = 40), s).filter { !s.answered(it.id) }.map { it.id }
        assertFalse("driveFrequency" in open); assertFalse("glareOncoming" in open)
        assertTrue("lowLightDriving" in open && "outdoorHours" in open)
        // an answer already given on the phone is kept
        assertEquals("NEVER", SurveyBridge.fromAi(heard, file, SurveyAnswers().with("driveFrequency", listOf("NEVER"))).one("driveFrequency"))
    }

    @Test fun `the backend's survey topics name real survey questions and are marked optional`() {
        val topics = QuestionBank.file.aiTopics.map { it.jsonObject }
        val optional = topics.filter { it["optional"]?.jsonPrimitive?.content == "true" }
        assertEquals(listOf("driving", "sun_time", "work_day"), optional.map { it["id"]!!.jsonPrimitive.content })
        val ids = file.questions.map { it.id }.toSet()
        assertTrue(optional.flatMap { t -> t["fields"]!!.jsonArray.map { it.jsonPrimitive.content } }.all { it in ids })
        assertEquals("red-flag question stays question 3 (the third topic)", "red_flags", topics[2]["id"]!!.jsonPrimitive.content)
    }
}
