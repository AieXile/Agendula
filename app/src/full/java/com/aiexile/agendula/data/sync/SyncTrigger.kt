package com.aiexile.agendula.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Starts a sync for one account.
 *
 * Shared by the sync adapter and by the app's own "sync now", because the app
 * cannot rely on the system trigger: ⚠️ `ContentService.hasAuthorityAccess()`
 * gates `requestSync` behind a compat change that is on at targetSdk ≥ 34, and
 * our authority is `userVisible="false"`, so Settings greys "Sync now" out. The
 * in-app button enqueues the work directly and is unaffected.
 */
@Singleton
class SyncTrigger @Inject constructor(
    @ApplicationContext private val context: Context,
) : SyncRequests {

    override fun syncNow(accountName: String) {
        enqueue(accountName)
    }

    /**
     * Starts a sync now.
     *
     * @param expedited for a trigger the user is looking at. ⚠️ Paired with
     *   `RUN_AS_NON_EXPEDITED_WORK_REQUEST`, which is not optional: the expedited
     *   quota is per-app and exhaustible, and the alternative policy
     *   (`DROP_WORK_REQUEST`) silently discards the sync the user just asked for.
     *   Never set from a background trigger — a boot receiver spending the quota
     *   leaves none for the button.
     * @return the unique work name, which the caller may wait on.
     */
    fun enqueue(accountName: String, expedited: Boolean = false): String {
        val uniqueName = SyncWorker.uniqueNameFor(accountName)
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(inputFor(accountName))
            .setConstraints(NETWORK)
            .apply {
                if (expedited) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName,
            // KEEP, not REPLACE: a periodic trigger arriving while a manual sync
            // is mid-flight must not cancel it and lose the cursor.
            ExistingWorkPolicy.KEEP,
            request,
        )
        return uniqueName
    }

    /**
     * Pushes a local edit soon, rather than on the next periodic window.
     *
     * Debounced: each call restarts [PUSH_DELAY], so a burst of edits costs one
     * sync. What waits out the delay is a [SyncPushWorker], which hands over to
     * [enqueueAfterRunning] — so the replace can only ever cancel a timer, never
     * a sync that is mid-flight.
     */
    fun pushSoon(accountName: String) {
        val request = OneTimeWorkRequestBuilder<SyncPushWorker>()
            .setInputData(inputFor(accountName))
            .setInitialDelay(PUSH_DELAY.inWholeSeconds, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            pushNameFor(accountName),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /**
     * A sync that runs after any one already running for [accountName].
     *
     * Not [enqueue]'s KEEP: a sync already past its upload phase would swallow
     * this request and leave the edit that prompted it for the next window.
     */
    fun enqueueAfterRunning(accountName: String) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(inputFor(accountName))
            .setConstraints(NETWORK)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            SyncWorker.uniqueNameFor(accountName),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    /**
     * A sync for a push message: the server says something changed.
     *
     * Appended like [enqueueAfterRunning], since a sync already past its
     * download would miss the change — but only once. A burst of pushes during
     * one sync must cost one more sync, not one each.
     */
    suspend fun enqueueFromPush(accountName: String) = pushEnqueue.withLock {
        val waiting = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(SyncWorker.uniqueNameFor(accountName))
            .first()
            .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
        if (!waiting) enqueueAfterRunning(accountName)
    }

    /** Serialises [enqueueFromPush]'s check and its enqueue across concurrent messages. */
    private val pushEnqueue = Mutex()

    /**
     * Puts the account on the periodic schedule.
     *
     * A plain `PeriodicWorkRequest` and no foreground service, deliberately —
     * see [SyncWorker]. WorkManager restores its own schedule after a reboot, so
     * nothing has to re-arm this from `BOOT_COMPLETED`; that matters because
     * Android 15 forbids starting a `dataSync` foreground service from boot, and
     * a design that needed one would have no way to run at all.
     *
     * ⚠️ Be honest about the cadence in the UI. The interval setting is a
     * floor, not a promise: in the `rare` and `restricted` App Standby buckets
     * network access is off entirely, and the genuine worst case is once overnight.
     *
     * @param intervalMinutes the sync-interval setting; 0 (manual only) takes
     *   the account off the schedule.
     * @param intervalChanged the user just picked a new interval, so a schedule
     *   already in place is replaced rather than kept.
     */
    fun schedule(accountName: String, intervalMinutes: Int, intervalChanged: Boolean = false) {
        val minutes = intervalMinutes
        if (minutes <= 0) {
            WorkManager.getInstance(context).cancelUniqueWork(periodicNameFor(accountName))
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            minutes.toLong(), TimeUnit.MINUTES,
            flexFor(minutes).inWholeMinutes, TimeUnit.MINUTES,
        )
            .setInputData(inputFor(accountName))
            .setConstraints(NETWORK)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            periodicNameFor(accountName),
            // UPDATE on every call would restart the interval on every app
            // launch, so a device that is opened often would never reach the
            // end of one. Only a changed interval replaces it.
            if (intervalChanged) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Takes a removed account off the schedule. */
    fun cancel(accountName: String) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(periodicNameFor(accountName))
            cancelUniqueWork(pushNameFor(accountName))
            cancelUniqueWork(SyncWorker.uniqueNameFor(accountName))
        }
    }

    private fun inputFor(accountName: String) =
        Data.Builder().putString(SyncWorker.KEY_ACCOUNT_NAME, accountName).build()

    private companion object {
        /** How long a local edit waits for more before it is pushed. */
        val PUSH_DELAY: Duration = 30.seconds

        /** The tail of each interval the system may run us in: a quarter of it, at most an hour. */
        fun flexFor(intervalMinutes: Int): Duration =
            (intervalMinutes / 4).minutes.coerceIn(5.minutes, 1.hours)

        /**
         * Sync needs a network, and saying so lets WorkManager run us the moment
         * connectivity returns rather than on the next interval.
         */
        val NETWORK: Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun periodicNameFor(accountName: String) = "caldav-sync-periodic:$accountName"

        fun pushNameFor(accountName: String) = "caldav-sync-push:$accountName"
    }
}
