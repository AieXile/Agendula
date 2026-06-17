package de.jeanlucmakiola.floret.data.tasks

import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.floret.data.tasks.TasksContract.Instances
import de.jeanlucmakiola.floret.data.tasks.TasksContract.Lists
import de.jeanlucmakiola.floret.data.tasks.TasksContract.Tasks
import de.jeanlucmakiola.floret.domain.Priority
import de.jeanlucmakiola.floret.domain.TaskStatus
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

        assertThat(task.id).isEqualTo(42L)
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
