package de.jeanlucmakiola.agendula.data.sync.push

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Re-registers every account daily, which renews the subscriptions; see [PushRegistrar]. */
@HiltWorker
class PushRenewalWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val registrar: PushRegistrar,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        registrar.updateAll()
        return Result.success()
    }

    companion object {
        const val NAME = "push-renewal"
    }
}
