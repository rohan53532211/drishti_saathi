package com.drishtisaathi.voice

import com.drishtisaathi.engine.Budget
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.KnownRx
import com.drishtisaathi.i18n.S
import com.drishtisaathi.voice.VoiceTestSupport.ctx
import com.drishtisaathi.voice.VoiceTestSupport.q
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceAnswersTest {
    private fun update(id: String, a: VoiceAnswer, start: InterviewAnswers = InterviewAnswers()) =
        (VoiceAnswers.action(q(id), a) as VoiceAnswers.Action.Answer).update(start)

    @Test fun `actions apply the same setters as the buttons`() {
        assertEquals(35, update("age", VoiceAnswer.Number(35)).age)
        assertEquals(true, update("nightDriving", VoiceAnswer.YesNo(true)).nightDriving)
        assertEquals(Budget.MID, update("budget", VoiceAnswer.Choice(Budget.MID)).budget)
        // wearsGlasses = no clears the follow-up, exactly like the button
        assertNull(update("wearsGlasses", VoiceAnswer.YesNo(false), InterviewAnswers(glassesAgeYears = 3)).glassesAgeYears)
        assertNull(update("glassesAgeYears", VoiceAnswer.Number(null), InterviewAnswers(glassesAgeYears = 3)).glassesAgeYears)
        val rx = InterviewAnswers(knownRx = KnownRx())
        assertNull(update("knownRx", VoiceAnswer.YesNo(false), rx).knownRx)
        assertEquals(VoiceAnswers.Action.OpenOldNumbers, VoiceAnswers.action(q("knownRx"), VoiceAnswer.YesNo(true)))
    }

    @Test fun `mismatched or invalid answers are refused`() {
        assertNull(VoiceAnswers.action(q("age"), VoiceAnswer.YesNo(true)))
        assertNull(VoiceAnswers.action(q("age"), VoiceAnswer.Number(null))) // age has no "don't know"
        assertNull(VoiceAnswers.action(q("age"), VoiceAnswer.Number(500)))
        assertNull(VoiceAnswers.action(q("budget"), VoiceAnswer.Choice("CHEAP")))
    }

    @Test fun `read-back labels match the button labels`() {
        assertEquals(S.yes, VoiceAnswers.label(q("diabetesOrBp"), VoiceAnswer.YesNo(true), ctx))
        assertEquals(S.dontKnow, VoiceAnswers.label(q("glassesAgeYears"), VoiceAnswer.Number(null), ctx))
        assertEquals(S.years.format(4), VoiceAnswers.label(q("glassesAgeYears"), VoiceAnswer.Number(4), ctx))
        assertEquals(S.budget(Budget.LOW, 2000, 5000), VoiceAnswers.label(q("budget"), VoiceAnswer.Choice(Budget.LOW), ctx))
        assertEquals("आपने कहा: हाँ. सही है?", VoiceAnswers.readBack(q("nearBlur"), VoiceAnswer.YesNo(true), ctx).hi)
    }
}
