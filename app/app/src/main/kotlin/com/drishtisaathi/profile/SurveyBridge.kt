package com.drishtisaathi.profile

import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.Occupation
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.ScreenHours
import com.drishtisaathi.engine.profile.Freq
import com.drishtisaathi.engine.profile.SurveyAnswers

/**
 * The survey covers some of the older "later" interview questions in more detail. Their fields are filled from the
 * survey and marked answered, so nobody is asked the same thing twice (the whole survey stays within 4 minutes).
 * A field the customer already answered directly (AI interview, earlier session) is never overwritten.
 */
object SurveyBridge {
    private fun screenBand(hours: Double, rules: RulesConfig): ScreenHours {
        // the same bands as the interview question: 0–2, 2–4, 4+ (the upper band starts at lensEngine.screenHoursFrom)
        val high = rules.lensEngine.screenHoursFrom
        return when {
            hours >= high -> ScreenHours.H4_PLUS
            hours >= high / 2 -> ScreenHours.H2_4
            else -> ScreenHours.H0_2
        }
    }

    /** The occupation the work activities point to, when it is clear; null = ask the occupation question. */
    fun occupation(activities: Set<String>): Occupation? = when {
        "DESKTOP" in activities -> Occupation.OFFICE_COMPUTER
        "FINE_HANDWORK" in activities -> Occupation.FINE_HANDWORK
        "FIELD_FARM" in activities -> Occupation.FIELD_OUTDOOR
        "MEETINGS" in activities -> Occupation.SHOP_BUSINESS
        else -> null
    }

    fun apply(a: InterviewAnswers, answered: Set<String>, s: SurveyAnswers, rules: RulesConfig): Pair<InterviewAnswers, Set<String>> {
        var out = a
        val ids = answered.toMutableSet()
        val from = rules.lensEngine.symptomFrom
        fun fill(id: String, set: (InterviewAnswers) -> InterviewAnswers) { if (id !in answered) { out = set(out); ids += id } }

        val head = s.freq("headacheFreq"); val tired = s.freq("tiredEyes")
        if (head != null) fill("headacheStrain") { it.copy(headacheStrain = head.atLeast(from) || tired?.atLeast(from) == true) }
        s.number("screenHoursDay")?.let { h -> fill("screenHours") { it.copy(screenHours = screenBand(h, rules)) } }
        s.one("indoorOutdoor")?.let { v -> fill("outdoorWork") { it.copy(outdoorWork = v == "OUTDOOR") } }
        if (s.answered("workActivities")) occupation(s.set("workActivities"))?.let { o -> fill("occupation") { it.copy(occupation = o) } }
        return out to ids
    }

    /**
     * Survey answers the AI voice interview already heard ([fields] = its extracted answers): single choices,
     * never / sometimes / often and hours, each checked against the survey file. Anything else is ignored, and the
     * survey then asks only what is still open.
     */
    fun fromAi(fields: kotlinx.serialization.json.JsonObject, file: com.drishtisaathi.engine.profile.SurveyFile, base: SurveyAnswers = SurveyAnswers()): SurveyAnswers {
        var out = base
        for (q in file.questions) {
            if (out.answered(q.id)) continue
            val v = fields[q.id] as? kotlinx.serialization.json.JsonPrimitive ?: continue
            when (q.kind) {
                com.drishtisaathi.engine.profile.SurveyKind.CHOICE, com.drishtisaathi.engine.profile.SurveyKind.FREQ ->
                    v.content.takeIf { v.isString && it in q.optionCodes }?.let { out = out.with(q.id, listOf(it)) }
                com.drishtisaathi.engine.profile.SurveyKind.NUMBER ->
                    v.content.toIntOrNull()?.takeIf { !v.isString && it in q.min..q.max }?.let { out = out.with(q.id, listOf(it.toString())) }
                else -> {}
            }
        }
        return out
    }

    /** Low-light or glare complaints (for the contrast test's eye-health note). */
    fun lowLightOrGlare(s: SurveyAnswers, from: Freq): Boolean =
        listOf("lowLightTrouble", "glareOncoming", "lowLightDriving").any { s.freq(it)?.atLeast(from) == true }
}
