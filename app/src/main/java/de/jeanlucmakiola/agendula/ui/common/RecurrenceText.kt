package de.jeanlucmakiola.agendula.ui.common

import android.icu.text.ListFormatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import de.jeanlucmakiola.agendula.R
import de.jeanlucmakiola.agendula.domain.recurrence.SimpleRecurrence
import de.jeanlucmakiola.agendula.domain.recurrence.occurrencesSpanYears
import de.jeanlucmakiola.agendula.domain.recurrence.upcomingOccurrences
import de.jeanlucmakiola.floret.locale.localizedDateFormatter
import kotlinx.datetime.toJavaLocalDate
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/**
 * Humanise an RFC 5545 RRULE into a localized phrase, e.g.
 * "Every week on Tue and Thu until 31 Dec 2026" or "Every day, 10 times".
 * Rules we don't render in full (ordinal monthly/yearly BYDAY, …) fall back to
 * a generic label. Weekday names are italicised.
 */
@Composable
fun recurrenceText(rrule: String, locale: Locale): AnnotatedString {
    val parts = rrule.removePrefix("RRULE:").split(';').mapNotNull { token ->
        val eq = token.indexOf('=')
        if (eq <= 0) null else token.substring(0, eq).uppercase() to token.substring(eq + 1)
    }.toMap()

    val freq = parts["FREQ"]?.uppercase()
    val interval = parts["INTERVAL"]?.toIntOrNull() ?: 1
    val base = when (freq) {
        "DAILY" -> if (interval == 1) stringResource(R.string.recurrence_daily)
        else pluralStringResource(R.plurals.recurrence_every_n_days, interval, interval)
        "WEEKLY" -> if (interval == 1) stringResource(R.string.recurrence_weekly)
        else pluralStringResource(R.plurals.recurrence_every_n_weeks, interval, interval)
        "MONTHLY" -> if (interval == 1) stringResource(R.string.recurrence_monthly)
        else pluralStringResource(R.plurals.recurrence_every_n_months, interval, interval)
        "YEARLY" -> if (interval == 1) stringResource(R.string.recurrence_yearly)
        else pluralStringResource(R.plurals.recurrence_every_n_years, interval, interval)
        else -> return AnnotatedString(stringResource(R.string.recurrence_generic))
    }

    // Weekly + BYDAY → "<base> on <days>"; other BYDAY forms keep just the base.
    val byDay = parts["BYDAY"]
    var dayNames: List<String>? = null
    var joinedDays: String? = null
    val main = if (freq == "WEEKLY" && !byDay.isNullOrBlank()) {
        val days = byDay.split(',').mapNotNull { rruleDayName(it.trim(), locale) }
        if (days.isNotEmpty()) {
            val joined = ListFormatter.getInstance(locale).format(days)
            dayNames = days
            joinedDays = joined
            stringResource(R.string.recurrence_on_days, base, joined)
        } else {
            base
        }
    } else {
        base
    }

    // UNTIL takes precedence over COUNT.
    val until = parts["UNTIL"]?.let { formatUntilDate(it, locale) }
    val count = parts["COUNT"]?.toIntOrNull()
    val full = when {
        until != null -> stringResource(R.string.recurrence_with_until, main, until)
        count != null -> pluralStringResource(R.plurals.recurrence_with_count, count, main, count)
        else -> main
    }

    return buildAnnotatedString {
        append(full)
        val names = dayNames
        val joined = joinedDays
        if (names != null && joined != null) {
            // Only the names are italicised, not the separators or conjunction.
            val regionStart = full.indexOf(joined)
            if (regionStart >= 0) {
                val regionEnd = regionStart + joined.length
                var cursor = regionStart
                for (name in names) {
                    val at = full.indexOf(name, cursor)
                    if (at in regionStart until regionEnd) {
                        addStyle(SpanStyle(fontStyle = FontStyle.Italic), at, at + name.length)
                        cursor = at + name.length
                    }
                }
            }
        }
    }
}

/**
 * The rule's first few dates as one line — "Next: 30 Jul, 6 Aug and 13 Aug" —
 * for the recurrence picker: [recurrenceText] says what the rule is, this says
 * what it does. A rule that yields nothing says so.
 */
@Composable
fun nextOccurrencesText(
    rule: SimpleRecurrence,
    start: kotlinx.datetime.LocalDate,
    locale: Locale,
): String {
    val dates = rule.upcomingOccurrences(start, limit = NEXT_OCCURRENCE_COUNT)
    if (dates.isEmpty()) return stringResource(R.string.recurrence_next_none)
    val pattern = if (occurrencesSpanYears(dates, start)) "dMMMy" else "dMMM"
    val formatter = localizedDateFormatter(locale, pattern)
    val formatted = dates.map { formatter.format(it.toJavaLocalDate()) }
    return stringResource(
        R.string.recurrence_next,
        ListFormatter.getInstance(locale).format(formatted),
    )
}

private const val NEXT_OCCURRENCE_COUNT = 3

/** Map an RRULE BYDAY token (e.g. "TU" or "2TH") to a localized short weekday name. */
private fun rruleDayName(token: String, locale: Locale): String? {
    val dow = when (token.takeLast(2).uppercase()) {
        "MO" -> DayOfWeek.MONDAY
        "TU" -> DayOfWeek.TUESDAY
        "WE" -> DayOfWeek.WEDNESDAY
        "TH" -> DayOfWeek.THURSDAY
        "FR" -> DayOfWeek.FRIDAY
        "SA" -> DayOfWeek.SATURDAY
        "SU" -> DayOfWeek.SUNDAY
        else -> return null
    }
    return dow.getDisplayName(JavaTextStyle.SHORT, locale)
}

private fun formatUntilDate(raw: String, locale: Locale): String? {
    val date = untilLocalDate(raw) ?: return null
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)
}

private val UNTIL_UTC_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT)

/**
 * The calendar day an RRULE UNTIL value denotes, in [zone]. The UTC form is
 * converted back first — `toRRule` writes the end of the chosen local day in
 * UTC, which for zones behind UTC already falls on the next UTC date. Date-only
 * and floating forms pass through unchanged.
 */
internal fun untilLocalDate(raw: String, zone: ZoneId = ZoneId.systemDefault()): LocalDate? {
    val value = raw.trim()
    return runCatching {
        LocalDateTime.parse(value, UNTIL_UTC_FORMAT)
            .atOffset(ZoneOffset.UTC)
            .atZoneSameInstant(zone)
            .toLocalDate()
    }.recoverCatching {
        val digits = value.takeWhile { it.isDigit() }
        LocalDate.of(
            digits.substring(0, 4).toInt(),
            digits.substring(4, 6).toInt(),
            digits.substring(6, 8).toInt(),
        )
    }.getOrNull()
}
