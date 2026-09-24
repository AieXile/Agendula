package de.jeanlucmakiola.caldav

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class NextcloudLoginFlowTest {

    private val server = MockWebServer()
    private lateinit var flow: NextcloudLoginFlow

    @Before fun start() {
        server.start()
        flow = NextcloudLoginFlow(OkHttpClient(), userAgent = "Agendula/1.0 (Pixel 8)")
    }

    @After fun stop() = server.shutdown()

    private fun json(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun startFlow(): NextcloudLoginFlow.Flow {
        server.enqueue(
            json(
                """
                {"poll":{"token":"tok-123","endpoint":"${server.url("/index.php/login/v2/poll")}"},
                 "login":"${server.url("/index.php/login/v2/flow/abc")}"}
                """.trimIndent(),
            ),
        )
        return flow.start(server.url("/"), now = 0).getOrThrow()
    }

    /** As production builds it: DavResource requires no automatic redirects. */
    private fun noRedirectFlow() = NextcloudLoginFlow(
        OkHttpClient.Builder().followRedirects(false).build(),
        userAgent = "Agendula/1.0 (Pixel 8)",
    )

    @Test
    fun `a redirected init is followed, and stays a POST`() {
        // ⚠️ Apex to www, a trailing slash, a proxy — Nextcloud canonicalises
        // with a 301 and the client cannot follow on its own. The method has to
        // survive: this route answers 405 to a GET.
        val redirecting = noRedirectFlow()
        server.enqueue(
            MockResponse().setResponseCode(301)
                .setHeader("Location", server.url("/nc/index.php/login/v2").toString()),
        )
        server.enqueue(
            json(
                """
                {"poll":{"token":"tok-123","endpoint":"${server.url("/nc/index.php/login/v2/poll")}"},
                 "login":"${server.url("/nc/index.php/login/v2/flow/abc")}"}
                """.trimIndent(),
            ),
        )

        val started = redirecting.start(server.url("/"), now = 0).getOrThrow()

        assertThat(started.pollToken).isEqualTo("tok-123")
        server.takeRequest()
        val followed = server.takeRequest()
        assertThat(followed.path).isEqualTo("/nc/index.php/login/v2")
        assertThat(followed.method).isEqualTo("POST")
    }

    @Test
    fun `a redirected poll is followed rather than read as a server error`() {
        // ⚠️ After approval. Reporting SERVER_ERROR here abandons a password the
        // server has already minted and can never hand back again.
        val redirecting = noRedirectFlow()
        val started = NextcloudLoginFlow.Flow(
            loginUrl = server.url("/index.php/login/v2/flow/abc"),
            pollEndpoint = server.url("/index.php/login/v2/poll"),
            pollToken = "tok-123",
            deadlineEpochSeconds = 1_200,
        )
        server.enqueue(
            MockResponse().setResponseCode(308)
                .setHeader("Location", server.url("/nc/login/v2/poll").toString()),
        )
        server.enqueue(
            json("""{"server":"${server.url("/")}","loginName":"me","appPassword":"secret-app-pw"}"""),
        )

        val result = redirecting.poll(started, now = 1)

        assertThat(result).isInstanceOf(NextcloudLoginFlow.PollResult.Approved::class.java)
        assertThat((result as NextcloudLoginFlow.PollResult.Approved).credentials.appPassword)
            .isEqualTo("secret-app-pw")
        server.takeRequest()
        val followed = server.takeRequest()
        assertThat(followed.method).isEqualTo("POST")
        assertThat(followed.body.readUtf8()).contains("token=tok-123")
    }

    @Test
    fun `init posts, and carries a User-Agent the user can recognise`() {
        startFlow()
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        // The User-Agent becomes the app password's *name* in Settings → Security
        // → Devices & sessions. With OkHttp's default the user sees
        // "okhttp/4.12.0" and cannot tell what to revoke.
        assertThat(request.getHeader("User-Agent")).isEqualTo("Agendula/1.0 (Pixel 8)")
    }

    @Test
    fun `polling is a POST - a GET gets 405`() {
        val started = startFlow()
        server.enqueue(MockResponse().setResponseCode(404))
        flow.poll(started, now = 1)

        server.takeRequest()  // the init request
        val poll = server.takeRequest()
        assertThat(poll.method).isEqualTo("POST")
        assertThat(poll.body.readUtf8()).contains("token=tok-123")
    }

    @Test
    fun `404 means pending`() {
        val started = startFlow()
        server.enqueue(MockResponse().setResponseCode(404))
        assertThat(flow.poll(started, now = 1)).isEqualTo(NextcloudLoginFlow.PollResult.Pending)
    }

    @Test
    fun `approval yields the app password, and loginName is kept as a username only`() {
        val started = startFlow()
        server.enqueue(
            json("""{"server":"${server.url("/")}","loginName":"Me@Example.COM","appPassword":"secret-app-pw"}"""),
        )
        val result = flow.poll(started, now = 1)
        assertThat(result).isInstanceOf(NextcloudLoginFlow.PollResult.Approved::class.java)
        val credentials = (result as NextcloudLoginFlow.PollResult.Approved).credentials
        assertThat(credentials.loginName).isEqualTo("Me@Example.COM")
        assertThat(credentials.appPassword).isEqualTo("secret-app-pw")
    }

    @Test
    fun `an unreachable claimed origin is replaced, and reported`() {
        val started = startFlow()
        server.enqueue(
            json("""{"server":"http://nextcloud:11000/","loginName":"me","appPassword":"pw"}"""),
        )

        val result = flow.poll(started, now = 1) as NextcloudLoginFlow.PollResult.Approved

        // We talk to the host that just answered us, not the one it named.
        assertThat(result.credentials.server.host).isEqualTo(server.hostName)
        // And the user is told which setting is wrong, rather than being sent
        // away with "no task lists" for what is a DNS failure.
        assertThat(result.hostMismatch?.actual).isEqualTo("nextcloud")
    }

    @Test
    fun `429 is rate limiting, not pending`() {
        // "Anything that isn't 200 is pending" turns Nextcloud's brute-force
        // protection into a twenty-minute spinner.
        val started = startFlow()
        server.enqueue(MockResponse().setResponseCode(429))
        assertThat(flow.poll(started, now = 1))
            .isInstanceOf(NextcloudLoginFlow.PollResult.Failed::class.java)
    }

    @Test
    fun `503 is maintenance, not pending`() {
        val started = startFlow()
        server.enqueue(MockResponse().setResponseCode(503))
        assertThat(flow.poll(started, now = 1))
            .isInstanceOf(NextcloudLoginFlow.PollResult.Failed::class.java)
    }

    @Test
    fun `a 200 that is not JSON is a captive portal, not an approval`() {
        // A Cloudflare challenge is a 200 carrying HTML.
        val started = startFlow()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html>Checking your browser…</html>"),
        )
        assertThat(flow.poll(started, now = 1))
            .isInstanceOf(NextcloudLoginFlow.PollResult.Failed::class.java)
    }

    @Test
    fun `the deadline is tracked locally, because 404 also means expired`() {
        val started = startFlow()
        assertThat(started.deadlineEpochSeconds).isEqualTo(1200L)
        // No response enqueued: an expired flow must not even reach the network.
        assertThat(flow.poll(started, now = 1201))
            .isInstanceOf(NextcloudLoginFlow.PollResult.Expired::class.java)
    }

    @Test
    fun `a URL that downgrades to http is refused`() {
        // The poll token is exchanged for a long-lived app password, and the login
        // URL takes the account password — both are credential-grade.
        val https = "https://cloud.example.com/".toHttpUrl()
        val http = "http://cloud.example.com/poll".toHttpUrl()
        val failure = runCatching { flow.requireSecureOrigin(https, http) }.exceptionOrNull()
        assertThat(failure).isNotNull()
        assertThat(failure!!.message).contains("http://")
    }

    @Test
    fun `the browser login URL gets the same scheme check as the poll endpoint`() {
        // It is where the user types their *account* password, so leaving it
        // unchecked is the more dangerous of the two omissions. `start` applies
        // requireSecureOrigin to both; this asserts the rule itself, because
        // reaching the branch over the wire would need a TLS MockWebServer and a
        // test that merely fails to connect would pass for the wrong reason.
        val typed = "https://cloud.example.com/".toHttpUrl()
        val harvester = "http://evil.example.com/harvest".toHttpUrl()
        val failure = runCatching { flow.requireSecureOrigin(typed, harvester) }.exceptionOrNull()
        assertThat(failure).isNotNull()
        assertThat(failure!!.message).contains("evil.example.com")
    }

    @Test
    fun `a host change is carried, not refused - reverse proxies are legitimate`() {
        // overwrite.cli.url pointing somewhere other than what the user typed is
        // ordinary on self-hosted installs. Throwing would make the flow unusable
        // for them; the UI confirms it instead.
        server.enqueue(
            json(
                """
                {"poll":{"token":"t","endpoint":"https://cloud.example.com/poll"},
                 "login":"https://cloud.example.com/flow"}
                """.trimIndent(),
            ),
        )
        val started = flow.start(server.url("/"), now = 0)
        assertThat(started.isSuccess).isTrue()
        val mismatch = started.getOrThrow().hostMismatch
        assertThat(mismatch).isNotNull()
        assertThat(mismatch!!.actual).isEqualTo("cloud.example.com")
        assertThat(mismatch.message).contains("overwrite.cli.url")
    }

    @Test fun `a downgraded server URL in the poll response is coerced, not refused`() {
        // ⚠️ The credential has already been issued and the 200 comes exactly
        // once — the row is deleted inside poll() before it answers. Throwing
        // here destroys a live app password and leaves it dangling in the user's
        // device list, for no protection at all.
        val flow = NextcloudLoginFlow(OkHttpClient(), "test")
        val expected = "https://cloud.example.com/login/v2/poll".toHttpUrl()
        val downgraded = "http://cloud.example.com".toHttpUrl()

        val coerced = flow.secureOrigin(expected, downgraded)

        assertThat(coerced.scheme).isEqualTo("https")
        assertThat(coerced.host).isEqualTo("cloud.example.com")
    }

    @Test fun `a cleartext port does not survive the coercion`() {
        val flow = NextcloudLoginFlow(OkHttpClient(), "test")
        val expected = "https://cloud.example.com/login/v2/poll".toHttpUrl()
        val downgraded = "http://cloud.example.com:8080/".toHttpUrl()

        // ⚠️ OkHttp only drops a *default* port across a scheme change, so
        // :8080 would be carried into https — a port that almost certainly
        // speaks cleartext, turning the promised coercion into a handshake
        // failure. The port that answered the poll is the one known to work.
        val coerced = flow.secureOrigin(expected, downgraded)

        assertThat(coerced.scheme).isEqualTo("https")
        assertThat(coerced.port).isEqualTo(443)
    }

    @Test fun `an already-secure server URL is left alone`() {
        val flow = NextcloudLoginFlow(OkHttpClient(), "test")
        val expected = "https://cloud.example.com/login/v2/poll".toHttpUrl()
        val actual = "https://dav.example.com".toHttpUrl()

        // secureOrigin keeps its narrow job: the scheme. Which origin we then
        // actually talk to is reachableOrigin's decision.
        assertThat(flow.secureOrigin(expected, actual)).isEqualTo(actual)
    }

    @Test fun `an origin outside the polled domain is replaced by the one that answered`() {
        val flow = NextcloudLoginFlow(OkHttpClient(), "test")
        val expected = "https://cloud.example.com/index.php/login/v2/poll".toHttpUrl()

        // ⚠️ The ordinary reverse-proxied install: the poll endpoint comes from
        // overwrite.cli.url and is right, while `server` is built from the
        // approving request's own Host header and is an internal name the phone
        // cannot resolve. Following it strands the user with a spent password.
        val reachable = flow.reachableOrigin(expected, "http://nextcloud:11000/".toHttpUrl())

        assertThat(reachable.host).isEqualTo("cloud.example.com")
        assertThat(reachable.isHttps).isTrue()
        assertThat(reachable.encodedPath).isEqualTo("/")
    }

    @Test fun `a sibling host is used verbatim and not reported as a mismatch`() {
        val flow = NextcloudLoginFlow(OkHttpClient(), "test")
        val polled = "https://cloud.example.com/index.php/login/v2/poll".toHttpUrl()
        val claimed = "https://nc.example.com/".toHttpUrl()

        // ⚠️ hostMismatchOf compares hosts exactly; reachableOrigin substitutes
        // only across registrable domains. Reporting the raw comparison tells the
        // user we replaced an address we in fact used, and blames a correct
        // setting — stickily, for the rest of the flow.
        val used = flow.reachableOrigin(polled, claimed)
        assertThat(used).isEqualTo(claimed)
        assertThat(flow.substitutedMismatch(polled, claimed, used)).isNull()
    }

    @Test fun `a substituted host is reported`() {
        val flow = NextcloudLoginFlow(OkHttpClient(), "test")
        val polled = "https://cloud.example.com/index.php/login/v2/poll".toHttpUrl()
        val claimed = "http://nextcloud:11000/".toHttpUrl()

        val used = flow.reachableOrigin(polled, claimed)
        assertThat(flow.substitutedMismatch(polled, claimed, used)?.actual).isEqualTo("nextcloud")
    }

    @Test fun `a subdirectory install without index_php keeps its prefix`() {
        val flow = NextcloudLoginFlow(OkHttpClient(), "test")
        // htaccess.IgnoreFrontController drops index.php from generated routes.
        val expected = "https://cloud.example.com/nc/login/v2/poll".toHttpUrl()

        val reachable = flow.reachableOrigin(expected, "http://nextcloud:11000/".toHttpUrl())

        assertThat(reachable.encodedPath).isEqualTo("/nc/")
    }

    @Test fun `a sibling host in the same domain is still honoured`() {
        val flow = NextcloudLoginFlow(OkHttpClient(), "test")
        val expected = "https://cloud.example.com/index.php/login/v2/poll".toHttpUrl()
        val actual = "https://dav.example.com/".toHttpUrl()

        // A real deployment shape, and the credential is scoped to that
        // registrable domain anyway.
        assertThat(flow.reachableOrigin(expected, actual)).isEqualTo(actual)
    }

    @Test fun `a subdirectory install keeps its prefix when the host is replaced`() {
        val flow = NextcloudLoginFlow(OkHttpClient(), "test")
        val expected =
            "https://cloud.example.com/nextcloud/index.php/login/v2/poll".toHttpUrl()

        // ⚠️ The container's own webroot is empty, so the claimed URL carries no
        // prefix at all. Keeping the claimed path alongside the polled host would
        // drop /nextcloud and send discovery to the wrong base — with the app
        // password already spent.
        val reachable = flow.reachableOrigin(expected, "http://nextcloud:11000/".toHttpUrl())

        assertThat(reachable.host).isEqualTo("cloud.example.com")
        assertThat(reachable.encodedPath).isEqualTo("/nextcloud/")
    }

    @Test fun `a webroot install is rebuilt at the root`() {
        val flow = NextcloudLoginFlow(OkHttpClient(), "test")
        val expected = "https://cloud.example.com/index.php/login/v2/poll".toHttpUrl()

        val reachable = flow.reachableOrigin(expected, "http://nextcloud:11000/whatever".toHttpUrl())

        // The claimed path goes with the claimed host: both came from the
        // generator we just decided not to trust.
        assertThat(reachable.encodedPath).isEqualTo("/")
    }

    @Test fun `a single-label host is compared exactly, not by a null domain`() {
        val flow = NextcloudLoginFlow(OkHttpClient(), "test")
        val expected = "https://localhost:8443/index.php/login/v2/poll".toHttpUrl()

        // topPrivateDomain() is null for both sides here; falling back to the
        // exact host is what keeps a homelab install working.
        assertThat(flow.reachableOrigin(expected, "https://localhost:8443/".toHttpUrl()).host)
            .isEqualTo("localhost")
        assertThat(flow.reachableOrigin(expected, "https://elsewhere/".toHttpUrl()).host)
            .isEqualTo("localhost")
    }

    @Test fun `the login URL is still refused outright when downgraded`() {
        // Before approval there is nothing to lose by refusing, and the login URL
        // is where the *account* password gets typed.
        val flow = NextcloudLoginFlow(OkHttpClient(), "test")
        val failure = runCatching {
            flow.requireSecureOrigin(
                "https://cloud.example.com".toHttpUrl(),
                "http://cloud.example.com/login".toHttpUrl(),
            )
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
    }
}
