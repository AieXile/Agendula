package com.aiexile.caldav

import at.bitfire.dav4jvm.BasicDigestAuthHandler
import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Test

class CalDavHttpTest {

    private val origin = "https://cloud.example.com/remote.php/dav/".toHttpUrl()

    @Test
    fun `the auth handler is scoped to the registrable domain, not the host`() {
        // ⚠️ The handler derives the registrable domain of each request host and
        // compares. Passing the full host means the comparison is
        // "cloud.example.com" == "example.com" — never true — and the credential
        // is withheld from every single request. That is every self-hosted
        // Nextcloud, silently answering 401 forever.
        val client = CalDavHttp.authenticated("Agendula", "user", "pw", origin)
        val handler = client.networkInterceptors.filterIsInstance<BasicDigestAuthHandler>().single()
        assertThat(handler.domain).isEqualTo("example.com")
    }

    @Test
    fun `so the credential actually reaches the host it was made for`() {
        val client = CalDavHttp.authenticated("Agendula", "user", "pw", origin)
        val handler = client.networkInterceptors.filterIsInstance<BasicDigestAuthHandler>().single()

        val authorised = handler.authenticateRequest(
            Request.Builder().url("https://cloud.example.com/remote.php/dav/").build(),
            null,
        )
        assertThat(authorised?.header("Authorization")).isNotNull()
    }

    @Test
    fun `and reaches a sibling host in the same domain, which is what iCloud needs`() {
        // The principal is on caldav.icloud.com and the home set on
        // pNN-caldav.icloud.com. An exact-host allowlist refuses the second.
        val client = CalDavHttp.authenticated(
            "Agendula",
            "user",
            "pw",
            "https://caldav.icloud.com/".toHttpUrl(),
        )
        val handler = client.networkInterceptors.filterIsInstance<BasicDigestAuthHandler>().single()

        val authorised = handler.authenticateRequest(
            Request.Builder().url("https://p42-caldav.icloud.com/1234/calendars/").build(),
            null,
        )
        assertThat(authorised?.header("Authorization")).isNotNull()
    }

    @Test
    fun `a multi-label public suffix is not mistaken for a domain`() {
        // ⚠️ A last-two-labels split scopes this to "co.uk" and then offers the
        // password preemptively to any host under it — one an attacker can buy a
        // certificate for. This is the headline case.
        val client = CalDavHttp.authenticated(
            "Agendula",
            "user",
            "pw",
            "https://cloud.example.co.uk/dav/".toHttpUrl(),
        )
        val handler = client.networkInterceptors.filterIsInstance<BasicDigestAuthHandler>().single()
        assertThat(handler.domain).isEqualTo("example.co.uk")

        val stranger = handler.authenticateRequest(
            Request.Builder().url("https://attacker.co.uk/dav/").build(),
            null,
        )
        assertThat(stranger).isNull()

        val ours = handler.authenticateRequest(
            Request.Builder().url("https://cloud.example.co.uk/dav/").build(),
            null,
        )
        assertThat(ours?.header("Authorization")).isNotNull()
    }

    @Test
    fun `a free-subdomain host does not trust its neighbours`() {
        // The PSL private section covers the dynamic-DNS providers self-hosters
        // actually use, where the neighbour is a stranger with a free account.
        val client = CalDavHttp.authenticated(
            "Agendula",
            "user",
            "pw",
            "https://myhome.duckdns.org/dav/".toHttpUrl(),
        )
        val handler = client.networkInterceptors.filterIsInstance<BasicDigestAuthHandler>().single()
        assertThat(handler.domain).isEqualTo("myhome.duckdns.org")

        val neighbour = handler.authenticateRequest(
            Request.Builder().url("https://evil.duckdns.org/dav/").build(),
            null,
        )
        assertThat(neighbour).isNull()
    }

    @Test
    fun `a bare address is scoped to itself, not to its last two labels`() {
        // ⚠️ topPrivateDomain() is null here, and null means *no restriction* to
        // the handler — so the fallback has to be the exact host. A split would
        // scope 192.168.1.10 to "1.10".
        val client = CalDavHttp.authenticated(
            "Agendula",
            "user",
            "pw",
            "https://192.168.1.10/dav/".toHttpUrl(),
        )
        val handler = client.networkInterceptors.filterIsInstance<BasicDigestAuthHandler>().single()
        assertThat(handler.domain).isEqualTo("192.168.1.10")

        val ours = handler.authenticateRequest(
            Request.Builder().url("https://192.168.1.10/dav/").build(),
            null,
        )
        assertThat(ours?.header("Authorization")).isNotNull()

        val other = handler.authenticateRequest(
            Request.Builder().url("https://10.20.1.10/dav/").build(),
            null,
        )
        assertThat(other).isNull()
    }

    @Test
    fun `a single-label host is scoped to itself`() {
        val client = CalDavHttp.authenticated(
            "Agendula",
            "user",
            "pw",
            "https://localhost:8443/dav/".toHttpUrl(),
        )
        val handler = client.networkInterceptors.filterIsInstance<BasicDigestAuthHandler>().single()
        assertThat(handler.domain).isEqualTo("localhost")

        val elsewhere = handler.authenticateRequest(
            Request.Builder().url("https://notlocalhost/dav/").build(),
            null,
        )
        assertThat(elsewhere).isNull()
    }

    @Test
    fun `an unrelated domain gets nothing`() {
        val client = CalDavHttp.authenticated("Agendula", "user", "pw", origin)
        val handler = client.networkInterceptors.filterIsInstance<BasicDigestAuthHandler>().single()

        assertThat(
            handler.authenticateRequest(
                Request.Builder().url("https://evil.example.org/dav/").build(),
                null,
            ),
        ).isNull()
    }

    @Test
    fun `cleartext never carries a preemptive credential`() {
        val client = CalDavHttp.authenticated("Agendula", "user", "pw", origin)
        val handler = client.networkInterceptors.filterIsInstance<BasicDigestAuthHandler>().single()

        assertThat(
            handler.authenticateRequest(
                Request.Builder().url("http://cloud.example.com/dav/").build(),
                null,
            )?.header("Authorization"),
        ).isNull()
    }

    @Test
    fun `cleartext carries no credential even when the server asks for one`() {
        val client = CalDavHttp.authenticated("Agendula", "user", "pw", origin)
        val handler = client.networkInterceptors.filterIsInstance<BasicDigestAuthHandler>().single()
        val request = Request.Builder().url("http://cloud.example.com/dav/").build()

        // ⚠️ Gating only the preemptive path leaves this open: the server just
        // has to ask. Basic is the password, in a header every hop can read.
        assertThat(handler.authenticateRequest(request, challenge(request, "Basic"))).isNull()
    }

    @Test
    fun `a challenge over TLS is still answered`() {
        val client = CalDavHttp.authenticated("Agendula", "user", "pw", origin)
        val handler = client.networkInterceptors.filterIsInstance<BasicDigestAuthHandler>().single()
        val request = Request.Builder().url("https://cloud.example.com/dav/").build()

        val authorised = handler.authenticateRequest(request, challenge(request, "Basic"))
        assertThat(authorised?.header("Authorization")).startsWith("Basic")
    }

    private fun challenge(request: Request, scheme: String) = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(401)
        .message("Authentication required")
        .header("WWW-Authenticate", "$scheme realm=\"dav\"")
        .build()

    @Test
    fun `the shared client bounds a whole call, not just each read`() {
        // ⚠️ readTimeout is per-read, so a server trickling a byte every 119
        // seconds satisfies it for ever and holds a sequential sync for the
        // entire WorkManager window. The ceiling is wide enough for a multiget
        // of a full batch over a slow homelab link and far below that window;
        // the revocation, which must return in seconds, still sets its own.
        assertThat(CalDavHttp.anonymous("Agendula").callTimeoutMillis)
            .isEqualTo(3 * 60 * 1000)
    }

    @Test
    fun `derived clients share one connection pool`() {
        // A fresh OkHttpClient per probe gives each its own pool and dispatcher
        // threads: every rung of the discovery ladder reopens TLS, and the
        // abandoned clients linger until GC.
        val a = CalDavHttp.anonymous("Agendula")
        val b = CalDavHttp.authenticated("Agendula", "user", "pw", origin)
        assertThat(a.connectionPool).isSameInstanceAs(b.connectionPool)
        assertThat(a.dispatcher).isSameInstanceAs(b.dispatcher)
    }

    @Test
    fun `redirects are never followed automatically`() {
        // DavResource requires it and asserts on it: redirects are followed by
        // hand so a HTTPS-to-HTTP downgrade can be refused and a permanent move
        // reported.
        assertThat(CalDavHttp.anonymous("Agendula").followRedirects).isFalse()
        assertThat(CalDavHttp.authenticated("Agendula", "u", "p", origin).followRedirects).isFalse()
    }
}
