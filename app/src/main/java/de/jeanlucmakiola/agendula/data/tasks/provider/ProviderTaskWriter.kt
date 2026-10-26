package de.jeanlucmakiola.agendula.data.tasks.provider

import de.jeanlucmakiola.agendula.data.tasks.TasksContract
import de.jeanlucmakiola.agendula.data.tasks.TasksContract.Tasks
import de.jeanlucmakiola.agendula.data.tasks.room.TaskEntity
import de.jeanlucmakiola.agendula.data.tasks.room.forAllDay
import de.jeanlucmakiola.agendula.domain.TaskStatus
import de.jeanlucmakiola.agendula.domain.statusFromInt
import org.dmfs.rfc5545.recur.RecurrenceRule
import java.time.ZoneId
import kotlin.time.Instant

/** A client's `ContentValues`, as plain Kotlin values. */
typealias ProviderValues = Map<String, Any?>

/** The `tasks` columns a client may write. Everything else the table publishes is ignored on a write. */
val WRITABLE_TASK_COLUMNS: Set<String> = setOf(
    Tasks.LIST_ID, Tasks.UID, Tasks.TITLE, Tasks.DESCRIPTION, Tasks.LOCATION, Tasks.URL, Tasks.PRIORITY,
    Tasks.CLASSIFICATION, Tasks.STATUS, Tasks.PERCENT_COMPLETE, Tasks.COMPLETED, Tasks.TASK_COLOR, Tasks.DTSTART,
    Tasks.DUE, Tasks.DURATION, Tasks.IS_ALLDAY, Tasks.TZ, Tasks.RRULE, Tasks.RDATE, Tasks.EXDATE, Tasks.PARENT_ID,
    Tasks.SORTING,
)

/** The columns that say whether a task, or one occurrence of it, is done. */
val COMPLETION_COLUMNS: Set<String> = setOf(Tasks.STATUS, Tasks.PERCENT_COMPLETE, Tasks.COMPLETED)

/** The columns that place a task in time; one occurrence of a series cannot be moved through the provider. */
val TIME_COLUMNS: Set<String> = setOf(
    Tasks.DTSTART, Tasks.DUE, Tasks.DURATION, Tasks.IS_ALLDAY, Tasks.TZ, Tasks.RRULE, Tasks.RDATE, Tasks.EXDATE,
)

/** What a write to one occurrence of a series does to it. */
enum class OccurrenceChange { COMPLETE, CANCEL, REOPEN }

private val ICAL_DATE = Regex("""\d{8}(T\d{6}Z?)?""")

private val ICAL_DURATION = Regex("""[+-]?P(\d+W|(\d+D)?(T(\d+H)?(\d+M)?(\d+S)?)?)""")

/**
 * Turns a client's OpenTasks `ContentValues` into [TaskEntity] changes. Pure, so
 * the rules are testable on the JVM. A bad value throws [IllegalArgumentException],
 * which reaches the client as is.
 *
 * Only [WRITABLE_TASK_COLUMNS] change anything. The rest of what the provider
 * publishes is accepted and ignored, so a client can write back a row it read;
 * the fields Agendula keeps for CalDAV (`href`, `etag`, `sequence`, unknown
 * properties) are not published and so cannot be written at all.
 */
object ProviderTaskWriter {

    /**
     * [values] narrowed to the columns in [writable]. A column [table] does not
     * have at all is a client bug and throws.
     */
    fun writable(table: ProviderTable, values: ProviderValues, writable: Set<String>): ProviderValues {
        val known = table.columns.mapTo(HashSet()) { it.first }
        val unknown = values.keys - known
        require(unknown.isEmpty()) { "Unknown column(s) for ${table.path}: ${unknown.sorted().joinToString()}" }
        return values.filterKeys { it in writable }
    }

    /** A new task from [values], which must name its list. */
    fun newTask(values: ProviderValues, uid: String, now: Instant, tzId: String): TaskEntity {
        val v = Values(values)
        val listId = requireNotNull(v.long(Tasks.LIST_ID)) { "A new task needs a ${Tasks.LIST_ID}" }
        val base = TaskEntity(listId = listId, uid = v.string(Tasks.UID)?.trim()?.ifEmpty { null } ?: uid, createdAt = now)
        // As in OpenTasks, a new task inserted without a zone is floating.
        val zoned = if (Tasks.TZ in v) values else values + (Tasks.TZ to null)
        return applied(base, zoned - Tasks.LIST_ID - Tasks.UID, now, tzId)
    }

    /**
     * [current] with [values] applied. Identity and sync columns are left alone,
     * and [current] itself comes back when nothing changed, so a write that
     * changes nothing owes the server nothing.
     */
    fun patch(current: TaskEntity, values: ProviderValues, now: Instant, tzId: String): TaskEntity {
        val edited = applied(current, values, now, tzId)
        return if (edited.copy(lastModified = current.lastModified, isDirty = current.isDirty) == current) current else edited
    }

    /**
     * The columns in [values] that would change [current] on their own. Writing
     * back what a row published changes nothing, so a client that read a row can
     * send all of it with the one value it meant to change.
     */
    fun changedColumns(current: TaskEntity, values: ProviderValues, tzId: String): Set<String> {
        val stamp = current.lastModified ?: Instant.DISTANT_PAST
        return values.keys.filterTo(HashSet()) { key ->
            val edited = runCatching { applied(current, mapOf(key to values[key]), stamp, tzId) }.getOrNull()
            edited == null || edited.copy(lastModified = current.lastModified, isDirty = current.isDirty) != current
        }
    }

    private fun applied(current: TaskEntity, values: ProviderValues, now: Instant, tzId: String): TaskEntity {
        val v = Values(values)
        val isOverride = current.masterId != null
        if (Tasks.UID in v) require(v.string(Tasks.UID)?.trim() == current.uid) { "A task's ${Tasks.UID} cannot change" }
        if (isOverride) {
            // NULL is what the row publishes, so writing it back is fine.
            require(listOf(Tasks.RRULE, Tasks.RDATE, Tasks.EXDATE).none { it in v && v.text(it) != null }) {
                "A modified occurrence carries no recurrence rule of its own"
            }
            if (Tasks.LIST_ID in v) require(v.long(Tasks.LIST_ID) == current.listId) { "A modified occurrence stays in its series' list" }
            if (Tasks.PARENT_ID in v) require(v.long(Tasks.PARENT_ID) == current.parentId) { "A modified occurrence keeps its series' parent" }
        }

        val listId = if (Tasks.LIST_ID in v) requireNotNull(v.long(Tasks.LIST_ID)) { "${Tasks.LIST_ID} cannot be null" } else current.listId
        val parentId = if (Tasks.PARENT_ID in v) v.long(Tasks.PARENT_ID)?.takeIf { it > 0 } else current.parentId
        require(parentId == null || parentId != current.id || current.id == 0L) { "A task cannot be its own parent" }

        val allDay = if (Tasks.IS_ALLDAY in v) v.boolean(Tasks.IS_ALLDAY) else current.isAllDay
        val dtstart = (if (Tasks.DTSTART in v) v.instant(Tasks.DTSTART) else current.dtstart)?.forAllDay(allDay)
        val due = (if (Tasks.DUE in v) v.instant(Tasks.DUE) else current.due)?.forAllDay(allDay)
        val duration = when {
            Tasks.DURATION in v -> v.string(Tasks.DURATION)?.trim()?.ifEmpty { null }
                ?.also { require(ICAL_DURATION.matches(it)) { "Not an iCalendar duration: $it" } }
            Tasks.DUE in v && due != null -> null
            else -> current.duration
        }
        require(due == null || duration == null) { "${Tasks.DUE} and ${Tasks.DURATION} are mutually exclusive" }
        require(dtstart == null || due == null || due >= dtstart) { "${Tasks.DUE} is before ${Tasks.DTSTART}" }
        val timezone = when {
            allDay -> null
            Tasks.TZ in v -> v.string(Tasks.TZ)?.also { zone ->
                require(runCatching { ZoneId.of(zone) }.isSuccess) { "Unknown time zone: $zone" }
            }
            dtstart == null && due == null -> null
            // A timed task without a zone is floating (RFC 5545 §3.3.5) and stays so.
            current.dtstart != null || current.due != null -> if (current.isAllDay) tzId else current.timezone
            else -> current.timezone ?: tzId
        }

        val rrule = if (Tasks.RRULE in v) normalizedRule(v.string(Tasks.RRULE)) else current.rrule
        val ruleDropped = Tasks.RRULE in v && rrule == null && current.rrule != null
        val rdate = if (Tasks.RDATE in v) dateList(Tasks.RDATE, v, current.rdate) else current.rdate.takeUnless { ruleDropped }
        val exdate = if (Tasks.EXDATE in v) dateList(Tasks.EXDATE, v, current.exdate) else current.exdate.takeUnless { ruleDropped }
        require(rrule == null || dtstart != null || due != null) { "A recurring task needs a ${Tasks.DTSTART} or ${Tasks.DUE}" }

        val (status, percent, completedAt) = completion(current, v, now)

        return current.copy(
            listId = listId,
            title = if (Tasks.TITLE in v) v.string(Tasks.TITLE)?.trim() else current.title,
            description = if (Tasks.DESCRIPTION in v) v.text(Tasks.DESCRIPTION) else current.description,
            location = if (Tasks.LOCATION in v) v.text(Tasks.LOCATION) else current.location,
            url = if (Tasks.URL in v) v.text(Tasks.URL) else current.url,
            priority = if (Tasks.PRIORITY in v) v.int(Tasks.PRIORITY, 0..9) ?: TasksContract.PRIORITY_NONE else current.priority,
            classification = if (Tasks.CLASSIFICATION in v) v.int(Tasks.CLASSIFICATION, 0..2) else current.classification,
            color = if (Tasks.TASK_COLOR in v) v.int(Tasks.TASK_COLOR) else current.color,
            sortOrder = if (Tasks.SORTING in v) v.int(Tasks.SORTING) ?: 0 else current.sortOrder,
            status = status,
            percentComplete = percent,
            completedAt = completedAt,
            dtstart = dtstart,
            due = due,
            duration = duration,
            isAllDay = allDay,
            timezone = timezone,
            rrule = rrule,
            rdate = rdate,
            exdate = exdate,
            parentId = parentId,
            lastModified = now,
            isDirty = true,
        )
    }

    /**
     * What [values] do to one occurrence of a series, or `null` when they say
     * nothing about its completion. An occurrence is done, cancelled or open;
     * partial progress belongs to a task, not to one of its occurrences.
     */
    fun occurrenceChange(values: ProviderValues): OccurrenceChange? {
        val v = Values(values.filterKeys { it in COMPLETION_COLUMNS })
        return when {
            Tasks.STATUS in v -> when (v.int(Tasks.STATUS, 0..3)) {
                TasksContract.STATUS_COMPLETED -> OccurrenceChange.COMPLETE
                TasksContract.STATUS_CANCELLED -> OccurrenceChange.CANCEL
                TasksContract.STATUS_NEEDS_ACTION, null -> OccurrenceChange.REOPEN
                else -> throw IllegalArgumentException("An occurrence is completed, cancelled or open, not in process")
            }
            Tasks.PERCENT_COMPLETE in v -> when (v.int(Tasks.PERCENT_COMPLETE, 0..100)) {
                100 -> OccurrenceChange.COMPLETE
                0, null -> OccurrenceChange.REOPEN
                else -> throw IllegalArgumentException("An occurrence is completed or open; partial progress belongs to its task")
            }
            Tasks.COMPLETED in v -> if (v.instant(Tasks.COMPLETED) != null) OccurrenceChange.COMPLETE else OccurrenceChange.REOPEN
            else -> null
        }
    }

    /**
     * Status, progress and completion time after a write. They move together the
     * way OpenTasks keeps them: a status implies its progress and time, progress
     * implies a status, and a completion time alone completes the task. When a
     * row is written back, the column that changed decides, not the ones it
     * carries unchanged.
     */
    private fun completion(current: TaskEntity, values: Values, now: Instant): Triple<TaskStatus, Int?, Instant?> {
        val v = Values(values.map.filterKeys { it !in COMPLETION_COLUMNS || it in completionChanged(current, values) })
        val percent = if (Tasks.PERCENT_COMPLETE in v) v.int(Tasks.PERCENT_COMPLETE, 0..100) else current.percentComplete
        val completed = if (Tasks.COMPLETED in v) v.instant(Tasks.COMPLETED) else current.completedAt
        return when {
            Tasks.STATUS in v -> when (val status = statusFromInt(v.int(Tasks.STATUS, 0..3))) {
                TaskStatus.COMPLETED -> Triple(status, if (Tasks.PERCENT_COMPLETE in v) percent else 100, completed ?: now)
                TaskStatus.NEEDS_ACTION ->
                    Triple(status, if (Tasks.PERCENT_COMPLETE in v) percent?.takeIf { it < 100 } else null, null)
                else -> Triple(status, percent, null)
            }
            Tasks.PERCENT_COMPLETE in v -> when {
                percent == null -> Triple(current.status, null, current.completedAt)
                percent >= 100 -> Triple(TaskStatus.COMPLETED, percent, completed ?: now)
                percent > 0 -> Triple(TaskStatus.IN_PROCESS, percent, null)
                else -> Triple(TaskStatus.NEEDS_ACTION, percent, null)
            }
            Tasks.COMPLETED in v -> when {
                completed != null -> Triple(TaskStatus.COMPLETED, 100, completed)
                // Clearing the time reopens a completed task; an open one has none to clear.
                current.status == TaskStatus.COMPLETED -> Triple(TaskStatus.NEEDS_ACTION, null, null)
                else -> Triple(current.status, current.percentComplete, null)
            }
            else -> Triple(current.status, current.percentComplete, current.completedAt)
        }
    }

    /**
     * The completion columns in [v] that differ from [current]. All of them when
     * none does, so a write that only repeats the row still settles the rest.
     */
    private fun completionChanged(current: TaskEntity, v: Values): Set<String> {
        val present = COMPLETION_COLUMNS.filterTo(HashSet()) { it in v }
        val changed = present.filterTo(HashSet()) { column ->
            when (column) {
                Tasks.STATUS -> statusFromInt(v.int(Tasks.STATUS, 0..3)) != current.status
                Tasks.PERCENT_COMPLETE -> v.int(Tasks.PERCENT_COMPLETE, 0..100) != current.percentComplete
                else -> v.instant(Tasks.COMPLETED) != current.completedAt
            }
        }
        return changed.ifEmpty { present }
    }

    /**
     * An `RDATE`/`EXDATE` value: DATE or DATE-TIME values, comma-separated, of
     * one kind. [stored] written back as is stays, whatever shape it synced in.
     */
    private fun dateList(column: String, v: Values, stored: String?): String? {
        val raw = v.string(column)?.trim()?.ifEmpty { null } ?: return null
        if (raw == stored?.trim()) return stored
        val values = raw.split(',').map(String::trim)
        require(values.all { ICAL_DATE.matches(it) }) { "$column takes iCalendar dates or date-times: $raw" }
        // lib-recur reads the whole list or none of it, so it has to be one kind.
        val kinds = values.map { if (it.length == 8) "date" else if (it.endsWith('Z')) "utc" else "local" }
        require(kinds.distinct().size == 1) { "$column mixes value kinds: $raw" }
        return values.joinToString(",")
    }

    private fun normalizedRule(raw: String?): String? {
        val rule = raw?.trim()?.removePrefix("RRULE:")?.ifEmpty { null } ?: return null
        require(runCatching { RecurrenceRule(rule) }.isSuccess) { "Not a recurrence rule: $rule" }
        return rule
    }
}

/** Lenient reads over [values]: a client may hand a number as a string or a flag as an integer. */
internal class Values(private val values: ProviderValues) {

    val keys: Set<String> get() = values.keys

    val map: ProviderValues get() = values

    operator fun contains(key: String) = key in values

    fun string(key: String): String? = values[key]?.toString()

    /** A free-text column: trimmed, blank stored as nothing. */
    fun text(key: String): String? = string(key)?.trim()?.ifEmpty { null }

    fun long(key: String): Long? = when (val value = values[key]) {
        null -> null
        is Number -> value.toLong()
        is Boolean -> if (value) 1L else 0L
        is String -> requireNotNull(value.trim().toLongOrNull()) { "$key is not a number: $value" }
        else -> throw IllegalArgumentException("$key is not a number: $value")
    }

    fun int(key: String, range: IntRange? = null): Int? = long(key)?.let { value ->
        require(value in Int.MIN_VALUE..Int.MAX_VALUE) { "$key is out of range: $value" }
        value.toInt().also { require(range == null || it in range) { "$key must be in $range: $it" } }
    }

    fun boolean(key: String): Boolean = when (val value = values[key]) {
        null -> false
        is Boolean -> value
        is String -> when (value.trim().lowercase()) {
            "1", "true" -> true
            "0", "false", "" -> false
            else -> throw IllegalArgumentException("$key is not a flag: $value")
        }
        else -> long(key) != 0L
    }

    fun instant(key: String): Instant? = long(key)?.let(Instant::fromEpochMilliseconds)
}
