package com.drishtisaathi.aivoice

import com.drishtisaathi.engine.DistanceSituation
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.NearTask
import com.drishtisaathi.engine.PowerKnown
import com.drishtisaathi.engine.ReadingFrequency
import com.drishtisaathi.engine.ScreenHours
import com.drishtisaathi.engine.VisionProblem
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.interview.Question
import com.drishtisaathi.interview.Questions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * AI voice anamnesis (Sarvam, through our backend). The backend asks the questions and returns the customer's
 * answers as fields; [AiProfileMapper] puts them into the app's own [InterviewAnswers], and the existing interview,
 * power path, tests and rules engine carry on from there. The AI never decides a referral or lens advice.
 */

/** Languages offered for the AI interview. Add one here (and on the backend) to offer more. */
enum class AiLanguage(val code: String, val appLang: Lang) {
    HINDI("hi-IN", Lang.HI),
    ENGLISH("en-IN", Lang.EN),
    ;
    companion object {
        fun forApp(l: Lang) = entries.firstOrNull { it.appLang == l } ?: HINDI
    }
}

@Serializable
data class AiProgress(val question: Int = 0, val expected: Int = 8, val max: Int = 8)

@Serializable
data class AiStartResponse(
    @SerialName("session_id") val sessionId: String,
    val question: String,
    val topic: String? = null,
    val audio: String? = null,
    @SerialName("tts_error") val ttsError: String? = null,
    val progress: AiProgress = AiProgress(),
)

@Serializable
data class AiTurnResponse(
    val transcript: String,
    val extracted: JsonObject = JsonObject(emptyMap()),
    @SerialName("next_question") val nextQuestion: String,
    val topic: String? = null,
    val audio: String? = null,
    @SerialName("tts_error") val ttsError: String? = null,
    @SerialName("should_continue") val shouldContinue: Boolean,
    val urgent: Boolean = false,
    val source: String = "scripted",
    val progress: AiProgress = AiProgress(),
)

/** One question and answer, kept with the session for staff and the shop PDF (never the customer copy or the bridge). */
@Serializable
data class AiTurn(val n: Int, val topic: String? = null, val question: String, val answer: String? = null, val source: String = "scripted")

@Serializable
data class AiProfile(
    val language: String,
    @SerialName("questions_asked") val questionsAsked: Int = 0,
    val answers: JsonObject = JsonObject(emptyMap()),
    @SerialName("red_flags_asked") val redFlagsAsked: Boolean = false,
    @SerialName("red_flag_unclear") val redFlagUnclear: Boolean = false,
    val urgent: Boolean = false,
    val transcript: List<AiTurn> = emptyList(),
)

/** Stored with the session (Measurements.aiInterview). */
@Serializable
data class AiInterviewRecord(
    val language: String,
    val consentAt: Long,
    val turns: List<AiTurn>,
    /** True when the AI interview stopped (backend failed) and the standard interview took over. */
    val fellBack: Boolean = false,
)

/** Clean error codes from the backend (detail.code), plus the app's own for transport problems. */
class AiException(val code: String, val httpStatus: Int = 0) : Exception(code) {
    /** The backend or Sarvam is not working (counts towards the switch to the standard interview). */
    val backendFailure: Boolean get() = code !in CUSTOMER_SIDE
    companion object {
        /** The customer can simply try again: these never count as a backend failure. */
        val CUSTOMER_SIDE = setOf("EMPTY_TRANSCRIPT", "BAD_AUDIO", "AUDIO_TOO_LONG", "MIC")
        /** No point retrying: switch to the standard interview at once. */
        val PERMANENT = setOf("NOT_CONFIGURED", "SARVAM_AUTH", "NO_SERVER", "UNAUTHORIZED", "SESSION_EXPIRED")
    }
}

interface AiAnamnesisApi {
    fun start(language: AiLanguage): AiStartResponse
    fun turn(sessionId: String, wavBase64: String): AiTurnResponse
    fun finish(sessionId: String): AiProfile
}

/** Result of the mapping: the merged answers and the question ids that now count as answered. */
data class MappedAnswers(val answers: InterviewAnswers, val answered: Set<String>)

object AiProfileMapper {
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
    private inline fun <reified E : Enum<E>> JsonObject.enum(k: String): E? =
        str(k)?.let { v -> enumValues<E>().firstOrNull { it.name == v } }

    private fun question(id: String): Question = Questions.all.first { it.id == id }

    private fun yesNo(a: InterviewAnswers, id: String, v: Boolean) = (question(id) as Question.YesNo).set(a, v)

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> choice(a: InterviewAnswers, id: String, v: T) = (question(id) as Question.Choice<T>).set(a, v)

    /**
     * Merges the AI answers into [base] through the same setters the interview buttons use (so follow-ups are cleared
     * the same way). Unknown or invalid values are ignored. Safety:
     * - red flags: a "yes" is kept; an unclear answer, or no answer, leaves the red-flag questions for the phone to ask;
     * - the power question is pre-selected but never marked answered, so the existing power path (old numbers screen
     *   or quick estimate) still runs from the standard interview.
     */
    fun map(base: InterviewAnswers, profile: AiProfile, alreadyAnswered: Set<String> = emptySet()): MappedAnswers {
        val f = profile.answers
        var a = base
        val ids = alreadyAnswered.toMutableSet()

        f.int("age")?.takeIf { it in 3..110 }?.let { a = a.copy(age = it); ids += Questions.AGE }
        f.enum<VisionProblem>("visionProblem")?.let { a = Questions.setVisionProblem(a, it); ids += Questions.VISION_PROBLEM }
        f.enum<DistanceSituation>("distanceSituation")?.takeIf { a.visionProblem?.distance == true }
            ?.let { a = choice(a, "distanceSituation", it); ids += "distanceSituation" }
        f.enum<NearTask>("nearTask")?.takeIf { a.visionProblem?.near == true }
            ?.let { a = choice(a, "nearTask", it); ids += "nearTask" }
        f.enum<ScreenHours>("screenHours")?.let { a = choice(a, "screenHours", it); ids += "screenHours" }
        f.enum<ReadingFrequency>("readingFrequency")?.let { a = choice(a, "readingFrequency", it); ids += "readingFrequency" }
        f.bool("outdoorWork")?.let { a = yesNo(a, "outdoorWork", it); ids += "outdoorWork" }
        f.bool("nightDriving")?.let { a = yesNo(a, "nightDriving", it); ids += "nightDriving" }

        // The app asks "headaches or tired eyes?" as one question: yes if either was yes, no only if both were no.
        val headache = f.bool("headacheStrain")
        val strain = f.bool("eyeStrain")
        val combined = when {
            headache == true || strain == true -> true
            headache == false && strain == false -> false
            else -> null
        }
        combined?.let { a = yesNo(a, "headacheStrain", it); ids += "headacheStrain" }

        f.bool("wearsGlasses")?.let { g ->
            a = yesNo(a, "wearsGlasses", g); ids += "wearsGlasses"
            if (g) f.int("glassesAgeYears")?.takeIf { it in 0..60 }?.let { a = a.copy(glassesAgeYears = it); ids += Questions.GLASSES_AGE }
        }
        // pre-selected only: the power question itself is asked again by the phone (it opens the power path)
        f.enum<PowerKnown>("powerKnown")?.let { a = a.copy(powerKnown = it, knownRx = a.knownRx.takeIf { _ -> it == PowerKnown.YES }) }

        // ---- red flags ----
        val flashes = f.bool("redFlagFlashes")
        val other = f.bool("redFlagOther")
        val any = f.bool("redFlagAny")
        when {
            flashes == true || other == true || any == true -> {
                if (flashes == true) { a = yesNo(a, "redFlagFlashes", true); ids += "redFlagFlashes" }
                if (other == true || (any == true && flashes != true)) { a = yesNo(a, "redFlagOther", true); ids += "redFlagOther" }
            }
            // a "no" counts only when the question was really asked and nothing in the answers contradicted it
            profile.redFlagsAsked && !profile.redFlagUnclear -> {
                if (flashes == false || any == false) { a = yesNo(a, "redFlagFlashes", false); ids += "redFlagFlashes" }
                if (other == false || any == false) { a = yesNo(a, "redFlagOther", false); ids += "redFlagOther" }
            }
            else -> {} // unclear or not asked: the phone asks the standard red-flag questions
        }

        val visible = Questions.visible(a).map { it.id }.toSet()
        return MappedAnswers(a, ids.filter { it in visible }.toSet() - Questions.POWER_KNOWN)
    }

    /**
     * Profile built on the phone from the turns received so far, used when the backend fails and /finish cannot be
     * reached. Red flags are always left for the phone to ask (the backend's own check did not run to the end).
     */
    fun localProfile(language: AiLanguage, turns: List<AiTurn>, extracted: JsonObject): AiProfile = AiProfile(
        language = language.code,
        questionsAsked = turns.size,
        answers = extracted,
        redFlagsAsked = false,
        redFlagUnclear = true,
        urgent = listOf("redFlagFlashes", "redFlagOther", "redFlagAny").any { (extracted[it] as? JsonPrimitive)?.booleanOrNull == true },
        transcript = turns,
    )

    /** Later answers replace earlier ones, except that a red flag once said yes stays yes. */
    fun mergeExtracted(old: JsonObject, new: JsonObject): JsonObject = buildJsonObject {
        old.forEach { (k, v) -> put(k, v) }
        new.forEach { (k, v) ->
            val sticky = k.startsWith("redFlag") && (old[k] as? JsonPrimitive)?.booleanOrNull == true
            if (!sticky) put(k, v)
        }
    }
}

/** What the AI interview screen shows. */
data class AiUiState(
    val phase: Phase = Phase.IDLE,
    val question: String? = null,
    /** Base64 WAV of [question]; null when TTS failed (the phone's own voice reads it instead). */
    val audio: String? = null,
    val lastTranscript: String? = null,
    val progress: AiProgress = AiProgress(),
    val errorCode: String? = null,
    val failures: Int = 0,
    val urgent: Boolean = false,
) {
    enum class Phase {
        IDLE, STARTING, ASKING, THINKING,
        /** Last call failed; the customer can retry. */
        RETRY,
        /** The backend ended the interview. */
        DONE,
        /** Two backend failures (or a permanent one): continue with the standard interview. */
        FALLBACK,
    }
}

/**
 * State machine for one AI interview. Blocking [api] calls: call these from a background dispatcher.
 * Two backend failures in a row (or a permanent one, like a missing key) switch to [AiUiState.Phase.FALLBACK];
 * answers received so far are kept ([profileSoFar]).
 */
class AiInterviewController(private val api: AiAnamnesisApi, val language: AiLanguage, private val maxFailures: Int = 2) {
    private val _state = MutableStateFlow(AiUiState())
    val state: StateFlow<AiUiState> = _state.asStateFlow()

    private var sessionId: String? = null
    private val turns = mutableListOf<AiTurn>()
    private var extracted = JsonObject(emptyMap())

    val transcript: List<AiTurn> get() = turns.toList()

    private fun fail(e: AiException, phaseIfRetry: AiUiState.Phase = AiUiState.Phase.RETRY) {
        _state.update { s ->
            val failures = if (e.backendFailure) s.failures + 1 else s.failures
            val fallback = e.code in AiException.PERMANENT || failures >= maxFailures
            s.copy(phase = if (fallback) AiUiState.Phase.FALLBACK else phaseIfRetry, errorCode = e.code, failures = failures)
        }
    }

    fun start() {
        _state.update { it.copy(phase = AiUiState.Phase.STARTING, errorCode = null) }
        try {
            val r = api.start(language)
            sessionId = r.sessionId
            turns.clear()
            turns += AiTurn(1, r.topic, r.question)
            _state.update { it.copy(phase = AiUiState.Phase.ASKING, question = r.question, audio = r.audio, progress = r.progress, failures = 0) }
        } catch (e: AiException) {
            fail(e)
        }
    }

    /** Sends one recorded answer (WAV bytes, 16 kHz mono). */
    fun answer(wav: ByteArray) {
        val sid = sessionId ?: return start()
        _state.update { it.copy(phase = AiUiState.Phase.THINKING, errorCode = null) }
        try {
            val r = api.turn(sid, java.util.Base64.getEncoder().encodeToString(wav))
            turns[turns.lastIndex] = turns.last().copy(answer = r.transcript)
            extracted = AiProfileMapper.mergeExtracted(extracted, r.extracted)
            if (r.shouldContinue) {
                turns += AiTurn(turns.size + 1, r.topic, r.nextQuestion, source = r.source)
            }
            _state.update {
                it.copy(
                    phase = if (r.shouldContinue) AiUiState.Phase.ASKING else AiUiState.Phase.DONE,
                    question = r.nextQuestion, audio = r.audio, lastTranscript = r.transcript, progress = r.progress,
                    failures = 0, urgent = r.urgent,
                )
            }
        } catch (e: AiException) {
            fail(e, AiUiState.Phase.RETRY)
        }
    }

    /** Microphone problem on the phone (not a backend failure). */
    fun micFailed() = fail(AiException("MIC"))

    /** Back to the current question after an error the customer can retry. */
    fun retry() {
        if (sessionId == null) start() else _state.update { it.copy(phase = AiUiState.Phase.ASKING, errorCode = null) }
    }

    /** The profile so far, built on the phone (no network). */
    fun profileSoFar(): AiProfile = AiProfileMapper.localProfile(language, turns.filter { it.answer != null }, extracted)

    /** Ends the interview: the backend's profile, or the one built on the phone if the backend cannot be reached. */
    fun finish(): AiProfile {
        val sid = sessionId ?: return profileSoFar()
        return try {
            api.finish(sid).let { p -> p.copy(transcript = p.transcript.filter { it.answer != null }) }
        } catch (e: AiException) {
            profileSoFar()
        } finally {
            sessionId = null
        }
    }
}

/** Short JSON helper for tests and the client. */
internal fun JsonElement.codeOrNull(): String? = runCatching {
    ((this as JsonObject)["detail"] as JsonObject)["code"]!!.jsonPrimitive.content
}.getOrNull()
