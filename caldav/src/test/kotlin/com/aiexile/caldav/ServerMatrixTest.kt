package com.aiexile.caldav

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

/**
 * The per-server trap matrix.
 *
 * Everything reproducible from the protocol alone runs here against
 * MockWebServer. The handful that genuinely need a live server are `@Ignore`d
 * with the reason spelled out — they are named so they can be turned on by hand,
 * not deleted and rediscovered.
 */
class ServerMatrixTest {

    private val server = MockWebServer()
    private val httpClient = OkHttpClient.Builder().followRedirects(false).build()
    private lateinit var collection: CalendarCollection

    @Before fun start() {
        server.start()
        collection = CalendarCollection(httpClient, server.url("/dav/tasks/"))
    }

    @After fun stop() = server.shutdown()

    // ------------------------------------------------------------- Nextcloud

    @Test fun `Nextcloud per-calendar UID uniqueness is a rejection, not a retry`() {
        // 409 no-uid-conflict: another resource in this collection already has
        // that UID. Retrying the same body reproduces it exactly.
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setHeader("Content-Type", "application/xml; charset=utf-8")
                .setBody(
                    "<D:error xmlns:D=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\">" +
                        "<C:no-uid-conflict/></D:error>",
                ),
        )

        val outcome = collection.create("one.ics", "x")

        assertThat(outcome).isInstanceOf(PutOutcome.Rejected::class.java)
        assertThat((outcome as PutOutcome.Rejected).code).isEqualTo(409)
    }

    @Test fun `Nextcloud's trashbin makes a reused href answer 403`() {
        // The trashbin renames a deleted resource to `<name>-deleted.ics`, so
        // delete, recreate and delete again at the same href returns 403. Task
        // apps hit this constantly because they reuse hrefs.
        server.enqueue(MockResponse().setResponseCode(403))

        val outcome = collection.delete(server.url("/dav/tasks/one.ics"), "e")

        // Not "already gone" and not a conflict — a refusal that must be
        // quarantined rather than retried forever.
        assertThat(outcome).isInstanceOf(DeleteOutcome.Rejected::class.java)
        assertThat((outcome as DeleteOutcome.Rejected).code).isEqualTo(403)
    }

    @Test fun `a shared Nextcloud calendar is recognised as shared`() {
        // ⚠️ The whole mutilation guard hangs off this bit. `CalendarObject::get()`
        // serves a whitelist-reduced body from a share while leaving the ETag
        // untouched, so an engine that thinks the collection is not shared will
        // write that reduced body back over the owner's task.
        server.enqueue(
            MockResponse().setResponseCode(207)
                .setHeader("Content-Type", "application/xml; charset=utf-8")
                .setBody(
                    """
                    <multistatus xmlns="DAV:">
                      <response>
                        <href>/dav/tasks/</href>
                        <propstat><prop><resourcetype>
                          <collection/>
                          <C:calendar xmlns:C="urn:ietf:params:xml:ns:caldav"/>
                          <CS:shared xmlns:CS="http://calendarserver.org/ns/"/>
                        </resourcetype></prop><status>HTTP/1.1 200 OK</status></propstat>
                      </response>
                    </multistatus>
                    """.trimIndent(),
                ),
        )

        assertThat(collection.state().getOrThrow().collection.isShared).isTrue()
    }

    // ------------------------------------------------------------------ SOGo

    @Test fun `SOGo's second-granularity token is carried verbatim`() {
        // SOGo's tokens are second-granularity integers rather than URIs. Nothing
        // may parse, normalise or compare them as anything but opaque text.
        server.enqueue(
            MockResponse().setResponseCode(207)
                .setHeader("Content-Type", "application/xml; charset=utf-8")
                .setBody(
                    "<multistatus xmlns=\"DAV:\"><sync-token>1730000000</sync-token></multistatus>",
                ),
        )

        val page = collection.changes("1729999999") as ChangeSet.Page

        assertThat(page.token).isEqualTo("1730000000")
        assertThat(server.takeRequest().body.readUtf8()).contains("1729999999")
    }

    @Test fun `SOGo's main calendar survives classification`() {
        // ⚠️ SOGo reports collection + calendar + schedule-outbox together for
        // every non-Apple client. The obvious "exclude schedule-outbox" rule
        // drops the user's only calendar.
        server.enqueue(
            MockResponse().setResponseCode(207)
                .setHeader("Content-Type", "application/xml; charset=utf-8")
                .setBody(
                    """
                    <multistatus xmlns="DAV:">
                      <response>
                        <href>/dav/tasks/</href>
                        <propstat><prop><resourcetype>
                          <collection/>
                          <C:calendar xmlns:C="urn:ietf:params:xml:ns:caldav"/>
                          <C:schedule-outbox xmlns:C="urn:ietf:params:xml:ns:caldav"/>
                        </resourcetype></prop><status>HTTP/1.1 200 OK</status></propstat>
                      </response>
                    </multistatus>
                    """.trimIndent(),
                ),
        )

        assertThat(collection.state().isSuccess).isTrue()
    }

    // -------------------------------------------------------------- Radicale

    @Test fun `Radicale advertising sync-collection without meaning it degrades`() {
        // Radicale advertised the report for years without implementing it, so a
        // 501 must fall back rather than fail the collection.
        server.enqueue(MockResponse().setResponseCode(501))
        assertThat(collection.changes("t")).isEqualTo(ChangeSet.Unsupported)
    }

    // ---------------------------------------------------- needs a real server

    @Ignore(
        "Needs a live Baikal on dav_auth_type = Digest. OkHttp has no Digest of " +
            "its own (square/okhttp#205), so this exercises the vendored " +
            "BasicDigestAuthHandler against a real challenge/nonce cycle, which " +
            "MockWebServer cannot reproduce faithfully.",
    )
    @Test fun `Baikal on Digest authenticates`() = Unit

    @Ignore(
        "Needs a live Nextcloud with a calendar shared read-write from another " +
            "account, holding a CLASS:CONFIDENTIAL task. Verifies that we never " +
            "write back the whitelist-reduced body CalendarObject::get() serves " +
            "— the single most destructive bug available here, and one no mock " +
            "can prove absent because the reduction happens server-side.",
    )
    @Test fun `a confidential task on a shared Nextcloud calendar survives a round trip`() = Unit

    @Ignore(
        "Needs a live SOGo. Its ETag is a row-version counter and the body is " +
            "regenerated per principal, so the same ETag can accompany different " +
            "bytes. Proving we do not silently keep a stale body needs the real " +
            "server's regeneration behaviour.",
    )
    @Test fun `an ETag-unchanged body change on SOGo is detected`() = Unit

    @Ignore(
        "Needs a live Nextcloud. MKCALENDAR is rate-limited to 10 per hour, " +
            "which is the behaviour under test and cannot be mocked usefully.",
    )
    @Test fun `Nextcloud rate-limits collection creation`() = Unit
}
