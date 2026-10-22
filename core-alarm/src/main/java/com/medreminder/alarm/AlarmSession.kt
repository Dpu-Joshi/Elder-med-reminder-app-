package com.medreminder.alarm

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Locale

/**
 * What the alarm screen plays: a few seconds of alarm sound, then the spoken reminder, again
 * and again until the elder taps a button or [totalMillis] runs out. After that the app's
 * follow-up alarms (5 and 10 minutes later) take over.
 */
class AlarmSession(
    context: Context,
    locale: Locale,
    private val ringMillis: Long = 6_000,
    private val totalMillis: Long = 90_000,
    private val minVolumeFraction: Float? = AlarmRinger.DEFAULT_MIN_VOLUME,
) {
    private val main = Handler(Looper.getMainLooper())
    private val ringer = AlarmRinger(context)
    val speaker = ReminderSpeaker(context, locale)

    private var line = ""
    private var fallback: String? = null
    private var startedAt = 0L
    private var running = false
    private var cycle = 0

    fun start(line: String, englishFallback: String?) {
        this.line = line
        fallback = englishFallback
        startedAt = SystemClock.elapsedRealtime()
        running = true
        ringPhase()
    }

    /** The Repeat button: stop the loop and say the reminder once more. */
    fun repeatNow() {
        stopSound()
        speaker.speak(line, fallback)
    }

    fun stopSound() {
        running = false
        cycle++
        main.removeCallbacksAndMessages(null)
        ringer.stop()
        speaker.stop()
    }

    fun release() {
        stopSound()
        speaker.shutdown()
    }

    private fun ringPhase() {
        if (!running) return
        if (SystemClock.elapsedRealtime() - startedAt >= totalMillis) {
            stopSound()
            return
        }
        val token = ++cycle
        ringer.start(minVolumeFraction)
        main.postDelayed({ if (token == cycle) speakPhase(token) }, ringMillis)
    }

    private fun speakPhase(token: Int) {
        ringer.stop()
        var advanced = false
        val next = Runnable {
            if (!advanced && token == cycle) {
                advanced = true
                main.postDelayed({ if (token == cycle) ringPhase() }, PAUSE_MILLIS)
            }
        }
        speaker.speak(line, fallback) { next.run() }
        // Some engines never report that they finished speaking.
        main.postDelayed(next, MAX_SPEECH_MILLIS)
    }

    private companion object {
        const val PAUSE_MILLIS = 1_500L
        const val MAX_SPEECH_MILLIS = 15_000L
    }
}
