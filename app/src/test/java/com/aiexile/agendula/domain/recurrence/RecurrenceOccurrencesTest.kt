package com.aiexile.agendula.domain.recurrence

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

class RecurrenceOccurrencesTest {

    private fun date(year: Int, month: Int, day: Int) = LocalDate(year, month, day)

    @Test
    fun `daily rule starts at the task's own date`() {
        val occurrences = SimpleRecurrence(RecurrenceFreq.Daily)
            .upcomingOccurrences(date(2026, 7, 30), limit = 3)

        assertThat(occurrences).containsExactly(
            date(2026, 7, 30),
            date(2026, 7, 31),
            date(2026, 8, 1),
        ).inOrder()
    }

    @Test
    fun `interval multiplies the period`() {
        val occurrences = SimpleRecurrence(RecurrenceFreq.Daily, interval = 3)
            .upcomingOccurrences(date(2026, 7, 30), limit = 3)

        assertThat(occurrences).containsExactly(
            date(2026, 7, 30),
            date(2026, 8, 2),
            date(2026, 8, 5),
        ).inOrder()
    }

    @Test
    fun `weekly without weekday picks repeats the start's weekday`() {
        // 30 Jul 2026 is a Thursday.
        val occurrences = SimpleRecurrence(RecurrenceFreq.Weekly)
            .upcomingOccurrences(date(2026, 7, 30), limit = 3)

        assertThat(occurrences).containsExactly(
            date(2026, 7, 30),
            date(2026, 8, 6),
            date(2026, 8, 13),
        ).inOrder()
    }

    @Test
    fun `weekly with picks fires on every chosen day, in weekday order`() {
        val occurrences = SimpleRecurrence(
            RecurrenceFreq.Weekly,
            byDays = setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY),
        ).upcomingOccurrences(date(2026, 7, 30), limit = 4)

        // The Thursday start is an occurrence in its own right (RFC 5545 puts
        // DTSTART in the set), then Friday 31 Jul and Mon/Fri of the next week.
        assertThat(occurrences).containsExactly(
            date(2026, 7, 30),
            date(2026, 7, 31),
            date(2026, 8, 3),
            date(2026, 8, 7),
        ).inOrder()
    }

    @Test
    fun `the start counts once even when it is also one of the picks`() {
        // Thursday start with Thursday picked: the seeded DTSTART and the rule's
        // own hit are the same date and must not both be listed.
        val occurrences = SimpleRecurrence(
            RecurrenceFreq.Weekly,
            byDays = setOf(DayOfWeek.THURSDAY),
        ).upcomingOccurrences(date(2026, 7, 30), limit = 3)

        assertThat(occurrences).containsExactly(
            date(2026, 7, 30),
            date(2026, 8, 6),
            date(2026, 8, 13),
        ).inOrder()
    }

    @Test
    fun `weekly interval skips whole weeks, counted from Monday`() {
        val occurrences = SimpleRecurrence(
            RecurrenceFreq.Weekly,
            interval = 2,
            byDays = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
        ).upcomingOccurrences(date(2026, 7, 30), limit = 3)

        // Start week (27 Jul–2 Aug) contributes only its Thursday; the next block
        // is two weeks on, so 10 Aug and 13 Aug — not 3 Aug.
        assertThat(occurrences).containsExactly(
            date(2026, 7, 30),
            date(2026, 8, 10),
            date(2026, 8, 13),
        ).inOrder()
    }

    @Test
    fun `monthly skips months without the start day rather than clamping`() {
        val occurrences = SimpleRecurrence(RecurrenceFreq.Monthly)
            .upcomingOccurrences(date(2026, 1, 31), limit = 4)

        // February, April and June have no 31st — the rule passes them by.
        assertThat(occurrences).containsExactly(
            date(2026, 1, 31),
            date(2026, 3, 31),
            date(2026, 5, 31),
            date(2026, 7, 31),
        ).inOrder()
    }

    @Test
    fun `yearly on 29 February only lands in leap years`() {
        val occurrences = SimpleRecurrence(RecurrenceFreq.Yearly)
            .upcomingOccurrences(date(2024, 2, 29), limit = 3)

        assertThat(occurrences).containsExactly(
            date(2024, 2, 29),
            date(2028, 2, 29),
            date(2032, 2, 29),
        ).inOrder()
    }

    @Test
    fun `count limits the series and skipped periods don't consume one`() {
        val occurrences = SimpleRecurrence(
            RecurrenceFreq.Monthly,
            end = RecurrenceEnd.Count(3),
        ).upcomingOccurrences(date(2026, 1, 31), limit = 10)

        assertThat(occurrences).containsExactly(
            date(2026, 1, 31),
            date(2026, 3, 31),
            date(2026, 5, 31),
        ).inOrder()
    }

    @Test
    fun `until is inclusive of its own date`() {
        val occurrences = SimpleRecurrence(
            RecurrenceFreq.Daily,
            end = RecurrenceEnd.Until(date(2026, 8, 1)),
        ).upcomingOccurrences(date(2026, 7, 30), limit = 10)

        assertThat(occurrences).containsExactly(
            date(2026, 7, 30),
            date(2026, 7, 31),
            date(2026, 8, 1),
        ).inOrder()
    }

    @Test
    fun `an until before the start yields nothing instead of spinning`() {
        val occurrences = SimpleRecurrence(
            RecurrenceFreq.Daily,
            end = RecurrenceEnd.Until(date(2026, 7, 1)),
        ).upcomingOccurrences(date(2026, 7, 30), limit = 3)

        assertThat(occurrences).isEmpty()
    }

    @Test
    fun `a run inside one year needs no year, one that leaves it does`() {
        val start = date(2026, 7, 30)
        val withinYear = SimpleRecurrence(RecurrenceFreq.Daily).upcomingOccurrences(start, limit = 3)
        assertThat(occurrencesSpanYears(withinYear, start)).isFalse()

        // Yearly: same day and month every time, so the year is the only thing
        // telling the three dates apart.
        val yearly = SimpleRecurrence(RecurrenceFreq.Yearly).upcomingOccurrences(start, limit = 3)
        assertThat(occurrencesSpanYears(yearly, start)).isTrue()

        // Monthly rolling past December.
        val newYearStart = date(2026, 11, 30)
        val overflowing = SimpleRecurrence(RecurrenceFreq.Monthly)
            .upcomingOccurrences(newYearStart, limit = 3)
        assertThat(overflowing).containsExactly(
            date(2026, 11, 30),
            date(2026, 12, 30),
            date(2027, 1, 30),
        ).inOrder()
        assertThat(occurrencesSpanYears(overflowing, newYearStart)).isTrue()
    }

    @Test
    fun `limit and count are both respected, whichever is smaller`() {
        val rule = SimpleRecurrence(RecurrenceFreq.Daily, end = RecurrenceEnd.Count(2))

        assertThat(rule.upcomingOccurrences(date(2026, 7, 30), limit = 5)).hasSize(2)
        assertThat(rule.upcomingOccurrences(date(2026, 7, 30), limit = 1)).hasSize(1)
        assertThat(rule.upcomingOccurrences(date(2026, 7, 30), limit = 0)).isEmpty()
    }
}
