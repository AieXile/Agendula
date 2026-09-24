package de.jeanlucmakiola.caldav

import at.bitfire.dav4jvm.DavCollection
import at.bitfire.dav4jvm.DavResource
import at.bitfire.dav4jvm.HttpUtils
import at.bitfire.dav4jvm.Property
import at.bitfire.dav4jvm.XmlUtils
import at.bitfire.dav4jvm.XmlUtils.insertTag
import at.bitfire.dav4jvm.XmlUtils.propertyName
import at.bitfire.dav4jvm.exception.DavException
import at.bitfire.dav4jvm.exception.HttpException
import at.bitfire.dav4jvm.property.push.WebDAVPush
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.util.Date
import kotlin.time.Instant

/** What a collection's server offers for WebDAV-Push over Web Push. */
data class PushSupport(
    /** Carried by every push message about this collection. */
    val topic: String,
    /** The server's VAPID key; absent means unauthenticated pushes. */
    val vapidPublicKey: String?,
)

/**
 * The client side of the WebDAV-Push draft (https://github.com/bitfireAT/webdav-push):
 * subscribing, unsubscribing and reading messages. Returns outcomes, never throws.
 */
object WebDavPush {

    sealed interface Registration {
        /** Accepted. [url] is where it lives; null if the server sent no `Location`. */
        data class Registered(val url: HttpUrl?, val expires: Instant) : Registration

        /** A definite no — push unavailable here, or our subscription refused. */
        data class Refused(val code: Int) : Registration

        /** Nothing definite: the network, or a server error worth retrying. */
        data class Failed(val reason: String) : Registration
    }

    /** A decoded push message. */
    data class Message(
        /** Which resource changed; null only for a VAPID key rotation. */
        val topic: String?,
        /** The collection's sync token after the change, when the server says. */
        val syncToken: String?,
        /** The server's VAPID key changed and every subscription must be renewed. */
        val keyRotation: Boolean,
    )

    /**
     * Subscribes [endpoint] to changes in [collection] and its members. Registering
     * the same endpoint again renews it.
     *
     * @param publicKey the subscription's p256dh key, base64url.
     * @param authSecret the subscription's auth secret, base64url; sent only with [publicKey].
     */
    fun register(
        client: OkHttpClient,
        collection: HttpUrl,
        endpoint: String,
        publicKey: String?,
        authSecret: String?,
        expires: Instant,
    ): Registration = try {
        var result: Registration = Registration.Failed("no response")
        val body = registerBody(endpoint, publicKey, authSecret, expires).toRequestBody(MIME_XML)
        DavCollection(client, collection).post(body) { response ->
            val location = response.header("Location")?.let { collection.resolve(it) }
            // The server may shorten what we asked for.
            val granted = response.header("Expires")
                ?.let { HttpUtils.parseDate(it) }
                ?.let { Instant.fromEpochMilliseconds(it.time) }
            result = Registration.Registered(location, granted ?: expires)
        }
        result
    } catch (e: HttpException) {
        if (e.code / 100 == 4) {
            Registration.Refused(e.code)
        } else {
            Registration.Failed(listOfNotNull("HTTP ${e.code}", serverMessage(e.responseBody)).joinToString(": "))
        }
    } catch (e: DavException) {
        Registration.Failed(e.toString())
    } catch (e: IOException) {
        Registration.Failed(e.toString())
    }

    /** The exception text sabre-based servers put in an error body, for the log. */
    internal fun serverMessage(body: String?): String? =
        body?.let { MESSAGE.find(it)?.groupValues?.get(1)?.trim() }?.takeIf { it.isNotEmpty() }

    /** Removes the subscription at [registration]; false only when it may still exist. */
    fun unregister(client: OkHttpClient, registration: HttpUrl): Boolean = try {
        DavResource(client, registration).delete { }
        true
    } catch (e: HttpException) {
        e.code == NOT_FOUND || e.code == GONE
    } catch (_: DavException) {
        false
    } catch (_: IOException) {
        false
    }

    /** `Push-Dont-Notify` for our own writes, so they do not wake us. */
    fun dontNotifyHeader(registration: HttpUrl): String = "\"$registration\""

    /** Decodes a decrypted push message, or null when it is not one. */
    fun parse(message: String): Message? = try {
        val parser = XmlUtils.newPullParser()
        parser.setInput(StringReader(message))
        var result: Message? = null
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT && result == null) {
            if (eventType == XmlPullParser.START_TAG && parser.propertyName() == WebDAVPush.PushMessage) {
                result = readMessage(parser)
            }
            eventType = parser.next()
        }
        result
    } catch (_: XmlPullParserException) {
        null
    } catch (_: IOException) {
        null
    }

    private fun readMessage(parser: XmlPullParser): Message {
        var topic: String? = null
        var syncToken: String? = null
        var keyRotation = false
        val depth = parser.depth
        var eventType = parser.eventType
        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.propertyName()) {
                    WebDAVPush.Topic -> topic = XmlUtils.readText(parser)?.trim()?.takeIf { it.isNotEmpty() }
                    SYNC_TOKEN -> syncToken = XmlUtils.readText(parser)?.trim()?.takeIf { it.isNotEmpty() }
                    // A key rotation names the transports property.
                    WebDAVPush.Transports -> keyRotation = true
                }
            }
            eventType = parser.next()
        }
        return Message(topic = topic, syncToken = syncToken, keyRotation = keyRotation && topic == null)
    }

    internal fun registerBody(
        endpoint: String,
        publicKey: String?,
        authSecret: String?,
        expires: Instant,
    ): String {
        val serializer = XmlUtils.newSerializer()
        val writer = StringWriter()
        serializer.setOutput(writer)
        serializer.startDocument("UTF-8", true)
        serializer.setPrefix("", WebDAVPush.NS_WEBDAV_PUSH)
        serializer.setPrefix("D", XmlUtils.NS_WEBDAV)
        serializer.insertTag(WebDAVPush.PushRegister) {
            insertTag(WebDAVPush.Subscription) {
                insertTag(WebDAVPush.WebPushSubscription) {
                    insertTag(WebDAVPush.PushResource) { text(endpoint) }
                    if (publicKey != null && authSecret != null) {
                        insertTag(WebDAVPush.ContentEncoding) { text("aes128gcm") }
                        insertTag(WebDAVPush.SubscriptionPublicKey) {
                            attribute(null, "type", "p256dh")
                            text(publicKey)
                        }
                        insertTag(WebDAVPush.AuthSecret) { text(authSecret) }
                    }
                }
            }
            // Depth 1 covers the tasks; property changes are left to the periodic sync.
            insertTag(WebDAVPush.Trigger) {
                insertTag(WebDAVPush.ContentUpdate) {
                    insertTag(DEPTH) { text("1") }
                }
            }
            insertTag(WebDAVPush.Expires) {
                text(HttpUtils.formatDate(Date(expires.toEpochMilliseconds())))
            }
        }
        serializer.endDocument()
        return writer.toString()
    }

    private val MIME_XML = "application/xml; charset=utf-8".toMediaType()
    private val DEPTH = Property.Name(XmlUtils.NS_WEBDAV, "depth")
    private val SYNC_TOKEN = Property.Name(XmlUtils.NS_WEBDAV, "sync-token")
    private val MESSAGE = Regex("<(?:[A-Za-z0-9]+:)?message>(.*?)</(?:[A-Za-z0-9]+:)?message>", RegexOption.DOT_MATCHES_ALL)
    private const val NOT_FOUND = 404
    private const val GONE = 410
}
