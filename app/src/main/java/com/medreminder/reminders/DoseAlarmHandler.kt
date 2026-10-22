package com.medreminder.reminders

import android.content.Context
import com.medreminder.R
import com.medreminder.alarm.AlarmNotifier
import com.medreminder.alarm.FiredAlarm
import com.medreminder.data.Language
import com.medreminder.data.Prefs
import com.medreminder.data.ReminderText
import com.medreminder.data.local.AppDatabase
import com.medreminder.data.remote.ElderRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What happens when an alarm goes off on the elder's phone. */
class DoseAlarmHandler(
    private val context: Context,
    private val db: AppDatabase,
    private val prefs: Prefs,
    private val reminders: ReminderScheduler,
    private val notifier: AlarmNotifier,
    private val elder: ElderRepository,
    private val scope: CoroutineScope,
) {
    suspend fun onFired(alarm: FiredAlarm) {
        db.alarms().delete(alarm.key)
        val lang = prefs.language

        if (alarm.key.startsWith(ReminderScheduler.TEST_PREFIX)) {
            notifier.show(
                notificationId = TEST_NOTIFICATION_ID,
                title = ReminderText.today(lang),
                text = if (lang == Language.HINDI) "टेस्ट अलार्म" else "Test alarm",
                smallIcon = R.drawable.ic_stat_pill,
                fullScreenIntent = DoseAlarmActivity.testIntent(context),
            )
            return
        }

        val doseId = alarm.key.substringBefore('#')
        val dose = db.doses().get(doseId) ?: return
        if (dose.doseStatus.isConfirmed) return
        db.doses().markFired(doseId, alarm.firedAtMillis)

        // Rings again 5 and 10 minutes later if nobody taps; the family is alerted at 30.
        if (alarm.attempt < ReminderScheduler.MAX_ATTEMPTS) {
            reminders.scheduleRepeat(doseId, alarm.attempt + 1, alarm.firedAtMillis + ReminderScheduler.REPEAT_MILLIS)
        }

        val medicine = db.medicines().get(dose.medicineId)?.toMedicine()
        val title = if (lang == Language.HINDI) "दवाई का समय" else "Medicine time"
        val text = medicine?.let {
            listOfNotNull(
                ReminderText.doseLabel(lang, it.doseAmount, it.doseUnit),
                ReminderText.foodLabel(lang, it.foodRule),
            ).joinToString(" · ")
        }.orEmpty()
        notifier.show(
            notificationId = ReminderScheduler.notificationId(doseId),
            title = title,
            text = text,
            smallIcon = R.drawable.ic_stat_pill,
            fullScreenIntent = DoseAlarmActivity.pendingIntent(context, doseId),
        )

        // Off the alarm's 10-second budget: extend the plan and tell the server the phone is alive.
        scope.launch {
            runCatching { reminders.rescheduleAll() }
            runCatching { elder.checkIn() }
        }
    }

    companion object {
        const val TEST_NOTIFICATION_ID = 7
    }
}
