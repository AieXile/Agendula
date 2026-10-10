package de.jeanlucmakiola.agendula.domain.export

import de.jeanlucmakiola.agendula.domain.Priority
import de.jeanlucmakiola.agendula.domain.TaskStatus
import de.jeanlucmakiola.agendula.domain.calendarDate
import de.jeanlucmakiola.agendula.domain.ical.ICalSerializer
import de.jeanlucmakiola.agendula.domain.ical.ICalValues
import de.jeanlucmakiola.agendula.domain.toICal
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Instant

/**
 * Writes a task list as an RFC 5545 `VCALENDAR` of `VTODO` components.
 *
 * Pure Kotlin and deliberately free of any Android type, so the format — the part
 * that decides whether an exported backup can actually be read again — is
 * unit-testable on the JVM. Serialising tasks is task-domain and stays here; the
 * SAF/file plumbing that carries the bytes out is not, and lives in the data
 * layer (and is a floret-kit candidate).
 *
 * **Times are always written in UTC.** Emitting a local `TZID` would oblige us to
 * also emit a matching `VTIMEZONE` component with its full transition rules, and
 * a `TZID` referencing an absent definition is what actually breaks importers. UTC
 * is unambiguous and universally accepted, so the exported instant is exact even
 * though the original wall-clock zone is not carried. All-day values keep their
 * `VALUE=DATE` form and stay date-only, which is the only representation that
 * survives a timezone change intact.
 */
object ICalendarWriter {

    private const val PRODUCT_ID = "-//Jean-Luc Makiola//Agendula//EN"

    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val DATE_TIME_UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    /** Serialises [list] to a complete `.ics` document. */
    fun write(list: ExportList): String = buildString {
        line("BEGIN:VCALENDAR")
        line("VERSION:2.0")
        line("PRODID:$PRODUCT_ID")
        line("CALSCALE:GREGORIAN")
        // Non-standard but near-universally understood, and the only way the list's
        // name survives into a calendar app. Importers that don't know it skip it.
        property("X-WR-CALNAME", list.name)

        // Parents must be addressable by UID, and a subtask may appear before its
        // parent in the list, so resolve every id up front.
        val uidsById = list.tasks.associate { it.taskId to uidFor(it) }
        list.tasks.forEach { task -> writeTask(task, uidsById) }

        line("END:VCALENDAR")
    }

    /**
     * The UID to write for [task].
     *
     * Local tasks have none, because nothing assigns one: the provider never
     * generates a `_uid` itself, and our write path does not set it either, so in
     * Local mode every task arrives here with `uid == null`.
     *
     * Note this is a gap we leave open, not one the provider imposes.
     * `processors/tasks/Validating.java:92-96` restricts `_uid` to sync adapters
     * on *update* only; `insert` does not check it, so any caller may assign a UID
     * at creation. Doing that would be strictly better than synthesising here —
     * a real UID minted at
     * creation is what lets a local task later be pushed to CalDAV without
     * duplicating.
     *
     * Until then: a VTODO without a UID is invalid and, worse, un-mergeable —
     * re-importing a backup would duplicate every task instead of matching it. So
     * we synthesise one from the row id, which is stable for as long as the row
     * is, and tag it with our own domain so a synthesised UID is recognisable as
     * such.
     */
    fun uidFor(task: ExportTask): String =
        task.uid?.takeIf { it.isNotBlank() } ?: "agendula-${task.taskId}@jeanlucmakiola.de"

    private fun StringBuilder.writeTask(task: ExportTask, uidsById: Map<Long, String>) {
        line("BEGIN:VTODO")
        property("UID", uidFor(task))
        // DTSTAMP is mandatory. It means "when this representation was written",
        // which for an export is now — not the task's own timestamps.
        property("DTSTAMP", formatUtc(Instant.fromEpochMilliseconds(System.currentTimeMillis())))
        property("SUMMARY", task.title)

        task.description?.takeIf { it.isNotBlank() }?.let { property("DESCRIPTION", it) }
        task.location?.takeIf { it.isNotBlank() }?.let { property("LOCATION", it) }
        // URL is a URI, not TEXT: it must not be escaped like one.
        task.url?.takeIf { it.isNotBlank() }?.let { rawProperty("URL", it) }
        task.tags.takeIf { it.isNotEmpty() }?.let { rawProperty("CATEGORIES", ICalValues.formatTextList(it)) }

        task.start?.let { dateProperty("DTSTART", it, task.isAllDay) }
        task.due?.let { dateProperty("DUE", it, task.isAllDay) }
        task.created?.let { rawProperty("CREATED", formatUtc(it)) }
        task.lastModified?.let { rawProperty("LAST-MODIFIED", formatUtc(it)) }
        // COMPLETED is defined as UTC date-time even for an all-day task.
        task.completedAt?.let { rawProperty("COMPLETED", formatUtc(it)) }

        rawProperty("STATUS", task.status.toICalName())
        if (task.priority != Priority.NONE) rawProperty("PRIORITY", task.priority.toICal().toString())
        task.percentComplete?.coerceIn(0, 100)?.let { rawProperty("PERCENT-COMPLETE", it.toString()) }

        // Passed through as stored. The provider keeps these in iCalendar form
        // already, and re-deriving them would risk changing what the user's
        // recurrence actually means.
        task.rrule?.takeIf { it.isNotBlank() }?.let { rawProperty("RRULE", it) }
        task.rdate?.takeIf { it.isNotBlank() }?.let { rawProperty("RDATE", it) }

        // Only emit the link when the parent is in this same document; a
        // RELATED-TO pointing outside the file would dangle on import.
        task.parentId?.let { uidsById[it] }?.let {
            property("RELATED-TO;RELTYPE=PARENT", it)
        }

        line("END:VTODO")
    }

    private fun StringBuilder.dateProperty(name: String, instant: Instant, allDay: Boolean) {
        if (allDay) {
            // Read in UTC, matching the storage convention (see AllDayTime): an
            // all-day value *is* UTC midnight of the intended calendar date.
            rawProperty("$name;VALUE=DATE", instant.calendarDate(allDay = true).format(DATE))
        } else {
            rawProperty(name, formatUtc(instant))
        }
    }

    private fun formatUtc(instant: Instant): String =
        java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds())
            .atZone(ZoneOffset.UTC)
            .format(DATE_TIME_UTC)

    /** A property whose value is TEXT, and so must be escaped. */
    private fun StringBuilder.property(name: String, value: String) =
        line("$name:${escapeText(value)}")

    /** A property whose value is already in its final form (dates, numbers, URIs, rules). */
    private fun StringBuilder.rawProperty(name: String, value: String) = line("$name:$value")

    private fun StringBuilder.line(content: String) {
        append(fold(content))
        append(CRLF)
    }

    /**
     * TEXT escaping and folding are the same operations the sync mapper needs,
     * and having two implementations of either is how the two halves drift.
     * These delegate; the implementations live in `domain/ical/`.
     *
     * This writer itself stays separate from `VTodoMapper` on purpose: it
     * serialises **domain** export models, which exist in both storage modes,
     * whereas the mapper serialises Room entities, which exist only in one.
     */
    internal fun escapeText(value: String): String = ICalValues.escapeText(value)

    internal fun fold(content: String): String = ICalSerializer.fold(content)

    private fun TaskStatus.toICalName(): String = when (this) {
        TaskStatus.NEEDS_ACTION -> "NEEDS-ACTION"
        TaskStatus.IN_PROCESS -> "IN-PROCESS"
        TaskStatus.COMPLETED -> "COMPLETED"
        TaskStatus.CANCELLED -> "CANCELLED"
    }

    private const val CRLF = "\r\n"
}
