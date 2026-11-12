package com.drishtisaathi.voice

import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.voice.VoiceTestSupport.ctx
import com.drishtisaathi.voice.VoiceTestSupport.parser
import com.drishtisaathi.voice.VoiceTestSupport.q
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors AndroidSpeechInput in SYSTEM_DIALOG mode: the dialog is "on screen" while a listener is pending. */
private class DialogFakeInput : SpeechInput {
    override val mode = MutableStateFlow(RecognitionMode.SYSTEM_DIALOG)
    override val diagnostics = MutableStateFlow(SpeechDiagnostics(false, null, "com.google.android.googlequicksearchbox", null))
    var listener: SpeechInput.Listener? = null
    var starts = 0
    var lastAllowNetwork: Boolean? = null
    override val externalUiActive: Boolean get() = listener != null
    override fun start(allowNetwork: Boolean, listener: SpeechInput.Listener) {
        starts++; lastAllowNetwork = allowNetwork; this.listener = listener
    }
    override fun stop() { listener = null }
    override fun release() {}
    /** The dialog returns: listener cleared first, then results, like onDialogResult. */
    fun dialogReturns(vararg texts: String) { val l = listener!!; listener = null; l.onResults(texts.toList(), null) }
}

class VoiceSystemDialogTest {
    private val speaker = FakeSpeaker()
    private val input = DialogFakeInput()
    private val vc = VoiceController(speaker, input, parser)

    @Test fun `system dialog needs the online consent like a network recogniser`() {
        vc.setMicPermission(true)
        assertEquals(MicAction.ASK_NETWORK_CONSENT, vc.onMicTapped())
        assertFalse(vc.state.value.recognitionAllowed)
        vc.setNetworkConsent(true)
        assertEquals(MicAction.TURN_ON, vc.onMicTapped())
        assertTrue(vc.state.value.copy(recognition = RecognitionMode.SYSTEM_DIALOG).recognitionAllowed)
    }

    @Test fun `screen stop while the dialog is open keeps the question, result gets the read-back`() {
        vc.setMicPermission(true)
        vc.setNetworkConsent(true)
        vc.onMicTapped()
        vc.begin(q("age"), Lang.HI, ctx) {}
        speaker.finish()
        assertEquals(1, input.starts)
        assertEquals(true, input.lastAllowNetwork)

        // The system dialog covers the app: ON_STOP then ON_START must not cancel or restart the question.
        vc.stop()
        vc.resume()
        assertEquals(1, input.starts)

        input.dialogReturns("पैंतीस साल")
        assertEquals("आपने कहा: 35 साल. सही है?", speaker.spoken.last())
        assertTrue(vc.state.value.dialogue is DialogueState.Confirming)
    }

    @Test fun `without the dialog open, stop still stops`() {
        vc.setMicPermission(true)
        vc.setNetworkConsent(true)
        vc.onMicTapped()
        vc.begin(q("age"), Lang.HI, ctx) {}
        // still speaking the question: nothing external on screen yet
        vc.stop()
        assertEquals(0, input.starts)
    }

    @Test fun `diagnostics are exposed for the staff line`() {
        assertEquals("com.google.android.googlequicksearchbox", vc.diagnostics!!.value.dialogPackage)
        assertFalse(vc.diagnostics!!.value.recognitionAvailable)
    }
}
