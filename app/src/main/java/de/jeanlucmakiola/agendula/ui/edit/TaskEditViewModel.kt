package de.jeanlucmakiola.agendula.ui.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jeanlucmakiola.agendula.data.prefs.SettingsPrefs
import de.jeanlucmakiola.agendula.data.reminders.ReminderScheduler
import de.jeanlucmakiola.agendula.data.tasks.TaskConflictException
import de.jeanlucmakiola.agendula.data.tasks.TasksRepository
import de.jeanlucmakiola.agendula.domain.Priority
import de.jeanlucmakiola.agendula.domain.Task
import de.jeanlucmakiola.agendula.domain.TaskFilter
import de.jeanlucmakiola.agendula.domain.TaskForm
import de.jeanlucmakiola.agendula.domain.TaskFormError
import de.jeanlucmakiola.agendula.domain.TaskFormField
import de.jeanlucmakiola.agendula.domain.TaskList
import de.jeanlucmakiola.agendula.domain.populatedFields
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
    val percentComplete: Int? = null,
    val reminderMinutesBeforeDue: Int? = null,
    val lists: List<TaskList> = emptyList(),
    /** Top-level tasks in the current list this task can be filed under (reparent). */
    val parentCandidates: List<Task> = emptyList(),
    /** Optional sections currently shown (core Title / List / When are always on). */
    val visibleFields: Set<TaskFormField> = emptySet(),
    /** Optional sections still tucked behind "More fields". */
    val hiddenFields: List<TaskFormField> = emptyList(),
    val errors: Set<TaskFormError> = emptySet(),
    val saveFailed: Boolean = false,
    /** The task changed underneath us; the screen asks to overwrite or cancel. */
    val saveConflict: Boolean = false,
    val saved: Boolean = false,
) {
    /** The currently-selected parent task, if this task is filed under one. */
    val parent: Task? get() = parentCandidates.firstOrNull { it.taskId == parentId }
}

@HiltViewModel
class TaskEditViewModel @Inject constructor(
    private val repository: TasksRepository,
    private val settingsPrefs: SettingsPrefs,
    private val reminderScheduler: ReminderScheduler,
) : ViewModel() {

    private val _state = MutableStateFlow(TaskEditUiState())
    val state: StateFlow<TaskEditUiState> = _state.asStateFlow()

    private var editingTaskId: Long? = null

    /** `last_modified` captured when the form loaded — the conflict-check baseline. */
    private var baselineLastModified: Instant? = null

    /** Sections shown by default (a setting); the rest unfold via [revealField]. */
    private var defaultFields: Set<TaskFormField> = emptySet()

    /** Start a fresh task, optionally pre-selecting a list / parent. */
    fun bindNew(presetListId: Long? = null, parentId: Long? = null) {
        editingTaskId = null
        baselineLastModified = null
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
                    parentCandidates = loadParents(defaultList, selfId = null),
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
            baselineLastModified = task.lastModified
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
                    percentComplete = task.percentComplete,
                    lists = lists,
                    parentCandidates = loadParents(task.listId, selfId = taskId),
                ),
            )
        }
    }

    /**
     * Tasks the edited task can be filed under: active, top-level tasks in
     * [listId], minus the task itself (a task can't parent itself). Restricting to
     * top-level keeps the hierarchy one level deep (matching the detail screen),
     * and to active so you don't file work under something already done/cancelled.
     */
    private suspend fun loadParents(listId: Long?, selfId: Long?): List<Task> {
        if (listId == null) return emptyList()
        val tasks = runCatching { repository.tasks(TaskFilter.OfList(listId)).first() }.getOrElse { emptyList() }
        return tasks.filter { !it.isClosed && !it.isSubtask && it.taskId != selfId }
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

    /** Switching list invalidates the parent (it lived in the old list); reload candidates. */
    fun onListChange(listId: Long) {
        if (_state.value.listId == listId) return // re-picking the same list keeps the parent
        update { it.copy(listId = listId, parentId = null, errors = emptySet()) }
        viewModelScope.launch {
            val candidates = loadParents(listId, selfId = editingTaskId)
            update { it.copy(parentCandidates = candidates) }
        }
    }

    fun onStartChange(value: Instant?) = update { it.copy(start = value) }
    fun onDueChange(value: Instant?) = update { it.copy(due = value) }
    fun onAllDayChange(value: Boolean) = update { it.copy(isAllDay = value) }
    fun onPriorityChange(value: Priority) = update { it.copy(priority = value) }
    fun onPercentChange(value: Int?) = update { it.copy(percentComplete = value?.coerceIn(0, 100)) }
    fun onParentChange(parentId: Long?) = update { it.copy(parentId = parentId) }
    fun onReminderChange(minutesBeforeDue: Int?) = update { it.copy(reminderMinutesBeforeDue = minutesBeforeDue) }

    /** Dismiss the conflict prompt without saving (the user keeps editing). */
    fun dismissConflict() = update { it.copy(saveConflict = false) }

    /** [force] = overwrite even if the task changed since it loaded (conflict prompt). */
    fun save(force: Boolean = false) {
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
                if (id == null) {
                    repository.createTask(form)
                } else {
                    repository.updateTask(id, form, expectedLastModified = if (force) null else baselineLastModified)
                }
                reminderScheduler.sync()
            }.onSuccess {
                _state.value = _state.value.copy(saved = true, saveFailed = false, saveConflict = false)
            }.onFailure { error ->
                _state.value = if (error is TaskConflictException) {
                    _state.value.copy(saveConflict = true, saveFailed = false)
                } else {
                    _state.value.copy(saveFailed = true)
                }
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
    percentComplete = percentComplete,
    reminderMinutesBeforeDue = reminderMinutesBeforeDue,
)
