package com.aiexile.agendula.domain.recurrence

import org.dmfs.rfc5545.DateTime
import org.dmfs.rfc5545.recur.RecurrenceRule
import org.dmfs.rfc5545.recurrenceset.RecurrenceList
import org.dmfs.rfc5545.recurrenceset.RecurrenceRuleAdapter
import org.dmfs.rfc5545.recurrenceset.RecurrenceSet
import java.time.ZoneId
import java.util.TimeZone
import kotlin.time.Instant

private const val MILLIS_PER_SECOND = 1000L
private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

/** The rule set of one task series, as stored. All strings are raw iCalendar values. */
data class RecurrenceSpec(
    val rrule: String?,
    val rdate: String?,
    val exdate: String?,
    /** The series anchor: DTSTART if present, else DUE. Never null for a recurring task. */
    val anchor: Instant,
    val isAllDay: Boolean,
    /** IANA zone id the anchor is expressed in; null means floating/local. */
    val timeZone: String?,
)

/**
 * The window expansion is bounded to: [from] inclusive, [until] exclusive, and
 * never more than [maxOccurrences] results — so an unbounded `RRULE` terminates.
 *
 * [pivot] is where "now" sits inside the window, and it is what the occurrence
 * budget is spent around. Without it a series firing more often than about
 * once a day exhausts [maxOccurrences] inside the past alone — an eight-hourly
 * task would stop expanding months before today, so it would never appear in
 * Today or Upcoming at all. At most a quarter of the budget goes to occurrences
 * before [pivot], and the most recent of those are the ones kept.
 */
data class ExpansionWindow(
    val from: Instant,
    val until: Instant,
    val maxOccurrences: Int = 500,
    val pivot: Instant = from,
)

/**
 * Expands a task series into its occurrences in memory, over `lib-recur`.
 *
 * There is no materialised instances table behind this: the repository already
 * filters and sorts in Kotlin, so occurrences are computed at read time and the
 * whole class of staleness bugs a cached table brings never exists.
 */
object RecurrenceExpander {

    /**
     * Every occurrence of [spec] inside [window], as its `RECURRENCE-ID` anchor —
     * the instant identifying that occurrence within the series. Ascending,
     * deduplicated, `EXDATE` applied.
     *
     * The anchor itself is always part of the set (RFC 5545 §3.8.5.3: `DTSTART`
     * is the first instance), so a spec with no rule and no `RDATE` expands to
     * exactly its anchor. A malformed `RRULE`, `RDATE` or `EXDATE` is dropped
     * rather than thrown — a task whose stored rule cannot be parsed still has
     * to appear.
     *
     * [floatingZone] resolves a series with no [RecurrenceSpec.timeZone]; it is a
     * parameter rather than a `TimeZone.getDefault()` lookup so expansion is
     * deterministic under test.
     */
    fun expand(
        spec: RecurrenceSpec,
        window: ExpansionWindow,
        floatingZone: ZoneId = ZoneId.systemDefault(),
    ): List<Instant> {
        val zone = zoneOf(spec, floatingZone)
        val anchorMillis = anchorMillis(spec)

        val set = RecurrenceSet()
        spec.rrule.orNull()?.let { raw -> ruleOf(raw, zone)?.let { set.addInstances(RecurrenceRuleAdapter(it)) } }
        spec.rdate.orNull()?.let { raw -> datesOf(raw, zone)?.let(set::addInstances) }
        spec.exdate.orNull()?.let { raw -> datesOf(raw, zone)?.let(set::addExceptions) }

        val iterator = set.iterator(zone, anchorMillis, window.until.toEpochMilliseconds())
        iterator.fastForward(window.from.toEpochMilliseconds())

        // Occurrences arrive ascending, so everything before the pivot lands first
        // and `past` is final by the time the first future one appears. Past is a
        // sliding window (the newest are the ones worth keeping); the rest of the
        // budget then goes to the future, undiminished when there is no past.
        val pastCap = window.maxOccurrences / 4
        val past = ArrayDeque<Instant>()
        val future = ArrayList<Instant>()
        var previous = Long.MIN_VALUE
        while (iterator.hasNext()) {
            val millis = iterator.next()
            if (millis == previous) continue
            previous = millis
            val at = Instant.fromEpochMilliseconds(millis)
            if (at < window.pivot) {
                if (past.size == pastCap) past.removeFirst()
                if (pastCap > 0) past.addLast(at)
            } else {
                future += at
                if (past.size + future.size >= window.maxOccurrences) break
            }
        }
        return past + future
    }

    /**
     * Index of the current occurrence in an ascending [occurrences] list: the
     * first one at or after [now], or the last one when the whole series is in
     * the past. `-1` when there are no occurrences at all.
     */
    fun currentOccurrenceIndex(occurrences: List<Instant>, now: Instant): Int {
        if (occurrences.isEmpty()) return -1
        val next = occurrences.indexOfFirst { it >= now }
        return if (next >= 0) next else occurrences.lastIndex
    }

    /**
     * Each occurrence's distance from the current one, index-aligned with
     * [occurrences]. `0` is the current occurrence, negative counts back into the
     * past and positive counts forward — the convention `Task.distanceFromCurrent`
     * carries and the data sources pick the current occurrence by.
     *
     * Purely positional: unlike the dmfs provider, which drove the same number off
     * each instance's closed state, this knows only times. Completion-aware
     * refinement belongs where overrides carry their status.
     */
    fun distancesFromCurrent(occurrences: List<Instant>, now: Instant): List<Int> {
        val current = currentOccurrenceIndex(occurrences, now)
        if (current < 0) return emptyList()
        return occurrences.indices.map { it - current }
    }

    private fun zoneOf(spec: RecurrenceSpec, floatingZone: ZoneId): TimeZone {
        if (spec.isAllDay) return TimeZone.getTimeZone(ZoneId.of("UTC"))
        val stored = spec.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        return TimeZone.getTimeZone(stored ?: floatingZone)
    }

    /**
     * All-day series are date-anchored: pin the anchor to UTC midnight, as it is
     * stored. A timed one is floored to the second, because RFC 5545 DATE-TIME
     * has no sub-second field — carrying millis in makes lib-recur emit the raw
     * anchor *and* its truncated self, doubling the first occurrence, and mints
     * `RECURRENCE-ID`s no other client could address.
     */
    private fun anchorMillis(spec: RecurrenceSpec): Long {
        val millis = spec.anchor.toEpochMilliseconds()
        val unit = if (spec.isAllDay) MILLIS_PER_DAY else MILLIS_PER_SECOND
        return Math.floorDiv(millis, unit) * unit
    }

    private fun ruleOf(value: String, zone: TimeZone): RecurrenceRule? = runCatching {
        RecurrenceRule(value).also { rule ->
            // lib-recur refuses to iterate a floating UNTIL against a zoned start,
            // and RFC 5545 §3.3.10 forbids that pairing — but stored rules carry it
            // anyway. Re-read the UNTIL's local fields in the series zone.
            val until = rule.until
            if (until != null && until.isFloating) {
                // ⚠️ A DATE-valued UNTIL bounds the whole of its last day, not
                // midnight on it. Its hour, minute and second fields are all
                // zero, so rebuilding from them silently dropped every
                // occurrence on the final day of a series anchored at any other
                // time — five daily occurrences where there should be six, only
                // away from UTC, which is to say for almost everyone. §3.3.10
                // pairs a DATE UNTIL with a DATE DTSTART; Google and Apple emit
                // it beside a timed one anyway, and end-of-day is the only
                // reading of that which keeps the day the user can see.
                val date = until.isAllDay
                rule.until = DateTime(
                    zone,
                    until.year,
                    until.month,
                    until.dayOfMonth,
                    if (date) 23 else until.hours,
                    if (date) 59 else until.minutes,
                    if (date) 59 else until.seconds,
                )
            }
        }
    }.getOrNull()

    private fun datesOf(value: String, zone: TimeZone): RecurrenceList? =
        runCatching { RecurrenceList(value, zone) }.getOrNull()

    private fun String?.orNull(): String? = this?.trim()?.ifEmpty { null }
}
