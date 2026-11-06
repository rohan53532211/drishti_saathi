package com.drishtisaathi.voice

import com.drishtisaathi.camera.InstructionSpeaker
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.SVoice
import com.drishtisaathi.i18n.Txt
import com.drishtisaathi.interview.ChoiceContext
import com.drishtisaathi.interview.Question
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class TtsStatus { INITIALISING, HINDI, ENGLISH_ONLY, UNAVAILABLE }

/**
 * - [ON_DEVICE]: the platform on-device recogniser has Hindi installed; audio never leaves the phone.
 * - [NETWORK_POSSIBLE]: a recogniser exists but on-device Hindi cannot be confirmed (API < 33, or the
 *   offline pack is missing); it may send audio to the speech service, so voice input needs explicit consent.
 * - [UNAVAILABLE]: no recogniser.
 */
enum class RecognitionMode { CHECKING, ON_DEVICE, NETWORK_POSSIBLE, UNAVAILABLE }

/** Text-to-speech. Implementations call `onDone` on the main thread once the utterance ends or fails. */
interface VoiceSpeaker {
    val status: StateFlow<TtsStatus>
    /** Flushes anything queued. Hindi text when [lang] is HI and a Hindi voice exists, otherwise English. */
    fun speak(text: Txt, lang: Lang, onDone: () -> Unit)
    fun stop()
    fun shutdown()
}

/** Speech recognition. Audio is never recorded or stored; only the recognised text is returned. */
interface SpeechInput {
    val mode: StateFlow<RecognitionMode>
    /** Starts one recognition. [allowNetwork] false = only an on-device recogniser may be used. */
    fun start(allowNetwork: Boolean, listener: Listener)
    /** Cancels the current recognition; no further callbacks for it. */
    fun stop()
    fun release()

    interface Listener {
        fun onPartial(text: String)
        fun onResults(hypotheses: List<String>, confidences: List<Float>?)
        fun onError(kind: RecognitionErrorKind)
    }
}

data class VoiceUiState(
    /** Read each question (and the read-back) aloud. */
    val readAloud: Boolean = false,
    /** The user asked to answer by voice (mic button on). */
    val listenEnabled: Boolean = false,
    val micPermission: Boolean = false,
    /** Tapped consent for a recogniser that may use the network (see [RecognitionMode.NETWORK_POSSIBLE]). */
    val networkConsent: Boolean = false,
    val tts: TtsStatus = TtsStatus.INITIALISING,
    val recognition: RecognitionMode = RecognitionMode.CHECKING,
    val dialogue: DialogueState = DialogueState.Idle,
    /** Status line under the mic (fallback reason, permission denied, …). */
    val message: Txt? = null,
) {
    val recognitionAllowed: Boolean
        get() = recognition == RecognitionMode.ON_DEVICE ||
            (recognition == RecognitionMode.NETWORK_POSSIBLE && networkConsent)

    /** Mic actually in use for the next question. */
    val canListen: Boolean get() = listenEnabled && micPermission && recognitionAllowed
}

/** What a tap on the mic button should do, given the current state. */
enum class MicAction { TURN_OFF, UNAVAILABLE, REQUEST_PERMISSION, ASK_NETWORK_CONSENT, TURN_ON }

fun micAction(s: VoiceUiState): MicAction = when {
    s.listenEnabled -> MicAction.TURN_OFF
    s.recognition == RecognitionMode.UNAVAILABLE -> MicAction.UNAVAILABLE
    !s.micPermission -> MicAction.REQUEST_PERMISSION
    s.recognition != RecognitionMode.ON_DEVICE && !s.networkConsent -> MicAction.ASK_NETWORK_CONSENT
    else -> MicAction.TURN_ON
}

/**
 * The voice layer for the interview. One instance for the whole app (owns the single TTS engine; camera
 * screens can share it through [instructionSpeaker]). Main-thread only. Not Android-specific: the Android
 * engines are behind [VoiceSpeaker] and [SpeechInput] so the whole flow is unit-tested with fakes.
 *
 * Screen protocol: [begin] for every question shown; [detach] when the interview screen goes away.
 */
class VoiceController(
    private val speaker: VoiceSpeaker,
    private val input: SpeechInput,
    private val parser: VoiceParser,
    readAloudByDefault: Boolean = true,
) {
    private val _state = MutableStateFlow(VoiceUiState(readAloud = readAloudByDefault))
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    /** Live engine availability (also copied into [state] by [refreshAvailability] and on every question). */
    val ttsStatus: StateFlow<TtsStatus> get() = speaker.status
    val recognitionMode: StateFlow<RecognitionMode> get() = input.mode

    /**
     * The same TTS engine behind the camera track's [InstructionSpeaker] interface, so camera screens do not
     * open a second engine. `shutdown()` on it is a no-op: the controller owns the engine ([release]).
     */
    val instructionSpeaker: InstructionSpeaker = object : InstructionSpeaker {
        override fun speak(text: Txt, lang: Lang) = speaker.speak(text, lang) {}
        override fun stop() = speaker.stop()
        override fun shutdown() {}
    }

    private class Session(val q: Question, val lang: Lang, val ctx: ChoiceContext, val onAction: (VoiceAnswers.Action) -> Unit)

    private var session: Session? = null
    private var dialogue: VoiceDialogue? = null
    /** Bumped whenever a dialogue is replaced or stopped, so late engine callbacks are dropped. */
    private var generation = 0
    private var utterance = 0

    // Trampoline: engine callbacks may arrive synchronously (fakes, unavailable TTS); never re-enter a transition.
    private val queue = ArrayDeque<() -> Unit>()
    private var draining = false
    private fun post(block: () -> Unit) {
        queue.addLast(block)
        if (draining) return
        draining = true
        try {
            while (queue.isNotEmpty()) queue.removeFirst().invoke()
        } finally {
            draining = false
        }
    }

    /** Refresh engine availability into [state]; call when the UI observes it (cheap). */
    fun refreshAvailability() = post {
        _state.update { it.copy(tts = speaker.status.value, recognition = input.mode.value) }
    }

    /** A question is on screen: flush any speech, read it aloud and (if enabled) listen. */
    fun begin(q: Question, lang: Lang, ctx: ChoiceContext, onAction: (VoiceAnswers.Action) -> Unit) = post {
        session = Session(q, lang, ctx, onAction)
        _state.update { it.copy(message = null) }
        restart()
    }

    /** Stop talking/listening (screen paused). The current question can be resumed with [resume]. */
    fun stop() = post { stopDialogue() }

    /** Re-run the current question (after [stop], e.g. when the app comes back to the foreground). */
    fun resume() = post { if (session != null && dialogue?.state.let { it == null || it == DialogueState.Idle }) restart() }

    /** The interview screen is gone: stop and forget the question. */
    fun detach() = post {
        stopDialogue()
        session = null
    }

    fun setReadAloud(on: Boolean) = post {
        _state.update { it.copy(readAloud = on, listenEnabled = if (on) it.listenEnabled else false, message = null) }
        if (on) restart() else stopDialogue()
    }

    /**
     * The mic button. Handles turning off / on and "not available" itself; returns
     * [MicAction.REQUEST_PERMISSION] or [MicAction.ASK_NETWORK_CONSENT] when the UI must ask first
     * (then call [setMicPermission] / [setNetworkConsent] and tap again via this method).
     */
    fun onMicTapped(): MicAction {
        val st = _state.value.copy(tts = speaker.status.value, recognition = input.mode.value)
        val action = micAction(st)
        when (action) {
            MicAction.TURN_OFF -> setListenEnabled(false)
            MicAction.TURN_ON -> setListenEnabled(true)
            MicAction.UNAVAILABLE -> post { _state.update { it.copy(recognition = st.recognition, message = SVoice.recognitionUnavailable) } }
            MicAction.REQUEST_PERMISSION, MicAction.ASK_NETWORK_CONSENT -> {}
        }
        return action
    }

    /** Mic on/off without the permission/consent checks of [onMicTapped] (they still gate [VoiceUiState.canListen]). */
    fun setListenEnabled(on: Boolean) = post {
        _state.update { it.copy(listenEnabled = on, readAloud = it.readAloud || on, message = null) }
        restart()
    }

    fun setMicPermission(granted: Boolean) = post {
        if (_state.value.micPermission == granted) return@post
        _state.update { it.copy(micPermission = granted) }
    }

    /** The user denied the mic permission: buttons keep working, read-aloud stays as it was. */
    fun onMicPermissionDenied() = post {
        _state.update { it.copy(micPermission = false, listenEnabled = false, message = SVoice.micDenied) }
    }

    /** Tapped "Allow voice answers" on the online-recognition consent. */
    fun setNetworkConsent(given: Boolean) = post {
        _state.update { it.copy(networkConsent = given, listenEnabled = if (given) it.listenEnabled else false) }
    }

    /** ✓ / ✗ on the read-back. */
    fun confirm(correct: Boolean) = post { dispatch(DialogueEvent.Tapped(correct)) }

    /** Frees the TTS engine and recogniser. Call once, when the app's owner goes away. */
    fun release() = post {
        stopDialogue()
        session = null
        input.release()
        speaker.shutdown()
    }

    // ---- internals -------------------------------------------------------------------------

    private fun restart() {
        stopDialogue()
        val s = session ?: return
        val st = _state.value.copy(tts = speaker.status.value, recognition = input.mode.value)
        _state.value = st
        if (!st.readAloud && !st.canListen) return
        val d = VoiceDialogue(
            question = s.q.text,
            reAsk = VoiceAnswers.reAsk(s.q),
            listen = st.canListen,
            parseAnswer = { h, c -> parser.parse(s.q, h, s.ctx, c) },
            parseYesNo = { h -> h.asSequence().map(parser::parseYesNo).firstOrNull { it is ParseResult.Parsed } ?: ParseResult.NoMatch },
            readBack = { a -> VoiceAnswers.readBack(s.q, a, s.ctx) },
        )
        dialogue = d
        run(d.start())
    }

    private fun stopDialogue() {
        val d = dialogue ?: return
        generation++
        dialogue = null
        d.on(DialogueEvent.Stop)
        input.stop()
        speaker.stop()
        _state.update { it.copy(dialogue = DialogueState.Idle) }
    }

    private fun dispatch(e: DialogueEvent) {
        val d = dialogue ?: return
        run(d.on(e))
    }

    private fun run(effects: List<DialogueEffect>) {
        val d = dialogue ?: return
        val s = session ?: return
        val gen = generation
        val ds = d.state
        _state.update { it.copy(dialogue = ds, message = messageFor(ds) ?: it.message) }
        if (ds is DialogueState.Fallback) onFallback(ds.reason)
        for (eff in effects) {
            if (gen != generation) return
            when (eff) {
                is DialogueEffect.Speak -> {
                    val id = ++utterance
                    speaker.speak(eff.text, s.lang) {
                        post { if (gen == generation && id == utterance) dispatch(DialogueEvent.SpeechDone) }
                    }
                }
                DialogueEffect.Listen -> input.start(_state.value.networkConsent, listenerFor(gen))
                DialogueEffect.StopListening -> input.stop()
                DialogueEffect.StopSpeaking -> speaker.stop()
                is DialogueEffect.Commit -> {
                    val action = VoiceAnswers.action(s.q, eff.answer)
                    if (action != null) s.onAction(action)
                }
            }
        }
    }

    private fun listenerFor(gen: Int) = object : SpeechInput.Listener {
        override fun onPartial(text: String) = post { if (gen == generation) dispatch(DialogueEvent.Partial(text)) }
        override fun onResults(hypotheses: List<String>, confidences: List<Float>?) =
            post { if (gen == generation) dispatch(DialogueEvent.Heard(hypotheses, confidences)) }
        override fun onError(kind: RecognitionErrorKind) = post { if (gen == generation) dispatch(DialogueEvent.RecognitionFailed(kind)) }
    }

    private fun messageFor(ds: DialogueState): Txt? = when (ds) {
        is DialogueState.Fallback -> when (ds.reason) {
            FallbackReason.NOT_UNDERSTOOD -> SVoice.fallback
            FallbackReason.MIC_PERMISSION -> SVoice.micDenied
            FallbackReason.UNAVAILABLE -> SVoice.recognitionUnavailable
        }
        is DialogueState.Confirming -> if (ds.phase == ConfirmPhase.TAP_ONLY) SVoice.tapToConfirm else null
        else -> null
    }

    /** Hard failures switch the mic off so the next question does not hit the same wall. */
    private fun onFallback(reason: FallbackReason) {
        when (reason) {
            FallbackReason.MIC_PERMISSION -> _state.update { it.copy(micPermission = false, listenEnabled = false) }
            FallbackReason.UNAVAILABLE -> _state.update { it.copy(listenEnabled = false) }
            FallbackReason.NOT_UNDERSTOOD -> {}
        }
    }
}
