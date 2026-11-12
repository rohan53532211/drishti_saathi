package com.drishtisaathi.interview

import com.drishtisaathi.engine.AffectedEye
import com.drishtisaathi.engine.Budget
import com.drishtisaathi.engine.ColourPref
import com.drishtisaathi.engine.DistanceSituation
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.LookPref
import com.drishtisaathi.engine.NearDistance
import com.drishtisaathi.engine.NearTask
import com.drishtisaathi.engine.Occupation
import com.drishtisaathi.engine.PowerKnown
import com.drishtisaathi.engine.ProblemSince
import com.drishtisaathi.engine.ReadingFrequency
import com.drishtisaathi.engine.RedFlagOnset
import com.drishtisaathi.engine.ScreenHours
import com.drishtisaathi.engine.StylePref
import com.drishtisaathi.engine.VisionProblem
import com.drishtisaathi.i18n.S
import com.drishtisaathi.i18n.SAnamnesis
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
        visibleIf: (InterviewAnswers) -> Boolean = { true },
    ) : Question(id, text, visibleIf)

    /** Yes → staff type the old glasses numbers on a separate screen; No → none. (No longer in [Questions.all].) */
    class OldNumbers(id: String, text: Txt) : Question(id, text)
}

/** Values the option labels need from rules.json (e.g. budget caps). */
data class ChoiceContext(val budgetLowCap: Int, val budgetMidCap: Int)

/**
 * The adaptive, voice-first anamnesis (12 Nov). Follow-ups appear only when they apply:
 * far: situation and since when; near: task and usual distance; glasses: age and satisfaction (else first pair);
 * a red flag: onset and which eye, and then nothing else is asked (urgent, the referral comes first).
 * The power path question ([POWER_KNOWN]) routes to the old-glasses screen (YES) or the quick estimate (NOT SURE / NO).
 */
object Questions {
    const val AGE = "age"
    const val GLASSES_AGE = "glassesAgeYears"
    /** Kept for sessions saved before the power path (the old yes/no question). */
    const val KNOWN_RX = "knownRx"
    const val VISION_PROBLEM = "visionProblem"
    const val POWER_KNOWN = "powerKnown"

    private val notUrgent: (InterviewAnswers) -> Boolean = { !it.urgentRedFlag }

    /** Sets the problem and the old yes/no fields the rules use; clears follow-ups that no longer apply. */
    fun setVisionProblem(a: InterviewAnswers, v: VisionProblem): InterviewAnswers = a.copy(
        visionProblem = v, distanceBlur = v.distance, nearBlur = v.near,
        distanceSituation = a.distanceSituation.takeIf { v.distance }, distanceSince = a.distanceSince.takeIf { v.distance },
        nearTask = a.nearTask.takeIf { v.near }, nearDistance = a.nearDistance.takeIf { v.near },
    )

    /** Power path is asked unless an urgent flag stopped everything, or there is no trouble and no glasses. */
    fun powerPathApplies(a: InterviewAnswers): Boolean =
        !a.urgentRedFlag && a.visionProblem != null && !(a.visionProblem == VisionProblem.NONE && a.wearsGlasses == false)

    /** Onset and eye belong to the red flag; cleared when neither flag is set any more. */
    private fun clearOnset(a: InterviewAnswers): InterviewAnswers =
        if (a.urgentRedFlag) a else a.copy(redFlagOnset = null, redFlagEye = null)

    val all: List<Question> = listOf(
        Question.Number(AGE, S.qAge, S.ageHint, 3..110, allowUnknown = false,
            get = { it.age }, set = { a, v -> a.copy(age = v) }),
        Question.Choice(VISION_PROBLEM, SAnamnesis.qVisionProblem, VisionProblem.entries.toList(), { v, _ -> SAnamnesis.visionProblem(v) },
            { it.visionProblem }, { a, v -> setVisionProblem(a, v) }),
        Question.Choice("distanceSituation", SAnamnesis.qDistanceSituation, DistanceSituation.entries.toList(),
            { v, _ -> SAnamnesis.distanceSituation(v) }, { it.distanceSituation }, { a, v -> a.copy(distanceSituation = v) },
            visibleIf = { it.visionProblem?.distance == true }),
        Question.Choice("distanceSince", SAnamnesis.qDistanceSince, ProblemSince.entries.toList(), { v, _ -> SAnamnesis.since(v) },
            { it.distanceSince }, { a, v -> a.copy(distanceSince = v) }, visibleIf = { it.visionProblem?.distance == true }),
        Question.Choice("nearTask", SAnamnesis.qNearTask, NearTask.entries.toList(), { v, _ -> SAnamnesis.nearTask(v) },
            { it.nearTask }, { a, v -> a.copy(nearTask = v) }, visibleIf = { it.visionProblem?.near == true }),
        Question.Choice("nearDistance", SAnamnesis.qNearDistance, NearDistance.entries.toList(), { v, _ -> SAnamnesis.nearDistance(v) },
            { it.nearDistance }, { a, v -> a.copy(nearDistance = v) }, visibleIf = { it.visionProblem?.near == true }),
        Question.YesNo("wearsGlasses", S.qWearsGlasses, { it.wearsGlasses }, { a, v ->
            // Clearing the follow-ups keeps stale data out if the answer flips.
            if (v) a.copy(wearsGlasses = true, firstTimeWearer = null)
            else a.copy(wearsGlasses = false, glassesAgeYears = null, happyWithGlasses = null)
        }),
        Question.Number(GLASSES_AGE, S.qGlassesAge, S.yearsHint, 0..60, allowUnknown = true,
            get = { it.glassesAgeYears }, set = { a, v -> a.copy(glassesAgeYears = v) },
            visibleIf = { it.wearsGlasses == true }),
        Question.YesNo("happyWithGlasses", SAnamnesis.qHappyWithGlasses, { it.happyWithGlasses }, { a, v -> a.copy(happyWithGlasses = v) },
            visibleIf = { it.wearsGlasses == true }),
        // Addition 4: only asked when they do not wear glasses now
        Question.YesNo("firstTimeWearer", S.qFirstTime, { it.firstTimeWearer }, { a, v -> a.copy(firstTimeWearer = v) },
            visibleIf = { it.wearsGlasses == false }),
        Question.YesNo("headacheStrain", S.qHeadache, { it.headacheStrain }, { a, v -> a.copy(headacheStrain = v) }),
        Question.YesNo("diabetesOrBp", S.qDiabetesBp, { it.diabetesOrBp }, { a, v -> a.copy(diabetesOrBp = v) }),
        Question.YesNo("redFlagFlashes", S.qRedFlagFlashes, { it.redFlagFlashes }, { a, v -> clearOnset(a.copy(redFlagFlashes = v)) }),
        Question.YesNo("redFlagOther", S.qRedFlagOther, { it.redFlagOther }, { a, v -> clearOnset(a.copy(redFlagOther = v)) }),
        Question.Choice("redFlagOnset", SAnamnesis.qRedFlagOnset, RedFlagOnset.entries.toList(), { v, _ -> SAnamnesis.onset(v) },
            { it.redFlagOnset }, { a, v -> a.copy(redFlagOnset = v) }, visibleIf = { it.urgentRedFlag }),
        Question.Choice("redFlagEye", SAnamnesis.qRedFlagEye, AffectedEye.entries.toList(), { v, _ -> SAnamnesis.eye(v) },
            { it.redFlagEye }, { a, v -> a.copy(redFlagEye = v) }, visibleIf = { it.urgentRedFlag }),
        // ---- everything below is skipped once an urgent flag is set ----
        Question.Choice(POWER_KNOWN, SAnamnesis.qPowerKnown, PowerKnown.entries.toList(), { v, _ -> SAnamnesis.powerKnown(v) },
            { it.powerKnown }, { a, v -> a.copy(powerKnown = v, knownRx = a.knownRx.takeIf { v == PowerKnown.YES }) },
            visibleIf = { powerPathApplies(it) }),
        Question.Choice("readingFrequency", S.qReadingFreq, ReadingFrequency.entries.toList(), { v, _ -> S.readingFrequency(v) },
            { it.readingFrequency }, { a, v -> a.copy(readingFrequency = v) }, visibleIf = notUrgent),
        Question.Choice("screenHours", S.qScreenHours, ScreenHours.entries.toList(), { v, _ -> S.screenHours(v) },
            { it.screenHours }, { a, v -> a.copy(screenHours = v) }, visibleIf = notUrgent),
        Question.YesNo("nightDriving", S.qNightDriving, { it.nightDriving }, { a, v -> a.copy(nightDriving = v) }, visibleIf = notUrgent),
        Question.YesNo("outdoorWork", S.qOutdoorWork, { it.outdoorWork }, { a, v -> a.copy(outdoorWork = v) }, visibleIf = notUrgent),
        Question.Choice("occupation", S.qOccupation, Occupation.entries.toList(), { v, _ -> S.occupation(v) },
            { it.occupation }, { a, v -> a.copy(occupation = v) }, visibleIf = notUrgent),
        Question.Choice("budget", S.qBudget, Budget.entries.toList(), { v, c -> S.budget(v, c.budgetLowCap, c.budgetMidCap) },
            { it.budget }, { a, v -> a.copy(budget = v) }, visibleIf = notUrgent),
        Question.Choice("style", S.qStyle, StylePref.entries.toList(), { v, _ -> S.style(v) },
            { it.style }, { a, v -> a.copy(style = v) }, visibleIf = notUrgent),
        // Addition 4: frame preferences
        Question.Choice("colourPreference", S.qColour, ColourPref.entries.toList(), { v, _ -> S.colour(v) },
            { it.colourPreference }, { a, v -> a.copy(colourPreference = v) }, visibleIf = notUrgent),
        Question.Choice("lookPreference", S.qLook, LookPref.entries.toList(), { v, _ -> S.look(v) },
            { it.lookPreference }, { a, v -> a.copy(lookPreference = v) }, visibleIf = notUrgent),
        Question.YesNo("lightweightPreference", S.qLightweight, { it.lightweightPreference }, { a, v -> a.copy(lightweightPreference = v) },
            visibleIf = notUrgent),
    )

    fun visible(a: InterviewAnswers): List<Question> = all.filter { it.visibleIf(a) }
}
