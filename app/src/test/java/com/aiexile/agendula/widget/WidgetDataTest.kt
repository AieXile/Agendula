package com.aiexile.agendula.widget

import com.google.common.truth.Truth.assertThat
import com.aiexile.agendula.data.prefs.ThemeMode
import com.aiexile.agendula.domain.SmartList
import com.aiexile.agendula.domain.TaskFilter
import com.aiexile.agendula.domain.TaskSorting
import com.aiexile.agendula.domain.TaskStatus
import com.aiexile.agendula.domain.allDayInstantOf
import com.aiexile.agendula.domain.testTask
import de.jeanlucmakiola.floret.glance.WidgetBackground
import de.jeanlucmakiola.floret.glance.WidgetThemeMode
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
        val filters = WIDGET_SMART_LISTS.map { TaskFilter.Smart(it) } +
            TaskFilter.OfList(42) +
            TaskFilter.OfLists(setOf(1L, 2L, 3L)) +
            TaskFilter.Saved(7)
        filters.forEach { assertThat(parseWidgetFilter(it.widgetStorageValue())).isEqualTo(it) }
    }

    @Test
    fun `a fresh widget shows Today`() {
        assertThat(parseWidgetFilter(null)).isEqualTo(TaskFilter.Smart(SmartList.TODAY))
    }

    @Test
    fun `garbage and smart lists the widget does not offer fall back to Today`() {
        listOf(
            "", "list:", "list:-3", "list:abc", "lists:", "lists:abc", "lists:-1,-2",
            "smart:COMPLETED", "smart:nope", "filter:", "filter:0", "filter:x", "whatever",
        ).forEach {
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
            groupByList = false,
            listNames = emptyMap(),
        )
        assertThat(rows.map { it.title }).containsExactly("soon", "later").inOrder()
    }

    @Test
    fun `a subtask is left to its parent when both are shown, kept when orphaned`() {
        val parent = testTask(id = 1, title = "parent")
        val child = testTask(id = 2, title = "child").copy(parentId = 1)
        val orphan = testTask(id = 3, title = "orphan").copy(parentId = 99)
        val rows = widgetRows(
            listOf(parent, child, orphan),
            TaskSorting.DEFAULT,
            now,
            newYork,
            groupByList = false,
            listNames = emptyMap(),
        )
        assertThat(rows.map { it.title }).containsExactly("parent", "orphan")
    }

    @Test
    fun `rows are capped`() {
        val many = (1L..(MAX_WIDGET_ROWS + 10L)).map { testTask(id = it, title = "t$it") }
        assertThat(
            widgetRows(many, TaskSorting.DEFAULT, now, newYork, groupByList = false, listNames = emptyMap()),
        ).hasSize(MAX_WIDGET_ROWS)
    }

    @Test
    fun `grouping sorts by list name, keeping each list's own order`() {
        val tasks = listOf(
            testTask(id = 1, listId = 2, title = "b-later", due = at("2026-07-22T09:00:00")),
            testTask(id = 2, listId = 1, title = "a-soon", due = at("2026-07-20T15:00:00")),
            testTask(id = 3, listId = 1, title = "a-later", due = at("2026-07-22T09:00:00")),
        )
        val rows = widgetRows(
            tasks,
            TaskSorting.DEFAULT,
            now,
            newYork,
            groupByList = true,
            listNames = mapOf(1L to "Alpha", 2L to "Beta"),
        )
        assertThat(rows.map { it.title }).containsExactly("a-soon", "a-later", "b-later").inOrder()
    }

    // --- sections ----------------------------------------------------------

    private fun row(id: Long, listId: Long = 1, dueKind: DueKind? = null, overdue: Boolean = false) = WidgetTaskRow(
        taskId = id,
        listId = listId,
        occurrenceStart = null,
        title = "t$id",
        due = null,
        isAllDay = false,
        dueKind = dueKind,
        isOverdue = overdue,
        color = 0,
    )

    @Test
    fun `due buckets appear in a fixed order and skip empty ones`() {
        val rows = listOf(
            row(1, dueKind = DueKind.LATER),
            row(2, dueKind = DueKind.PAST, overdue = true),
            row(3, dueKind = DueKind.TODAY),
        )
        val sections = widgetSections(rows, groupByList = false, listNames = emptyMap()) { it?.name ?: "none" }
        assertThat(sections.filterIsInstance<WidgetSection.Header>().map { it.name })
            .containsExactly("PAST", "TODAY", "LATER").inOrder()
    }

    @Test
    fun `a bucket's rows keep their own order under its one header`() {
        val rows = listOf(row(1, dueKind = DueKind.TODAY), row(2, dueKind = DueKind.TODAY), row(3, dueKind = DueKind.TODAY))
        val sections = widgetSections(rows, groupByList = false, listNames = emptyMap()) { "x" }
        assertThat(sections.filterIsInstance<WidgetSection.Header>()).hasSize(1)
        assertThat(sections.filterIsInstance<WidgetSection.TaskItem>().map { it.row.taskId }).containsExactly(1L, 2L, 3L).inOrder()
    }

    @Test
    fun `grouping by list keeps the widgetRows order and headers each run once`() {
        val rows = listOf(row(1, listId = 1), row(2, listId = 1), row(3, listId = 2))
        val sections = widgetSections(rows, groupByList = true, listNames = mapOf(1L to "Alpha", 2L to "Beta")) { "x" }
        assertThat(sections.filterIsInstance<WidgetSection.Header>().map { it.name }).containsExactly("Alpha", "Beta").inOrder()
        assertThat(sections.filterIsInstance<WidgetSection.TaskItem>().map { it.row.taskId }).containsExactly(1L, 2L, 3L).inOrder()
    }

    // --- per-widget background / theme --------------------------------------

    @Test
    fun `an unset background is Solid, and round-trips`() {
        assertThat(parseWidgetBackground(null)).isEqualTo(WidgetBackground.SOLID)
        WidgetBackground.entries.forEach { assertThat(parseWidgetBackground(it.name)).isEqualTo(it) }
        assertThat(parseWidgetBackground("garbage")).isEqualTo(WidgetBackground.SOLID)
    }

    @Test
    fun `an unset theme override follows the app, Light and Dark pin regardless`() {
        assertThat(parseWidgetThemeOverride(null)).isNull()
        assertThat(parseWidgetThemeOverride("garbage")).isNull()
        assertThat(parseWidgetThemeOverride("LIGHT")).isEqualTo(WidgetThemeMode.LIGHT)
        assertThat(parseWidgetThemeOverride("DARK")).isEqualTo(WidgetThemeMode.DARK)
    }

    private fun readyWithTheme(appTheme: ThemeMode) = TaskWidgetData.Ready(
        filter = DEFAULT_WIDGET_FILTER,
        listNames = emptyMap(),
        rows = emptyList(),
        groupByList = false,
        dynamicColor = true,
        themeMode = appTheme,
        use24Hour = true,
    )

    @Test
    fun `no override resolves to whatever the app's own theme means`() {
        assertThat(readyWithTheme(ThemeMode.DARK).effectiveThemeMode(null)).isEqualTo(WidgetThemeMode.DARK)
        assertThat(readyWithTheme(ThemeMode.SYSTEM).effectiveThemeMode(null)).isEqualTo(WidgetThemeMode.SYSTEM)
    }

    @Test
    fun `an override wins over the app's own theme`() {
        assertThat(readyWithTheme(ThemeMode.DARK).effectiveThemeMode(WidgetThemeMode.LIGHT)).isEqualTo(WidgetThemeMode.LIGHT)
    }

    @Test
    fun `an unset row colour is Neutral, and round-trips`() {
        assertThat(parseWidgetRowColor(null)).isEqualTo(WidgetRowColor.NEUTRAL)
        WidgetRowColor.entries.forEach { assertThat(parseWidgetRowColor(it.name)).isEqualTo(it) }
        assertThat(parseWidgetRowColor("garbage")).isEqualTo(WidgetRowColor.NEUTRAL)
    }

    @Test
    fun `overdue count only counts overdue rows`() {
        val ready = readyWithTheme(ThemeMode.SYSTEM).copy(
            rows = listOf(row(1, overdue = true), row(2, overdue = false), row(3, overdue = true)),
        )
        assertThat(ready.overdueCount).isEqualTo(2)
    }
}
