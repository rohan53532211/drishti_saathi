package com.drishtisaathi.camera

import android.content.Context
import android.speech.tts.TextToSpeech
import com.drishtisaathi.engine.config.CameraConfig
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.Txt
import java.util.Locale

/**
 * Speaks short camera instructions. Minimal on purpose: Track D builds the full voice layer; the lead can
 * back this interface with Track D's speaker later.
 */
interface InstructionSpeaker {
    fun speak(text: Txt, lang: Lang)
    fun stop()
    fun shutdown()
}

/** Silent speaker for tests, previews and when TTS is unavailable. */
object SilentSpeaker : InstructionSpeaker {
    override fun speak(text: Txt, lang: Lang) {}
    override fun stop() {}
    override fun shutdown() {}
}

/**
 * Pure pacing logic so the same instruction is not repeated every frame:
 * - a new instruction must stay current for [stableMs] before it is spoken (gates flicker frame to frame);
 * - at least [minGapMs] between any two utterances;
 * - the same instruction again only after [repeatMs].
 */
class InstructionRateLimiter(
    private val stableMs: Long,
    private val minGapMs: Long,
    private val repeatMs: Long,
) {
    constructor(cfg: CameraConfig) : this(cfg.speakStableMs, cfg.speakMinGapMs, cfg.speakRepeatMs)

    private var currentKey: String? = null
    private var currentSince = 0L
    private var lastSpokenKey: String? = null
    private var lastSpokenAt = Long.MIN_VALUE / 2

    /** Feed the current instruction every frame; returns true when it should be spoken now. */
    fun offer(key: String, nowMs: Long): Boolean {
        if (key != currentKey) {
            currentKey = key
            currentSince = nowMs
        }
        if (nowMs - currentSince < stableMs) return false
        val sinceLast = nowMs - lastSpokenAt
        if (sinceLast < minGapMs) return false
        if (key == lastSpokenKey && sinceLast < repeatMs) return false
        lastSpokenKey = key
        lastSpokenAt = nowMs
        return true
    }

    fun reset() {
        currentKey = null
        lastSpokenKey = null
        lastSpokenAt = Long.MIN_VALUE / 2
    }
}

/**
 * Eye-check voice: each instruction is spoken once. The same instruction again (a repeat) only after [repeatMs]
 * (8 s); a different one (the state changed) at once.
 */
class InstructionPacer(private val repeatMs: Long = 8_000, private val clock: () -> Long = System::currentTimeMillis) {
    private var lastKey: String? = null
    private var lastAt = Long.MIN_VALUE / 2

    fun allow(key: String): Boolean {
        val now = clock()
        if (key == lastKey && now - lastAt < repeatMs) return false
        lastKey = key
        lastAt = now
        return true
    }
}

/** Glue: gate result → rate limiter → speaker. */
class InstructionAnnouncer(
    private val speaker: InstructionSpeaker,
    private val limiter: InstructionRateLimiter,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun announce(key: String, text: Txt, lang: Lang) {
        if (limiter.offer(key, clock())) speaker.speak(text, lang)
    }

    fun announce(result: GateResult, lang: Lang) = announce(result.instructionKey, result.instruction, lang)

    /** Speak immediately (e.g. "done"), bypassing the limiter. */
    fun sayNow(text: Txt, lang: Lang) {
        speaker.stop()
        speaker.speak(text, lang)
    }
}

/**
 * Android TextToSpeech: Hindi (hi-IN) for [Lang.HI], falling back to English text in en-IN when no Hindi
 * voice is installed; English text in en-IN for [Lang.EN]. Utterances flush the queue so instructions never
 * pile up behind each other.
 */
class TtsInstructionSpeaker(context: Context) : InstructionSpeaker {
    private val hindi = Locale.forLanguageTag("hi-IN")
    private val englishIndia = Locale.forLanguageTag("en-IN")
    @Volatile private var ready = false
    @Volatile private var hindiOk = false
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val t = tts
            if (status == TextToSpeech.SUCCESS && t != null) {
                hindiOk = t.isLanguageAvailable(hindi) >= TextToSpeech.LANG_AVAILABLE
                ready = true
            }
        }
    }

    override fun speak(text: Txt, lang: Lang) {
        val t = tts ?: return
        if (!ready) return
        val useHindi = lang == Lang.HI && hindiOk
        val locale = if (useHindi) hindi else englishIndia
        val words = if (useHindi) text.hi else text.en
        if (t.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) t.setLanguage(Locale.ENGLISH)
        t.speak(words, TextToSpeech.QUEUE_FLUSH, null, "cam-" + words.hashCode())
    }

    override fun stop() {
        tts?.stop()
    }

    override fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
