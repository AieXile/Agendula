package de.jeanlucmakiola.floret.domain

import kotlin.time.Instant

/**
 * A validated create/edit form. Kept free of Android types so ViewModels and
 * tests can build and validate it on the JVM. The data layer turns it into
 * provider ContentValues ([de.jeanlucmakiola.floret.data.tasks.TaskWriteMapper]).
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
