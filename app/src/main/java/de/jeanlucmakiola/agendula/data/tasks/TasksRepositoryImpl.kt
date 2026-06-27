package de.jeanlucmakiola.agendula.data.tasks

import de.jeanlucmakiola.agendula.data.di.IoDispatcher
import de.jeanlucmakiola.agendula.domain.DayWindow
import de.jeanlucmakiola.agendula.domain.Task
import de.jeanlucmakiola.agendula.domain.TaskDetail
import de.jeanlucmakiola.agendula.domain.TaskFilter
import de.jeanlucmakiola.agendula.domain.TaskFiltering
import de.jeanlucmakiola.agendula.domain.TaskForm
import de.jeanlucmakiola.agendula.domain.TaskList
import de.jeanlucmakiola.agendula.domain.TaskSorting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant

@Singleton
class TasksRepositoryImpl @Inject constructor(
    private val dataSource: TasksDataSource,
    private val providerResolver: ProviderResolver,
    @IoDispatcher private val io: CoroutineDispatcher,
) : TasksRepository {

    override fun taskLists(): Flow<List<TaskList>> = observing { dataSource.taskLists() }

    override fun tasks(filter: TaskFilter): Flow<List<Task>> = observing { loadTasks(filter) }

    override fun taskDetail(taskId: Long): Flow<TaskDetail?> = observing {
        dataSource.task(taskId)?.let { task ->
            TaskDetail(
                task = task,
                subtasks = dataSource.subtasks(taskId).filter { it.taskId != taskId },
                parent = task.parentId?.takeIf { it > 0 }?.let { dataSource.task(it) },
            )
        }
    }

    private fun loadTasks(filter: TaskFilter): List<Task> {
        val query = when (filter) {
            is TaskFilter.OfList -> TaskQuery(listId = filter.listId, includeCompleted = true)
            // Read completed tasks too, then let [TaskFiltering.matches] enforce each
            // smart list's own open/closed semantics. We over-read on purpose so a
            // parent's subtask-progress reflects ALL its children — completed ones
            // included — even on smart lists that hide completed tasks themselves.
            is TaskFilter.Smart -> TaskQuery(includeCompleted = true)
        }
        val (todayStart, todayEnd) = DayWindow.today(Clock.System.now(), ZoneId.systemDefault())
        val all = dataSource.tasks(query)
        // (done, total) of direct children per parent, over the unfiltered set.
        val progress: Map<Long, Pair<Int, Int>> = all
            .filter { (it.parentId ?: 0L) > 0L }
            .groupingBy { it.parentId!! }
            .fold(0 to 0) { (done, total), t ->
                (if (t.isCompleted) done + 1 else done) to (total + 1)
            }
        return all
            .filter { TaskFiltering.matches(it, filter, todayStart, todayEnd) }
            .map { task ->
                progress[task.taskId]?.let { (done, total) ->
                    task.copy(subtaskDone = done, subtaskTotal = total)
                } ?: task
            }
            .sortedWith(TaskSorting.DEFAULT)
    }

    override suspend fun createTask(form: TaskForm): Long =
        withContext(io) { dataSource.insertTask(form) }

    override suspend fun updateTask(taskId: Long, form: TaskForm, expectedLastModified: Instant?) =
        withContext(io) {
            // Conflict-safe overwrite: re-read just before writing and bail if the
            // provider's last_modified moved since the form captured it (external
            // sync / another app). A null baseline means "force / overwrite anyway".
            if (expectedLastModified != null) {
                val current = dataSource.task(taskId)?.lastModified
                if (current != null && current != expectedLastModified) throw TaskConflictException(taskId)
            }
            dataSource.updateTask(taskId, form)
        }

    override suspend fun setCompleted(taskId: Long, completed: Boolean) =
        withContext(io) { dataSource.setCompleted(taskId, completed) }

    override suspend fun deleteTask(taskId: Long) =
        withContext(io) { dataSource.deleteTask(taskId) }

    override suspend fun createLocalList(name: String, color: Int): Long =
        withContext(io) { dataSource.createLocalList(name, color) }

    override fun providerStatus(): ProviderStatus {
        val provider = providerResolver.resolve() ?: return ProviderStatus.NO_PROVIDER
        return if (providerResolver.hasPermission(provider)) ProviderStatus.READY
        else ProviderStatus.NEEDS_PERMISSION
    }

    /**
     * Emits an initial load, then re-loads on every provider change. The observer
     * callback (main thread) only pokes a conflated channel; the actual blocking
     * query runs on [io].
     */
    private fun <T> observing(load: () -> T): Flow<T> = callbackFlow {
        val ticks = Channel<Unit>(Channel.CONFLATED)
        val handle = dataSource.registerObserver { ticks.trySend(Unit) }
        ticks.trySend(Unit) // prime the initial emission
        val job = launch {
            ticks.consumeEach { send(load()) }
        }
        awaitClose {
            handle.close()
            job.cancel()
            ticks.close()
        }
    }.flowOn(io)
}
