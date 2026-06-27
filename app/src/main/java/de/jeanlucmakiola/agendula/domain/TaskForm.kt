package de.jeanlucmakiola.agendula.domain

import kotlin.time.Instant

/**
 * A validated create/edit form. Kept free of Android types so ViewModels and
 * tests can build and validate it on the JVM. The data layer turns it into
 * provider ContentValues ([de.jeanlucmakiola.agendula.data.tasks.TaskWriteMapper]).
 */
data class TaskForm(
    val title: String,
    val listId: Long,
    val description: String? = null,
    val start: Instant? = null,
    val due: Instant? = null,
    val isAllDay: Boolean = false,
    val priority: Priority = Priority.NONE,
    val parentId: Long? = null,
    /** 0..100 progress; `null` leaves it unset (cleared). */
    val percentComplete: Int? = null,
    /** Minutes before [due] to fire a reminder; `null` = no reminder. */
    val reminderMinutesBeforeDue: Int? = null,
) {
    fun validate(): Set<TaskFormError> = buildSet {
        if (title.isBlank()) add(TaskFormError.BLANK_TITLE)
        if (listId <= 0L) add(TaskFormError.NO_LIST)
        if (start != null && due != null && due < start) add(TaskFormError.DUE_BEFORE_START)
        if (reminderMinutesBeforeDue != null && due == null) add(TaskFormError.REMINDER_WITHOUT_DUE)
    }

    val isValid: Boolean get() = validate().isEmpty()
}

enum class TaskFormError { BLANK_TITLE, NO_LIST, DUE_BEFORE_START, REMINDER_WITHOUT_DUE }

/**
 * Optional edit-form sections that can be shown or tucked behind "More fields"
 * (the core Title / List / When are always visible). Which render by default is
 * a setting; the rest unfold on demand. Declaring these as an enum keeps the
 * disclosure generic, so adding a field later is one entry plus its card.
 */
enum class TaskFormField { Description, Priority, Progress, Parent, Reminder }

/** The optional fields that already carry a value — auto-revealed when editing. */
fun TaskForm.populatedFields(): Set<TaskFormField> = buildSet {
    if (!description.isNullOrBlank()) add(TaskFormField.Description)
    if (priority != Priority.NONE) add(TaskFormField.Priority)
    if ((percentComplete ?: 0) > 0) add(TaskFormField.Progress)
    if (parentId != null && parentId > 0) add(TaskFormField.Parent)
    if (reminderMinutesBeforeDue != null) add(TaskFormField.Reminder)
}
