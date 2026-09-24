package de.jeanlucmakiola.agendula.data.tasks.room

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RoomTaskMapperTest {

    @Test
    fun `a task in a read-only share says so`() {
        val row = TaskRow(
            task = TaskEntity(id = 1, listId = 2, uid = "u"),
            listName = "Shared",
            listColor = 0,
            accountDisplayName = "me@host",
            listReadOnly = true,
        )

        assertThat(RoomTaskMapper.task(row).isReadOnly).isTrue()
    }

    @Test
    fun `a read-only list does not accept writes`() {
        val list = TaskListRow(
            list = TaskListEntity(id = 2, name = "Shared", color = 0, accountId = 1, isReadOnly = true),
            accountDisplayName = "me@host",
        )

        assertThat(RoomTaskMapper.taskList(list).acceptsWrites).isFalse()
    }
}
