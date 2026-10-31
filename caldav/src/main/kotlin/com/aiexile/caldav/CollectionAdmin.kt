package com.aiexile.caldav

import at.bitfire.dav4jvm.Property
import at.bitfire.dav4jvm.XmlUtils
import at.bitfire.dav4jvm.XmlUtils.insertTag
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.StringWriter

/**
 * What a server will let us do to the collections themselves.
 *
 * ⚠️ Asked, never assumed, because the alternative is an affordance that
 * 405s: **iCloud is
 * extended-MKCOL only** and answers 405 to MKCALENDAR, **Google has neither**
 * (and no VTODO either), and **Posteo disables collection creation outright**.
 * Offering "new list on this account" to any of them produces a failure the user
 * can do nothing about, at the end of a form they have already filled in.
 */
data class CollectionSupport(
    /** RFC 4791 §5.3.1. The straightforward route, and what Nextcloud wants. */
    val mkCalendar: Boolean,
    /** RFC 5689: MKCOL carrying a body that sets `resourcetype`. iCloud's route. */
    val extendedMkCol: Boolean,
) {
    /** Whether a "new list here" affordance should exist at all. */
    val canCreate: Boolean get() = mkCalendar || extendedMkCol

    companion object {
        /** What an OPTIONS we could not read has to mean: offer nothing. */
        val NONE = CollectionSupport(mkCalendar = false, extendedMkCol = false)
    }
}

/** How a collection write ended. */
sealed interface CollectionOutcome {
    data class Created(val url: HttpUrl) : CollectionOutcome
    data object Updated : CollectionOutcome

    /**
     * The server understood and refused. Terminal: retrying writes nothing and
     * the user has to be told.
     *
     * [code] is for logs and for deciding; the caller owns the wording, the same
     * rule [CalDavDiscovery.Outcome.Cause] states.
     */
    data class Refused(val code: Int, val reason: String) : CollectionOutcome

    /** The server never answered. Worth retrying, unlike [Refused]. */
    data class Failed(val reason: String) : CollectionOutcome

    /** Neither creation method exists here. Nothing to retry and nothing to fix. */
    data object Unsupported : CollectionOutcome
}

/**
 * Creating, renaming, recolouring and deleting task collections.
 *
 * A seam, like [RemoteCalendar]: deciding *what* to do to a collection — refuse
 * a read-only one, pick a name that will not collide, roll a local row back when
 * the server says no — is the app's business and should not need a server to
 * exercise. [DavCollectionAdmin] is the only production implementation.
 */
interface CollectionAdmin {

    /** What [homeSet] supports, as an OPTIONS rather than a guess. */
    fun support(homeSet: HttpUrl): CollectionSupport

    /**
     * Makes a task collection under [homeSet].
     *
     * @param name the path segment to create it at. The caller owns collision
     *   handling, because only it knows whether a second attempt is wanted.
     */
    fun create(
        homeSet: HttpUrl,
        name: String,
        displayName: String,
        color: Int?,
        support: CollectionSupport,
    ): CollectionOutcome

    /** PROPPATCH: a rename, a recolour, or both. */
    fun updateProperties(url: HttpUrl, displayName: String?, color: Int?): CollectionOutcome

    /** DELETE. The tasks go with it, on the server and everywhere else. */
    fun delete(url: HttpUrl): CollectionOutcome
}

class DavCollectionAdmin(
    private val httpClient: OkHttpClient,
) : CollectionAdmin {

    /**
     * ⚠️ Both headers, and neither on its own is enough.
     *
     * `Allow` names the methods, which is where MKCALENDAR shows up; `DAV` names
     * the compliance classes, which is the only place `extended-mkcol` is ever
     * advertised (RFC 5689 §5.1). A server may support extended MKCOL while
     * listing plain MKCOL in `Allow` — that is the normal shape — so reading
     * `Allow` alone reports iCloud as unable to create anything.
     */
    override fun support(homeSet: HttpUrl): CollectionSupport = try {
        // No compression: some servers have broken compression for OPTIONS,
        // which is why DavResource disables it for the same request.
        val request = Request.Builder()
            .url(homeSet)
            .method("OPTIONS", null)
            .header("Content-Length", "0")
            .header("Accept-Encoding", "identity")
            .build()
        Redirects.follow(httpClient, request).use { response ->
            if (!response.isSuccessful) return CollectionSupport.NONE
            val allow = headerTokens(response.headers("Allow"))
            val dav = headerTokens(response.headers("DAV"))
            CollectionSupport(
                mkCalendar = "MKCALENDAR" in allow,
                extendedMkCol = "EXTENDED-MKCOL" in dav,
            )
        }
    } catch (_: IOException) {
        // Unknown reads as "no", which costs the user an affordance they can
        // reach again on the next attempt — the other way round costs them a
        // 405 at the end of a form.
        CollectionSupport.NONE
    }

    /**
     * ⚠️ MKCALENDAR first where both exist. It is the method written for this
     * job, every server that has it treats it as authoritative, and extended
     * MKCOL's `resourcetype` set is refused outright by some servers that
     * nonetheless advertise the class.
     */
    override fun create(
        homeSet: HttpUrl,
        name: String,
        displayName: String,
        color: Int?,
        support: CollectionSupport,
    ): CollectionOutcome {
        if (!support.canCreate) return CollectionOutcome.Unsupported
        // A trailing slash, always: a collection URL that lacks one is resolved
        // against its *parent* by every later `resolve`, so the first resource
        // written into it lands one level up.
        val url = homeSet.newBuilder().addPathSegment(name).addPathSegment("").build()
        val (method, body) = if (support.mkCalendar) {
            "MKCALENDAR" to mkCalendarBody(displayName, color)
        } else {
            "MKCOL" to extendedMkColBody(displayName, color)
        }
        return send(method, url, body) { CollectionOutcome.Created(url) }
    }

    override fun updateProperties(
        url: HttpUrl,
        displayName: String?,
        color: Int?,
    ): CollectionOutcome {
        if (displayName == null && color == null) return CollectionOutcome.Updated
        return send("PROPPATCH", url, propPatchBody(displayName, color)) {
            // ⚠️ The 207 is not read for per-property statuses, deliberately. A
            // server that stores the name and refuses the colour answers 207
            // with a 403 propstat around `calendar-color` — and there is nothing
            // for the user to do about a colour their server will not keep,
            // while failing the whole rename over it would be worse. The colour
            // is ours locally either way.
            CollectionOutcome.Updated
        }
    }

    override fun delete(url: HttpUrl): CollectionOutcome {
        val request = Request.Builder().url(url).delete().build()
        return execute(request) { response ->
            // ⚠️ 404 and 410 are success, exactly as they are for a resource:
            // the point of a DELETE is for the thing to be absent, and it is.
            when {
                response.isSuccessful || response.code == NOT_FOUND || response.code == GONE ->
                    CollectionOutcome.Updated
                else -> CollectionOutcome.Refused(response.code, response.message)
            }
        }
    }

    private fun send(
        method: String,
        url: HttpUrl,
        body: String,
        onSuccess: () -> CollectionOutcome,
    ): CollectionOutcome {
        val request = Request.Builder()
            .url(url)
            .method(method, body.toRequestBody(MIME_XML))
            .build()
        return execute(request) { response ->
            if (response.isSuccessful) onSuccess() else {
                CollectionOutcome.Refused(response.code, response.message)
            }
        }
    }

    private fun execute(
        request: Request,
        grade: (okhttp3.Response) -> CollectionOutcome,
    ): CollectionOutcome = try {
        Redirects.follow(httpClient, request).use(grade)
    } catch (e: IOException) {
        CollectionOutcome.Failed(e.message ?: e.toString())
    }

    /**
     * ⚠️ `VTODO` and nothing else.
     *
     * The component set is the one property here that changes what the
     * collection *is*: a calendar created without it is a
     * `supported-calendar-component-set` of the server's choosing, which on
     * several is events-only — and a task list that refuses tasks is the worst
     * possible outcome of "new list".
     */
    private fun mkCalendarBody(displayName: String, color: Int?): String = xml { serializer ->
        serializer.insertTag(MKCALENDAR) {
            insertTag(SET) {
                insertTag(PROP) {
                    insertTag(DISPLAYNAME) { text(displayName) }
                    insertTag(COMPONENT_SET) {
                        insertTag(COMP) { attribute(null, "name", COMPONENT) }
                    }
                    color?.let { insertTag(CALENDAR_COLOR) { text(hexOf(it)) } }
                }
            }
        }
    }

    /**
     * RFC 5689, where the resource type is set by the request rather than by the
     * method — which is the whole difference, and the reason iCloud needs this
     * one.
     */
    private fun extendedMkColBody(displayName: String, color: Int?): String = xml { serializer ->
        serializer.insertTag(MKCOL) {
            insertTag(SET) {
                insertTag(PROP) {
                    insertTag(RESOURCETYPE) {
                        insertTag(COLLECTION)
                        insertTag(CALENDAR)
                    }
                    insertTag(DISPLAYNAME) { text(displayName) }
                    insertTag(COMPONENT_SET) {
                        insertTag(COMP) { attribute(null, "name", COMPONENT) }
                    }
                    color?.let { insertTag(CALENDAR_COLOR) { text(hexOf(it)) } }
                }
            }
        }
    }

    private fun propPatchBody(displayName: String?, color: Int?): String = xml { serializer ->
        serializer.insertTag(PROPERTYUPDATE) {
            insertTag(SET) {
                insertTag(PROP) {
                    displayName?.let { insertTag(DISPLAYNAME) { text(it) } }
                    color?.let { insertTag(CALENDAR_COLOR) { text(hexOf(it)) } }
                }
            }
        }
    }

    private fun xml(build: (org.xmlpull.v1.XmlSerializer) -> Unit): String {
        val serializer = XmlUtils.newSerializer()
        val writer = StringWriter()
        serializer.setOutput(writer)
        serializer.setPrefix("d", XmlUtils.NS_WEBDAV)
        serializer.setPrefix("c", XmlUtils.NS_CALDAV)
        serializer.setPrefix("i", XmlUtils.NS_APPLE_ICAL)
        serializer.startDocument("UTF-8", null)
        build(serializer)
        serializer.endDocument()
        return writer.toString()
    }

    /**
     * ⚠️ `#RRGGBBAA`, which is what Apple's `calendar-color` is and what
     * [at.bitfire.dav4jvm.property.CalendarColor] parses back. Writing the
     * packed ARGB `Int` verbatim yields "-65536" for red, which is not a colour
     * in any spelling.
     */
    private fun hexOf(argb: Int): String = "#%06X%02X".format(argb and 0xFFFFFF, argb ushr 24)

    /**
     * A comma-separated header, split and normalised.
     *
     * Repeated headers *and* comma lists, because servers use both spellings for
     * `DAV` and for `Allow`.
     */
    private fun headerTokens(values: List<String>): Set<String> = values
        .flatMap { it.split(',') }
        .map { it.trim().uppercase() }
        .filter { it.isNotEmpty() }
        .toSet()

    private companion object {
        const val COMPONENT = "VTODO"
        const val NOT_FOUND = 404
        const val GONE = 410
        val MIME_XML = "application/xml; charset=utf-8".toMediaType()

        val MKCALENDAR = Property.Name(XmlUtils.NS_CALDAV, "mkcalendar")
        val CALENDAR = Property.Name(XmlUtils.NS_CALDAV, "calendar")
        val COMPONENT_SET = Property.Name(XmlUtils.NS_CALDAV, "supported-calendar-component-set")
        val COMP = Property.Name(XmlUtils.NS_CALDAV, "comp")
        val MKCOL = Property.Name(XmlUtils.NS_WEBDAV, "mkcol")
        val SET = Property.Name(XmlUtils.NS_WEBDAV, "set")
        val PROP = Property.Name(XmlUtils.NS_WEBDAV, "prop")
        val PROPERTYUPDATE = Property.Name(XmlUtils.NS_WEBDAV, "propertyupdate")
        val DISPLAYNAME = Property.Name(XmlUtils.NS_WEBDAV, "displayname")
        val RESOURCETYPE = Property.Name(XmlUtils.NS_WEBDAV, "resourcetype")
        val COLLECTION = Property.Name(XmlUtils.NS_WEBDAV, "collection")
        val CALENDAR_COLOR = Property.Name(XmlUtils.NS_APPLE_ICAL, "calendar-color")
    }
}
