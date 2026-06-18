package de.jeanlucmakiola.floret.domain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class TaskSectionsTest {

    private val dayMs = 86_400_000L
    private val todayStart = Instant.fromEpochMilliseconds(1000 * dayMs)
    private val todayEnd = Instant.fromEpochMilliseconds(1001 * dayMs)

    private fun at(millis: Long) = Instant.fromEpochMilliseconds(millis)
    private fun sectionOf(task: Task) = TaskSections.sectionOf(task, todayStart, todayEnd)

    @Test
    fun `buckets by due relative to today`() {
        assertThat(sectionOf(testTask(due = at(1000 * dayMs - 1)))).isEqualTo(TaskSection.OVERDUE)
        assertThat(sectionOf(testTask(due = at(1000 * dayMs + 1)))).isEqualTo(TaskSection.TODAY)
        assertThat(sectionOf(testTask(due = at(1001 * dayMs + 1)))).isEqualTo(TaskSection.UPCOMING)
        assertThat(sectionOf(testTask(due = null))).isEqualTo(TaskSection.NO_DATE)
    }

    @Test
    fun `completed always lands in completed regardless of due`() {
        val overdueButDone = testTask(status = TaskStatus.COMPLETED, due = at(1000 * dayMs - 1))
        assertThat(sectionOf(overdueButDone)).isEqualTo(TaskSection.COMPLETED)
    }

    @Test
    fun `of returns only non-empty sections in fixed order`() {
        val tasks = listOf(
            testTask(id = 1, due = at(1001 * dayMs + 1)),   // upcoming
            testTask(id = 2, due = at(1000 * dayMs - 1)),   // overdue
            testTask(id = 3, status = TaskStatus.COMPLETED), // completed
        )
        val sections = TaskSections.of(tasks, todayStart, todayEnd)
        assertThat(sections.map { it.section })
            .containsExactly(TaskSection.OVERDUE, TaskSection.UPCOMING, TaskSection.COMPLETED)
            .inOrder()
    }

    @Test
    fun `empty input yields no sections`() {
        assertThat(TaskSections.of(emptyList(), todayStart, todayEnd)).isEmpty()
    }
}
