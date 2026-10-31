package com.aiexile.agendula.data.sync

import com.aiexile.agendula.data.di.IoDispatcher
import com.aiexile.agendula.data.sync.push.PushRegistrar
import com.aiexile.agendula.data.prefs.SettingsPrefs
import com.aiexile.agendula.data.tasks.room.AccountEntity
import com.aiexile.agendula.data.tasks.room.TaskListEntity
import com.aiexile.agendula.data.tasks.room.TasksDatabase
import com.aiexile.caldav.CalDavDiscovery
import com.aiexile.caldav.TaskCollection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one thing the sign-in flow needs from [AccountRepository].
 *
 * A seam, so the flow's state machine can be tested without a database, a
 * Keystore or an `AccountManager` — the three things that make the rest of this
 * class Android-only.
 */
interface AccountCreator {
    suspend fun create(
        displayName: String,
        username: String,
        appPassword: String,
        found: CalDavDiscovery.Outcome.Found,
        selected: Set<TaskCollection>,
        /** An existing account to sign in again, instead of creating one. */
        reauthenticating: Long? = null,
    ): AccountRepository.Outcome
}

/**
 * Turns a finished sign-in into an account that exists in all three places it
 * has to: the Room `accounts` row, the encrypted credential, and the
 * system-visible `AccountManager` entry.
 *
 * The order matters. The Room row comes first because its id keys the
 * credential, and the system account comes last because it is the one thing a
 * user can see — an entry in Settings for an account whose credential failed to
 * store would be a sync that silently never works.
 */
@Singleton
class AccountRepository @Inject constructor(
    private val database: TasksDatabase,
    private val credentials: CredentialStore,
    private val accounts: CalDavAccounts,
    private val syncTrigger: SyncTrigger,
    private val cadence: SyncCadenceStore,
    private val accountState: AccountStateStore,
    private val quarantine: QuarantineStore,
    private val notices: SyncNoticeStore,
    private val collectionSupport: CollectionSupportStore,
    private val gateway: CalDavGateway,
    private val availability: SyncAvailability,
    private val settings: SettingsPrefs,
    private val push: PushRegistrar,
    @IoDispatcher private val io: CoroutineDispatcher,
) : AccountCreator {

    private suspend fun syncInterval(): Int = settings.settings.first().syncIntervalMinutes

    /** What went wrong, in words a user can act on. */
    sealed interface Outcome {
        data class Created(val accountId: Long) : Outcome
        data object AlreadyExists : Outcome

        /** External storage mode is on, and nothing would ever show what this account syncs. */
        data object ExternalStorage : Outcome
        data class CredentialFailed(val cause: Cause, val detail: String = "") : Outcome

        /**
         * Why an account could not be saved, in a form the UI can translate.
         *
         * ⚠️ The UI renders *this*, never [CredentialFailed.detail] — which is
         * a `Throwable.message` and so an untranslated, often unreadable string.
         */
        enum class Cause {
            /** The Keystore refused to hold the password. */
            KEYSTORE_REFUSED,

            /** Anything else that stopped the write. */
            NOT_SAVED,
        }
    }

    suspend fun all(): List<AccountEntity> = withContext(io) { database.accounts().all() }

    /** The accounts, observed, so a sync landing updates a screen that is open. */
    fun observeAll(): Flow<List<AccountEntity>> = database.accounts().observeAll()

    /** Every account's synced lists. */
    fun observeSyncedLists(): Flow<List<TaskListEntity>> = database.taskLists().observeSynced()

    /**
     * Creates an account and the task lists the user chose.
     *
     * [selected] is a subset of what discovery found; a collection the user did
     * not tick is simply not created, and can be added later without touching
     * anything else — `task_lists.account_id` is a nullable FK, so attaching is
     * an `UPDATE`.
     */
    override suspend fun create(
        displayName: String,
        username: String,
        appPassword: String,
        found: CalDavDiscovery.Outcome.Found,
        selected: Set<TaskCollection>,
        reauthenticating: Long?,
    ): Outcome = withContext(io) {
        if (!availability.accountsUsable()) return@withContext Outcome.ExternalStorage
        // "Sign in again" names its account, so a login name the server spells
        // differently from last time still lands on it rather than beside it.
        // Only on the same server: anything else is a different account.
        val target = reauthenticating?.let { database.accounts().account(it) }
        if (target != null && sameServer(target, found)) {
            return@withContext reauthenticate(target, target.displayName, username, appPassword, found, selected)
        }
        // Both stores, not just one. There is no unique index on
        // accounts.display_name and nothing prunes Room when the system account
        // disappears, so "removed from system Settings, re-added here" would
        // otherwise leave a second Room row and a duplicate of every list.
        val systemAccount = accounts.find(displayName)
        val existing = database.accounts().all().firstOrNull { it.displayName == displayName }

        // ⚠️ Re-authentication, not a duplicate. An account stopped by a 401 has
        // no other way back: `create` is the only path that writes a credential,
        // and refusing it here left the user with "that account is already set
        // up" and no option but to remove the account — discarding the choice to
        // keep its lists attached. Narrow on purpose: a *healthy* account of the
        // same name is still a duplicate, so this can never silently overwrite a
        // working credential.
        if (existing != null && accountState.needsSignIn(existing.id)) {
            return@withContext reauthenticate(existing, displayName, username, appPassword, found, selected)
        }

        if (existing != null) return@withContext Outcome.AlreadyExists

        // ⚠️ In the system, not in Room: an orphan, not a duplicate. `remove()`
        // ignores whether the AccountManager entry actually went (and `find`
        // returns null while the device is locked), so this state is reachable —
        // and refusing here left the user with an account that cannot be removed
        // from inside the app at all, since the accounts screen is driven off
        // Room. Clearing it up is kinder than refusing for ever.
        systemAccount?.let { accounts.remove(it) }

        // ⚠️ Uncancellable as a whole. The row, its lists, the credential and
        // the system entry are four stores that cannot share a transaction, and
        // the caller is a viewModelScope tied to the Settings destination — a
        // back gesture during "Adding the account" would otherwise leave the row
        // and its lists with no credential and no system account: the rollback
        // never runs, `needsSignIn` is false so re-auth will not fire, and every
        // retry answers AlreadyExists. `remove()` documents the same hazard.
        withContext(NonCancellable) {
            val inserted = mutableListOf<Long>()
            val accountId = database.runInTransaction<Long> {
                val id = database.accounts().insert(
                    AccountEntity(
                        displayName = displayName,
                        // Persist where a 301/308 actually put us — dav4jvm#209 exists
                        // precisely so this is knowable, and re-following the redirect
                        // on every sync is what not persisting it costs.
                        principalUrl = (found.movedTo ?: found.principal).toString(),
                        // The principal's own home set. `resolve("./")` on a collection
                        // URL is a no-op — CalDAV hrefs already end in "/" — so the
                        // old version stored the first collection's own URL, and that
                        // collection may not even be from the account's own home set.
                        homeSetUrl = found.homeSets.firstOrNull()?.toString(),
                        username = username,
                    ),
                )
                inserted += attach(id, selected)
                id
            }

            if (!credentials.put(accountId, appPassword)) {
                // Never leave a half-made account behind: without a credential it
                // would sit in Settings failing to sync with nothing to explain it.
                rollback(accountId, inserted)
                return@withContext Outcome.CredentialFailed(
                    Outcome.Cause.KEYSTORE_REFUSED,
                    "the device keystore would not store the password",
                )
            }

            if (!accounts.add(displayName, accountId)) {
                credentials.clear(accountId)
                rollback(accountId, inserted)
                return@withContext Outcome.AlreadyExists
            }

            // On the schedule from the moment it exists, and syncing immediately —
            // an account that shows up empty until the first periodic window looks
            // broken.
            syncTrigger.schedule(displayName, syncInterval())
            syncTrigger.enqueue(displayName)

            Outcome.Created(accountId)
        }
    }

    /**
     * Points [selected] at [accountId], re-attaching what a previous removal
     * left behind rather than inserting a second copy.
     *
     * ⚠️ `remove()` leaves the lists as device-only ones on purpose, so a plain
     * insert gives the user their old "Personal" full of tasks *and* a freshly
     * synced "Personal" holding the same tasks from the server. The row keeps
     * its name, colour, ordering and tasks — the user's, not the server's — and
     * loses only its cursor, because the account it was reconciled against is
     * gone.
     *
     * ⚠️ On a re-authentication this also has to see the account's *own* lists,
     * not just detached ones — the re-auth path walks the same picker, so a
     * lookup that missed them would insert a second copy of every list the
     * account already syncs. On a create the id was minted a statement earlier,
     * so nothing is attached to it yet and only detached rows can match.
     *
     * @return the ids of the lists this call *created*, which are the only ones
     *   a rollback may delete.
     */
    private fun attach(accountId: Long, selected: Set<TaskCollection>): List<Long> {
        val known = database.taskLists().attachable(accountId).associateBy { it.href }
        val inserted = mutableListOf<Long>()
        selected.forEach { collection ->
            val href = collection.url.toString()
            val current = known[href]
            if (current == null) {
                inserted += database.taskLists().insert(
                    TaskListEntity(
                        name = collection.displayName ?: collection.url.pathSegments
                            .lastOrNull { it.isNotEmpty() }
                            .orEmpty(),
                        color = collection.color ?: DEFAULT_LIST_COLOR,
                        accountId = accountId,
                        isReadOnly = collection.readOnly,
                        href = href,
                    ),
                )
            } else {
                // Changing hands, as opposed to re-authenticating the account
                // that already owns it: a cursor from the previous owner says
                // nothing about this one, while the current owner's is still
                // good and throwing it away costs a full reconciliation.
                val changingHands = current.accountId != accountId
                database.taskLists().update(
                    current.copy(
                        accountId = accountId,
                        isReadOnly = collection.readOnly,
                        syncToken = current.syncToken.takeUnless { changingHands },
                        ctag = current.ctag.takeUnless { changingHands },
                    ),
                )
            }
        }
        return inserted
    }

    /** What the server offers an existing account, beside what it already syncs. */
    sealed interface Collections {
        data class Found(
            val collections: List<TaskCollection>,
            /** Hrefs of the collections this account already syncs. */
            val attached: Set<String>,
        ) : Collections

        /** The account is stopped, or has no credential we can use. */
        data object NeedsSignIn : Collections

        data class Failed(val cause: CalDavDiscovery.Outcome.Cause?) : Collections
    }

    /**
     * Re-runs discovery for [accountId], so lists added on the server after
     * setup can be picked up and ones that are synced can be dropped.
     */
    suspend fun collections(accountId: Long): Collections = withContext(io) {
        if (accountState.needsSignIn(accountId)) return@withContext Collections.NeedsSignIn
        val account = database.accounts().account(accountId)
            ?: return@withContext Collections.Failed(null)
        val username = account.username ?: return@withContext Collections.NeedsSignIn
        val principal = account.principalUrl?.toHttpUrlOrNull()
            ?: return@withContext Collections.Failed(null)
        val password = (credentials.get(accountId) as? CredentialStore.Secret.Present)?.value
            ?: return@withContext Collections.NeedsSignIn
        val attached = database.taskLists().syncedForAccount(accountId)
            .mapNotNull { it.href }
            .toSet()
        when (
            val outcome = gateway.discover(
                principal.toString(),
                CalDavGateway.Credentials(username, password, principal),
            )
        ) {
            is CalDavDiscovery.Outcome.Found -> Collections.Found(outcome.collections, attached)
            is CalDavDiscovery.Outcome.NeedsAuthentication,
            CalDavDiscovery.Outcome.Unauthenticated,
            -> Collections.NeedsSignIn
            is CalDavDiscovery.Outcome.NotCalDav -> Collections.Failed(outcome.cause)
            is CalDavDiscovery.Outcome.Failed -> Collections.Failed(outcome.cause)
        }
    }

    /**
     * Makes [selected] the synced subset of [offered] for [accountId].
     *
     * Newly ticked collections are attached and synced. Unticked ones that were
     * synced go the way an account removal takes its lists: detached as
     * device-only lists when [keepUnticked], deleted from this device otherwise.
     * Nothing is deleted on the server either way.
     */
    suspend fun setSyncedCollections(
        accountId: Long,
        offered: List<TaskCollection>,
        selected: Set<HttpUrl>,
        keepUnticked: Boolean,
    ) = withContext(io) {
        val account = database.accounts().account(accountId) ?: return@withContext
        withContext(NonCancellable) {
            val change = collectionChange(
                offered,
                selected,
                database.taskLists().syncedForAccount(accountId),
            )
            database.runInTransaction {
                attach(accountId, change.attach)
                change.drop.forEach { list ->
                    if (keepUnticked) {
                        database.taskLists().setAccount(list.id, null)
                    } else {
                        database.taskLists().delete(list.id)
                    }
                }
            }
            val dropped = change.drop.map { it.id }.toSet()
            runCatching { push.forgetLists(accountId, dropped) }
            cadence.forget(dropped)
            quarantine.forget(dropped)
            change.drop.forEach { notices.forgetList(accountId, it.name) }
            if (change.attach.isNotEmpty()) syncTrigger.enqueue(account.displayName, expedited = true)
        }
    }

    /**
     * Gives a quarantined resource another go: its failure count is cleared and
     * the account synced. If it fails again it is counted afresh, and the notice
     * comes back once it reaches the threshold again.
     */
    suspend fun retryQuarantined(accountId: Long, key: String) = withContext(io) {
        val account = database.accounts().account(accountId) ?: return@withContext
        val listIds = database.taskLists().syncedForAccount(accountId).map { it.id }.toSet()
        quarantine.release(listIds, key)
        notices.forgetQuarantined(accountId, key)
        syncTrigger.enqueue(account.displayName, expedited = true)
    }

    /**
     * Replaces the credential of an account the server had stopped accepting.
     *
     * The tasks stay exactly as they are — the password was the only thing that
     * went stale. Everything the user was asked for on the way here is applied
     * all the same:
     *
     * ⚠️ This branch used to take [appPassword] and drop [found] and [selected]
     * on the floor. The user walked the whole add flow, ticked collections, and
     * was shown Done — while no newly-ticked list was created, no unticked one
     * was detached, and a moved principal or a corrected username was discarded,
     * so a relocated account could never be repaired. It also never called
     * `accounts.add`, so an account the user had deleted in Android Settings —
     * nothing listens for `LOGIN_ACCOUNTS_CHANGED` — stayed absent from Settings
     * for ever while syncing happily via WorkManager, and every later add
     * answered AlreadyExists.
     */
    private suspend fun reauthenticate(
        existing: AccountEntity,
        displayName: String,
        username: String,
        appPassword: String,
        found: CalDavDiscovery.Outcome.Found,
        selected: Set<TaskCollection>,
    ): Outcome {
        val accountId = existing.id
        if (!credentials.put(accountId, appPassword)) {
            return Outcome.CredentialFailed(
                Outcome.Cause.KEYSTORE_REFUSED,
                "the device keystore would not store the password",
            )
        }
        withContext(NonCancellable) {
            database.runInTransaction {
                database.accounts().update(
                    existing.copy(
                        // Where discovery just found it, which is the only way a
                        // principal that has moved can ever be corrected.
                        principalUrl = (found.movedTo ?: found.principal).toString(),
                        homeSetUrl = found.homeSets.firstOrNull()?.toString()
                            ?: existing.homeSetUrl,
                        username = username,
                    ),
                )
                attach(accountId, selected)
            }
            // ⚠️ Ticked lists are attached; unticked ones are left alone. The
            // picker pre-ticks everything *writable*, not everything already
            // attached, so detaching what is unticked would silently stop
            // syncing a read-only share the account has synced for months —
            // over a default the user never chose. Detaching belongs here the
            // day the picker knows what this account already holds.
            // The Room row is the account as far as this app is concerned, so a
            // missing system entry is re-registered rather than left behind.
            if (accounts.find(displayName) == null) accounts.add(displayName, accountId)
            accountState.setNeedsSignIn(accountId, false)
            database.accounts().recordSync(accountId, at = null, error = null)
            syncTrigger.schedule(displayName, syncInterval())
            syncTrigger.enqueue(displayName)
        }
        return Outcome.Created(accountId)
    }

    /**
     * Undoes a half-made account.
     *
     * ⚠️ The lists this attempt *created* have to go explicitly.
     * `task_lists.account_id` is `ON DELETE SET NULL` — deliberately, so
     * removing a working account never destroys tasks — which means deleting the
     * account row alone would leave a set of empty device-only lists behind.
     *
     * ⚠️ And only those. A list [attach] re-attached was already on the device
     * and holds the user's tasks; `SET NULL` returns it to being device-only,
     * which is exactly where it came from.
     */
    private fun rollback(accountId: Long, inserted: List<Long>) = database.runInTransaction {
        inserted.forEach { database.taskLists().delete(it) }
        database.accounts().delete(accountId)
    }

    /**
     * Puts every existing account back on the periodic schedule.
     *
     * Cheap and idempotent — `KEEP` means an already-scheduled account is left
     * exactly as it is — so calling it on app open costs nothing and repairs the
     * one case WorkManager cannot: a schedule lost to "clear app data" or to a
     * restore onto a device that never ran the account-add flow.
     */
    suspend fun rescheduleAll(intervalChanged: Boolean = false) = withContext(io) {
        val stopped = accountState.needingSignIn()
        database.accounts().all().forEach { account ->
            // ⚠️ A stopped account must not come back on the timer. `KEEP` only
            // keeps work that is unfinished, and CANCELLED counts as finished —
            // so rescheduling would re-enqueue the very request a 401 removed,
            // and the next app open would put a dead app password back on a
            // four-hour loop against a server that throttles by IP.
            //
            // This is also where the cancellation happens at all: the engine
            // cannot cancel from inside the worker it is running in.
            if (account.id in stopped) {
                syncTrigger.cancel(account.displayName)
            } else {
                syncTrigger.schedule(account.displayName, syncInterval(), intervalChanged)
            }
        }
    }

    /** The accounts a caller may sync right now — stopped ones excluded. */
    suspend fun syncable(): List<AccountEntity> = withContext(io) {
        val stopped = accountState.needingSignIn()
        database.accounts().all().filterNot { it.id in stopped }
    }

    /**
     * Removes an account and everything that keys off it.
     *
     * The lists are **not** deleted: `task_lists.account_id` is `ON DELETE SET
     * NULL`, so they become device-only lists. Removing an account is not an
     * instruction to destroy the tasks it held.
     */
    suspend fun remove(accountId: Long, displayName: String, deleteLocalData: Boolean = false) =
        withContext(io) {
            syncTrigger.cancel(displayName)
            // Before the revocation: the subscriptions can only be removed with
            // the credential that is about to stop working.
            withContext(NonCancellable) { runCatching { push.forgetAccount(accountId) } }
            revokeAppPassword(accountId)

            // ⚠️ Uncancellable from here. Everything below is destructive and
            // spread over four stores that cannot share a transaction, and the
            // caller is a viewModelScope tied to the Settings destination — the
            // user taps Remove, the screen slides away, and a couple of back
            // gestures kill the scope mid-sequence. Only the DataStore writes can
            // observe cancellation (every Room DAO here is blocking), so the
            // realistic landing point is `cadence.forget`: the app password is
            // already revoked server-side while the row survives holding it, and
            // the account reads "sign in again" for a credential we ourselves
            // invalidated. Land further in and the tasks are gone with the row
            // still there. The tail is three DataStore writes, two deletes and an
            // AccountManager call — bounded and sub-second, so finishing it is
            // strictly better than stopping anywhere inside it.
            withContext(NonCancellable) {
                // The lists survive as device-only lists, so their cursors must
                // not: a re-added account would otherwise inherit a "reconciled
                // recently" that was true of a different account's data.
                val listIds = database.taskLists().syncedForAccount(accountId)
                    .map { it.id }
                    .toSet()
                cadence.forget(listIds)
                // ⚠️ And the quarantine counts, which are keyed the same way and
                // are just as global. A list re-attached to a new account would
                // otherwise inherit them, and a resource already at THRESHOLD is
                // skipped for ever — it never succeeds, so it never clears.
                quarantine.forget(listIds)
                // Play's Account Deletion policy does not apply to us — there is
                // no Agendula account to delete — but "I want it gone from this
                // device too" is a reasonable thing to want, and it is the only
                // way to get the tasks off the device without also uninstalling.
                if (deleteLocalData) database.taskLists().deleteForAccount(accountId)

                accountState.setNeedsSignIn(accountId, false)
                // Keyed by account id, exactly like the flag above, and just as
                // orphaned once the row goes: ids are AUTOINCREMENT so they are
                // never reused, but nothing would ever read or clear these again.
                notices.dismiss(accountId)
                // The same reasoning, for what the server said it would let us
                // create: keyed by an id nothing will ever mention again.
                collectionSupport.forget(accountId)
                credentials.clear(accountId)
                database.accounts().delete(accountId)
                accounts.find(displayName)?.let { accounts.remove(it) }
            }
        }

    /**
     * Best effort, and before the credential is cleared — it is the credential.
     *
     * A failure here is never allowed to stop the removal: the user asked for the
     * account to go, and a server that is unreachable, or was never a Nextcloud,
     * is not a reason to keep it.
     */
    private suspend fun revokeAppPassword(accountId: Long) {
        val account = database.accounts().account(accountId) ?: return
        val username = account.username ?: return
        val origin = account.principalUrl?.toHttpUrlOrNull() ?: return
        val password = (credentials.get(accountId) as? CredentialStore.Secret.Present)?.value
            ?: return
        // ⚠️ The budget lives on the request itself, in AppPassword.revoke.
        // Wrapping this in withTimeoutOrNull only *looked* bounded: the call
        // parks on a socket read that no cancellation can break, and withContext
        // returns when its block does, so the deadline passed and we waited
        // anyway — minutes, on a multi-homed host that stalls.
        try {
            gateway.revokeAppPassword(
                CalDavGateway.Credentials(username, password, origin),
            )
        } catch (_: CancellationException) {
            // Deliberately swallowed. If the caller went away mid-revoke we still
            // want the removal to finish rather than stop half-done; the tail
            // below runs uncancellable for the same reason.
        }
    }

    /** What [setSyncedCollections] has to do: collections to attach, lists to let go. */
    internal data class CollectionChange(
        val attach: Set<TaskCollection>,
        val drop: List<TaskListEntity>,
    )

    private fun sameServer(account: AccountEntity, found: CalDavDiscovery.Outcome.Found): Boolean {
        val stored = account.principalUrl?.toHttpUrlOrNull() ?: return true
        return stored.host.equals((found.movedTo ?: found.principal).host, ignoreCase = true)
    }

    internal companion object {
        /** M3 primary-ish blue; the user recolours a list from its own screen. */
        const val DEFAULT_LIST_COLOR = 0xFF4C6FFF.toInt()

        /**
         * Only collections the server still offers are judged. A synced list
         * missing from [offered] — a share revoked a minute ago, a flaky listing —
         * is not the user unticking it, and is left for sync to sort out.
         */
        fun collectionChange(
            offered: List<TaskCollection>,
            selected: Set<HttpUrl>,
            synced: List<TaskListEntity>,
        ): CollectionChange {
            val syncedHrefs = synced.mapNotNull { it.href }.toSet()
            val offeredHrefs = offered.map { it.url.toString() }.toSet()
            val selectedHrefs = selected.map { it.toString() }.toSet()
            return CollectionChange(
                attach = offered.filter {
                    it.url in selected && it.url.toString() !in syncedHrefs
                }.toSet(),
                drop = synced.filter { it.href in offeredHrefs && it.href !in selectedHrefs },
            )
        }
    }
}
