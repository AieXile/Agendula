package de.jeanlucmakiola.caldav

import at.bitfire.dav4jvm.Property
import at.bitfire.dav4jvm.Response
import at.bitfire.dav4jvm.property.CalendarColor
import at.bitfire.dav4jvm.property.CurrentUserPrivilegeSet
import at.bitfire.dav4jvm.property.DisplayName
import at.bitfire.dav4jvm.property.MaxICalendarSize
import at.bitfire.dav4jvm.property.ResourceType
import at.bitfire.dav4jvm.property.SupportedCalendarComponentSet
import at.bitfire.dav4jvm.property.SupportedReportSet
import at.bitfire.dav4jvm.property.push.PushTransports
import at.bitfire.dav4jvm.property.push.Topic
import okhttp3.HttpUrl

/** A collection that survived classification, and what we know about it. */
data class TaskCollection(
    val url: HttpUrl,
    val displayName: String?,
    /** Packed ARGB, the same form `task_lists.color` stores. */
    val color: Int?,
    val readOnly: Boolean,
    /**
     * A share rather than the user's own collection.
     *
     * Worth carrying: Nextcloud **rewrites task bodies on GET from a shared
     * calendar** — stripping `VALARM`, and reducing `CLASS:CONFIDENTIAL` to a
     * VEVENT-shaped whitelist — while leaving the ETag untouched. Re-PUTting what
     * we downloaded destroys the owner's task, so the engine needs to know.
     */
    val isShared: Boolean,
    val supportsSyncCollection: Boolean,
    val maxResourceSize: Long?,
    /** WebDAV-Push over Web Push, when the server offers it for this collection. */
    val push: PushSupport? = null,
)

/**
 * Decides which collections in a Depth-1 listing can hold tasks.
 *
 * Both of the rules here are **inversions of the obvious one**, and they are
 * the important part of discovery. Getting either
 * backwards produces a client that silently finds nothing on a large fraction of
 * real servers, with no error to diagnose.
 */
object CollectionClassifier {

    /**
     * The properties [classify] reads, requested **by name**.
     *
     * `allprop` legitimately omits every one of them (RFC 4791 §5.2.3 for the
     * component set, RFC 3744 §3.7 for the privileges), so asking for them by
     * name is not an optimisation — it is the only way to get them.
     */
    val PROPERTIES = arrayOf(
        ResourceType.NAME,
        DisplayName.NAME,
        CalendarColor.NAME,
        SupportedCalendarComponentSet.NAME,
        SupportedReportSet.NAME,
        CurrentUserPrivilegeSet.NAME,
        MaxICalendarSize.NAME,
        PushTransports.NAME,
        Topic.NAME,
    )

    /** `CS:shared`, which Nextcloud adds alongside `caldav:calendar` on a share. */
    private val SHARED = Property.Name("http://calendarserver.org/ns/", "shared")

    fun classify(response: Response): TaskCollection? {
        val resourceType = response[ResourceType::class.java] ?: return null

        // ⚠️ Positive test on an *unordered set*, never exclusion and never by
        // position. Excluding `schedule-outbox` — the obvious rule — drops SOGo's
        // main personal calendar, which reports collection + calendar +
        // schedule-outbox simultaneously for every non-Apple client, i.e. for us.
        // The positive rule also keeps shared calendars (they add CS:shared) and
        // still drops Nextcloud's nc:deleted-calendar, which deliberately strips
        // caldav:calendar.
        if (ResourceType.CALENDAR !in resourceType.types) return null

        if (!supportsTasks(response)) return null

        val privileges = response[CurrentUserPrivilegeSet::class.java]
        // Absent means writable. RFC 3744 §3.7 lets a server withhold the
        // property, and DAVx5 defaults to writable for the same reason: assuming
        // read-only hides collections the user can perfectly well write to.
        // A 403 on write is handled where it happens.
        val readOnly = privileges != null &&
            !privileges.mayWriteContent && !privileges.mayBind

        val reports = response[SupportedReportSet::class.java]

        return TaskCollection(
            url = response.href,
            displayName = response[DisplayName::class.java]?.displayName,
            // CalendarColor.color is a packed ARGB Int. Calling toString() on it
            // yields "-65536" for red — an unparseable decimal, not a colour.
            color = response[CalendarColor::class.java]?.color,
            readOnly = readOnly,
            isShared = SHARED in resourceType.types,
            // A hint, not a contract — Radicale advertised this for years without
            // implementing it, so the engine must still degrade gracefully.
            supportsSyncCollection = reports?.reports?.contains(SupportedReportSet.SYNC_COLLECTION) == true,
            maxResourceSize = response[MaxICalendarSize::class.java]?.maxSize,
            push = pushSupport(response),
        )
    }

    /**
     * Push needs both halves: a Web Push transport to subscribe with and a topic
     * to recognise the messages by. Either alone is useless, so either alone is
     * no support at all.
     */
    internal fun pushSupport(response: Response): PushSupport? {
        val webPush = response[PushTransports::class.java]?.webPush ?: return null
        val topic = response[Topic::class.java]?.topic ?: return null
        return PushSupport(topic = topic, vapidPublicKey = webPush.vapidPublicKey)
    }

    /**
     * ⚠️ **An absent `supported-calendar-component-set` means "supports
     * everything", not "supports nothing".**
     *
     * RFC 4791 §5.2.3 says the property SHOULD NOT be returned from an allprop
     * request, so it is legitimately missing unless asked for **by name** — and
     * even then plenty of servers omit it. Keeping only collections whose set
     * *includes* VTODO therefore drops every server that does not advertise it.
     *
     * The grammar is `(comp+)`; an empty element is non-conformant, and
     * dav4jvm's parser starts all-`false`, so an empty one arrives
     * indistinguishable from "no VTODO". Treat that as all too — the cost of
     * being wrong is an empty collection list, and the cost of the other error is
     * a task list the user cannot reach.
     */
    internal fun supportsTasks(response: Response): Boolean {
        val components = response[SupportedCalendarComponentSet::class.java] ?: return true
        val advertisesNothing = !components.supportsEvents &&
            !components.supportsTasks &&
            !components.supportsJournal
        if (advertisesNothing) return true
        return components.supportsTasks
    }

}
