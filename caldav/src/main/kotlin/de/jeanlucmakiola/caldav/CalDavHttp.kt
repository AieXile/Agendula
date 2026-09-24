package de.jeanlucmakiola.caldav

import at.bitfire.dav4jvm.BasicDigestAuthHandler
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.GzipSource
import okio.buffer
import java.util.concurrent.TimeUnit

/**
 * The HTTP clients the CalDAV layer talks through.
 *
 * ⚠️ `followRedirects(false)` is mandatory, not a preference: `DavResource`
 * requires it and asserts on it. Redirects are followed by hand so a
 * HTTPS→HTTP downgrade can be refused and a permanent move can be reported to
 * the caller — see `dav/PROVENANCE.md` change 3.
 */
object CalDavHttp {

    /**
     * One shared base client, so every derived client reuses its connection pool
     * and dispatcher threads. Building a fresh `OkHttpClient` per probe gives
     * each its own pool — every rung of the RFC 6764 ladder reopens TLS, and the
     * abandoned clients' idle threads live until GC.
     */
    private val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            // A homelab server on the end of a slow link is normal; a hung socket
            // is not. Bounded so a killed worker is the exception, not the rule.
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            // ⚠️ A whole-call ceiling, because the three above are per-attempt:
            // a server trickling one byte every 119 seconds satisfies the read
            // timeout for ever, and holds a sequential sync for the entire
            // WorkManager window while every later collection is skipped. Wide
            // enough for a large multiget on a slow homelab link, narrow enough
            // that one stalled request cannot eat the run.
            .callTimeout(3, TimeUnit.MINUTES)
            .build()
    }

    /** Discovery before we have credentials, and the Nextcloud login flow. */
    fun anonymous(userAgent: String): OkHttpClient = base(userAgent).build()

    /**
     * Authenticated against [origin]'s registrable domain.
     *
     * Uses the vendored [BasicDigestAuthHandler] rather than a hand-rolled
     * interceptor, and it is worth saying why, because a preemptive-Basic
     * interceptor is the obvious thing to write and this project wrote one first:
     *
     * - **It does Digest.** Baïkal defaults to `dav_auth_type = Digest` and OkHttp
     *   has no Digest support of its own (square/okhttp#205, open for years).
     *   Baïkal is squarely in the self-hosting audience.
     * - **It already sends Basic preemptively over HTTPS**, and only over HTTPS,
     *   so the extra round trip on every request of a PROPFIND-heavy sync is
     *   avoided without a second implementation.
     * - **It restricts by registrable domain**, which is what a cross-host home
     *   set needs: iCloud puts the principal on `caldav.icloud.com` and the home
     *   set on `pNN-caldav.icloud.com`, and an exact-host allowlist refuses the
     *   second one.
     * - **It caches which scheme worked for the lifetime of the client**, so a
     *   sync that reuses one client pays the 401 challenge once. ⚠️ The cache
     *   lives on the handler, and this builds a fresh one per call — `discover`,
     *   `revokeAppPassword` and `sync` each get their own — so a Digest-only
     *   server pays one challenge per client, not one per process.
     */
    fun authenticated(
        userAgent: String,
        username: String,
        password: String,
        origin: HttpUrl,
    ): OkHttpClient {
        val handler = BasicDigestAuthHandler(
            // ⚠️ The **registrable** domain, not the host, and it must be
            // derived exactly as the handler derives it for each request — pass
            // "cloud.example.com" where the handler computes "example.com" and
            // the credential is withheld from every request. That is every
            // self-hosted Nextcloud.
            //
            // Public-suffix list, not a last-two-labels split: the split scopes
            // cloud.example.co.uk to co.uk and 192.168.1.10 to 1.10, offering
            // the password preemptively to strangers. `topPrivateDomain()` is
            // null for an IP literal or a single-label host, where the exact
            // host is the only safe scope.
            //
            // What this still trusts: two hosts under one registrable domain
            // have one owner. That is what a cross-host `calendar-home-set`
            // needs — iCloud answers on caldav.icloud.com and serves from
            // pNN-caldav.icloud.com — so a breached dav.example.com can still
            // claim evil.example.com. Narrowing further costs iCloud.
            domain = origin.topPrivateDomain() ?: origin.host,
            username = username,
            password = password,
            // Never over cleartext, challenged or not.
            insecureBasic = false,
        )
        return base(userAgent)
            .authenticator(handler)
            .addNetworkInterceptor(handler)
            .build()
    }

    private fun base(userAgent: String) = shared.newBuilder()
        .addInterceptor(calendarHeaders)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder().header("User-Agent", userAgent).build(),
            )
        }

    /**
     * The two headers that decide whether ETags are usable and whether our bytes
     * survive the server.
     *
     * **`Accept-Encoding: identity`.** Setting it by hand disables OkHttp's
     * transparent gzip, and that is the point: ⚠️ a compressing intermediary
     * *weakens the ETag*. Any gzip-enabled nginx, Cloudflare or Traefik in front
     * of an otherwise perfect server turns every strong validator into `W/"…"`,
     * and a weak tag cannot be used with `If-Match` (RFC 9110 §13.1.1) — so
     * conditional writes stop working for reasons that live in the user's reverse
     * proxy rather than in their CalDAV server. Trading some bandwidth for a
     * working conditional PUT is the right side of that deal.
     *
     * **`Prefer: handling=strict` on writes.** sabre-based servers (Baïkal,
     * Nextcloud, ownCloud, SabreDAV itself) run vobject's `REPAIR` over anything
     * uploaded unless this is set, and `Server::createFile` then deliberately
     * withholds the ETag because the stored bytes are no longer ours. Strict
     * handling keeps both. RFC 7240 §2 requires an unrecognised preference to be
     * ignored, so sending it to every server costs nothing.
     */
    private val calendarHeaders = Interceptor { chain ->
        val request = chain.request()
        val builder = request.newBuilder().header("Accept-Encoding", "identity")
        if (request.method in WRITE_METHODS) {
            // ⚠️ Appended, not replaced. A caller that set its own `Prefer` —
            // `return=minimal`, a `depth-noroot` — would otherwise have it
            // silently dropped on every write.
            val existing = request.header("Prefer")?.takeIf { it.isNotBlank() }
            builder.header("Prefer", listOfNotNull(existing, STRICT_HANDLING).joinToString(", "))
        }
        gunzip(chain.proceed(builder.build()))
    }

    /**
     * Undoes a compression we asked not to have.
     *
     * Setting `Accept-Encoding` by hand takes OkHttp's transparent gunzip out of
     * the loop — it only decompresses what it asked for itself. ⚠️ A proxy that
     * gzips regardless (a misconfigured nginx, an ISP middlebox) then hands raw
     * deflate bytes to the iCalendar parser, which reports it as an unreadable
     * body and quarantines a perfectly good resource. Honouring the header the
     * response actually carries costs nothing when nobody compressed anything.
     */
    private fun gunzip(response: Response): Response {
        if (!"gzip".equals(response.header("Content-Encoding"), ignoreCase = true)) return response
        val body = response.body ?: return response
        val source = GzipSource(body.source()).buffer()
        return response.newBuilder()
            .removeHeader("Content-Encoding")
            // Dropped with it: the length describes the compressed bytes and is
            // a lie about what the caller now reads.
            .removeHeader("Content-Length")
            .body(source.asResponseBody(body.contentType(), contentLength = -1))
            .build()
    }

    private val WRITE_METHODS = setOf("PUT", "POST", "PATCH")

    private const val STRICT_HANDLING = "handling=strict"
}
