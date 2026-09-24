package de.jeanlucmakiola.agendula.domain

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
        val errors = TaskForm(title = "x", listId = 1, reminders = listOf(10)).validate()
        assertThat(errors).contains(TaskFormError.REMINDER_WITHOUT_DUE)
    }

    @Test
    fun `progress and parent auto-reveal only when they carry a value`() {
        assertThat(TaskForm(title = "x", listId = 1).populatedFields()).isEmpty()
        assertThat(TaskForm(title = "x", listId = 1, percentComplete = 0, parentId = 0).populatedFields())
            .isEmpty()
        assertThat(TaskForm(title = "x", listId = 1, percentComplete = 30, parentId = 9).populatedFields())
            .containsExactly(TaskFormField.Progress, TaskFormField.Parent)
    }
}
