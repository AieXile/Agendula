package de.jeanlucmakiola.caldav

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class AppPasswordTest {

    private val server = MockWebServer()
    private val httpClient = OkHttpClient()

    @Before fun start() = server.start()

    @After fun stop() = server.shutdown()

    @Test fun `a redirected revocation is followed`() {
        // Production builds its clients with followRedirects(false), so a server
        // that canonicalises host or path leaves the password dangling —
        // attempted, and doing nothing.
        val noRedirects = OkHttpClient.Builder().followRedirects(false).build()
        server.enqueue(
            MockResponse().setResponseCode(301)
                .setHeader("Location", server.url("/nc/ocs/v2.php/core/apppassword").toString()),
        )
        server.enqueue(MockResponse().setResponseCode(200))

        val revoked = AppPassword.revokeAt(noRedirects, server.url("/"))

        assertThat(revoked).isTrue()
        server.takeRequest()
        val followed = server.takeRequest()
        assertThat(followed.method).isEqualTo("DELETE")
        assertThat(followed.getHeader("OCS-APIRequest")).isEqualTo("true")
    }

    @Test fun `the OCS root is derived from a principal, not appended to it`() {
        // ⚠️ Appending to the principal produces a path that 404s on every
        // server, silently — the revocation looks attempted and does nothing.
        val principal = "https://cloud.example.com/remote.php/dav/principals/users/alice/"
        assertThat(AppPassword.ocsRootFor(principal.toHttpUrl()).toString())
            .isEqualTo("https://cloud.example.com/")
    }

    @Test fun `a subpath install keeps its subpath`() {
        val principal = "https://host/nextcloud/remote.php/dav/principals/users/alice/"
        assertThat(AppPassword.ocsRootFor(principal.toHttpUrl()).toString())
            .isEqualTo("https://host/nextcloud/")
    }

    @Test fun `a principal with no remote_php falls back to the origin`() {
        val principal = "https://baikal.example.com/dav.php/principals/alice/"
        assertThat(AppPassword.ocsRootFor(principal.toHttpUrl()).toString())
            .isEqualTo("https://baikal.example.com/")
    }

    @Test fun `a server root is used verbatim, not treated as a principal`() {
        server.enqueue(MockResponse().setResponseCode(200))

        val revoked = AppPassword.revokeAt(httpClient, server.url("/nextcloud/"))

        // ⚠️ ocsRootFor looks for remote.php and falls back to the bare origin.
        // A login flow hands back a server base, so routing one through revoke()
        // would collapse /nextcloud/ to / and DELETE a path that 404s on every
        // subpath install — attempted, and doing nothing.
        assertThat(revoked).isTrue()
        assertThat(server.takeRequest().path)
            .isEqualTo("/nextcloud/ocs/v2.php/core/apppassword")
    }

    @Test fun `a server that never answers does not hold the removal`() {
        // Accepts the connection and says nothing — the off-VPN homelab shape.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val startedAt = System.nanoTime()
        val revoked = AppPassword.revoke(
            httpClient,
            server.url("/remote.php/dav/principals/users/alice/"),
            timeout = 200.milliseconds,
        )
        val elapsed = (System.nanoTime() - startedAt).nanoseconds

        // ⚠️ The budget has to live on the call. execute() parks on a socket read
        // that neither coroutine cancellation nor Thread.interrupt can break, so
        // a timeout imposed from outside returns only once the read has finished
        // anyway — after the shared client's own minutes-long budget.
        assertThat(revoked).isFalse()
        assertThat(elapsed).isLessThan(2.seconds)
    }

    @Test fun `the default budget is what the removal is willing to wait`() {
        assertThat(AppPassword.REVOCATION_TIMEOUT).isEqualTo(5.seconds)
    }

    @Test fun `the revocation is a DELETE with the OCS header`() {
        server.enqueue(MockResponse().setResponseCode(200))

        val revoked = AppPassword.revoke(
            httpClient,
            server.url("/remote.php/dav/principals/users/alice/"),
        )

        assertThat(revoked).isTrue()
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("DELETE")
        assertThat(request.path).isEqualTo("/ocs/v2.php/core/apppassword")
        // ⚠️ Without this header Nextcloud answers with a CSRF complaint that
        // reads exactly like a wrong password.
        assertThat(request.getHeader("OCS-APIRequest")).isEqualTo("true")
    }

    @Test fun `an unreachable server is reported, not thrown`() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertThat(AppPassword.revoke(httpClient, server.url("/remote.php/dav/"))).isFalse()
    }
}
