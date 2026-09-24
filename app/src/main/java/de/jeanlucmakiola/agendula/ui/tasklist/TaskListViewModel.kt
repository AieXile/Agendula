package de.jeanlucmakiola.agendula.ui.tasklist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jeanlucmakiola.agendula.data.prefs.SettingsPrefs
import de.jeanlucmakiola.agendula.data.sync.RemoteListRepository
import de.jeanlucmakiola.agendula.data.tasks.TasksRepository
import de.jeanlucmakiola.agendula.data.tasks.recoveringFromProviderFailure
import de.jeanlucmakiola.agendula.domain.Task
import de.jeanlucmakiola.agendula.domain.TaskFilter
import de.jeanlucmakiola.agendula.domain.TaskForm
import de.jeanlucmakiola.agendula.domain.TaskList
import de.jeanlucmakiola.agendula.domain.TaskSortOrder
import de.jeanlucmakiola.agendula.domain.comparator
import de.jeanlucmakiola.agendula.ui.lists.ListWriteFailure
import de.jeanlucmakiola.agendula.ui.lists.asFailure
import de.jeanlucmakiola.agendula.data.di.ApplicationScope
import de.jeanlucmakiola.agendula.domain.recurrence.RecurringScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface TaskListUiState {
    data object Loading : TaskListUiState
    data object Failure : TaskListUiState

    /**
     * [list] is the real list when the filter is a [TaskFilter.OfList] — it
     * titles the bar and backs the edit action — and `null` for smart lists,
     * where the screen falls back to the smart label.
     */
    data class Content(
        val tasks: List<Task>,
        val list: TaskList? = null,
        /** Whether the inline "add a subtask" row shows on expanded groups (M5 setting). */
        val showAddSubtaskRow: Boolean = true,
        /** Whether a real list uses the bottom quick-add bar instead of the FAB. */
        val bottomAddBar: Boolean = false,
        val sortOrder: TaskSortOrder = TaskSortOrder.DUE,
        /** This real list is kept out of the smart lists. */
        val hiddenFromSmartLists: Boolean = false,
    ) : TaskListUiState
}

/**
 * The tasks for one [TaskFilter]. Call [bind] with the filter the screen shows;
 * the list re-emits live as the provider changes. Actions are fire-and-forget —
 * the resulting provider change flows back through the list automatically.
 */
/** What the undo chip can take back. */
sealed interface UndoReceipt {
    val task: Task
    data class Deleted(override val task: Task) : UndoReceipt
    data class Completed(override val task: Task, val subtasks: List<Task> = emptyList()) : UndoReceipt
}

private const val UNDO_WINDOW_MS = 4_000L

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TaskListViewModel @Inject constructor(
    private val repository: TasksRepository,
    private val settingsPrefs: SettingsPrefs,
    private val remoteLists: RemoteListRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val filter = MutableStateFlow<TaskFilter?>(null)

    /** Occurrences swiped to delete but not yet committed — hidden until the undo lapses. */
    private val pendingDeletes = MutableStateFlow<Map<String, Task>>(emptyMap())

    private val _undo = MutableStateFlow<UndoReceipt?>(null)

    /** The action the floating undo chip is offering to take back, if any. */
    val undo: StateFlow<UndoReceipt?> = _undo.asStateFlow()
    private var undoTimer: Job? = null

    val state: StateFlow<TaskListUiState> =
        filter.filterNotNull()
            .flatMapLatest { f ->
                val tasks = repository.tasks(f)
                val content: kotlinx.coroutines.flow.Flow<TaskListUiState> = when (f) {
                    is TaskFilter.OfList ->
                        combine(tasks, repository.taskLists()) { rows, lists ->
                            TaskListUiState.Content(rows, lists.firstOrNull { it.id == f.listId })
                        }
                    is TaskFilter.Smart ->
                        tasks.map { TaskListUiState.Content(it) }
                }
                // Drop rows pending an undoable delete so the row vanishes on swipe
                // while the actual provider delete waits for the snackbar to commit,
                // and fold in the live UI settings (add-subtask row, bottom add bar).
                combine(content, pendingDeletes, settingsPrefs.settings) { st, pending, settings ->
                    if (st is TaskListUiState.Content) {
                        st.copy(
                            tasks = settings.visibleIn(f, st.tasks)
                                .filter { it.occurrenceKey !in pending }
                                .sortedWith(settings.sortOrder.comparator()),
                            showAddSubtaskRow = settings.showAddSubtaskRow,
                            bottomAddBar = settings.bottomAddBar,
                            sortOrder = settings.sortOrder,
                            hiddenFromSmartLists = st.list?.id?.let { it in settings.hiddenFromSmartLists } ?: false,
                        )
                    } else {
                        st
                    }
                }
                    .onStart { emit(TaskListUiState.Loading) }
                    // Recover rather than terminate: a provider hiccup (mid-update,
                    // permission not yet granted) shows Failure but keeps retrying,
                    // so the screen heals itself instead of staying stuck.
                    .recoveringFromProviderFailure { TaskListUiState.Failure }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskListUiState.Loading)

    /**
     * Parents the screen has expanded whose children it can't pull from the list
     * itself — i.e. smart lists, where the date filter drops a parent's subtasks.
     * For each we observe the full child set live (below), so the group can expand.
     */
    private val lazyChildParents = MutableStateFlow<Set<Long>>(emptySet())

    /**
     * The on-demand child sets, keyed by parent id, kept live. Empty for real
     * lists (the screen reports no parents there, since it already holds every
     * child) so no redundant queries run.
     */
    val lazyChildren: StateFlow<Map<Long, List<Task>>> =
        lazyChildParents
            .flatMapLatest { ids ->
                if (ids.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    combine(ids.map { id -> repository.subtasks(id).map { id to it } }) { it.toMap() }
                }
            }
            // Without this an exception here escapes stateIn's coroutine, past
            // viewModelScope's SupervisorJob, and crashes the process.
            .recoveringFromProviderFailure { emptyMap() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** The screen reports which expanded parents need their children fetched. */
    fun setLazyChildParents(ids: Set<Long>) { lazyChildParents.value = ids }

    fun bind(taskFilter: TaskFilter) { filter.value = taskFilter }

    fun toggleComplete(task: Task) {
        if (task.isCompleted) {
            viewModelScope.launch {
                runCatching { repository.setCompleted(task.taskId, task.occurrenceStart, false) }
            }
            return
        }
        viewModelScope.launch {
            // Completing a parent completes its open subtasks; undo has to know which.
            val openSubtasks = if (task.occurrenceStart == null) {
                runCatching { repository.subtasks(task.taskId).first() }.getOrDefault(emptyList()).filter { !it.isClosed }
            } else {
                emptyList()
            }
            runCatching { repository.setCompleted(task.taskId, task.occurrenceStart, true) }
            offerUndo(UndoReceipt.Completed(task, openSubtasks))
        }
    }

    /**
     * Swipe-delete: the row hides at once and the delete commits once the undo
     * window lapses — or when this screen goes away, whichever comes first. A
     * swiped occurrence of a series takes only that occurrence with it.
     */
    fun requestDelete(task: Task) {
        commitDeletes()
        pendingDeletes.value = pendingDeletes.value + (task.occurrenceKey to task)
        offerUndo(UndoReceipt.Deleted(task))
    }

    fun undo() {
        when (val receipt = _undo.value) {
            is UndoReceipt.Deleted -> pendingDeletes.value = pendingDeletes.value - receipt.task.occurrenceKey
            is UndoReceipt.Completed -> viewModelScope.launch {
                runCatching {
                    repository.setCompleted(receipt.task.taskId, receipt.task.occurrenceStart, false)
                    receipt.subtasks.forEach { repository.setCompleted(it.taskId, it.occurrenceStart, false) }
                }
            }
            null -> Unit
        }
        undoTimer?.cancel()
        _undo.value = null
    }

    /** Shows [receipt]; a delete already waiting is committed once its chip is replaced. */
    private fun offerUndo(receipt: UndoReceipt) {
        if (receipt !is UndoReceipt.Deleted) commitDeletes()
        undoTimer?.cancel()
        _undo.value = receipt
        undoTimer = viewModelScope.launch {
            delay(UNDO_WINDOW_MS)
            commitDeletes()
            _undo.value = null
        }
    }

    /**
     * Commits every pending delete. The rows stay hidden: they are gone from the
     * store, and un-hiding them could flash them back before the change lands.
     * Runs in the application scope so leaving the screen cannot cancel it.
     */
    private fun commitDeletes() {
        val due = pendingDeletes.value.values.filter { it.occurrenceKey !in committed }
        if (due.isEmpty()) return
        committed += due.map { it.occurrenceKey }
        appScope.launch {
            due.forEach { task ->
                runCatching { repository.deleteTask(task.taskId, task.occurrenceStart, RecurringScope.ThisOccurrence) }
            }
        }
    }

    private val committed = mutableSetOf<String>()

    override fun onCleared() {
        commitDeletes()
    }

    fun setHiddenFromSmartLists(listId: Long, hidden: Boolean) =
        viewModelScope.launch { settingsPrefs.setHiddenFromSmartLists(listId, hidden) }

    fun setSortOrder(order: TaskSortOrder) = viewModelScope.launch { settingsPrefs.setSortOrder(order) }

    /**
     * Deletes every completed task in [listId]. A completed occurrence goes on its
     * own, a series that is closed as a whole goes whole, and a parent that still
     * has open subtasks stays — deleting it would take them along.
     */
    fun clearCompleted(listId: Long) {
        val done = (state.value as? TaskListUiState.Content)?.tasks
            ?.filter { it.listId == listId && it.isCompleted && it.subtaskDone == it.subtaskTotal }
            .orEmpty()
        appScope.launch {
            done.forEach { task ->
                val wholeSeries = task.isOccurrence && task.taskId == task.seriesId
                val scope = if (wholeSeries) RecurringScope.AllOccurrences else RecurringScope.ThisOccurrence
                runCatching { repository.deleteTask(task.taskId, task.occurrenceStart, scope) }
            }
        }
    }

    /** Inline "add task" — title only, into [listId]. */
    fun quickAdd(title: String, listId: Long) = viewModelScope.launch {
        if (title.isBlank() || listId <= 0L) return@launch
        runCatching { repository.createTask(TaskForm(title = title, listId = listId)) }
    }

    private val _listWriteFailure = MutableStateFlow<ListWriteFailure?>(null)

    /** Set when a list write is refused; the screen shows it and clears it. */
    val listWriteFailure: StateFlow<ListWriteFailure?> = _listWriteFailure.asStateFlow()

    private val _listDeleted = MutableStateFlow(false)

    /** Flips once the list this screen shows is really gone, so it can leave. */
    val listDeleted: StateFlow<Boolean> = _listDeleted.asStateFlow()

    fun clearListWriteFailure() { _listWriteFailure.value = null }

    /**
     * Rename / recolour the list this screen is showing.
     *
     * ⚠️ A synced list goes to the server **first**. Writing the row and setting
     * `is_dirty` is what used to happen, and nothing ever read that flag — so a
     * rename looked like it worked, never left the phone, and was silently
     * reverted by whichever sync next re-read the collection's display name.
     */
    fun updateList(listId: Long, name: String, color: Int, accountId: Long?) =
        viewModelScope.launch {
            if (name.isBlank()) return@launch
            if (accountId == null) {
                runCatching { repository.updateList(listId, name.trim(), color) }
                    .onFailure { _listWriteFailure.value = ListWriteFailure.SAVE }
                return@launch
            }
            val outcome = runCatching { remoteLists.rename(listId, name.trim(), color) }
                .getOrElse { RemoteListRepository.Outcome.Unreachable }
            _listWriteFailure.value = outcome.asFailure(ListWriteFailure.SAVE)
        }

    /**
     * Delete the list **and its tasks**. The screen navigates away on
     * [listDeleted], not on the call — leaving first would strand a refusal on a
     * screen that no longer exists.
     */
    fun deleteList(listId: Long, accountId: Long?) = viewModelScope.launch {
        if (accountId == null) {
            runCatching { repository.deleteList(listId) }
                .onSuccess { _listDeleted.value = true }
                .onFailure { _listWriteFailure.value = ListWriteFailure.DELETE }
            return@launch
        }
        // ⚠️ The server first here too, and this one is destructive on both
        // sides. Deleting only the row left the collection on the server with
        // nothing pointing at it — the list vanished from the phone, kept
        // existing for every other client, and could only be got back by
        // re-adding the whole account.
        val outcome = runCatching { remoteLists.delete(listId) }
            .getOrElse { RemoteListRepository.Outcome.Unreachable }
        val failure = outcome.asFailure(ListWriteFailure.DELETE)
        if (failure == null) _listDeleted.value = true else _listWriteFailure.value = failure
    }

    /** Inline "add subtask" from an expanded list group — files it under [parent]. */
    fun quickAddSubtask(parent: Task, title: String) = viewModelScope.launch {
        if (title.isBlank() || parent.listId <= 0L) return@launch
        runCatching {
            repository.createTask(
                TaskForm(title = title.trim(), listId = parent.listId, parentId = parent.taskId),
            )
        }
    }
}
