package de.jeanlucmakiola.agendula.domain

import kotlin.time.Instant

/** A task list (the `tasklists` table). Lists group under their account. */
data class TaskList(
    val id: Long,
    val name: String,
    val color: Int,
    val accountName: String,
    val accountType: String,
    val isSynced: Boolean,
    val isVisible: Boolean,
    val owner: String?,
    /**
     * The CalDAV account this list belongs to, or null for a device-only one.
     *
     * ⚠️ Distinct from [accountName], which is a label and is filled in with a
     * placeholder for a device-only list. This is what a write has to be aimed
     * at, so it has to be able to say "nowhere".
     */
    val accountId: Long? = null,
    /**
     * A share we may read and not write. Renaming or deleting it is the owner's
     * to do, and attempting either answers 403.
     */
    val isReadOnly: Boolean = false,
) {
    /** A device-only list Agendula (or another app) created locally, not synced. */
    val isLocal: Boolean
        get() = accountType == LocalAccount.TYPE || accountType == LocalAccount.DMFS_TYPE

    /** Whether a task may be created in, moved to, or edited within this list. */
    val acceptsWrites: Boolean
        get() = !isReadOnly
}

enum class TaskStatus { NEEDS_ACTION, IN_PROCESS, COMPLETED, CANCELLED }

/** iCalendar priority, bucketed for the UI. */
enum class Priority { NONE, LOW, MEDIUM, HIGH }

/**
 * One occurrence of a task. [taskId] is the underlying task row and the stable
 * target for edits and navigation; [occurrenceStart] distinguishes occurrences of
 * the same series.
 */
data class Task(
    val taskId: Long,
    val listId: Long,
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
    val timeZone: String?,
    val completedAt: Instant?,
    val listColor: Int,
    val taskColor: Int?,
    val listName: String?,
    val accountName: String?,
    val parentId: Long?,
    /**
     * This row carries a recurrence rule, so it is one occurrence of a series and
     * [start]/[due] are that occurrence's resolved times — *not* the master's
     * anchor. Edits go through
     * [de.jeanlucmakiola.agendula.data.tasks.TasksDataSource.updateInstance], which
     * forks a `RECURRENCE-ID` override instead of re-anchoring the series.
     */
    val isRecurring: Boolean,
    /**
     * This occurrence's `RECURRENCE-ID` anchor — what identifies it within its
     * series — or `null` when the task does not recur. Together with [taskId] it
     * is a stable, collision-free identity for an occurrence, which is what list
     * keys and [de.jeanlucmakiola.agendula.data.tasks.TasksDataSource.updateInstance]
     * address it by.
     */
    val occurrenceStart: Instant? = null,
    val distanceFromCurrent: Int?,
    val created: Instant?,
    val lastModified: Instant?,
    /**
     * Direct-subtask progress, filled in by the repository over the *full* task
     * set so a parent's progress chip counts completed children even on smart
     * lists that hide them. Both 0 when the task has no subtasks.
     */
    val subtaskTotal: Int = 0,
    val subtaskDone: Int = 0,
    /**
     * The series master's row id when this is an occurrence of a recurring task —
     * a generated occurrence *or* a `RECURRENCE-ID` override — else `null`.
     * Series-wide writes ("this and following", "all") address this row.
     */
    val seriesId: Long? = null,
    /** The series' `RRULE`, for display and for prefilling the editor. */
    val recurrenceRule: String? = null,
    /**
     * The task's list is a read-only share. An edit here would never reach the
     * server, and the dirty row would then block every later download of it.
     */
    val isReadOnly: Boolean = false,
) {
    /** One occurrence of a series, whether generated or overridden. */
    val isOccurrence: Boolean get() = seriesId != null && occurrenceStart != null

    val isCompleted: Boolean get() = status == TaskStatus.COMPLETED
    val isClosed: Boolean get() = status == TaskStatus.COMPLETED || status == TaskStatus.CANCELLED
    val isSubtask: Boolean get() = parentId != null && parentId > 0
    /** The task's own colour if set, else the list colour. */
    val effectiveColor: Int get() = taskColor ?: listColor

    /**
     * Stable identity for a lazy-list key. Two occurrences of one series can show
     * up in the same list, so [taskId] alone is not unique — and folding
     * `(taskId, occurrenceStart)` into a Long could collide, which as a Compose
     * key is a visible bug.
     */
    val occurrenceKey: String
        get() = if (occurrenceStart == null) "$taskId" else "$taskId@${occurrenceStart.toEpochMilliseconds()}"
}

/** Detail bundle: a task, its parent (if it's a subtask), and its direct children. */
data class TaskDetail(
    val task: Task,
    val subtasks: List<Task>,
    val parent: Task? = null,
)

// --- pure provider <-> domain value mapping (unit-testable) ------------------

fun priorityFromICal(value: Int?): Priority = when {
    value == null || value <= 0 -> Priority.NONE
    value in 1..4 -> Priority.HIGH
    value == 5 -> Priority.MEDIUM
    else -> Priority.LOW // 6..9
}

/** Representative iCalendar priority for a bucket (1 high, 5 medium, 9 low). */
fun Priority.toICal(): Int = when (this) {
    Priority.NONE -> PRIORITY_NONE
    Priority.HIGH -> 1
    Priority.MEDIUM -> 5
    Priority.LOW -> 9
}

fun statusFromInt(value: Int?): TaskStatus = when (value) {
    ICalStatus.IN_PROCESS -> TaskStatus.IN_PROCESS
    ICalStatus.COMPLETED -> TaskStatus.COMPLETED
    ICalStatus.CANCELLED -> TaskStatus.CANCELLED
    else -> TaskStatus.NEEDS_ACTION
}

fun TaskStatus.toInt(): Int = when (this) {
    TaskStatus.NEEDS_ACTION -> ICalStatus.NEEDS_ACTION
    TaskStatus.IN_PROCESS -> ICalStatus.IN_PROCESS
    TaskStatus.COMPLETED -> ICalStatus.COMPLETED
    TaskStatus.CANCELLED -> ICalStatus.CANCELLED
}
