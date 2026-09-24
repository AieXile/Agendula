/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package at.bitfire.dav4jvm

import at.bitfire.dav4jvm.property.CurrentUserPrincipal
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * Covers what Agendula changed while vendoring, and the two upstream defects
 * we were expected to inherit. Everything else in this directory is
 * upstream's own suite, unmodified. See `dav/PROVENANCE.md`.
 */
class LocalChangesTest {

    private val httpClient = OkHttpClient.Builder().followRedirects(false).build()
    private val mockServer = MockWebServer()

    @Before fun startServer() = mockServer.start()
    @After fun stopServer() = mockServer.shutdown()

    private fun redirect(code: Int, to: String) = MockResponse()
        .setResponseCode(code)
        .setHeader("Location", to)

    // -- change 3: dav4jvm#209, permanent redirects never reached the caller ---

    @Test
    fun permanentRedirectIsReportedSoTheCallerCanPersistIt() {
        mockServer.enqueue(redirect(301, "/moved/"))
        mockServer.enqueue(MockResponse().setResponseCode(200))

        val resource = DavResource(httpClient, mockServer.url("/dav/"))
        resource.head { }

        assertEquals(mockServer.url("/moved/"), resource.permanentLocation)
    }

    @Test
    fun a308IsPermanentToo() {
        mockServer.enqueue(redirect(308, "/moved/"))
        mockServer.enqueue(MockResponse().setResponseCode(200))

        val resource = DavResource(httpClient, mockServer.url("/dav/"))
        resource.head { }

        assertEquals(mockServer.url("/moved/"), resource.permanentLocation)
    }

    @Test
    fun aTemporaryRedirectIsNotPersisted() {
        mockServer.enqueue(redirect(302, "/elsewhere/"))
        mockServer.enqueue(MockResponse().setResponseCode(200))

        val resource = DavResource(httpClient, mockServer.url("/dav/"))
        resource.head { }

        assertNull(resource.permanentLocation)
        // The in-flight location still moves, as it must.
        assertEquals(mockServer.url("/elsewhere/"), resource.location)
    }

    @Test
    fun permanentLocationDescribesOnlyTheRequestJustMade() {
        // DavResource objects are reused. A value left over from an earlier
        // request would have the caller persist a URL that a later one replaced,
        // and every sync after that 404s.
        mockServer.enqueue(redirect(301, "/moved/"))
        mockServer.enqueue(MockResponse().setResponseCode(200))
        mockServer.enqueue(MockResponse().setResponseCode(200))

        val resource = DavResource(httpClient, mockServer.url("/dav/"))
        resource.head { }
        assertEquals(mockServer.url("/moved/"), resource.permanentLocation)

        resource.head { }
        assertNull(resource.permanentLocation)
    }

    @Test
    fun aTemporaryHopEndsThePermanentChain() {
        // 301 -> 302 means "the resource moved to /moved/, and /moved/ is being
        // served from /temp/ for now". Persisting /temp/ would be wrong.
        mockServer.enqueue(redirect(301, "/moved/"))
        mockServer.enqueue(redirect(302, "/temp/"))
        mockServer.enqueue(MockResponse().setResponseCode(200))

        val resource = DavResource(httpClient, mockServer.url("/dav/"))
        resource.head { }

        assertEquals(mockServer.url("/moved/"), resource.permanentLocation)
        assertEquals(mockServer.url("/temp/"), resource.location)
    }

    // -- the "303 is not handled" claim, checked rather than assumed -----------

    @Test
    fun a303IsFollowed() {
        // "Handles 301/302/307/308 but not 303" was listed as a defect we would
        // inherit. It does not hold for this version: OkHttp's
        // Response.isRedirect includes HTTP_SEE_OTHER, and followRedirects re-sends
        // the same method, which is what RFC 6764 section 5 asks for in discovery.
        mockServer.enqueue(redirect(303, "/other/"))
        mockServer.enqueue(MockResponse().setResponseCode(200))

        val resource = DavResource(httpClient, mockServer.url("/dav/"))
        resource.head { }

        assertEquals(mockServer.url("/other/"), resource.location)
    }

    // -- change 6: <D:unauthenticated/> is parsed, not inferred ---------------

    private fun principalBody(inner: String) = MockResponse()
        .setResponseCode(207)
        .setHeader("Content-Type", "application/xml; charset=utf-8")
        .setBody(
            """
            <multistatus xmlns="DAV:"><response><href>/dav/</href>
              <propstat><prop><current-user-principal>$inner</current-user-principal></prop>
              <status>HTTP/1.1 200 OK</status></propstat>
            </response></multistatus>
            """.trimIndent(),
        )

    private fun principalOf(inner: String): CurrentUserPrincipal? {
        mockServer.enqueue(principalBody(inner))
        var found: CurrentUserPrincipal? = null
        DavResource(httpClient, mockServer.url("/dav/"))
            .propfind(0, CurrentUserPrincipal.NAME) { response, _ ->
                found = response[CurrentUserPrincipal::class.java]
            }
        return found
    }

    @Test
    fun unauthenticatedIsDistinguishableFromAMerelyEmptyElement() {
        // RFC 5397 §3: a 200 whose body says the request was not authenticated.
        // Upstream parsed only <href>, so this arrived identical to an empty
        // element and to a server that omits the property — and a rejected
        // credential then looks like a discovery that simply found nothing.
        assertTrue(principalOf("<unauthenticated/>")!!.unauthenticated)

        val empty = principalOf("")
        assertNull(empty!!.href)
        assertFalse(empty.unauthenticated)
    }

    @Test
    fun anOrdinaryPrincipalHrefStillParses() {
        val principal = principalOf("<href>/principals/me/</href>")
        assertEquals("/principals/me/", principal!!.href)
        assertFalse(principal.unauthenticated)
    }

    // -- change 1: commons-lang3 removed from date parsing --------------------

    /**
     * Runs [block] with a deliberately non-UTC default zone.
     *
     * The defect change 2 fixes *is* "the formatter used the default zone", so a
     * test that does not move the default zone passes identically before and
     * after the fix — and CI containers default to UTC. Without this the whole
     * change is pinned by nothing.
     */
    private fun <T> inNonUtcZone(block: () -> T): T {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))   // UTC+05:30
        try {
            return block()
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun formatDateEmitsGmtRatherThanLocalTimeLabelledGmt() = inNonUtcZone {
        assertEquals(
            "Wed, 21 Oct 2015 07:28:00 GMT",
            HttpUtils.formatDate(java.util.Date(1445412480000L)),
        )
    }

    @Test
    fun parseDateAcceptsTheRfc7231Format() = inNonUtcZone {
        assertEquals(1445412480000L, HttpUtils.parseDate("Wed, 21 Oct 2015 07:28:00 GMT")!!.time)
    }

    @Test
    fun parseDateHonoursAnExplicitOffsetInsteadOfMatchingGmtAsAPrefix() {
        // Pattern 1 ends in the *literal* 'GMT', and SimpleDateFormat.parse is
        // happy with a prefix — so without a full-consumption check the "+02:00"
        // is discarded and the result is two hours out, which is the same class of
        // error change 2 set out to remove.
        assertEquals(1445405280000L, HttpUtils.parseDate("Wed, 21 Oct 2015 07:28:00 GMT+02:00")!!.time)
        assertEquals(1445430480000L, HttpUtils.parseDate("Wed, 21 Oct 2015 07:28:00 GMT-05:00")!!.time)
    }

    @Test
    fun parseDateRejectsTrailingGarbageRatherThanMatchingAPrefix() {
        assertNull(HttpUtils.parseDate("Wed, 21 Oct 2015 07:28:00 GMT and then some"))
    }

    @Test
    fun parseDateAcceptsTheObsoleteFormats() {
        // RFC 850, and ANSI C asctime() — both still emitted in the wild.
        assertTrue(HttpUtils.parseDate("Wednesday, 21-Oct-15 07:28:00 GMT") != null)
        assertTrue(HttpUtils.parseDate("Wed Oct 21 07:28:00 2015") != null)
    }

    @Test
    fun parseDateReturnsNullRatherThanThrowing() {
        assertNull(HttpUtils.parseDate("not a date at all"))
    }

    @Test
    fun parseDateIsIndependentOfTheDefaultLocale() {
        // SimpleDateFormat defaults to the platform locale, which would reject
        // English month names on a Turkish or German device.
        val locale = Locale.getDefault()
        val zone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Istanbul"))
            assertEquals(1445412480000L, HttpUtils.parseDate("Wed, 21 Oct 2015 07:28:00 GMT")!!.time)
        } finally {
            Locale.setDefault(locale)
            TimeZone.setDefault(zone)
        }
    }

    // --- change 7: a same-host downgrade is upgraded, a cross-host one is not ---

    /**
     * A TLS MockWebServer, because the branch under test only exists on HTTPS.
     *
     * Same trap as change 2's: a test that runs against plain HTTP here would
     * pass whatever the code did, since `location.isHttps` gates the whole
     * decision.
     */
    private fun httpsServer(): Pair<MockWebServer, OkHttpClient> {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()

        val tlsServer = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .sslSocketFactory(
                clientCertificates.sslSocketFactory(),
                clientCertificates.trustManager,
            )
            .build()
        return tlsServer to client
    }

    @Test
    fun `a same-host redirect to http is retried over https`() {
        // ⚠️ Exactly what a Nextcloud behind a proxy without `overwriteprotocol`
        // sends for /.well-known/caldav. Refusing it makes a working CalDAV
        // server report as "not a CalDAV server".
        val (tls, client) = httpsServer()
        val resource = DavResource(client, tls.url("/.well-known/caldav"))
        val downgrade = "http://${tls.hostName}:${tls.port}/remote.php/dav/"

        tls.enqueue(MockResponse().setResponseCode(301).setHeader("Location", downgrade))
        tls.enqueue(MockResponse().setResponseCode(200))

        resource.head { }

        tls.takeRequest()
        val followed = tls.takeRequest()
        assertEquals("/remote.php/dav/", followed.path)
        // The scheme was put back; the host and path are the server's own.
        assertEquals("https", resource.location.scheme)
        assertEquals("/remote.php/dav/", resource.location.encodedPath)
    }

    @Test
    fun `a cross-host redirect to http is still refused`() {
        val (tls, client) = httpsServer()
        val resource = DavResource(client, tls.url("/dav/"))
        tls.enqueue(
            MockResponse().setResponseCode(301)
                .setHeader("Location", "http://elsewhere.example.com/dav/"),
        )

        // No innocent reading of this one, so it stays fatal.
        assertThrows(at.bitfire.dav4jvm.exception.DavException::class.java) { resource.head { } }
    }

    // --- change 10: the Digest challenge beside a Basic one is read ----------

    @Test
    fun `a Basic challenge no longer hides the Digest one in the same header`() {
        val handler = BasicDigestAuthHandler("example.com", "user", "pw")
        val request = okhttp3.Request.Builder().url("https://cloud.example.com/dav/").build()
        // The interceptor primes Basic preemptively over HTTPS, so by the time
        // the first 401 arrives a Basic challenge is already cached — which is
        // what made the abort fire on every server that offers both.
        handler.authenticateRequest(request, null)

        val challenged = handler.authenticateRequest(
            request,
            run {
                okhttp3.Response.Builder()
                    .request(request)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(401)
                    .message("Unauthorized")
                    .addHeader("WWW-Authenticate", "Basic realm=\"dav\"")
                    .addHeader(
                        "WWW-Authenticate",
                        "Digest realm=\"dav\", nonce=\"abc\", qop=\"auth\"",
                    )
                    .build()
            },
        )

        assertTrue(challenged!!.header("Authorization")!!.startsWith("Digest"))
    }

    // --- change 11: the digest counter is per handler and per nonce ----------

    @Test
    fun `the nonce count restarts when the server issues a new nonce`() {
        val handler = BasicDigestAuthHandler(null, "user", "pw")
        val request = okhttp3.Request.Builder().url("https://example.com/dav/").build()
        fun answer(nonce: String) = handler.digestRequest(
            request,
            okhttp3.Challenge(
                "Digest",
                mapOf<String?, String>("realm" to "dav", "nonce" to nonce, "qop" to "auth"),
            ),
        )!!.header("Authorization")!!

        assertTrue(answer("one").contains("nc=00000001"))
        assertTrue(answer("one").contains("nc=00000002"))
        // RFC 7616 3.4.1 counts requests sent with *that* nonce.
        assertTrue(answer("two").contains("nc=00000001"))
    }

    @Test
    fun `two accounts do not interleave their nonce counts`() {
        val request = okhttp3.Request.Builder().url("https://example.com/dav/").build()
        val challenge = okhttp3.Challenge(
            "Digest",
            mapOf<String?, String>("realm" to "dav", "nonce" to "n", "qop" to "auth"),
        )
        val first = BasicDigestAuthHandler(null, "one", "pw")
        val second = BasicDigestAuthHandler(null, "two", "pw")

        first.digestRequest(request, challenge)
        val theirs = second.digestRequest(request, challenge)!!.header("Authorization")!!

        assertTrue(theirs.contains("nc=00000001"))
    }

    // --- change 12: qop list values are trimmed -----------------------------

    @Test
    fun `a spaced qop list still selects auth-int`() {
        val handler = BasicDigestAuthHandler(null, "user", "pw")
        val request = okhttp3.Request.Builder().url("https://example.com/dav/").build()
        val header = handler.digestRequest(
            request,
            okhttp3.Challenge(
                "Digest",
                mapOf<String?, String>(
                    "realm" to "dav",
                    "nonce" to "n",
                    "qop" to "auth, auth-int",
                ),
            ),
        )!!.header("Authorization")!!

        assertTrue(header.contains("qop=auth-int"))
    }

    @Test
    fun `a single spaced qop value does not fall back to RFC 2069`() {
        val handler = BasicDigestAuthHandler(null, "user", "pw")
        val request = okhttp3.Request.Builder().url("https://example.com/dav/").build()
        val header = handler.digestRequest(
            request,
            okhttp3.Challenge(
                "Digest",
                mapOf<String?, String>("realm" to "dav", "nonce" to "n", "qop" to " auth"),
            ),
        )!!.header("Authorization")!!

        // The legacy branch emits no qop, nc or cnonce, which an RFC 7616 server
        // rejects outright — a permanent 401 against a server behaving legally.
        assertTrue(header.contains("qop=auth"))
        assertTrue(header.contains("nc="))
        assertTrue(header.contains("cnonce="))
    }
}
