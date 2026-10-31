package com.aiexile.agendula.data.tasks.room

import com.aiexile.agendula.domain.ical.ICalComponent
import com.aiexile.agendula.domain.ical.ICalParser
import com.aiexile.agendula.domain.ical.ICalProperty
import com.aiexile.agendula.domain.ical.ICalSerializer
import com.aiexile.agendula.domain.ical.ICalValues
import kotlin.math.roundToLong
import kotlin.time.Instant

/**
 * Lifts claimable `VALARM`s out of a residue written before v3 synced them.
 *
 * ⚠️ Frozen with [TasksDatabase.MIGRATION_2_3]: a copy of `AlarmCodec`'s claim
 * rule as it stood then, not a call into it, so a later change to what the
 * codec claims cannot change what this migration produced for an earlier
 * upgrader.
 */
internal object AlarmLift {

    /** The task columns the claim rule reads. */
    data class Task(
        val dtstart: Instant?,
        val due: Instant?,
        val isAllDay: Boolean,
        val recurring: Boolean,
    )

    data class Alarm(val minutesBefore: Int, val reference: AlarmReference, val uid: String?, val raw: String)

    /** The lifted alarms and the residue without them, or `null` residue when nothing is left. */
    data class Result(val alarms: List<Alarm>, val residue: String?)

    private const val DAY_MINUTES = 24 * 60
    private const val MAX_RAW_BYTES = 25 * 1024

    fun lift(residue: String?, task: Task): Result {
        val component = parse(residue) ?: return Result(emptyList(), residue)
        val alarms = mutableListOf<Alarm>()
        val kept = component.components.filter { sub ->
            if (!sub.name.equals("VALARM", ignoreCase = true)) return@filter true
            val alarm = claim(sub, task) ?: return@filter true
            alarms += alarm
            false
        }
        if (alarms.isEmpty()) return Result(emptyList(), residue)
        val rest = ICalSerializer.serializeProperties(component.properties) + ICalSerializer.serializeAll(kept)
        return Result(alarms, rest.ifEmpty { null })
    }

    private fun claim(alarm: ICalComponent, task: Task): Alarm? {
        val action = alarm.property("ACTION")?.value?.trim()?.uppercase()
        if (action != "DISPLAY" && action != "AUDIO") return null
        if (alarm.property("X-APPLE-DEFAULT-ALARM")?.value?.trim().equals("TRUE", ignoreCase = true)) return null
        if (listOf("X-APPLE-PROXIMITY", "PROXIMITY", "REPEAT", "DURATION", "RELATED-TO").any { alarm.property(it) != null }) {
            return null
        }
        if (alarm.components.isNotEmpty()) return null
        val trigger = alarm.properties("TRIGGER").singleOrNull() ?: return null
        val value = trigger.value.trim()
        if (value.startsWith("19760401T005545Z")) return null
        val absolute = trigger.param("VALUE").equals("DATE-TIME", ignoreCase = true) ||
            (!value.startsWith("P", ignoreCase = true) && !value.startsWith("-") && !value.startsWith("+"))
        val (minutes, reference) = (if (absolute) absolute(trigger, task) else relative(trigger, task)) ?: return null
        val raw = ICalSerializer.serialize(alarm)
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_RAW_BYTES) return null
        val uid = (alarm.property("X-WR-ALARMUID") ?: alarm.property("UID"))?.value?.trim()?.takeIf { it.isNotEmpty() }
        return Alarm(minutes, reference, uid, raw)
    }

    private fun relative(trigger: ICalProperty, task: Task): Pair<Int, AlarmReference>? {
        val seconds = ICalValues.parseDuration(trigger.value) ?: return null
        val reference = when {
            trigger.param("RELATED").equals("END", ignoreCase = true) ->
                if (task.due != null) AlarmReference.DUE else return null
            task.dtstart != null && task.dtstart != task.due -> AlarmReference.START
            task.due != null -> AlarmReference.DUE
            else -> return null
        }
        val before = -seconds
        val minutes = if (task.isAllDay) days(before) else if (before % 60 != 0L) return null else before / 60
        return minutes.takeIf { it in 0..Int.MAX_VALUE }?.let { it.toInt() to reference }
    }

    private fun absolute(trigger: ICalProperty, task: Task): Pair<Int, AlarmReference>? {
        if (task.recurring) return null
        val due = task.due ?: return null
        val at = (ICalValues.parseTime(trigger) as? ICalValues.TimeValue.Timed)
            ?.takeIf { it.tzid == null }?.instant ?: return null
        val before = (due - at).inWholeSeconds
        val minutes = if (task.isAllDay) days(before) else Math.floorDiv(before, 60L)
        return minutes.takeIf { it in 0..Int.MAX_VALUE }?.let { it.toInt() to AlarmReference.DUE }
    }

    private fun days(seconds: Long): Long = (seconds / 86_400.0).roundToLong() * DAY_MINUTES

    private fun parse(text: String?): ICalComponent? {
        if (text.isNullOrEmpty()) return null
        return runCatching { ICalParser.parse("BEGIN:VTODO\r\n$text\r\nEND:VTODO\r\n") }.getOrNull()
    }
}
