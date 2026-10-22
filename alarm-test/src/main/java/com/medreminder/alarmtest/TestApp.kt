package com.medreminder.alarmtest

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.medreminder.alarm.AlarmHost
import com.medreminder.alarm.AlarmNotifier
import com.medreminder.alarm.AlarmScheduler
import com.medreminder.alarm.AlarmSpec
import com.medreminder.alarm.FiredAlarm
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TestApp : Application(), AlarmHost {
    lateinit var log: TestLog
        private set
    lateinit var settings: TestSettings
        private set
    lateinit var scheduler: AlarmScheduler
        private set
    lateinit var notifier: AlarmNotifier
        private set

    override fun onCreate() {
        super.onCreate()
        log = TestLog(this)
        settings = TestSettings(this)
        scheduler = AlarmScheduler(this)
        notifier = AlarmNotifier(this)
        notifier.ensureChannel("Medicine alarm", "Rings at dose time, even on silent")
    }

    override fun onAlarmFired(context: Context, alarm: FiredAlarm, done: () -> Unit) {
        log.update(alarm.key) { it.copy(firedAt = alarm.firedAtMillis) }
        val entry = log.entry(alarm.key)
        val base = entry?.base ?: alarm.key
        if (log.takenAt(base) != null) {
            done()
            return
        }
        if (settings.repeatIfNotTapped && alarm.attempt < MAX_ATTEMPTS) {
            val next = alarm.attempt + 1
            schedule(
                base = base,
                key = "$base#a$next",
                triggerAt = alarm.firedAtMillis + REPEAT_MILLIS,
                attempt = next,
                isSnooze = entry?.isSnooze == true,
            )
        }
        notifier.show(
            notificationId = base.hashCode(),
            title = if (settings.hindi) "दवाई का समय" else "Medicine time",
            text = if (settings.hindi) "1 गोली · खाने के बाद" else "1 tablet · after food",
            smallIcon = android.R.drawable.ic_lock_idle_alarm,
            fullScreenIntent = alarmScreenIntent(alarm.key, base),
        )
        done()
    }

    override fun rescheduleAll(context: Context, done: () -> Unit) {
        val now = System.currentTimeMillis()
        val pending = log.entries().filter {
            it.firedAt == null && !it.cancelled && it.scheduledAt > now - RESCHEDULE_WINDOW_MILLIS
        }
        // Past-due ones ring right away: a late reminder after a reboot beats none.
        pending.forEach { scheduler.schedule(AlarmSpec(it.key, it.scheduledAt, it.attempt), it.requestedMode) }
        log.addEvent("${clock(now)} alarms re-registered (${pending.size}) after reboot, update, clock or permission change")
        done()
    }

    override fun alarmShowIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

    fun schedule(
        triggerAt: Long,
        base: String = "t$triggerAt",
        key: String = base,
        attempt: Int = 1,
        isSnooze: Boolean = false,
    ): TestLog.Entry {
        val requested = settings.mode
        val used = scheduler.schedule(AlarmSpec(key, triggerAt, attempt), requested)
        val entry = TestLog.Entry(key, base, triggerAt, attempt, requested, used, isSnooze = isSnooze)
        log.put(entry)
        return entry
    }

    /** Ring every [intervalMinutes] from now until the next 7:00 in the morning. */
    fun scheduleOvernight(intervalMinutes: Int): Int {
        val now = System.currentTimeMillis()
        val end = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 7)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        var t = now + intervalMinutes * 60_000L
        var count = 0
        while (t <= end && count < MAX_SERIES) {
            schedule(triggerAt = t)
            t += intervalMinutes * 60_000L
            count++
        }
        return count
    }

    /** Cancels every alarm of this reminder that has not rung yet. */
    fun cancelPending(base: String? = null) {
        log.entries()
            .filter { (base == null || it.base == base) && it.firedAt == null && !it.cancelled }
            .forEach { e ->
                scheduler.cancel(e.key)
                log.update(e.key) { it.copy(cancelled = true) }
            }
    }

    fun alarmScreenIntent(key: String, base: String): PendingIntent =
        PendingIntent.getActivity(
            this,
            key.hashCode(),
            Intent(this, TestAlarmActivity::class.java)
                .putExtra(TestAlarmActivity.EXTRA_KEY, key)
                .putExtra(TestAlarmActivity.EXTRA_BASE, base)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        const val MAX_ATTEMPTS = 3
        const val REPEAT_MILLIS = 5 * 60_000L
        const val SNOOZE_MILLIS = 10 * 60_000L
        private const val RESCHEDULE_WINDOW_MILLIS = 2 * 60 * 60_000L
        private const val MAX_SERIES = 48

        fun clock(millis: Long): String = SimpleDateFormat("dd MMM HH:mm:ss", Locale.US).format(Date(millis))
    }
}

val Context.testApp: TestApp get() = applicationContext as TestApp
