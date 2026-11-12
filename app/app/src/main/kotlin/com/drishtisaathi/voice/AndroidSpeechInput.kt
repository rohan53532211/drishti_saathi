package com.drishtisaathi.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android speech recognition in hi-IN (the lexicon also covers English and romanised Hindi).
 *
 * Recogniser order ([RecognizerChoice]): a Google RecognitionService component → the system default →
 * Android 13+ on-device → the system voice dialog → none (buttons). A recogniser that fails because it cannot
 * serve us (bind failure, Hindi not supported) is dropped and the next one is tried in the same tap.
 *
 * Privacy: audio goes to the recogniser only while listening and is never recorded or stored by the app; only
 * the recognised text is used. EXTRA_PREFER_OFFLINE is false, so every recogniser except the on-device one may
 * use the network: the controller asks for the online consent first ([RecognitionMode.NETWORK_POSSIBLE] /
 * [RecognitionMode.SYSTEM_DIALOG]).
 */
class AndroidSpeechInput(context: Context) : SpeechInput {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val _mode = MutableStateFlow(RecognitionMode.CHECKING)
    override val mode: StateFlow<RecognitionMode> = _mode.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var session = 0
    private var watchdog: Runnable? = null

    /** Remaining backends, best first; the head is the one in use. */
    private var candidates: List<RecognizerBackend> = emptyList()
    private val current: RecognizerBackend? get() = candidates.firstOrNull()

    private val _diag = MutableStateFlow(SpeechDiagnostics(false, null, null, null))
    override val diagnostics: StateFlow<SpeechDiagnostics> = _diag.asStateFlow()

    /**
     * Set by the voice UI (an ActivityResult launcher) so the dialog backend can open the system voice dialog;
     * null when no screen can host it. Results come back through [onDialogResult].
     */
    var dialogLauncher: ((Intent) -> Unit)? = null
    private var dialogListener: SpeechInput.Listener? = null
    private var dialogSession = 0
    override val externalUiActive: Boolean get() = dialogListener != null

    init {
        main.post { detect() }
    }

    private fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LANGUAGE)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
    }

    // ---- discovery -----------------------------------------------------------------------------------

    /** Every RecognitionService component visible to the app (needs the manifest <queries> entry). */
    private fun services(): List<ServiceComponent> = runCatching {
        app.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .mapNotNull { it.serviceInfo?.let { si -> ServiceComponent(si.packageName, si.name) } }
    }.getOrDefault(emptyList())

    private fun defaultServicePackage(): String? =
        runCatching { Settings.Secure.getString(app.contentResolver, "voice_recognition_service") }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.substringBefore('/')

    private fun dialogPackage(): String? = runCatching {
        app.packageManager.resolveActivity(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0)?.activityInfo?.packageName
    }.getOrNull()

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(app) }.getOrDefault(false)

    private fun detect() {
        val services = services()
        val defaultAvailable = runCatching { SpeechRecognizer.isRecognitionAvailable(app) }.getOrDefault(false)
        val dialog = dialogPackage()
        candidates = RecognizerChoice.order(services, defaultAvailable, onDeviceAvailable(), dialog != null)
        _diag.value = SpeechDiagnostics(
            recognitionAvailable = defaultAvailable,
            recognizerPackage = defaultServicePackage(),
            dialogPackage = dialog,
            lastError = _diag.value.lastError,
            backend = current?.label,
            services = services.map { "${it.packageName}/${it.className.removePrefix(it.packageName)}" },
        )
        publishMode()
    }

    private fun publishMode() {
        _mode.value = current?.mode ?: RecognitionMode.UNAVAILABLE
        _diag.value = _diag.value.copy(backend = current?.label)
    }

    /** The backend in use cannot serve us: drop it and move on to the next one. */
    private fun demote(reason: String) {
        lastError(reason)
        if (candidates.isNotEmpty()) candidates = candidates.drop(1)
        publishMode()
    }

    private fun lastError(name: String?) { _diag.value = _diag.value.copy(lastError = name) }

    /**
     * Asks the on-device recogniser to download its Hindi model (Android 13+). Downloads only the language
     * model; no user data. Not called automatically.
     */
    fun requestOfflineHindiModel() {
        if (!onDeviceAvailable()) return
        val r = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(app) }.getOrNull() ?: return
        runCatching { r.triggerModelDownload(intent()) }
        main.postDelayed({ r.destroy(); detect() }, 2_000)
    }

    // ---- listening -----------------------------------------------------------------------------------

    override fun start(allowNetwork: Boolean, listener: SpeechInput.Listener) {
        stop()
        startWith(allowNetwork, listener)
    }

    private fun startWith(allowNetwork: Boolean, listener: SpeechInput.Listener) {
        val backend = current
        if (backend == null) {
            main.post { listener.onError(RecognitionErrorKind.UNAVAILABLE) }
            return
        }
        if (backend.mode != RecognitionMode.ON_DEVICE && !allowNetwork) {
            main.post { listener.onError(RecognitionErrorKind.UNAVAILABLE) }
            return
        }
        if (backend == RecognizerBackend.Dialog) { startDialog(listener); return }

        val s = ++session
        val r = runCatching {
            when (backend) {
                is RecognizerBackend.Component ->
                    SpeechRecognizer.createSpeechRecognizer(app, ComponentName(backend.packageName, backend.className))
                RecognizerBackend.OnDevice ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) SpeechRecognizer.createOnDeviceSpeechRecognizer(app) else null
                else -> SpeechRecognizer.createSpeechRecognizer(app)
            }
        }.getOrNull()
        if (r == null) {
            demote("CREATE_FAILED_${backend.label}")
            startWith(allowNetwork, listener)
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
                if (error in RecognizerChoice.BACKEND_FAILURE_CODES && candidates.size > 1) {
                    // This recogniser cannot serve hi-IN (or did not bind): try the next one in the same tap.
                    demote("${errorName(error)} (${backend.label})")
                    main.post { startWith(allowNetwork, listener) }
                    return
                }
                lastError("${errorName(error)} (${backend.label})")
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
                lastError("TIMEOUT (${backend.label})")
                listener.onError(RecognitionErrorKind.NO_SPEECH)
            }
        }
        watchdog = dog
        main.postDelayed(dog, TIMEOUT_MS)
        runCatching { r.startListening(intent()) }.onFailure {
            stop()
            demote("START_FAILED_${backend.label}")
            main.post { startWith(allowNetwork, listener) }
        }
    }

    // ---- fallback: system voice dialog (RecognizerIntent.ACTION_RECOGNIZE_SPEECH) -------------------

    private fun dialogIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LANGUAGE)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
    }

    private fun startDialog(listener: SpeechInput.Listener) {
        val launch = dialogLauncher
        if (launch == null) {
            lastError("DIALOG_NO_SCREEN")
            main.post { listener.onError(RecognitionErrorKind.UNAVAILABLE) }
            return
        }
        dialogListener = listener
        val s = ++dialogSession
        runCatching { launch(dialogIntent()) }.onFailure {
            if (s == dialogSession) dialogListener = null
            demote("DIALOG_LAUNCH_FAILED")
            main.post { listener.onError(RecognitionErrorKind.UNAVAILABLE) }
        }
    }

    /** Result of the system voice dialog; parsed by the controller exactly like SpeechRecognizer results. */
    fun onDialogResult(resultCode: Int, data: Intent?) {
        val listener = dialogListener ?: return
        dialogListener = null
        val texts = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty()
        val conf = data?.getFloatArrayExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES)?.toList()
        when {
            resultCode == android.app.Activity.RESULT_OK && texts.isNotEmpty() -> listener.onResults(texts, conf)
            resultCode == android.app.Activity.RESULT_OK -> { lastError("ERROR_NO_MATCH (DIALOG)"); listener.onError(RecognitionErrorKind.NO_MATCH) }
            else -> { lastError(dialogErrorName(resultCode)); listener.onError(RecognitionErrorKind.NO_SPEECH) }
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
        dialogListener = null // a dialog already on screen cannot be closed; its result is ignored
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
        /** SpeechRecognizer.ERROR_* constant name for the staff voice check line. */
        fun errorName(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            10 -> "ERROR_TOO_MANY_REQUESTS"
            11 -> "ERROR_SERVER_DISCONNECTED"
            12 -> "ERROR_LANGUAGE_NOT_SUPPORTED"
            13 -> "ERROR_LANGUAGE_UNAVAILABLE"
            14 -> "ERROR_CANNOT_CHECK_SUPPORT"
            15 -> "ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS"
            else -> "ERROR_$code"
        }

        /** Result codes of the system voice dialog (RecognizerIntent.RESULT_*). */
        fun dialogErrorName(resultCode: Int): String = when (resultCode) {
            android.app.Activity.RESULT_CANCELED -> "DIALOG_CANCELLED"
            RecognizerIntent.RESULT_NO_MATCH -> "DIALOG_NO_MATCH"
            RecognizerIntent.RESULT_CLIENT_ERROR -> "DIALOG_CLIENT_ERROR"
            RecognizerIntent.RESULT_SERVER_ERROR -> "DIALOG_SERVER_ERROR"
            RecognizerIntent.RESULT_NETWORK_ERROR -> "DIALOG_NETWORK_ERROR"
            RecognizerIntent.RESULT_AUDIO_ERROR -> "DIALOG_AUDIO_ERROR"
            else -> "DIALOG_RESULT_$resultCode"
        }

        const val LANGUAGE = "hi-IN"
        /** Safety net if the recogniser never reports back. */
        const val TIMEOUT_MS = 15_000L
    }
}
