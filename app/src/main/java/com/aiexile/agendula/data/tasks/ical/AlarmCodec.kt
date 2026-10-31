package com.aiexile.agendula.data.tasks.ical

import com.aiexile.agendula.data.reminders.ReminderPlanner
import com.aiexile.agendula.data.tasks.room.AlarmReference
import com.aiexile.agendula.data.tasks.room.TaskAlarmEntity
import com.aiexile.agendula.data.tasks.room.TaskEntity
import com.aiexile.agendula.domain.ical.ICalComponent
import com.aiexile.agendula.domain.ical.ICalParam
import com.aiexile.agendula.domain.ical.ICalParser
import com.aiexile.agendula.domain.ical.ICalProperty
import com.aiexile.agendula.domain.ical.ICalSerializer
import com.aiexile.agendula.domain.ical.ICalValues
import com.aiexile.agendula.domain.DAY_MINUTES
import com.aiexile.agendula.domain.MIN_ALL_DAY_LEAD
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * `VALARM` ↔ [TaskAlarmEntity].
 *
 * ## What is claimed
 *
 * Only an alarm Agendula can fire exactly as written becomes a reminder row.
 * Everything else stays in the task's residue and round-trips untouched — it
 * still fires in the client that wrote it, just not here:
 *
 * - `ACTION` other than `DISPLAY`/`AUDIO`;
 * - Apple's default alarms (`X-APPLE-DEFAULT-ALARM:TRUE`). They are that Mac's
 *   preference, not task data: claimed, they would come back to the Mac as an
 *   explicit alarm without the flag after the first edit here;
 * - location alarms (`X-APPLE-PROXIMITY`, RFC 9074 `PROXIMITY`, a nested
 *   component) and Apple's `19760401T005545Z` sentinel trigger they carry;
 * - repeating alarms (`REPEAT`/`DURATION`) and RFC 9074 snoozes (`RELATED-TO`);
 * - a trigger *after* its anchor — on an all-day task, after the end of its
 *   day. The planner coerces a negative lead to 0, so it would fire at the
 *   wrong time;
 * - an absolute trigger on a recurring task, or on one with no `DUE` to count
 *   back from.
 *
 * ## What "unchanged" means
 *
 * A claimed row keeps the `VALARM` it was read from in [TaskAlarmEntity.raw].
 * On write, the row is "unchanged" when that original still [resolve]s to the
 * row's lead against the task *as it is now* — so `-PT15M` and `-P0DT0H15M0S`
 * are the same, a relative alarm survives a due-date edit, and an absolute one
 * stops matching once `DUE` moves. An unchanged row is re-emitted verbatim; a
 * changed one is authored fresh under its existing uid.
 *
 * ## Anchors
 *
 * `RELATED=END` counts from `DUE`. `RELATED=START` (the default) counts from
 * `DTSTART` — except when `DTSTART` equals `DUE`, or when there is no
 * `DTSTART` but there is a `DUE`. Apple writes the latter shape (against §3.8.6.3,
 * which wants `DTSTART` present).
 *
 * ## All-day tasks
 *
 * A relative trigger counts from the date's midnight, so the lead is the
 * reminder as [com.aiexile.agendula.domain.TaskReminder.allDay] reads it:
 * `-P1D` is a day before at this device's all-day time, which this mapper cannot
 * see, and `PT9H` is 09:00 on the day. An absolute trigger has no zone to read
 * a time of day in, so it rounds to the nearest whole day.
 */
object AlarmCodec {

    /** Apple's placeholder trigger for location alarms. Never a real time. */
    private const val APPLE_SENTINEL = "19760401T005545Z"

    /** What a `VALARM` means to the scheduler. */
    data class Lead(val minutesBefore: Int, val reference: AlarmReference)

    /** A `VALARM` as a reminder row — `taskId` is the caller's — or null if it is not ours to claim. */
    fun fromVAlarm(alarm: ICalComponent, task: TaskEntity): TaskAlarmEntity? {
        val lead = resolve(alarm, task) ?: return null
        val raw = ICalSerializer.serialize(alarm)
        // Too large to keep verbatim means it could not be re-emitted, and an
        // alarm that is authored fresh on every write loses what made it large.
        if (raw.toByteArray(Charsets.UTF_8).size > VTodoMapper.MAX_RESIDUE_BYTES) return null
        val uid = (alarm.property("X-WR-ALARMUID") ?: alarm.property("UID"))?.value?.trim()?.takeIf { it.isNotEmpty() }
        val base = TaskAlarmEntity(
            taskId = 0,
            minutesBefore = lead.minutesBefore,
            reference = lead.reference,
            raw = raw,
        )
        return if (uid == null) base else base.copy(uid = uid)
    }

    /** The lead [alarm] stands for on [task], or null when it is not claimable. */
    fun resolve(alarm: ICalComponent, task: TaskEntity): Lead? {
        val action = alarm.property("ACTION")?.value?.trim()?.uppercase()
        if (action != "DISPLAY" && action != "AUDIO") return null
        if (alarm.property("X-APPLE-DEFAULT-ALARM")?.value?.trim().equals("TRUE", ignoreCase = true)) return null
        val excluded = listOf("X-APPLE-PROXIMITY", "PROXIMITY", "REPEAT", "DURATION", "RELATED-TO")
        if (excluded.any { alarm.property(it) != null }) return null
        if (alarm.components.isNotEmpty()) return null

        val trigger = alarm.properties("TRIGGER").singleOrNull() ?: return null
        val value = trigger.value.trim()
        if (value.startsWith(APPLE_SENTINEL)) return null

        val absolute = trigger.param("VALUE").equals("DATE-TIME", ignoreCase = true) ||
            (!value.startsWith("P", ignoreCase = true) && !value.startsWith("-") && !value.startsWith("+"))
        return if (absolute) absoluteLead(trigger, task) else relativeLead(trigger, task)
    }

    private fun relativeLead(trigger: ICalProperty, task: TaskEntity): Lead? {
        val seconds = ICalValues.parseDuration(trigger.value) ?: return null
        val reference = when {
            trigger.param("RELATED").equals("END", ignoreCase = true) ->
                if (task.due != null) AlarmReference.DUE else return null
            task.dtstart != null && task.dtstart != task.due -> AlarmReference.START
            task.due != null -> AlarmReference.DUE
            else -> return null
        }
        val before = -seconds
        // A lead in seconds is not one the planner can reproduce.
        if (before % 60 != 0L) return null
        val minutes = before / 60
        val latest = if (task.isAllDay) MIN_ALL_DAY_LEAD.toLong() else 0L
        return minutes.takeIf { it in latest..Int.MAX_VALUE }?.let { Lead(it.toInt(), reference) }
    }

    private fun absoluteLead(trigger: ICalProperty, task: TaskEntity): Lead? {
        if (task.rrule != null || task.rdate != null) return null
        val due = task.due ?: return null
        val at = (ICalValues.parseTime(trigger) as? ICalValues.TimeValue.Timed)
            ?.takeIf { it.tzid == null } // §3.8.6.3: an absolute trigger MUST be UTC
            ?.instant ?: return null
        val before = (due - at).inWholeSeconds
        val minutes = if (task.isAllDay) {
            // An all-day DUE is stored as UTC midnight of its date, which is
            // the calendar date the trigger counts back from.
            roundToDays(before)
        } else {
            Math.floorDiv(before, 60L)
        }
        return minutes.toIntOrNull()?.let { Lead(it, AlarmReference.DUE) }
    }

    /**
     * [read] as builds before 1.2 stored it on an all-day task: rounded to whole
     * days. Such a row keeps the other client's time of day instead of overwriting it.
     */
    private fun readAsWholeDays(read: Lead, task: TaskEntity): Lead? =
        if (task.isAllDay && read.minutesBefore % DAY_MINUTES != 0) {
            read.copy(minutesBefore = roundToDays(read.minutesBefore * 60L).coerceAtLeast(0).toInt())
        } else {
            null
        }

    /** Nearest whole day, in minutes. */
    private fun roundToDays(seconds: Long): Long =
        (seconds / 86_400.0).roundToLong() * DAY_MINUTES

    /** Negative is after the anchor, which is not claimed. */
    private fun Long.toIntOrNull(): Int? = takeIf { it in 0..Int.MAX_VALUE }?.toInt()

    /**
     * [row] as a `VALARM` on [task], or null when it cannot be written: its
     * anchor is missing. That row stays on the device — the planner ignores it
     * too — because `RELATED=END` without `DUE` is a resource the server
     * refuses ([VTodoMapper.validate]).
     *
     * ⚠️ A row whose anchor the task has lost is written against the other
     * date, which is what the planner fires it from. Leaving it out would drop
     * it from every other client, and the next download would then take it off
     * this device too.
     *
     * [now] marks an authored alarm `ACKNOWLEDGED` once it is past even the
     * planner's missed-reminder grace, so a client that honours RFC 9074 does
     * not greet it as a missed alarm. Not sooner: the stamp comes back on the
     * next download and would silence the late reminder this device still owes.
     */
    fun toVAlarm(row: TaskAlarmEntity, task: TaskEntity, now: Instant): ICalComponent? {
        val reference = when {
            row.reference == AlarmReference.START && task.dtstart == null -> AlarmReference.DUE
            row.reference == AlarmReference.DUE && task.due == null -> AlarmReference.START
            else -> row.reference
        }
        val lead = Lead(row.minutesBefore, reference)
        row.raw?.let(::parse)?.let { original ->
            val read = resolve(original, task)
            if (read == lead || read != null && readAsWholeDays(read, task) == lead) return original
        }

        val anchor = when (reference) {
            AlarmReference.DUE -> task.due
            AlarmReference.START -> task.dtstart
        } ?: return null
        val related = if (reference == AlarmReference.DUE) "END" else "START"
        val minutes = row.minutesBefore.coerceAtLeast(if (task.isAllDay) MIN_ALL_DAY_LEAD else 0).toLong()
        val seconds = -minutes * 60

        val properties = mutableListOf(
            ICalProperty("UID", value = row.uid),
            ICalProperty("X-WR-ALARMUID", value = row.uid),
            ICalProperty("ACTION", value = "DISPLAY"),
            ICalProperty("DESCRIPTION", value = ICalValues.escapeText(row.message ?: "Reminder")),
            ICalProperty("TRIGGER", listOf(ICalParam("RELATED", related)), ICalValues.formatDuration(seconds)),
        )
        val recurring = task.rrule != null || task.rdate != null
        if (!recurring && anchor + seconds.seconds + ReminderPlanner.MISSED_GRACE_MS.milliseconds < now) {
            properties += ICalProperty("ACKNOWLEDGED", value = ICalValues.formatDateTime(now, null))
        }
        return ICalComponent("VALARM", properties)
    }

    /**
     * What [row] reads back as once it has been to the server and back — not
     * always the row itself, because the wire is coarser: a lead past the end
     * of its anchor is clamped, and a before-start row on a task without a
     * start goes out before-due. A download that brings back exactly this has not changed the
     * reminder, and the row's own lead must be kept rather than taken from it.
     *
     * ⚠️ As authored from the row, not re-emitted from [TaskAlarmEntity.raw]:
     * a row rounded to whole days before 1.2 re-emits a time of day it does
     * not hold, and would keep its rounded lead against the very alarm that
     * carries it.
     */
    fun wireLead(row: TaskAlarmEntity, task: TaskEntity): Lead? =
        toVAlarm(row.copy(raw = null), task, Instant.DISTANT_PAST)?.let { resolve(it, task) }

    private fun parse(raw: String): ICalComponent? =
        runCatching { ICalParser.parse(raw) }.getOrNull()?.takeIf { it.name.equals("VALARM", ignoreCase = true) }
}
