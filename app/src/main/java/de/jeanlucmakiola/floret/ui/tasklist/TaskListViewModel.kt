package de.jeanlucmakiola.floret.ui.tasklist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jeanlucmakiola.floret.data.tasks.TasksRepository
import de.jeanlucmakiola.floret.domain.Task
import de.jeanlucmakiola.floret.domain.TaskFilter
import de.jeanlucmakiola.floret.domain.TaskForm
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
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
    data class Content(val tasks: List<Task>, val listName: String? = null) : TaskListUiState
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
                // while the actual provider delete waits for the snackbar to commit.
                combine(content, pendingDeletes) { st, pending ->
                    if (st is TaskListUiState.Content) {
                        st.copy(tasks = st.tasks.filter { it.taskId !in pending })
                    } else {
                        st
                    }
                }
                    .onStart { emit(TaskListUiState.Loading) }
                    .catch { emit(TaskListUiState.Failure) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskListUiState.Loading)

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
