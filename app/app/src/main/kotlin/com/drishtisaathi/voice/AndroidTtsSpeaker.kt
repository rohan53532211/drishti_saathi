package com.drishtisaathi.voice

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.Txt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Android TextToSpeech. Hindi (hi-IN) when a Hindi voice is installed on the phone, otherwise the English
 * text in en-IN ([TtsStatus.ENGLISH_ONLY]). Only voices that work offline are chosen: the read-back contains
 * health answers, so nothing is sent to a network voice. Every utterance flushes the queue.
 */
class AndroidTtsSpeaker(context: Context) : VoiceSpeaker {
    private val main = Handler(Looper.getMainLooper())
    private val _status = MutableStateFlow(TtsStatus.INITIALISING)
    override val status: StateFlow<TtsStatus> = _status.asStateFlow()

    private val hindi = Locale.forLanguageTag("hi-IN")
    private val englishIndia = Locale.forLanguageTag("en-IN")
    private var hindiVoice: Voice? = null
    private var englishVoice: Voice? = null
    private var tts: TextToSpeech? = null
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    private var pending: Triple<Txt, Lang, () -> Unit>? = null
    private var counter = 0

    init {
        tts = TextToSpeech(context.applicationContext) { code -> main.post { onInit(code) } }
    }

    private fun onInit(code: Int) {
        val t = tts
        if (code != TextToSpeech.SUCCESS || t == null) {
            _status.value = TtsStatus.UNAVAILABLE
            pending?.third?.let { main.post(it) }
            pending = null
            return
        }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finish(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
            // Flushed by a newer utterance or stop(): the caller has moved on, so no callback.
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                utteranceId?.let { callbacks.remove(it) }
            }
        })
        t.setSpeechRate(0.9f) // a little slower for older customers
        hindiVoice = offlineVoice(t, "hi")
        englishVoice = offlineVoice(t, "en")
        val hindiOk = hindiVoice != null || (runCatching { t.voices.isNullOrEmpty() }.getOrDefault(true) && t.isLanguageAvailable(hindi) >= TextToSpeech.LANG_AVAILABLE)
        val englishOk = englishVoice != null || t.isLanguageAvailable(englishIndia) >= TextToSpeech.LANG_AVAILABLE ||
            t.isLanguageAvailable(Locale.ENGLISH) >= TextToSpeech.LANG_AVAILABLE
        _status.value = when {
            hindiOk -> TtsStatus.HINDI
            englishOk -> TtsStatus.ENGLISH_ONLY
            else -> TtsStatus.UNAVAILABLE
        }
        pending?.let { (text, lang, done) -> pending = null; speak(text, lang, done) }
    }

    /** Installed, offline voice for [language], preferring India and higher quality. */
    private fun offlineVoice(t: TextToSpeech, language: String): Voice? = runCatching {
        t.voices.orEmpty()
            .filter { v ->
                v.locale.language == language && !v.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features
            }
            .sortedWith(compareByDescending<Voice> { it.locale.country == "IN" }.thenByDescending { it.quality })
            .firstOrNull()
    }.getOrNull()

    private fun finish(id: String?) {
        val cb = id?.let { callbacks.remove(it) } ?: return
        main.post(cb)
    }

    override fun speak(text: Txt, lang: Lang, onDone: () -> Unit) {
        val t = tts
        when (_status.value) {
            TtsStatus.INITIALISING -> { pending = Triple(text, lang, onDone); return }
            TtsStatus.UNAVAILABLE -> { main.post(onDone); return }
            else -> {}
        }
        if (t == null) { main.post(onDone); return }
        val useHindi = lang == Lang.HI && _status.value == TtsStatus.HINDI
        val voice = if (useHindi) hindiVoice else englishVoice
        val ok = if (voice != null) {
            t.setVoice(voice) == TextToSpeech.SUCCESS
        } else {
            t.setLanguage(if (useHindi) hindi else englishIndia) >= TextToSpeech.LANG_AVAILABLE ||
                (!useHindi && t.setLanguage(Locale.ENGLISH) >= TextToSpeech.LANG_AVAILABLE)
        }
        if (!ok) { main.post(onDone); return }
        val id = "voice-" + (++counter)
        callbacks.clear() // QUEUE_FLUSH: older utterances will never complete
        callbacks[id] = onDone
        val params = Bundle()
        if (t.speak(if (useHindi) text.hi else text.en, TextToSpeech.QUEUE_FLUSH, params, id) != TextToSpeech.SUCCESS) {
            callbacks.remove(id)
            main.post(onDone)
        }
    }

    override fun stop() {
        pending = null
        callbacks.clear()
        tts?.stop()
    }

    override fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        _status.value = TtsStatus.UNAVAILABLE
    }
}
