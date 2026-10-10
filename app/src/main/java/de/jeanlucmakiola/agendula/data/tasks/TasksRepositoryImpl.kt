package de.jeanlucmakiola.agendula.data.tasks

import de.jeanlucmakiola.agendula.data.di.IoDispatcher
import de.jeanlucmakiola.agendula.data.prefs.SettingsPrefs
import de.jeanlucmakiola.floret.time.DayWindow
import de.jeanlucmakiola.agendula.domain.SavedFilter
import de.jeanlucmakiola.agendula.domain.SeriesCollapse
import de.jeanlucmakiola.agendula.domain.Task
import de.jeanlucmakiola.agendula.domain.TaskDetail
import de.jeanlucmakiola.agendula.domain.TaskFilter
import de.jeanlucmakiola.agendula.domain.TaskFiltering
import de.jeanlucmakiola.agendula.domain.TaskForm
import de.jeanlucmakiola.agendula.domain.TaskList
import de.jeanlucmakiola.agendula.domain.TaskSorting
import de.jeanlucmakiola.agendula.domain.TaskStatus
import de.jeanlucmakiola.agendula.domain.recurrence.RecurringScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    private val startupGate: StartupGate,
    private val settingsPrefs: SettingsPrefs,
    @IoDispatcher private val io: CoroutineDispatcher,
) : TasksRepository {

    override fun taskLists(): Flow<List<TaskList>> = observing { dataSource.taskLists() }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun filteredTasks(filter: TaskFilter): Flow<FilteredTasks> = when (filter) {
        // A deleted filter shows nothing rather than failing the screen or widget on it.
        is TaskFilter.Saved -> settingsPrefs.savedFilter(filter.id)
            .flatMapLatest { saved ->
                if (saved == null) flowOf(FilteredTasks(emptyList()))
                else observing {
                    // A deleted list's id would otherwise leave the filter quietly empty.
                    val current = saved.withListsIn(dataSource.taskLists().mapTo(HashSet()) { it.id })
                    FilteredTasks(loadTasks(filter, current), current)
                }
            }
        else -> observing { FilteredTasks(loadTasks(filter)) }
    }

    override fun subtasks(parentId: Long): Flow<List<Task>> = observing {
        SeriesCollapse.visible(dataSource.subtasks(parentId))
            .filter { it.taskId != parentId }
            .sortedWith(TaskSorting.DEFAULT)
    }

    override fun taskDetail(taskId: Long, occurrenceStart: Instant?): Flow<TaskDetail?> = observing {
        dataSource.task(taskId, occurrenceStart)?.let { task ->
            TaskDetail(
                task = task,
                subtasks = SeriesCollapse.visible(dataSource.subtasks(taskId)).filter { it.taskId != taskId },
                parent = task.parentId?.takeIf { it > 0 }?.let { dataSource.task(it) },
            )
        }
    }

    private fun loadTasks(filter: TaskFilter, saved: SavedFilter? = null): List<Task> {
        val query = when (filter) {
            is TaskFilter.OfList -> TaskQuery(listId = filter.listId, includeCompleted = true)
            // Read completed tasks too, then let [TaskFiltering.matches] enforce each
            // smart list's own open/closed semantics. We over-read on purpose so a
            // parent's subtask-progress reflects ALL its children — completed ones
            // included — even on smart lists that hide completed tasks themselves.
            is TaskFilter.Smart -> TaskQuery(includeCompleted = true)
            // Same over-read as Smart: TaskQuery.listId only narrows to one list, so
            // several lists are read in full and membership is filtered in-memory.
            is TaskFilter.OfLists -> TaskQuery(includeCompleted = true)
            is TaskFilter.Tagged -> TaskQuery(includeCompleted = true)
            is TaskFilter.Saved -> TaskQuery(includeCompleted = true)
        }
        val (todayStart, todayEnd) = DayWindow.today(Clock.System.now(), ZoneId.systemDefault())
        val all = SeriesCollapse.visible(dataSource.tasks(query))
        // (done, total) of direct children per parent, over the unfiltered set.
        val progress: Map<Long, Pair<Int, Int>> = all
            .filter { (it.parentId ?: 0L) > 0L }
            .groupingBy { it.parentId!! }
            .fold(0 to 0) { (done, total), t ->
                (if (t.isCompleted) done + 1 else done) to (total + 1)
            }
        return all
            .filter { TaskFiltering.matches(it, filter, todayStart, todayEnd, saved) }
            .map { task ->
                progress[task.taskId]?.let { (done, total) ->
                    task.copy(subtaskDone = done, subtaskTotal = total)
                } ?: task
            }
            .sortedWith(TaskSorting.DEFAULT)
    }

    override suspend fun createTask(form: TaskForm): Long =
        withContext(io) {
            val id = dataSource.insertTask(form)
            writeReminders(id, form)
            id
        }

    override suspend fun duplicateTask(taskId: Long, occurrenceStart: Instant?): Long =
        withContext(io) {
            val task = checkNotNull(dataSource.task(taskId, occurrenceStart)) { "Task $taskId is gone" }
            copyOf(task, task.parentId, depth = 0)
        }

    private fun copyOf(task: Task, parentId: Long?, depth: Int): Long {
        val id = dataSource.insertTask(
            TaskForm(
                title = task.title,
                listId = task.listId,
                description = task.description,
                start = task.start,
                due = task.due,
                isAllDay = task.isAllDay,
                priority = task.priority,
                parentId = parentId,
                location = task.location,
                url = task.url,
                tags = task.tags,
            ),
        )
        val reminders = dataSource.reminders()[task.taskId].orEmpty()
        if (reminders.isNotEmpty()) dataSource.setReminders(id, reminders)
        if (depth < MAX_COPY_DEPTH) {
            SeriesCollapse.visible(dataSource.subtasks(task.taskId))
                .filter { it.taskId != task.taskId && it.status != TaskStatus.CANCELLED }
                .forEach { copyOf(it, id, depth + 1) }
        }
        return id
    }

    override suspend fun reminderFor(taskId: Long): Int? =
        withContext(io) { runCatching { dataSource.alarms()[taskId]?.minutesBefore }.getOrNull() }

    override suspend fun remindersFor(taskId: Long): List<TaskReminder> =
        withContext(io) { runCatching { dataSource.reminders()[taskId].orEmpty() }.getOrDefault(emptyList()) }

    override suspend fun setReminders(taskId: Long, reminders: List<TaskReminder>) =
        withContext(io) { dataSource.setReminders(taskId, reminders) }

    override suspend fun updateTask(
        taskId: Long,
        form: TaskForm,
        expectedLastModified: Instant?,
        occurrenceStart: Instant?,
        scope: RecurringScope,
    ) = withContext(io) {
        // Re-read just before writing: it settles the conflict check *and* tells
        // us which URI to write through.
        val current = dataSource.task(taskId, occurrenceStart)
        // Conflict-safe overwrite: bail if the provider's last_modified moved
        // since the form captured it (external sync / another app). A null
        // baseline means "force / overwrite anyway".
        if (expectedLastModified != null) {
            val seen = current?.lastModified
            if (seen != null && seen != expectedLastModified) throw TaskConflictException(taskId)
        }
        val seriesId = current?.seriesId
        val occurrence = current?.occurrenceStart
        if (seriesId != null && occurrence != null) {
            when (scope) {
                RecurringScope.AllOccurrences -> {
                    writeReminders(seriesId, form)
                    dataSource.updateSeries(seriesId, occurrence, form)
                    return@withContext
                }
                RecurringScope.ThisAndFollowing -> {
                    val created = dataSource.splitSeries(seriesId, occurrence, form)
                    writeReminders(created, form)
                    return@withContext
                }
                RecurringScope.ThisOccurrence -> Unit
            }
        }
        // A recurring task's start/due are one occurrence's resolved times, so
        // writing them back to the task row would re-anchor the whole series.
        // Going through the occurrence forks an override instead.
        val instance = current?.takeIf { it.isRecurring }?.occurrenceStart
        if (instance == null) {
            writeReminders(taskId, form)
            dataSource.updateTask(taskId, form)
            return@withContext
        }
        // ⚠️ Written to the series, and then put back. Forking an occurrence
        // copies the task's *current* properties onto the new override row,
        // so setting the alarm beforehand is the only way to carry it
        // across — and leaving it there would silently give every other
        // occurrence in the series the reminder the user set on one of them.
        val seriesReminders = dataSource.reminders()[taskId].orEmpty()
        writeReminders(taskId, form)
        try {
            dataSource.updateInstance(taskId, instance, form)
        } finally {
            dataSource.setReminders(taskId, seriesReminders)
        }
    }

    /** The form's before-due reminders; a start-relative one from elsewhere is kept as it was. */
    private fun writeReminders(taskId: Long, form: TaskForm) {
        val kept = dataSource.reminders()[taskId].orEmpty().filter { it.fromStart }
        dataSource.setReminders(taskId, kept + form.reminders.distinct().sorted().map { TaskReminder(it) })
    }

    override suspend fun setCancelled(taskId: Long, occurrenceStart: Instant?, cancelled: Boolean) =
        withContext(io) {
            if (occurrenceStart != null) dataSource.setCancelledInstance(taskId, occurrenceStart, cancelled)
            else dataSource.setCancelled(taskId, cancelled)
        }

    override suspend fun setCompleted(taskId: Long, occurrenceStart: Instant?, completed: Boolean) =
        withContext(io) {
            if (occurrenceStart != null) {
                dataSource.setCompletedInstance(taskId, occurrenceStart, completed)
            } else {
                dataSource.setCompleted(taskId, completed)
                // Done means done with the parts too; reopening leaves them as they are.
                if (completed) {
                    SeriesCollapse.visible(dataSource.subtasks(taskId))
                        .filter { it.taskId != taskId && !it.isClosed }
                        .forEach { sub ->
                            val occurrence = sub.occurrenceStart
                            if (occurrence != null) dataSource.setCompletedInstance(sub.taskId, occurrence, true)
                            else dataSource.setCompleted(sub.taskId, true)
                        }
                }
            }
        }

    override suspend fun deleteTask(taskId: Long, occurrenceStart: Instant?, scope: RecurringScope) =
        withContext(io) {
            val current = dataSource.task(taskId, occurrenceStart)
            val seriesId = current?.seriesId
            val occurrence = current?.occurrenceStart
            if (seriesId == null || occurrence == null) return@withContext deleteWithSubtasks(taskId)
            when (scope) {
                RecurringScope.ThisOccurrence ->
                    // An override row deletes itself into an EXDATE; a generated
                    // occurrence has no row to delete.
                    if (current.taskId != seriesId) dataSource.deleteTask(current.taskId)
                    else dataSource.deleteOccurrence(seriesId, occurrence)
                RecurringScope.ThisAndFollowing -> dataSource.deleteFollowing(seriesId, occurrence)
                RecurringScope.AllOccurrences -> deleteWithSubtasks(seriesId)
            }
        }

    /** A parent goes with its subtasks; left behind they would hang off a tombstone. */
    private fun deleteWithSubtasks(taskId: Long) {
        dataSource.subtasks(taskId).map { it.taskId }.distinct()
            .filter { it != taskId }
            .forEach { dataSource.deleteTask(it) }
        dataSource.deleteTask(taskId)
    }

    override suspend fun createLocalList(name: String, color: Int): Long =
        withContext(io) { dataSource.createLocalList(name, color) }

    override suspend fun updateList(listId: Long, name: String, color: Int) =
        withContext(io) { dataSource.updateList(listId, name, color) }

    override suspend fun deleteList(listId: Long) =
        withContext(io) { dataSource.deleteList(listId) }

    override suspend fun reorderLists(listIds: List<Long>) =
        withContext(io) { dataSource.reorderLists(listIds) }

    override fun providerStatus(): ProviderStatus {
        // Our own store is always ready: it ships with the app, needs no provider
        // and no grant. The permission gate only ever applied to External mode —
        // now that is visibly true rather than a special case inside it.
        if (providerResolver.mode() == StorageMode.OWN) return ProviderStatus.READY
        val provider = providerResolver.resolve() ?: return ProviderStatus.NO_PROVIDER
        return if (providerResolver.hasPermission(provider)) ProviderStatus.READY
        else ProviderStatus.NEEDS_PERMISSION
    }

    /**
     * Emits an initial load, then re-loads on every store change. The observer
     * callback (main thread) only pokes a conflated channel; the actual blocking
     * query runs on [io].
     */
    private fun <T> observing(load: () -> T): Flow<T> = callbackFlow {
        // Nothing reads a store before the stored mode has landed and a v0.3.x
        // install has been imported — otherwise the first emission comes from the
        // wrong store, or from an empty one.
        startupGate.awaitReady()
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

/** Subtask levels a duplicate follows; deeper than any real list, short of a cycle. */
private const val MAX_COPY_DEPTH = 8
