package de.jeanlucmakiola.agendula.domain

import java.time.ZoneId
import kotlin.time.Instant

/** Local-day boundaries used by the smart-list predicates. Pure + testable. */
object DayWindow {

    /** Returns `[startOfToday, startOfTomorrow)` in [zone] for the instant [now]. */
    fun today(now: Instant, zone: ZoneId): Pair<Instant, Instant> {
        val date = java.time.Instant.ofEpochMilli(now.toEpochMilliseconds())
            .atZone(zone)
            .toLocalDate()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return Instant.fromEpochMilliseconds(start) to Instant.fromEpochMilliseconds(end)
    }
}
