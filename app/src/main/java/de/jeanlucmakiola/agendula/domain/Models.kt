package de.jeanlucmakiola.agendula.domain

import de.jeanlucmakiola.agendula.data.tasks.TasksContract
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
) {
    /** A device-only list Agendula (or another app) created locally, not synced. */
    val isLocal: Boolean get() = accountType == TasksContract.LOCAL_ACCOUNT_TYPE
}

enum class TaskStatus { NEEDS_ACTION, IN_PROCESS, COMPLETED, CANCELLED }

/** iCalendar priority, bucketed for the UI. */
enum class Priority { NONE, LOW, MEDIUM, HIGH }

/**
 * A task occurrence as read from the `instances` view. [id] is the instance row
 * id; [taskId] is the underlying `tasks._id` and the stable target for edits.
 */
data class Task(
    val id: Long,
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
    val isRecurring: Boolean,
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
) {
    val isCompleted: Boolean get() = status == TaskStatus.COMPLETED
    val isClosed: Boolean get() = status == TaskStatus.COMPLETED || status == TaskStatus.CANCELLED
    val isSubtask: Boolean get() = parentId != null && parentId > 0
    /** The task's own colour if set, else the list colour. */
    val effectiveColor: Int get() = taskColor ?: listColor
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
    Priority.NONE -> TasksContract.PRIORITY_NONE
    Priority.HIGH -> 1
    Priority.MEDIUM -> 5
    Priority.LOW -> 9
}

fun statusFromInt(value: Int?): TaskStatus = when (value) {
    TasksContract.STATUS_IN_PROCESS -> TaskStatus.IN_PROCESS
    TasksContract.STATUS_COMPLETED -> TaskStatus.COMPLETED
    TasksContract.STATUS_CANCELLED -> TaskStatus.CANCELLED
    else -> TaskStatus.NEEDS_ACTION
}

fun TaskStatus.toInt(): Int = when (this) {
    TaskStatus.NEEDS_ACTION -> TasksContract.STATUS_NEEDS_ACTION
    TaskStatus.IN_PROCESS -> TasksContract.STATUS_IN_PROCESS
    TaskStatus.COMPLETED -> TasksContract.STATUS_COMPLETED
    TaskStatus.CANCELLED -> TasksContract.STATUS_CANCELLED
}
