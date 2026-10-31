package com.aiexile.caldav

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class CalDavDiscoveryTest {

    private val httpClient = OkHttpClient.Builder().followRedirects(false).build()
    private val server = MockWebServer()
    private lateinit var discovery: CalDavDiscovery

    @Before fun start() {
        server.start()
        discovery = CalDavDiscovery(httpClient)
    }

    @After fun stop() = server.shutdown()

    private fun options(dav: String = "1, 2, 3, calendar-access") =
        MockResponse().setResponseCode(200).setHeader("DAV", dav)

    private fun multistatus(body: String) = MockResponse()
        .setResponseCode(207)
        .setHeader("Content-Type", "application/xml; charset=utf-8")
        .setBody(body)

    private fun principalResponse(href: String?) = multistatus(
        """
        <multistatus xmlns="DAV:">
          <response>
            <href>/dav/</href>
            <propstat><prop><current-user-principal>
              ${href?.let { "<href>$it</href>" } ?: "<unauthenticated/>"}
            </current-user-principal></prop><status>HTTP/1.1 200 OK</status></propstat>
          </response>
        </multistatus>
        """.trimIndent(),
    )

    private fun homeSetResponse(vararg hrefs: String) = multistatus(
        """
        <multistatus xmlns="DAV:" xmlns:CAL="urn:ietf:params:xml:ns:caldav">
          <response>
            <href>/principals/me/</href>
            <propstat><prop><CAL:calendar-home-set>
              ${hrefs.joinToString("\n") { "<href>$it</href>" }}
            </CAL:calendar-home-set></prop><status>HTTP/1.1 200 OK</status></propstat>
          </response>
        </multistatus>
        """.trimIndent(),
    )

    /** One `<response>` for a collection in a Depth-1 listing. */
    private fun collection(
        href: String,
        resourceTypes: String,
        componentSet: String? = null,
        displayName: String = "Tasks",
        extraProps: String = "",
    ) = """
        <response>
          <href>$href</href>
          <propstat><prop>
            <resourcetype>$resourceTypes</resourcetype>
            <displayname>$displayName</displayname>
            ${componentSet ?: ""}
            $extraProps
          </prop><status>HTTP/1.1 200 OK</status></propstat>
        </response>
    """

    private fun listing(vararg responses: String) = multistatus(
        """
        <multistatus xmlns="DAV:" xmlns:CAL="urn:ietf:params:xml:ns:caldav"
                     xmlns:CS="http://calendarserver.org/ns/"
                     xmlns:nc="http://nextcloud.com/ns">
          <response><href>/dav/calendars/me/</href>
            <propstat><prop><resourcetype><collection/></resourcetype></prop>
            <status>HTTP/1.1 200 OK</status></propstat></response>
          ${responses.joinToString("\n")}
        </multistatus>
        """.trimIndent(),
    )

    private fun discoverCollections(listingBody: MockResponse): List<TaskCollection> {
        server.enqueue(homeSetResponse("/dav/calendars/me/"))
        server.enqueue(listingBody)
        val outcome = discovery.fromPrincipal(server.url("/principals/me/"))
        assertThat(outcome).isInstanceOf(CalDavDiscovery.Outcome.Found::class.java)
        return (outcome as CalDavDiscovery.Outcome.Found).collections
    }

    // ------------------------------------------------------------ principal

    @Test
    fun `a home set is resolved against where the principal actually landed`() {
        // ⚠️ followRedirects rewrites the resource's location in place, and a
        // relative home-set href means "relative to the answer". Resolved
        // against the URL we aimed at, a permanently moved principal names a
        // path under the old prefix: the home-set PROPFIND 404s and a perfectly
        // good account reports that it holds no calendars.
        server.enqueue(
            MockResponse().setResponseCode(301)
                .setHeader("Location", server.url("/nc/principals/me/").toString()),
        )
        server.enqueue(homeSetResponse("calendars/me/"))
        server.enqueue(listing(collection("/nc/principals/me/calendars/me/tasks/", "<collection/>")))

        val outcome = discovery.fromPrincipal(server.url("/principals/me/"))

        assertThat(outcome).isInstanceOf(CalDavDiscovery.Outcome.Found::class.java)
        // Under the prefix the redirect landed on, not the one we aimed at —
        // which would have been /principals/me/calendars/me/, a 404.
        assertThat((outcome as CalDavDiscovery.Outcome.Found).homeSets.single().encodedPath)
            .isEqualTo("/nc/principals/me/calendars/me/")
    }


    @Test
    fun `a 200 carrying unauthenticated is a failed login, not an empty result`() {
        // RFC 5397 section 3. Without this check a rejected credential looks like
        // a successful discovery that happened to find nothing — which is the
        // shape of bug report nobody can act on.
        server.enqueue(options())
        server.enqueue(principalResponse(null))

        assertThat(discovery.probe(server.url("/dav/")))
            .isEqualTo(CalDavDiscovery.Outcome.Unauthenticated)
    }

    @Test
    fun `a 401 means sign in, not failure`() {
        // iCloud and Zoho answer 401 from /.well-known/caldav — the endpoint *is*
        // the DAV root and wants auth. RFC-legal, and must not end the walk.
        server.enqueue(options())
        server.enqueue(MockResponse().setResponseCode(401))

        assertThat(discovery.probe(server.url("/dav/")))
            .isInstanceOf(CalDavDiscovery.Outcome.NeedsAuthentication::class.java)
    }

    @Test
    fun `a WebDAV server without calendar-access is not a CalDAV server`() {
        server.enqueue(options(dav = "1, 2, 3"))
        server.enqueue(principalResponse("/principals/me/"))

        val outcome = discovery.probe(server.url("/dav/"))
        assertThat(outcome).isInstanceOf(CalDavDiscovery.Outcome.NotCalDav::class.java)
    }

    // ------------------------------------------------------------ home sets

    @Test
    fun `every calendar-home-set href is followed, not just the first`() {
        // Multiple home sets are normative (RFC 4791 section 6.2.1's own example)
        // and iCloud depends on it.
        server.enqueue(homeSetResponse("/dav/one/", "/dav/two/"))
        server.enqueue(listing(collection("/dav/one/tasks/", "<collection/><CAL:calendar/>")))
        server.enqueue(listing(collection("/dav/two/more/", "<collection/><CAL:calendar/>")))

        val outcome = discovery.fromPrincipal(server.url("/principals/me/"))
        val found = outcome as CalDavDiscovery.Outcome.Found
        assertThat(found.collections.map { it.url.encodedPath })
            .containsExactly("/dav/one/tasks/", "/dav/two/more/")
    }

    @Test
    fun `a 401 on one home set does not invalidate the whole account`() {
        // The iCloud shape: principal on one host, home set on another. The
        // interceptor withholds the credential from the second host by design, so
        // a 401 there must not tell the user to sign in again with credentials
        // that just worked.
        server.enqueue(homeSetResponse("/dav/one/", "/dav/two/"))
        server.enqueue(listing(collection("/dav/one/tasks/", "<collection/><CAL:calendar/>")))
        server.enqueue(MockResponse().setResponseCode(401))

        val found = discovery.fromPrincipal(server.url("/principals/me/")) as CalDavDiscovery.Outcome.Found
        assertThat(found.collections.map { it.url.encodedPath }).containsExactly("/dav/one/tasks/")
        assertThat(found.failedHomeSets).hasSize(1)
        assertThat(found.failedHomeSets.single().needsAuthentication).isTrue()
    }

    @Test
    fun `every home set failing is a server error, not an empty account`() {
        // Returning Found with no collections says "connected, no task lists" for
        // what is actually a 500 — a bug report nobody can act on.
        server.enqueue(homeSetResponse("/dav/one/"))
        server.enqueue(MockResponse().setResponseCode(500))

        assertThat(discovery.fromPrincipal(server.url("/principals/me/")))
            .isInstanceOf(CalDavDiscovery.Outcome.Failed::class.java)
    }

    @Test
    fun `when every home set wants credentials, it names the hosts to allow`() {
        server.enqueue(homeSetResponse("/dav/one/"))
        server.enqueue(MockResponse().setResponseCode(401))

        val outcome = discovery.fromPrincipal(server.url("/principals/me/"))
        assertThat(outcome).isInstanceOf(CalDavDiscovery.Outcome.NeedsAuthentication::class.java)
        // Without the host, the caller can never widen the credential allowlist —
        // the cross-host home set stays permanently unreachable.
        assertThat((outcome as CalDavDiscovery.Outcome.NeedsAuthentication).hosts)
            .containsExactly(server.hostName)
    }

    @Test
    fun `a typed http URL is refused with the scheme as the reason`() {
        // Credentials are never sent over cleartext, so this could only ever
        // answer "not authorised" — which the user reads as a wrong password.
        val outcome = discovery.discover("http://cloud.example.com/dav/")
        assertThat(outcome).isInstanceOf(CalDavDiscovery.Outcome.Failed::class.java)
        val failed = outcome as CalDavDiscovery.Outcome.Failed
        // The cause is what the UI renders; the detail is for logs only.
        assertThat(failed.cause).isEqualTo(CalDavDiscovery.Outcome.Cause.INSECURE)
        assertThat(failed.detail).contains("http://")
    }

    @Test
    fun `a colour comes back as packed ARGB, not as a decimal string`() {
        val collections = discoverCollections(
            listing(
                collection(
                    "/dav/calendars/me/tasks/",
                    "<collection/><CAL:calendar/>",
                    extraProps = """<x1:calendar-color xmlns:x1="http://apple.com/ns/ical/">#FF0000FF</x1:calendar-color>""",
                ),
            ),
        )
        assertThat(collections.single().color).isEqualTo(0xFFFF0000.toInt())
    }

    @Test
    fun `a share is flagged, because Nextcloud rewrites bodies fetched from one`() {
        val collections = discoverCollections(
            listing(
                collection("/dav/calendars/me/shared/", "<collection/><CAL:calendar/><CS:shared/>"),
            ),
        )
        assertThat(collections.single().isShared).isTrue()
    }

    @Test
    fun `a principal with no home set is a failure worth naming`() {
        server.enqueue(homeSetResponse())
        val outcome = discovery.fromPrincipal(server.url("/principals/me/"))
        assertThat(outcome).isInstanceOf(CalDavDiscovery.Outcome.Failed::class.java)
    }

    // --------------------------------------------------- the two filters

    @Test
    fun `an absent supported-calendar-component-set means supports everything`() {
        // RFC 4791 section 5.2.3 says it SHOULD NOT come back from allprop, so it
        // is legitimately missing. Requiring VTODO to be listed silently drops
        // every server that does not advertise it.
        val collections = discoverCollections(
            listing(collection("/dav/calendars/me/tasks/", "<collection/><CAL:calendar/>")),
        )
        assertThat(collections.map { it.url.encodedPath }).containsExactly("/dav/calendars/me/tasks/")
    }

    @Test
    fun `an empty component set is treated as everything, not as nothing`() {
        // The grammar is (comp+) so this is non-conformant, and dav4jvm's parser
        // starts all-false — arriving indistinguishable from "no VTODO".
        val collections = discoverCollections(
            listing(
                collection(
                    "/dav/calendars/me/tasks/",
                    "<collection/><CAL:calendar/>",
                    componentSet = "<CAL:supported-calendar-component-set/>",
                ),
            ),
        )
        assertThat(collections).hasSize(1)
    }

    @Test
    fun `a VEVENT-only collection is excluded`() {
        val collections = discoverCollections(
            listing(
                collection(
                    "/dav/calendars/me/events/",
                    "<collection/><CAL:calendar/>",
                    componentSet = """<CAL:supported-calendar-component-set><CAL:comp name="VEVENT"/></CAL:supported-calendar-component-set>""",
                ),
            ),
        )
        assertThat(collections).isEmpty()
    }

    @Test
    fun `SOGo's personal calendar survives, because the test is positive not exclusionary`() {
        // SOGo reports collection + calendar + schedule-outbox simultaneously for
        // every non-Apple client, i.e. for us. Excluding schedule-outbox — the
        // obvious rule — would drop the user's main calendar.
        val collections = discoverCollections(
            listing(
                collection(
                    "/SOGo/dav/me/Calendar/personal/",
                    "<collection/><CAL:calendar/><CAL:schedule-outbox/>",
                ),
            ),
        )
        assertThat(collections).hasSize(1)
    }

    @Test
    fun `a shared calendar is kept`() {
        val collections = discoverCollections(
            listing(
                collection("/dav/calendars/me/shared/", "<collection/><CAL:calendar/><CS:shared/>"),
            ),
        )
        assertThat(collections).hasSize(1)
    }

    @Test
    fun `Nextcloud's trashed calendar is dropped, because it strips caldav-calendar`() {
        val collections = discoverCollections(
            listing(
                collection("/dav/calendars/me/deleted/", "<collection/><nc:deleted-calendar/>"),
            ),
        )
        assertThat(collections).isEmpty()
    }

    @Test
    fun `inboxes, outboxes and notification collections are not task lists`() {
        val collections = discoverCollections(
            listing(
                collection("/dav/calendars/me/inbox/", "<collection/><CAL:schedule-inbox/>"),
                collection("/dav/calendars/me/outbox/", "<collection/><CAL:schedule-outbox/>"),
                collection("/dav/calendars/me/notifications/", "<collection/><CS:notification/>"),
                collection("/dav/calendars/me/tasks/", "<collection/><CAL:calendar/>"),
            ),
        )
        assertThat(collections.map { it.url.encodedPath }).containsExactly("/dav/calendars/me/tasks/")
    }

    // ------------------------------------------------------- privileges

    @Test
    fun `an absent privilege set means writable`() {
        // RFC 3744 section 3.7 lets a server withhold it, and assuming read-only
        // hides collections the user can perfectly well write to.
        val collections = discoverCollections(
            listing(collection("/dav/calendars/me/tasks/", "<collection/><CAL:calendar/>")),
        )
        assertThat(collections.single().readOnly).isFalse()
    }

    @Test
    fun `a read-only share is reported as read-only`() {
        val collections = discoverCollections(
            listing(
                collection(
                    "/dav/calendars/me/shared/",
                    "<collection/><CAL:calendar/><CS:shared/>",
                    extraProps = "<current-user-privilege-set><privilege><read/></privilege></current-user-privilege-set>",
                ),
            ),
        )
        assertThat(collections.single().readOnly).isTrue()
    }

    @Test
    fun `sync-collection support is reported when advertised`() {
        val collections = discoverCollections(
            listing(
                collection(
                    "/dav/calendars/me/tasks/",
                    "<collection/><CAL:calendar/>",
                    extraProps = "<supported-report-set><supported-report><report><sync-collection/></report></supported-report></supported-report-set>",
                ),
            ),
        )
        assertThat(collections.single().supportsSyncCollection).isTrue()
    }
}
