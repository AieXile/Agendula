package com.aiexile.agendula.domain.recurrence

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus

/**
 * The first [limit] dates a [SimpleRecurrence] fires on, starting at [start]
 * (DTSTART), for previewing a rule as dates instead of as words. A preview only,
 * kept to the shapes the picker can build; the expander stays the authority.
 *
 * Mirrors RFC 5545: [start] is always the first occurrence (§3.8.5.3), even when
 * the rule's own picks miss it; a monthly or yearly rule *skips* a period the
 * start day doesn't exist in rather than clamping; a weekly rule repeats in
 * blocks of `interval` weeks beginning on Monday (the default WKST, since
 * [toRRule] never writes one); [RecurrenceEnd.Count] counts real occurrences and
 * [RecurrenceEnd.Until] is inclusive.
 *
 * Returns fewer than [limit] dates when the series ends first, and an empty list
 * only when the rule yields nothing at all (an UNTIL before [start]).
 */
fun SimpleRecurrence.upcomingOccurrences(start: LocalDate, limit: Int): List<LocalDate> {
    if (limit <= 0) return emptyList()
    val until = (end as? RecurrenceEnd.Until)?.date
    val maxCount = (end as? RecurrenceEnd.Count)?.times ?: Int.MAX_VALUE
    val wanted = minOf(limit, maxCount)
    if (wanted <= 0) return emptyList()
    if (until != null && start > until) return emptyList()

    // DTSTART is in the recurrence set whatever the rule picks, so seed with it
    // and let the walk skip anything landing on or before it.
    val result = mutableListOf(start)
    var period = 0
    // Periods can yield nothing (a skipped 31st), so the cap counts periods
    // examined rather than dates found.
    while (result.size < wanted && period < MAX_PERIODS) {
        for (date in occurrencesInPeriod(period, start)) {
            if (date <= start) continue
            if (until != null && date > until) return result
            result += date
            if (result.size == wanted) return result
        }
        period++
    }
    return result
}

/** The dates this rule's [period]-th repetition yields (empty when skipped). */
private fun SimpleRecurrence.occurrencesInPeriod(period: Int, start: LocalDate): List<LocalDate> =
    when (freq) {
        RecurrenceFreq.Daily -> listOf(start.plus(period * interval, DateTimeUnit.DAY))
        RecurrenceFreq.Weekly -> weeklyOccurrences(period, start)
        RecurrenceFreq.Monthly -> {
            val month = start.plus(period * interval, DateTimeUnit.MONTH)
            // plus() clamps into the shorter month, but the rule skips such a
            // period — so a clamped date means "not this month".
            listOfNotNull(dateOrNull(month.year, month.month.number, start.day))
        }
        RecurrenceFreq.Yearly ->
            listOfNotNull(dateOrNull(start.year + period * interval, start.month.number, start.day))
    }

/**
 * One weekly block: every picked weekday inside the week that begins
 * `period * interval` weeks after the start's own week, in weekday order. With
 * no picks the rule simply repeats the start's weekday.
 */
private fun SimpleRecurrence.weeklyOccurrences(period: Int, start: LocalDate): List<LocalDate> {
    if (byDays.isEmpty()) return listOf(start.plus(period * interval, DateTimeUnit.WEEK))
    val daysIntoWeek = (start.dayOfWeek.isoDayNumber - DayOfWeek.MONDAY.isoDayNumber + DAYS_PER_WEEK) %
        DAYS_PER_WEEK
    val weekStart = start
        .minus(daysIntoWeek, DateTimeUnit.DAY)
        .plus(period * interval, DateTimeUnit.WEEK)
    return byDays.sortedBy { it.isoDayNumber }.map { day ->
        weekStart.plus(
            (day.isoDayNumber - DayOfWeek.MONDAY.isoDayNumber + DAYS_PER_WEEK) % DAYS_PER_WEEK,
            DateTimeUnit.DAY,
        )
    }
}

/**
 * Whether a run of [occurrences] starting at [start] leaves its starting year,
 * i.e. whether showing them without a year would be ambiguous — a yearly rule
 * would otherwise read as the same date repeated.
 */
fun occurrencesSpanYears(occurrences: List<LocalDate>, start: LocalDate): Boolean =
    occurrences.any { it.year != start.year } || occurrences.map { it.year }.distinct().size > 1

/** [LocalDate] for a day-of-month that may not exist in that month; null if it doesn't. */
private fun dateOrNull(year: Int, month: Int, day: Int): LocalDate? =
    runCatching { LocalDate(year, month, day) }.getOrNull()

private const val DAYS_PER_WEEK = 7

/**
 * How many repetitions to examine before giving up: generous enough for the
 * sparsest rule the picker can build, bounded so a rule whose occurrences all
 * fall outside its own UNTIL can't spin.
 */
private const val MAX_PERIODS = 2_000
