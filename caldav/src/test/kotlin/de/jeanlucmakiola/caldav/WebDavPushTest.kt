package de.jeanlucmakiola.caldav

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.time.Instant

class WebDavPushTest {

    private val server = MockWebServer()
    private val httpClient = OkHttpClient.Builder().followRedirects(false).build()

    @Before fun start() = server.start()

    @After fun stop() = server.shutdown()

    private fun collectionUrl() = server.url("/dav/tasks/")

    private val requested = Instant.parse("2026-10-01T10:00:00Z")

    // ------------------------------------------------------------ discovery

    @Test fun `state reads the topic and VAPID key`() {
        server.enqueue(stateResponse(push = PUSH_PROPS))

        val push = CalendarCollection(httpClient, collectionUrl()).state().getOrThrow().collection.push

        assertThat(push).isEqualTo(PushSupport(topic = "O7M1nQ7cKkKTKsoS_j6Z3w", vapidPublicKey = VAPID))
    }

    @Test fun `state asks for the push properties by name`() {
        server.enqueue(stateResponse(push = ""))
        CalendarCollection(httpClient, collectionUrl()).state().getOrThrow()

        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("https://bitfire.at/webdav-push")
        assertThat(body).contains("transports")
        assertThat(body).contains("topic")
    }

    @Test fun `a topic without a web-push transport is no support`() {
        server.enqueue(stateResponse(push = """<P:topic>t1</P:topic>"""))

        val push = CalendarCollection(httpClient, collectionUrl()).state().getOrThrow().collection.push

        assertThat(push).isNull()
    }

    @Test fun `a web-push transport without a topic is no support`() {
        server.enqueue(stateResponse(push = """<P:transports><P:web-push/></P:transports>"""))

        val push = CalendarCollection(httpClient, collectionUrl()).state().getOrThrow().collection.push

        assertThat(push).isNull()
    }

    @Test fun `web-push without a VAPID key is still support`() {
        server.enqueue(
            stateResponse(push = """<P:transports><P:web-push/></P:transports><P:topic>t1</P:topic>"""),
        )

        val push = CalendarCollection(httpClient, collectionUrl()).state().getOrThrow().collection.push

        assertThat(push).isEqualTo(PushSupport(topic = "t1", vapidPublicKey = null))
    }

    // ---------------------------------------------------------- registration

    @Test fun `register posts the subscription and reads Location and Expires`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Location", "/dav/subscriptions/io6Efei4ooph")
                .setHeader("Expires", "Sat, 03 Oct 2026 07:28:00 GMT"),
        )

        val outcome = WebDavPush.register(
            httpClient, collectionUrl(), "https://up.example.net/abc", "PUBKEY", "AUTH", requested,
        )

        assertThat(outcome).isEqualTo(
            WebDavPush.Registration.Registered(
                url = server.url("/dav/subscriptions/io6Efei4ooph"),
                expires = Instant.parse("2026-10-03T07:28:00Z"),
            ),
        )
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/dav/tasks/")
        val body = request.body.readUtf8()
        assertThat(body).contains("push-register")
        assertThat(body).contains("https://up.example.net/abc")
        assertThat(body).contains("aes128gcm")
        assertThat(body).contains("PUBKEY")
        assertThat(body).contains("AUTH")
        assertThat(body).contains("content-update")
        assertThat(body).contains("Thu, 01 Oct 2026 10:00:00 GMT")
    }

    @Test fun `register without Expires keeps the requested date`() {
        server.enqueue(MockResponse().setResponseCode(204).setHeader("Location", "/sub/1"))

        val outcome = WebDavPush.register(httpClient, collectionUrl(), "https://up/1", null, null, requested)

        assertThat(outcome).isEqualTo(WebDavPush.Registration.Registered(server.url("/sub/1"), requested))
    }

    @Test fun `register leaves the keys out when there are none`() {
        server.enqueue(MockResponse().setResponseCode(204).setHeader("Location", "/sub/1"))

        WebDavPush.register(httpClient, collectionUrl(), "https://up/1", null, null, requested)

        val body = server.takeRequest().body.readUtf8()
        assertThat(body).doesNotContain("content-encoding")
        assertThat(body).doesNotContain("subscription-public-key")
    }

    @Test fun `a 403 is a refusal`() {
        server.enqueue(MockResponse().setResponseCode(403))

        val outcome = WebDavPush.register(httpClient, collectionUrl(), "https://up/1", "k", "a", requested)

        assertThat(outcome).isEqualTo(WebDavPush.Registration.Refused(403))
    }

    @Test fun `a 500 is a failure, not a refusal`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val outcome = WebDavPush.register(httpClient, collectionUrl(), "https://up/1", "k", "a", requested)

        assertThat(outcome).isInstanceOf(WebDavPush.Registration.Failed::class.java)
    }

    @Test fun `a 500 carries sabre's exception message`() {
        server.enqueue(
            MockResponse().setResponseCode(500)
                .setHeader("Content-Type", "application/xml; charset=utf-8")
                .setBody(
                """<?xml version="1.0" encoding="utf-8"?>
                <d:error xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns">
                  <s:exception>TypeError</s:exception>
                  <s:message>Call to a member function getTimestamp() on bool</s:message>
                </d:error>""",
            ),
        )

        val outcome = WebDavPush.register(httpClient, collectionUrl(), "https://up/1", "k", "a", requested)

        assertThat(outcome).isEqualTo(
            WebDavPush.Registration.Failed("HTTP 500: Call to a member function getTimestamp() on bool"),
        )
    }

    @Test fun `unregister counts an already expired subscription as removed`() {
        server.enqueue(MockResponse().setResponseCode(404))

        assertThat(WebDavPush.unregister(httpClient, server.url("/sub/1"))).isTrue()
        assertThat(server.takeRequest().method).isEqualTo("DELETE")
    }

    @Test fun `unregister reports a server error as not removed`() {
        server.enqueue(MockResponse().setResponseCode(500))

        assertThat(WebDavPush.unregister(httpClient, server.url("/sub/1"))).isFalse()
    }

    // ---------------------------------------------------------------- writes

    @Test fun `writes name our subscription in Push-Dont-Notify, reads do not`() {
        val registration = server.url("/sub/1")
        val collection = CalendarCollection(httpClient, collectionUrl(), pushRegistration = registration)
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"e1\""))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(stateResponse(push = ""))

        collection.create("a.ics", "BEGIN:VCALENDAR")
        collection.delete(collectionUrl().resolve("a.ics")!!, "e1")
        collection.state()

        assertThat(server.takeRequest().getHeader("Push-Dont-Notify")).isEqualTo("\"$registration\"")
        assertThat(server.takeRequest().getHeader("Push-Dont-Notify")).isEqualTo("\"$registration\"")
        assertThat(server.takeRequest().getHeader("Push-Dont-Notify")).isNull()
    }

    // -------------------------------------------------------------- messages

    @Test fun `a content update carries its topic and sync token`() {
        val message = WebDavPush.parse(
            """
            <?xml version="1.0" encoding="utf-8" ?>
            <push-message xmlns="https://bitfire.at/webdav-push" xmlns:D="DAV:">
              <topic>O7M1nQ7cKkKTKsoS_j6Z3w</topic>
              <content-update>
                <D:sync-token>http://example.com/sync/10</D:sync-token>
              </content-update>
              <property-update />
            </push-message>
            """.trimIndent(),
        )

        assertThat(message).isEqualTo(
            WebDavPush.Message(
                topic = "O7M1nQ7cKkKTKsoS_j6Z3w",
                syncToken = "http://example.com/sync/10",
                keyRotation = false,
            ),
        )
    }

    @Test fun `a transports update without a topic is a key rotation`() {
        val message = WebDavPush.parse(
            """
            <?xml version="1.0" encoding="utf-8" ?>
            <push-message xmlns="https://bitfire.at/webdav-push" xmlns:D="DAV:">
              <property-update>
                <D:prop>
                  <transports />
                </D:prop>
              </property-update>
            </push-message>
            """.trimIndent(),
        )

        assertThat(message).isEqualTo(WebDavPush.Message(topic = null, syncToken = null, keyRotation = true))
    }

    @Test fun `anything that is not a push message is null`() {
        assertThat(WebDavPush.parse("not xml")).isNull()
        assertThat(WebDavPush.parse("<multistatus xmlns=\"DAV:\"/>")).isNull()
    }

    private fun stateResponse(push: String) = MockResponse()
        .setResponseCode(207)
        .setHeader("Content-Type", "application/xml; charset=utf-8")
        .setBody(
            """
            <multistatus xmlns="DAV:" xmlns:P="https://bitfire.at/webdav-push">
              <response>
                <href>/dav/tasks/</href>
                <propstat><prop>
                  <resourcetype><collection/><C:calendar xmlns:C="urn:ietf:params:xml:ns:caldav"/></resourcetype>
                  $push
                </prop><status>HTTP/1.1 200 OK</status></propstat>
              </response>
            </multistatus>
            """.trimIndent(),
        )

    private companion object {
        const val VAPID =
            "BA1Hxzyi1RUM1b5wjxsn7nGxAszw2u61m164i3MrAIxHF6YK5h4SDYic-dRuU_RCPCfA5aq9ojSwk5Y2EmClBPs"

        const val PUSH_PROPS = """
            <P:transports>
              <P:web-push>
                <P:vapid-public-key type="p256ecdsa">$VAPID</P:vapid-public-key>
              </P:web-push>
            </P:transports>
            <P:topic>O7M1nQ7cKkKTKsoS_j6Z3w</P:topic>
            <P:supported-triggers>
              <P:content-update><depth>1</depth></P:content-update>
            </P:supported-triggers>
        """
    }
}
