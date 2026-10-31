package com.aiexile.agendula.domain.ical

import com.aiexile.agendula.domain.allDayInstantOf
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Instant

/** Value-level codecs: RFC 5545 TEXT escaping and the DATE / DATE-TIME forms. */
object ICalValues {

    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /**
     * RFC 5545 §3.3.11. Note the asymmetry with [unescapeText]: a literal colon
     * needs no escape in a property value, and escaping it is a common bug that
     * other clients then have to undo.
     */
    fun escapeText(value: String): String = value
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\r\n", "\\n")
        .replace("\n", "\\n")
        .replace("\r", "\\n")

    fun unescapeText(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    'n', 'N' -> out.append('\n')
                    '\\', ';', ',' -> out.append(next)
                    // An unknown escape is left as it was found; inventing a
                    // meaning for it would corrupt the value on write-back.
                    else -> out.append(c).append(next)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /**
     * A multi-valued TEXT property (`CATEGORIES`, §3.8.1.2): split on the
     * commas that are not escaped, then unescape each value.
     */
    fun parseTextList(value: String): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '\\' && i + 1 < value.length -> {
                    current.append(c).append(value[i + 1])
                    i += 2
                    continue
                }
                c == ',' -> {
                    values += unescapeText(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        values += unescapeText(current.toString())
        return values
    }

    /** The inverse of [parseTextList]. */
    fun formatTextList(values: List<String>): String = values.joinToString(",", transform = ::escapeText)

    /** How a DATE / DATE-TIME property was written, and whether we can reproduce it. */
    sealed interface TimeValue {
        /** `VALUE=DATE` — date-only. */
        data class Date(val instant: Instant) : TimeValue

        /** A UTC instant (`…Z`), or a zoned one whose `TZID` the device knows. */
        data class Timed(val instant: Instant, val tzid: String?) : TimeValue

        /**
         * A form we can read but not reproduce: a floating time (no `Z`, no
         * `TZID`), or a `TZID` absent from the device's tzdb. [instant] is a
         * best-effort reading for the UI; the property itself stays in the
         * residue and is re-emitted verbatim, so nothing is lost on write-back.
         */
        data class Unrepresentable(val instant: Instant?, val reason: String) : TimeValue
    }

    /**
     * Reads a DATE or DATE-TIME property.
     *
     * The unknown-`TZID` case is the one that matters: RFC 5545 lets a file
     * carry its own `VTIMEZONE` for a zone the device has never heard of, and
     * guessing UTC there silently moves the user's task by hours. It is reported
     * as [TimeValue.Unrepresentable] instead.
     */
    fun parseTime(property: ICalProperty): TimeValue {
        val raw = property.value.trim()
        val isDate = property.param("VALUE").equals("DATE", ignoreCase = true) ||
            (raw.length == 8 && !raw.contains('T'))

        if (isDate) {
            val date = runCatching { LocalDate.parse(raw, DATE) }.getOrNull()
                ?: return TimeValue.Unrepresentable(null, "unparseable DATE")
            return TimeValue.Date(allDayInstantOf(date))
        }

        val utc = raw.endsWith("Z")
        val local = runCatching { LocalDateTime.parse(raw.removeSuffix("Z"), DATE_TIME) }.getOrNull()
            ?: return TimeValue.Unrepresentable(null, "unparseable DATE-TIME")

        if (utc) return TimeValue.Timed(local.toInstant(ZoneOffset.UTC).toKotlin(), null)

        val tzid = property.param("TZID")
            ?: return TimeValue.Unrepresentable(
                local.toInstant(ZoneOffset.UTC).toKotlin(),
                "floating time",
            )

        val zone = runCatching { ZoneId.of(tzid) }.getOrNull()
            ?: return TimeValue.Unrepresentable(
                local.toInstant(ZoneOffset.UTC).toKotlin(),
                "unknown TZID $tzid",
            )

        return TimeValue.Timed(local.atZone(zone).toInstant().toKotlin(), tzid)
    }

    /** The instant this value denotes, however well it could be read. */
    fun readInstant(value: TimeValue): Instant? = when (value) {
        is TimeValue.Date -> value.instant
        is TimeValue.Timed -> value.instant
        is TimeValue.Unrepresentable -> value.instant
    }

    /** `20260904` — the all-day form. */
    fun formatDate(instant: Instant): String =
        java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds())
            .atZone(ZoneOffset.UTC)
            .format(DATE)

    /** `20260904T080000Z`, or the local form when [tzid] names a zone we know. */
    fun formatDateTime(instant: Instant, tzid: String?): String {
        val moment = java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds())
        val zone = tzid?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        return if (zone == null) {
            moment.atZone(ZoneOffset.UTC).format(DATE_TIME) + "Z"
        } else {
            moment.atZone(zone).format(DATE_TIME)
        }
    }

    private val DURATION = Regex(
        "([+-])?P(?:(\\d+)W|(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * RFC 5545 §3.3.6 `dur-value` in signed seconds: `-PT15M` → -900, `P1W` →
     * 604800, `-P1DT2H` → -93600. Null when it does not parse.
     *
     * A little more lenient than the grammar — `PT1H5S` skips the minutes the
     * grammar would require — because a stricter reader would leave another
     * client's alarm unread over a form nobody misreads.
     */
    fun parseDuration(value: String): Long? {
        val match = DURATION.matchEntire(value.trim()) ?: return null
        val (sign, weeks, days, hours, minutes, seconds) = match.destructured
        // "P" and "PT" alone match the pattern and mean nothing.
        if (listOf(weeks, days, hours, minutes, seconds).all(String::isEmpty)) return null
        if (value.trim().endsWith("T", ignoreCase = true)) return null
        fun part(text: String, scale: Long): Long =
            if (text.isEmpty()) 0L else Math.multiplyExact(text.toLong(), scale)
        val total = runCatching {
            listOf(
                part(weeks, 7 * 86_400L), part(days, 86_400L),
                part(hours, 3_600L), part(minutes, 60L), part(seconds, 1L),
            ).reduce(Math::addExact)
        }.getOrNull() ?: return null
        return if (sign == "-") -total else total
    }

    /** The inverse of [parseDuration], in days and time, never weeks: `-PT15M`, `-P1D`, `PT0S`. */
    fun formatDuration(seconds: Long): String {
        if (seconds == 0L) return "PT0S"
        val magnitude = kotlin.math.abs(seconds)
        val days = magnitude / 86_400
        val hours = magnitude % 86_400 / 3_600
        val minutes = magnitude % 3_600 / 60
        val rest = magnitude % 60
        return buildString {
            if (seconds < 0) append('-')
            append('P')
            if (days > 0) append(days).append('D')
            if (hours > 0 || minutes > 0 || rest > 0) {
                append('T')
                if (hours > 0) append(hours).append('H')
                if (minutes > 0) append(minutes).append('M')
                if (rest > 0) append(rest).append('S')
            }
        }
    }

    private fun java.time.Instant.toKotlin(): Instant =
        Instant.fromEpochMilliseconds(toEpochMilli())
}
