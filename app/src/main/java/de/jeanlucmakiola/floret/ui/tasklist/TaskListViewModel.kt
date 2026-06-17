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
    data class Content(val tasks: List<Task>) : TaskListUiState
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

    val state: StateFlow<TaskListUiState> =
        filter.filterNotNull()
            .flatMapLatest { f ->
                repository.tasks(f)
                    .map<List<Task>, TaskListUiState> { TaskListUiState.Content(it) }
                    .onStart { emit(TaskListUiState.Loading) }
                    .catch { emit(TaskListUiState.Failure) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskListUiState.Loading)

    fun bind(taskFilter: TaskFilter) { filter.value = taskFilter }

    fun toggleComplete(task: Task) = viewModelScope.launch {
        runCatching { repository.setCompleted(task.taskId, !task.isCompleted) }
    }

    fun delete(task: Task) = viewModelScope.launch {
        runCatching { repository.deleteTask(task.taskId) }
    }

    /** Inline "add task" — title only, into [listId]. */
    fun quickAdd(title: String, listId: Long) = viewModelScope.launch {
        if (title.isBlank() || listId <= 0L) return@launch
        runCatching { repository.createTask(TaskForm(title = title, listId = listId)) }
    }
}
