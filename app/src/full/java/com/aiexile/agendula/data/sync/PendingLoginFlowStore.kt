package com.aiexile.agendula.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aiexile.agendula.data.di.SyncStateDataStore
import com.aiexile.caldav.NextcloudLoginFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a started login flow is written down, so it can outlive this process.
 *
 * A seam for the same reason [AccountCreator] and [CalDavGateway] are: the flow
 * that decides *when* a one-shot password stops being ours has to be testable
 * without a DataStore.
 */
interface LoginFlowRecord {

    /** Called **before** the browser is handed the URL. */
    suspend fun remember(flow: NextcloudLoginFlow.Flow)

    /** The flow is over, however it ended. */
    suspend fun forget()
}

/**
 * The Nextcloud login flow that is currently out at a browser.
 *
 * ⚠️ [NextcloudLoginFlow.Flow]'s own doc says to persist it **before** launching
 * the browser, because the flow outlives our process — and it did not. The
 * browser is a separate task, so process death while the user is approving is
 * ordinary rather than exotic, and it stranded a one-shot app password that
 * nothing could then collect *or* revoke: the flow's poll token was the only way
 * back to it, and it lived in a ViewModel field.
 *
 * The token is not a credential. It authorises exactly one poll of one flow the
 * user is in the middle of approving, and it is useless past the twenty-minute
 * window — so it belongs in the sync-state store rather than the Keystore.
 *
 * ⚠️ What this does **not** cover is the window *after* approval, where the
 * password itself lives only in memory. That needs the wizard's own state to
 * survive, which is a different piece of work.
 */
@Singleton
class PendingLoginFlowStore @Inject constructor(
    @SyncStateDataStore private val dataStore: DataStore<Preferences>,
    private val gateway: CalDavGateway,
) : LoginFlowRecord {

    private val lock = Mutex()
    private var reclaimed = false

    /** Records [flow] so a process that dies mid-approval can still finish with it. */
    override suspend fun remember(flow: NextcloudLoginFlow.Flow) {
        dataStore.edit { prefs ->
            prefs[LOGIN_URL] = flow.loginUrl.toString()
            prefs[POLL_ENDPOINT] = flow.pollEndpoint.toString()
            prefs[POLL_TOKEN] = flow.pollToken
            prefs[DEADLINE] = flow.deadlineEpochSeconds
        }
    }

    /** The flow is finished, one way or another. */
    override suspend fun forget() {
        dataStore.edit { prefs ->
            prefs.remove(LOGIN_URL)
            prefs.remove(POLL_ENDPOINT)
            prefs.remove(POLL_TOKEN)
            prefs.remove(DEADLINE)
        }
    }

    /**
     * Collects and hands back a password nobody is left to own.
     *
     * Revoked rather than used: the address the user typed, the collections they
     * ticked and the account name are all gone with the process, so there is
     * nothing to finish. What is left is a live app password in the user's
     * device list, under the same name as every other attempt — which is exactly
     * what they cannot tell apart, and so dare not prune.
     *
     * ⚠️ **Once per process.** This activity is recreated on every rotation,
     * theme switch and locale change, and a second run against a flow the *live*
     * wizard is still polling would consume its one-shot 200 and revoke the
     * password it was about to be handed. A flow remembered after this has run
     * belongs to a wizard that is alive to finish it.
     */
    suspend fun reclaim() {
        lock.withLock {
            if (reclaimed) return
            reclaimed = true
        }
        val flow = pending() ?: return
        when (val result = gateway.pollLoginFlow(flow)) {
            is NextcloudLoginFlow.PollResult.Approved -> {
                // Cleared first: a revocation that fails must not leave a token
                // that would be polled again, and the 200 is already spent.
                forget()
                gateway.revokeIssuedAppPassword(
                    CalDavGateway.Credentials(
                        username = result.credentials.loginName,
                        password = result.credentials.appPassword,
                        origin = result.credentials.server,
                    ),
                )
            }

            is NextcloudLoginFlow.PollResult.Expired -> forget()

            // Still inside the window, or the server had a moment. Either way
            // the token is still worth something, so it is left for the next
            // open; a poll past the deadline answers Expired and clears it.
            NextcloudLoginFlow.PollResult.Pending,
            is NextcloudLoginFlow.PollResult.Failed,
            -> Unit
        }
    }

    private suspend fun pending(): NextcloudLoginFlow.Flow? {
        val prefs = dataStore.data.first()
        val endpoint = prefs[POLL_ENDPOINT]?.toHttpUrlOrNull() ?: return null
        val token = prefs[POLL_TOKEN] ?: return null
        val deadline = prefs[DEADLINE] ?: return null
        return NextcloudLoginFlow.Flow(
            loginUrl = prefs[LOGIN_URL]?.toHttpUrlOrNull() ?: endpoint,
            pollEndpoint = endpoint,
            pollToken = token,
            deadlineEpochSeconds = deadline,
        )
    }

    private companion object {
        val LOGIN_URL = stringPreferencesKey("login_flow_url")
        val POLL_ENDPOINT = stringPreferencesKey("login_flow_poll_endpoint")
        val POLL_TOKEN = stringPreferencesKey("login_flow_poll_token")
        val DEADLINE = longPreferencesKey("login_flow_deadline")
    }
}
