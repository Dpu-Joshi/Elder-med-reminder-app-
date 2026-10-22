package com.medreminder.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.ceil

/** Loops the phone's alarm sound on the alarm stream and vibrates, until [stop]. */
class AlarmRinger(context: Context) {
    private val context = context.applicationContext
    private val audioManager = this.context.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null

    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            this.context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            this.context.getSystemService(Vibrator::class.java)
        }

    /**
     * @param minVolumeFraction raises the alarm volume to at least this share of its maximum
     *   (alarm volume is separate from the ringer, so silent mode does not mute it, but it can
     *   be set to zero). Pass null to leave the volume alone.
     */
    fun start(minVolumeFraction: Float? = DEFAULT_MIN_VOLUME) {
        stop()
        minVolumeFraction?.let(::ensureAlarmVolume)
        player = createPlayer()
        vibrate()
    }

    fun stop() {
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        vibrator.cancel()
    }

    val isRinging: Boolean get() = player?.isPlaying == true

    fun alarmVolume(): Int = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)

    private fun ensureAlarmVolume(fraction: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        if (max <= 0) return
        val target = ceil(max * fraction).toInt().coerceIn(1, max)
        if (audioManager.getStreamVolume(AudioManager.STREAM_ALARM) < target) {
            // Can throw in some Do Not Disturb states; ringing at the current volume is still better than nothing.
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, target, 0) }
        }
    }

    private fun createPlayer(): MediaPlayer? {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        // The user's alarm tone can be "None"; fall back to the ringtone, then the notification sound.
        // The default URIs go through the settings provider, so no storage permission is needed.
        val candidates = listOf(
            RingtoneManager.TYPE_ALARM,
            RingtoneManager.TYPE_RINGTONE,
            RingtoneManager.TYPE_NOTIFICATION,
        ).map { RingtoneManager.getDefaultUri(it) }

        for (uri in candidates) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(attributes)
                mp.setDataSource(context, uri)
                mp.isLooping = true
                mp.prepare()
                mp.start()
                return mp
            } catch (e: Exception) {
                mp.release()
            }
        }
        return null
    }

    private fun vibrate() {
        if (!vibrator.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                effect,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
        }
    }

    companion object {
        const val DEFAULT_MIN_VOLUME = 0.7f
    }
}
