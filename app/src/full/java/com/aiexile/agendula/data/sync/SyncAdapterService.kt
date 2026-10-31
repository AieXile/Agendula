package com.aiexile.agendula.data.sync

import android.accounts.Account
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.IBinder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes

/**
 * The sync adapter whose entire job is to start a WorkManager job and wait.
 *
 * DAVx5's own comment describes the same design: *"We use the sync adapter
 * framework only for the trigger, actual syncing is implemented with
 * WorkManager."*
 *
 * ⚠️ Registering this is **not optional decoration**.
 * `ContentService.hasAuthorityAccess()` gates `requestSync`,
 * `setSyncAutomatically`, `addPeriodicSync`, `setIsSyncable`, `getSyncStatus` and
 * seven more behind a compat change that is on for targetSdk ≥ 34 — which we
 * are. With no sync adapter registered for our authority, every one of those
 * calls **returns silently**: no exception, no log, and it passes on a
 * Robolectric shadow. The visible result is an account permanently reading "Sync
 * off for all items" with a greyed-out "Sync now", and it is documented on no
 * Android behaviour-changes page.
 *
 * The greying-out is why the app ships its own sync button regardless:
 * `enabledSyncNowMenu()` needs at least one checked authority switch, and ours
 * is `userVisible="false"`.
 */
class SyncAdapterService : Service() {

    private val adapter by lazy { CalDavSyncAdapter(applicationContext) }

    override fun onBind(intent: Intent?): IBinder = adapter.syncAdapterBinder
}

private class CalDavSyncAdapter(context: Context) :
    AbstractThreadedSyncAdapter(context, /* autoInitialize = */ true) {

    override fun onPerformSync(
        account: Account,
        extras: Bundle,
        authority: String,
        provider: ContentProviderClient,
        syncResult: SyncResult,
    ) {
        val workManager = WorkManager.getInstance(context)
        val uniqueName = SyncTrigger(context).enqueue(account.name)

        // Block this thread until the work reaches a terminal state. The framework
        // treats onPerformSync returning as "the sync is done", so returning early
        // would make every sync look instantaneous and defeat the back-off it
        // applies on failure. runBlocking is fine here: onPerformSync is already
        // called on a background thread the framework owns.
        //
        // ⚠️ Watch the **unique work name**, not the request id. enqueueUniqueWork
        // is asynchronous — the WorkSpec row is not written by the time the next
        // line runs — so a flow keyed on the id emits null for an unknown id and
        // the wait returns immediately, having waited for nothing. And under
        // KEEP, when a run is already in flight, our request is never enqueued at
        // all and its id stays unknown forever. Keying on the name handles both:
        // it waits for whichever run is actually happening.
        val infos = runCatching {
            runBlocking {
                withTimeoutOrNull(WORKER_TIMEOUT_MINUTES.minutes) {
                    workManager.getWorkInfosForUniqueWorkFlow(uniqueName)
                        .first { infos -> infos.isNotEmpty() && infos.all { it.state.isFinished } }
                }
            }
        }.getOrNull()

        // Counted as a soft error: the engine's own per-collection and
        // per-resource isolation decides what is actually fatal, and telling the
        // framework otherwise would have it back off the whole account. Being
        // deduplicated by KEEP is *not* a failure — the sync is happening, this
        // trigger simply joined the one already running.
        val timedOut = infos == null
        val failed = infos?.any { it.state == WorkInfo.State.FAILED } == true
        if (timedOut || failed) syncResult.stats.numIoExceptions++
    }

    private companion object {
        /** DAVx5 uses the same ceiling; an ordinary worker is documented for < 10 min. */
        const val WORKER_TIMEOUT_MINUTES = 10L
    }
}
