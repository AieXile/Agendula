package com.aiexile.agendula.domain

import kotlin.time.Instant

/**
 * A validated create/edit form. Kept free of Android types so ViewModels and
 * tests can build and validate it on the JVM. The data layer turns it into
 * provider ContentValues ([com.aiexile.agendula.data.tasks.TaskWriteMapper]).
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
    /** The task's own reminders; empty = the list's or app default applies, unless [remindersChosen]. */
    val reminders: List<TaskReminder> = emptyList(),
    /** Whether [reminders] are exactly the task's, as the editor showed them: empty is then none. */
    val remindersChosen: Boolean = false,
    val location: String? = null,
    val url: String? = null,
    val tags: List<String> = emptyList(),
    /** The series' `RRULE` value; `null` = does not repeat. Ignored for a single-occurrence edit. */
    val rrule: String? = null,
) {
    fun validate(): Set<TaskFormError> = buildSet {
        if (title.isBlank()) add(TaskFormError.BLANK_TITLE)
        if (listId <= 0L) add(TaskFormError.NO_LIST)
        if (start != null && due != null && due < start) add(TaskFormError.DUE_BEFORE_START)
        if (reminders.isNotEmpty() && start == null && due == null) add(TaskFormError.REMINDER_WITHOUT_DATE)
        if (rrule != null && start == null && due == null) add(TaskFormError.RECURRENCE_WITHOUT_DATE)
    }

    val isValid: Boolean get() = validate().isEmpty()
}

enum class TaskFormError { BLANK_TITLE, NO_LIST, DUE_BEFORE_START, REMINDER_WITHOUT_DATE, RECURRENCE_WITHOUT_DATE }

/**
 * Optional edit-form sections that can be shown or tucked behind "More fields"
 * (the core Title / List / When are always visible). Which render by default is
 * a setting; the rest unfold on demand. Declaring these as an enum keeps the
 * disclosure generic, so adding a field later is one entry plus its card.
 */
enum class TaskFormField { Description, Recurrence, Priority, Progress, Parent, Reminder, Location, Url, Tags }

/** The optional fields that already carry a value — auto-revealed when editing. */
fun TaskForm.populatedFields(): Set<TaskFormField> = buildSet {
    if (!description.isNullOrBlank()) add(TaskFormField.Description)
    if (priority != Priority.NONE) add(TaskFormField.Priority)
    if ((percentComplete ?: 0) > 0) add(TaskFormField.Progress)
    if (parentId != null && parentId > 0) add(TaskFormField.Parent)
    if (reminders.isNotEmpty()) add(TaskFormField.Reminder)
    if (rrule != null) add(TaskFormField.Recurrence)
    if (!location.isNullOrBlank()) add(TaskFormField.Location)
    if (!url.isNullOrBlank()) add(TaskFormField.Url)
    if (tags.isNotEmpty()) add(TaskFormField.Tags)
}
