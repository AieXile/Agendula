package de.jeanlucmakiola.agendula.ui.common

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.time.Instant

/**
 * Zone helpers shared by the date/time pickers. All-day conversions live in
 * [de.jeanlucmakiola.agendula.domain.AllDayTime] — these cover the timed case,
 * where the device zone is the right frame of reference.
 */

private val zone: ZoneId get() = ZoneId.systemDefault()

internal fun Instant.toLocalTime(): LocalTime =
    java.time.Instant.ofEpochMilli(toEpochMilliseconds()).atZone(zone).toLocalTime()

internal fun localToInstant(date: LocalDate, time: LocalTime): Instant =
    Instant.fromEpochMilliseconds(date.atTime(time).atZone(zone).toInstant().toEpochMilli())
