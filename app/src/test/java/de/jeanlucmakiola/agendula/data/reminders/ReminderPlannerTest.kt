package de.jeanlucmakiola.agendula.data.reminders

import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.agendula.data.prefs.Settings
import de.jeanlucmakiola.agendula.data.tasks.TaskReminder
import de.jeanlucmakiola.agendula.domain.allDayInstantOf
import de.jeanlucmakiola.agendula.domain.testTask
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.time.Instant

class ReminderPlannerTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private val newYork = ZoneId.of("America/New_York")
    private val date = LocalDate.of(2026, 7, 20)

    private fun millis(at: LocalDateTime, zone: ZoneId) = at.atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `an all-day reminder fires at the configured local time, not UTC midnight`() {
        val trigger = ReminderPlanner.triggerAt(allDayInstantOf(date), true, 0, 9 * 60, berlin)
        assertThat(trigger).isEqualTo(millis(date.atTime(9, 0), berlin))
    }

    @Test
    fun `an all-day reminder stays on its date west of Greenwich`() {
        val trigger = ReminderPlanner.triggerAt(allDayInstantOf(date), true, 0, 9 * 60, newYork)
        assertThat(trigger).isEqualTo(millis(date.atTime(9, 0), newYork))
    }

    @Test
    fun `the lead counts back from the all-day time`() {
        val trigger = ReminderPlanner.triggerAt(allDayInstantOf(date), true, 24 * 60, 8 * 60 + 30, berlin)
        assertThat(trigger).isEqualTo(millis(date.minusDays(1).atTime(8, 30), berlin))
    }

    @Test
    fun `a timed reminder counts back from the instant itself`() {
        val due = Instant.fromEpochMilliseconds(1_000_000_000L)
        assertThat(ReminderPlanner.triggerAt(due, false, 15, 9 * 60, berlin))
            .isEqualTo(1_000_000_000L - 15 * 60_000L)
    }

    @Test
    fun `plan uses the all-day setting and skips what is out of the window`() {
        val now = millis(date.minusDays(1).atTime(12, 0), berlin)
        val allDay = testTask(id = 1, due = allDayInstantOf(date)).copy(isAllDay = true)
        val farAway = testTask(id = 2, due = allDayInstantOf(date.plusDays(60))).copy(isAllDay = true)
        val plan = ReminderPlanner.plan(
            tasks = listOf(allDay, farAway),
            perTask = emptyMap(),
            settings = Settings(allDayReminderMinuteOfDay = 7 * 60),
            now = now,
            zone = berlin,
        )
        assertThat(plan).containsExactly(ScheduledReminder(1, millis(date.atTime(7, 0), berlin)))
    }

    @Test
    fun `an all-day task takes the all-day defaults, not the timed ones`() {
        val task = testTask(id = 6, due = allDayInstantOf(date)).copy(isAllDay = true, listId = 7)
        val plan = ReminderPlanner.plan(
            tasks = listOf(task),
            perTask = emptyMap(),
            settings = Settings(
                defaultReminderMinutes = listOf(5),
                defaultAllDayReminderMinutes = listOf(0, 24 * 60),
            ),
            now = millis(date.minusDays(2).atTime(12, 0), berlin),
            zone = berlin,
        )
        assertThat(plan).containsExactly(
            ScheduledReminder(6, millis(date.atTime(9, 0), berlin)),
            ScheduledReminder(6, millis(date.minusDays(1).atTime(9, 0), berlin)),
        )
    }

    @Test
    fun `a list can override the all-day default`() {
        val task = testTask(id = 7, due = allDayInstantOf(date)).copy(isAllDay = true, listId = 8)
        val plan = ReminderPlanner.plan(
            tasks = listOf(task),
            perTask = emptyMap(),
            settings = Settings(perListAllDayReminderOverride = mapOf(8L to emptyList())),
            now = millis(date.minusDays(2).atTime(12, 0), berlin),
            zone = berlin,
        )
        assertThat(plan).isEmpty()
    }

    @Test
    fun `a start-relative reminder counts back from the start`() {
        val start = Instant.fromEpochMilliseconds(millis(date.atTime(10, 0), berlin))
        val due = Instant.fromEpochMilliseconds(millis(date.atTime(18, 0), berlin))
        val task = testTask(id = 3, due = due).copy(start = start)
        val plan = ReminderPlanner.plan(
            tasks = listOf(task),
            perTask = mapOf(3L to listOf(TaskReminder(minutesBefore = 30, fromStart = true))),
            settings = Settings(),
            now = millis(date.atTime(0, 0), berlin),
            zone = berlin,
        )
        assertThat(plan).containsExactly(ScheduledReminder(3, millis(date.atTime(9, 30), berlin)))
    }

    @Test
    fun `an all-day due is shown as its own date`() {
        val text = formatReminderDue(allDayInstantOf(date).toEpochMilliseconds(), true, newYork, Locale.US)
        assertThat(text).isEqualTo("Jul 20, 2026")
    }

    private val words = RelativeDays(today = "today", tomorrow = "tomorrow", yesterday = "yesterday")

    private fun relative(at: LocalDateTime, today: LocalDate = date) =
        formatReminderDue(millis(at, berlin), false, berlin, Locale.US, is24Hour = true, relative = words, today = today)

    @Test
    fun `a due near today reads as a word`() {
        assertThat(relative(date.atTime(9, 0))).isEqualTo("today, 09:00")
        assertThat(relative(date.plusDays(1).atTime(9, 0))).isEqualTo("tomorrow, 09:00")
        assertThat(relative(date.minusDays(1).atTime(9, 0))).isEqualTo("yesterday, 09:00")
    }

    @Test
    fun `a due later this week reads as its weekday`() {
        assertThat(relative(date.plusDays(4).atTime(18, 30))).isEqualTo("Friday, 18:30")
    }

    @Test
    fun `a due a week out or further back keeps its date`() {
        assertThat(relative(date.plusDays(7).atTime(9, 0))).isEqualTo("Jul 27, 2026, 09:00")
        assertThat(relative(date.minusDays(2).atTime(9, 0))).isEqualTo("Jul 18, 2026, 09:00")
    }

    @Test
    fun `an all-day due tomorrow reads as tomorrow in any zone`() {
        val text = formatReminderDue(
            allDayInstantOf(date.plusDays(1)).toEpochMilliseconds(), true, newYork, Locale.US,
            relative = words, today = date,
        )
        assertThat(text).isEqualTo("tomorrow")
    }

    @Test
    fun `every stored reminder of a task is armed`() {
        val due = Instant.fromEpochMilliseconds(millis(date.atTime(18, 0), berlin))
        val task = testTask(id = 4, due = due)
        val plan = ReminderPlanner.plan(
            tasks = listOf(task),
            perTask = mapOf(4L to listOf(TaskReminder(0), TaskReminder(60))),
            settings = Settings(defaultReminderMinutes = listOf(15)),
            now = millis(date.atTime(0, 0), berlin),
            zone = berlin,
        )
        assertThat(plan).containsExactly(
            ScheduledReminder(4, millis(date.atTime(18, 0), berlin)),
            ScheduledReminder(4, millis(date.atTime(17, 0), berlin)),
        )
    }

    @Test
    fun `a task without its own reminders gets every default one`() {
        val due = Instant.fromEpochMilliseconds(millis(date.atTime(18, 0), berlin))
        val task = testTask(id = 5, due = due)
        val plan = ReminderPlanner.plan(
            tasks = listOf(task),
            perTask = emptyMap(),
            settings = Settings(defaultReminderMinutes = listOf(5, 30)),
            now = millis(date.atTime(0, 0), berlin),
            zone = berlin,
        )
        assertThat(plan).containsExactly(
            ScheduledReminder(5, millis(date.atTime(17, 55), berlin)),
            ScheduledReminder(5, millis(date.atTime(17, 30), berlin)),
        )
    }

    @Test
    fun `a recurring occurrence carries its anchor`() {
        val due = Instant.fromEpochMilliseconds(millis(date.atTime(18, 0), berlin))
        val task = testTask(id = 5, due = due).copy(isRecurring = true, occurrenceStart = due)
        val plan = ReminderPlanner.plan(listOf(task), emptyMap(), Settings(), millis(date.atTime(0, 0), berlin), berlin)
        assertThat(plan.single().occurrenceStart).isEqualTo(due.toEpochMilliseconds())
    }
}
