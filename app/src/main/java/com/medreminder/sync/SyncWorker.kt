package com.medreminder.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.medreminder.data.Prefs
import com.medreminder.data.remote.ElderRepository
import com.medreminder.graph
import java.util.concurrent.TimeUnit

/**
 * Elder phone housekeeping. The local part (planning the next alarms) runs with or without
 * internet; the network part (send Taken, pull medicine changes, check in) is best effort and
 * simply tried again next time.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = applicationContext.graph
        if (graph.prefs.mode != Prefs.Mode.ELDER || !graph.prefs.isPaired) return Result.success()

        runCatching { graph.reminders.rescheduleAll() }
        return try {
            graph.elder.pushUnsynced()
            if (graph.elder.pull() == ElderRepository.PullResult.REMOVED) {
                // The family deleted this elder; stop ringing.
                graph.elder.wipeLocal()
                return Result.success()
            }
            graph.elder.checkIn()
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val PERIODIC = "elder-sync"
        private const val SOON = "elder-sync-soon"
        private const val MAX_RETRIES = 5

        /** Every 3 hours, no network constraint, so planning continues offline. */
        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(3, TimeUnit.HOURS).build(),
            )
        }

        /** As soon as there is a network, for example after a Taken tap made offline. */
        fun runSoon(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                SOON,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(context).cancelUniqueWork(SOON)
        }
    }
}
