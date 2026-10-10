package de.jeanlucmakiola.agendula.domain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Instant

class SavedFilterTest {

    private val dayMs = 86_400_000L
    private val todayStart = Instant.fromEpochMilliseconds(1000 * dayMs)
    private val todayEnd = Instant.fromEpochMilliseconds(1001 * dayMs)
    private val overdue = Instant.fromEpochMilliseconds(999 * dayMs)
    private val today = Instant.fromEpochMilliseconds(1000 * dayMs + 1)
    private val upcoming = Instant.fromEpochMilliseconds(1002 * dayMs)

    private fun SavedFilter.matches(task: Task) = matches(task, todayStart, todayEnd)

    private val empty = SavedFilter(id = 1, name = "f")

    @Test
    fun `an empty filter is every open task`() {
        assertThat(empty.matches(testTask(due = null))).isTrue()
        assertThat(empty.matches(testTask(due = overdue))).isTrue()
        assertThat(empty.matches(testTask(status = TaskStatus.COMPLETED))).isFalse()
        assertThat(empty.matches(testTask(status = TaskStatus.CANCELLED))).isFalse()
    }

    @Test
    fun `today and overdue together`() {
        val filter = empty.copy(due = setOf(DueWindow.TODAY, DueWindow.OVERDUE))
        assertThat(filter.matches(testTask(due = overdue))).isTrue()
        assertThat(filter.matches(testTask(due = today))).isTrue()
        assertThat(filter.matches(testTask(due = upcoming))).isFalse()
        assertThat(filter.matches(testTask(due = null))).isFalse()
    }

    @Test
    fun `lists narrow by membership`() {
        val filter = empty.copy(listIds = setOf(2L, 3L))
        assertThat(filter.matches(testTask(listId = 2))).isTrue()
        assertThat(filter.matches(testTask(listId = 1))).isFalse()
    }

    @Test
    fun `lists from another store match nothing here`() {
        val filter = empty.copy(listsElsewhere = true)
        assertThat(filter.matches(testTask(listId = 1))).isFalse()
        assertThat(filter.copy(listIds = setOf(1L)).matches(testTask(listId = 1))).isTrue()
    }

    @Test
    fun `priority is a floor`() {
        val filter = empty.copy(minPriority = Priority.MEDIUM)
        assertThat(filter.matches(testTask(priority = Priority.HIGH))).isTrue()
        assertThat(filter.matches(testTask(priority = Priority.MEDIUM))).isTrue()
        assertThat(filter.matches(testTask(priority = Priority.LOW))).isFalse()
        assertThat(filter.matches(testTask(priority = Priority.NONE))).isFalse()
    }

    @Test
    fun `tags match any or all`() {
        val any = empty.copy(tags = setOf("work", "home"))
        val all = any.copy(tagMatch = TagMatch.ALL)
        val one = testTask(tags = listOf("work"))
        val both = testTask(tags = listOf("home", "work", "x"))
        assertThat(any.matches(one)).isTrue()
        assertThat(any.matches(testTask(tags = listOf("x")))).isFalse()
        assertThat(all.matches(one)).isFalse()
        assertThat(all.matches(both)).isTrue()
    }

    @Test
    fun `criteria combine with and`() {
        val filter = empty.copy(due = setOf(DueWindow.TODAY), listIds = setOf(1L), tags = setOf("work"))
        assertThat(filter.matches(testTask(listId = 1, due = today, tags = listOf("work")))).isTrue()
        assertThat(filter.matches(testTask(listId = 2, due = today, tags = listOf("work")))).isFalse()
        assertThat(filter.matches(testTask(listId = 1, due = today))).isFalse()
    }

    @Test
    fun `an all-day due is read as its date west of utc`() {
        val zone = ZoneId.of("America/New_York")
        val date = LocalDate.of(2026, 10, 10)
        val start = Instant.fromEpochMilliseconds(date.atStartOfDay(zone).toInstant().toEpochMilli())
        val end = Instant.fromEpochMilliseconds(date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        fun window(d: LocalDate) = dueWindowOf(testTask(due = allDayInstantOf(d), isAllDay = true), start, end, zone)
        assertThat(window(date)).isEqualTo(DueWindow.TODAY)
        assertThat(window(date.plusDays(1))).isEqualTo(DueWindow.UPCOMING)
        assertThat(window(date.minusDays(1))).isEqualTo(DueWindow.OVERDUE)
    }

    @Test
    fun `a new task preset lands in its filter`() {
        val day = LocalDate.of(2026, 10, 10)
        val filter = empty.copy(
            due = setOf(DueWindow.UPCOMING),
            tags = setOf("work", "home"),
            tagMatch = TagMatch.ALL,
            minPriority = Priority.HIGH,
        )
        val preset = filter.newTaskPreset(day)
        assertThat(preset.dueDate).isEqualTo(day.plusDays(1))
        assertThat(preset.tags).containsExactly("home", "work")
        assertThat(preset.priority).isEqualTo(Priority.HIGH)
        assertThat(empty.copy(due = setOf(DueWindow.TODAY, DueWindow.UPCOMING)).newTaskPreset(day).dueDate).isEqualTo(day)
        assertThat(empty.copy(due = setOf(DueWindow.TODAY, DueWindow.NO_DATE)).newTaskPreset(day).dueDate).isNull()
        assertThat(empty.copy(tags = setOf("b", "a")).newTaskPreset(day).tags).containsExactly("a")
    }

    @Test
    fun `lists since deleted drop out`() {
        val filter = empty.copy(listIds = setOf(1L, 5L))
        assertThat(filter.withListsIn(setOf(1L, 2L)).listIds).containsExactly(1L)
        assertThat(filter.withListsIn(setOf(2L)).anyList).isTrue()
        assertThat(filter.copy(listsElsewhere = true).withListsIn(setOf(2L)).anyList).isFalse()
    }
}
