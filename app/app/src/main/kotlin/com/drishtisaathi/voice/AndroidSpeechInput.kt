package com.drishtisaathi.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android SpeechRecognizer in hi-IN (the lexicon also covers English and romanised Hindi, which the hi-IN
 * model transcribes too).
 *
 * Privacy: audio is streamed to the recogniser only while listening and is never recorded or stored by the
 * app; only the recognised text is used. On Android 13+ the on-device recogniser is used when it has Hindi
 * installed ([RecognitionMode.ON_DEVICE]). Otherwise the default recogniser is used with EXTRA_PREFER_OFFLINE,
 * which may still go online, so the controller only uses it after explicit consent ([RecognitionMode.NETWORK_POSSIBLE]).
 */
class AndroidSpeechInput(context: Context) : SpeechInput {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val _mode = MutableStateFlow(RecognitionMode.CHECKING)
    override val mode: StateFlow<RecognitionMode> = _mode.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var session = 0
    private var watchdog: Runnable? = null

    init {
        main.post { detectMode() }
    }

    private fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LANGUAGE)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
    }

    private fun detectMode() {
        val anyRecognizer = SpeechRecognizer.isRecognitionAvailable(app)
        val fallback = if (anyRecognizer) RecognitionMode.NETWORK_POSSIBLE else RecognitionMode.UNAVAILABLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(app)) {
            checkOnDevice(fallback)
        } else {
            _mode.value = fallback
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun checkOnDevice(fallback: RecognitionMode) {
        val r = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(app) }.getOrNull()
        if (r == null) { _mode.value = fallback; return }
        runCatching {
            r.checkRecognitionSupport(intent(), { main.post(it) }, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    val installed = support.installedOnDeviceLanguages.any { it.startsWith("hi") }
                    _mode.value = if (installed) RecognitionMode.ON_DEVICE else fallback
                    r.destroy()
                }
                override fun onError(error: Int) {
                    _mode.value = fallback
                    r.destroy()
                }
            })
        }.onFailure { _mode.value = fallback; r.destroy() }
    }

    /**
     * Asks the on-device recogniser to download its Hindi model (Android 13+). Downloads only the language
     * model; no user data. Not called automatically: the lead decides whether to offer it.
     */
    fun requestOfflineHindiModel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !SpeechRecognizer.isOnDeviceRecognitionAvailable(app)) return
        val r = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(app) }.getOrNull() ?: return
        runCatching { r.triggerModelDownload(intent()) }
        main.postDelayed({ r.destroy(); detectMode() }, 2_000)
    }

    override fun start(allowNetwork: Boolean, listener: SpeechInput.Listener) {
        stop()
        val s = ++session
        val onDevice = _mode.value == RecognitionMode.ON_DEVICE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        if (!onDevice && !allowNetwork) {
            main.post { if (s == session) listener.onError(RecognitionErrorKind.UNAVAILABLE) }
            return
        }
        val r = runCatching {
            if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(app) else SpeechRecognizer.createSpeechRecognizer(app)
        }.getOrNull()
        if (r == null) {
            main.post { if (s == session) listener.onError(RecognitionErrorKind.UNAVAILABLE) }
            return
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {} // audio is never kept
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                if (s != session) return
                finishSession()
                listener.onError(RecognitionErrors.kind(error))
            }

            override fun onResults(results: Bundle?) {
                if (s != session) return
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val conf = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.toList()
                finishSession()
                if (texts.isEmpty()) listener.onError(RecognitionErrorKind.NO_MATCH) else listener.onResults(texts, conf)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (s != session) return
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.let { listener.onPartial(it) }
            }
        })
        val dog = Runnable {
            if (s == session) {
                stop()
                listener.onError(RecognitionErrorKind.NO_SPEECH)
            }
        }
        watchdog = dog
        main.postDelayed(dog, TIMEOUT_MS)
        runCatching { r.startListening(intent()) }.onFailure {
            stop()
            main.post { listener.onError(RecognitionErrorKind.UNAVAILABLE) }
        }
    }

    /** Ends the session without destroying synchronously inside the recogniser's own callback. */
    private fun finishSession() {
        session++
        watchdog?.let(main::removeCallbacks)
        watchdog = null
        val r = recognizer
        recognizer = null
        if (r != null) main.post { r.destroy() }
    }

    override fun stop() {
        session++
        watchdog?.let(main::removeCallbacks)
        watchdog = null
        val r = recognizer ?: return
        recognizer = null
        runCatching { r.cancel() }
        runCatching { r.destroy() }
    }

    override fun release() = stop()

    companion object {
        const val LANGUAGE = "hi-IN"
        /** Safety net if the recogniser never reports back. */
        const val TIMEOUT_MS = 15_000L
    }
}
