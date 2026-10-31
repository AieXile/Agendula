package com.aiexile.agendula.domain.recurrence

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Test

class SimpleRecurrenceTest {

    private val utc = TimeZone.UTC
    private val berlin = TimeZone.of("Europe/Berlin")

    @Test
    fun `plain frequency parses with defaults`() {
        assertThat(parseSimpleRecurrence("FREQ=WEEKLY"))
            .isEqualTo(SimpleRecurrence(RecurrenceFreq.Weekly))
        assertThat(parseSimpleRecurrence("FREQ=DAILY"))
            .isEqualTo(SimpleRecurrence(RecurrenceFreq.Daily))
    }

    @Test
    fun `leading RRULE prefix and WKST are tolerated`() {
        assertThat(parseSimpleRecurrence("RRULE:FREQ=MONTHLY;WKST=MO"))
            .isEqualTo(SimpleRecurrence(RecurrenceFreq.Monthly))
    }

    @Test
    fun `interval parses`() {
        assertThat(parseSimpleRecurrence("FREQ=WEEKLY;INTERVAL=2"))
            .isEqualTo(SimpleRecurrence(RecurrenceFreq.Weekly, interval = 2))
    }

    @Test
    fun `until parses date-only and UTC datetime forms`() {
        val expected = SimpleRecurrence(
            RecurrenceFreq.Daily,
            end = RecurrenceEnd.Until(LocalDate(2026, 8, 1)),
        )
        assertThat(parseSimpleRecurrence("FREQ=DAILY;UNTIL=20260801", utc)).isEqualTo(expected)
        assertThat(parseSimpleRecurrence("FREQ=DAILY;UNTIL=20260801T235959Z", utc))
            .isEqualTo(expected)
    }

    @Test
    fun `until datetime converts from UTC into the given zone before taking the date`() {
        // 21:59:59Z == 23:59:59 in Berlin (CEST) — still August 1 there.
        assertThat(parseSimpleRecurrence("FREQ=DAILY;UNTIL=20260801T215959Z", berlin))
            .isEqualTo(
                SimpleRecurrence(RecurrenceFreq.Daily, end = RecurrenceEnd.Until(LocalDate(2026, 8, 1))),
            )
    }

    @Test
    fun `count parses`() {
        assertThat(parseSimpleRecurrence("FREQ=YEARLY;COUNT=5"))
            .isEqualTo(SimpleRecurrence(RecurrenceFreq.Yearly, end = RecurrenceEnd.Count(5)))
    }

    @Test
    fun `weekly byday parses as weekday picks`() {
        assertThat(parseSimpleRecurrence("FREQ=WEEKLY;BYDAY=MO,FR"))
            .isEqualTo(
                SimpleRecurrence(
                    RecurrenceFreq.Weekly,
                    byDays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
                ),
            )
    }

    @Test
    fun `rules beyond the simple shape are rejected`() {
        // Ordinal BYDAY ("second Thursday") and BYDAY on non-weekly rules.
        assertThat(parseSimpleRecurrence("FREQ=WEEKLY;BYDAY=MO,2TH")).isNull()
        assertThat(parseSimpleRecurrence("FREQ=MONTHLY;BYDAY=MO")).isNull()
        assertThat(parseSimpleRecurrence("FREQ=MONTHLY;BYMONTHDAY=15")).isNull()
        assertThat(parseSimpleRecurrence("FREQ=HOURLY")).isNull()
        assertThat(parseSimpleRecurrence("")).isNull()
        assertThat(parseSimpleRecurrence("FREQ=DAILY;UNTIL=20260801;COUNT=3")).isNull()
        assertThat(parseSimpleRecurrence("FREQ=DAILY;INTERVAL=0")).isNull()
        assertThat(parseSimpleRecurrence("FREQ=DAILY;COUNT=abc")).isNull()
    }

    @Test
    fun `toRRule renders the minimal form`() {
        assertThat(SimpleRecurrence(RecurrenceFreq.Weekly).toRRule()).isEqualTo("FREQ=WEEKLY")
        assertThat(SimpleRecurrence(RecurrenceFreq.Daily, interval = 3).toRRule())
            .isEqualTo("FREQ=DAILY;INTERVAL=3")
        assertThat(
            SimpleRecurrence(RecurrenceFreq.Monthly, end = RecurrenceEnd.Count(12)).toRRule(),
        ).isEqualTo("FREQ=MONTHLY;COUNT=12")
    }

    @Test
    fun `toRRule renders weekdays in ISO order regardless of set order`() {
        val rule = SimpleRecurrence(
            RecurrenceFreq.Weekly,
            byDays = setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
        ).toRRule()
        assertThat(rule).isEqualTo("FREQ=WEEKLY;BYDAY=MO,WE,FR")
    }

    @Test
    fun `toRRule ignores weekday picks on non-weekly frequencies`() {
        val rule = SimpleRecurrence(
            RecurrenceFreq.Monthly,
            byDays = setOf(DayOfWeek.MONDAY),
        ).toRRule()
        assertThat(rule).isEqualTo("FREQ=MONTHLY")
    }

    @Test
    fun `toRRule writes until as the end of the chosen day in the given zone`() {
        val rule = SimpleRecurrence(
            RecurrenceFreq.Weekly,
            interval = 2,
            end = RecurrenceEnd.Until(LocalDate(2026, 8, 1)),
        )
        assertThat(rule.toRRule(utc))
            .isEqualTo("FREQ=WEEKLY;INTERVAL=2;UNTIL=20260801T235959Z")
        // 23:59:59 Berlin (CEST, +2) == 21:59:59Z.
        assertThat(rule.toRRule(berlin))
            .isEqualTo("FREQ=WEEKLY;INTERVAL=2;UNTIL=20260801T215959Z")
    }

    // 2026-06-19T23:59:00Z — a moment just before a June 20 occurrence.
    private val cutoffMillis = 1_781_913_540_000L

    @Test
    fun `truncation replaces count and keeps every other part`() {
        assertThat(rruleTruncatedAt("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,FR;COUNT=30", cutoffMillis))
            .isEqualTo("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,FR;UNTIL=20260619T235900Z")
    }

    @Test
    fun `truncation replaces an existing until`() {
        assertThat(rruleTruncatedAt("FREQ=DAILY;UNTIL=20301231T235959Z", cutoffMillis))
            .isEqualTo("FREQ=DAILY;UNTIL=20260619T235900Z")
    }

    @Test
    fun `truncation works on rules the simple picker cannot express`() {
        assertThat(rruleTruncatedAt("RRULE:FREQ=MONTHLY;BYDAY=2TH", cutoffMillis))
            .isEqualTo("FREQ=MONTHLY;BYDAY=2TH;UNTIL=20260619T235900Z")
    }

    @Test
    fun `parse and render round-trip`() {
        val rules = listOf(
            "FREQ=DAILY",
            "FREQ=WEEKLY;INTERVAL=2",
            "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,FR;UNTIL=20301231T235959Z",
            "FREQ=MONTHLY;COUNT=6",
            "FREQ=YEARLY;UNTIL=20301231T235959Z",
        )
        rules.forEach { rule ->
            assertThat(parseSimpleRecurrence(rule, utc)!!.toRRule(utc)).isEqualTo(rule)
        }
        // Round-trips through a non-UTC zone too: 22:59:59Z == 23:59:59 CET.
        val berlinRule = "FREQ=WEEKLY;BYDAY=MO,FR;UNTIL=20301231T225959Z"
        assertThat(parseSimpleRecurrence(berlinRule, berlin)!!.toRRule(berlin))
            .isEqualTo(berlinRule)
    }
}
