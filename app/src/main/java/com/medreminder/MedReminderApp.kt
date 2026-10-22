package com.medreminder

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.medreminder.alarm.AlarmHost
import com.medreminder.alarm.AlarmNotifier
import com.medreminder.alarm.FiredAlarm
import com.medreminder.data.Prefs
import com.medreminder.data.local.AppDatabase
import com.medreminder.data.remote.ElderRepository
import com.medreminder.data.remote.FamilyRepository
import com.medreminder.push.FamilyAlertNotifier
import com.medreminder.reminders.DoseAlarmHandler
import com.medreminder.reminders.ReminderScheduler
import com.medreminder.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Hand-wired dependencies: small enough that a DI framework would cost more than it saves. */
class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs = Prefs(app)
    val db = AppDatabase.build(app)
    val alarmNotifier = AlarmNotifier(app)
    val reminders = ReminderScheduler(app, db, prefs, alarmNotifier)
    val elder = ElderRepository(app, prefs, db, reminders)
    val family = FamilyRepository()
    val alarmHandler = DoseAlarmHandler(app, db, prefs, reminders, alarmNotifier, elder, scope)
}

class MedReminderApp : Application(), AlarmHost {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.alarmNotifier.ensureChannel("दवाई का अलार्म · Medicine alarm", "Rings at dose time, even on silent")
        FamilyAlertNotifier.ensureChannel(this)
        if (graph.prefs.mode == Prefs.Mode.ELDER && graph.prefs.isPaired) SyncWorker.schedulePeriodic(this)
    }

    override fun onAlarmFired(context: Context, alarm: FiredAlarm, done: () -> Unit) {
        graph.scope.launch {
            try {
                graph.alarmHandler.onFired(alarm)
            } finally {
                done()
            }
        }
    }

    override fun rescheduleAll(context: Context, done: () -> Unit) {
        graph.scope.launch {
            try {
                if (graph.prefs.mode == Prefs.Mode.ELDER) graph.reminders.rescheduleAll()
            } finally {
                done()
            }
        }
    }

    override fun alarmShowIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
}

val Context.graph: AppGraph get() = (applicationContext as MedReminderApp).graph
