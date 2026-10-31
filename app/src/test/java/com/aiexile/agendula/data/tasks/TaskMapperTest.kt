package com.aiexile.agendula.data.tasks

import com.google.common.truth.Truth.assertThat
import com.aiexile.agendula.data.tasks.TasksContract.Instances
import com.aiexile.agendula.data.tasks.TasksContract.Lists
import com.aiexile.agendula.data.tasks.TasksContract.Tasks
import com.aiexile.agendula.domain.Priority
import com.aiexile.agendula.domain.TaskStatus
import org.junit.jupiter.api.Test

class TaskMapperTest {

    @Test
    fun `maps an instance row to a Task`() {
        val reader = MapColumnReader(
            mapOf(
                Tasks.ID to 42L,
                Instances.TASK_ID to 7L,
                Tasks.LIST_ID to 3L,
                Tasks.TITLE to "Buy milk",
                Tasks.DESCRIPTION to "2%",
                Tasks.PRIORITY to 1,
                Tasks.STATUS to TasksContract.STATUS_IN_PROCESS,
                Tasks.PERCENT_COMPLETE to 40,
                Instances.INSTANCE_DUE to 1_000L,
                Instances.INSTANCE_START to 500L,
                Tasks.IS_ALLDAY to 0,
                Tasks.LIST_COLOR to 0x123456,
                Tasks.TASK_COLOR to 0xABCDEF,
                Tasks.LIST_NAME to "Groceries",
                Tasks.PARENT_ID to 7L,
                Instances.IS_RECURRING to 1,
                Instances.DISTANCE_FROM_CURRENT to 0,
            ),
        )

        val task = TaskMapper.task(reader)

        assertThat(task.taskId).isEqualTo(7L)
        assertThat(task.listId).isEqualTo(3L)
        assertThat(task.title).isEqualTo("Buy milk")
        assertThat(task.priority).isEqualTo(Priority.HIGH)
        assertThat(task.status).isEqualTo(TaskStatus.IN_PROCESS)
        assertThat(task.percentComplete).isEqualTo(40)
        assertThat(task.due?.toEpochMilliseconds()).isEqualTo(1_000L)
        assertThat(task.start?.toEpochMilliseconds()).isEqualTo(500L)
        assertThat(task.isRecurring).isTrue()
        assertThat(task.effectiveColor).isEqualTo(0xABCDEF)
        assertThat(task.isSubtask).isTrue()
    }

    @Test
    fun `an occurrence is identified by its recurrence-id anchor`() {
        fun occurrence(columns: Map<String, Any?>) =
            TaskMapper.task(MapColumnReader(columns + (Tasks.RRULE to "FREQ=DAILY")))

        // instance_original_time is the provider's own RECURRENCE-ID and wins.
        val anchored = occurrence(
            mapOf(
                Instances.TASK_ID to 7L,
                Instances.INSTANCE_ORIGINAL_TIME to 500L,
                Instances.INSTANCE_START to 900L,
            ),
        )
        assertThat(anchored.occurrenceStart?.toEpochMilliseconds()).isEqualTo(500L)
        assertThat(anchored.occurrenceKey).isEqualTo("7@500")

        // Older provider schemas omit it; the occurrence's start reconstructs it.
        val byStart = occurrence(mapOf(Instances.TASK_ID to 7L, Instances.INSTANCE_START to 900L))
        assertThat(byStart.occurrenceStart?.toEpochMilliseconds()).isEqualTo(900L)

        // A series carrying only DUE anchors on the due date instead.
        val byDue = occurrence(mapOf(Instances.TASK_ID to 7L, Instances.INSTANCE_DUE to 1_200L))
        assertThat(byDue.occurrenceStart?.toEpochMilliseconds()).isEqualTo(1_200L)
    }

    @Test
    fun `a non-recurring task has no occurrence anchor and keys by task id`() {
        val task = TaskMapper.task(
            MapColumnReader(mapOf(Tasks.ID to 4L, Instances.INSTANCE_START to 500L)),
        )
        assertThat(task.occurrenceStart).isNull()
        assertThat(task.occurrenceKey).isEqualTo("4")
    }

    @Test
    fun `recurrence is detected from rrule when is_recurring is absent`() {
        // tasks.org's bundled provider is DB 22 and has no `is_recurring` column;
        // reading it alone would report the series as one-off and send its edits
        // to the master row, re-anchoring the whole thing.
        val task = TaskMapper.task(
            MapColumnReader(mapOf(Tasks.ID to 1L, Tasks.RRULE to "FREQ=WEEKLY;BYDAY=MO")),
        )
        assertThat(task.isRecurring).isTrue()
    }

    @Test
    fun `recurrence is detected from rdate alone`() {
        val task = TaskMapper.task(
            MapColumnReader(mapOf(Tasks.ID to 1L, Tasks.RDATE to "20260720T090000Z")),
        )
        assertThat(task.isRecurring).isTrue()
    }

    @Test
    fun `a plain task is not recurring`() {
        val task = TaskMapper.task(MapColumnReader(mapOf(Tasks.ID to 1L, Tasks.TITLE to "One-off")))
        assertThat(task.isRecurring).isFalse()
    }

    @Test
    fun `falls back to instance id when task_id missing, and list color when no task color`() {
        val task = TaskMapper.task(
            MapColumnReader(mapOf(Tasks.ID to 9L, Tasks.LIST_COLOR to 0x111111)),
        )
        assertThat(task.taskId).isEqualTo(9L)
        assertThat(task.effectiveColor).isEqualTo(0x111111)
        assertThat(task.title).isEmpty()
        assertThat(task.due).isNull()
    }

    @Test
    fun `maps a task list row`() {
        val list = TaskMapper.taskList(
            MapColumnReader(
                mapOf(
                    Lists.ID to 5L,
                    Lists.NAME to "Work",
                    Lists.COLOR to 0x00FF00,
                    Lists.ACCOUNT_NAME to "me@dav",
                    Lists.ACCOUNT_TYPE to "bitfire.at.davdroid",
                    Lists.SYNC_ENABLED to 1,
                    Lists.VISIBLE to 1,
                ),
            ),
        )
        assertThat(list.id).isEqualTo(5L)
        assertThat(list.name).isEqualTo("Work")
        assertThat(list.isSynced).isTrue()
        assertThat(list.isLocal).isFalse()
    }
}
