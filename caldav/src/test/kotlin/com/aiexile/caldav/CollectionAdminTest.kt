package com.aiexile.caldav

import com.google.common.truth.Truth.assertThat
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The collection-management side, against a server rather than a mock of one.
 *
 * The three things worth pinning are the three that fail silently: which method
 * gets used, whether the request says `VTODO`, and whether a colour goes out in
 * a spelling anything can read back.
 */
class CollectionAdminTest {

    private lateinit var server: MockWebServer
    private lateinit var admin: DavCollectionAdmin

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        admin = DavCollectionAdmin(CalDavHttp.anonymous("test"))
    }

    @After fun tearDown() = server.shutdown()

    private fun homeSet() = server.url("/dav/calendars/me/")

    @Test
    fun `MKCALENDAR is read out of Allow and extended MKCOL out of DAV`() {
        // The normal shape for an extended-MKCOL server: plain MKCOL in Allow,
        // and the class advertised only in DAV. Reading Allow alone reports
        // iCloud as unable to create anything at all.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Allow", "OPTIONS, GET, PROPFIND, MKCOL")
                .setHeader("DAV", "1, 2, 3, calendar-access, extended-mkcol"),
        )

        val support = admin.support(homeSet())

        assertThat(support.mkCalendar).isFalse()
        assertThat(support.extendedMkCol).isTrue()
        assertThat(support.canCreate).isTrue()
    }

    @Test
    fun `a server offering neither offers nothing`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Allow", "OPTIONS, GET, PROPFIND")
                .setHeader("DAV", "1, 2, calendar-access"),
        )

        // Posteo disables collection creation and Google has neither. The
        // affordance has to be gone rather than answering 405 at the end of a
        // form the user has already filled in.
        assertThat(admin.support(homeSet()).canCreate).isFalse()
    }

    @Test
    fun `an OPTIONS that never answers reads as no`() {
        server.shutdown()

        assertThat(admin.support(homeSet())).isEqualTo(CollectionSupport.NONE)
    }

    @Test
    fun `creating asks for VTODO, by MKCALENDAR where it exists`() {
        server.enqueue(MockResponse().setResponseCode(201))

        val outcome = admin.create(
            homeSet = homeSet(),
            name = "shopping",
            displayName = "Shopping",
            color = 0xFF4C6FFF.toInt(),
            support = CollectionSupport(mkCalendar = true, extendedMkCol = true),
        )

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("MKCALENDAR")
        // ⚠️ The trailing slash is load-bearing: without it every later
        // `resolve` against this URL lands in the parent collection.
        assertThat(request.path).isEqualTo("/dav/calendars/me/shopping/")
        val body = request.body.readUtf8()
        // A calendar created without the component set gets the server's
        // default, which on several is events-only — a task list that refuses
        // tasks.
        assertThat(body).contains("VTODO")
        assertThat(body).contains("Shopping")
        // #RRGGBBAA, not the packed Int, which prints as "-11702273".
        assertThat(body).contains("#4C6FFFFF")
        assertThat(outcome).isInstanceOf(CollectionOutcome.Created::class.java)
    }

    @Test
    fun `a server with only extended MKCOL gets a resourcetype instead`() {
        server.enqueue(MockResponse().setResponseCode(201))

        admin.create(
            homeSet = homeSet(),
            name = "shopping",
            displayName = "Shopping",
            color = null,
            support = CollectionSupport(mkCalendar = false, extendedMkCol = true),
        )

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("MKCOL")
        // The whole difference between the two: here the request says what the
        // collection is, rather than the method.
        assertThat(request.body.readUtf8()).contains("resourcetype")
    }

    @Test
    fun `a server with neither is refused before a request is made`() {
        val outcome = admin.create(
            homeSet = homeSet(),
            name = "shopping",
            displayName = "Shopping",
            color = null,
            support = CollectionSupport.NONE,
        )

        assertThat(outcome).isEqualTo(CollectionOutcome.Unsupported)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a refusal carries its status rather than becoming a failure`() {
        // Nextcloud's trashbin renames a deleted collection instead of removing
        // it, so re-creating one under a used name answers 403 for ever. The
        // caller retries under a fresh segment, which it can only do if it can
        // tell "refused" from "unreachable".
        server.enqueue(MockResponse().setResponseCode(403))

        val outcome = admin.create(
            homeSet = homeSet(),
            name = "shopping",
            displayName = "Shopping",
            color = null,
            support = CollectionSupport(mkCalendar = true, extendedMkCol = false),
        )

        assertThat((outcome as CollectionOutcome.Refused).code).isEqualTo(403)
    }

    @Test
    fun `a rename and a recolour go out as one PROPPATCH`() {
        server.enqueue(MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\"/>"))

        val outcome = admin.updateProperties(
            url = server.url("/dav/calendars/me/shopping/"),
            displayName = "Groceries",
            color = 0xFF00FF00.toInt(),
        )

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertThat(request.method).isEqualTo("PROPPATCH")
        // One request, not two: a rename and a recolour are one edit as far as
        // the user is concerned, and half of one landing is the worse outcome.
        assertThat(body).contains("Groceries")
        assertThat(body).contains("#00FF00FF")
        assertThat(outcome).isEqualTo(CollectionOutcome.Updated)
    }

    @Test
    fun `a collection that is already gone counts as deleted`() {
        server.enqueue(MockResponse().setResponseCode(404))

        // The point of a DELETE is for the thing to be absent, and it is.
        // Treating "already gone" as a failure leaves a row nothing can remove.
        assertThat(admin.delete(server.url("/dav/calendars/me/shopping/")))
            .isEqualTo(CollectionOutcome.Updated)
    }
}
