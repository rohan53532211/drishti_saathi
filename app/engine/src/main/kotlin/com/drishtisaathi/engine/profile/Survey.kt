package com.drishtisaathi.engine.profile

import com.drishtisaathi.engine.InterviewAnswers
import kotlinx.serialization.Serializable

/** Text in both languages, as it stands in the JSON assets. */
@Serializable data class L10n(val en: String, val hi: String)

/** "Never / Sometimes / Often" answers. */
@Serializable enum class Freq { NEVER, SOMETIMES, OFTEN;
    fun atLeast(other: Freq): Boolean = ordinal >= other.ordinal
}

@Serializable enum class DriveFrequency { NEVER, MONTHLY, WEEKLY, DAILY }

@Serializable enum class SurveyKind { FREQ, CHOICE, MULTI, NUMBER, YES_NO }

/** A survey section, colour-coded like a vision-profile questionnaire (colour = RGB hex). */
@Serializable
data class SurveySection(val id: String, val colour: String, val title: L10n, val why: L10n)

/**
 * One skip rule; every rule of a question must hold.
 * - [flag]: `urgent` (a red flag was reported), `notUrgent`;
 * - [survey] with [in] / [notIn]: an earlier survey answer (a multi answer matches when any picked option is listed);
 * - [minAge] / [maxAge]: the customer's age (an unknown age fails the rule).
 */
@Serializable
data class SurveyCondition(
    val flag: String? = null,
    val survey: String? = null,
    val `in`: List<String> = emptyList(),
    val notIn: List<String> = emptyList(),
    val minAge: Int? = null,
    val maxAge: Int? = null,
)

/**
 * One survey question. [options] for CHOICE / MULTI (FREQ and YES_NO have fixed options); [say] = spoken keywords per
 * option; NUMBER uses [min]..[max] in steps of [step] with [unit]. [why] is the "Why we ask" line (spoken on "?").
 */
@Serializable
data class SurveyQuestion(
    val id: String,
    val section: String,
    val kind: SurveyKind,
    val text: L10n,
    val why: L10n,
    val options: Map<String, L10n> = emptyMap(),
    val say: Map<String, List<String>> = emptyMap(),
    val min: Int = 0,
    val max: Int = 0,
    val step: Int = 1,
    val unit: L10n? = null,
    val showIf: List<SurveyCondition> = emptyList(),
) {
    /** Option codes in display order. */
    val optionCodes: List<String>
        get() = when (kind) {
            SurveyKind.FREQ -> Freq.entries.map { it.name }
            SurveyKind.YES_NO -> listOf(YES, NO)
            else -> options.keys.toList()
        }

    companion object { const val YES = "YES"; const val NO = "NO" }
}

/** The `survey` block of anamnesis_questions.json. */
@Serializable
data class SurveyFile(
    val notes: String = "",
    /** The whole survey must stay within this many minutes. */
    val maxMinutes: Int,
    /** Planning figure: seconds one question takes by tap or voice. */
    val secondsPerQuestion: Int,
    val sections: List<SurveySection>,
    val questions: List<SurveyQuestion>,
) {
    fun section(id: String): SurveySection = sections.first { it.id == id }
    fun question(id: String): SurveyQuestion? = questions.firstOrNull { it.id == id }

    /** Throws when the file is inconsistent (unknown sections or rule targets, options missing, bad ranges). */
    fun validate() {
        val sec = sections.map { it.id }
        require(sec.toSet().size == sec.size) { "duplicate survey section" }
        val ids = questions.map { it.id }
        require(ids.toSet().size == ids.size) { "duplicate survey question id" }
        questions.forEachIndexed { i, q ->
            require(q.section in sec) { "${q.id}: unknown section ${q.section}" }
            when (q.kind) {
                SurveyKind.CHOICE, SurveyKind.MULTI -> require(q.options.size >= 2) { "${q.id}: needs options" }
                SurveyKind.NUMBER -> require(q.max > q.min && q.step > 0 && q.unit != null) { "${q.id}: number range / unit" }
                else -> require(q.options.isEmpty()) { "${q.id}: fixed options" }
            }
            require(q.say.keys.all { it in q.optionCodes }) { "${q.id}: spoken keywords for unknown option" }
            q.showIf.forEach { c ->
                require(listOfNotNull(c.flag, c.survey, c.minAge, c.maxAge).isNotEmpty()) { "${q.id}: empty rule" }
                c.flag?.let { require(it in FLAGS) { "${q.id}: unknown flag $it" } }
                c.survey?.let { s ->
                    // a rule may only look back, so the order of the questions is also the order of asking
                    require(ids.indexOf(s) in 0 until i) { "${q.id}: rule on $s, which is not asked earlier" }
                    val target = questions[ids.indexOf(s)]
                    require((c.`in` + c.notIn).isNotEmpty() && (c.`in` + c.notIn).all { it in target.optionCodes }) { "${q.id}: rule values for $s" }
                }
            }
        }
    }

    companion object { val FLAGS = setOf("urgent", "notUrgent") }
}

/** The survey's answers: question id → picked option codes (one for a single choice, a number as text). */
@Serializable
data class SurveyAnswers(val values: Map<String, List<String>> = emptyMap()) {
    fun answered(id: String): Boolean = id in values
    fun one(id: String): String? = values[id]?.firstOrNull()
    fun set(id: String): Set<String> = values[id]?.toSet().orEmpty()
    fun number(id: String): Double? = one(id)?.toDoubleOrNull()
    fun freq(id: String): Freq? = one(id)?.let { v -> Freq.entries.firstOrNull { it.name == v } }
    fun yes(id: String): Boolean? = one(id)?.let { it == SurveyQuestion.YES }
    fun with(id: String, picked: List<String>): SurveyAnswers = copy(values = values + (id to picked))
}

/** Skip logic and progress of the survey (pure). */
object Survey {

    fun shows(q: SurveyQuestion, a: InterviewAnswers, s: SurveyAnswers): Boolean = q.showIf.all { c ->
        val flagOk = when (c.flag) {
            null -> true
            "urgent" -> a.urgentRedFlag
            "notUrgent" -> !a.urgentRedFlag
            else -> error("unknown flag ${c.flag}")
        }
        val surveyOk = c.survey?.let { id ->
            val picked = s.values[id] ?: return@let false
            (c.`in`.isEmpty() || picked.any { it in c.`in` }) && (c.notIn.isEmpty() || picked.none { it in c.notIn })
        } ?: true
        val age = a.age
        val ageOk = (c.minAge == null || (age != null && age >= c.minAge)) && (c.maxAge == null || (age != null && age <= c.maxAge))
        flagOk && surveyOk && ageOk
    }

    /**
     * The questions that apply, in order. [sections] limits the survey (the emergency short path asks the SAFETY
     * section only); a block whose rule fails is skipped.
     */
    fun visible(file: SurveyFile, a: InterviewAnswers, s: SurveyAnswers, sections: Set<String>? = null): List<SurveyQuestion> =
        file.questions.filter { (sections == null || it.section in sections) && shows(it, a, s) }

    fun nextUnanswered(file: SurveyFile, a: InterviewAnswers, s: SurveyAnswers, sections: Set<String>? = null): SurveyQuestion? =
        visible(file, a, s, sections).firstOrNull { !s.answered(it.id) }

    /** Answers of questions that no longer apply are dropped (e.g. the driving block after "never"). */
    fun prune(file: SurveyFile, a: InterviewAnswers, s: SurveyAnswers): SurveyAnswers {
        var cur = s
        // rules only look back, so one pass in file order is enough
        for (q in file.questions) if (cur.answered(q.id) && !shows(q, a, cur)) cur = cur.copy(values = cur.values - q.id)
        return cur
    }

    /** Planned duration in seconds for these answers (every visible question). */
    fun plannedSeconds(file: SurveyFile, a: InterviewAnswers, s: SurveyAnswers): Int =
        visible(file, a, s).size * file.secondsPerQuestion
}
