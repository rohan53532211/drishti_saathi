package com.drishtisaathi.voice

import com.drishtisaathi.i18n.SVoice
import com.drishtisaathi.i18n.Txt

/** Why voice gave up on this question; the buttons always remain. */
enum class FallbackReason { NOT_UNDERSTOOD, MIC_PERMISSION, UNAVAILABLE }

/** Recogniser failures, already mapped from Android error codes (see [RecognitionErrors]). */
enum class RecognitionErrorKind { NO_SPEECH, NO_MATCH, PERMISSION, UNAVAILABLE, BUSY, OTHER }

enum class ConfirmPhase { SPEAKING, LISTENING, TAP_ONLY }

sealed interface DialogueState {
    data object Idle : DialogueState
    /** TTS is reading the question (attempt 0) or the apology + question (attempt 1). */
    data class Asking(val attempt: Int) : DialogueState
    data class Listening(val attempt: Int, val partial: String = "") : DialogueState
    /** "आपने कहा: …. सही है?" — spoken, then a spoken yes/no or a tap on ✓ / ✗. */
    data class Confirming(
        val answer: VoiceAnswer,
        val readBack: Txt,
        val attempt: Int,
        val phase: ConfirmPhase,
        val heard: String,
    ) : DialogueState
    data class Accepted(val answer: VoiceAnswer) : DialogueState
    data class Fallback(val reason: FallbackReason) : DialogueState
}

sealed interface DialogueEvent {
    /** The last utterance finished (or TTS is unavailable). */
    data object SpeechDone : DialogueEvent
    data class Partial(val text: String) : DialogueEvent
    data class Heard(val hypotheses: List<String>, val confidences: List<Float>? = null) : DialogueEvent
    data class RecognitionFailed(val kind: RecognitionErrorKind) : DialogueEvent
    /** ✓ (true) or ✗ (false) tapped on the read-back. */
    data class Tapped(val correct: Boolean) : DialogueEvent
    data object Stop : DialogueEvent
}

sealed interface DialogueEffect {
    data class Speak(val text: Txt) : DialogueEffect
    data object Listen : DialogueEffect
    data object StopListening : DialogueEffect
    data object StopSpeaking : DialogueEffect
    /** Apply the answer exactly like the matching button. */
    data class Commit(val answer: VoiceAnswer) : DialogueEffect
}

/**
 * One question's voice dialogue: Asking → Listening → Confirming → Accepted, with one Retry (re-ask) and then
 * Fallback to the buttons. Pure: callers feed events and execute the returned effects.
 *
 * @param listen false = read-aloud only (no mic): the question is spoken and the dialogue ends.
 */
class VoiceDialogue(
    private val question: Txt,
    private val reAsk: Txt,
    private val listen: Boolean,
    private val parseAnswer: (hypotheses: List<String>, confidences: List<Float>?) -> ParseResult,
    private val parseYesNo: (hypotheses: List<String>) -> ParseResult,
    private val readBack: (VoiceAnswer) -> Txt,
    private val maxAttempts: Int = 2,
) {
    var state: DialogueState = DialogueState.Idle
        private set

    fun start(): List<DialogueEffect> {
        state = DialogueState.Asking(0)
        return listOf(DialogueEffect.StopListening, DialogueEffect.Speak(question))
    }

    fun on(e: DialogueEvent): List<DialogueEffect> {
        val s = state
        if (e == DialogueEvent.Stop) {
            if (s == DialogueState.Idle) return emptyList()
            state = DialogueState.Idle
            return listOf(DialogueEffect.StopListening, DialogueEffect.StopSpeaking)
        }
        return when (s) {
            is DialogueState.Asking -> when (e) {
                DialogueEvent.SpeechDone ->
                    if (listen) go(DialogueState.Listening(s.attempt), DialogueEffect.Listen) else go(DialogueState.Idle)
                else -> emptyList()
            }
            is DialogueState.Listening -> when (e) {
                is DialogueEvent.Partial -> go(s.copy(partial = e.text))
                is DialogueEvent.Heard -> when (val r = parseAnswer(e.hypotheses, e.confidences)) {
                    is ParseResult.Parsed -> {
                        val rb = readBack(r.answer)
                        go(
                            DialogueState.Confirming(r.answer, rb, s.attempt, ConfirmPhase.SPEAKING, e.hypotheses.firstOrNull().orEmpty()),
                            DialogueEffect.Speak(rb),
                        )
                    }
                    else -> fail(s.attempt)
                }
                is DialogueEvent.RecognitionFailed -> when (e.kind) {
                    RecognitionErrorKind.PERMISSION -> go(DialogueState.Fallback(FallbackReason.MIC_PERMISSION))
                    RecognitionErrorKind.UNAVAILABLE -> go(DialogueState.Fallback(FallbackReason.UNAVAILABLE))
                    else -> fail(s.attempt)
                }
                else -> emptyList()
            }
            is DialogueState.Confirming -> when (e) {
                DialogueEvent.SpeechDone -> when {
                    s.phase != ConfirmPhase.SPEAKING -> emptyList()
                    listen -> go(s.copy(phase = ConfirmPhase.LISTENING), DialogueEffect.Listen)
                    else -> go(s.copy(phase = ConfirmPhase.TAP_ONLY))
                }
                is DialogueEvent.Tapped ->
                    if (e.correct) accept(s.answer) else fail(s.attempt)
                is DialogueEvent.Heard -> {
                    if (s.phase != ConfirmPhase.LISTENING) return emptyList()
                    val r = parseYesNo(e.hypotheses)
                    val yes = (r as? ParseResult.Parsed)?.answer as? VoiceAnswer.YesNo
                    when (yes?.value) {
                        true -> accept(s.answer)
                        false -> fail(s.attempt)
                        null -> go(s.copy(phase = ConfirmPhase.TAP_ONLY), DialogueEffect.Speak(SVoice.tapToConfirm))
                    }
                }
                is DialogueEvent.RecognitionFailed ->
                    if (s.phase == ConfirmPhase.LISTENING) go(s.copy(phase = ConfirmPhase.TAP_ONLY), DialogueEffect.Speak(SVoice.tapToConfirm))
                    else emptyList()
                else -> emptyList()
            }
            DialogueState.Idle, is DialogueState.Accepted, is DialogueState.Fallback -> emptyList()
        }
    }

    private fun accept(a: VoiceAnswer): List<DialogueEffect> =
        go(DialogueState.Accepted(a), DialogueEffect.StopListening, DialogueEffect.StopSpeaking, DialogueEffect.Commit(a))

    /** Not understood or rejected: re-ask once, then fall back to the buttons. */
    private fun fail(attempt: Int): List<DialogueEffect> =
        if (attempt + 1 < maxAttempts) {
            go(DialogueState.Asking(attempt + 1), DialogueEffect.StopListening, DialogueEffect.Speak(reAsk))
        } else {
            go(DialogueState.Fallback(FallbackReason.NOT_UNDERSTOOD), DialogueEffect.StopListening, DialogueEffect.Speak(SVoice.fallback))
        }

    private fun go(next: DialogueState, vararg effects: DialogueEffect): List<DialogueEffect> {
        state = next
        return effects.toList()
    }
}

/** Android SpeechRecognizer error codes → [RecognitionErrorKind]. Literal values so this stays pure. */
object RecognitionErrors {
    fun kind(code: Int): RecognitionErrorKind = when (code) {
        6 -> RecognitionErrorKind.NO_SPEECH // ERROR_SPEECH_TIMEOUT
        7 -> RecognitionErrorKind.NO_MATCH // ERROR_NO_MATCH
        9 -> RecognitionErrorKind.PERMISSION // ERROR_INSUFFICIENT_PERMISSIONS
        // NETWORK_TIMEOUT, NETWORK, SERVER, TOO_MANY_REQUESTS, SERVER_DISCONNECTED, LANGUAGE_NOT_SUPPORTED, LANGUAGE_UNAVAILABLE
        1, 2, 4, 10, 11, 12, 13 -> RecognitionErrorKind.UNAVAILABLE
        8 -> RecognitionErrorKind.BUSY // ERROR_RECOGNIZER_BUSY
        else -> RecognitionErrorKind.OTHER // AUDIO, CLIENT, …
    }
}
