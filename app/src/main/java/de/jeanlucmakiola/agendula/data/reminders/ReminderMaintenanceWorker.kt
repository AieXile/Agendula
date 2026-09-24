package de.jeanlucmakiola.agendula.data.reminders

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * A daily re-sync under the alarms. The scheduler only arms a rolling window,
 * so without a pass now and then a user who never opens the app runs off its
 * far edge; it also repairs alarms a device dropped without a reboot to say so.
 */
@HiltWorker
class ReminderMaintenanceWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scheduler: ReminderScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        runCatching { scheduler.sync() }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "reminder-maintenance"

        /** Idempotent; every launch may call it. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReminderMaintenanceWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(1, TimeUnit.DAYS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
