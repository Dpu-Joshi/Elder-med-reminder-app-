package com.medreminder.reminders

import android.content.Context
import com.medreminder.alarm.AlarmMode
import com.medreminder.alarm.AlarmNotifier
import com.medreminder.alarm.AlarmScheduler
import com.medreminder.alarm.AlarmSpec
import com.medreminder.data.DosePlanner
import com.medreminder.data.DoseStatus
import com.medreminder.data.Prefs
import com.medreminder.data.local.AppDatabase
import com.medreminder.data.local.DoseEntity
import com.medreminder.data.local.ScheduledAlarmEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * Plans the next doses from the medicines stored on the phone and registers one exact alarm
 * per dose. Runs after every sync, boot, clock change and alarm, entirely offline, so the
 * phone keeps ringing for days without internet.
 *
 * Alarm keys: the dose id for the first ring, "<doseId>#a2" and "#a3" for the repeats 5 and 10
 * minutes later, "<doseId>#s<n>" for a snooze, and "test_<time>" for the setup test alarm.
 */
class ReminderScheduler(
    context: Context,
    private val db: AppDatabase,
    private val prefs: Prefs,
    private val notifier: AlarmNotifier,
) {
    private val alarms = AlarmScheduler(context)
    private val mutex = Mutex()

    suspend fun rescheduleAll() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val zone = zone()
            val now = System.currentTimeMillis()
            val today = LocalDate.now(zone)

            val planned = db.medicines().all().flatMap {
                DosePlanner.plan(it.id, it.schedule(), zone, today.minusDays(1), today.plusDays(1))
            }
            db.doses().insertIgnore(
                planned.map { DoseEntity(it.id, it.medicineId, it.scheduledAtMillis, it.localDate, it.localTime) },
            )
            db.doses().deleteFuturePendingExcept(now, planned.map { it.id })
            db.doses().deleteOlderThan(now - KEEP_HISTORY_MILLIS)

            val doses = db.doses().between(now - CATCH_UP_MILLIS, now + HORIZON_MILLIS).associateBy { it.id }
            val desired = mutableMapOf<String, AlarmSpec>()
            for (dose in doses.values) {
                when {
                    dose.doseStatus == DoseStatus.PENDING && dose.scheduledAt > now ->
                        desired[dose.id] = AlarmSpec(dose.id, dose.scheduledAt)
                    // Due while the phone was off: ring now rather than not at all.
                    dose.doseStatus == DoseStatus.PENDING && dose.firedCount == 0 ->
                        desired[dose.id] = AlarmSpec(dose.id, now + CATCH_UP_DELAY_MILLIS)
                    dose.doseStatus == DoseStatus.SNOOZED && dose.snoozedAt != null &&
                        dose.snoozedAt + SNOOZE_MILLIS > now -> {
                        val key = "${dose.id}#s${dose.snoozeCount}"
                        desired[key] = AlarmSpec(key, dose.snoozedAt + SNOOZE_MILLIS)
                    }
                }
            }
            // Keep repeats and test alarms that are still waiting (a reboot wipes them from AlarmManager).
            val previous = db.alarms().all()
            for (alarm in previous) {
                val dose = doses[alarm.key.substringBefore('#')]
                val stillWanted = alarm.triggerAt > now - CATCH_UP_DELAY_MILLIS &&
                    (alarm.key.startsWith(TEST_PREFIX) || (alarm.key.contains("#a") && dose?.doseStatus?.isConfirmed == false))
                if (stillWanted && alarm.key !in desired) {
                    desired[alarm.key] = AlarmSpec(alarm.key, maxOf(alarm.triggerAt, now + CATCH_UP_DELAY_MILLIS), alarm.attempt)
                }
            }

            for (alarm in previous) {
                if (alarm.key !in desired) {
                    alarms.cancel(alarm.key)
                    db.alarms().delete(alarm.key)
                }
            }
            var allExact = true
            for (spec in desired.values) {
                if (register(spec) == AlarmMode.INEXACT_WHILE_IDLE) allExact = false
            }
            prefs.exactAlarmsWorking = allExact
        }
    }

    suspend fun scheduleRepeat(doseId: String, attempt: Int, at: Long) = withContext(Dispatchers.IO) {
        register(AlarmSpec("$doseId#a$attempt", at, attempt))
    }

    suspend fun scheduleSnooze(dose: DoseEntity, snoozedAt: Long) = withContext(Dispatchers.IO) {
        cancelDoseAlarms(dose.id)
        register(AlarmSpec("${dose.id}#s${dose.snoozeCount + 1}", snoozedAt + SNOOZE_MILLIS))
    }

    /** Called when a dose is taken or snoozed: no more rings for it, and the notification goes. */
    suspend fun cancelDoseAlarms(doseId: String) = withContext(Dispatchers.IO) {
        db.alarms().all()
            .filter { it.key == doseId || it.key.startsWith("$doseId#") }
            .forEach {
                alarms.cancel(it.key)
                db.alarms().delete(it.key)
            }
        notifier.cancel(notificationId(doseId))
    }

    suspend fun scheduleTest(): Long = withContext(Dispatchers.IO) {
        val at = System.currentTimeMillis() + 60_000L
        register(AlarmSpec("$TEST_PREFIX$at", at))
        at
    }

    suspend fun cancelAll() = withContext(Dispatchers.IO) {
        db.alarms().all().forEach { alarms.cancel(it.key) }
        db.alarms().clear()
    }

    fun forget(key: String) = alarms.cancel(key)

    private suspend fun register(spec: AlarmSpec): AlarmMode {
        val mode = alarms.schedule(spec, AlarmMode.ALARM_CLOCK)
        db.alarms().upsert(ScheduledAlarmEntity(spec.key, spec.triggerAtMillis, spec.attempt))
        return mode
    }

    private fun zone(): ZoneId = runCatching { ZoneId.of(prefs.timezone) }.getOrDefault(ZoneId.of("Asia/Kolkata"))

    companion object {
        const val TEST_PREFIX = "test_"
        const val MAX_ATTEMPTS = 3
        const val REPEAT_MILLIS = 5 * 60_000L
        const val SNOOZE_MILLIS = 10 * 60_000L
        private const val CATCH_UP_MILLIS = 60 * 60_000L
        private const val CATCH_UP_DELAY_MILLIS = 5_000L
        private const val HORIZON_MILLIS = 2 * 24 * 60 * 60_000L
        private const val KEEP_HISTORY_MILLIS = 7 * 24 * 60 * 60_000L

        fun notificationId(doseId: String): Int = doseId.hashCode()
    }
}
