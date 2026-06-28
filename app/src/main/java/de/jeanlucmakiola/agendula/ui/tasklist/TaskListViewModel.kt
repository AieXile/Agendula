package de.jeanlucmakiola.agendula.ui.tasklist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jeanlucmakiola.agendula.data.prefs.SettingsPrefs
import de.jeanlucmakiola.agendula.data.tasks.TasksRepository
import de.jeanlucmakiola.agendula.domain.Task
import de.jeanlucmakiola.agendula.domain.TaskFilter
import de.jeanlucmakiola.agendula.domain.TaskForm
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
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
     * [listName] is the real list's name when the filter is a
     * [TaskFilter.OfList] (for the top-bar title), `null` for smart lists —
     * the screen falls back to the smart label in that case.
     */
    data class Content(
        val tasks: List<Task>,
        val listName: String? = null,
        /** Whether the inline "add a subtask" row shows on expanded groups (M5 setting). */
        val showAddSubtaskRow: Boolean = true,
        /** Whether a real list uses the bottom quick-add bar instead of the FAB. */
        val bottomAddBar: Boolean = false,
    ) : TaskListUiState
}

/**
 * The tasks for one [TaskFilter]. Call [bind] with the filter the screen shows;
 * the list re-emits live as the provider changes. Actions are fire-and-forget —
 * the resulting provider change flows back through the list automatically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TaskListViewModel @Inject constructor(
    private val repository: TasksRepository,
    private val settingsPrefs: SettingsPrefs,
) : ViewModel() {

    private val filter = MutableStateFlow<TaskFilter?>(null)

    /** Tasks swiped to delete but not yet committed — hidden until the undo lapses. */
    private val pendingDeletes = MutableStateFlow<Set<Long>>(emptySet())

    val state: StateFlow<TaskListUiState> =
        filter.filterNotNull()
            .flatMapLatest { f ->
                val tasks = repository.tasks(f)
                val content: kotlinx.coroutines.flow.Flow<TaskListUiState> = when (f) {
                    is TaskFilter.OfList ->
                        combine(tasks, repository.taskLists()) { list, lists ->
                            TaskListUiState.Content(list, lists.firstOrNull { it.id == f.listId }?.name)
                        }
                    is TaskFilter.Smart ->
                        tasks.map { TaskListUiState.Content(it) }
                }
                // Drop rows pending an undoable delete so the row vanishes on swipe
                // while the actual provider delete waits for the snackbar to commit,
                // and fold in the live UI settings (add-subtask row, bottom add bar).
                val uiPrefs = settingsPrefs.settings.map { it.showAddSubtaskRow to it.bottomAddBar }
                combine(content, pendingDeletes, uiPrefs) { st, pending, (showRow, bottomBar) ->
                    if (st is TaskListUiState.Content) {
                        st.copy(
                            tasks = st.tasks.filter { it.taskId !in pending },
                            showAddSubtaskRow = showRow,
                            bottomAddBar = bottomBar,
                        )
                    } else {
                        st
                    }
                }
                    .onStart { emit(TaskListUiState.Loading) }
                    .catch { emit(TaskListUiState.Failure) }
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
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** The screen reports which expanded parents need their children fetched. */
    fun setLazyChildParents(ids: Set<Long>) { lazyChildParents.value = ids }

    fun bind(taskFilter: TaskFilter) { filter.value = taskFilter }

    fun toggleComplete(task: Task) = viewModelScope.launch {
        runCatching { repository.setCompleted(task.taskId, !task.isCompleted) }
    }

    /** Swipe-delete: hide the row now; the screen's snackbar commits or restores it. */
    fun markPendingDelete(task: Task) {
        pendingDeletes.value = pendingDeletes.value + task.taskId
    }

    /** Undo — stop hiding the task; it returns to the list. */
    fun undoDelete(taskId: Long) {
        pendingDeletes.value = pendingDeletes.value - taskId
    }

    /**
     * Commit the delete once the undo window passes. The id stays in the pending
     * set: the task is gone from the provider so it can't reappear, and removing
     * it could briefly un-hide the row before the provider change propagates.
     */
    fun commitDelete(taskId: Long) = viewModelScope.launch {
        if (taskId !in pendingDeletes.value) return@launch
        runCatching { repository.deleteTask(taskId) }
    }

    /** Inline "add task" — title only, into [listId]. */
    fun quickAdd(title: String, listId: Long) = viewModelScope.launch {
        if (title.isBlank() || listId <= 0L) return@launch
        runCatching { repository.createTask(TaskForm(title = title, listId = listId)) }
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
