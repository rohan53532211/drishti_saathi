package com.drishtisaathi.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognizerChoiceTest {
    private val samsung = ServiceComponent("com.samsung.android.bixby.agent", "com.samsung.android.bixby.agent.RecognitionService")
    private val googleTts = ServiceComponent("com.google.android.tts", "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService")
    private val gsa = ServiceComponent("com.google.android.googlequicksearchbox", "com.google.android.voicesearch.serviceapi.GoogleRecognitionService")

    @Test fun `Google component first even when Samsung is listed first, then default, on-device, dialog`() {
        val order = RecognizerChoice.order(listOf(samsung, gsa, googleTts), defaultAvailable = true, onDeviceAvailable = true, dialogAvailable = true)
        assertEquals(
            listOf(
                RecognizerBackend.Component(googleTts.packageName, googleTts.className),
                RecognizerBackend.Component(gsa.packageName, gsa.className),
                RecognizerBackend.Default,
                RecognizerBackend.OnDevice,
                RecognizerBackend.Dialog,
            ),
            order,
        )
    }

    @Test fun `only Samsung service and nothing else - default if available, else dialog, else none`() {
        assertEquals(listOf(RecognizerBackend.Default), RecognizerChoice.order(listOf(samsung), true, false, false))
        assertEquals(listOf(RecognizerBackend.Dialog), RecognizerChoice.order(listOf(samsung), false, false, true))
        assertTrue(RecognizerChoice.order(emptyList(), false, false, false).isEmpty())
    }

    @Test fun `modes - online-capable backends need consent, on-device does not`() {
        assertEquals(RecognitionMode.NETWORK_POSSIBLE, RecognizerBackend.Component("p", "c").mode)
        assertEquals(RecognitionMode.NETWORK_POSSIBLE, RecognizerBackend.Default.mode)
        assertEquals(RecognitionMode.ON_DEVICE, RecognizerBackend.OnDevice.mode)
        assertEquals(RecognitionMode.SYSTEM_DIALOG, RecognizerBackend.Dialog.mode)
    }

    @Test fun `bind and language failures move on to the next recogniser, no-speech does not`() {
        assertTrue(12 in RecognizerChoice.BACKEND_FAILURE_CODES) // LANGUAGE_NOT_SUPPORTED
        assertTrue(11 in RecognizerChoice.BACKEND_FAILURE_CODES) // SERVER_DISCONNECTED
        assertTrue(6 !in RecognizerChoice.BACKEND_FAILURE_CODES) // SPEECH_TIMEOUT
        assertTrue(7 !in RecognizerChoice.BACKEND_FAILURE_CODES) // NO_MATCH
    }

    @Test fun `label is short for the staff line`() {
        assertEquals("com.google.android.tts/.Svc", RecognizerBackend.Component("com.google.android.tts", "com.google.android.tts.Svc").label)
    }
}
