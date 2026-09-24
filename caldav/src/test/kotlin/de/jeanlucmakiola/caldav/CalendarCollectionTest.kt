package de.jeanlucmakiola.caldav

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockWebServer
import okio.buffer
import org.junit.After
import org.junit.Before
import org.junit.Test

class CalendarCollectionTest {

    private val server = MockWebServer()
    private val httpClient = OkHttpClient.Builder().followRedirects(false).build()
    private lateinit var collection: CalendarCollection

    @Before fun start() {
        server.start()
        collection = CalendarCollection(httpClient, server.url("/dav/tasks/"))
    }

    @After fun stop() = server.shutdown()

    // ------------------------------------------------------------------ list

    @Test fun `list reads hrefs and strong etags`() {
        server.enqueue(
            multistatus(
                """
                <response>
                  <href>/dav/tasks/one.ics</href>
                  <propstat><prop><getetag>"e1"</getetag></prop>
                  <status>HTTP/1.1 200 OK</status></propstat>
                </response>
                <response>
                  <href>/dav/tasks/two.ics</href>
                  <propstat><prop><getetag>W/"e2"</getetag></prop>
                  <status>HTTP/1.1 200 OK</status></propstat>
                </response>
                """,
            ),
        )

        val refs = collection.list().getOrThrow()

        assertThat(refs.map { it.href.encodedPath })
            .containsExactly("/dav/tasks/one.ics", "/dav/tasks/two.ics")
        assertThat(refs[0].eTag).isEqualTo(ETag("e1", weak = false))
        assertThat(refs[1].eTag!!.usable).isFalse()
    }

    @Test fun `list asks for no calendar-data and no time-range`() {
        server.enqueue(multistatus(""))
        collection.list().getOrThrow()

        val body = server.takeRequest().body.readUtf8()
        // Bodies in a listing would download the whole collection every time, and
        // would arrive without an ETag matched to them.
        assertThat(body).doesNotContain("calendar-data")
        // A VTODO without DTSTART or DUE is outside every range by construction,
        // and some servers answer a ranged VTODO filter with nothing at all.
        assertThat(body).doesNotContain("time-range")
        assertThat(body).contains("VTODO")
    }

    // ----------------------------------------------------------------- fetch

    @Test fun `fetch matches responses against the request`() {
        server.enqueue(
            multistatus(
                """
                <response>
                  <href>/dav/tasks/one.ics</href>
                  <propstat><prop>
                    <getetag>"e1"</getetag>
                    <C:calendar-data xmlns:C="urn:ietf:params:xml:ns:caldav">BEGIN:VCALENDAR
END:VCALENDAR</C:calendar-data>
                  </prop><status>HTTP/1.1 200 OK</status></propstat>
                </response>
                <response>
                  <href>/dav/tasks/somebody-elses.ics</href>
                  <propstat><prop>
                    <getetag>"e9"</getetag>
                    <C:calendar-data xmlns:C="urn:ietf:params:xml:ns:caldav">BEGIN:VCALENDAR
END:VCALENDAR</C:calendar-data>
                  </prop><status>HTTP/1.1 200 OK</status></propstat>
                </response>
                """,
            ),
        )

        val result = collection.fetch(listOf(href("one.ics"), href("two.ics"))).getOrThrow()

        assertThat(result.resources.map { it.href.encodedPath })
            .containsExactly("/dav/tasks/one.ics")
        // Real servers answer with URLs nobody asked about. Applying one of those
        // to a row keyed by another href corrupts the wrong task.
        assertThat(result.unsolicited.map { it.encodedPath })
            .containsExactly("/dav/tasks/somebody-elses.ics")
        assertThat(result.missing.map { it.encodedPath }).containsExactly("/dav/tasks/two.ics")
    }

    @Test fun `a server that respells the path is still matched`() {
        server.enqueue(
            multistatus(
                """
                <response>
                  <href>/dav/tasks/my%40dav.ics</href>
                  <propstat><prop>
                    <getetag>"e1"</getetag>
                    <C:calendar-data xmlns:C="urn:ietf:params:xml:ns:caldav">BEGIN:VCALENDAR
END:VCALENDAR</C:calendar-data>
                  </prop><status>HTTP/1.1 200 OK</status></propstat>
                </response>
                """,
            ),
        )

        val asked = href("my@dav.ics")
        val result = collection.fetch(listOf(asked)).getOrThrow()

        // ⚠️ Compared raw, one resource lands in unsolicited *and* missing, its
        // body is dropped, and three such runs quarantine it out of the download
        // for good — nothing on that side can refund the count.
        assertThat(result.resources).hasSize(1)
        assertThat(result.missing).isEmpty()
        assertThat(result.unsolicited).isEmpty()
        assertThat(result.failed).isEmpty()
        // The href we asked for, not the server's spelling: apply is keyed on
        // the local row's href.
        assertThat(result.resources.single().href).isEqualTo(asked)
    }

    @Test fun `an href stored before a redirect still matches the reply`() {
        server.enqueue(
            multistatus(
                """
                <response>
                  <href>/dav/tasks/one.ics</href>
                  <propstat><prop>
                    <getetag>"e1"</getetag>
                    <C:calendar-data xmlns:C="urn:ietf:params:xml:ns:caldav">BEGIN:VCALENDAR
END:VCALENDAR</C:calendar-data>
                  </prop><status>HTTP/1.1 200 OK</status></propstat>
                </response>
                """,
            ),
        )

        // ⚠️ The REPORT body carries only paths, so the reply resolves against
        // the collection we asked — while a stored href can still carry the
        // origin we held before a redirect moved us. Keying on the origin would
        // put every resource of a redirected collection into both buckets.
        val stored = "https://old.example.com/dav/tasks/one.ics".toHttpUrl()
        val result = collection.fetch(listOf(stored)).getOrThrow()

        assertThat(result.resources.map { it.href }).containsExactly(stored)
        assertThat(result.missing).isEmpty()
        assertThat(result.unsolicited).isEmpty()
    }

    @Test fun `the same path on another host is not our resource`() {
        server.enqueue(
            multistatus(
                """
                <response>
                  <href>https://evil.example.com/dav/tasks/one.ics</href>
                  <propstat><prop>
                    <getetag>"e1"</getetag>
                    <C:calendar-data xmlns:C="urn:ietf:params:xml:ns:caldav">BEGIN:VCALENDAR
END:VCALENDAR</C:calendar-data>
                  </prop><status>HTTP/1.1 200 OK</status></propstat>
                </response>
                """,
            ),
        )

        val result = collection.fetch(listOf(href("one.ics"))).getOrThrow()

        // Matching on the path alone would apply one host's body to another
        // host's row.
        assertThat(result.resources).isEmpty()
        assertThat(result.unsolicited.map { it.host }).containsExactly("evil.example.com")
        assertThat(result.missing).hasSize(1)
    }

    @Test fun `an ambiguous spelling matches nothing rather than the wrong row`() {
        // A third spelling: matches neither request exactly, decodes like both.
        server.enqueue(
            multistatus(
                """
                <response>
                  <href>/dav/tasks/%6Dy@dav.ics</href>
                  <propstat><prop>
                    <getetag>"e1"</getetag>
                    <C:calendar-data xmlns:C="urn:ietf:params:xml:ns:caldav">BEGIN:VCALENDAR
END:VCALENDAR</C:calendar-data>
                  </prop><status>HTTP/1.1 200 OK</status></propstat>
                </response>
                """,
            ),
        )

        val plain = href("my@dav.ics")
        val encoded = server.url("/dav/tasks/my%40dav.ics")
        val result = collection.fetch(listOf(plain, encoded)).getOrThrow()

        // ⚠️ Two rows decode alike, so no answer can say which it means. Guessing
        // applies one row's body to the other; both are reported instead, and the
        // syncer will not spend a quarantine count on a stray of the same path.
        assertThat(result.resources).isEmpty()
        assertThat(result.unsolicited).hasSize(1)
        assertThat(result.missing).containsExactly(plain, encoded)
    }

    @Test fun `a trailing slash is still a different resource`() {
        server.enqueue(
            multistatus(
                """
                <response>
                  <href>/dav/tasks/one.ics/</href>
                  <propstat><prop><getetag>"e1"</getetag></prop><status>HTTP/1.1 200 OK</status></propstat>
                </response>
                """,
            ),
        )

        val result = collection.fetch(listOf(href("one.ics"))).getOrThrow()

        // Deliberately not normalised, as UrlUtils.equals also refuses to.
        assertThat(result.resources).isEmpty()
        assertThat(result.missing).hasSize(1)
    }

    @Test fun `an encoded slash is not the same as a real one`() {
        server.enqueue(
            multistatus(
                """
                <response>
                  <href>/dav/tasks/a/b.ics</href>
                  <propstat><prop>
                    <getetag>"e1"</getetag>
                    <C:calendar-data xmlns:C="urn:ietf:params:xml:ns:caldav">BEGIN:VCALENDAR
END:VCALENDAR</C:calendar-data>
                  </prop><status>HTTP/1.1 200 OK</status></propstat>
                </response>
                """,
            ),
        )

        val asked = server.url("/dav/tasks/a%2Fb.ics")
        val result = collection.fetch(listOf(asked)).getOrThrow()

        // One segment "a/b" is not two segments "a" and "b" — which is why the
        // key stays a list rather than a joined string.
        assertThat(result.resources).isEmpty()
        assertThat(result.missing).containsExactly(asked)
    }

    @Test fun `a refused resource is reported as failed, not as missing`() {
        server.enqueue(
            multistatus(
                """
                <response>
                  <href>/dav/tasks/one.ics</href>
                  <propstat><prop>
                    <getetag>"e1"</getetag>
                    <C:calendar-data xmlns:C="urn:ietf:params:xml:ns:caldav">BEGIN:VCALENDAR
END:VCALENDAR</C:calendar-data>
                  </prop><status>HTTP/1.1 200 OK</status></propstat>
                </response>
                <response>
                  <href>/dav/tasks/two.ics</href>
                  <status>HTTP/1.1 403 Forbidden</status>
                </response>
                """,
            ),
        )

        val result = collection.fetch(listOf(href("one.ics"), href("two.ics"))).getOrThrow()

        // The server did mention two.ics, so it is not missing. Merging the two
        // costs the caller the difference between "refused" and "omitted", which
        // are not the same fact and do not deserve the same answer.
        assertThat(result.missing).isEmpty()
        assertThat(result.failed.map { it.href.encodedPath to it.code })
            .containsExactly("/dav/tasks/two.ics" to 403)
        // One bad member does not cost the batch its good ones.
        assertThat(result.resources.map { it.href.encodedPath })
            .containsExactly("/dav/tasks/one.ics")
    }

    @Test fun `a success carrying no calendar-data is reported as failed`() {
        server.enqueue(
            multistatus(
                """
                <response>
                  <href>/dav/tasks/one.ics</href>
                  <propstat><prop>
                    <getetag>"e1"</getetag>
                  </prop><status>HTTP/1.1 200 OK</status></propstat>
                </response>
                """,
            ),
        )

        val result = collection.fetch(listOf(href("one.ics"))).getOrThrow()

        // No HTTP judgement to report: the server said yes and sent nothing.
        assertThat(result.failed.map { it.href.encodedPath to it.code })
            .containsExactly("/dav/tasks/one.ics" to 0)
        assertThat(result.resources).isEmpty()
        assertThat(result.missing).isEmpty()
    }

    @Test fun `a propstat refusal carries its own status, not a blank`() {
        server.enqueue(
            multistatus(
                """
                <response>
                  <href>/dav/tasks/one.ics</href>
                  <propstat><prop>
                    <getetag>"e1"</getetag>
                  </prop><status>HTTP/1.1 200 OK</status></propstat>
                  <propstat><prop>
                    <C:calendar-data xmlns:C="urn:ietf:params:xml:ns:caldav"/>
                  </prop><status>HTTP/1.1 403 Forbidden</status></propstat>
                </response>
                """,
            ),
        )

        val result = collection.fetch(listOf(href("one.ics"))).getOrThrow()

        // A per-property refusal is the usual shape for "you may not read this
        // one object". Response.properties drops non-2xx propstats, so reporting
        // 0 here would make a deterministic refusal look like a transient blank.
        assertThat(result.failed.map { it.code }).containsExactly(403)
    }

    @Test fun `a resource the server says is gone carries its status`() {
        server.enqueue(
            multistatus(
                """
                <response>
                  <href>/dav/tasks/one.ics</href>
                  <status>HTTP/1.1 404 Not Found</status>
                </response>
                """,
            ),
        )

        val result = collection.fetch(listOf(href("one.ics"))).getOrThrow()

        // The code has to survive the trip: the engine treats 404 and 403 as
        // opposite verdicts.
        assertThat(result.failed.map { it.code }).containsExactly(404)
        assertThat(result.missing).isEmpty()
    }

    @Test fun `fetch batches`() {
        repeat(2) { server.enqueue(multistatus("")) }
        collection.fetch((1..3).map { href("$it.ics") }, batchSize = 2).getOrThrow()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `a batch that fails keeps the batches that already worked`() {
        server.enqueue(
            multistatus(
                """
                <response>
                  <href>/dav/tasks/1.ics</href>
                  <propstat><prop>
                    <getetag>"e1"</getetag>
                    <C:calendar-data xmlns:C="urn:ietf:params:xml:ns:caldav">BEGIN:VCALENDAR
END:VCALENDAR</C:calendar-data>
                  </prop><status>HTTP/1.1 200 OK</status></propstat>
                </response>
                """,
            ),
        )
        server.enqueue(MockResponse().setResponseCode(500))

        val result = collection.fetch((1..3).map { href("$it.ics") }, batchSize = 1).getOrThrow()

        // ⚠️ Wrapping the whole loop threw away everything parsed so far and
        // answered failure, so the engine recorded a collection failure and
        // re-downloaded the lot next run — on a flaky link, for ever.
        assertThat(result.resources.map { it.href.encodedPath })
            .containsExactly("/dav/tasks/1.ics")
        // The rest were never asked, which is exactly what missing means; the
        // engine counts those rather than acting on them as deletions.
        assertThat(result.missing.map { it.encodedPath })
            .containsExactly("/dav/tasks/2.ics", "/dav/tasks/3.ics")
    }

    @Test fun `a fetch that never got anything still fails`() {
        server.enqueue(MockResponse().setResponseCode(500))

        assertThat(collection.fetch(listOf(href("one.ics"))).isFailure).isTrue()
    }

    // ---------------------------------------------------------------- create

    @Test fun `create with a strong etag is stored`() {
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"new\""))

        val outcome = collection.create("one.ics", "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n")

        assertThat(outcome).isInstanceOf(PutOutcome.Stored::class.java)
        assertThat((outcome as PutOutcome.Stored).eTag).isEqualTo(ETag("new", weak = false))
        assertThat(server.takeRequest().getHeader("If-None-Match")).isEqualTo("*")
    }

    @Test fun `create with a weak etag needs a refetch`() {
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "W/\"new\""))
        // A weak tag is worse than none: storing it makes every later write look
        // conditional while silently not being one.
        assertThat(collection.create("one.ics", "x"))
            .isInstanceOf(PutOutcome.StoredNeedsRefetch::class.java)
    }

    @Test fun `create with no etag needs a refetch`() {
        server.enqueue(MockResponse().setResponseCode(201))
        assertThat(collection.create("one.ics", "x"))
            .isInstanceOf(PutOutcome.StoredNeedsRefetch::class.java)
    }

    @Test fun `create answered 412 means the name is taken`() {
        server.enqueue(MockResponse().setResponseCode(412))
        val outcome = collection.create("one.ics", "x")
        assertThat(outcome).isInstanceOf(PutOutcome.NameTaken::class.java)
        assertThat((outcome as PutOutcome.NameTaken).href.encodedPath)
            .isEqualTo("/dav/tasks/one.ics")
    }

    @Test fun `create answered 415 is rejected and not retried`() {
        server.enqueue(MockResponse().setResponseCode(415))
        val outcome = collection.create("one.ics", "x")
        assertThat(outcome).isInstanceOf(PutOutcome.Rejected::class.java)
        assertThat((outcome as PutOutcome.Rejected).code).isEqualTo(415)
    }

    // ---------------------------------------------------------------- update

    @Test fun `update answered 412 with the resource still there is a conflict`() {
        server.enqueue(MockResponse().setResponseCode(412))
        server.enqueue(MockResponse().setResponseCode(200))

        assertThat(collection.update(href("one.ics"), "old", "x"))
            .isEqualTo(PutOutcome.ServerNewer)
        assertThat(server.takeRequest().getHeader("If-Match")).isEqualTo("\"old\"")
        assertThat(server.takeRequest().method).isEqualTo("HEAD")
    }

    @Test fun `update answered 412 with the resource gone is not a conflict`() {
        server.enqueue(MockResponse().setResponseCode(412))
        server.enqueue(MockResponse().setResponseCode(404))

        // RFC 9110 §13.1.1 requires 412 once the precondition is evaluated, so a
        // deleted resource and a changed one are the same status code.
        assertThat(collection.update(href("one.ics"), "old", "x"))
            .isEqualTo(PutOutcome.Vanished)
    }

    @Test fun `update without an etag writes unconditionally`() {
        server.enqueue(MockResponse().setResponseCode(204).setHeader("ETag", "\"new\""))
        collection.update(href("one.ics"), null, "x")
        assertThat(server.takeRequest().getHeader("If-Match")).isNull()
    }

    // ---------------------------------------------------------------- delete

    @Test fun `delete counts 404 and 410 as success`() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertThat(collection.delete(href("one.ics"), "e")).isEqualTo(DeleteOutcome.Deleted)

        server.enqueue(MockResponse().setResponseCode(410))
        assertThat(collection.delete(href("one.ics"), "e")).isEqualTo(DeleteOutcome.Deleted)
    }

    @Test fun `delete sends If-Match and reports a conflict`() {
        server.enqueue(MockResponse().setResponseCode(412))
        server.enqueue(MockResponse().setResponseCode(200))

        assertThat(collection.delete(href("one.ics"), "old"))
            .isEqualTo(DeleteOutcome.ServerNewer)
        assertThat(server.takeRequest().getHeader("If-Match")).isEqualTo("\"old\"")
    }

    @Test fun `delete answered 403 is rejected`() {
        // Nextcloud's trashbin renames a deleted resource, so delete, recreate and
        // delete again on the same href answers 403.
        server.enqueue(MockResponse().setResponseCode(403))
        val outcome = collection.delete(href("one.ics"), null)
        assertThat(outcome).isInstanceOf(DeleteOutcome.Rejected::class.java)
        assertThat((outcome as DeleteOutcome.Rejected).code).isEqualTo(403)
    }

    // --------------------------------------------------------------- headers

    @Test fun `writes carry Prefer strict and identity encoding`() {
        val client = CalDavHttp.anonymous("Agendula test")
        val withHeaders = CalendarCollection(client, server.url("/dav/tasks/"))
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"e\""))

        withHeaders.create("one.ics", "x")

        val request = server.takeRequest()
        // Without this, sabre runs vobject REPAIR over the body and then withholds
        // the ETag because the stored bytes are no longer ours.
        assertThat(request.getHeader("Prefer")).isEqualTo("handling=strict")
        // Without this, a gzip-compressing proxy weakens every ETag it touches.
        assertThat(request.getHeader("Accept-Encoding")).isEqualTo("identity")
    }

    @Test fun `a caller's own Prefer survives beside ours`() {
        val client = CalDavHttp.anonymous("Agendula test")
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(
            okhttp3.Request.Builder()
                .url(server.url("/dav/tasks/one.ics"))
                .header("Prefer", "return=minimal")
                .put("x".toRequestBody())
                .build(),
        ).execute().close()

        // Replacing it would silently drop whatever the caller asked for.
        assertThat(server.takeRequest().getHeader("Prefer"))
            .isEqualTo("return=minimal, handling=strict")
    }

    @Test fun `a body gzipped despite identity is still readable`() {
        val client = CalDavHttp.anonymous("Agendula test")
        val body = okio.Buffer()
        okio.GzipSink(body).buffer().use { it.writeUtf8("BEGIN:VCALENDAR") }
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Encoding", "gzip")
                .setBody(body),
        )

        val response = client.newCall(
            okhttp3.Request.Builder().url(server.url("/dav/tasks/one.ics")).build(),
        ).execute()

        // ⚠️ Asking for identity takes OkHttp's transparent gunzip out of the
        // loop, so a proxy that compresses anyway hands raw deflate to the
        // iCalendar parser and a good resource is quarantined as unreadable.
        assertThat(response.use { it.body!!.string() }).isEqualTo("BEGIN:VCALENDAR")
    }

    // ----------------------------------------------------------------- state

    @Test fun `state re-reads read-only and shared`() {
        server.enqueue(
            multistatus(
                """
                <response>
                  <href>/dav/tasks/</href>
                  <propstat><prop>
                    <resourcetype>
                      <collection/>
                      <C:calendar xmlns:C="urn:ietf:params:xml:ns:caldav"/>
                      <CS:shared xmlns:CS="http://calendarserver.org/ns/"/>
                    </resourcetype>
                    <current-user-privilege-set>
                      <privilege><read/></privilege>
                    </current-user-privilege-set>
                    <CS:getctag xmlns:CS="http://calendarserver.org/ns/">ct-1</CS:getctag>
                  </prop><status>HTTP/1.1 200 OK</status></propstat>
                </response>
                """,
            ),
        )

        val state = collection.state().getOrThrow()

        assertThat(state.collection.readOnly).isTrue()
        assertThat(state.collection.isShared).isTrue()
        assertThat(state.ctag).isEqualTo("ct-1")
    }

    @Test fun `a member reported alongside self never becomes the collection state`() {
        server.enqueue(
            multistatus(
                """
                <response>
                  <href>/dav/tasks/member.ics</href>
                  <propstat><prop>
                    <resourcetype>
                      <collection/>
                      <C:calendar xmlns:C="urn:ietf:params:xml:ns:caldav"/>
                    </resourcetype>
                    <current-user-privilege-set><privilege><read/></privilege></current-user-privilege-set>
                  </prop><status>HTTP/1.1 200 OK</status></propstat>
                </response>
                <response>
                  <href>/dav/tasks/</href>
                  <propstat><prop>
                    <resourcetype>
                      <collection/>
                      <C:calendar xmlns:C="urn:ietf:params:xml:ns:caldav"/>
                    </resourcetype>
                    <CS:getctag xmlns:CS="http://calendarserver.org/ns/">mine</CS:getctag>
                  </prop><status>HTTP/1.1 200 OK</status></propstat>
                </response>
                """,
            ),
        )

        // Some servers answer a Depth-0 PROPFIND with a member as well. Taking the
        // first classifiable response would read that member's ACL as the
        // collection's own — here, read-only when it is not.
        val state = collection.state().getOrThrow()
        assertThat(state.ctag).isEqualTo("mine")
        assertThat(state.collection.readOnly).isFalse()
    }

    @Test fun `state fails rather than guessing when the collection is no longer one`() {
        server.enqueue(
            multistatus(
                """
                <response>
                  <href>/dav/tasks/</href>
                  <propstat><prop><resourcetype><collection/></resourcetype></prop>
                  <status>HTTP/1.1 200 OK</status></propstat>
                </response>
                """,
            ),
        )
        assertThat(collection.state().isFailure).isTrue()
    }

    // ----------------------------------------------------------------- setup

    private fun href(name: String): HttpUrl = server.url("/dav/tasks/$name")

    private fun multistatus(responses: String) = MockResponse()
        .setResponseCode(207)
        .setHeader("Content-Type", "application/xml; charset=utf-8")
        .setBody("<multistatus xmlns=\"DAV:\">$responses</multistatus>")
}
