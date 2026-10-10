package de.jeanlucmakiola.agendula.data.sync

import de.jeanlucmakiola.agendula.data.di.IoDispatcher
import de.jeanlucmakiola.agendula.data.sync.push.PushRegistrar
import de.jeanlucmakiola.agendula.data.tasks.room.AccountEntity
import de.jeanlucmakiola.agendula.data.tasks.room.TasksDatabase
import de.jeanlucmakiola.caldav.CalDavHttp
import de.jeanlucmakiola.caldav.CalendarCollection
import de.jeanlucmakiola.caldav.RemoteCalendar
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Syncs one account: every list it owns, against the collection each points at.
 *
 * ⚠️ **A failed collection must not fail the account.** One revoked share, one
 * calendar the server 500s on, must not stop the other four from syncing — so
 * every collection's outcome is a [SyncReport] rather than an exception, and the
 * account's own result is the list of them.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val database: TasksDatabase,
    private val store: RoomSyncStore,
    private val credentials: CredentialStore,
    private val quarantine: QuarantineStore,
    private val cadence: SyncCadenceStore,
    private val accountState: AccountStateStore,
    private val notices: SyncNoticeStore,
    private val availability: SyncAvailability,
    private val push: PushRegistrar,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Why an account could not be synced at all, as opposed to one of its lists. */
    sealed interface Result {
        data class Synced(
            val reports: List<SyncReport>,
            /**
             * What this run destroyed or gave up on that was not already on
             * record — the caller's cue to say so out loud.
             */
            val notices: List<SyncNotice> = emptyList(),
        ) : Result

        /** The credential is gone or undecryptable: only re-authentication helps. */
        data class NeedsSignIn(val accountId: Long, val reason: String) : Result

        data class Misconfigured(val reason: String) : Result

        /** External storage mode: nothing reads what a sync would write, so none runs. */
        data object Paused : Result
    }

    suspend fun sync(accountName: String): Result = withContext(io) {
        if (!availability.accountsUsable()) return@withContext Result.Paused
        val account = database.accounts().all().firstOrNull { it.displayName == accountName }
            ?: return@withContext Result.Misconfigured("no such account: $accountName")

        // ⚠️ Before anything reaches the network. A periodic request that outlives
        // the stop — or a manual trigger on a stopped account — must not spend a
        // request on a credential we already know the server rejects: Nextcloud
        // throttles then 429s per source IP, and that lands on the user's other
        // clients rather than on us.
        if (accountState.needsSignIn(account.id)) {
            return@withContext Result.NeedsSignIn(account.id, "waiting for you to sign in again")
        }

        val username = account.username
            ?: return@withContext fatal(account.id, Result.Misconfigured("account has no username"))
        val origin = account.principalUrl?.toHttpUrlOrNull()
            ?: return@withContext fatal(
                account.id,
                Result.Misconfigured("account has no principal URL"),
            )

        val password = when (val secret = credentials.get(account.id)) {
            is CredentialStore.Secret.Present -> secret.value
            CredentialStore.Secret.Absent -> {
                stopForSignIn(account.id, "no stored password")
                return@withContext Result.NeedsSignIn(account.id, "no stored password")
            }
            is CredentialStore.Secret.Unrecoverable -> {
                stopForSignIn(account.id, secret.reason)
                return@withContext Result.NeedsSignIn(account.id, secret.reason)
            }
        }

        val client = CalDavHttp.authenticated(USER_AGENT, username, password, origin)
        val subscriptions = push.subscriptionsByHref(account.id)
        val reports = syncCollections(account) { url ->
            CalendarCollection(client, url, pushRegistration = subscriptions[url.toString()])
        }

        // ⚠️ Before the auth check, not after it. A 401 on one collection does
        // not un-discard an edit another collection already destroyed, and
        // returning NeedsSignIn past this point would drop the record of it.
        // Outside `syncCollections` because that is driven without a network by
        // the reconciliation tests, which have nothing to say about notices.
        val fresh = notices.record(
            accountId = account.id,
            at = kotlin.time.Clock.System.now(),
            reports = reports,
            titles = quarantinedTitles(reports),
        )

        if (reports.any { it.authFailure }) {
            // ⚠️ Stop the account rather than let the schedule keep trying.
            // Nextcloud throttles and then 429s **per source IP**, so a timer on a
            // dead app password degrades every other Nextcloud client on the
            // user's network — and there is nothing here to retry: the fix is a
            // sign-in only the user can perform.
            stopForSignIn(account.id, "the server rejected the credentials")
            return@withContext Result.NeedsSignIn(account.id, "the server rejected the credentials")
        }

        accountState.setNeedsSignIn(account.id, false)
        // Never fails the sync: push is an optimisation on top of the schedule.
        runCatching { push.onSynced(account, reports) }
        Result.Synced(reports, fresh)
    }

    /**
     * The local title of each quarantined resource, by href.
     *
     * ⚠️ Resolved here rather than left to the store, which has no database.
     * Without it the user is told "a task has stopped syncing" over a row
     * reading `a1f9c3e2-….ics` — the opaque blob `SyncNoticeStore` refuses to
     * show for a discarded edit, and unactionable for exactly the same reason.
     * A resource we have never stored has no title to find, and its filename is
     * then genuinely all there is.
     */
    private fun quarantinedTitles(reports: List<SyncReport>): Map<String, String> =
        reports.filter { it.quarantined.isNotEmpty() }
            .flatMap { report ->
                val wanted = report.quarantined.mapTo(mutableSetOf()) { it.href }
                store.rowsIn(report.listId)
                    .filter { it.href in wanted && !it.title.isNullOrBlank() }
                    .map { it.href!! to it.title!! }
            }
            .toMap()

    /**
     * Records why the account could not be synced at all.
     *
     * ⚠️ Without this the row keeps its old `lastSyncAt`, and the accounts screen
     * goes on reporting "synced 5 minutes ago" for an account whose credential
     * can no longer be decrypted — the silent failure the account layer exists to
     * avoid.
     */
    /**
     * Marks an account as stopped until the user signs in again.
     *
     * ⚠️ It does **not** cancel the work, even though stopping the timer is the
     * whole point — because this runs *inside* `SyncWorker`, and one of the two
     * unique names it would cancel is the WorkSpec currently executing us.
     * WorkManager would interrupt the coroutine, so `Result.NeedsSignIn` would
     * never be returned and the adapter would see CANCELLED rather than FAILED.
     *
     * The flag does the work instead: [sync] refuses before touching the network,
     * so a firing that survives costs nothing, and [AccountRepository.rescheduleAll]
     * cancels the schedule from outside any worker.
     */
    private suspend fun stopForSignIn(accountId: Long, reason: String) {
        accountState.setNeedsSignIn(accountId, true)
        database.accounts().recordSync(accountId, at = null, error = reason)
    }

    private fun fatal(accountId: Long, result: Result): Result {
        val reason = when (result) {
            is Result.NeedsSignIn -> result.reason
            is Result.Misconfigured -> result.reason
            is Result.Synced, Result.Paused -> return result
        }
        database.accounts().recordSync(accountId, at = null, error = reason)
        return result
    }

    /** Split out from [sync] so the reconciliation can be driven without a network. */
    internal suspend fun syncCollections(
        account: AccountEntity,
        remoteFor: (HttpUrl) -> RemoteCalendar,
    ): List<SyncReport> {
        // Lists owing a DELETE first: a task moved between two of this account's
        // collections then leaves the old one before it arrives in the new one,
        // which a server that keeps UIDs unique per account needs.
        val owing = database.tasks().listsWithTombstones().toSet()
        val lists = database.taskLists().syncedForAccount(account.id)
            .sortedBy { it.id !in owing }
        val listIds = lists.map { it.id }.toSet()

        // ⚠️ Only this account's keys are written back. The counts are global
        // while the worker's uniqueness is only per account, so replacing the
        // whole map would discard a concurrently syncing account's increments and
        // resurrect the counters it had cleared.
        val before = quarantine.counts()
        val counts = before.toMutableMap()
        val syncer = CollectionSyncer(store)

        val now = kotlin.time.Clock.System.now()
        val lastFull = cadence.lastFullSync()

        // Never reconciled, or the token has been trusted long enough.
        val due = lists.associate { list ->
            val since = lastFull[list.id]
            list.id to (since == null || now - since >= SyncCadenceStore.FULL_RECONCILIATION_INTERVAL)
        }

        val reports = lists.map { list ->
            val url = list.href?.toHttpUrlOrNull()
                ?: return@map SyncReport(list.id, list.name, failure = "list has no collection URL")
            syncer.sync(
                list = list,
                remote = remoteFor(url),
                quarantine = counts,
                fullReconciliationDue = due[list.id] == true,
            )
        }

        // ⚠️ Only the runs that were *due*. A server without `sync-collection`
        // reconciles in full every single time, so recording each one kept the
        // clock permanently fresh and `fullReconciliationDue` permanently false
        // — which costs nothing on that path, since the cursor is null anyway,
        // but silently disables everything else hung off the periodic mark. The
        // download-side quarantine probe is the one that matters: for exactly
        // those servers it would never have fired.
        cadence.record(
            reports.filter { it.reconciledInFull && it.failure == null && due[it.listId] == true }
                .associate { it.listId to now },
        )

        fun mine(key: String) = key.substringBefore('|').toLongOrNull() in listIds
        quarantine.merge(
            updates = counts.filterKeys(::mine),
            cleared = before.keys.filter(::mine).filterNot { it in counts }.toSet(),
        )
        database.accounts().recordSync(
            accountId = account.id,
            at = now,
            error = reports.mapNotNull { it.failure }.firstOrNull(),
        )
        return reports
    }

    private companion object {
        /**
         * Matches what the account-add flow signed in with, so Nextcloud's
         * Settings → Security → Devices & sessions keeps naming the app password
         * after the app rather than after OkHttp.
         */
        const val USER_AGENT = "Agendula (Android)"
    }
}
