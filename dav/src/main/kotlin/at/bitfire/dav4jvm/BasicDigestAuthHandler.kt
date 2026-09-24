/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package at.bitfire.dav4jvm

import okhttp3.*
import okhttp3.Response
import okio.Buffer
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Handler to manage authentication against a given service (may be limited to one domain).
 * There's no domain-based cache, because the same user name and password will be used for
 * all requests.
 *
 * Authentication methods/credentials found to be working will be cached for further requests
 * (this is why the interceptor is needed).
 *
 * Usage: Set as authenticator *and* as network interceptor.
 */
class BasicDigestAuthHandler(
        /**
         * Authenticate only against hosts sharing this registrable domain, as
         * [HttpUrl.topPrivateDomain] derives it — or, for a host that has none
         * (an IP literal, `localhost`, a host that *is* a public suffix), only
         * against that exact host. Null means no restriction.
         */
        val domain: String?,

        val username: String,
        val password: String,

        /**
         * Allow Basic over cleartext — both preemptively and in answer to a
         * challenge. Off by default: the header is the password.
         */
        val insecureBasic: Boolean = false
): Authenticator, Interceptor {

    companion object {
        private const val HEADER_AUTHORIZATION = "Authorization"

        fun quotedString(s: String) = "\"" + s.replace("\"", "\\\"") + "\""
        fun h(data: String) = data.toByteArray().toByteString().md5().hex()

        fun h(body: RequestBody): String {
            val buffer = Buffer()
            body.writeTo(buffer)
            return buffer.readByteArray().toByteString().md5().hex()
        }

        fun kd(secret: String, data: String) = h("$secret:$data")
    }

    // cached authentication schemes
    private var basicAuth: Challenge? = null
    private var digestAuth: Challenge? = null

    /*
     * ⚠️ Per handler, not per process. Upstream keeps these on the companion
     * object while `SyncEngine` builds one handler per account, so two accounts
     * syncing at once interleave their `nc` values against each other's nonces.
     * RFC 7616 §3.4.1 defines `nc` as the count of requests sent *with that
     * nonce*, starting at 1 — a server that enforces it (Apache
     * `AuthDigestNcCheck On`, several NAS stacks) answers 401 and the account is
     * marked as needing sign-in. The tell upstream left behind is that every
     * digest test has to reset the counter by hand.
     */
    var clientNonce = h(UUID.randomUUID().toString())
    val nonceCount = AtomicInteger(1)

    /** The server nonce [nonceCount] is counting against. */
    private var countedNonce: String? = null


    fun authenticateRequest(request: Request, response: Response?): Request? {
        domain?.let {
            val host = request.url.host
            // ⚠️ The public-suffix list, not a last-two-labels split. Splitting
            // scopes `cloud.example.co.uk` to `co.uk` and `192.168.1.10` to
            // `1.10`, handing the credential preemptively to any host that
            // matches — including one an attacker can buy a certificate for.
            // `topPrivateDomain()` is null for hosts with no registrable domain,
            // and null here would mean *no* restriction, so it falls back to the
            // exact host.
            if (!domain.equals(request.url.topPrivateDomain() ?: host, true)) {
                Dav4jvm.log.warning("Not authenticating against $host because it doesn't belong to $domain")
                return null
            }
        }

        if (response == null) {
            // we're not processing a 401 response

            if (basicAuth == null && digestAuth == null && (request.isHttps || insecureBasic)) {
                Dav4jvm.log.fine("Trying Basic auth preemptively")
                basicAuth = Challenge("Basic", "")
            }

        } else {
            // we're processing a 401 response

            // ⚠️ Every challenge is read before anything is decided. Upstream
            // returned out of this loop the moment Basic was found to have
            // failed — and because the handler is a network interceptor,
            // `basicAuth` is *always* primed preemptively, so that fired on the
            // very first 401. A server advertising `Basic` then `Digest` in one
            // header therefore never had its Digest challenge read at all: a
            // Baikal/Apache front end that offers both and rejects Basic at the
            // app layer got no Digest answer, and the account was marked as
            // needing sign-in permanently. Giving up is still the outcome when
            // nothing usable is left — it just happens after the whole header
            // has been looked at.
            var newBasicAuth: Challenge? = null
            var newDigestAuth: Challenge? = null
            for (challenge in response.challenges())
                when {
                    "Basic".equals(challenge.scheme, true) ->
                        if (basicAuth != null) {
                            Dav4jvm.log.warning("Basic credentials didn't work last time -> not offering them again")
                        } else {
                            newBasicAuth = challenge
                        }
                    "Digest".equals(challenge.scheme, true) ->
                        if (digestAuth != null && !"true".equals(challenge.authParams["stale"], true)) {
                            Dav4jvm.log.warning("Digest credentials didn't work last time and server nonce has not expired -> not offering them again")
                        } else {
                            newDigestAuth = challenge
                        }
                }

            // ⚠️ Not cached if we would refuse to answer it. Caching a challenge
            // we never answer leaves the handler believing Basic is in play: the
            // preemptive block is skipped, the refusal repeats, and the 401 after
            // that reports "Basic credentials didn't work last time" about a
            // credential that was never put on the wire.
            basicAuth = newBasicAuth?.takeIf { request.isHttps || insecureBasic }
            digestAuth = newDigestAuth
        }

        // we MUST prefer Digest auth [https://tools.ietf.org/html/rfc2617#section-4.6]
        when {
            digestAuth != null -> {
                Dav4jvm.log.fine("Adding Digest authorization request for ${request.url}")
                return digestRequest(request, digestAuth)
            }

            basicAuth != null -> {
                // ⚠️ Gated here, not only on the preemptive path above. Basic
                // *is* the password, in a header any hop can read, so a plain
                // HTTP server answering 401 with a Basic challenge must not be
                // able to ask for it. Digest is left alone: it never transmits
                // the password, and refusing it would kill a LAN server the day
                // the cleartext opt-in ships.
                if (!request.isHttps && !insecureBasic) {
                    Dav4jvm.log.warning("Refusing to send Basic credentials over cleartext to ${request.url}")
                    return null
                }
                Dav4jvm.log.fine("Adding Basic authorization header for ${request.url}")

                /* In RFC 2617 (obsolete), there was no encoding for credentials defined, although
                 one can interpret it as "use ISO-8859-1 encoding". This has been clarified by RFC 7617,
                 which creates a new charset parameter for WWW-Authenticate, which always must be UTF-8.
                 So, UTF-8 encoding for credentials is compatible with all RFC 7617 servers and many,
                 but not all pre-RFC 7617 servers. */
                return request.newBuilder()
                        .header(HEADER_AUTHORIZATION, Credentials.basic(username, password, Charsets.UTF_8))
                        .build()
            }

            response != null ->
                Dav4jvm.log.warning("No supported authentication scheme")
        }

        return null
    }

    fun digestRequest(request: Request, digest: Challenge?): Request? {
        if (digest == null)
            return null

        val realm = digest.authParams["realm"]
        val opaque = digest.authParams["opaque"]
        val nonce = digest.authParams["nonce"]

        val algorithm = Algorithm.determine(digest.authParams["algorithm"])
        val qop = Protection.selectFrom(digest.authParams["qop"])

        // build response parameters
        var response: String? = null

        val params = LinkedList<String>()
        params.add("username=${quotedString(username)}")
        if (realm != null)
            params.add("realm=${quotedString(realm)}")
        else {
            Dav4jvm.log.warning("No realm provided, aborting Digest auth")
            return null
        }
        if (nonce != null)
            params.add("nonce=${quotedString(nonce)}")
        else {
            Dav4jvm.log.warning("No nonce provided, aborting Digest auth")
            return null
        }
        if (opaque != null)
            params.add("opaque=${quotedString(opaque)}")

        if (algorithm != null)
            params.add("algorithm=${quotedString(algorithm.algorithm)}")

        val method = request.method
        val digestURI = request.url.encodedPath
        params.add("uri=${quotedString(digestURI)}")

        if (qop != null) {
            // ⚠️ A new server nonce restarts the count at 1: §3.4.1 counts
            // requests sent with *that* nonce, so carrying the count over makes
            // the first request against a rotated nonce look like the
            // thousandth. The client nonce stays put — it is ours, one per
            // handler, and pairing it with a restarted count is what the RFC
            // describes.
            if (nonce != countedNonce) {
                countedNonce = nonce
                nonceCount.set(1)
            }

            params.add("qop=${qop.qop}")
            params.add("cnonce=${quotedString(clientNonce)}")

            val nc = nonceCount.getAndIncrement()
            val ncValue = String.format(Locale.ROOT, "%08x", nc)
            params.add("nc=$ncValue")

            val a1: String? = when (algorithm) {
                Algorithm.MD5 ->
                    "$username:$realm:$password"
                Algorithm.MD5_SESSION ->
                    h("$username:$realm:$password") + ":$nonce:$clientNonce"
                else ->
                    null
            }
            Dav4jvm.log.finer("A1=$a1")

            val a2: String? = when (qop) {
                Protection.Auth ->
                    "$method:$digestURI"
                Protection.AuthInt -> {
                    try {
                        val body = request.body
                        "$method:$digestURI:" + (if (body != null) h(body) else h(""))
                    } catch(e: IOException) {
                        Dav4jvm.log.warning("Couldn't get entity-body for hash calculation")
                        null
                    }
                }
            }
            Dav4jvm.log.finer("A2=$a2")

            if (a1 != null && a2 != null)
                response = kd(h(a1), "$nonce:$ncValue:$clientNonce:${qop.qop}:${h(a2)}")

        } else {
            Dav4jvm.log.finer("Using legacy Digest auth")

            // legacy (backwards compatibility with RFC 2069)
            if (algorithm == Algorithm.MD5) {
                val a1 = "$username:$realm:$password"
                val a2 = "$method:$digestURI"
                response = kd(h(a1), nonce + ":" + h(a2))
            }
        }

        return if (response != null) {
            params.add("response=" + quotedString(response))
            request.newBuilder()
                    .header(HEADER_AUTHORIZATION, "Digest " + params.joinToString(", "))
                    .build()
        } else
            null
    }


    private enum class Algorithm(
            val algorithm: String
    ) {
        MD5("MD5"),
        MD5_SESSION("MD5-sess");

        companion object {
            fun determine(paramValue: String?): Algorithm? {
                return when {
                    paramValue == null || MD5.algorithm.equals(paramValue, true) ->
                        MD5
                    MD5_SESSION.algorithm.equals(paramValue, true) ->
                        MD5_SESSION
                    else -> {
                        Dav4jvm.log.warning("Ignoring unknown hash algorithm: $paramValue")
                        null
                    }
                }
            }
        }
    }

    private enum class Protection(
        val qop: String
    ) {    // quality of protection:
        Auth("auth"),              // authentication only
        AuthInt("auth-int");       // authentication with integrity protection

        companion object {
            fun selectFrom(paramValue: String?): Protection? {
                paramValue?.let {
                    var qopAuth = false
                    var qopAuthInt = false
                    // ⚠️ Trimmed. HTTP list syntax allows space around the
                    // separator, so `qop="auth, auth-int"` silently downgraded
                    // to auth and a single spaced value matched nothing at all —
                    // which dropped into the RFC 2069 branch and emitted a
                    // response with no qop, nc or cnonce, for a permanent 401
                    // against a server behaving legally.
                    for (qop in paramValue.split(","))
                        when (qop.trim().lowercase()) {
                            "auth" -> qopAuth = true
                            "auth-int" -> qopAuthInt = true
                        }

                    // prefer auth-int as it provides more protection
                    if (qopAuthInt)
                        return AuthInt
                    else if (qopAuth)
                        return Auth
                }
                return null
            }
        }
    }


    override fun authenticate(route: Route?, response: Response) =
            authenticateRequest(response.request, response)

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        if (request.header(HEADER_AUTHORIZATION) == null) {
            // try to apply cached authentication
            val authRequest = authenticateRequest(request, null)
            if (authRequest != null)
                request = authRequest
        }
        return chain.proceed(request)
    }

}