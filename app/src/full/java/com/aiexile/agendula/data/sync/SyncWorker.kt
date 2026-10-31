package com.aiexile.agendula.data.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import com.aiexile.agendula.R
import com.aiexile.agendula.data.reminders.ReminderScheduler

/**
 * Where sync actually happens.
 *
 * Two things about this worker are decided already. It is a
 * **`CoroutineWorker` with no foreground service**: an ordinary
 * worker is documented for under 10 minutes, and escalating to `setForeground`
 * pulls in `FOREGROUND_SERVICE_DATA_SYNC`, the Android 15 six-hours-per-24
 * `dataSync` budget whose failure mode is a fatal `RemoteServiceException`, and
 * a Play requirement for a video demo per declared FGS type. And it must be
 * **chunked and resumable** — the sync cursor is persisted per collection so a
 * killed worker resumes rather than restarts, because under WorkManager process
 * death mid-sync is routine rather than exotic.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val engine: SyncEngine,
    private val noticeNotifier: SyncNoticeNotifier,
    private val reminderScheduler: ReminderScheduler,
    private val accountState: AccountStateStore,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val accountName = inputData.getString(KEY_ACCOUNT_NAME) ?: return Result.failure()

        return when (val outcome = engine.sync(accountName)) {
            is SyncEngine.Result.Synced -> {
                // ⚠️ Success even when collections failed. A retry re-runs the
                // whole account, and WorkManager's backoff would then punish the
                // four healthy collections for the one that 500s — while the
                // failing one is already contained by its own quarantine counter.
                outcome.reports.forEach { report ->
                    if (report.failure != null || report.hadWork) Log.i(TAG, report.toString())
                }
                // ⚠️ Said out loud, not only logged. `SyncReport`'s own doc
                // calls the report "the other half" of server-wins, and this
                // worker runs on a four-hour timer with the app closed — so a
                // log line is the same as saying nothing. Only what is new: the
                // store has already dropped whatever the user has been told.
                noticeNotifier.post(accountName, outcome.notices)
                // Nothing else re-arms reminders for what a sync pulled in: in our
                // own store no provider broadcast fires.
                if (outcome.reports.any { it.hadWork || it.discardedEdits.isNotEmpty() }) {
                    runCatching { reminderScheduler.sync() }
                }
                noticeNotifier.cancelSignIn(accountName)
                Result.success()
            }

            // Only the user can fix this, and retrying costs them Nextcloud's
            // per-IP brute-force throttle — which takes their *other* clients
            // down with it. Said once per stop, since this runs with the app closed.
            is SyncEngine.Result.NeedsSignIn -> {
                if (accountState.markSignInNotified(outcome.accountId)) {
                    noticeNotifier.postSignIn(accountName, outcome.accountId)
                }
                Result.failure()
            }

            is SyncEngine.Result.Misconfigured -> Result.failure()

            // Not a failure: the account is kept, and syncs again once the user
            // switches back to Agendula's own storage.
            SyncEngine.Result.Paused -> Result.success()
        }
    }

    /**
     * ⚠️ Implemented **unconditionally**, even though this worker never asks to
     * run in the foreground.
     *
     * `setExpedited` falls back to a foreground service below API 31, and
     * WorkManager calls this to build it. The default implementation throws
     * `IllegalStateException`, so a worker that only ever runs expedited on
     * modern devices crashes on every device running API 29 or 30 — which we
     * support. It is never actually shown above API 30.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.sync_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )

        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.sync_notification_title))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // ⚠️ **No `foregroundServiceType`.** Declaring `dataSync` is what drags in
        // `FOREGROUND_SERVICE_DATA_SYNC`, the Android 15 six-hours-per-24 budget
        // whose failure mode is a fatal `RemoteServiceException`, and a Play
        // requirement for a video demo per declared type — the whole tail this
        // worker exists to avoid. It is not needed either: above API 30
        // `setExpedited` uses an expedited job and never calls this at all, and
        // types only became mandatory at API 34.
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        /** One in-flight sync per account, so a manual trigger cannot pile up. */
        fun uniqueNameFor(accountName: String) = "caldav-sync:$accountName"

        const val KEY_ACCOUNT_NAME = "accountName"

        private const val TAG = "SyncWorker"
        private const val CHANNEL_ID = "sync"
        private const val NOTIFICATION_ID = 4001
    }
}
