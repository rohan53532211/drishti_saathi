package com.drishtisaathi.voice

/**
 * Which speech recogniser to try, in order (pure, unit-tested). Samsung phones often default to Samsung's
 * own service, which may not support Hindi or may not be visible to apps; Google's service is preferred.
 *
 * 1. a Google RecognitionService component ([PREFERRED_PACKAGES], in that order)
 * 2. the system default recogniser
 * 3. Android 13+: the on-device recogniser
 * 4. the system voice dialog (RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
 * 5. nothing → buttons only
 */
sealed interface RecognizerBackend {
    val label: String

    data class Component(val packageName: String, val className: String) : RecognizerBackend {
        override val label get() = "$packageName/${className.removePrefix(packageName)}"
    }
    data object Default : RecognizerBackend { override val label = "DEFAULT" }
    data object OnDevice : RecognizerBackend { override val label = "ON_DEVICE" }
    data object Dialog : RecognizerBackend { override val label = "DIALOG" }

    /** Mode shown to the controller; anything that may use the network needs the online consent. */
    val mode: RecognitionMode
        get() = when (this) {
            is Component, Default -> RecognitionMode.NETWORK_POSSIBLE
            OnDevice -> RecognitionMode.ON_DEVICE
            Dialog -> RecognitionMode.SYSTEM_DIALOG
        }
}

/** A RecognitionService found through PackageManager. */
data class ServiceComponent(val packageName: String, val className: String)

object RecognizerChoice {
    val PREFERRED_PACKAGES = listOf("com.google.android.tts", "com.google.android.googlequicksearchbox")

    /**
     * SpeechRecognizer error codes that mean "this recogniser cannot serve us" (as opposed to "did not hear
     * anything"): try the next backend straight away. CLIENT 5, SERVER_DISCONNECTED 11,
     * LANGUAGE_NOT_SUPPORTED 12, LANGUAGE_UNAVAILABLE 13.
     */
    val BACKEND_FAILURE_CODES = setOf(5, 11, 12, 13)

    fun order(
        services: List<ServiceComponent>,
        defaultAvailable: Boolean,
        onDeviceAvailable: Boolean,
        dialogAvailable: Boolean,
    ): List<RecognizerBackend> = buildList {
        PREFERRED_PACKAGES.forEach { pkg ->
            services.firstOrNull { it.packageName == pkg }?.let { add(RecognizerBackend.Component(it.packageName, it.className)) }
        }
        if (defaultAvailable) add(RecognizerBackend.Default)
        if (onDeviceAvailable) add(RecognizerBackend.OnDevice)
        if (dialogAvailable) add(RecognizerBackend.Dialog)
    }
}
