package de.jeanlucmakiola.agendula.data.sync.push

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jeanlucmakiola.agendula.data.di.IoDispatcher
import de.jeanlucmakiola.agendula.data.sync.AccountStateStore
import de.jeanlucmakiola.agendula.data.sync.CredentialStore
import de.jeanlucmakiola.agendula.data.sync.SyncAvailability
import de.jeanlucmakiola.agendula.data.sync.SyncReport
import de.jeanlucmakiola.agendula.data.tasks.room.AccountEntity
import de.jeanlucmakiola.agendula.data.tasks.room.TasksDatabase
import de.jeanlucmakiola.caldav.CalDavHttp
import de.jeanlucmakiola.caldav.WebDavPush
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * Keeps WebDAV-Push subscriptions in step with the synced lists, modelled on
 * DAVx5's `PushRegistrationManager`. [update] registers each account with the
 * distributor; [onNewEndpoint] subscribes the endpoint it answers with. The
 * daily [PushRenewalWorker] re-registers, which is what renews subscriptions.
 */
@Singleton
class PushRegistrar @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: TasksDatabase,
    private val store: PushStore,
    private val distributors: PushDistributors,
    private val credentials: CredentialStore,
    private val accountState: AccountStateStore,
    private val availability: SyncAvailability,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** One subscribe/unsubscribe pass at a time, across every entry point. */
    private val mutex = Mutex()

    /** Records what a sync read about push support, and registers if that changed. */
    suspend fun onSynced(account: AccountEntity, reports: List<SyncReport>) {
        val read = reports.filter { it.collectionRead }.associate { it.listId to it.pushSupport }
        if (store.recordSupport(read)) update(account.id)
    }

    suspend fun updateAll() = mutex.withLock {
        withContext(io) {
            database.accounts().all().forEach { updateAccount(it) }
            scheduleRenewal()
        }
    }

    suspend fun update(accountId: Long) = mutex.withLock {
        withContext(io) {
            database.accounts().account(accountId)?.let { updateAccount(it) }
            scheduleRenewal()
        }
    }

    /** Our subscription per collection URL, for `Push-Dont-Notify` on the account's writes. */
    suspend fun subscriptionsByHref(accountId: Long): Map<String, HttpUrl> = withContext(io) {
        val pushes = store.all()
        database.taskLists().syncedForAccount(accountId).mapNotNull { list ->
            // Normalised the way SyncEngine spells the URL it looks up.
            val href = list.href?.toHttpUrlOrNull()?.toString() ?: return@mapNotNull null
            val subscription = pushes[list.id]?.subscription?.toHttpUrlOrNull() ?: return@mapNotNull null
            href to subscription
        }.toMap()
    }

    /** The distributor's endpoint for [accountId] is ready: subscribe the account's lists to it. */
    suspend fun onNewEndpoint(accountId: Long, endpoint: PushEndpoint) = mutex.withLock {
        withContext(io) {
            val account = database.accounts().account(accountId) ?: return@withContext
            if (!pushAllowed(account)) return@withContext
            val client = clientFor(account) ?: return@withContext

            val pushes = store.all()
            val lists = database.taskLists().syncedForAccount(account.id)
            val wanted = lists.filter { it.href != null && pushes[it.id]?.support != null }
            val renewBefore = Clock.System.now() + RENEW_MARGIN

            for (list in wanted) {
                val push = pushes.getValue(list.id)
                val current = push.subscription != null &&
                    push.endpoint == endpoint.url &&
                    push.expires?.let { it > renewBefore } == true
                if (current) continue

                // A changed endpoint means a new subscription, not an update.
                if (push.endpoint != null && push.endpoint != endpoint.url) {
                    push.subscription?.toHttpUrlOrNull()?.let { WebDavPush.unregister(client, it) }
                }
                val collection = list.href!!.toHttpUrlOrNull() ?: continue
                when (
                    val outcome = WebDavPush.register(
                        client = client,
                        collection = collection,
                        endpoint = endpoint.url,
                        publicKey = endpoint.pubKeySet?.pubKey,
                        authSecret = endpoint.pubKeySet?.auth,
                        expires = Clock.System.now() + REQUESTED_LIFETIME,
                    )
                ) {
                    is WebDavPush.Registration.Registered -> store.recordSubscription(
                        listId = list.id,
                        subscription = outcome.url?.toString(),
                        endpoint = endpoint.url,
                        expires = outcome.expires,
                    )
                    is WebDavPush.Registration.Refused -> {
                        Log.w(TAG, "push refused for ${list.id}: HTTP ${outcome.code}")
                        store.clearSubscription(list.id)
                        // Stop the account as a sync would; Nextcloud throttles per IP.
                        if (outcome.code == UNAUTHORIZED) {
                            accountState.setNeedsSignIn(account.id, true)
                            return@withContext
                        }
                    }
                    // Retried by the next renewal.
                    is WebDavPush.Registration.Failed -> Log.w(TAG, "push registration failed for ${list.id}: ${outcome.reason}")
                }
            }

            // A list that lost push support still holds a subscription nobody wants.
            val wantedIds = wanted.map { it.id }.toSet()
            val stale = lists.filter { it.id !in wantedIds && pushes[it.id]?.subscription != null }
            unsubscribe(client, stale.map { it.id }, pushes)
        }
    }

    /** The distributor dropped [accountId]'s registration: its subscriptions lead nowhere. */
    suspend fun onUnregistered(accountId: Long) = mutex.withLock {
        withContext(io) {
            database.accounts().account(accountId)?.let { unsubscribeAll(it) }
        }
    }

    /** Before an account is removed, while its credential still works. */
    suspend fun forgetAccount(accountId: Long) = mutex.withLock {
        withContext(io) {
            val account = database.accounts().account(accountId) ?: return@withContext
            unsubscribeAll(account)
            distributors.unregister(accountId.toString())
            store.forget(database.taskLists().syncedForAccount(accountId).map { it.id }.toSet())
        }
    }

    /** Before lists stop syncing with [accountId]. */
    suspend fun forgetLists(accountId: Long, listIds: Set<Long>) = mutex.withLock {
        withContext(io) {
            if (listIds.isEmpty()) return@withContext
            val account = database.accounts().account(accountId)
            val client = account?.let { clientFor(it) }
            val pushes = store.all()
            if (client != null) unsubscribe(client, listIds.toList(), pushes)
            store.forget(listIds)
        }
    }

    private suspend fun updateAccount(account: AccountEntity) {
        val instance = account.id.toString()
        val distributor = if (pushAllowed(account)) distributors.toUse() else null
        val pushes = store.all()
        val capable = database.taskLists().syncedForAccount(account.id)
            .mapNotNull { pushes[it.id]?.support }

        if (distributor == null || capable.isEmpty()) {
            // Not unregistered: the connector would forget the user's distributor.
            unsubscribeAll(account)
            return
        }

        val vapid = capable.firstNotNullOfOrNull { it.vapidPublicKey }
        try {
            UnifiedPush.register(context, instance, account.displayName, vapid)
        } catch (_: UnifiedPush.VapidNotValidException) {
            Log.w(TAG, "server VAPID key for ${account.id} is not usable")
            UnifiedPush.register(context, instance, account.displayName, null)
        }
    }

    private suspend fun unsubscribeAll(account: AccountEntity) {
        val pushes = store.all()
        val held = database.taskLists().syncedForAccount(account.id)
            .filter { pushes[it.id]?.subscription != null }
            .map { it.id }
        if (held.isEmpty()) return
        val client = clientFor(account)
        if (client != null) {
            unsubscribe(client, held, pushes)
        } else {
            // Without a credential they are left to expire.
            held.forEach { store.clearSubscription(it) }
        }
    }

    /** Tells the server, then forgets locally; after the first failure only forgets. */
    private suspend fun unsubscribe(client: OkHttpClient, listIds: List<Long>, pushes: Map<Long, PushStore.ListPush>) {
        var reachable = true
        listIds.forEach { listId ->
            val subscription = pushes[listId]?.subscription?.toHttpUrlOrNull()
            if (reachable && subscription != null) reachable = WebDavPush.unregister(client, subscription)
            store.clearSubscription(listId)
        }
    }

    private suspend fun pushAllowed(account: AccountEntity): Boolean =
        availability.accountsUsable() && !accountState.needsSignIn(account.id)

    /** Null for an account waiting on sign-in, as in `SyncEngine.sync`. */
    private suspend fun clientFor(account: AccountEntity): OkHttpClient? {
        if (accountState.needsSignIn(account.id)) return null
        val username = account.username ?: return null
        val origin = account.principalUrl?.toHttpUrlOrNull() ?: return null
        val password = (credentials.get(account.id) as? CredentialStore.Secret.Present)?.value ?: return null
        return CalDavHttp.authenticated(USER_AGENT, username, password, origin)
            .newBuilder()
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /** Only while some list could be pushed; nothing to renew otherwise. */
    private suspend fun scheduleRenewal() {
        val work = WorkManager.getInstance(context)
        val needed = distributors.toUse() != null && store.all().values.any { it.support != null }
        if (!needed) {
            work.cancelUniqueWork(PushRenewalWorker.NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<PushRenewalWorker>(RENEWAL_INTERVAL_DAYS, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        work.enqueueUniquePeriodicWork(PushRenewalWorker.NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private companion object {
        const val TAG = "PushRegistrar"
        const val USER_AGENT = "Agendula (Android)"
        const val UNAUTHORIZED = 401

        /** A registration is one small request; removal waits on it. */
        const val CALL_TIMEOUT_SECONDS = 15L

        /** What we ask for; the draft recommends at least three days. */
        val REQUESTED_LIFETIME = 3.days

        const val RENEWAL_INTERVAL_DAYS = 1L

        /** Two renewal intervals, since periodic work is not punctual. */
        val RENEW_MARGIN = (2 * RENEWAL_INTERVAL_DAYS).days
    }
}
