package de.jeanlucmakiola.agendula.data.tasks

import de.jeanlucmakiola.agendula.domain.Task
import de.jeanlucmakiola.agendula.domain.TaskDetail
import de.jeanlucmakiola.agendula.domain.TaskFilter
import de.jeanlucmakiola.agendula.domain.TaskForm
import de.jeanlucmakiola.agendula.domain.TaskList
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/** Whether Agendula can use the tasks provider right now. Drives onboarding. */
enum class ProviderStatus { READY, NEEDS_PERMISSION, NO_PROVIDER }

/**
 * The single entry point the UI layer uses. Flows re-emit automatically when the
 * provider changes (our writes *and* external sync like DAVx5), via the
 * data source's ContentObserver.
 */
interface TasksRepository {
    fun taskLists(): Flow<List<TaskList>>
    fun tasks(filter: TaskFilter): Flow<List<Task>>

    /**
     * The direct children of [parentId], re-emitting live like the other flows.
     * Lets a smart-list view pull a parent's full subtask set on demand — those
     * children are usually filtered out of the list's own (date-based) query.
     */
    fun subtasks(parentId: Long): Flow<List<Task>>
    fun taskDetail(taskId: Long): Flow<TaskDetail?>

    suspend fun createTask(form: TaskForm): Long

    /**
     * Overwrite [taskId] with [form]. When [expectedLastModified] is non-null, the
     * task's current `last_modified` is re-checked first and a
     * [TaskConflictException] is thrown if it differs — i.e. something changed it
     * since the form loaded. Pass `null` to force the write (overwrite-anyway).
     */
    suspend fun updateTask(taskId: Long, form: TaskForm, expectedLastModified: Instant? = null)
    suspend fun setCompleted(taskId: Long, completed: Boolean)
    suspend fun deleteTask(taskId: Long)
    suspend fun createLocalList(name: String, color: Int): Long

    /** Synchronous snapshot for the permission/onboarding gate. */
    fun providerStatus(): ProviderStatus
}
