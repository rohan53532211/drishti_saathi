package com.drishtisaathi.aivoice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** WAV container for 16-bit mono PCM (what the backend and Sarvam speech-to-text expect). */
object Wav {
    const val SAMPLE_RATE = 16_000
    const val MAX_SECONDS = 15

    fun wrap(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }.array()
        return header + pcm
    }
}

/**
 * Tap-to-record: 16 kHz mono 16-bit PCM, stops by itself at [Wav.MAX_SECONDS]. Audio is kept in memory only and
 * handed to the caller as WAV; nothing is written to storage.
 */
class WavRecorder {
    @Volatile private var running = false
    private var thread: Thread? = null
    private val pcm = ByteArrayOutputStream()

    val isRecording: Boolean get() = running

    /** Needs RECORD_AUDIO. [onAutoStop] runs (on the recording thread) when the time limit is reached. */
    @SuppressLint("MissingPermission")
    fun start(onAutoStop: () -> Unit): Boolean {
        if (running) return true
        val min = AudioRecord.getMinBufferSize(Wav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Wav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096))
        }.getOrNull() ?: return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        pcm.reset()
        running = true
        val limit = Wav.SAMPLE_RATE * 2 * Wav.MAX_SECONDS
        thread = Thread {
            val buf = ByteArray(4096)
            try {
                rec.startRecording()
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) synchronized(pcm) { pcm.write(buf, 0, minOf(n, limit - pcm.size())) }
                    if (pcm.size() >= limit) { running = false; onAutoStop() }
                }
            } catch (_: Exception) {
                running = false
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        }.apply { name = "ai-voice-recorder"; start() }
        return true
    }

    /** Stops and returns the WAV (null if nothing was recorded). */
    fun stop(): ByteArray? {
        running = false
        thread?.join(1_000)
        thread = null
        val data = synchronized(pcm) { pcm.toByteArray().also { pcm.reset() } }
        return if (data.isEmpty()) null else Wav.wrap(data)
    }

    fun release() { running = false; thread?.join(500); thread = null; synchronized(pcm) { pcm.reset() } }
}

/** Plays the backend's base64 WAV question audio through a private cache file that is deleted after use. */
class QuestionPlayer(private val context: Context) {
    private var player: MediaPlayer? = null
    private var file: File? = null

    fun play(base64Wav: String, onDone: () -> Unit = {}): Boolean {
        stop()
        val bytes = runCatching { java.util.Base64.getDecoder().decode(base64Wav) }.getOrNull() ?: return false
        val f = File(context.cacheDir, "ai_question.wav").apply { writeBytes(bytes) }
        file = f
        return runCatching {
            player = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                setOnCompletionListener { onDone() }
                prepare()
                start()
            }
        }.isSuccess
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        file?.delete()
        file = null
    }
}
