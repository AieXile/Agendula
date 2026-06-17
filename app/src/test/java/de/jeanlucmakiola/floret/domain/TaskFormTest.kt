package de.jeanlucmakiola.floret.domain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class TaskFormTest {

    private fun at(millis: Long) = Instant.fromEpochMilliseconds(millis)

    @Test
    fun `a titled task in a list is valid`() {
        assertThat(TaskForm(title = "Write tests", listId = 1).validate()).isEmpty()
    }

    @Test
    fun `blank title and missing list are errors`() {
        val errors = TaskForm(title = "  ", listId = 0).validate()
        assertThat(errors).containsExactly(TaskFormError.BLANK_TITLE, TaskFormError.NO_LIST)
    }

    @Test
    fun `due before start is an error`() {
        val errors = TaskForm(
            title = "x",
            listId = 1,
            start = at(1000),
            due = at(500),
        ).validate()
        assertThat(errors).contains(TaskFormError.DUE_BEFORE_START)
    }

    @Test
    fun `a reminder requires a due date`() {
        val errors = TaskForm(title = "x", listId = 1, reminderMinutesBeforeDue = 10).validate()
        assertThat(errors).contains(TaskFormError.REMINDER_WITHOUT_DUE)
    }
}
