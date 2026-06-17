package de.jeanlucmakiola.floret.data.tasks

import de.jeanlucmakiola.floret.data.di.IoDispatcher
import de.jeanlucmakiola.floret.domain.DayWindow
import de.jeanlucmakiola.floret.domain.SmartList
import de.jeanlucmakiola.floret.domain.Task
import de.jeanlucmakiola.floret.domain.TaskDetail
import de.jeanlucmakiola.floret.domain.TaskFilter
import de.jeanlucmakiola.floret.domain.TaskFiltering
import de.jeanlucmakiola.floret.domain.TaskForm
import de.jeanlucmakiola.floret.domain.TaskList
import de.jeanlucmakiola.floret.domain.TaskSorting
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
            TaskDetail(task, dataSource.subtasks(taskId).filter { it.taskId != taskId })
        }
    }

    private fun loadTasks(filter: TaskFilter): List<Task> {
        val query = when (filter) {
            is TaskFilter.OfList -> TaskQuery(listId = filter.listId, includeCompleted = true)
            is TaskFilter.Smart -> TaskQuery(includeCompleted = filter.list == SmartList.COMPLETED)
        }
        val (todayStart, todayEnd) = DayWindow.today(Clock.System.now(), ZoneId.systemDefault())
        return dataSource.tasks(query)
            .filter { TaskFiltering.matches(it, filter, todayStart, todayEnd) }
            .sortedWith(TaskSorting.DEFAULT)
    }

    override suspend fun createTask(form: TaskForm): Long =
        withContext(io) { dataSource.insertTask(form) }

    override suspend fun updateTask(taskId: Long, form: TaskForm) =
        withContext(io) { dataSource.updateTask(taskId, form) }

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
