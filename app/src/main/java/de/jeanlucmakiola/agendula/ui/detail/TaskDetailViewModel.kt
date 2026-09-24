package de.jeanlucmakiola.agendula.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jeanlucmakiola.agendula.data.tasks.TasksRepository
import de.jeanlucmakiola.agendula.data.tasks.recoveringFromProviderFailure
import de.jeanlucmakiola.agendula.domain.Task
import de.jeanlucmakiola.agendula.domain.TaskDetail
import de.jeanlucmakiola.agendula.domain.TaskForm
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import de.jeanlucmakiola.agendula.domain.recurrence.RecurringScope
import de.jeanlucmakiola.agendula.data.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject
import kotlin.time.Instant

sealed interface TaskDetailUiState {
    data object Loading : TaskDetailUiState
    data object NotFound : TaskDetailUiState
    data class Content(
        val detail: TaskDetail,
        /** The task's own reminders; empty when the list's or app default applies. */
        val reminders: List<de.jeanlucmakiola.agendula.data.tasks.TaskReminder> = emptyList(),
    ) : TaskDetailUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TaskDetailViewModel @Inject constructor(
    private val repository: TasksRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val target = MutableStateFlow<Pair<Long, Instant?>?>(null)

    val state: StateFlow<TaskDetailUiState> =
        target.filterNotNull()
            .flatMapLatest { (id, occurrence) ->
                repository.taskDetail(id, occurrence)
                    .map<TaskDetail?, TaskDetailUiState> { detail ->
                        if (detail == null) {
                            TaskDetailUiState.NotFound
                        } else {
                            TaskDetailUiState.Content(
                                detail = detail,
                                reminders = repository.remindersFor(detail.task.taskId),
                            )
                        }
                    }
                    .onStart { emit(TaskDetailUiState.Loading) }
                    .recoveringFromProviderFailure { TaskDetailUiState.NotFound }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskDetailUiState.Loading)

    fun bind(id: Long, occurrenceStart: Instant? = null) { target.value = id to occurrenceStart }

    fun toggleComplete(task: Task) = viewModelScope.launch {
        runCatching { repository.setCompleted(task.taskId, task.occurrenceStart, !task.isCompleted) }
    }

    fun setCancelled(task: Task, cancelled: Boolean) = viewModelScope.launch {
        runCatching { repository.setCancelled(task.taskId, task.occurrenceStart, cancelled) }
    }

    /** In the application scope: the screen pops as soon as this is called. */
    fun delete(task: Task, scope: RecurringScope = RecurringScope.AllOccurrences) = appScope.launch {
        runCatching { repository.deleteTask(task.taskId, task.occurrenceStart, scope) }
    }

    /** Copy the task and hand the copy's id to [onCopied], on the main thread. */
    fun duplicate(task: Task, onCopied: (Long) -> Unit) = viewModelScope.launch {
        runCatching { repository.duplicateTask(task.taskId, task.occurrenceStart) }.onSuccess(onCopied)
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
