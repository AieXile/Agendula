package com.aiexile.agendula.data.sync

import com.aiexile.agendula.data.di.IoDispatcher
import com.aiexile.agendula.data.sync.RemoteLists.Outcome
import com.aiexile.agendula.data.sync.push.PushStore
import com.aiexile.agendula.data.tasks.ProviderResolver
import com.aiexile.agendula.data.tasks.StorageMode
import com.aiexile.agendula.data.tasks.room.AccountEntity
import com.aiexile.agendula.data.tasks.room.TaskListEntity
import com.aiexile.agendula.data.tasks.room.TasksDatabase
import com.aiexile.caldav.CalDavHttp
import com.aiexile.caldav.CollectionAdmin
import com.aiexile.caldav.CollectionOutcome
import com.aiexile.caldav.CollectionSupport
import com.aiexile.caldav.DavCollectionAdmin
import com.aiexile.caldav.ResourceNames
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task lists that live on a server: making them, renaming them, recolouring
 * them and deleting them.
 *
 * ⚠️ Every write here is **server first, Room second**, and that ordering is the
 * whole design. The other way round gives the user a list that exists on their
 * phone and nowhere else, and nothing to tell them so — `task_lists.is_dirty`
 * was already set by a rename and read by nobody, which is precisely that
 * failure with the evidence discarded. A refused write leaves the local row
 * exactly as it was, so what is on screen is what is on the server.
 *
 * Device-only lists are not this class's business: they have no href, no
 * account and nothing to ask permission of. [com.aiexile.agendula.data.tasks.TasksRepository]
 * keeps them.
 */
@Singleton
class RemoteListRepository @Inject constructor(
    private val database: TasksDatabase,
    private val credentials: CredentialStore,
    private val support: CollectionSupportStore,
    private val syncTrigger: SyncTrigger,
    private val cadence: SyncCadenceStore,
    private val notices: SyncNoticeStore,
    private val quarantine: QuarantineStore,
    private val accountState: AccountStateStore,
    private val resolver: ProviderResolver,
    private val push: PushStore,
    @IoDispatcher private val io: CoroutineDispatcher,
) : RemoteLists {

    /**
     * The accounts a new list may be created on, freshest answer first.
     *
     * ⚠️ Re-asked rather than cached for ever. `CollectionSupportStore` holds
     * the last answer so a picker can draw immediately, but a server that gained
     * the capability in an upgrade — or lost it in a config change — must be
     * able to say so, and the only moment that costs nothing is while the user
     * is looking at the picker.
     */
    override suspend fun creatableAccounts(): List<AccountEntity> = withContext(io) {
        // ⚠️ Empty in External mode, whatever the accounts table holds. The
        // lists on screen then come from a third-party provider, so a row
        // inserted into ours would exist, sync, and be visible to nobody.
        if (resolver.mode() != StorageMode.OWN) return@withContext emptyList()
        val stopped = accountState.needingSignIn()
        val candidates = database.accounts().all().filter {
            it.homeSetUrl?.toHttpUrlOrNull() != null && it.id !in stopped
        }
        // ⚠️ Together, not one after another. Each probe is a blocking OPTIONS,
        // so three accounts with one server on a slow link held the "Where" row
        // off the sheet for the sum of all three — with the sheet already drawn.
        coroutineScope {
            candidates.map { account -> async { account to supportFor(account) } }
                .awaitAll()
                .filter { (_, support) -> support.canCreate }
                .map { (account, _) -> account }
        }
    }

    /**
     * Makes a collection on [accountId]'s home set and a row pointing at it.
     *
     * @return the new list's local id, or why there is none.
     */
    override suspend fun create(
        accountId: Long,
        name: String,
        color: Int,
    ): Outcome = withContext(io) {
        // ⚠️ Re-checked here, not only in `creatableAccounts`. The picker's list
        // is a StateFlow that outlives one opening of the sheet, so a mode that
        // flips between the list being built and Save being tapped would
        // otherwise create the collection on the server and file the row in a
        // store External mode never reads.
        if (resolver.mode() != StorageMode.OWN) return@withContext Outcome.NoAccount
        val account = database.accounts().account(accountId) ?: return@withContext Outcome.NoAccount
        val homeSet = account.homeSetUrl?.toHttpUrlOrNull()
            ?: return@withContext Outcome.NoAccount
        val admin = adminFor(account) ?: return@withContext Outcome.NoAccount
        val capabilities = support.refresh(accountId) { admin.support(homeSet) }
        if (!capabilities.canCreate) return@withContext Outcome.Unsupported

        // ⚠️ A second attempt, but only for the one refusal a different name
        // can fix. Nextcloud's trashbin *renames* a deleted collection rather
        // than removing it, so re-creating under a segment used before answers
        // 403 for ever — and "Shopping" is exactly the name someone deletes and
        // remakes. Retrying anything else spends a second authenticated write
        // that will fail the same way, and worse: a 401 is a second hit on the
        // brute-force counter, and a 507 retried reports the wrong code back,
        // since the caller only ever sees the *last* attempt's.
        val first = ResourceNames.forCollection(name)
        var created = admin.create(homeSet, first, name, color, capabilities)
        if (created is CollectionOutcome.Refused && created.code in NAME_REFUSALS) {
            created = admin.create(homeSet, ResourceNames.randomCollection(), name, color, capabilities)
        }

        when (created) {
            is CollectionOutcome.Created -> {
                // ⚠️ Uncancellable. The collection exists on the server from
                // here on, and a cancellation between that and the row would
                // leave one the app has no record of and no way to reach —
                // visible only on the next full account re-add.
                withContext(NonCancellable) {
                    database.taskLists().insert(
                        TaskListEntity(
                            name = name,
                            color = color,
                            accountId = accountId,
                            href = created.url.toString(),
                        ),
                    )
                }
                // The server has it and we do not; a sync is how the two agree
                // on a ctag and a token rather than reconciling in full later.
                syncTrigger.enqueue(account.displayName, expedited = true)
                Outcome.Done
            }

            is CollectionOutcome.Refused -> Outcome.Refused(created.code)
            is CollectionOutcome.Failed -> Outcome.Unreachable
            CollectionOutcome.Unsupported -> Outcome.Unsupported
            CollectionOutcome.Updated -> Outcome.Unexpected
        }
    }

    /**
     * Renames and recolours [listId] on the server, then locally.
     *
     * ⚠️ Refuses a read-only collection rather than discovering it at write
     * time. A share the owner has made read-only answers 403 to a PROPPATCH, and
     * a row that has already been renamed locally by then reads as a rename that
     * worked and then quietly reverted on the next sync.
     */
    override suspend fun rename(listId: Long, name: String, color: Int): Outcome = withContext(io) {
        val list = database.taskLists().entity(listId) ?: return@withContext Outcome.NoAccount
        if (list.isReadOnly) return@withContext Outcome.ReadOnly
        val url = list.href?.toHttpUrlOrNull() ?: return@withContext Outcome.NoAccount
        val account = list.accountId?.let { database.accounts().account(it) }
            ?: return@withContext Outcome.NoAccount
        val admin = adminFor(account) ?: return@withContext Outcome.NoAccount

        when (val outcome = admin.updateProperties(url, displayName = name, color = color)) {
            CollectionOutcome.Updated -> {
                withContext(NonCancellable) {
                    // Read again inside the write: a sync running alongside this
                    // may have refreshed the ACL flag or the cursor, and writing
                    // back the entity we read before the network call would
                    // revert it.
                    val current = database.taskLists().entity(listId) ?: return@withContext
                    database.taskLists().update(
                        // isDirty stays false: the server already has this. The
                        // flag existed for a PROPPATCH that never happened.
                        current.copy(name = name, color = color, isDirty = false),
                    )
                }
                Outcome.Done
            }

            is CollectionOutcome.Refused -> Outcome.Refused(outcome.code)
            is CollectionOutcome.Failed -> Outcome.Unreachable
            is CollectionOutcome.Created, CollectionOutcome.Unsupported -> Outcome.Unexpected
        }
    }

    /**
     * Deletes [listId] on the server, then on the device.
     *
     * ⚠️ The one write where "already gone" is success — [CollectionAdmin.delete]
     * grades 404 and 410 that way — because otherwise a collection someone
     * removed from another client leaves a row here that nothing can get rid of.
     */
    override suspend fun delete(listId: Long): Outcome = withContext(io) {
        val list = database.taskLists().entity(listId) ?: return@withContext Outcome.Done
        if (list.isReadOnly) return@withContext Outcome.ReadOnly
        val url = list.href?.toHttpUrlOrNull() ?: return@withContext Outcome.NoAccount
        val account = list.accountId?.let { database.accounts().account(it) }
            ?: return@withContext Outcome.NoAccount
        val admin = adminFor(account) ?: return@withContext Outcome.NoAccount

        when (val outcome = admin.delete(url)) {
            CollectionOutcome.Updated -> {
                withContext(NonCancellable) {
                    // `tasks.list_id` is ON DELETE CASCADE, so the tasks go with
                    // it — which is what was just done on the server.
                    database.taskLists().delete(listId)
                    // And the per-list state keyed off it, exactly as removing an
                    // account clears its lists': the ids are AUTOINCREMENT so
                    // nothing would ever read these again. The notices go by
                    // *name*, which is how they are keyed — a discarded-edit
                    // notice would otherwise name a list that no longer exists
                    // until the user tapped "Got it".
                    forgetPerListState(listId)
                    list.accountId?.let { notices.forgetList(it, list.name) }
                }
                Outcome.Done
            }

            is CollectionOutcome.Refused -> Outcome.Refused(outcome.code)
            is CollectionOutcome.Failed -> Outcome.Unreachable
            is CollectionOutcome.Created, CollectionOutcome.Unsupported -> Outcome.Unexpected
        }
    }

    private suspend fun supportFor(account: AccountEntity): CollectionSupport {
        val homeSet = account.homeSetUrl?.toHttpUrlOrNull() ?: return CollectionSupport.NONE
        val admin = adminFor(account) ?: return CollectionSupport.NONE
        return support.refresh(account.id) { admin.support(homeSet) }
    }

    /**
     * Null when the account has no credential we can use — a stopped account, or
     * a restore.
     *
     * ⚠️ The stopped check is the same one `SyncEngine.sync` makes before it
     * touches the network, and for the same reason: Nextcloud's brute-force
     * protection throttles and then **429s per source IP**, so spending a
     * request on a credential we already know the server rejects lands on the
     * user's *other* clients. Opening the "new list" sheet must not do that any
     * more than a timer may.
     */
    private suspend fun adminFor(account: AccountEntity): CollectionAdmin? {
        if (accountState.needsSignIn(account.id)) return null
        val username = account.username ?: return null
        val origin = account.principalUrl?.toHttpUrlOrNull() ?: return null
        val password = (credentials.get(account.id) as? CredentialStore.Secret.Present)?.value
            ?: return null
        return DavCollectionAdmin(client(username, password, origin))
    }

    private fun client(username: String, password: String, origin: HttpUrl): OkHttpClient =
        CalDavHttp.authenticated(USER_AGENT, username, password, origin)

    private suspend fun forgetPerListState(listId: Long) {
        val ids = setOf(listId)
        cadence.forget(ids)
        quarantine.forget(ids)
        // The subscription went with the collection on the server.
        push.forget(ids)
    }

    private companion object {
        /** The same agent the sync and the add flow use, so the server names us once. */
        const val USER_AGENT = "Agendula (Android)"

        /**
         * Refusals a different path segment can get past, and only those.
         *
         * 403 is Nextcloud's trashbin still holding the name; 405 is a server
         * answering "already a collection there". Everything else — 401, 409,
         * 423, 507 — means the same thing under any name.
         */
        val NAME_REFUSALS = setOf(403, 405)
    }
}
