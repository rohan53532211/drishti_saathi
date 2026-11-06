package com.drishtisaathi.voice

import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.i18n.S
import com.drishtisaathi.i18n.SVoice
import com.drishtisaathi.i18n.Txt
import com.drishtisaathi.interview.ChoiceContext
import com.drishtisaathi.interview.Question
import com.drishtisaathi.interview.Questions

/** Pure helpers that turn a [VoiceAnswer] into text and into the same update the buttons apply. */
object VoiceAnswers {

    /** How the answer is read back ("35 साल", "हाँ", "दुकान / व्यापार"). Uses the same labels as the buttons. */
    fun label(q: Question, a: VoiceAnswer, ctx: ChoiceContext): Txt = when (a) {
        is VoiceAnswer.YesNo -> if (a.value) S.yes else S.no
        is VoiceAnswer.Number -> when {
            a.value == null -> S.dontKnow
            q.id == Questions.AGE || q.id == Questions.GLASSES_AGE -> S.years.format(a.value)
            else -> Txt(a.value.toString(), a.value.toString())
        }
        is VoiceAnswer.Choice -> choiceLabel(q, a.option, ctx)
    }

    /** "आपने कहा: <answer>. सही है?" */
    fun readBack(q: Question, a: VoiceAnswer, ctx: ChoiceContext): Txt {
        val l = label(q, a, ctx)
        return Txt(SVoice.readBack.en.format(l.en), SVoice.readBack.hi.format(l.hi))
    }

    /** Apology + the question again, for the one re-ask. */
    fun reAsk(q: Question): Txt = Txt(SVoice.sayAgain.en + " " + q.text.en, SVoice.sayAgain.hi + " " + q.text.hi)

    /** What the screen does with an accepted voice answer. */
    sealed interface Action {
        /** Call the screen's `onAnswer(questionId, update)`, exactly like the buttons. */
        data class Answer(val questionId: String, val update: (InterviewAnswers) -> InterviewAnswers) : Action
        /** "Yes" to the old-numbers question: open the numbers entry screen (like the Yes button). */
        data object OpenOldNumbers : Action
    }

    /** null when the answer does not fit the question (should not happen: the parser is per question type). */
    fun action(q: Question, a: VoiceAnswer): Action? = when (q) {
        is Question.YesNo -> (a as? VoiceAnswer.YesNo)?.let { v -> Action.Answer(q.id) { ans -> q.set(ans, v.value) } }
        is Question.Number -> (a as? VoiceAnswer.Number)?.let { v ->
            if (v.value == null && !q.allowUnknown) null
            else if (v.value != null && v.value !in q.range) null
            else Action.Answer(q.id) { ans -> q.set(ans, v.value) }
        }
        is Question.Choice<*> -> (a as? VoiceAnswer.Choice)?.let { v -> choiceAction(q, v.option) }
        is Question.OldNumbers -> (a as? VoiceAnswer.YesNo)?.let { v ->
            if (v.value) Action.OpenOldNumbers else Action.Answer(q.id) { ans -> ans.copy(knownRx = null) }
        }
    }

    // Choice<T> is erased at runtime; options come from the same question, so the cast is safe.
    @Suppress("UNCHECKED_CAST")
    private fun choiceLabel(q: Question, option: Any, ctx: ChoiceContext): Txt =
        (q as? Question.Choice<Any>)?.label?.invoke(option, ctx) ?: Txt(option.toString(), option.toString())

    @Suppress("UNCHECKED_CAST")
    private fun choiceAction(q: Question.Choice<*>, option: Any): Action? {
        val typed = q as Question.Choice<Any>
        if (option !in typed.options) return null
        return Action.Answer(q.id) { ans -> typed.set(ans, option) }
    }
}
