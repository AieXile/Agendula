package com.aiexile.agendula.domain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class TaskFilteringTest {

    private val dayMs = 86_400_000L
    private val todayStart = Instant.fromEpochMilliseconds(1000 * dayMs)
    private val todayEnd = Instant.fromEpochMilliseconds(1001 * dayMs)

    private fun at(millis: Long) = Instant.fromEpochMilliseconds(millis)
    private fun matches(task: Task, smart: SmartList) =
        TaskFiltering.matches(task, TaskFilter.Smart(smart), todayStart, todayEnd)

    @Test
    fun `overdue is open with a due before today`() {
        val task = testTask(due = at(1000 * dayMs - 1))
        assertThat(matches(task, SmartList.OVERDUE)).isTrue()
        assertThat(matches(task, SmartList.TODAY)).isFalse()
        assertThat(matches(task, SmartList.UPCOMING)).isFalse()
    }

    @Test
    fun `today is open with due within today`() {
        val task = testTask(due = at(1000 * dayMs + 1))
        assertThat(matches(task, SmartList.TODAY)).isTrue()
        assertThat(matches(task, SmartList.OVERDUE)).isFalse()
    }

    @Test
    fun `upcoming is open with due tomorrow or later`() {
        val task = testTask(due = at(1001 * dayMs + 1))
        assertThat(matches(task, SmartList.UPCOMING)).isTrue()
        assertThat(matches(task, SmartList.TODAY)).isFalse()
    }

    @Test
    fun `no-date is open without a due`() {
        val task = testTask(due = null)
        assertThat(matches(task, SmartList.NO_DATE)).isTrue()
        assertThat(matches(task, SmartList.ALL)).isTrue()
        assertThat(matches(task, SmartList.UPCOMING)).isFalse()
    }

    @Test
    fun `completed only matches COMPLETED, never the open lists`() {
        val task = testTask(status = TaskStatus.COMPLETED, due = at(1000 * dayMs + 1))
        assertThat(matches(task, SmartList.COMPLETED)).isTrue()
        assertThat(matches(task, SmartList.ALL)).isFalse()
        assertThat(matches(task, SmartList.TODAY)).isFalse()
    }

    @Test
    fun `OfList matches by list id`() {
        val task = testTask(listId = 7)
        assertThat(TaskFiltering.matches(task, TaskFilter.OfList(7), todayStart, todayEnd)).isTrue()
        assertThat(TaskFiltering.matches(task, TaskFilter.OfList(8), todayStart, todayEnd)).isFalse()
    }

    @Test
    fun `Tagged matches open and completed tasks carrying the tag`() {
        val filter = TaskFilter.Tagged("Work")
        assertThat(TaskFiltering.matches(testTask(tags = listOf("Home", "Work")), filter, todayStart, todayEnd)).isTrue()
        assertThat(
            TaskFiltering.matches(testTask(tags = listOf("Work"), status = TaskStatus.COMPLETED), filter, todayStart, todayEnd),
        ).isTrue()
        assertThat(TaskFiltering.matches(testTask(tags = listOf("work")), filter, todayStart, todayEnd)).isFalse()
    }

    @Test
    fun `narrowing by tags needs every one of them`() {
        val task = testTask(tags = listOf("Home", "Work"))
        assertThat(TaskFiltering.hasAllTags(task, emptySet())).isTrue()
        assertThat(TaskFiltering.hasAllTags(task, setOf("Work"))).isTrue()
        assertThat(TaskFiltering.hasAllTags(task, setOf("Work", "Home"))).isTrue()
        assertThat(TaskFiltering.hasAllTags(task, setOf("Work", "Errands"))).isFalse()
    }

    @Test
    fun `tags in use are listed once each, alphabetically ignoring case`() {
        val tasks = listOf(
            testTask(id = 1, tags = listOf("work", "Home")),
            testTask(id = 2, tags = listOf("Home", "errands")),
        )
        assertThat(tagsInUse(tasks)).containsExactly("errands", "Home", "work").inOrder()
    }
}
