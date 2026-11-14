package com.drishtisaathi.engine.profile

import com.drishtisaathi.engine.InterviewAnswers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase D: the survey file and its skip logic. */
class SurveyTest {
    private val file = SurveyAssets.file
    private fun ids(a: InterviewAnswers, s: SurveyAnswers = SurveyAnswers(), sections: Set<String>? = null) =
        Survey.visible(file, a, s, sections).map { it.id }

    @Test fun `the file is consistent and has every section in order`() {
        file.validate()
        assertEquals(listOf("SAFETY", "GENERAL", "WORK", "DIGITAL", "MOBILITY", "LEISURE", "WISHES"), file.sections.map { it.id })
        assertTrue(file.sections.all { Regex("#[0-9A-F]{6}").matches(it.colour) })
        // questions are grouped by section, in the order of the sections
        val order = file.questions.map { q -> file.sections.indexOfFirst { it.id == q.section } }
        assertEquals(order.sorted(), order)
        assertTrue(file.questions.all { it.text.en.isNotBlank() && it.text.hi.isNotBlank() && it.why.en.isNotBlank() && it.why.hi.isNotBlank() })
        assertTrue(file.questions.flatMap { it.options.values }.all { it.en.isNotBlank() && it.hi.isNotBlank() })
    }

    @Test fun `the red-flag follow-up is asked only after a red flag`() {
        assertFalse(Safety.SIGNS_QUESTION in ids(InterviewAnswers(age = 30, redFlagFlashes = false, redFlagOther = false)))
        val urgent = InterviewAnswers(age = 30, redFlagFlashes = true)
        assertEquals(Safety.SIGNS_QUESTION, ids(urgent).first())
        assertEquals("the emergency short path asks the safety section only", listOf(Safety.SIGNS_QUESTION), ids(urgent, sections = setOf("SAFETY")))
        assertEquals(RedFlag.pickable.map { it.name }.toSet(), file.question(Safety.SIGNS_QUESTION)!!.options.keys)
    }

    @Test fun `the driving block is skipped for people who never drive`() {
        val a = InterviewAnswers(age = 30)
        val block = listOf("glareOncoming", "lowLightDriving", "refocusDashboard")
        // not yet answered: the follow-ups wait for the frequency
        assertTrue(block.none { it in ids(a) })
        assertTrue(block.none { it in ids(a, SurveyAnswers().with("driveFrequency", listOf("NEVER"))) })
        for (f in listOf("DAILY", "WEEKLY", "MONTHLY")) {
            assertTrue(f, ids(a, SurveyAnswers().with("driveFrequency", listOf(f))).containsAll(block))
        }
    }

    @Test fun `the first-progressive question is for 45 and over only`() {
        assertFalse("firstProgressive" in ids(InterviewAnswers(age = 44)))
        assertTrue("firstProgressive" in ids(InterviewAnswers(age = 45)))
        assertFalse("an unknown age skips it", "firstProgressive" in ids(InterviewAnswers()))
    }

    @Test fun `answers of a skipped block are dropped when the answer changes`() {
        val a = InterviewAnswers(age = 50)
        val s = SurveyAnswers().with("driveFrequency", listOf("DAILY")).with("glareOncoming", listOf("OFTEN")).with("tiredEyes", listOf("SOMETIMES"))
        assertEquals(s, Survey.prune(file, a, s))
        val changed = Survey.prune(file, a, s.with("driveFrequency", listOf("NEVER")))
        assertFalse(changed.answered("glareOncoming"))
        assertTrue(changed.answered("tiredEyes"))
        assertNull(changed.freq("glareOncoming"))
        assertEquals(Freq.SOMETIMES, changed.freq("tiredEyes"))
    }

    @Test fun `next unanswered walks the visible questions in order and ends`() {
        val a = InterviewAnswers(age = 50)
        var s = SurveyAnswers()
        val asked = mutableListOf<String>()
        while (true) {
            val q = Survey.nextUnanswered(file, a, s) ?: break
            asked += q.id
            s = s.with(q.id, listOf(if (q.id == "driveFrequency") "NEVER" else q.optionCodes.firstOrNull() ?: "3"))
        }
        assertEquals(ids(a, s), asked)
        assertFalse("glareOncoming" in asked)
        assertNotNull(file.question("doubleVision"))
    }

    @Test fun `the whole survey fits in four minutes even with every block`() {
        val a = InterviewAnswers(age = 60, redFlagOther = true)
        val all = SurveyAnswers().with("driveFrequency", listOf("DAILY"))
        assertEquals(file.questions.size, Survey.visible(file, a, all).size)
        assertTrue(Survey.plannedSeconds(file, a, all) <= file.maxMinutes * 60)
        assertEquals(4, file.maxMinutes)
    }

    @Test fun `typed readers of the answers`() {
        val s = SurveyAnswers().with("screenHoursDay", listOf("6")).with("devices", listOf("PHONE", "TV")).with("doubleVision", listOf("NO"))
        assertEquals(6.0, s.number("screenHoursDay")!!, 0.0)
        assertEquals(setOf("PHONE", "TV"), s.set("devices"))
        assertEquals(false, s.yes("doubleVision"))
        assertNull(s.yes("onePair"))
    }
}
