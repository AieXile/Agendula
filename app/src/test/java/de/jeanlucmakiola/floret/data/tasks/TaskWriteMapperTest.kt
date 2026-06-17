package de.jeanlucmakiola.floret.data.tasks

import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.floret.data.tasks.TasksContract.Lists
import de.jeanlucmakiola.floret.data.tasks.TasksContract.Tasks
import de.jeanlucmakiola.floret.domain.Priority
import de.jeanlucmakiola.floret.domain.TaskForm
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class TaskWriteMapperTest {

    @Test
    fun `timed task writes due, priority and an explicit timezone`() {
        val form = TaskForm(
            title = "  Pay rent ",
            listId = 4L,
            due = Instant.fromEpochMilliseconds(2_000L),
            priority = Priority.HIGH,
        )
        val values = TaskWriteMapper.taskValues(form, tzId = "Europe/Berlin")

        assertThat(values[Tasks.TITLE]).isEqualTo("Pay rent")
        assertThat(values[Tasks.LIST_ID]).isEqualTo(4L)
        assertThat(values[Tasks.DUE]).isEqualTo(2_000L)
        assertThat(values[Tasks.PRIORITY]).isEqualTo(1)
        assertThat(values[Tasks.IS_ALLDAY]).isEqualTo(0)
        assertThat(values[Tasks.TZ]).isEqualTo("Europe/Berlin")
    }

    @Test
    fun `all-day task clears the timezone`() {
        val form = TaskForm(
            title = "Holiday",
            listId = 1L,
            due = Instant.fromEpochMilliseconds(0L),
            isAllDay = true,
        )
        val values = TaskWriteMapper.taskValues(form, tzId = "Europe/Berlin")
        assertThat(values[Tasks.IS_ALLDAY]).isEqualTo(1)
        assertThat(values[Tasks.TZ]).isNull()
    }

    @Test
    fun `completion sets status, percent and timestamp, un-completion clears them`() {
        val done = TaskWriteMapper.completionValues(completed = true, nowMillis = 999L)
        assertThat(done[Tasks.STATUS]).isEqualTo(TasksContract.STATUS_COMPLETED)
        assertThat(done[Tasks.PERCENT_COMPLETE]).isEqualTo(100)
        assertThat(done[Tasks.COMPLETED]).isEqualTo(999L)

        val undone = TaskWriteMapper.completionValues(completed = false, nowMillis = 999L)
        assertThat(undone[Tasks.STATUS]).isEqualTo(TasksContract.STATUS_NEEDS_ACTION)
        assertThat(undone[Tasks.COMPLETED]).isNull()
    }

    @Test
    fun `local list uses the LOCAL account`() {
        val values = TaskWriteMapper.localListValues("Inbox", 0x123)
        assertThat(values[Lists.NAME]).isEqualTo("Inbox")
        assertThat(values[Lists.ACCOUNT_TYPE]).isEqualTo(TasksContract.LOCAL_ACCOUNT_TYPE)
        assertThat(values[Lists.ACCOUNT_NAME]).isEqualTo(TasksContract.LOCAL_ACCOUNT_NAME)
    }
}
