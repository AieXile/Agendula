package de.jeanlucmakiola.caldav

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Nextcloud Login Flow v2.
 *
 * The user approves the app in a browser and the server mints a dedicated **app
 * password**, so the account password never touches this device and the grant can
 * be revoked from the server's own settings. It is not deprecated, there is no
 * v3, and OAuth2 is a worse fit.
 *
 * Every rule below is a correction from an audit of the first draft, and each
 * one turns a diagnosable error back into a diagnosable error.
 */
class NextcloudLoginFlow(
    private val httpClient: OkHttpClient,
    /**
     * ⚠️ Becomes the **app password's name** in Settings → Security → Devices &
     * sessions. With OkHttp's default the user sees `okhttp/4.12.0` and cannot
     * tell what to revoke — which defeats the entire point of the flow.
     */
    private val userAgent: String,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** The 20-minute server-side lifetime (`lifetime = 1200` in `LoginFlowV2Mapper.php`). */
    val flowLifetimeSeconds = 1200L

    /**
     * A started flow. Persist this **before** launching the browser: the flow
     * outlives our process, and Custom Tabs return no result when dismissed.
     */
    data class Flow(
        val loginUrl: HttpUrl,
        val pollEndpoint: HttpUrl,
        val pollToken: String,
        val deadlineEpochSeconds: Long,
        /**
         * Set when the server sent us to a different host than the user typed.
         * **Carried, not thrown**: it is generated from `overwrite.cli.url` /
         * `overwriteprotocol` / `trusted_proxies` and is legitimate behind a
         * reverse proxy, which describes a large share of self-hosted installs.
         * Refusing outright would make Login Flow v2 unusable for them. The UI
         * confirms it with the user, quoting the cause.
         */
        val hostMismatch: HostMismatch? = null,
    )

    data class Credentials(val server: HttpUrl, val loginName: String, val appPassword: String)

    sealed interface PollResult {
        data class Approved(
            val credentials: Credentials,
            /**
             * The server named an origin outside the one we polled, and we used
             * the one we polled instead. Reported so the user can be told which
             * setting is wrong, never acted on.
             */
            val hostMismatch: HostMismatch? = null,
        ) : PollResult
        /** 404: still waiting. Also what an expired or already-consumed flow returns. */
        data object Pending : PollResult
        data class Expired(val reason: String) : PollResult
        data class Failed(val cause: Cause, val reason: String) : PollResult

        /**
         * Why the flow ended, in a form the UI can translate.
         *
         * ⚠️ The UI must render *this*, never [Failed.reason]. A server's own
         * words are untranslatable, often in a language the user does not read,
         * and here they are frequently a bare status line or a JSON parser's
         * complaint. [reason] exists for logs, and is never shown — the same rule
         * [CalDavDiscovery.Outcome.Cause] states for discovery.
         */
        enum class Cause {
            /** Answering, but turning away repeated attempts (429). */
            RATE_LIMITED,

            /** Down on purpose (503). */
            MAINTENANCE,

            /** The server's own error, or an answer we could not read. */
            SERVER_ERROR,
        }
    }

    /**
     * @param server the base URL the user typed
     * @param now epoch seconds, for the locally tracked deadline
     */
    fun start(server: HttpUrl, now: Long): Result<Flow> = runCatching {
        val request = Request.Builder()
            .url(server.newBuilder().addPathSegments(FLOW_START_PATH).build())
            .header("User-Agent", userAgent)
            // OCS-APIRequest is *not* needed: v2 is a Frontpage route, not OCS.
            .post(FormBody.Builder().build())
            .build()

        Redirects.follow(httpClient, request).use { response ->
            if (!response.isSuccessful) error("login flow init failed: HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            val root = json.parseToJsonElement(body).jsonObject
            val poll = root["poll"]?.jsonObject ?: error("no poll object in login flow response")

            val endpointRaw = poll["endpoint"]?.jsonPrimitive?.content
                ?: error("no poll endpoint")
            val endpoint = endpointRaw.toHttpUrlOrNull() ?: error("poll endpoint is not a URL")
            requireSecureOrigin(server, endpoint)

            val loginUrl = root["login"]?.jsonPrimitive?.content?.toHttpUrlOrNull()
                ?: error("no login URL")
            // ⚠️ The login URL is where the user types their **account** password,
            // in a browser. Validating only the poll endpoint leaves the more
            // dangerous of the two unchecked: a misconfigured or hostile server —
            // or a MITM on a typed http:// base — could point the browser at any
            // origin and harvest it.
            requireSecureOrigin(server, loginUrl)

            Flow(
                loginUrl = loginUrl,
                pollEndpoint = endpoint,
                pollToken = poll["token"]?.jsonPrimitive?.content ?: error("no poll token"),
                deadlineEpochSeconds = now + flowLifetimeSeconds,
                hostMismatch = hostMismatchOf(server, endpoint) ?: hostMismatchOf(server, loginUrl),
            )
        }
    }

    /**
     * One poll. The 200 is returned **exactly once** — the server deletes the row
     * inside `poll()` before returning — so the caller must persist the result
     * immediately.
     */
    fun poll(flow: Flow, now: Long): PollResult {
        if (now > flow.deadlineEpochSeconds) {
            // 404 is also what an expired flow returns, so the deadline is tracked
            // locally or "expired" is indistinguishable from "still waiting".
            return PollResult.Expired("the 20-minute approval window has passed")
        }

        val request = Request.Builder()
            .url(flow.pollEndpoint)
            .header("User-Agent", userAgent)
            // ⚠️ POST, form-encoded. A GET gets 405.
            .post(FormBody.Builder().add("token", flow.pollToken).build())
            .build()

        return runCatching {
            Redirects.follow(httpClient, request).use { response ->
                when {
                    // ⚠️ 404 means pending, and *only* 404 does. "Anything that
                    // isn't 200 is pending" swallows 429 (brute-force protection),
                    // 503 (maintenance), a Cloudflare challenge page and every
                    // DNS/TLS failure — turning a diagnosable error into a
                    // twenty-minute spinner.
                    response.code == 404 -> PollResult.Pending

                    response.code == 200 -> {
                        val contentType = response.header("Content-Type").orEmpty()
                        // A Cloudflare challenge is a 200 carrying HTML.
                        if (!contentType.contains("application/json", ignoreCase = true)) {
                            PollResult.Failed(
                                PollResult.Cause.SERVER_ERROR,
                                "server answered 200 with $contentType, not JSON",
                            )
                        } else {
                            parseCredentials(flow, response.body?.string().orEmpty())
                        }
                    }

                    response.code == 429 ->
                        PollResult.Failed(
                            PollResult.Cause.RATE_LIMITED,
                            "the server is rate-limiting this address (429)",
                        )

                    response.code == 503 ->
                        PollResult.Failed(
                            PollResult.Cause.MAINTENANCE,
                            "the server is in maintenance mode (503)",
                        )

                    else -> PollResult.Failed(
                        PollResult.Cause.SERVER_ERROR,
                        "unexpected HTTP ${response.code}",
                    )
                }
            }
        }.getOrElse { PollResult.Failed(PollResult.Cause.SERVER_ERROR, it.message ?: it.toString()) }
    }

    private fun parseCredentials(flow: Flow, body: String): PollResult = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val server = root["server"]?.jsonPrimitive?.content?.toHttpUrlOrNull()
            ?: error("no server URL in poll response")
        // ⚠️ Coerced, never refused — neither the host nor the scheme may discard
        // the credentials. The 200 is returned exactly once (the server deletes
        // the row inside poll() before answering), so a throw here burns a live
        // app password and leaves it dangling in the user's device list.
        val secureServer = reachableOrigin(flow.pollEndpoint, server)
        PollResult.Approved(
            hostMismatch = substitutedMismatch(flow.pollEndpoint, server, secureServer),
            credentials = Credentials(
                server = secureServer,
                // ⚠️ loginName is what the user typed — possibly an email, an
                // LDAP-derived value, or the right name in the wrong case. It is
                // the Basic auth username and nothing else. Interpolating it into
                // `remote.php/dav/calendars/<loginName>/` is the classic "logged
                // in but no calendars" bug; the principal comes from discovery.
                loginName = root["loginName"]?.jsonPrimitive?.content ?: error("no loginName"),
                appPassword = root["appPassword"]?.jsonPrimitive?.content ?: error("no appPassword"),
            ),
        )
    }.getOrElse { PollResult.Failed(PollResult.Cause.SERVER_ERROR, it.message ?: it.toString()) }

    /**
     * These URLs are generated from `overwrite.cli.url` / `overwriteprotocol` /
     * `trusted_proxies`, which are misconfigured on a large fraction of
     * self-hosted installs — so they are validated rather than trusted verbatim.
     *
     * A downgrade to `http` is **fatal here and only here**: this runs *before*
     * the user has approved anything, and the login URL is where they type their
     * account password. There is nothing to lose by refusing, and everything to
     * lose by not.
     */
    internal fun requireSecureOrigin(expected: HttpUrl, actual: HttpUrl) {
        if (expected.isHttps && !actual.isHttps) {
            error("the server returned an http:// URL (${actual.host}) for an https:// server")
        }
    }

    /**
     * The same downgrade, on the *poll response*, where refusing is the wrong
     * answer.
     *
     * ⚠️ By this point the credential has already been issued, and Nextcloud
     * returns it **exactly once** — the row is deleted inside `poll()` before it
     * answers. Throwing here does not protect anything: it destroys a live app
     * password, leaves one dangling in the user's device list, and sends them
     * through the whole flow again. The comment on the host mismatch above says
     * precisely this, and the scheme deserves the same treatment.
     *
     * Coercing is strictly safer than either alternative, because the invariant
     * that matters is *what we do next*: we only ever talk to the coerced URL, so
     * the credential never travels in cleartext regardless of what the server
     * put in the JSON.
     */
    internal fun secureOrigin(expected: HttpUrl, actual: HttpUrl): HttpUrl =
        if (expected.isHttps && !actual.isHttps) {
            // ⚠️ The port goes with the scheme. OkHttp only drops a *default*
            // port across a scheme change, so `http://cloud.example.com:8080/`
            // coerced to https keeps :8080 — a port that almost certainly speaks
            // cleartext, and the promised coercion becomes a TLS handshake
            // failure. The port that answered our poll is the one known to work,
            // and a claimed port emitted alongside a wrong scheme comes from the
            // same misconfiguration as the scheme did.
            actual.newBuilder().scheme("https").port(expected.port).build()
        } else {
            actual
        }

    /**
     * The origin to actually talk to, given what the poll response claimed.
     *
     * ⚠️ `server` and the poll endpoint come out of *different* generators in
     * Nextcloud: the poll endpoint honours `overwrite.cli.url`, while `server`
     * is built from the approving request's own protocol and Host header, which
     * respect `X-Forwarded-*` only once `trusted_proxies` is set. The ordinary
     * docker-compose-behind-nginx install therefore answers a perfectly good
     * `https://cloud.example.com` poll endpoint with
     * `"server": "http://nextcloud:11000"` — a name that does not resolve on the
     * phone. Following it means discovery fails, the account is never created,
     * and the app password is already spent.
     *
     * So when the claimed origin is outside the registrable domain we just
     * successfully polled, rebuild it entirely from the poll endpoint — scheme,
     * host, port *and* base path — which is the one URL empirically known to
     * answer. The claimed path is dropped with the claimed host: both come from
     * the same generator, and keeping half of a URL we have decided not to trust
     * is how a subdirectory install loses its prefix. The endpoint's own prefix
     * is whatever precedes `index.php/login/v2/poll`, which is where the flow
     * was opened. Coerced, never refused: by this point the credential exists
     * and the 200 is spent, so throwing burns a live app password.
     *
     * This compares one server-emitted origin against another, never against
     * what the user typed, so a correctly configured proxy — which emits the
     * same origin in both — is untouched. A sibling host under the same
     * registrable domain passes through, because that is a real deployment shape
     * and the credential is scoped to that domain anyway.
     */
    internal fun reachableOrigin(expected: HttpUrl, actual: HttpUrl): HttpUrl {
        val secure = secureOrigin(expected, actual)
        // The same boundary the credential is scoped by; null for an IP literal
        // or a single-label host, where only the exact host will do.
        val claimed = secure.topPrivateDomain() ?: secure.host
        val polled = expected.topPrivateDomain() ?: expected.host
        if (claimed.equals(polled, ignoreCase = true)) return secure
        return expected.newBuilder()
            .encodedPath(baseOf(expected))
            .query(null)
            .fragment(null)
            .build()
    }

    private companion object {
        const val FLOW_START_PATH = "index.php/login/v2"

        /** What `start()` appends, plus the poll leg — with and without the front controller. */
        val FLOW_TAILS = listOf("index.php/login/v2/poll", "login/v2/poll")
    }

    /**
     * The poll endpoint with the flow's own path removed — the server's web root.
     *
     * ⚠️ Both spellings. Nextcloud drops `index.php` from generated routes when
     * `htaccess.IgnoreFrontController` is on, so a subdirectory install can answer
     * `/nc/login/v2/poll` — and matching only the `index.php` form would return
     * "/" and lose the `/nc` prefix, which is the exact loss this function exists
     * to prevent.
     */
    private fun baseOf(pollEndpoint: HttpUrl): String {
        val path = pollEndpoint.encodedPath
        val tail = FLOW_TAILS.map { path.indexOf(it) }.firstOrNull { it >= 0 } ?: return "/"
        return path.take(tail).ifEmpty { "/" }
    }

    /**
     * The mismatch to report, which is only ever one we acted on.
     *
     * ⚠️ [hostMismatchOf] compares hosts exactly, while [reachableOrigin]
     * substitutes only when the *registrable domain* differs. So a server
     * answering `nc.example.com` for a poll endpoint on `cloud.example.com` is
     * used verbatim and correctly — and reporting that would tell the user we
     * replaced an address we did not, blaming a setting that is right. The note
     * is sticky, so it would follow them through the rest of the flow.
     */
    internal fun substitutedMismatch(
        expected: HttpUrl,
        claimed: HttpUrl,
        used: HttpUrl,
    ): HostMismatch? =
        hostMismatchOf(expected, claimed)?.takeIf { used.host != claimed.host }

    /** A different host than the user typed — reported, not refused. */
    internal fun hostMismatchOf(expected: HttpUrl, actual: HttpUrl): HostMismatch? =
        if (expected.host != actual.host) HostMismatch(expected.host, actual.host) else null

    /** The server pointed the flow at a different host than the user typed. */
    data class HostMismatch(val expected: String, val actual: String) {
        val message: String
            get() = "the server sent us to \"$actual\" but you typed \"$expected\" — " +
                "its overwrite.cli.url is probably wrong"
    }
}
