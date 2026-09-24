package de.jeanlucmakiola.caldav

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** RFC 6578. Every case here is a documented failure of a real client. */
class CalendarChangesTest {

    private val server = MockWebServer()
    private val httpClient = OkHttpClient.Builder().followRedirects(false).build()
    private lateinit var collection: CalendarCollection

    @Before fun start() {
        server.start()
        collection = CalendarCollection(httpClient, server.url("/dav/tasks/"))
    }

    @After fun stop() = server.shutdown()

    @Test fun `changed and removed are told apart`() {
        server.enqueue(
            multistatus(
                """
                <response>
                  <href>/dav/tasks/kept.ics</href>
                  <propstat><prop><getetag>"e1"</getetag></prop>
                  <status>HTTP/1.1 200 OK</status></propstat>
                </response>
                <response>
                  <href>/dav/tasks/gone.ics</href>
                  <status>HTTP/1.1 404 Not Found</status>
                </response>
                <sync-token>urn:x:2</sync-token>
                """,
            ),
        )

        val page = collection.changes("urn:x:1") as ChangeSet.Page

        // ⚠️ The deletion status is at DAV:response level, not inside a propstat.
        assertThat(page.changed.map { it.href.encodedPath })
            .containsExactly("/dav/tasks/kept.ics")
        assertThat(page.removed.map { it.encodedPath })
            .containsExactly("/dav/tasks/gone.ics")
        assertThat(page.token).isEqualTo("urn:x:2")
        assertThat(page.truncated).isFalse()
    }

    @Test fun `a status that is neither success nor removal is carried as a change`() {
        server.enqueue(
            multistatus(
                """
                <response>
                  <href>/dav/tasks/refused.ics</href>
                  <status>HTTP/1.1 403 Forbidden</status>
                </response>
                <sync-token>urn:x:2</sync-token>
                """,
            ),
        )

        val page = collection.changes("urn:x:1") as ChangeSet.Page

        // Dropping it would report the resource as unchanged and leave the row
        // stale until the next full reconciliation. With no validator on it the
        // multiget is forced, and that is what grades the refusal.
        assertThat(page.changed.map { it.href.encodedPath })
            .containsExactly("/dav/tasks/refused.ics")
        assertThat(page.changed.single().eTag).isNull()
        assertThat(page.removed).isEmpty()
    }

    @Test fun `no DAV limit is ever sent`() {
        server.enqueue(multistatus("<sync-token>urn:x:2</sync-token>"))
        collection.changes(null)

        val body = server.takeRequest().body.readUtf8()
        // Nextcloud regressed DAV:limit to a localised HTML error page, so asking
        // for a bounded page is how you get an unparseable response.
        assertThat(body).doesNotContain("limit")
        assertThat(body).contains("sync-level")
    }

    @Test fun `a 507 on our own href is truncation, not failure`() {
        server.enqueue(
            multistatus(
                """
                <response>
                  <href>/dav/tasks/one.ics</href>
                  <propstat><prop><getetag>"e1"</getetag></prop>
                  <status>HTTP/1.1 200 OK</status></propstat>
                </response>
                <response>
                  <href>/dav/tasks/</href>
                  <status>HTTP/1.1 507 Insufficient Storage</status>
                </response>
                <sync-token>urn:x:2</sync-token>
                """,
            ),
        )

        val page = collection.changes("urn:x:1") as ChangeSet.Page

        assertThat(page.truncated).isTrue()
        assertThat(page.changed).hasSize(1)
        assertThat(page.token).isEqualTo("urn:x:2")
    }

    @Test fun `a 207 with no sync-token is not an error`() {
        server.enqueue(
            multistatus(
                """
                <response>
                  <href>/dav/tasks/one.ics</href>
                  <propstat><prop><getetag>"e1"</getetag></prop>
                  <status>HTTP/1.1 200 OK</status></propstat>
                </response>
                """,
            ),
        )

        val page = collection.changes("urn:x:1") as ChangeSet.Page

        // The changes are real; only the cursor is missing.
        assertThat(page.changed).hasSize(1)
        assertThat(page.token).isNull()
    }

    // --------------------------------------------------- token invalidation

    @Test fun `invalidation is recognised on every status servers use for it`() {
        // ⚠️ 403 sabre, 400 Google, 409 Radicale, 412 Evolution. Thunderbird
        // matches 400 alone and never recovers from the 403 most self-hosted
        // servers emit.
        listOf(400, 403, 409, 412).forEach { code ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(code)
                    .setHeader("Content-Type", "application/xml; charset=utf-8")
                    .setBody(
                        "<D:error xmlns:D=\"DAV:\"><D:valid-sync-token/></D:error>",
                    ),
            )
            assertThat(collection.changes("stale")).isEqualTo(ChangeSet.TokenInvalid)
        }
    }

    @Test fun `invalidation is recognised whatever prefix the server uses`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "application/xml; charset=utf-8")
                .setBody("<error xmlns=\"DAV:\"><valid-sync-token/></error>"),
        )
        assertThat(collection.changes("stale")).isEqualTo(ChangeSet.TokenInvalid)
    }

    @Test fun `a 4xx without the marker is not an invalidation`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("nope"))
        // Treating every 403 as invalidation would discard a working token on a
        // permissions error and re-download the whole collection.
        assertThat(collection.changes("good")).isInstanceOf(ChangeSet.Failed::class.java)
    }

    @Test fun `an unimplemented report is reported as unsupported`() {
        server.enqueue(MockResponse().setResponseCode(501))
        assertThat(collection.changes(null)).isEqualTo(ChangeSet.Unsupported)
    }

    @Test fun `a 507 outer status is a failure rather than truncation`() {
        server.enqueue(MockResponse().setResponseCode(507))
        assertThat(collection.changes("t")).isInstanceOf(ChangeSet.Failed::class.java)
    }

    private fun multistatus(body: String) = MockResponse()
        .setResponseCode(207)
        .setHeader("Content-Type", "application/xml; charset=utf-8")
        .setBody("<multistatus xmlns=\"DAV:\">$body</multistatus>")
}
