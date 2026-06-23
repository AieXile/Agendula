package de.jeanlucmakiola.floret.ui.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jeanlucmakiola.floret.data.prefs.SettingsPrefs
import de.jeanlucmakiola.floret.data.reminders.ReminderScheduler
import de.jeanlucmakiola.floret.data.tasks.TasksRepository
import de.jeanlucmakiola.floret.domain.Priority
import de.jeanlucmakiola.floret.domain.TaskForm
import de.jeanlucmakiola.floret.domain.TaskFormError
import de.jeanlucmakiola.floret.domain.TaskFormField
import de.jeanlucmakiola.floret.domain.TaskList
import de.jeanlucmakiola.floret.domain.populatedFields
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Instant

/**
 * Editable form state for creating or editing a task. The screen renders these
 * fields and calls the `on*` setters; [save] validates, writes, re-syncs
 * reminders, and flips [TaskEditUiState.saved] for the screen to navigate back.
 */
data class TaskEditUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val title: String = "",
    val description: String = "",
    val listId: Long? = null,
    val start: Instant? = null,
    val due: Instant? = null,
    val isAllDay: Boolean = false,
    val priority: Priority = Priority.NONE,
    val parentId: Long? = null,
    val reminderMinutesBeforeDue: Int? = null,
    val lists: List<TaskList> = emptyList(),
    /** Optional sections currently shown (core Title / List / When are always on). */
    val visibleFields: Set<TaskFormField> = emptySet(),
    /** Optional sections still tucked behind "More fields". */
    val hiddenFields: List<TaskFormField> = emptyList(),
    val errors: Set<TaskFormError> = emptySet(),
    val saveFailed: Boolean = false,
    val saved: Boolean = false,
)

@HiltViewModel
class TaskEditViewModel @Inject constructor(
    private val repository: TasksRepository,
    private val settingsPrefs: SettingsPrefs,
    private val reminderScheduler: ReminderScheduler,
) : ViewModel() {

    private val _state = MutableStateFlow(TaskEditUiState())
    val state: StateFlow<TaskEditUiState> = _state.asStateFlow()

    private var editingTaskId: Long? = null

    /** Sections shown by default (a setting); the rest unfold via [revealField]. */
    private var defaultFields: Set<TaskFormField> = emptySet()

    /** Start a fresh task, optionally pre-selecting a list / parent. */
    fun bindNew(presetListId: Long? = null, parentId: Long? = null) {
        editingTaskId = null
        viewModelScope.launch {
            val settings = settingsPrefs.settings.first()
            defaultFields = settings.defaultEditFields
            val lists = runCatching { repository.taskLists().first() }.getOrElse { emptyList() }
            val defaultList = presetListId
                ?: settings.defaultListId
                ?: lists.firstOrNull { !it.isLocal }?.id
                ?: lists.firstOrNull()?.id
            _state.value = withFields(
                TaskEditUiState(
                    loading = false,
                    isNew = true,
                    listId = defaultList,
                    parentId = parentId,
                    lists = lists,
                ),
            )
        }
    }

    /** Load an existing task for editing. */
    fun bindEdit(taskId: Long) {
        editingTaskId = taskId
        viewModelScope.launch {
            defaultFields = settingsPrefs.settings.first().defaultEditFields
            val lists = runCatching { repository.taskLists().first() }.getOrElse { emptyList() }
            val task = runCatching { repository.taskDetail(taskId).first()?.task }.getOrNull()
            if (task == null) {
                _state.value = _state.value.copy(loading = false, lists = lists)
                return@launch
            }
            _state.value = withFields(
                TaskEditUiState(
                    loading = false,
                    isNew = false,
                    title = task.title,
                    description = task.description.orEmpty(),
                    listId = task.listId,
                    start = task.start,
                    due = task.due,
                    isAllDay = task.isAllDay,
                    priority = task.priority,
                    parentId = task.parentId,
                    lists = lists,
                ),
            )
        }
    }

    /** Reveal an optional section (one tap from the "More fields" picker). */
    fun revealField(field: TaskFormField) = update {
        val visible = it.visibleFields + field
        it.copy(visibleFields = visible, hiddenFields = hiddenOf(visible))
    }

    /**
     * Resolve which optional sections are visible: the default set, plus any
     * field that already carries a value (so editing never hides real data).
     */
    private fun withFields(state: TaskEditUiState): TaskEditUiState {
        val visible = defaultFields + state.toForm().populatedFields()
        return state.copy(visibleFields = visible, hiddenFields = hiddenOf(visible))
    }

    private fun hiddenOf(visible: Set<TaskFormField>): List<TaskFormField> =
        TaskFormField.entries.filterNot { it in visible }

    fun onTitleChange(value: String) = update { it.copy(title = value, errors = emptySet()) }
    fun onDescriptionChange(value: String) = update { it.copy(description = value) }
    fun onListChange(listId: Long) = update { it.copy(listId = listId, errors = emptySet()) }
    fun onStartChange(value: Instant?) = update { it.copy(start = value) }
    fun onDueChange(value: Instant?) = update { it.copy(due = value) }
    fun onAllDayChange(value: Boolean) = update { it.copy(isAllDay = value) }
    fun onPriorityChange(value: Priority) = update { it.copy(priority = value) }
    fun onReminderChange(minutesBeforeDue: Int?) = update { it.copy(reminderMinutesBeforeDue = minutesBeforeDue) }

    fun save() {
        val current = _state.value
        val form = current.toForm()
        val errors = form.validate()
        if (errors.isNotEmpty()) {
            _state.value = current.copy(errors = errors)
            return
        }
        viewModelScope.launch {
            runCatching {
                val id = editingTaskId
                if (id == null) repository.createTask(form) else repository.updateTask(id, form)
                reminderScheduler.sync()
            }.onSuccess {
                _state.value = _state.value.copy(saved = true, saveFailed = false)
            }.onFailure {
                _state.value = _state.value.copy(saveFailed = true)
            }
        }
    }

    private inline fun update(block: (TaskEditUiState) -> TaskEditUiState) {
        _state.value = block(_state.value)
    }
}

/** Snapshot the editable state as a validated [TaskForm]. */
private fun TaskEditUiState.toForm(): TaskForm = TaskForm(
    title = title,
    listId = listId ?: 0L,
    description = description.ifBlank { null },
    start = start,
    due = due,
    isAllDay = isAllDay,
    priority = priority,
    parentId = parentId,
    reminderMinutesBeforeDue = reminderMinutesBeforeDue,
)
