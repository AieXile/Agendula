package com.aiexile.agendula.data.tasks

import com.google.common.truth.Truth.assertThat
import com.aiexile.agendula.data.prefs.Settings
import com.aiexile.agendula.domain.TaskForm
import com.aiexile.agendula.domain.TaskReminder
import com.aiexile.agendula.domain.allDayInstantOf
import com.aiexile.agendula.domain.testTask
import com.aiexile.agendula.domain.asReminderLead
import de.jeanlucmakiola.floret.reminders.asReminderLeads
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class StoredRemindersTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val now = Instant.parse("2026-10-01T10:00:00Z")
    private val dayBeforeStart = TaskReminder(1_440, fromStart = true)
    private val dayBeforeDue = TaskReminder(1_440)
    private val hourBeforeDue = TaskReminder(60)
    private val settings = Settings(
        defaultReminders = listOf(dayBeforeStart, dayBeforeDue, hourBeforeDue).map { it.asReminderLead() },
    )

    private fun stored(form: TaskForm, current: com.aiexile.agendula.domain.Task? = null, with: Settings = settings) =
        StoredReminders.of(form, current, with, now.toEpochMilliseconds(), zone)

    @Test
    fun `a new task takes the defaults for the dates it has`() {
        val form = TaskForm(title = "t", listId = 1, start = now + 2.days, due = now + 5.days)
        assertThat(stored(form)).containsExactly(dayBeforeStart, dayBeforeDue, hourBeforeDue)
        assertThat(stored(form.copy(start = null))).containsExactly(dayBeforeDue, hourBeforeDue)
        assertThat(stored(form.copy(due = null))).containsExactly(dayBeforeStart)
    }

    @Test
    fun `a default already past when the task gets it is left out`() {
        val form = TaskForm(title = "t", listId = 1, start = now, due = now + 5.days)
        assertThat(stored(form)).containsExactly(dayBeforeDue, hourBeforeDue)
    }

    @Test
    fun `a recurring task keeps it for the occurrences to come`() {
        val form = TaskForm(title = "t", listId = 1, start = now, due = now + 5.days, rrule = "FREQ=DAILY")
        assertThat(stored(form)).containsExactly(dayBeforeStart, dayBeforeDue, hourBeforeDue)
    }

    private fun switched(reminders: List<TaskReminder>, form: TaskForm, with: Settings = settings) =
        StoredReminders.switched(reminders, form, with, now.toEpochMilliseconds(), zone)

    @Test
    fun `a date the task gains brings its defaults`() {
        val current = testTask(start = now + 2.days)
        val form = TaskForm(title = "t", listId = 1, start = now + 2.days, due = now + 5.days, reminders = listOf(dayBeforeStart))
        assertThat(stored(form, current)).containsExactly(dayBeforeStart, dayBeforeDue, hourBeforeDue)
        val due = testTask(due = now + 5.days)
        assertThat(stored(form.copy(reminders = listOf(hourBeforeDue)), due)).containsExactly(hourBeforeDue, dayBeforeStart)
    }

    @Test
    fun `a gained date the form already counts from keeps the form`() {
        val current = testTask(start = now + 2.days)
        val ownDue = TaskReminder(30)
        val form = TaskForm(title = "t", listId = 1, start = now + 2.days, due = now + 5.days, reminders = listOf(ownDue))
        assertThat(stored(form, current)).containsExactly(ownDue)
    }

    @Test
    fun `reminders the editor chose are stored as they are`() {
        val form = TaskForm(title = "t", listId = 1, start = now + 2.days, due = now + 5.days, remindersChosen = true)
        assertThat(stored(form)).isEmpty()
        assertThat(stored(form.copy(reminders = listOf(hourBeforeDue)), testTask(due = now + 5.days)))
            .containsExactly(hourBeforeDue)
    }

    @Test
    fun `an edit that keeps its dates keeps the form`() {
        val current = testTask(due = now + 5.days)
        val form = TaskForm(title = "t", listId = 1, due = now + 6.days)
        assertThat(stored(form, current)).isEmpty()
    }

    @Test
    fun `a default from before anchors existed swaps when the task turns all-day`() {
        val legacy = Settings(defaultReminders = listOf(15).asReminderLeads(), defaultAllDayReminders = listOf(0).asReminderLeads())
        val due = allDayInstantOf(LocalDate.of(2026, 10, 3))
        val form = TaskForm(title = "t", listId = 1, due = due, isAllDay = true)
        assertThat(switched(listOf(TaskReminder(15)), form, legacy)).containsExactly(TaskReminder(0))
    }

    @Test
    fun `only the whole default swaps`() {
        val due = allDayInstantOf(LocalDate.of(2026, 10, 3))
        val form = TaskForm(title = "t", listId = 1, due = due, isAllDay = true)
        assertThat(switched(listOf(dayBeforeDue), form)).containsExactly(dayBeforeDue)
    }

    @Test
    fun `a reminder of its own turns into whole days on an all-day task`() {
        val due = allDayInstantOf(LocalDate.of(2026, 10, 3))
        val form = TaskForm(title = "t", listId = 1, due = due, isAllDay = true)
        assertThat(switched(listOf(TaskReminder(20 * 60)), form)).containsExactly(TaskReminder(1_440))
    }

    @Test
    fun `a time of day is dropped when the task turns timed`() {
        val form = TaskForm(title = "t", listId = 1, due = now + 2.days + 3.hours)
        val ownTime = TaskReminder.allDay(0, 17 * 60)
        assertThat(switched(listOf(ownTime, TaskReminder(2_880)), form)).containsExactly(TaskReminder(2_880))
        assertThat(switched(listOf(ownTime), form)).containsExactly(dayBeforeDue, hourBeforeDue)
    }
}
