package com.drishtisaathi.voice

import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.Occupation
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S
import com.drishtisaathi.i18n.SVoice
import com.drishtisaathi.i18n.Txt
import com.drishtisaathi.voice.VoiceTestSupport.ctx
import com.drishtisaathi.voice.VoiceTestSupport.parser
import com.drishtisaathi.voice.VoiceTestSupport.q
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeSpeaker(status: TtsStatus = TtsStatus.HINDI) : VoiceSpeaker {
    override val status = MutableStateFlow(status)
    val spoken = mutableListOf<String>()
    private var pending: (() -> Unit)? = null
    var stops = 0
    override fun speak(text: Txt, lang: Lang, onDone: () -> Unit) {
        spoken += if (lang == Lang.HI && status.value == TtsStatus.HINDI) text.hi else text.en
        pending = onDone
    }
    /** The engine finished the current utterance. */
    fun finish() { pending?.also { pending = null }?.invoke() }
    override fun stop() { stops++; pending = null }
    override fun shutdown() {}
}

class FakeInput(mode: RecognitionMode = RecognitionMode.ON_DEVICE) : SpeechInput {
    override val mode = MutableStateFlow(mode)
    var listener: SpeechInput.Listener? = null
    var starts = 0
    var lastAllowNetwork: Boolean? = null
    var released = false
    override fun start(allowNetwork: Boolean, listener: SpeechInput.Listener) {
        starts++; lastAllowNetwork = allowNetwork; this.listener = listener
    }
    override fun stop() { listener = null }
    override fun release() { released = true }
    fun say(vararg texts: String) = listener!!.onResults(texts.toList(), null)
}

class VoiceControllerTest {
    private val speaker = FakeSpeaker()
    private val input = FakeInput()
    private val vc = VoiceController(speaker, input, parser)
    private val actions = mutableListOf<VoiceAnswers.Action>()

    private fun enableMic() {
        vc.setMicPermission(true)
        assertEquals(MicAction.TURN_ON, vc.onMicTapped())
    }

    private fun apply(a: VoiceAnswers.Action, start: InterviewAnswers = InterviewAnswers()) =
        (a as VoiceAnswers.Action.Answer).update(start)

    @Test fun `read-aloud only speaks each question and never opens the mic`() {
        vc.begin(q("age"), Lang.HI, ctx) { actions += it }
        assertEquals(listOf(S.qAge.hi), speaker.spoken)
        speaker.finish()
        assertEquals(0, input.starts)
        assertEquals(DialogueState.Idle, vc.state.value.dialogue)
    }

    @Test fun `full voice answer calls the same update as the buttons`() {
        enableMic()
        vc.begin(q("occupation"), Lang.HI, ctx) { actions += it }
        speaker.finish()
        assertEquals(1, input.starts)
        assertEquals(false, input.lastAllowNetwork)
        input.say("मेरी दुकान है")
        assertEquals("आपने कहा: दुकान / व्यापार. सही है?", speaker.spoken.last())
        speaker.finish()
        input.say("हाँ")
        assertEquals(1, actions.size)
        assertEquals(Occupation.SHOP_BUSINESS, apply(actions[0]).occupation)
        assertEquals("occupation", (actions[0] as VoiceAnswers.Action.Answer).questionId)
    }

    @Test fun `English UI speaks English and falls back to English text when no Hindi voice`() {
        vc.begin(q("age"), Lang.EN, ctx) {}
        assertEquals(S.qAge.en, speaker.spoken.last())
        val sp2 = FakeSpeaker(TtsStatus.ENGLISH_ONLY)
        VoiceController(sp2, FakeInput(), parser).begin(q("age"), Lang.HI, ctx) {}
        assertEquals(S.qAge.en, sp2.spoken.last())
    }

    @Test fun `new question flushes the old dialogue and late callbacks are ignored`() {
        enableMic()
        vc.begin(q("age"), Lang.HI, ctx) { actions += it }
        speaker.finish()
        val oldListener = input.listener!!
        vc.begin(q("nightDriving"), Lang.HI, ctx) { actions += it }
        oldListener.onResults(listOf("35"), null) // stale
        assertTrue(actions.isEmpty())
        assertEquals(S.qNightDriving.hi, speaker.spoken.last())
        assertTrue(vc.state.value.dialogue is DialogueState.Asking)
    }

    @Test fun `detach stops speech and listening`() {
        enableMic()
        vc.begin(q("age"), Lang.HI, ctx) {}
        speaker.finish()
        val stopsBefore = speaker.stops
        vc.detach()
        assertNull(input.listener)
        assertTrue(speaker.stops > stopsBefore)
        assertEquals(DialogueState.Idle, vc.state.value.dialogue)
        vc.resume() // nothing to resume after detach
        assertEquals(DialogueState.Idle, vc.state.value.dialogue)
    }

    @Test fun `stop then resume re-asks the current question`() {
        vc.begin(q("age"), Lang.HI, ctx) {}
        vc.stop()
        val n = speaker.spoken.size
        vc.resume()
        assertEquals(n + 1, speaker.spoken.size)
        assertEquals(S.qAge.hi, speaker.spoken.last())
    }

    @Test fun `two failures fall back with a message, buttons still work`() {
        enableMic()
        vc.begin(q("age"), Lang.HI, ctx) { actions += it }
        speaker.finish(); input.say("उम्म")
        speaker.finish(); input.say("हम्म")
        assertEquals(DialogueState.Fallback(FallbackReason.NOT_UNDERSTOOD), vc.state.value.dialogue)
        assertEquals(SVoice.fallback, vc.state.value.message)
        assertTrue(actions.isEmpty())
    }

    @Test fun `mic permission missing asks for it, denial keeps read-aloud`() {
        assertEquals(MicAction.REQUEST_PERMISSION, vc.onMicTapped())
        vc.onMicPermissionDenied()
        assertFalse(vc.state.value.listenEnabled)
        assertTrue(vc.state.value.readAloud)
        assertEquals(SVoice.micDenied, vc.state.value.message)
        vc.begin(q("age"), Lang.HI, ctx) {}
        speaker.finish()
        assertEquals(0, input.starts)
    }

    @Test fun `network recogniser needs consent before it is used`() {
        val inp = FakeInput(RecognitionMode.NETWORK_POSSIBLE)
        val c = VoiceController(speaker, inp, parser)
        c.setMicPermission(true)
        assertEquals(MicAction.ASK_NETWORK_CONSENT, c.onMicTapped())
        assertFalse(c.state.value.listenEnabled)
        c.setNetworkConsent(true)
        assertEquals(MicAction.TURN_ON, c.onMicTapped())
        c.begin(q("age"), Lang.HI, ctx) {}
        speaker.finish()
        assertEquals(1, inp.starts)
        assertEquals(true, inp.lastAllowNetwork)
    }

    @Test fun `no recogniser means mic is unavailable`() {
        val c = VoiceController(speaker, FakeInput(RecognitionMode.UNAVAILABLE), parser)
        c.setMicPermission(true)
        assertEquals(MicAction.UNAVAILABLE, c.onMicTapped())
        assertEquals(SVoice.recognitionUnavailable, c.state.value.message)
        assertFalse(c.state.value.listenEnabled)
    }

    @Test fun `recogniser that turns out unavailable switches the mic off`() {
        enableMic()
        vc.begin(q("age"), Lang.HI, ctx) {}
        speaker.finish()
        input.listener!!.onError(RecognitionErrorKind.UNAVAILABLE)
        assertFalse(vc.state.value.listenEnabled)
        assertEquals(SVoice.recognitionUnavailable, vc.state.value.message)
    }

    @Test fun `mic tap twice turns voice answers off`() {
        enableMic()
        assertTrue(vc.state.value.listenEnabled)
        assertEquals(MicAction.TURN_OFF, vc.onMicTapped())
        assertFalse(vc.state.value.listenEnabled)
    }

    @Test fun `old numbers yes opens the entry screen, no records none`() {
        enableMic()
        vc.begin(q("knownRx"), Lang.HI, ctx) { actions += it }
        speaker.finish(); input.say("हाँ है"); speaker.finish()
        vc.confirm(true)
        assertEquals(VoiceAnswers.Action.OpenOldNumbers, actions.single())
    }

    @Test fun `camera instruction speaker shares the engine`() {
        vc.instructionSpeaker.speak(Txt("Hold still", "स्थिर रहें"), Lang.HI)
        assertEquals("स्थिर रहें", speaker.spoken.last())
    }

    @Test fun `release frees the engines`() {
        vc.release()
        assertTrue(input.released)
    }

    @Test fun `micAction decision table`() {
        val base = VoiceUiState(recognition = RecognitionMode.ON_DEVICE, micPermission = true)
        assertEquals(MicAction.TURN_ON, micAction(base))
        assertEquals(MicAction.TURN_OFF, micAction(base.copy(listenEnabled = true)))
        assertEquals(MicAction.REQUEST_PERMISSION, micAction(base.copy(micPermission = false)))
        assertEquals(MicAction.ASK_NETWORK_CONSENT, micAction(base.copy(recognition = RecognitionMode.CHECKING)))
        assertEquals(MicAction.TURN_ON, micAction(base.copy(recognition = RecognitionMode.NETWORK_POSSIBLE, networkConsent = true)))
        assertEquals(MicAction.UNAVAILABLE, micAction(base.copy(recognition = RecognitionMode.UNAVAILABLE)))
    }
}
