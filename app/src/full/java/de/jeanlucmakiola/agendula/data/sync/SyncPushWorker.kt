package de.jeanlucmakiola.agendula.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * The end of [SyncTrigger.pushSoon]'s debounce: hands the account to a real
 * sync. Instant, so the REPLACE that restarts the debounce only ever cancels a
 * timer.
 */
@HiltWorker
class SyncPushWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val trigger: SyncTrigger,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val accountName = inputData.getString(SyncWorker.KEY_ACCOUNT_NAME) ?: return Result.failure()
        trigger.enqueueAfterRunning(accountName)
        return Result.success()
    }
}
