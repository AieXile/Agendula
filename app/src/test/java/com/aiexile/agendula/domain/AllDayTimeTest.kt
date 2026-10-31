package com.aiexile.agendula.domain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Instant

class AllDayTimeTest {

    private val berlin = ZoneId.of("Europe/Berlin") // UTC+2 in July
    private val newYork = ZoneId.of("America/New_York") // UTC-4 in July
    private val julyTwentieth = LocalDate.of(2026, 7, 20)

    @Test
    fun `an all-day instant is UTC midnight of its date`() {
        val instant = allDayInstantOf(julyTwentieth)
        assertThat(instant.toEpochMilliseconds() % (24L * 60 * 60 * 1000)).isEqualTo(0L)
        assertThat(instant.calendarDate(allDay = true)).isEqualTo(julyTwentieth)
    }

    @Test
    fun `an all-day date reads the same everywhere, unlike a timed one`() {
        val allDay = allDayInstantOf(julyTwentieth)
        // The whole point: zone must not change which day an all-day value denotes.
        assertThat(allDay.calendarDate(allDay = true, zone = berlin)).isEqualTo(julyTwentieth)
        assertThat(allDay.calendarDate(allDay = true, zone = newYork)).isEqualTo(julyTwentieth)
        // Read as a timed value in New York it would slip to the 19th — the bug.
        assertThat(allDay.calendarDate(allDay = false, zone = newYork)).isEqualTo(julyTwentieth.minusDays(1))
    }

    @Test
    fun `toggling all-day off keeps the day and lands on local midnight`() {
        val allDay = allDayInstantOf(julyTwentieth)
        val timed = allDay.rebasedForAllDay(allDay = false, zone = berlin)

        assertThat(timed.calendarDate(allDay = false, zone = berlin)).isEqualTo(julyTwentieth)
        val local = java.time.Instant.ofEpochMilli(timed.toEpochMilliseconds()).atZone(berlin)
        assertThat(local.toLocalTime()).isEqualTo(java.time.LocalTime.MIDNIGHT)
    }

    @Test
    fun `toggling all-day on keeps the day the user was looking at`() {
        // 2026-07-20T23:30 in Berlin — late enough that a naive UTC read slips a day.
        val lateEvening = Instant.fromEpochMilliseconds(
            julyTwentieth.atTime(23, 30).atZone(berlin).toInstant().toEpochMilli(),
        )
        val allDay = lateEvening.rebasedForAllDay(allDay = true, zone = berlin)

        assertThat(allDay.calendarDate(allDay = true)).isEqualTo(julyTwentieth)
    }

    @Test
    fun `round-tripping the toggle is stable`() {
        val original = allDayInstantOf(julyTwentieth)
        val there = original.rebasedForAllDay(allDay = false, zone = newYork)
        val back = there.rebasedForAllDay(allDay = true, zone = newYork)
        assertThat(back).isEqualTo(original)
    }
}
