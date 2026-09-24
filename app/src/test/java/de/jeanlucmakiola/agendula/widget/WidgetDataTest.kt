package de.jeanlucmakiola.agendula.widget

import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.agendula.domain.SmartList
import de.jeanlucmakiola.agendula.domain.TaskFilter
import de.jeanlucmakiola.agendula.domain.TaskSorting
import de.jeanlucmakiola.agendula.domain.TaskStatus
import de.jeanlucmakiola.agendula.domain.allDayInstantOf
import de.jeanlucmakiola.agendula.domain.testTask
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.time.Instant

class WidgetDataTest {

    private val newYork = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 7, 20)

    private fun at(local: String, zone: ZoneId = newYork): Instant =
        Instant.fromEpochMilliseconds(LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli())

    private val now = at("2026-07-20T12:00:00")

    // --- filter storage ------------------------------------------------------

    @Test
    fun `every offered filter round-trips through storage`() {
        val filters = WIDGET_SMART_LISTS.map { TaskFilter.Smart(it) } + TaskFilter.OfList(42)
        filters.forEach { assertThat(parseWidgetFilter(it.widgetStorageValue())).isEqualTo(it) }
    }

    @Test
    fun `a fresh widget shows Today`() {
        assertThat(parseWidgetFilter(null)).isEqualTo(TaskFilter.Smart(SmartList.TODAY))
    }

    @Test
    fun `garbage and smart lists the widget does not offer fall back to Today`() {
        listOf("", "list:", "list:-3", "list:abc", "smart:COMPLETED", "smart:nope", "whatever").forEach {
            assertThat(parseWidgetFilter(it)).isEqualTo(DEFAULT_WIDGET_FILTER)
        }
    }

    // --- due classification --------------------------------------------------

    @Test
    fun `an all-day due is read as its UTC date, not the local one`() {
        // UTC midnight of the 21st is still the 20th in New York; read as timed it would say today.
        val tomorrowAllDay = allDayInstantOf(today.plusDays(1))
        assertThat(dueKind(tomorrowAllDay, allDay = true, today = today, zone = newYork)).isEqualTo(DueKind.TOMORROW)
        assertThat(isOverdue(allDayInstantOf(today), allDay = true, now = now, today = today, zone = newYork)).isFalse()
    }

    @Test
    fun `a timed task earlier today is today, and overdue`() {
        val morning = at("2026-07-20T09:00:00")
        assertThat(dueKind(morning, allDay = false, today = today, zone = newYork)).isEqualTo(DueKind.TODAY)
        assertThat(isOverdue(morning, allDay = false, now = now, today = today, zone = newYork)).isTrue()
    }

    @Test
    fun `past and later days classify by date`() {
        assertThat(dueKind(at("2026-07-18T09:00:00"), false, today, newYork)).isEqualTo(DueKind.PAST)
        assertThat(dueKind(at("2026-07-25T09:00:00"), false, today, newYork)).isEqualTo(DueKind.LATER)
        assertThat(isOverdue(allDayInstantOf(today.minusDays(1)), true, now, today, newYork)).isTrue()
    }

    // --- rows ------------------------------------------------------------------

    @Test
    fun `closed tasks are dropped and the rest sorted`() {
        val rows = widgetRows(
            listOf(
                testTask(id = 1, title = "later", due = at("2026-07-22T09:00:00")),
                testTask(id = 2, title = "done", status = TaskStatus.COMPLETED),
                testTask(id = 3, title = "soon", due = at("2026-07-20T15:00:00")),
            ),
            TaskSorting.DEFAULT,
            now,
            newYork,
        )
        assertThat(rows.map { it.title }).containsExactly("soon", "later").inOrder()
    }

    @Test
    fun `a subtask is left to its parent when both are shown, kept when orphaned`() {
        val parent = testTask(id = 1, title = "parent")
        val child = testTask(id = 2, title = "child").copy(parentId = 1)
        val orphan = testTask(id = 3, title = "orphan").copy(parentId = 99)
        val rows = widgetRows(listOf(parent, child, orphan), TaskSorting.DEFAULT, now, newYork)
        assertThat(rows.map { it.title }).containsExactly("parent", "orphan")
    }

    @Test
    fun `rows are capped`() {
        val many = (1L..(MAX_WIDGET_ROWS + 10L)).map { testTask(id = it, title = "t$it") }
        assertThat(widgetRows(many, TaskSorting.DEFAULT, now, newYork)).hasSize(MAX_WIDGET_ROWS)
    }
}
