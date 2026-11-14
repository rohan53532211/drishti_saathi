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
import com.drishtisaathi.i18n.Txt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.Locale

/**
 * CORE questions come before the eye check (age, the problem, red flags, near-task follow-ups, glasses, power);
 * LATER questions come after the face measurement (lifestyle and frame preferences).
 */
enum class Stage { CORE, LATER }

/** One interview question. Texts, options, stage and follow-up rules come from `assets/anamnesis_questions.json`. */
sealed class Question(
    val id: String,
    val text: Txt,
    val visibleIf: (InterviewAnswers) -> Boolean = { true },
    val stage: Stage = Stage.CORE,
    /** Still asked after an urgent red flag (everything else waits: the referral comes first). */
    val askWhenUrgent: Boolean = false,
) {

    class YesNo(
        id: String, text: Txt,
        val get: (InterviewAnswers) -> Boolean?,
        val set: (InterviewAnswers, Boolean) -> InterviewAnswers,
        visibleIf: (InterviewAnswers) -> Boolean = { true },
        stage: Stage = Stage.CORE, askWhenUrgent: Boolean = false,
    ) : Question(id, text, visibleIf, stage, askWhenUrgent)

    class Number(
        id: String, text: Txt,
        val hint: Txt,
        val range: IntRange,
        val allowUnknown: Boolean,
        val get: (InterviewAnswers) -> Int?,
        val set: (InterviewAnswers, Int?) -> InterviewAnswers,
        visibleIf: (InterviewAnswers) -> Boolean = { true },
        stage: Stage = Stage.CORE, askWhenUrgent: Boolean = false,
    ) : Question(id, text, visibleIf, stage, askWhenUrgent)

    class Choice<T : Any>(
        id: String, text: Txt,
        val options: List<T>,
        val label: (T, ChoiceContext) -> Txt,
        val get: (InterviewAnswers) -> T?,
        val set: (InterviewAnswers, T) -> InterviewAnswers,
        visibleIf: (InterviewAnswers) -> Boolean = { true },
        stage: Stage = Stage.CORE, askWhenUrgent: Boolean = false,
    ) : Question(id, text, visibleIf, stage, askWhenUrgent)

    /** Yes → staff type the old glasses numbers on a separate screen; No → none. (Not in the question file.) */
    class OldNumbers(id: String, text: Txt) : Question(id, text)
}

/** Values the option labels need from rules.json (e.g. budget caps). */
data class ChoiceContext(val budgetLowCap: Int, val budgetMidCap: Int)

// ---- the question file ------------------------------------------------------------------------

@Serializable data class QText(val en: String, val hi: String) { val txt: Txt get() = Txt(en, hi) }

/** One follow-up rule: `answer` = saved answer field whose value must be one of `in`; `flag` = urgent / notUrgent / powerPath. */
@Serializable data class QCondition(val answer: String? = null, val `in`: List<String> = emptyList(), val flag: String? = null)

@Serializable data class QSpec(
    val id: String,
    val stage: Stage,
    val askWhenUrgent: Boolean = false,
    val text: QText,
    val kind: String,
    val hint: QText? = null,
    val range: List<Int>? = null,
    val allowUnknown: Boolean = false,
    val options: Map<String, QText> = emptyMap(),
    val showIf: List<QCondition> = emptyList(),
)

@Serializable data class QuestionsFile(
    val schemaVersion: Int,
    val notes: String = "",
    val questions: List<QSpec>,
    /** The voice keyword lexicon ([com.drishtisaathi.voice.VoiceLexicon]). */
    val voiceKeywords: JsonObject,
    /** Topics the backend's AI interview covers (read by backend/app/anamnesis.py). */
    val aiTopics: JsonArray,
    /** The vision-profile survey (sections, questions, skip rules), read by the engine's Survey. */
    val survey: com.drishtisaathi.engine.profile.SurveyFile,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = false }
        fun parse(text: String): QuestionsFile = json.decodeFromString(serializer(), text)
    }
}

/** Where the question file comes from: the app sets [loader] to its assets; unit tests read the source tree. */
object QuestionBank {
    const val ASSET = "anamnesis_questions.json"
    @Volatile var loader: () -> String = { java.io.File("src/main/assets/$ASSET").readText(Charsets.UTF_8) }
    val text: String by lazy { loader() }
    val file: QuestionsFile by lazy { QuestionsFile.parse(text) }
    /** The survey block, checked once (unknown sections, rule targets, options). */
    val survey: com.drishtisaathi.engine.profile.SurveyFile by lazy { file.survey.also { it.validate() } }
}

/**
 * The adaptive, voice-first anamnesis. Follow-ups appear only when they apply (the file's showIf rules):
 * far: situation and since when; near: task and usual distance; glasses: age and satisfaction (else first pair);
 * a red flag: onset and which eye, and then nothing else is asked (urgent, the referral comes first).
 * The power path question ([POWER_KNOWN]) routes to the old-glasses screen (YES) or the eye check (NOT SURE / NO).
 */
object Questions {
    const val AGE = "age"
    const val GLASSES_AGE = "glassesAgeYears"
    /** Kept for sessions saved before the power path (the old yes/no question). */
    const val KNOWN_RX = "knownRx"
    const val VISION_PROBLEM = "visionProblem"
    const val POWER_KNOWN = "powerKnown"

    /** Sets the problem and the old yes/no fields the rules use; clears follow-ups that no longer apply. */
    fun setVisionProblem(a: InterviewAnswers, v: VisionProblem): InterviewAnswers = a.copy(
        visionProblem = v, distanceBlur = v.distance, nearBlur = v.near,
        distanceSituation = a.distanceSituation.takeIf { v.distance }, distanceSince = a.distanceSince.takeIf { v.distance },
        nearTask = a.nearTask.takeIf { v.near }, nearDistance = a.nearDistance.takeIf { v.near },
    )

    /** Power path is asked unless there is no trouble and no glasses (an urgent flag no longer stops it). */
    fun powerPathApplies(a: InterviewAnswers): Boolean =
        a.visionProblem != null && !(a.visionProblem == VisionProblem.NONE && a.wearsGlasses == false)

    /** Onset and eye belong to the red flag; cleared when neither flag is set any more. */
    private fun clearOnset(a: InterviewAnswers): InterviewAnswers =
        if (a.urgentRedFlag) a else a.copy(redFlagOnset = null, redFlagEye = null)

    // ---- how each question id reads and writes InterviewAnswers (texts and rules are in the file) ----

    private sealed interface Binding
    private class YesNoB(val get: (InterviewAnswers) -> Boolean?, val set: (InterviewAnswers, Boolean) -> InterviewAnswers) : Binding
    private class NumberB(val get: (InterviewAnswers) -> Int?, val set: (InterviewAnswers, Int?) -> InterviewAnswers) : Binding
    private class ChoiceB<T : Enum<T>>(
        val values: List<T>, val get: (InterviewAnswers) -> T?, val set: (InterviewAnswers, T) -> InterviewAnswers,
    ) : Binding

    private val bindings: Map<String, Binding> = mapOf(
        AGE to NumberB({ it.age }, { a, v -> a.copy(age = v) }),
        VISION_PROBLEM to ChoiceB(VisionProblem.entries, { it.visionProblem }, { a, v -> setVisionProblem(a, v) }),
        "distanceSituation" to ChoiceB(DistanceSituation.entries, { it.distanceSituation }, { a, v -> a.copy(distanceSituation = v) }),
        "distanceSince" to ChoiceB(ProblemSince.entries, { it.distanceSince }, { a, v -> a.copy(distanceSince = v) }),
        "nearTask" to ChoiceB(NearTask.entries, { it.nearTask }, { a, v -> a.copy(nearTask = v) }),
        "nearDistance" to ChoiceB(NearDistance.entries, { it.nearDistance }, { a, v -> a.copy(nearDistance = v) }),
        "wearsGlasses" to YesNoB({ it.wearsGlasses }, { a, v ->
            // Clearing the follow-ups keeps stale data out if the answer flips.
            if (v) a.copy(wearsGlasses = true, firstTimeWearer = null)
            else a.copy(wearsGlasses = false, glassesAgeYears = null, happyWithGlasses = null)
        }),
        GLASSES_AGE to NumberB({ it.glassesAgeYears }, { a, v -> a.copy(glassesAgeYears = v) }),
        "happyWithGlasses" to YesNoB({ it.happyWithGlasses }, { a, v -> a.copy(happyWithGlasses = v) }),
        "firstTimeWearer" to YesNoB({ it.firstTimeWearer }, { a, v -> a.copy(firstTimeWearer = v) }),
        "headacheStrain" to YesNoB({ it.headacheStrain }, { a, v -> a.copy(headacheStrain = v) }),
        "diabetesOrBp" to YesNoB({ it.diabetesOrBp }, { a, v -> a.copy(diabetesOrBp = v) }),
        "straightLinesBent" to YesNoB({ it.straightLinesBent }, { a, v -> a.copy(straightLinesBent = v) }),
        "redFlagFlashes" to YesNoB({ it.redFlagFlashes }, { a, v -> clearOnset(a.copy(redFlagFlashes = v)) }),
        "redFlagOther" to YesNoB({ it.redFlagOther }, { a, v -> clearOnset(a.copy(redFlagOther = v)) }),
        "redFlagOnset" to ChoiceB(RedFlagOnset.entries, { it.redFlagOnset }, { a, v -> a.copy(redFlagOnset = v) }),
        "redFlagEye" to ChoiceB(AffectedEye.entries, { it.redFlagEye }, { a, v -> a.copy(redFlagEye = v) }),
        POWER_KNOWN to ChoiceB(PowerKnown.entries, { it.powerKnown },
            { a, v -> a.copy(powerKnown = v, knownRx = a.knownRx.takeIf { v == PowerKnown.YES }) }),
        "readingFrequency" to ChoiceB(ReadingFrequency.entries, { it.readingFrequency }, { a, v -> a.copy(readingFrequency = v) }),
        "screenHours" to ChoiceB(ScreenHours.entries, { it.screenHours }, { a, v -> a.copy(screenHours = v) }),
        "nightDriving" to YesNoB({ it.nightDriving }, { a, v -> a.copy(nightDriving = v) }),
        "outdoorWork" to YesNoB({ it.outdoorWork }, { a, v -> a.copy(outdoorWork = v) }),
        "occupation" to ChoiceB(Occupation.entries, { it.occupation }, { a, v -> a.copy(occupation = v) }),
        "budget" to ChoiceB(Budget.entries, { it.budget }, { a, v -> a.copy(budget = v) }),
        "style" to ChoiceB(StylePref.entries, { it.style }, { a, v -> a.copy(style = v) }),
        "colourPreference" to ChoiceB(ColourPref.entries, { it.colourPreference }, { a, v -> a.copy(colourPreference = v) }),
        "lookPreference" to ChoiceB(LookPref.entries, { it.lookPreference }, { a, v -> a.copy(lookPreference = v) }),
        "lightweightPreference" to YesNoB({ it.lightweightPreference }, { a, v -> a.copy(lightweightPreference = v) }),
    )

    private val answersJson = Json { encodeDefaults = true; explicitNulls = true }

    /** The file's showIf rules: every condition must hold. */
    fun shows(conditions: List<QCondition>, a: InterviewAnswers): Boolean {
        if (conditions.isEmpty()) return true
        val obj by lazy { answersJson.encodeToJsonElement(InterviewAnswers.serializer(), a).jsonObject }
        return conditions.all { c ->
            when {
                c.flag == "urgent" -> a.urgentRedFlag
                c.flag == "notUrgent" -> !a.urgentRedFlag
                c.flag == "powerPath" -> powerPathApplies(a)
                c.flag != null -> error("unknown flag ${c.flag}")
                c.answer != null -> {
                    val v = obj[c.answer] ?: error("unknown answer field ${c.answer}")
                    v !is JsonNull && (v as JsonPrimitive).content in c.`in`
                }
                else -> error("empty condition")
            }
        }
    }

    private fun fill(s: String, c: ChoiceContext) =
        s.replace("{low}", String.format(Locale.US, "%,d", c.budgetLowCap)).replace("{mid}", String.format(Locale.US, "%,d", c.budgetMidCap))

    private fun <T : Enum<T>> choice(spec: QSpec, b: ChoiceB<T>, vis: (InterviewAnswers) -> Boolean): Question {
        val labels = b.values.associateWith { v -> requireNotNull(spec.options[v.name]) { "${spec.id}: no label for ${v.name}" } }
        require(spec.options.keys == b.values.map { it.name }.toSet()) { "${spec.id}: options ${spec.options.keys}" }
        return Question.Choice(spec.id, spec.text.txt, b.values, { v, c -> labels.getValue(v).let { Txt(fill(it.en, c), fill(it.hi, c)) } },
            b.get, b.set, vis, spec.stage, spec.askWhenUrgent)
    }

    /** Builds the questions from [file]; throws when the file and the bindings do not match. */
    fun build(file: QuestionsFile): List<Question> {
        val ids = file.questions.map { it.id }
        require(ids.toSet() == bindings.keys && ids.size == ids.toSet().size) { "question ids ${ids.toSet() xor bindings.keys}" }
        return file.questions.map { spec ->
            val vis: (InterviewAnswers) -> Boolean = { shows(spec.showIf, it) }
            shows(spec.showIf, InterviewAnswers()) // fails early on unknown fields or flags
            when (val b = bindings.getValue(spec.id)) {
                is YesNoB -> { require(spec.kind == "yesNo") { spec.id }; Question.YesNo(spec.id, spec.text.txt, b.get, b.set, vis, spec.stage, spec.askWhenUrgent) }
                is NumberB -> {
                    require(spec.kind == "number") { spec.id }
                    val r = requireNotNull(spec.range) { "${spec.id}: range" }
                    Question.Number(spec.id, spec.text.txt, requireNotNull(spec.hint) { "${spec.id}: hint" }.txt, r[0]..r[1],
                        spec.allowUnknown, b.get, b.set, vis, spec.stage, spec.askWhenUrgent)
                }
                is ChoiceB<*> -> { require(spec.kind == "choice") { spec.id }; @Suppress("UNCHECKED_CAST") choice(spec, b as ChoiceB<VisionProblem>, vis) }
            }
        }
    }

    private infix fun <T> Set<T>.xor(o: Set<T>) = (this - o) + (o - this)

    val all: List<Question> by lazy { build(QuestionBank.file) }

    /**
     * Every question that applies to these answers (summary, edit screen, PDF). After an urgent red flag only the
     * red-flag follow-ups are left: nothing else is asked, the referral comes first.
     */
    fun visible(a: InterviewAnswers): List<Question> = all.filter { it.visibleIf(a) }

    /** The questions asked in [stage]. */
    fun visibleIn(stage: Stage, a: InterviewAnswers): List<Question> = visible(a).filter { it.stage == stage }
}
