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
import de.jeanlucmakiola.agendula.domain.htmlToPlainText
import de.jeanlucmakiola.agendula.domain.looksLikeHtml
import de.jeanlucmakiola.agendula.domain.rebasedForAllDay
import de.jeanlucmakiola.agendula.domain.readableDescription
import de.jeanlucmakiola.agendula.domain.recurrence.RecurringScope
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
    /** The stored description when it was HTML, written back as-is unless its text is edited. */
    val descriptionHtml: String? = null,
    val listId: Long? = null,
    val start: Instant? = null,
    val due: Instant? = null,
    val isAllDay: Boolean = false,
    val priority: Priority = Priority.NONE,
    val parentId: Long? = null,
    val percentComplete: Int? = null,
    val reminders: List<Int> = emptyList(),
    val location: String = "",
    val url: String = "",
    val rrule: String? = null,
    /** Editing one occurrence of a series: saving asks how far the edit reaches. */
    val isOccurrence: Boolean = false,
    /** The series' rule as loaded; a changed rule rules out "only this task". */
    val originalRule: String? = null,
    /** Something differs from what was loaded — back asks before discarding it. */
    val isDirty: Boolean = false,
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
    /** A fresh task whose title should take focus once the form shows. */
    val autofocusTitle: Boolean = false,
) {
    /** The currently-selected parent task, if this task is filed under one. */
    val parent: Task? get() = parentCandidates.firstOrNull { it.taskId == parentId }

    /** Lists a task can be saved into: read-only shares drop out, except the one it is already in. */
    val writableLists: List<TaskList> get() = lists.filter { it.acceptsWrites || it.id == listId }

    val recurrenceChanged: Boolean get() = rrule != originalRule
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
    private var editingOccurrence: Instant? = null

    /** The form as loaded, to tell a real edit from an untouched one. */
    private var baseline: TaskForm? = null

    /**
     * Whether the form has already been populated. The host `LaunchedEffect`
     * re-fires whenever the composition restarts — an Activity recreation
     * (rotation, theme/font/display-size change, split-screen, unfolding) — while
     * this ViewModel survives on the nav back stack. Without this guard the
     * rebind would overwrite in-progress edits with the untouched provider row.
     */
    private var bound = false

    /** `last_modified` captured when the form loaded — the conflict-check baseline. */
    private var baselineLastModified: Instant? = null

    /** Sections shown by default (a setting); the rest unfold via [revealField]. */
    private var defaultFields: Set<TaskFormField> = emptySet()

    /** Start a fresh task, optionally pre-selecting a list / parent. */
    fun bindNew(presetListId: Long? = null, parentId: Long? = null, initialTitle: String? = null) {
        if (bound) return
        bound = true
        editingTaskId = null
        baselineLastModified = null
        viewModelScope.launch {
            val settings = settingsPrefs.settings.first()
            defaultFields = settings.defaultEditFields
            val lists = runCatching { repository.taskLists().first() }.getOrElse { emptyList() }
            // A read-only or vanished default would give the task a list it
            // cannot be saved to.
            val writable = lists.filter { it.acceptsWrites }
            val defaultList = presetListId
                ?: settings.defaultListId?.takeIf { id -> writable.any { it.id == id } }
                ?: writable.firstOrNull { !it.isLocal }?.id
                ?: writable.firstOrNull()?.id
            _state.value = withFields(
                TaskEditUiState(
                    loading = false,
                    isNew = true,
                    autofocusTitle = settings.autofocusTitle,
                    listId = defaultList,
                    parentId = parentId,
                    lists = lists,
                    parentCandidates = loadParents(defaultList, selfId = null),
                    title = initialTitle.orEmpty(),
                ),
            ).also { baseline = it.toForm().copy(title = "") }
        }
    }

    /** Load an existing task for editing. */
    fun bindEdit(taskId: Long, occurrenceStart: Instant? = null) {
        if (bound && editingTaskId == taskId) return
        bound = true
        editingTaskId = taskId
        editingOccurrence = occurrenceStart
        viewModelScope.launch {
            defaultFields = settingsPrefs.settings.first().defaultEditFields
            val lists = runCatching { repository.taskLists().first() }.getOrElse { emptyList() }
            val task = runCatching { repository.taskDetail(taskId, occurrenceStart).first()?.task }.getOrNull()
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
                    description = task.description?.readableDescription().orEmpty(),
                    descriptionHtml = task.description?.takeIf { it.looksLikeHtml() },
                    listId = task.listId,
                    start = task.start,
                    due = task.due,
                    isAllDay = task.isAllDay,
                    priority = task.priority,
                    parentId = task.parentId,
                    percentComplete = task.percentComplete,
                    reminders = repository.remindersFor(taskId).filter { !it.fromStart }.map { it.minutesBefore },
                    location = task.location.orEmpty(),
                    url = task.url.orEmpty(),
                    rrule = task.recurrenceRule,
                    originalRule = task.recurrenceRule,
                    isOccurrence = task.isOccurrence,
                    lists = lists,
                    parentCandidates = loadParents(task.listId, selfId = taskId),
                ),
            ).also { baseline = it.toForm() }
            editingOccurrence = task.occurrenceStart
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
    /**
     * All-day and timed values use different conventions (UTC midnight vs. a real
     * instant in the device zone), so the switch has to move the timestamps too —
     * flipping the flag alone makes an all-day task read back as "02:00", which
     * looks to the user like the time reset itself.
     */
    fun onAllDayChange(value: Boolean) = update {
        it.copy(
            isAllDay = value,
            start = it.start?.rebasedForAllDay(value),
            due = it.due?.rebasedForAllDay(value),
        )
    }
    fun onPriorityChange(value: Priority) = update { it.copy(priority = value) }
    fun onPercentChange(value: Int?) = update { it.copy(percentComplete = value?.coerceIn(0, 100)) }
    fun onParentChange(parentId: Long?) = update { it.copy(parentId = parentId) }
    fun addReminder(minutesBeforeDue: Int) = update {
        it.copy(reminders = (it.reminders + minutesBeforeDue).distinct().sorted(), errors = emptySet())
    }
    fun removeReminder(minutesBeforeDue: Int) = update { it.copy(reminders = it.reminders - minutesBeforeDue) }
    fun onLocationChange(value: String) = update { it.copy(location = value) }
    fun onUrlChange(value: String) = update { it.copy(url = value) }
    fun onRecurrenceChange(rrule: String?) = update { it.copy(rrule = rrule, errors = emptySet()) }

    /** Dismiss the conflict prompt without saving (the user keeps editing). */
    fun dismissConflict() = update { it.copy(saveConflict = false) }

    /** Whether saving needs the "this / following / all" choice first. */
    fun needsScope(): Boolean {
        val current = _state.value
        return !current.isNew && current.isOccurrence && current.toForm().validate().isEmpty()
    }

    /**
     * [force] = overwrite even if the task changed since it loaded (conflict prompt).
     * [scope] only matters when editing one occurrence of a series.
     */
    fun save(force: Boolean = false, scope: RecurringScope = lastScope) {
        lastScope = scope
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
                    // ⚠️ Remembered. A second Save on this screen would otherwise
                    // take the create path again and write a second task —
                    // whatever sent the user back to it.
                    editingTaskId = repository.createTask(form)
                } else {
                    repository.updateTask(
                        taskId = id,
                        form = form,
                        expectedLastModified = if (force) null else baselineLastModified,
                        occurrenceStart = editingOccurrence,
                        scope = scope,
                    )
                }
                // ⚠️ Outside the write's own result. The task is saved by this
                // point, and reporting a scheduling failure as a *save* failure
                // sends the user back to tap Save again — on a screen whose
                // editingTaskId is still null, which creates a second task. The
                // reminder is re-synced on the next data change and on launch;
                // the duplicate is forever.
                runCatching { reminderScheduler.sync() }
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

    private var lastScope: RecurringScope = RecurringScope.ThisOccurrence

    private inline fun update(block: (TaskEditUiState) -> TaskEditUiState) {
        val next = block(_state.value)
        _state.value = next.copy(isDirty = baseline != null && next.toForm() != baseline)
    }
}

/** Snapshot the editable state as a validated [TaskForm]. */
private fun TaskEditUiState.toForm(): TaskForm = TaskForm(
    title = title,
    listId = listId ?: 0L,
    description = descriptionHtml?.takeIf { it.htmlToPlainText() == description } ?: description.ifBlank { null },
    start = start,
    due = due,
    isAllDay = isAllDay,
    priority = priority,
    parentId = parentId,
    percentComplete = percentComplete,
    reminders = reminders,
    location = location.ifBlank { null },
    url = url.ifBlank { null },
    rrule = rrule,
)
