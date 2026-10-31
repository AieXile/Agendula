package com.aiexile.caldav

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Redirect following for the plain-HTTP corners of this module.
 *
 * ⚠️ Every client here is built with `followRedirects(false)`, because
 * `DavResource` requires it and asserts on it — so the DAV calls follow by hand
 * and the two OCS/JSON ones did not follow at all. A Nextcloud that
 * canonicalises host or path with a 301 (apex→www, a trailing slash, a proxy)
 * therefore turned the login flow's `start` into a bare failure, its `poll` into
 * an unexplained SERVER_ERROR *after* the password was minted, and the app
 * password revocation into the "attempted, and did nothing" outcome its own
 * KDoc exists to prevent.
 *
 * ⚠️ The method and body are **re-issued as they were**, which is 307/308
 * behaviour rather than the classic 301/302 POST→GET rewrite. Both endpoints
 * answer 405 to a GET, so rewriting the method would turn one silent failure
 * into another; and the bodies here are in-memory forms, so replaying costs
 * nothing.
 */
internal object Redirects {

    /** The same ceiling `DavResource` uses, for the same reason. */
    const val MAX_HOPS = 5

    @Throws(IOException::class)
    fun follow(client: OkHttpClient, request: Request): Response {
        var current = request
        repeat(MAX_HOPS) {
            val response = client.newCall(current).execute()
            if (!response.isRedirect) return response
            val target = response.header("Location")?.let { current.url.resolve(it) }
            response.close()
            if (target == null) throw IOException("redirect with no usable Location from ${current.url}")
            current = current.newBuilder().url(secure(current.url, target)).build()
        }
        throw IOException("more than $MAX_HOPS redirects from ${request.url}")
    }

    /**
     * The downgrade rule `DavResource` already applies, stated once more here.
     *
     * A downgrade to the *same host* is the single most common misconfiguration
     * in this space — a Nextcloud behind a TLS-terminating proxy with no
     * `overwriteprotocol` builds every redirect with `http://` — and re-issuing
     * over TLS is strictly safer than what we were asked to do. A downgrade to a
     * different host has no innocent reading.
     *
     * ⚠️ The port goes with the scheme: OkHttp only drops a *default* port
     * across a scheme change, so a redirect to `http://host:8080` would
     * otherwise be re-issued as `https://host:8080`, which almost certainly
     * speaks cleartext. The port we were already talking to is the one known to
     * answer.
     */
    private fun secure(from: HttpUrl, to: HttpUrl): HttpUrl = when {
        !from.isHttps || to.isHttps -> to
        to.host.equals(from.host, ignoreCase = true) ->
            to.newBuilder().scheme("https").port(from.port).build()
        else -> throw IOException("refusing a redirect from HTTPS to HTTP at ${to.host}")
    }
}
