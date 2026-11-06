package com.drishtisaathi.interview

import com.drishtisaathi.engine.Budget
import com.drishtisaathi.engine.ColourPref
import com.drishtisaathi.engine.LookPref
import com.drishtisaathi.engine.ReadingFrequency
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.Occupation
import com.drishtisaathi.engine.ScreenHours
import com.drishtisaathi.engine.StylePref
import com.drishtisaathi.i18n.S
import com.drishtisaathi.i18n.Txt

/** One M1 interview question. Answers are stored straight into [InterviewAnswers]. */
sealed class Question(val id: String, val text: Txt, val visibleIf: (InterviewAnswers) -> Boolean = { true }) {

    class YesNo(
        id: String, text: Txt,
        val get: (InterviewAnswers) -> Boolean?,
        val set: (InterviewAnswers, Boolean) -> InterviewAnswers,
        visibleIf: (InterviewAnswers) -> Boolean = { true },
    ) : Question(id, text, visibleIf)

    class Number(
        id: String, text: Txt,
        val hint: Txt,
        val range: IntRange,
        val allowUnknown: Boolean,
        val get: (InterviewAnswers) -> Int?,
        val set: (InterviewAnswers, Int?) -> InterviewAnswers,
        visibleIf: (InterviewAnswers) -> Boolean = { true },
    ) : Question(id, text, visibleIf)

    class Choice<T : Any>(
        id: String, text: Txt,
        val options: List<T>,
        val label: (T, ChoiceContext) -> Txt,
        val get: (InterviewAnswers) -> T?,
        val set: (InterviewAnswers, T) -> InterviewAnswers,
    ) : Question(id, text)

    /** Yes → staff type the old glasses numbers on a separate screen; No → none. */
    class OldNumbers(id: String, text: Txt) : Question(id, text)
}

/** Values the option labels need from rules.json (e.g. budget caps). */
data class ChoiceContext(val budgetLowCap: Int, val budgetMidCap: Int)

object Questions {
    const val AGE = "age"
    const val GLASSES_AGE = "glassesAgeYears"
    const val KNOWN_RX = "knownRx"

    val all: List<Question> = listOf(
        Question.Number(AGE, S.qAge, S.ageHint, 3..110, allowUnknown = false,
            get = { it.age }, set = { a, v -> a.copy(age = v) }),
        Question.YesNo("wearsGlasses", S.qWearsGlasses, { it.wearsGlasses }, { a, v ->
            // Clearing the follow-up keeps stale data out if the answer flips to "no".
            if (v) a.copy(wearsGlasses = true, firstTimeWearer = null) else a.copy(wearsGlasses = false, glassesAgeYears = null)
        }),
        Question.Number(GLASSES_AGE, S.qGlassesAge, S.yearsHint, 0..60, allowUnknown = true,
            get = { it.glassesAgeYears }, set = { a, v -> a.copy(glassesAgeYears = v) },
            visibleIf = { it.wearsGlasses == true }),
        Question.OldNumbers(KNOWN_RX, S.qKnownRx),
        Question.YesNo("distanceBlur", S.qDistanceBlur, { it.distanceBlur }, { a, v -> a.copy(distanceBlur = v) }),
        Question.YesNo("nearBlur", S.qNearBlur, { it.nearBlur }, { a, v -> a.copy(nearBlur = v) }),
        // Addition 4: only asked when they don't wear glasses now
        Question.YesNo("firstTimeWearer", S.qFirstTime, { it.firstTimeWearer }, { a, v -> a.copy(firstTimeWearer = v) },
            visibleIf = { it.wearsGlasses == false }),
        Question.Choice("readingFrequency", S.qReadingFreq, ReadingFrequency.entries.toList(), { v, _ -> S.readingFrequency(v) },
            { it.readingFrequency }, { a, v -> a.copy(readingFrequency = v) }),
        Question.Choice("screenHours", S.qScreenHours, ScreenHours.entries.toList(), { v, _ -> S.screenHours(v) },
            { it.screenHours }, { a, v -> a.copy(screenHours = v) }),
        Question.YesNo("nightDriving", S.qNightDriving, { it.nightDriving }, { a, v -> a.copy(nightDriving = v) }),
        Question.YesNo("outdoorWork", S.qOutdoorWork, { it.outdoorWork }, { a, v -> a.copy(outdoorWork = v) }),
        Question.Choice("occupation", S.qOccupation, Occupation.entries.toList(), { v, _ -> S.occupation(v) },
            { it.occupation }, { a, v -> a.copy(occupation = v) }),
        Question.YesNo("headacheStrain", S.qHeadache, { it.headacheStrain }, { a, v -> a.copy(headacheStrain = v) }),
        Question.YesNo("diabetesOrBp", S.qDiabetesBp, { it.diabetesOrBp }, { a, v -> a.copy(diabetesOrBp = v) }),
        Question.YesNo("redFlagFlashes", S.qRedFlagFlashes, { it.redFlagFlashes }, { a, v -> a.copy(redFlagFlashes = v) }),
        Question.YesNo("redFlagOther", S.qRedFlagOther, { it.redFlagOther }, { a, v -> a.copy(redFlagOther = v) }),
        Question.Choice("budget", S.qBudget, Budget.entries.toList(), { v, c -> S.budget(v, c.budgetLowCap, c.budgetMidCap) },
            { it.budget }, { a, v -> a.copy(budget = v) }),
        Question.Choice("style", S.qStyle, StylePref.entries.toList(), { v, _ -> S.style(v) },
            { it.style }, { a, v -> a.copy(style = v) }),
        // Addition 4: frame preferences
        Question.Choice("colourPreference", S.qColour, ColourPref.entries.toList(), { v, _ -> S.colour(v) },
            { it.colourPreference }, { a, v -> a.copy(colourPreference = v) }),
        Question.Choice("lookPreference", S.qLook, LookPref.entries.toList(), { v, _ -> S.look(v) },
            { it.lookPreference }, { a, v -> a.copy(lookPreference = v) }),
        Question.YesNo("lightweightPreference", S.qLightweight, { it.lightweightPreference }, { a, v -> a.copy(lightweightPreference = v) }),
    )

    fun visible(a: InterviewAnswers): List<Question> = all.filter { it.visibleIf(a) }
}
