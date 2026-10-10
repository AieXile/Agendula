package de.jeanlucmakiola.agendula.domain.export

import de.jeanlucmakiola.agendula.domain.Priority
import de.jeanlucmakiola.agendula.domain.TaskStatus
import kotlin.time.Instant

/**
 * One task as it goes out to iCalendar — a **master** task, not an occurrence.
 *
 * Deliberately not [de.jeanlucmakiola.agendula.domain.Task]. That model is read
 * from the `instances` view, where a recurring task appears once per occurrence
 * with resolved times and no rule; exporting from it would write the same task
 * fifty times and lose the RRULE that generated them. Export reads the `tasks`
 * table instead, and needs two fields the UI never asks for ([uid], [rrule]).
 */
data class ExportTask(
    /** `tasks._id` — the fallback identity when [uid] is absent. */
    val taskId: Long,
    /**
     * The iCalendar UID, or `null` for a task created on this device and never
     * synced. The dmfs provider only lets a *sync adapter* assign one, so in Local
     * mode this is null for everything — see [ICalendarWriter.uidFor], which
     * synthesises a stable substitute rather than emitting a VTODO with no UID.
     */
    val uid: String?,
    val title: String,
    val description: String?,
    val location: String?,
    val url: String?,
    val priority: Priority,
    val status: TaskStatus,
    val percentComplete: Int?,
    val start: Instant?,
    val due: Instant?,
    val isAllDay: Boolean,
    val completedAt: Instant?,
    val created: Instant?,
    val lastModified: Instant?,
    /** Raw `RRULE` value as stored, without the `RRULE:` name. Null when non-recurring. */
    val rrule: String?,
    /** Raw `RDATE` value as stored. Null when absent. */
    val rdate: String?,
    /** `tasks._id` of the parent, for `RELATED-TO;RELTYPE=PARENT`. */
    val parentId: Long?,
    val tags: List<String> = emptyList(),
)

/** A task list and everything in it, ready to become one `.ics` document. */
data class ExportList(
    val listId: Long,
    val name: String,
    val accountName: String,
    val tasks: List<ExportTask>,
)

/** A single file the export produced: [fileName] and its finished bytes. */
data class ExportDocument(
    val fileName: String,
    val content: ByteArray,
) {
    // ByteArray gets identity equals/hashCode, which makes this data class lie.
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is ExportDocument && fileName == other.fileName && content.contentEquals(other.content))

    override fun hashCode(): Int = 31 * fileName.hashCode() + content.contentHashCode()
}
