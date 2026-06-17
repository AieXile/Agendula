package de.jeanlucmakiola.floret.data.tasks

import de.jeanlucmakiola.floret.domain.Task
import de.jeanlucmakiola.floret.domain.TaskDetail
import de.jeanlucmakiola.floret.domain.TaskFilter
import de.jeanlucmakiola.floret.domain.TaskForm
import de.jeanlucmakiola.floret.domain.TaskList
import kotlinx.coroutines.flow.Flow

/** Whether Floret can use the tasks provider right now. Drives onboarding. */
enum class ProviderStatus { READY, NEEDS_PERMISSION, NO_PROVIDER }

/**
 * The single entry point the UI layer uses. Flows re-emit automatically when the
 * provider changes (our writes *and* external sync like DAVx5), via the
 * data source's ContentObserver.
 */
interface TasksRepository {
    fun taskLists(): Flow<List<TaskList>>
    fun tasks(filter: TaskFilter): Flow<List<Task>>
    fun taskDetail(taskId: Long): Flow<TaskDetail?>

    suspend fun createTask(form: TaskForm): Long
    suspend fun updateTask(taskId: Long, form: TaskForm)
    suspend fun setCompleted(taskId: Long, completed: Boolean)
    suspend fun deleteTask(taskId: Long)
    suspend fun createLocalList(name: String, color: Int): Long

    /** Synchronous snapshot for the permission/onboarding gate. */
    fun providerStatus(): ProviderStatus
}
