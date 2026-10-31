package com.aiexile.agendula.domain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TaskReminderTest {

    @Test
    fun `whole days keep the device's all-day time`() {
        assertThat(TaskReminder(0).allDay).isEqualTo(AllDayReminder(0, null))
        assertThat(TaskReminder(2 * 1_440).allDay).isEqualTo(AllDayReminder(2, null))
    }

    @Test
    fun `anything else names a time of day`() {
        assertThat(TaskReminder(-9 * 60).allDay).isEqualTo(AllDayReminder(0, 9 * 60))
        assertThat(TaskReminder(7 * 60).allDay).isEqualTo(AllDayReminder(1, 17 * 60))
        assertThat(TaskReminder(15).allDay).isEqualTo(AllDayReminder(1, 23 * 60 + 45))
    }

    @Test
    fun `a day and a time round-trip`() {
        listOf(0 to 9 * 60, 1 to 17 * 60, 7 to 30, 3 to null).forEach { (days, minute) ->
            assertThat(TaskReminder.allDay(days, minute).allDay).isEqualTo(AllDayReminder(days, minute))
        }
    }

    @Test
    fun `a lead past the day is clamped to its last minute`() {
        assertThat(TaskReminder(-1_440).allDay).isEqualTo(AllDayReminder(0, 1_439))
    }

    @Test
    fun `midnight is a minute past it`() {
        assertThat(TaskReminder.allDay(1, 0).allDay).isEqualTo(AllDayReminder(1, 1))
        assertThat(TaskReminder.allDay(1, null).allDay).isEqualTo(AllDayReminder(1, null))
    }

    @Test
    fun `a default lead keeps its anchor whatever its lead`() {
        val reminders = listOf(TaskReminder(-540, fromStart = true), TaskReminder(1_440, fromStart = true), TaskReminder(-540))
        assertThat(reminders.map { it.asReminderLead().asTaskReminder() }).isEqualTo(reminders)
    }

    @Test
    fun `whole days round to the nearest`() {
        assertThat(TaskReminder(60).inWholeDays()).isEqualTo(TaskReminder(0))
        assertThat(TaskReminder(23 * 60, fromStart = true).inWholeDays()).isEqualTo(TaskReminder(1_440, fromStart = true))
        assertThat(TaskReminder(-540).inWholeDays()).isEqualTo(TaskReminder(0))
    }

    @Test
    fun `all-day reminders are listed in the order they ring`() {
        val eightPm = TaskReminder(0)
        val nine = TaskReminder.allDay(0, 9 * 60)
        val dayBefore = TaskReminder(1_440)
        assertThat(listOf(eightPm, nine, dayBefore).sortedWith(taskReminderDisplayOrder(true, 20 * 60)))
            .containsExactly(dayBefore, nine, eightPm).inOrder()
    }
}
