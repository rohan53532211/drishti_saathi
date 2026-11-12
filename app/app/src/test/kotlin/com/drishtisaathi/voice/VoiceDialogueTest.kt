package com.drishtisaathi.voice

import com.drishtisaathi.i18n.SVoice
import com.drishtisaathi.voice.DialogueEffect.Commit
import com.drishtisaathi.voice.DialogueEffect.Listen
import com.drishtisaathi.voice.DialogueEffect.Speak
import com.drishtisaathi.voice.DialogueEffect.StopListening
import com.drishtisaathi.voice.DialogueEffect.StopSpeaking
import com.drishtisaathi.voice.DialogueEvent.Heard
import com.drishtisaathi.voice.DialogueEvent.RecognitionFailed
import com.drishtisaathi.voice.DialogueEvent.SpeechDone
import com.drishtisaathi.voice.DialogueEvent.Tapped
import com.drishtisaathi.voice.VoiceTestSupport.ctx
import com.drishtisaathi.voice.VoiceTestSupport.parser
import com.drishtisaathi.voice.VoiceTestSupport.q
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceDialogueTest {
    private val age = q("age")

    private fun dialogue(listen: Boolean = true) = VoiceDialogue(
        question = age.text,
        reAsk = VoiceAnswers.reAsk(age),
        listen = listen,
        parseAnswer = { h, c -> parser.parse(age, h, ctx, c) },
        parseYesNo = { h -> parser.parseYesNo(h.first()) },
        readBack = { a -> VoiceAnswers.readBack(age, a, ctx) },
    )

    private val thirtyFive = VoiceAnswer.Number(35)

    @Test fun `happy path - ask, listen, read back, spoken yes, accepted`() {
        val d = dialogue()
        assertEquals(listOf(StopListening, Speak(age.text)), d.start())
        assertEquals(DialogueState.Asking(0), d.state)

        assertEquals(listOf(Listen), d.on(SpeechDone))
        assertEquals(DialogueState.Listening(0), d.state)

        d.on(DialogueEvent.Partial("पैं"))
        assertEquals(DialogueState.Listening(0, "पैं"), d.state)

        val fx = d.on(Heard(listOf("पैंतीस साल")))
        val c = d.state as DialogueState.Confirming
        assertEquals(thirtyFive, c.answer)
        assertEquals(ConfirmPhase.SPEAKING, c.phase)
        assertEquals("आपने कहा: 35 साल. सही है?", c.readBack.hi)
        assertEquals("You said: 35 years. Is that right?", c.readBack.en)
        assertEquals(listOf(Speak(c.readBack)), fx)

        assertEquals(listOf(Listen), d.on(SpeechDone))
        assertEquals(ConfirmPhase.LISTENING, (d.state as DialogueState.Confirming).phase)

        assertEquals(listOf(StopListening, StopSpeaking, Commit(thirtyFive)), d.on(Heard(listOf("हाँ जी"))))
        assertEquals(DialogueState.Accepted(thirtyFive), d.state)
    }

    @Test fun `tap ✓ accepts even while the read-back is still being spoken`() {
        val d = dialogue()
        d.start(); d.on(SpeechDone); d.on(Heard(listOf("35")))
        assertEquals(listOf(StopListening, StopSpeaking, Commit(thirtyFive)), d.on(Tapped(true)))
        assertEquals(DialogueState.Accepted(thirtyFive), d.state)
    }

    @Test fun `no match re-asks once then falls back to buttons`() {
        val d = dialogue()
        d.start(); d.on(SpeechDone)
        val retry = d.on(Heard(listOf("उम्म")))
        assertEquals(DialogueState.Asking(1), d.state)
        assertEquals(listOf(StopListening, Speak(VoiceAnswers.reAsk(age))), retry)
        assertTrue(VoiceAnswers.reAsk(age).hi.startsWith(SVoice.sayAgain.hi))

        d.on(SpeechDone)
        assertEquals(DialogueState.Listening(1), d.state)
        val fx = d.on(Heard(listOf("कुछ नहीं")))
        assertEquals(DialogueState.Fallback(FallbackReason.NOT_UNDERSTOOD), d.state)
        assertEquals(listOf(StopListening, Speak(SVoice.fallback)), fx)

        // Terminal: nothing else happens.
        assertEquals(emptyList<DialogueEffect>(), d.on(SpeechDone))
        assertEquals(emptyList<DialogueEffect>(), d.on(Heard(listOf("35"))))
    }

    @Test fun `spoken no at read-back re-asks, second no falls back`() {
        val d = dialogue()
        d.start(); d.on(SpeechDone); d.on(Heard(listOf("35"))); d.on(SpeechDone)
        d.on(Heard(listOf("नहीं")))
        assertEquals(DialogueState.Asking(1), d.state)
        d.on(SpeechDone); d.on(Heard(listOf("36"))); d.on(SpeechDone)
        assertEquals(VoiceAnswer.Number(36), (d.state as DialogueState.Confirming).answer)
        d.on(Tapped(false))
        assertEquals(DialogueState.Fallback(FallbackReason.NOT_UNDERSTOOD), d.state)
    }

    @Test fun `ambiguous answer counts as a failed attempt`() {
        val d = dialogue()
        d.start(); d.on(SpeechDone)
        d.on(Heard(listOf("पैंतीस या छत्तीस")))
        assertEquals(DialogueState.Asking(1), d.state)
    }

    @Test fun `unclear confirmation waits for a tap instead of guessing`() {
        val d = dialogue()
        d.start(); d.on(SpeechDone); d.on(Heard(listOf("35"))); d.on(SpeechDone)
        val fx = d.on(Heard(listOf("क्या?")))
        assertEquals(listOf(Speak(SVoice.tapToConfirm)), fx)
        assertEquals(ConfirmPhase.TAP_ONLY, (d.state as DialogueState.Confirming).phase)
        // The tap prompt finishing does not reopen the mic.
        assertEquals(emptyList<DialogueEffect>(), d.on(SpeechDone))
        d.on(Tapped(true))
        assertEquals(DialogueState.Accepted(thirtyFive), d.state)
    }

    @Test fun `recogniser timeout re-asks, permission error falls back immediately`() {
        val d = dialogue()
        d.start(); d.on(SpeechDone)
        d.on(RecognitionFailed(RecognitionErrorKind.NO_SPEECH))
        assertEquals(DialogueState.Asking(1), d.state)

        val d2 = dialogue()
        d2.start(); d2.on(SpeechDone)
        d2.on(RecognitionFailed(RecognitionErrorKind.PERMISSION))
        assertEquals(DialogueState.Fallback(FallbackReason.MIC_PERMISSION), d2.state)

        val d3 = dialogue()
        d3.start(); d3.on(SpeechDone)
        d3.on(RecognitionFailed(RecognitionErrorKind.UNAVAILABLE))
        assertEquals(DialogueState.Fallback(FallbackReason.UNAVAILABLE), d3.state)
    }

    @Test fun `recogniser error during confirmation leaves ✓ ✗`() {
        val d = dialogue()
        d.start(); d.on(SpeechDone); d.on(Heard(listOf("35"))); d.on(SpeechDone)
        d.on(RecognitionFailed(RecognitionErrorKind.NO_SPEECH))
        assertEquals(ConfirmPhase.TAP_ONLY, (d.state as DialogueState.Confirming).phase)
    }

    @Test fun `read-aloud only ends after the question`() {
        val d = dialogue(listen = false)
        d.start()
        assertEquals(emptyList<DialogueEffect>(), d.on(SpeechDone))
        assertEquals(DialogueState.Idle, d.state)
    }

    @Test fun `stop from any active state silences everything`() {
        val d = dialogue()
        d.start(); d.on(SpeechDone)
        assertEquals(listOf(StopListening, StopSpeaking), d.on(DialogueEvent.Stop))
        assertEquals(DialogueState.Idle, d.state)
        assertEquals(emptyList<DialogueEffect>(), d.on(DialogueEvent.Stop))
        assertEquals(emptyList<DialogueEffect>(), d.on(Heard(listOf("35"))))
    }

    @Test fun `events out of order are ignored`() {
        val d = dialogue()
        d.start()
        assertEquals(emptyList<DialogueEffect>(), d.on(Heard(listOf("35"))))
        assertEquals(emptyList<DialogueEffect>(), d.on(Tapped(true)))
        assertEquals(DialogueState.Asking(0), d.state)
    }

    @Test fun `android error codes map to kinds`() {
        assertEquals(RecognitionErrorKind.NO_SPEECH, RecognitionErrors.kind(6))
        assertEquals(RecognitionErrorKind.NO_MATCH, RecognitionErrors.kind(7))
        assertEquals(RecognitionErrorKind.PERMISSION, RecognitionErrors.kind(9))
        assertEquals(RecognitionErrorKind.UNAVAILABLE, RecognitionErrors.kind(2))
        assertEquals(RecognitionErrorKind.UNAVAILABLE, RecognitionErrors.kind(12))
        assertEquals(RecognitionErrorKind.BUSY, RecognitionErrors.kind(8))
        assertEquals(RecognitionErrorKind.OTHER, RecognitionErrors.kind(5))
    }
}
