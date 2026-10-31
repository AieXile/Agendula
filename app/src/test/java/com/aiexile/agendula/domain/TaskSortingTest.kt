package com.aiexile.agendula.domain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class TaskSortingTest {

    private fun at(millis: Long) = Instant.fromEpochMilliseconds(millis)

    @Test
    fun `open before completed, due-soonest first, dateless last`() {
        val completed = testTask(id = 1, title = "done", status = TaskStatus.COMPLETED, due = at(10))
        val dueLater = testTask(id = 2, title = "later", due = at(200))
        val dueSooner = testTask(id = 3, title = "sooner", due = at(100))
        val noDate = testTask(id = 4, title = "someday", due = null)

        val sorted = listOf(completed, noDate, dueLater, dueSooner).sortedWith(TaskSorting.DEFAULT)

        assertThat(sorted.map { it.taskId }).containsExactly(3L, 2L, 4L, 1L).inOrder()
    }

    @Test
    fun `higher priority wins when due dates tie`() {
        val low = testTask(id = 1, title = "low", priority = Priority.LOW, due = at(100))
        val high = testTask(id = 2, title = "high", priority = Priority.HIGH, due = at(100))

        val sorted = listOf(low, high).sortedWith(TaskSorting.DEFAULT)

        assertThat(sorted.map { it.taskId }).containsExactly(2L, 1L).inOrder()
    }
}
