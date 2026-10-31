package com.aiexile.agendula.data.tasks

import com.google.common.truth.Truth.assertThat
import com.aiexile.agendula.data.tasks.TasksContract.Lists
import com.aiexile.agendula.data.tasks.TasksContract.Tasks
import com.aiexile.agendula.domain.Priority
import com.aiexile.agendula.domain.TaskForm
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
    fun `percent complete is written and clamped to 0-100`() {
        val values = TaskWriteMapper.taskValues(
            TaskForm(title = "x", listId = 1L, percentComplete = 140),
            tzId = "UTC",
        )
        assertThat(values[Tasks.PERCENT_COMPLETE]).isEqualTo(100)

        val unset = TaskWriteMapper.taskValues(TaskForm(title = "x", listId = 1L), tzId = "UTC")
        assertThat(unset[Tasks.PERCENT_COMPLETE]).isNull()
    }

    @Test
    fun `progress keeps completion in sync both ways`() {
        // 100% completes the task (timestamp left to the provider / existing value).
        val done = TaskWriteMapper.taskValues(
            TaskForm(title = "x", listId = 1L, percentComplete = 100),
            tzId = "UTC",
        )
        assertThat(done[Tasks.STATUS]).isEqualTo(TasksContract.STATUS_COMPLETED)
        assertThat(done.containsKey(Tasks.COMPLETED)).isFalse()

        // Below 100% reopens it and clears the completion timestamp.
        val reopened = TaskWriteMapper.taskValues(
            TaskForm(title = "x", listId = 1L, percentComplete = 75),
            tzId = "UTC",
        )
        assertThat(reopened[Tasks.STATUS]).isEqualTo(TasksContract.STATUS_IN_PROCESS)
        assertThat(reopened[Tasks.COMPLETED]).isNull()

        // No percent in the form ⇒ status is left to the complete toggle.
        val untouched = TaskWriteMapper.taskValues(TaskForm(title = "x", listId = 1L), tzId = "UTC")
        assertThat(untouched.containsKey(Tasks.STATUS)).isFalse()
    }

    @Test
    fun `parent id is written so a task can be filed under another`() {
        val values = TaskWriteMapper.taskValues(
            TaskForm(title = "x", listId = 1L, parentId = 7L),
            tzId = "UTC",
        )
        assertThat(values[Tasks.PARENT_ID]).isEqualTo(7L)
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
    fun `all-day timestamps are pinned to UTC midnight`() {
        // 2026-07-20T22:00Z — i.e. local midnight on the 21st in Berlin (UTC+2).
        // The provider resolves all-day dates against UTC, so storing this as-is
        // would land the task on the 20th for anyone reading it back.
        val berlinMidnight = Instant.fromEpochMilliseconds(1_784_412_000_000L)
        val values = TaskWriteMapper.taskValues(
            TaskForm(title = "Holiday", listId = 1L, start = berlinMidnight, due = berlinMidnight, isAllDay = true),
            tzId = "Europe/Berlin",
        )

        val dayMs = 24L * 60 * 60 * 1000
        assertThat(values[Tasks.DUE] as Long % dayMs).isEqualTo(0L)
        assertThat(values[Tasks.DTSTART] as Long % dayMs).isEqualTo(0L)
    }

    @Test
    fun `timed timestamps are written untouched`() {
        val at = Instant.fromEpochMilliseconds(1_784_412_345_678L)
        val values = TaskWriteMapper.taskValues(
            TaskForm(title = "Standup", listId = 1L, start = at, due = at),
            tzId = "Europe/Berlin",
        )
        assertThat(values[Tasks.DTSTART]).isEqualTo(1_784_412_345_678L)
        assertThat(values[Tasks.DUE]).isEqualTo(1_784_412_345_678L)
    }

    @Test
    fun `duration is always cleared so it cannot collide with due`() {
        // The provider validates the *merged* row and throws "Only one of DUE or
        // DURATION must be supplied" if the stored row still carries a duration.
        val values = TaskWriteMapper.taskValues(
            TaskForm(title = "x", listId = 1L, due = Instant.fromEpochMilliseconds(5_000L)),
            tzId = "UTC",
        )
        assertThat(values.containsKey(Tasks.DURATION)).isTrue()
        assertThat(values[Tasks.DURATION]).isNull()
    }

    @Test
    fun `instance values drop list and parent, which an override cannot express`() {
        val form = TaskForm(title = "x", listId = 4L, parentId = 7L, due = Instant.fromEpochMilliseconds(1_000L))
        val values = TaskWriteMapper.instanceValues(form, tzId = "UTC")

        assertThat(values.containsKey(Tasks.LIST_ID)).isFalse()
        assertThat(values.containsKey(Tasks.PARENT_ID)).isFalse()
        // …but still carries the edit itself.
        assertThat(values[Tasks.TITLE]).isEqualTo("x")
        assertThat(values[Tasks.DUE]).isEqualTo(1_000L)
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
    fun `category row carries only the name, so the provider resolves the category itself`() {
        val values = TaskWriteMapper.categoryValues(taskId = 12L, name = "Work")

        assertThat(values).containsExactly(
            TasksContract.Properties.TASK_ID, 12L,
            TasksContract.Properties.MIMETYPE, "vnd.android.cursor.item/category",
            TasksContract.Category.NAME, "Work",
        )
    }

    @Test
    fun `alarm carries every column the provider's validator demands`() {
        val values = TaskWriteMapper.alarmValues(taskId = 12L, minutesBeforeDue = 30)

        assertThat(values[TasksContract.Properties.TASK_ID]).isEqualTo(12L)
        assertThat(values[TasksContract.Properties.MIMETYPE])
            .isEqualTo("vnd.android.cursor.item/alarm")
        assertThat(values[TasksContract.Alarm.MINUTES_BEFORE]).isEqualTo(30)
        // REFERENCE must be present and non-negative, ALARM_TYPE present and
        // non-zero (0 is excluded from the provider's has_alarms count).
        assertThat(values[TasksContract.Alarm.REFERENCE]).isEqualTo(TasksContract.Alarm.REFERENCE_DUE)
        assertThat(values[TasksContract.Alarm.ALARM_TYPE]).isEqualTo(TasksContract.Alarm.TYPE_MESSAGE)
        // property_id must be absent or the insert is rejected.
        assertThat(values.containsKey(TasksContract.Properties.PROPERTY_ID)).isFalse()
    }

    @Test
    fun `local list uses the LOCAL account`() {
        val values = TaskWriteMapper.localListValues("Inbox", 0x123)
        assertThat(values[Lists.NAME]).isEqualTo("Inbox")
        assertThat(values[Lists.ACCOUNT_TYPE]).isEqualTo(TasksContract.LOCAL_ACCOUNT_TYPE)
        assertThat(values[Lists.ACCOUNT_NAME]).isEqualTo(TasksContract.LOCAL_ACCOUNT_NAME)
    }
}
