package de.jeanlucmakiola.agendula.domain.ical

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.zone.ZoneOffsetTransitionRule

/**
 * Builds a `VTIMEZONE` for an IANA zone id.
 *
 * ⚠️ This is not optional decoration. RFC 5545 §3.2.19: a `TZID` parameter
 * without a leading solidus **must** reference a `VTIMEZONE` in the same
 * calendar object. The mapper emits `DTSTART;TZID=Europe/Berlin:…` whenever a
 * task carries a zone, so a resource that ships those without a definition is
 * malformed — and `Prefer: handling=strict`, which we send precisely so servers
 * stop silently repairing our bytes, turns "malformed" from a shrug into a
 * rejection.
 *
 * The definition is generated from `java.time`'s own rules rather than carried
 * from the server, which is a deliberate trade. A server's `VTIMEZONE` is opaque
 * to us and lives at `VCALENDAR` level, outside the `VTODO` the mapper stores
 * residue for; regenerating it means the zone we write always agrees with the
 * zone we compute occurrences in. The round-trip corpus already treats
 * `VTIMEZONE` bodies as opaque for exactly this reason.
 *
 * **Bounded on purpose:** only the currently-effective rules are emitted, not
 * the full historical transition table. Tasks are dated now or later; reproducing
 * a century of political time changes to place a due date is not a trade worth
 * making.
 */
object VTimeZones {

    /** The `VTIMEZONE` for [zoneId], or null when the id is not resolvable. */
    fun forZone(zoneId: String): ICalComponent? {
        val zone = runCatching { ZoneId.of(zoneId) }.getOrNull() ?: return null
        val rules = zone.rules

        val observances = if (rules.transitionRules.isEmpty()) {
            // Either a fixed-offset zone, or one whose DST was abolished and
            // whose rules therefore ended. Both are a single standard offset from
            // here on, which is the only period a task can fall in.
            val offset = rules.getStandardOffset(java.time.Instant.now())
            listOf(fixedObservance(offset))
        } else {
            rules.transitionRules.map(::observance)
        }

        return ICalComponent(
            name = "VTIMEZONE",
            properties = listOf(ICalProperty("TZID", emptyList(), zoneId)),
            components = observances,
        )
    }

    /** The `VTIMEZONE`s referenced by any `TZID` parameter under [component]. */
    fun forComponent(component: ICalComponent): List<ICalComponent> =
        tzids(component).sorted().mapNotNull(::forZone)

    private fun tzids(component: ICalComponent): Set<String> {
        val here = component.properties.mapNotNull { it.param("TZID") }
        return (here + component.components.flatMap { tzids(it) })
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun fixedObservance(offset: ZoneOffset) = ICalComponent(
        name = "STANDARD",
        properties = listOf(
            ICalProperty("DTSTART", emptyList(), "19700101T000000"),
            ICalProperty("TZOFFSETFROM", emptyList(), format(offset)),
            ICalProperty("TZOFFSETTO", emptyList(), format(offset)),
        ),
        components = emptyList(),
    )

    private fun observance(rule: ZoneOffsetTransitionRule): ICalComponent {
        val daylight = rule.offsetAfter.totalSeconds > rule.standardOffset.totalSeconds
        // The rule's own first transition, so DTSTART is a real instance of the
        // recurrence rather than an arbitrary date that happens to share a month.
        val start = rule.createTransition(ANCHOR_YEAR).dateTimeBefore
        return ICalComponent(
            name = if (daylight) "DAYLIGHT" else "STANDARD",
            properties = listOfNotNull(
                ICalProperty("DTSTART", emptyList(), formatLocal(start)),
                ICalProperty("TZOFFSETFROM", emptyList(), format(rule.offsetBefore)),
                ICalProperty("TZOFFSETTO", emptyList(), format(rule.offsetAfter)),
                rrule(rule)?.let { ICalProperty("RRULE", emptyList(), it) },
            ),
            components = emptyList(),
        )
    }

    private fun rrule(rule: ZoneOffsetTransitionRule): String? {
        val month = rule.month.value
        val day = rule.dayOfMonthIndicator
        val dow = rule.dayOfWeek ?: return "FREQ=YEARLY;BYMONTH=$month;BYMONTHDAY=$day"

        val byDay = when {
            // ⚠️ java.time does not encode "the last <dow>" as -1. The EU rule
            // arrives as dayOfMonthIndicator = 25 — "the first Sunday on or after
            // the 25th" — and only the fact that a seven-day window ending on the
            // last day of the month *is* the last occurrence identifies it.
            // Missing this emits a seven-value BYMONTHDAY list where every other
            // client writes BYDAY=-1SU.
            day > 0 && day + 6 == rule.month.maxLength() -> "-1${abbreviate(dow)}"
            day == -1 -> "-1${abbreviate(dow)}"
            // "the nth <dow>", which java.time encodes as "on or after day
            // 1 / 8 / 15 / 22".
            day > 0 && (day - 1) % 7 == 0 -> "${(day - 1) / 7 + 1}${abbreviate(dow)}"
            else -> null
        }
        if (byDay != null) return "FREQ=YEARLY;BYMONTH=$month;BYDAY=$byDay"

        // Anything else is "the first <dow> on or after day N", which iCalendar
        // can only say as a day-of-week plus the seven dates it could land on.
        if (day <= 0) return null
        val days = (day until day + 7).joinToString(",")
        return "FREQ=YEARLY;BYMONTH=$month;BYDAY=${abbreviate(dow)};BYMONTHDAY=$days"
    }

    private fun abbreviate(day: DayOfWeek) = when (day) {
        DayOfWeek.MONDAY -> "MO"
        DayOfWeek.TUESDAY -> "TU"
        DayOfWeek.WEDNESDAY -> "WE"
        DayOfWeek.THURSDAY -> "TH"
        DayOfWeek.FRIDAY -> "FR"
        DayOfWeek.SATURDAY -> "SA"
        DayOfWeek.SUNDAY -> "SU"
    }

    /** `+HHMM`, or `+HHMMSS` for the handful of zones with a sub-minute offset. */
    private fun format(offset: ZoneOffset): String {
        val total = offset.totalSeconds
        val sign = if (total < 0) "-" else "+"
        val abs = kotlin.math.abs(total)
        val hours = abs / 3600
        val minutes = (abs % 3600) / 60
        val seconds = abs % 60
        return buildString {
            append(sign)
            append("%02d%02d".format(hours, minutes))
            if (seconds != 0) append("%02d".format(seconds))
        }
    }

    private fun formatLocal(time: LocalDateTime): String =
        "%04d%02d%02dT%02d%02d%02d".format(
            time.year, time.monthValue, time.dayOfMonth,
            time.hour, time.minute, time.second,
        )

    /**
     * The year the observance `DTSTART`s are anchored to.
     *
     * Any year the rule applies in produces an equivalent definition; 1970 is
     * the conventional choice and keeps generated bodies stable across runs.
     */
    private const val ANCHOR_YEAR = 1970
}
