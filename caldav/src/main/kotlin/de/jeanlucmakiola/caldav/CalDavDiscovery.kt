package de.jeanlucmakiola.caldav

import at.bitfire.dav4jvm.DavResource
import at.bitfire.dav4jvm.Response
import at.bitfire.dav4jvm.property.CalendarHomeSet
import at.bitfire.dav4jvm.exception.HttpException
import at.bitfire.dav4jvm.property.CurrentUserPrincipal
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * RFC 6764 discovery: from what the user typed to the list of task collections.
 *
 * The happy path is five requests. Everything else here is a rule that exists
 * because a real server broke the obvious implementation.
 */
class CalDavDiscovery(
    private val httpClient: OkHttpClient,
    private val dns: DnsResolver = DnsResolver.None,
    /**
     * Whether a typed `http://` base URL may be probed.
     *
     * Off by default. Credentials are never sent over cleartext — the
     * interceptor withholds them — so an http:// server would otherwise answer
     * 401 forever and the user would be told their password was wrong when the
     * real problem is the scheme. Any escape hatch has to be a narrow, warned,
     * per-account opt-in; this is that switch.
     */
    private val allowCleartext: Boolean = false,
) {

    /**
     * Properties requested **by name**, because allprop legitimately omits them.
     *
     * Owned by [CollectionClassifier], which is what reads them back — a second
     * copy here would drift the day one of them is added.
     */
    private val collectionProperties = CollectionClassifier.PROPERTIES

    /** A home set that could not be listed. One failing must not hide the others. */
    data class HomeSetFailure(
        val url: HttpUrl,
        val reason: String,
        val needsAuthentication: Boolean,
    )

    sealed interface Outcome {
        data class Found(
            val principal: HttpUrl,
            val collections: List<TaskCollection>,
            /** Every `calendar-home-set` href, in the order the principal listed them. */
            val homeSets: List<HttpUrl>,
            /** Set when a 301/308 moved us; the caller must persist it. */
            val movedTo: HttpUrl?,
            /** Home sets on a different host than the principal. Normative, but worth surfacing. */
            val crossHostHomeSets: List<HttpUrl>,
            /** Home sets that could not be read. The rest of the result is still good. */
            val failedHomeSets: List<HomeSetFailure> = emptyList(),
        ) : Outcome

        /**
         * The server wants credentials. Not a failure — authenticate and retry.
         *
         * [hosts] names *which* hosts asked, so the caller can say which one it
         * could not reach. A cross-host home set under the same registrable
         * domain — iCloud puts the principal on `caldav.icloud.com` and the home
         * set on `pNN-caldav.icloud.com` — is already covered, because that is
         * the scope `CalDavHttp` gives the credential.
         *
         * ⚠️ What is *not* covered is a home set on a genuinely different
         * registrable domain, which RFC 4791 §6.2.1 allows. Nothing widens the
         * allowlist for it and nothing should without the user's say-so: the
         * password would be offered to a host named by the first server, and one
         * credential scope is what makes that decidable. The add flow reports
         * such a host by name instead of pretending the account is empty.
         */
        data class NeedsAuthentication(val hosts: List<String>) : Outcome

        /** A 200 whose body says the credentials were not accepted (RFC 5397 §3). */
        data object Unauthenticated : Outcome

        data class NotCalDav(val cause: Cause, val detail: String) : Outcome

        data class Failed(val cause: Cause, val detail: String) : Outcome

        /**
         * Why discovery ended, in a form the UI can translate.
         *
         * ⚠️ The UI must render *this*, never [Failed.detail]. A server's own
         * words are untranslatable, frequently in a language the user does not
         * read, and quite often a bare status line — "HTTP 405 Method Not
         * Allowed" tells someone entering their address precisely nothing, and
         * bypasses `strings.xml` entirely. [detail] exists for logs and bug
         * reports, and is never shown.
         */
        enum class Cause {
            /** The address is not a URL, or names nothing we can look up. */
            NOT_AN_ADDRESS,

            /** Reached something, but it does not speak WebDAV at all. */
            NOT_A_DAV_SERVER,

            /** Speaks WebDAV but not CalDAV — a file-sharing endpoint, say. */
            NO_CALENDAR_SUPPORT,

            /** Nothing answered: DNS, connection refused, TLS, timeout. */
            UNREACHABLE,

            /** The address, or where it redirects, is plain HTTP. */
            INSECURE,

            /** Signed in, but the account exposes no calendar home. */
            NO_CALENDARS,

            /** The server answered, and the answer was an error of its own. */
            SERVER_ERROR,
        }
    }

    /**
     * Classifies a transport or protocol failure for the UI.
     *
     * ⚠️ **405 is the interesting one.** It is what an ordinary web server
     * answers to `PROPFIND`, which makes it the single most likely response to
     * someone typing their *website* instead of their CalDAV address — and it
     * means exactly "this is not a DAV server". Surfacing it as "HTTP 405 Method
     * Not Allowed" hands the user a status code where they needed a sentence.
     */
    private fun causeOf(error: Throwable): Outcome.Cause = when {
        error is HttpException -> when (error.code) {
            METHOD_NOT_ALLOWED, NOT_IMPLEMENTED, NOT_FOUND -> Outcome.Cause.NOT_A_DAV_SERVER
            else -> Outcome.Cause.SERVER_ERROR
        }
        // Everything that never got an answer: DNS, refused, TLS, timeout.
        error is IOException -> Outcome.Cause.UNREACHABLE
        else -> Outcome.Cause.SERVER_ERROR
    }

    /**
     * Walks every candidate for [input] and returns the first real result.
     *
     * A 401 stops the walk immediately: it means we found a DAV server and simply
     * have no credentials for it, and continuing down the ladder would replace a
     * precise "sign in" with a vague "nothing found".
     */
    fun discover(input: String): Outcome {
        ServiceDiscovery.asBaseUrl(input)?.let { typed ->
            if (!typed.isHttps && !allowCleartext) {
                return Outcome.Failed(
                    Outcome.Cause.INSECURE,
                    "\"$input\" is an unencrypted http:// address",
                )
            }
        }

        val candidates = ServiceDiscovery.candidatesFor(input, dns)
        if (candidates.isEmpty()) {
            return Outcome.Failed(Outcome.Cause.NOT_AN_ADDRESS, "no candidates for \"$input\"")
        }

        var lastFailure: Outcome = Outcome.Failed(Outcome.Cause.UNREACHABLE, "no candidate answered")
        for (candidate in candidates) {
            when (val outcome = probe(candidate.url)) {
                is Outcome.Found, is Outcome.NeedsAuthentication, Outcome.Unauthenticated -> return outcome
                else -> lastFailure = outcome
            }
        }
        return lastFailure
    }

    internal fun probe(base: HttpUrl): Outcome {
        val resource = DavResource(httpClient, base)

        // The OPTIONS gate. `DAV: calendar-access` is what distinguishes a CalDAV
        // server from any other WebDAV host, and it is what keeps Google's SRV
        // record — which points at something that answers 405 to PROPFIND — from
        // looking like a discovery that merely found no calendars.
        var davCapabilities: Set<String> = emptySet()
        runCatching { resource.options { capabilities, _ -> davCapabilities = capabilities } }

        var principalHref: String? = null
        var unauthenticated = false
        val propfindResult = runCatching {
            resource.propfind(0, CurrentUserPrincipal.NAME) { response, _ ->
                response[CurrentUserPrincipal::class.java]?.let {
                    principalHref = it.href
                    unauthenticated = unauthenticated || it.unauthenticated
                }
            }
        }

        propfindResult.exceptionOrNull()?.let { error ->
            // 401 is not a failure — iCloud and Zoho answer it from
            // /.well-known/caldav, which *is* the DAV root, and it is RFC-legal.
            if (isUnauthorized(error)) return Outcome.NeedsAuthentication(listOf(base.host))
            return Outcome.Failed(causeOf(error), error.message ?: error.toString())
        }

        // ⚠️ RFC 5397 §3: a 200 carrying <D:unauthenticated/> means the
        // credentials were rejected. Without this check a failed login looks like
        // a successful discovery that found nothing — the shape of bug report
        // nobody can act on. The element is parsed explicitly (dav/PROVENANCE.md
        // change 6) rather than inferred from a null href, which would also fire
        // for a merely non-conformant empty element.
        if (unauthenticated) return Outcome.Unauthenticated

        val href = principalHref
            ?: return if (davCapabilities.contains("calendar-access")) {
                Outcome.Failed(
                    Outcome.Cause.NO_CALENDAR_SUPPORT,
                    "advertises calendar-access but returned no principal",
                )
            } else {
                Outcome.NotCalDav(
                    Outcome.Cause.NOT_A_DAV_SERVER,
                    "no DAV:current-user-principal, and no calendar-access in OPTIONS",
                )
            }

        if (davCapabilities.isNotEmpty() && !davCapabilities.contains("calendar-access")) {
            return Outcome.NotCalDav(
                Outcome.Cause.NO_CALENDAR_SUPPORT,
                "OPTIONS advertises ${davCapabilities.joinToString()} but not calendar-access",
            )
        }

        val principal = resource.location.resolve(href)
            ?: return Outcome.Failed(
                Outcome.Cause.SERVER_ERROR,
                "principal href \"$href\" is not a usable URL",
            )

        return fromPrincipal(principal, movedTo = resource.permanentLocation)
    }

    internal fun fromPrincipal(principal: HttpUrl, movedTo: HttpUrl? = null): Outcome {
        val homeSets = mutableListOf<HttpUrl>()
        val principalResource = DavResource(httpClient, principal)
        val homeSetResult = runCatching {
            principalResource.propfind(0, CalendarHomeSet.NAME) { response, _ ->
                // ⚠️ Iterate ALL hrefs. Multiple home sets are normative (RFC 4791
                // §6.2.1's own example) and iCloud depends on it: the principal is
                // on caldav.icloud.com and the home set on pNN-caldav.icloud.com.
                // ⚠️ Against where the PROPFIND *landed*, not where it was
                // aimed. `followRedirects` rewrites `location` in place, and the
                // probe above already reads it back for exactly this reason. A
                // principal that has permanently moved answers a relative
                // `<href>calendars/alice/</href>`, which resolved against the
                // pre-redirect URL names a path on the old host — the home-set
                // PROPFIND 404s and a perfectly good account reports that it
                // holds no calendars.
                response[CalendarHomeSet::class.java]?.hrefs?.forEach { raw ->
                    principalResource.location.resolve(raw)?.let(homeSets::add)
                }
            }
        }
        homeSetResult.exceptionOrNull()?.let { error ->
            if (isUnauthorized(error)) return Outcome.NeedsAuthentication(listOf(principal.host))
            return Outcome.Failed(causeOf(error), error.message ?: error.toString())
        }

        if (homeSets.isEmpty()) {
            return Outcome.Failed(Outcome.Cause.NO_CALENDARS, "principal has no calendar-home-set")
        }

        // Cross-host is legal and required, but never over plain HTTP: the
        // credentials follow the home set, and a downgrade would send them in the
        // clear. The caller surfaces the host change to the user.
        val crossHost = homeSets.filter { it.host != principalResource.location.host }
        val insecure = homeSets.filter { principal.isHttps && !it.isHttps }
        if (insecure.isNotEmpty()) {
            return Outcome.Failed(
                Outcome.Cause.INSECURE,
                "calendar-home-set downgrades to HTTP: ${insecure.first()}",
            )
        }

        val collections = linkedMapOf<HttpUrl, TaskCollection>()
        val failures = mutableListOf<HomeSetFailure>()
        var anySucceeded = false

        for (homeSet in homeSets.distinct()) {
            val result = runCatching {
                DavResource(httpClient, homeSet).propfind(1, *collectionProperties) { response, relation ->
                    if (relation == Response.HrefRelation.SELF) return@propfind
                    CollectionClassifier.classify(response)?.let { collections[it.url] = it }
                }
            }
            result.fold(
                // A failed home set must not fail the account — the same rule the
                // engine runs on. One broken share must not hide every other list,
                // and a 401 from a cross-host home set must not tell the user to
                // sign in again with credentials that just worked.
                onSuccess = { anySucceeded = true },
                onFailure = {
                    failures += HomeSetFailure(
                        url = homeSet,
                        reason = it.message ?: it.toString(),
                        needsAuthentication = isUnauthorized(it),
                    )
                },
            )
        }

        // Every home set failing is a server problem, not a discovery that found
        // no lists. Reporting it as success gives the user "connected, no task
        // lists" for what is actually an error.
        if (!anySucceeded) {
            val needAuth = failures.filter { it.needsAuthentication }
            return if (needAuth.isNotEmpty() && needAuth.size == failures.size) {
                Outcome.NeedsAuthentication(needAuth.map { it.url.host }.distinct())
            } else {
                Outcome.Failed(
                    Outcome.Cause.NO_CALENDARS,
                    failures.firstOrNull()?.reason ?: "no calendar-home-set could be listed",
                )
            }
        }

        return Outcome.Found(
            principal = principal,
            collections = collections.values.toList(),
            homeSets = homeSets.distinct(),
            movedTo = movedTo,
            crossHostHomeSets = crossHost,
            failedHomeSets = failures,
        )
    }

    private fun isUnauthorized(error: Throwable): Boolean =
        error is at.bitfire.dav4jvm.exception.UnauthorizedException

    companion object {
        /** `https://host/path` → the URL, or null. Convenience for callers. */
        fun url(value: String): HttpUrl? = value.toHttpUrlOrNull()

        private const val NOT_FOUND = 404
        private const val METHOD_NOT_ALLOWED = 405
        private const val NOT_IMPLEMENTED = 501
    }
}
