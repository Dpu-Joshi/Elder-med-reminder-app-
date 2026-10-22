package com.medreminder.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives every alarm scheduled by [AlarmScheduler] and hands it to the app's [AlarmHost]. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmContract.ACTION_FIRE) return
        val key = intent.getStringExtra(AlarmContract.EXTRA_KEY) ?: return
        val host = context.applicationContext as? AlarmHost ?: return
        val mode = intent.getStringExtra(AlarmContract.EXTRA_MODE)
            ?.let { name -> AlarmMode.entries.firstOrNull { it.name == name } }
            ?: AlarmMode.ALARM_CLOCK
        val fired = FiredAlarm(
            key = key,
            scheduledAtMillis = intent.getLongExtra(AlarmContract.EXTRA_SCHEDULED_AT, 0L),
            firedAtMillis = System.currentTimeMillis(),
            attempt = intent.getIntExtra(AlarmContract.EXTRA_ATTEMPT, 1),
            mode = mode,
        )
        val pending = goAsync()
        host.onAlarmFired(context, fired) { pending.finish() }
    }
}

/**
 * Alarms are wiped by a reboot, and exact ones by revoking the permission; the clock or
 * time zone can also move under them. Every such event re-registers all future alarms.
 */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val host = context.applicationContext as? AlarmHost ?: return
        val pending = goAsync()
        host.rescheduleAll(context) { pending.finish() }
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}
