package de.jeanlucmakiola.agendula.data.tasks

import de.jeanlucmakiola.agendula.domain.TaskReminder
import de.jeanlucmakiola.agendula.domain.SavedFilter
import de.jeanlucmakiola.agendula.domain.Task
import de.jeanlucmakiola.agendula.domain.TaskDetail
import de.jeanlucmakiola.agendula.domain.TaskFilter
import de.jeanlucmakiola.agendula.domain.TaskForm
import de.jeanlucmakiola.agendula.domain.TaskList
import de.jeanlucmakiola.agendula.domain.recurrence.RecurringScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

/** Whether Agendula can use the tasks provider right now. Drives onboarding. */
enum class ProviderStatus { READY, NEEDS_PERMISSION, NO_PROVIDER }

/** Tasks for a filter; [savedFilter] is a saved filter's definition, `null` once it's deleted. */
data class FilteredTasks(val tasks: List<Task>, val savedFilter: SavedFilter? = null)

/**
 * The single entry point the UI layer uses. Flows re-emit automatically when the
 * provider changes (our writes *and* external sync like DAVx5), via the
 * data source's ContentObserver.
 */
interface TasksRepository {
    fun taskLists(): Flow<List<TaskList>>

    /**
     * The lists a task may be saved into — [taskLists] without read-only shares.
     * What a list picker for creating or moving a task should offer.
     */
    fun writableTaskLists(): Flow<List<TaskList>> =
        taskLists().map { lists -> lists.filter { it.acceptsWrites } }
    fun tasks(filter: TaskFilter): Flow<List<Task>> = filteredTasks(filter).map { it.tasks }

    /** [tasks] together with the definition behind a [TaskFilter.Saved]. */
    fun filteredTasks(filter: TaskFilter): Flow<FilteredTasks>

    /**
     * The direct children of [parentId], re-emitting live like the other flows.
     * Lets a smart-list view pull a parent's full subtask set on demand — those
     * children are usually filtered out of the list's own (date-based) query.
     */
    fun subtasks(parentId: Long): Flow<List<Task>>
    /** [occurrenceStart] picks one occurrence of a series; `null` means the current one. */
    fun taskDetail(taskId: Long, occurrenceStart: Instant? = null): Flow<TaskDetail?>

    suspend fun createTask(form: TaskForm): Long

    /**
     * Copy a task into its own list as a fresh, open, non-repeating task — this
     * occurrence's dates, its reminders and its open subtasks. Returns the copy's id.
     */
    suspend fun duplicateTask(taskId: Long, occurrenceStart: Instant? = null): Long

    /**
     * Overwrite [taskId] with [form]. When [expectedLastModified] is non-null, the
     * task's current `last_modified` is re-checked first and a
     * [TaskConflictException] is thrown if it differs — i.e. something changed it
     * since the form loaded. Pass `null` to force the write (overwrite-anyway).
     */
    suspend fun updateTask(
        taskId: Long,
        form: TaskForm,
        expectedLastModified: Instant? = null,
        occurrenceStart: Instant? = null,
        scope: RecurringScope = RecurringScope.ThisOccurrence,
    )
    /**
     * Complete or reopen a task. Pass the occurrence's [Task.occurrenceStart] so a
     * recurring series forks a `RECURRENCE-ID` override for that one occurrence
     * instead of closing the whole series; `null` completes the row itself.
     */
    suspend fun setCompleted(taskId: Long, occurrenceStart: Instant?, completed: Boolean)

    /** Cancel or restore a task; with [occurrenceStart], only that occurrence of a series. */
    suspend fun setCancelled(taskId: Long, occurrenceStart: Instant?, cancelled: Boolean)
    /** Delete a task, or — for an occurrence of a series — as much of the series as [scope] says. */
    suspend fun deleteTask(
        taskId: Long,
        occurrenceStart: Instant? = null,
        scope: RecurringScope = RecurringScope.AllOccurrences,
    )

    /** Every reminder stored on the task, in order — including start-relative ones. */
    suspend fun remindersFor(taskId: Long): List<TaskReminder>

    /** Replace every reminder on the task; an empty list leaves the list's / global setting in charge. */
    suspend fun setReminders(taskId: Long, reminders: List<TaskReminder>)

    suspend fun createLocalList(name: String, color: Int): Long
    suspend fun updateList(listId: Long, name: String, color: Int)

    /** Deletes the list **and its tasks**. Local lists only — see [TasksDataSource.deleteList]. */
    suspend fun deleteList(listId: Long)

    suspend fun reorderLists(listIds: List<Long>)

    /** Synchronous snapshot for the permission/onboarding gate. */
    fun providerStatus(): ProviderStatus
}
