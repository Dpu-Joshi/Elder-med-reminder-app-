package com.medreminder.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * Schedules alarms on the phone itself, so they ring with no internet.
 *
 * Each alarm is identified by its key through the intent's data URI, so scheduling the same key
 * again replaces the old alarm and [cancel] needs only the key.
 */
class AlarmScheduler(context: Context) {
    private val context = context.applicationContext
    private val alarmManager = this.context.getSystemService(AlarmManager::class.java)

    /** False on Android 12+ until the family member allows "Alarms & reminders" for the app. */
    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    /**
     * Schedules [spec] and returns the mode actually used: when exact alarms are not allowed it
     * falls back to [AlarmMode.INEXACT_WHILE_IDLE], which the caller should surface as a warning.
     */
    fun schedule(spec: AlarmSpec, preferred: AlarmMode = AlarmMode.ALARM_CLOCK): AlarmMode {
        val mode = if (preferred != AlarmMode.INEXACT_WHILE_IDLE && !canScheduleExact()) {
            AlarmMode.INEXACT_WHILE_IDLE
        } else {
            preferred
        }
        val operation = firePendingIntent(spec, mode)
        return try {
            setAlarm(mode, spec.triggerAtMillis, operation)
            mode
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call.
            val fallback = firePendingIntent(spec, AlarmMode.INEXACT_WHILE_IDLE)
            setAlarm(AlarmMode.INEXACT_WHILE_IDLE, spec.triggerAtMillis, fallback)
            AlarmMode.INEXACT_WHILE_IDLE
        }
    }

    fun cancel(key: String) {
        val pending = PendingIntent.getBroadcast(
            context,
            0,
            fireIntent(key),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager.cancel(pending)
        pending.cancel()
    }

    fun isScheduled(key: String): Boolean = PendingIntent.getBroadcast(
        context,
        0,
        fireIntent(key),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    ) != null

    private fun setAlarm(mode: AlarmMode, triggerAtMillis: Long, operation: PendingIntent) {
        when (mode) {
            AlarmMode.ALARM_CLOCK -> alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent()),
                operation,
            )
            AlarmMode.EXACT_WHILE_IDLE ->
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
            AlarmMode.INEXACT_WHILE_IDLE ->
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
        }
    }

    private fun firePendingIntent(spec: AlarmSpec, mode: AlarmMode): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            fireIntent(spec.key)
                .putExtra(AlarmContract.EXTRA_KEY, spec.key)
                .putExtra(AlarmContract.EXTRA_SCHEDULED_AT, spec.triggerAtMillis)
                .putExtra(AlarmContract.EXTRA_ATTEMPT, spec.attempt)
                .putExtra(AlarmContract.EXTRA_MODE, mode.name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun fireIntent(key: String): Intent =
        Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmContract.ACTION_FIRE)
            .setData(
                Uri.Builder()
                    .scheme(AlarmContract.URI_SCHEME)
                    .authority(context.packageName)
                    .appendPath(key)
                    .build(),
            )

    private fun showIntent(): PendingIntent {
        (context as? AlarmHost)?.let { return it.alarmShowIntent(context) }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent().setPackage(context.packageName)
        return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE)
    }
}
