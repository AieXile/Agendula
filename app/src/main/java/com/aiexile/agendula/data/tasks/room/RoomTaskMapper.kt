package com.aiexile.agendula.data.tasks.room

import com.aiexile.agendula.domain.LocalAccount
import com.aiexile.agendula.domain.Task
import com.aiexile.agendula.domain.TaskList
import com.aiexile.agendula.domain.export.ExportTask
import com.aiexile.agendula.domain.priorityFromICal
import com.aiexile.agendula.domain.recurrence.RecurrenceSpec
import kotlin.time.Instant

/** Account type reported for a list attached to one of ours. */
const val CALDAV_ACCOUNT_TYPE = "caldav"

/** Maps Room rows to domain models. Pure + testable, like [com.aiexile.agendula.data.tasks.TaskMapper]. */
object RoomTaskMapper {

    fun taskList(row: TaskListRow): TaskList = TaskList(
        id = row.list.id,
        name = row.list.name,
        color = row.list.color,
        // TaskList.accountName is non-null and the lists screen groups by it, so a
        // list with no account still has to report something to group under.
        accountName = row.accountDisplayName ?: LocalAccount.NAME,
        accountType = if (row.list.accountId == null) LocalAccount.TYPE else CALDAV_ACCOUNT_TYPE,
        isSynced = row.list.isSynced,
        isVisible = row.list.isVisible,
        owner = row.list.owner,
        accountId = row.list.accountId,
        isReadOnly = row.list.isReadOnly,
    )

    /**
     * One occurrence of [row]. [occurrenceStart] is the occurrence's
     * `RECURRENCE-ID` anchor and `null` for a task that does not recur;
     * [start] / [due] are that occurrence's resolved times.
     */
    fun task(
        row: TaskRow,
        occurrenceStart: Instant? = null,
        start: Instant? = row.task.dtstart,
        due: Instant? = row.task.due,
        distanceFromCurrent: Int? = null,
        seriesId: Long? = if (row.task.isRecurring) row.task.id else null,
        recurrenceRule: String? = row.task.rrule,
    ): Task = Task(
        taskId = row.task.id,
        listId = row.task.listId,
        title = row.task.title.orEmpty(),
        description = row.task.description,
        location = row.task.location,
        url = row.task.url,
        tags = row.task.categories,
        priority = priorityFromICal(row.task.priority),
        status = row.task.status,
        percentComplete = row.task.percentComplete,
        start = start,
        due = due,
        isAllDay = row.task.isAllDay,
        timeZone = row.task.timezone,
        completedAt = row.task.completedAt,
        listColor = row.listColor,
        taskColor = row.task.color,
        listName = row.listName,
        accountName = row.accountDisplayName ?: LocalAccount.NAME,
        parentId = row.task.parentId,
        isRecurring = row.task.isRecurring,
        occurrenceStart = occurrenceStart,
        distanceFromCurrent = distanceFromCurrent,
        created = row.task.createdAt,
        lastModified = row.task.lastModified,
        seriesId = seriesId,
        recurrenceRule = recurrenceRule,
        isReadOnly = row.listReadOnly,
    )

    fun exportTask(task: TaskEntity): ExportTask = ExportTask(
        taskId = task.id,
        uid = task.uid,
        title = task.title.orEmpty(),
        description = task.description,
        location = task.location,
        url = task.url,
        tags = task.categories,
        priority = priorityFromICal(task.priority),
        status = task.status,
        percentComplete = task.percentComplete,
        start = task.dtstart,
        due = task.due,
        isAllDay = task.isAllDay,
        completedAt = task.completedAt,
        created = task.createdAt,
        lastModified = task.lastModified,
        rrule = task.rrule,
        rdate = task.rdate,
        parentId = task.parentId?.takeIf { it > 0 },
    )
}

/** A row carries a recurrence rule if it has an `RRULE` or an `RDATE`. */
val TaskEntity.isRecurring: Boolean
    get() = !rrule.isNullOrBlank() || !rdate.isNullOrBlank()

/**
 * The series anchor: `DTSTART` when present, else `DUE`. A `VTODO` may carry only
 * a due date, and RFC 5545 then anchors the recurrence on it — matching how the
 * dmfs provider instantiated the same series.
 */
val TaskEntity.recurrenceAnchor: Instant?
    get() = dtstart ?: due

/** The rule set of this series, or `null` when it does not recur. */
fun TaskEntity.recurrenceSpec(): RecurrenceSpec? {
    if (!isRecurring) return null
    val anchor = recurrenceAnchor ?: return null
    return RecurrenceSpec(
        rrule = rrule,
        rdate = rdate,
        exdate = exdate,
        anchor = anchor,
        isAllDay = isAllDay,
        timeZone = timezone,
    )
}
