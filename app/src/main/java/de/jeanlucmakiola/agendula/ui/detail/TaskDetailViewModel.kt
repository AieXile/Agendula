package de.jeanlucmakiola.agendula.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jeanlucmakiola.agendula.data.tasks.TasksRepository
import de.jeanlucmakiola.agendula.domain.Task
import de.jeanlucmakiola.agendula.domain.TaskDetail
import de.jeanlucmakiola.agendula.domain.TaskForm
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

sealed interface TaskDetailUiState {
    data object Loading : TaskDetailUiState
    data object NotFound : TaskDetailUiState
    data class Content(val detail: TaskDetail) : TaskDetailUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TaskDetailViewModel @Inject constructor(
    private val repository: TasksRepository,
) : ViewModel() {

    private val taskId = MutableStateFlow<Long?>(null)

    val state: StateFlow<TaskDetailUiState> =
        taskId.filterNotNull()
            .flatMapLatest { id ->
                repository.taskDetail(id)
                    .map<TaskDetail?, TaskDetailUiState> { detail ->
                        if (detail == null) TaskDetailUiState.NotFound else TaskDetailUiState.Content(detail)
                    }
                    .onStart { emit(TaskDetailUiState.Loading) }
                    .catch { emit(TaskDetailUiState.NotFound) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskDetailUiState.Loading)

    fun bind(id: Long) { taskId.value = id }

    fun toggleComplete(task: Task) = viewModelScope.launch {
        runCatching { repository.setCompleted(task.taskId, !task.isCompleted) }
    }

    fun delete(task: Task) = viewModelScope.launch {
        runCatching { repository.deleteTask(task.taskId) }
    }

    /** Add a subtask (title only) under the currently-shown task. */
    fun addSubtask(title: String) = viewModelScope.launch {
        val parent = (state.value as? TaskDetailUiState.Content)?.detail?.task ?: return@launch
        if (title.isBlank()) return@launch
        runCatching {
            repository.createTask(
                TaskForm(title = title.trim(), listId = parent.listId, parentId = parent.taskId),
            )
        }
    }
}
