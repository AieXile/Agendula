package de.jeanlucmakiola.agendula.ui.common

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.Instant

/**
 * Locale-aware display formatting for the [kotlin.time.Instant]s the domain
 * uses. Kept tiny and dependency-free; the data layer stores instants, the UI
 * renders them in the device's zone and locale.
 */
private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

private fun Instant.atSystemZone() =
    java.time.Instant.ofEpochMilli(toEpochMilliseconds())
        .atZone(ZoneId.systemDefault())

/** Medium localized date, e.g. "18 Jun 2026". */
fun Instant.formatDate(): String = atSystemZone().format(dateFormatter)

/** Short localized time, e.g. "14:30". */
fun Instant.formatTime(): String = atSystemZone().format(timeFormatter)

/** Date alone for all-day items, otherwise date + time. */
fun Instant.formatDateTime(allDay: Boolean): String =
    if (allDay) formatDate() else "${formatDate()} · ${formatTime()}"
