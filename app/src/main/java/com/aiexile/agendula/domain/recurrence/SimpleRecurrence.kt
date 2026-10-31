package com.aiexile.agendula.domain.recurrence

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The recurrence shapes the simple picker can express: a frequency,
 * an interval, weekly weekday picks, and an optional end. Anything beyond
 * that (ordinal BYDAY like "2TH", BYMONTHDAY, EXDATE rules, …) stays a raw
 * RRULE string the picker shows as "custom" and leaves untouched unless the
 * user replaces it.
 */
data class SimpleRecurrence(
    val freq: RecurrenceFreq,
    val interval: Int = 1,
    val end: RecurrenceEnd = RecurrenceEnd.Never,
    /**
     * Weekly only: the weekdays the rule fires on (RRULE BYDAY). Empty means
     * no BYDAY part — the day is derived from DTSTART.
     */
    val byDays: Set<DayOfWeek> = emptySet(),
)

enum class RecurrenceFreq {
    Daily,
    Weekly,
    Monthly,
    Yearly,
}

sealed interface RecurrenceEnd {
    data object Never : RecurrenceEnd

    /** Last day on which an occurrence may fall (inclusive). */
    data class Until(val date: LocalDate) : RecurrenceEnd

    /** Total number of occurrences, counting the first. */
    data class Count(val times: Int) : RecurrenceEnd
}

/**
 * Parse an RRULE into the picker's simple shape, or null when the rule uses
 * parts the picker can't represent (so the UI preserves the original string).
 * Accepts an optional leading "RRULE:" and an ignored WKST part. A datetime
 * UNTIL is converted from UTC into [zone] before its date is taken, mirroring
 * [toRRule].
 */
fun parseSimpleRecurrence(
    rrule: String,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): SimpleRecurrence? {
    val parts = rrule.removePrefix("RRULE:").split(';')
        .filter { it.isNotBlank() }
        .associate { token ->
            val eq = token.indexOf('=')
            if (eq <= 0) return null
            token.substring(0, eq).uppercase() to token.substring(eq + 1)
        }
    if (parts.keys.any { it !in setOf("FREQ", "INTERVAL", "UNTIL", "COUNT", "WKST", "BYDAY") }) {
        return null
    }

    val freq = when (parts["FREQ"]?.uppercase()) {
        "DAILY" -> RecurrenceFreq.Daily
        "WEEKLY" -> RecurrenceFreq.Weekly
        "MONTHLY" -> RecurrenceFreq.Monthly
        "YEARLY" -> RecurrenceFreq.Yearly
        else -> return null
    }
    val interval = parts["INTERVAL"]?.let { it.toIntOrNull()?.takeIf { n -> n >= 1 } ?: return null } ?: 1

    // BYDAY is simple only as plain weekday picks on a weekly rule; ordinal
    // forms ("2TH" = second Thursday) and BYDAY on other frequencies are not.
    val byDays = parts["BYDAY"]?.let { raw ->
        if (freq != RecurrenceFreq.Weekly) return null
        raw.split(',').map { token -> rruleDay(token.trim()) ?: return null }.toSet()
    } ?: emptySet()

    val until = parts["UNTIL"]
    val count = parts["COUNT"]
    if (until != null && count != null) return null
    val end = when {
        until != null -> RecurrenceEnd.Until(parseUntilDate(until, zone) ?: return null)
        count != null -> RecurrenceEnd.Count(count.toIntOrNull()?.takeIf { it >= 1 } ?: return null)
        else -> RecurrenceEnd.Never
    }
    return SimpleRecurrence(freq, interval, end, byDays)
}

/**
 * Render as a bare RRULE value (no "RRULE:" prefix — the store keeps the bare
 * value). UNTIL is written as the end of the chosen day *in [zone]*, expressed
 * in UTC, so a plain `T235959Z` cannot leak one extra day for zones ahead of UTC.
 */
fun SimpleRecurrence.toRRule(zone: TimeZone = TimeZone.currentSystemDefault()): String = buildString {
    append("FREQ=")
    append(
        when (freq) {
            RecurrenceFreq.Daily -> "DAILY"
            RecurrenceFreq.Weekly -> "WEEKLY"
            RecurrenceFreq.Monthly -> "MONTHLY"
            RecurrenceFreq.Yearly -> "YEARLY"
        },
    )
    if (interval > 1) append(";INTERVAL=$interval")
    if (freq == RecurrenceFreq.Weekly && byDays.isNotEmpty()) {
        append(";BYDAY=")
        append(
            byDays.sortedBy { it.isoDayNumber }
                .joinToString(",") { RRULE_DAY_CODES.getValue(it) },
        )
    }
    when (val e = end) {
        RecurrenceEnd.Never -> Unit
        is RecurrenceEnd.Until -> {
            val utc = LocalDateTime(e.date, LocalTime(23, 59, 59))
                .toInstant(zone)
                .toLocalDateTime(TimeZone.UTC)
            append(
                ";UNTIL=%04d%02d%02dT%02d%02d%02dZ".format(
                    utc.year, utc.month.number, utc.day,
                    utc.hour, utc.minute, utc.second,
                ),
            )
        }
        is RecurrenceEnd.Count -> append(";COUNT=${e.times}")
    }
}

internal val RRULE_DAY_CODES: Map<DayOfWeek, String> = mapOf(
    DayOfWeek.MONDAY to "MO",
    DayOfWeek.TUESDAY to "TU",
    DayOfWeek.WEDNESDAY to "WE",
    DayOfWeek.THURSDAY to "TH",
    DayOfWeek.FRIDAY to "FR",
    DayOfWeek.SATURDAY to "SA",
    DayOfWeek.SUNDAY to "SU",
)

/** Exact two-letter BYDAY token → weekday; ordinal forms ("2TH") return null. */
private fun rruleDay(token: String): DayOfWeek? =
    RRULE_DAY_CODES.entries.firstOrNull { it.value == token.uppercase() }?.key

/**
 * End an arbitrary RRULE (simple or not) at [untilUtcMillis]: any existing
 * UNTIL/COUNT is dropped, every other part (BYDAY, INTERVAL, …) survives.
 * Used for "delete this and all following occurrences" — the caller passes a
 * moment just before the first occurrence to remove.
 */
fun rruleTruncatedAt(rrule: String, untilUtcMillis: Long): String {
    val kept = rrule.removePrefix("RRULE:").split(';')
        .filter { it.isNotBlank() }
        .filterNot { part ->
            val key = part.substringBefore('=').trim().uppercase()
            key == "UNTIL" || key == "COUNT"
        }
    val until = Instant.fromEpochMilliseconds(untilUtcMillis).toLocalDateTime(TimeZone.UTC)
    val untilPart = "UNTIL=%04d%02d%02dT%02d%02d%02dZ".format(
        until.year, until.month.number, until.day,
        until.hour, until.minute, until.second,
    )
    return (kept + untilPart).joinToString(";")
}

/**
 * Date of an RRULE UNTIL value ("20260801" or "20260801T215959Z"). Datetime
 * forms are UTC (RFC 5545); the date is taken after converting into [zone] so
 * a [toRRule]-rendered value round-trips to the day the user picked.
 */
private fun parseUntilDate(raw: String, zone: TimeZone): LocalDate? = runCatching {
    val date = LocalDate(
        raw.substring(0, 4).toInt(),
        raw.substring(4, 6).toInt(),
        raw.substring(6, 8).toInt(),
    )
    if (raw.length >= 15 && raw[8] == 'T') {
        val time = LocalTime(
            raw.substring(9, 11).toInt(),
            raw.substring(11, 13).toInt(),
            raw.substring(13, 15).toInt(),
        )
        LocalDateTime(date, time).toInstant(TimeZone.UTC).toLocalDateTime(zone).date
    } else {
        date
    }
}.getOrNull()
