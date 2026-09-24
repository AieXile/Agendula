package de.jeanlucmakiola.agendula.data.sync

import de.jeanlucmakiola.agendula.data.di.IoDispatcher
import de.jeanlucmakiola.caldav.AppPassword
import de.jeanlucmakiola.caldav.CalDavDiscovery
import de.jeanlucmakiola.caldav.CalDavHttp
import de.jeanlucmakiola.caldav.DnsJavaResolver
import de.jeanlucmakiola.caldav.NextcloudLoginFlow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The network side of adding an account, behind one interface.
 *
 * It exists so the sign-in state machine can be tested. That machine decides
 * which of five outcomes leads where, when a one-shot app password is spent, and
 * which host a credential is scoped to — all of which are exactly the sort of
 * thing that goes wrong quietly, and none of which should require a server to
 * exercise.
 */
interface CalDavGateway {

    /** Discovery against [target], optionally carrying credentials. */
    suspend fun discover(target: String, credentials: Credentials? = null): CalDavDiscovery.Outcome

    /** Starts Nextcloud Login Flow v2, or returns null if this is not a Nextcloud. */
    suspend fun startLoginFlow(server: HttpUrl): NextcloudLoginFlow.Flow?

    suspend fun pollLoginFlow(flow: NextcloudLoginFlow.Flow): NextcloudLoginFlow.PollResult

    /**
     * Hands an app password back to the server, best effort.
     *
     * Without it, uninstalling never revokes anything — the credential we minted
     * outlives the app in the user's device list.
     */
    suspend fun revokeAppPassword(credentials: Credentials): Boolean

    /**
     * The same, for a password the login flow just minted.
     *
     * ⚠️ Separate because [Credentials.origin] means something different here:
     * the *server root* the flow reported, not a principal URL. Sending it
     * through [revokeAppPassword] would derive the OCS root as though it were a
     * principal, and a subpath install's `https://host/nextcloud/` would collapse
     * to `https://host/` — a DELETE that 404s on every one of them.
     */
    suspend fun revokeIssuedAppPassword(credentials: Credentials): Boolean

    /** Credentials, and the origin whose registrable domain they are scoped to. */
    data class Credentials(val username: String, val password: String, val origin: HttpUrl)
}

@Singleton
class OkHttpCalDavGateway @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
) : CalDavGateway {

    /**
     * Becomes the app password's **name** in Nextcloud's Settings → Security →
     * Devices & sessions. OkHttp's default would show `okhttp/4.12.0`, leaving
     * the user unable to tell what to revoke — which defeats the whole point of
     * using an app password.
     */
    private val userAgent = "Agendula (Android)"

    override suspend fun discover(
        target: String,
        credentials: CalDavGateway.Credentials?,
    ): CalDavDiscovery.Outcome = withContext(io) {
        val client = credentials?.let {
            CalDavHttp.authenticated(userAgent, it.username, it.password, it.origin)
        } ?: CalDavHttp.anonymous(userAgent)
        CalDavDiscovery(client, DnsJavaResolver()).discover(target)
    }

    override suspend fun startLoginFlow(server: HttpUrl): NextcloudLoginFlow.Flow? =
        withContext(io) {
            NextcloudLoginFlow(CalDavHttp.anonymous(userAgent), userAgent)
                .start(server, now())
                .getOrNull()
        }

    /**
     * ⚠️ The budget lives on the request, as it does for the revocation.
     * `execute()` parks on a socket read that no cancellation can break, so the
     * four places that cancel the poll job only stop the *next* request — and
     * the shared client's ceiling is sized for a multiget, not for a two-second
     * poll loop against a server that answered a moment ago.
     */
    override suspend fun pollLoginFlow(flow: NextcloudLoginFlow.Flow): NextcloudLoginFlow.PollResult =
        withContext(io) {
            val client = CalDavHttp.anonymous(userAgent).newBuilder()
                .callTimeout(POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
            NextcloudLoginFlow(client, userAgent).poll(flow, now())
        }

    override suspend fun revokeAppPassword(
        credentials: CalDavGateway.Credentials,
    ): Boolean = withContext(io) {
        val client = CalDavHttp.authenticated(
            userAgent, credentials.username, credentials.password, credentials.origin,
        )
        AppPassword.revoke(client, credentials.origin)
    }

    override suspend fun revokeIssuedAppPassword(
        credentials: CalDavGateway.Credentials,
    ): Boolean = withContext(io) {
        val client = CalDavHttp.authenticated(
            userAgent, credentials.username, credentials.password, credentials.origin,
        )
        AppPassword.revokeAt(client, credentials.origin)
    }

    private fun now() = System.currentTimeMillis() / 1000

    private companion object {
        /** One poll of a 2s loop. Long enough for a homelab, short enough to cancel. */
        const val POLL_TIMEOUT_SECONDS = 15L
    }
}
