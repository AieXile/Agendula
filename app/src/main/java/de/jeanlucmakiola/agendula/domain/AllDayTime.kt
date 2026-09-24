package de.jeanlucmakiola.agendula.domain

import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Instant

/**
 * All-day tasks are date-only in iCalendar. OpenTasks reads them back through
 * `DateTime.toAllDay()`, which discards the time-of-day and resolves the
 * remaining date against UTC — so the storage convention is **UTC midnight of
 * the intended calendar date, with a null timezone**. Timed tasks, by contrast,
 * are ordinary instants rendered in the device's zone.
 *
 * These two conventions disagree about which day a given instant is, which is
 * why every all-day value needs an explicit conversion rather than a raw
 * `Instant` passed straight through.
 */

/** UTC midnight of [date] — the storage form for an all-day value. */
fun allDayInstantOf(date: java.time.LocalDate): Instant =
    Instant.fromEpochMilliseconds(date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())

/**
 * The calendar date this instant denotes: read in UTC for [allDay] values,
 * in [zone] for timed ones.
 */
fun Instant.calendarDate(allDay: Boolean, zone: ZoneId = ZoneId.systemDefault()): java.time.LocalDate =
    java.time.Instant.ofEpochMilli(toEpochMilliseconds())
        .atZone(if (allDay) ZoneOffset.UTC else zone)
        .toLocalDate()

/**
 * Move an instant across the two conventions when the all-day switch flips, so
 * the day the user is looking at stays put. Without this, toggling all-day off
 * turns a UTC-midnight value into "02:00" in Berlin (or the previous day, 19:00,
 * in New York) — reading to the user as "the time reset itself".
 */
fun Instant.rebasedForAllDay(allDay: Boolean, zone: ZoneId = ZoneId.systemDefault()): Instant =
    if (allDay) allDayInstantOf(calendarDate(allDay = false, zone = zone))
    else Instant.fromEpochMilliseconds(
        calendarDate(allDay = true).atStartOfDay(zone).toInstant().toEpochMilli(),
    )
