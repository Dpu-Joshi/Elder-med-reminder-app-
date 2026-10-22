package com.medreminder.alarm

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speaks the reminder with the phone's own text-to-speech, on the alarm stream.
 *
 * Many cheap phones ship without a Hindi voice. [speak] takes an English fallback line for that
 * case, because reading Devanagari with an English voice says nothing useful. [hasPrimaryVoice]
 * lets the setup screen ask the family to install the voice.
 */
class ReminderSpeaker(
    context: Context,
    private val primary: Locale,
    private val onReady: ((ReminderSpeaker) -> Unit)? = null,
) {
    private val main = Handler(Looper.getMainLooper())
    private val callbacks = HashMap<String, () -> Unit>()
    private val nextId = AtomicInteger()
    private var queued: (() -> Unit)? = null
    private var initialized = false

    /** Null until the engine has started. */
    var hasPrimaryVoice: Boolean? = null
        private set

    /** False when the phone has no working text-to-speech engine at all. */
    var engineOk: Boolean = true
        private set

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        main.post { onInit(status) }
    }

    fun speak(text: String, englishFallback: String? = null, onDone: () -> Unit = {}) {
        if (!initialized) {
            queued = { speak(text, englishFallback, onDone) }
            return
        }
        if (!engineOk) {
            onDone()
            return
        }
        val line = if (hasPrimaryVoice == true) {
            tts.setLanguage(primary)
            text
        } else if (englishFallback != null) {
            tts.setLanguage(Locale.ENGLISH)
            englishFallback
        } else {
            onDone()
            return
        }
        val id = "u${nextId.incrementAndGet()}"
        callbacks[id] = onDone
        if (tts.speak(line, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            callbacks.remove(id)
            onDone()
        }
    }

    fun stop() {
        callbacks.clear()
        queued = null
        if (initialized) tts.stop()
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    private fun onInit(status: Int) {
        initialized = true
        if (status != TextToSpeech.SUCCESS) {
            engineOk = false
            hasPrimaryVoice = false
        } else {
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            hasPrimaryVoice = tts.isLanguageAvailable(primary) >= TextToSpeech.LANG_AVAILABLE
            tts.setSpeechRate(SPEECH_RATE)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = finish(utteranceId)

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finish(utteranceId)
                override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
            })
        }
        onReady?.invoke(this)
        queued?.let {
            queued = null
            it()
        }
    }

    private fun finish(utteranceId: String?) {
        main.post { utteranceId?.let { callbacks.remove(it) }?.invoke() }
    }

    companion object {
        val HINDI: Locale = Locale.forLanguageTag("hi-IN")

        /** A little slower than normal for older listeners. */
        private const val SPEECH_RATE = 0.85f
    }
}
