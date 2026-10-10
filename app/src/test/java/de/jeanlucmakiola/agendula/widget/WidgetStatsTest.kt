package de.jeanlucmakiola.agendula.widget

import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.agendula.domain.TaskList
import de.jeanlucmakiola.agendula.domain.TaskSortOrder
import de.jeanlucmakiola.agendula.domain.TaskStatus
import de.jeanlucmakiola.agendula.domain.allDayInstantOf
import de.jeanlucmakiola.agendula.domain.comparator
import de.jeanlucmakiola.agendula.domain.testTask
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.time.Instant

class WidgetStatsTest {

    private val newYork = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 7, 20)
    private val comparator = TaskSortOrder.DUE.comparator()

    private fun at(local: String, zone: ZoneId = newYork): Instant =
        Instant.fromEpochMilliseconds(LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli())

    private val now = at("2026-07-20T12:00:00")

    // --- todayProgress ---------------------------------------------------

    @Test
    fun `a task completed today counts as done, one completed yesterday does not`() {
        val completed = listOf(
            testTask(id = 1, status = TaskStatus.COMPLETED, completedAt = at("2026-07-20T09:00:00")),
            testTask(id = 2, status = TaskStatus.COMPLETED, completedAt = at("2026-07-19T23:00:00")),
        )
        val (done, _, _) = todayProgress(emptyList(), completed, comparator, now, today, newYork)
        assertThat(done).isEqualTo(1)
    }

    @Test
    fun `an all-day task due today and an overdue timed task both count as remaining`() {
        val open = listOf(
            testTask(id = 1, due = allDayInstantOf(today), isAllDay = true),
            testTask(id = 2, due = at("2026-07-20T08:00:00")), // already past `now` (12:00)
            testTask(id = 3, due = at("2026-07-21T08:00:00")), // tomorrow, not remaining
        )
        val (_, remaining, _) = todayProgress(open, emptyList(), comparator, now, today, newYork)
        assertThat(remaining).isEqualTo(2)
    }

    @Test
    fun `an earlier-due remaining task sorts ahead of a later one`() {
        val open = listOf(
            testTask(id = 1, due = at("2026-07-20T11:00:00")), // overdue, closer to now
            testTask(id = 2, due = at("2026-07-20T07:00:00")), // more overdue, earliest
        )
        val (_, _, rows) = todayProgress(open, emptyList(), comparator, now, today, newYork)
        assertThat(rows.map { it.taskId }).containsExactly(2L, 1L).inOrder()
    }

    // --- upcoming ----------------------------------------------------------

    @Test
    fun `upcoming sorts by due, overdue first, and skips undated ones`() {
        val open = listOf(
            testTask(id = 1, due = null),
            testTask(id = 2, due = at("2026-07-21T08:00:00")),
            testTask(id = 3, due = at("2026-07-20T08:00:00")), // overdue, earliest
        )
        assertThat(upcoming(open, now, today, newYork).map { it.taskId }).containsExactly(3L, 2L).inOrder()
    }

    @Test
    fun `upcoming is empty when nothing is dated`() {
        assertThat(upcoming(listOf(testTask(due = null)), now, today, newYork)).isEmpty()
    }

    // --- weekDays ------------------------------------------------------------

    @Test
    fun `today's bucket folds in overdue tasks from earlier days`() {
        val open = listOf(
            testTask(id = 1, due = at("2026-07-18T08:00:00")), // two days overdue
            testTask(id = 2, due = at("2026-07-20T08:00:00")), // due today, already past now
        )
        val week = weekDays(open, comparator, now, today, newYork)
        assertThat(week.first().date).isEqualTo(today)
        assertThat(week.first().count).isEqualTo(2)
        assertThat(week.first().overdueCount).isEqualTo(2)
    }

    @Test
    fun `a future day only counts tasks due that exact day`() {
        val open = listOf(
            testTask(id = 1, due = at("2026-07-22T08:00:00")),
            testTask(id = 2, due = at("2026-07-23T08:00:00")),
        )
        val week = weekDays(open, comparator, now, today, newYork)
        assertThat(week[2].count).isEqualTo(1) // today + 2 = 07-22
        assertThat(week[2].overdueCount).isEqualTo(0)
        assertThat(week[3].count).isEqualTo(1) // today + 3 = 07-23
    }

    @Test
    fun `a day at the far edge of daylight saving still buckets by local date`() {
        // Spans the US spring-forward boundary; only exercised to confirm
        // calendarDate (not a raw day-of-year add) drives the bucketing.
        val dstToday = LocalDate.of(2026, 3, 7)
        val dstNow = at("2026-03-07T12:00:00")
        val open = listOf(testTask(id = 1, due = at("2026-03-08T08:00:00")))
        val week = weekDays(open, comparator, dstNow, dstToday, newYork)
        assertThat(week[1].date).isEqualTo(LocalDate.of(2026, 3, 8))
        assertThat(week[1].count).isEqualTo(1)
    }

    // --- listTiles -------------------------------------------------------

    @Test
    fun `listTiles counts open tasks per visible list and skips a subtask whose parent is also open`() {
        val lists = listOf(
            TaskList(1, "Home", 0, "a", "t", false, isVisible = true, owner = null),
            TaskList(2, "Hidden", 0, "a", "t", false, isVisible = false, owner = null),
        )
        val open = listOf(
            testTask(id = 1, listId = 1),
            testTask(id = 2, listId = 1, parentId = 1), // subtask of an open parent
            testTask(id = 3, listId = 2),
        )
        val tiles = listTiles(open, lists)
        assertThat(tiles).hasSize(1)
        assertThat(tiles.single().id).isEqualTo(1L)
        assertThat(tiles.single().openCount).isEqualTo(1)
    }
}
