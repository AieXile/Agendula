package de.jeanlucmakiola.agendula.data.tasks

import de.jeanlucmakiola.agendula.domain.Task
import de.jeanlucmakiola.agendula.domain.TaskForm
import de.jeanlucmakiola.agendula.domain.TaskList
import de.jeanlucmakiola.agendula.domain.TaskReminder
import kotlin.time.Instant

/** What to fetch from the provider. Smart-list date logic is applied above this. */
data class TaskQuery(
    val listId: Long? = null,
    val includeCompleted: Boolean = false,
)

/**
 * Domain-shaped, synchronous seam over the tasks ContentResolver. Returns parsed
 * lists so [TasksRepositoryImpl] can be tested with a fake on the JVM. All Cursor
 * and ContentObserver handling lives in [AndroidTasksDataSource].
 */
interface TasksDataSource {
    fun taskLists(): List<TaskList>
    fun tasks(query: TaskQuery): List<Task>
    fun task(taskId: Long): Task?

    /**
     * The occurrence of [taskId] that starts at [occurrenceStart], or [task] when
     * that is null or names no occurrence — so a stale anchor still lands on the task.
     */
    fun task(taskId: Long, occurrenceStart: Instant?): Task? = task(taskId)
    fun subtasks(parentTaskId: Long): List<Task>

    /**
     * The task rows directly under [parentTaskId]: one id per subtask, its series
     * master when it recurs, whether or not any occurrence falls in the window
     * [subtasks] expands.
     */
    fun subtaskIds(parentTaskId: Long): List<Long> =
        subtasks(parentTaskId).map { it.seriesId ?: it.taskId }.distinct().filter { it != parentTaskId }

    /**
     * Delete [taskId] and its subtasks, all the way down; left behind they would
     * hang off a tombstone. Each task once, so a parent loop an import brought in
     * ends instead of recursing forever.
     */
    fun deleteWithSubtasks(taskId: Long) {
        val seen = HashSet<Long>()
        fun delete(id: Long) {
            if (!seen.add(id)) return
            subtaskIds(id).forEach(::delete)
            deleteTask(id)
        }
        delete(taskId)
    }

    /**
     * The write paths take the task's reminders in the same write as the task
     * itself, so a sync can never upload one without the other.
     *
     * Each takes the task's whole set of [reminders], before-start and
     * before-due alike, and `null` leaves the stored ones alone.
     */
    fun insertTask(form: TaskForm, reminders: List<TaskReminder>? = null): Long
    fun updateTask(taskId: Long, form: TaskForm, reminders: List<TaskReminder>? = null)

    /**
     * Update a single occurrence of a recurring task, addressed by the task row and
     * the occurrence's `RECURRENCE-ID` anchor ([Task.occurrenceStart]). The store
     * forks an override rather than moving the series anchor — which is what
     * [updateTask] would do, since a recurring task's start/due are the
     * occurrence's resolved times.
     *
     * Addressing by `(taskId, occurrenceStart)` rather than by a materialised
     * instance row id keeps this seam independent of any one store's row
     * numbering; External mode maps it back to an instance row itself.
     */
    fun updateInstance(taskId: Long, occurrenceStart: Instant, form: TaskForm, reminders: List<TaskReminder>? = null)

    /**
     * Edit the whole series [seriesId] through its occurrence at [occurrenceStart]:
     * content from [form], and the series moved by however far that occurrence moved.
     */
    fun updateSeries(seriesId: Long, occurrenceStart: Instant, form: TaskForm, reminders: List<TaskReminder>? = null)

    /**
     * "This and following": end [seriesId] before [occurrenceStart] and continue
     * from there as a new series built from [form]. Returns the new series' id.
     */
    fun splitSeries(seriesId: Long, occurrenceStart: Instant, form: TaskForm, reminders: List<TaskReminder>? = null): Long

    /** Delete one generated occurrence of [seriesId] (an `EXDATE`). */
    fun deleteOccurrence(seriesId: Long, occurrenceStart: Instant)

    /** Delete [occurrenceStart] and every later occurrence of [seriesId]. */
    fun deleteFollowing(seriesId: Long, occurrenceStart: Instant)


    /** Make [taskId]'s reminders exactly [reminders]. A synced task is owed a PUT for it. */
    fun setReminders(taskId: Long, reminders: List<TaskReminder>)

    /**
     * Every task's reminders, by task id, in stored order. One query, for the
     * scheduler. A task missing from the map takes the default; one present
     * with an empty list has none (see `TaskEntity.remindersOff`).
     */
    fun reminders(): Map<Long, List<TaskReminder>>

    /**
     * Every task in [listId] read from the **`tasks` table**, for export. Masters,
     * not occurrences — see [TaskMapper.exportTask] for why that distinction
     * matters. Excludes rows the provider has flagged deleted-but-unsynced.
     */
    fun exportTasks(listId: Long): List<de.jeanlucmakiola.agendula.domain.export.ExportTask>

    fun setCompleted(taskId: Long, completed: Boolean)

    /** Cancel [taskId] (`STATUS:CANCELLED`) or put it back to needs-action. */
    fun setCancelled(taskId: Long, cancelled: Boolean)

    /**
     * Complete (or reopen) **one occurrence** of a recurring task, addressed the
     * same way [updateInstance] is. Ticking a series through [setCompleted] would
     * close the master row, which takes every past and future occurrence out of
     * every list at once.
     *
     * Implementations fall back to [setCompleted] when the row turns out not to
     * be a series master — an override, or a plain task the caller happened to
     * hand an anchor for — so the routing above cannot get this wrong.
     */
    fun setCompletedInstance(taskId: Long, occurrenceStart: Instant, completed: Boolean)

    /** Cancel (or restore) one occurrence of a series; the counterpart of [setCompletedInstance]. */
    fun setCancelledInstance(taskId: Long, occurrenceStart: Instant, cancelled: Boolean)
    fun deleteTask(taskId: Long)
    fun createLocalList(name: String, color: Int): Long

    /** Rename and recolour [listId]. */
    fun updateList(listId: Long, name: String, color: Int)

    /**
     * Delete [listId] **and the tasks in it** — `tasks.list_id` cascades on the
     * Room path, and the provider does the same on the External one.
     *
     * Only ever called for a local, device-only list: a collection that belongs
     * to an account is the server's to remove, and neither store expresses a
     * collection tombstone yet. The UI gates on [TaskList.isLocal]; this seam
     * does not re-check it.
     */
    fun deleteList(listId: Long)

    /** Store [listIds] as the lists' display order. A store without one ignores it. */
    fun reorderLists(listIds: List<Long>) = Unit

    /** Observe any change to tasks/lists; [onChange] fires on a background thread. */
    fun registerObserver(onChange: () -> Unit): AutoCloseable
}
