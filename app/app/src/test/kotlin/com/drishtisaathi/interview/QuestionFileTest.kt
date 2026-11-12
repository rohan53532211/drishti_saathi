package com.drishtisaathi.interview

import com.drishtisaathi.UiState
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.PowerKnown
import com.drishtisaathi.engine.VisionProblem
import com.drishtisaathi.voice.VoiceLexicon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** assets/anamnesis_questions.json: texts, stages, follow-up rules, voice keywords and the backend's topics. */
class QuestionFileTest {
    private val text = File("src/main/assets/${QuestionBank.ASSET}").readText(Charsets.UTF_8)
    private val file = QuestionsFile.parse(text)
    private val bannedEn = Regex("""\b(prescription|prescribed|diagnos\w*|power is)\b""", RegexOption.IGNORE_CASE)
    private val bannedHi = listOf("पर्चा", "निदान", "पावर है", "आपका नंबर")

    private fun core(a: InterviewAnswers) = Questions.visibleIn(Stage.CORE, a).map { it.id }
    private fun later(a: InterviewAnswers) = Questions.visibleIn(Stage.LATER, a).map { it.id }

    @Test fun `every question text and option in both languages, without banned wording`() {
        val texts = file.questions.flatMap { q -> listOfNotNull(q.text, q.hint) + q.options.values }
        assertTrue(texts.size > 80)
        for (t in texts) {
            assertTrue(t.toString(), t.en.isNotBlank() && t.hi.isNotBlank())
            assertEquals(t.en, null, bannedEn.find(t.en)?.value)
            assertTrue(t.hi, bannedHi.none { it in t.hi })
        }
    }

    @Test fun `the file builds the question table and the voice keywords`() {
        assertEquals(27, Questions.all.size)
        VoiceLexicon.fromQuestionsFile(text)
        assertTrue(file.aiTopics.size >= 10)
        assertThrows(IllegalArgumentException::class.java) {
            Questions.build(file.copy(questions = file.questions.filter { it.id != "budget" }))
        }
        val badRule = file.copy(questions = file.questions.map {
            if (it.id == "nearTask") it.copy(showIf = listOf(QCondition(answer = "shoeSize", `in` = listOf("x")))) else it
        })
        assertThrows(IllegalStateException::class.java) { Questions.build(badRule) }
    }

    @Test fun `red flags come right after the problem, and the power question ends the first part`() {
        val a = Questions.setVisionProblem(InterviewAnswers(age = 30), VisionProblem.DISTANCE).copy(wearsGlasses = true)
        val ids = core(a)
        assertEquals(listOf("age", "visionProblem", "redFlagFlashes", "redFlagOther"), ids.take(4))
        assertEquals(Questions.POWER_KNOWN, ids.last())
        // far-only follow-ups and lifestyle wait until after the eye check
        assertTrue("distanceSituation" in later(a) && "budget" in later(a))
        assertFalse("nearTask" in core(a))
    }

    @Test fun `near follow-ups come before the eye check, glasses follow-ups only when they apply`() {
        val near = Questions.setVisionProblem(InterviewAnswers(age = 45), VisionProblem.NEAR)
        assertTrue(core(near).containsAll(listOf("nearTask", "nearDistance", "wearsGlasses")))
        assertFalse("glassesAgeYears" in core(near.copy(wearsGlasses = false)))
        assertTrue("firstTimeWearer" in later(near.copy(wearsGlasses = false)))
        assertTrue("glassesAgeYears" in core(near.copy(wearsGlasses = true)))
    }

    @Test fun `after an urgent red flag only the red-flag follow-ups are asked`() {
        val a = Questions.setVisionProblem(InterviewAnswers(age = 50), VisionProblem.BOTH).copy(redFlagFlashes = true)
        assertEquals(listOf("age", "visionProblem", "redFlagFlashes", "redFlagOther", "redFlagOnset", "redFlagEye"), core(a))
        assertEquals(emptyList<String>(), later(a))
        val s = UiState(answers = a, answered = core(a).toSet())
        assertTrue(s.allAnswered)
    }

    @Test fun `answering moves to the next unanswered question of the stage`() {
        var s = UiState(answers = InterviewAnswers(age = 40), answered = setOf("age"))
        s = s.copy(questionIndex = 1)
        assertEquals("visionProblem", s.currentQuestion?.id)
        // the AI interview already covered the glasses: they are skipped later
        val a = Questions.setVisionProblem(s.answers, VisionProblem.DISTANCE).copy(wearsGlasses = false, powerKnown = PowerKnown.NO)
        val st = s.copy(answers = a, answered = setOf("age", "visionProblem", "wearsGlasses"))
        val next = st.stageQuestions.indexOfFirst { it.id !in st.answered }
        assertEquals("redFlagFlashes", st.stageQuestions[next].id)
    }

    @Test fun `budget labels fill the caps`() {
        val q = Questions.all.first { it.id == "budget" } as Question.Choice<*>
        @Suppress("UNCHECKED_CAST") val c = q as Question.Choice<Any>
        assertEquals("Low (up to ₹2,000)", c.label(c.options.first(), ChoiceContext(2000, 5000)).en)
    }
}
