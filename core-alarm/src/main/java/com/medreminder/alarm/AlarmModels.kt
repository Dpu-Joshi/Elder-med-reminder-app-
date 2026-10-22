package com.medreminder.alarm

import android.app.PendingIntent
import android.content.Context

/**
 * How an alarm is handed to [android.app.AlarmManager].
 *
 * [ALARM_CLOCK] is the most reliable: it survives Doze and most OEM battery savers, and shows
 * the alarm icon in the status bar. [EXACT_WHILE_IDLE] is kept so the test app can compare the
 * two on real phones. [INEXACT_WHILE_IDLE] is the fallback when exact alarms are not allowed;
 * it can ring many minutes late.
 */
enum class AlarmMode { ALARM_CLOCK, EXACT_WHILE_IDLE, INEXACT_WHILE_IDLE }

/** One alarm the app wants to ring. [key] is the app's own id (for example a dose id) and must be unique. */
data class AlarmSpec(
    val key: String,
    val triggerAtMillis: Long,
    val attempt: Int = 1,
)

/** What [AlarmReceiver] hands back to the app when an alarm goes off. */
data class FiredAlarm(
    val key: String,
    val scheduledAtMillis: Long,
    val firedAtMillis: Long,
    val attempt: Int,
    val mode: AlarmMode,
) {
    val delayMillis: Long get() = firedAtMillis - scheduledAtMillis
}

/**
 * Implemented by the app's [android.app.Application] class. The receivers in this library
 * find it through `context.applicationContext`.
 */
interface AlarmHost {
    /** Called on the main thread when an alarm fires. Call [done] when finished, within about 10 seconds. */
    fun onAlarmFired(context: Context, alarm: FiredAlarm, done: () -> Unit)

    /** Register every future alarm again: after a reboot, an app update, a clock change or a permission change. */
    fun rescheduleAll(context: Context, done: () -> Unit)

    /** Opened when the user taps the alarm icon in the status bar. */
    fun alarmShowIntent(context: Context): PendingIntent
}

internal object AlarmContract {
    const val ACTION_FIRE = "com.medreminder.alarm.action.FIRE"
    const val EXTRA_KEY = "com.medreminder.alarm.extra.KEY"
    const val EXTRA_SCHEDULED_AT = "com.medreminder.alarm.extra.SCHEDULED_AT"
    const val EXTRA_ATTEMPT = "com.medreminder.alarm.extra.ATTEMPT"
    const val EXTRA_MODE = "com.medreminder.alarm.extra.MODE"
    const val URI_SCHEME = "medreminder-alarm"
}
