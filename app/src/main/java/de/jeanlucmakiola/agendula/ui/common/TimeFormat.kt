package de.jeanlucmakiola.agendula.ui.common

import androidx.compose.runtime.staticCompositionLocalOf
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * The resolved clock convention for the whole UI, provided at the app root from
 * the time-format setting and the device's own 24-hour switch.
 */
val LocalUse24HourFormat = staticCompositionLocalOf { true }

/** The resolved first day of the week, provided at the app root from the week-start setting. */
val LocalFirstDayOfWeek = staticCompositionLocalOf { WeekFields.of(Locale.getDefault()).firstDayOfWeek }

/** A minute-of-day as a clock time: "14:00" or "2:00 PM". */
fun formatMinuteOfDay(minuteOfDay: Int, is24Hour: Boolean, locale: Locale = Locale.getDefault()): String =
    LocalTime.of(minuteOfDay / 60 % 24, minuteOfDay % 60)
        .format(DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a", locale))

/** The locale's first day of the week, for a week start left on automatic. */
fun localeFirstDayOfWeek(locale: Locale = Locale.getDefault()): DayOfWeek = WeekFields.of(locale).firstDayOfWeek
