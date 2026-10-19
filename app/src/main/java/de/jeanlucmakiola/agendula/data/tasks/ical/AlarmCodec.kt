package de.jeanlucmakiola.agendula.data.tasks.ical

import de.jeanlucmakiola.agendula.data.reminders.ReminderPlanner
import de.jeanlucmakiola.agendula.data.tasks.room.AlarmReference
import de.jeanlucmakiola.agendula.data.tasks.room.TaskAlarmEntity
import de.jeanlucmakiola.agendula.data.tasks.room.TaskEntity
import de.jeanlucmakiola.agendula.domain.ical.ICalComponent
import de.jeanlucmakiola.agendula.domain.ical.ICalParam
import de.jeanlucmakiola.agendula.domain.ical.ICalParser
import de.jeanlucmakiola.agendula.domain.ical.ICalProperty
import de.jeanlucmakiola.agendula.domain.ical.ICalSerializer
import de.jeanlucmakiola.agendula.domain.ical.ICalValues
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
 * - a trigger *after* its anchor. The planner coerces a negative lead to 0, so
 *   it would fire at the wrong time;
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
 * which wants `DTSTART` present), and reading it as a before-due reminder is
 * what makes it editable here: the editor only shows before-due rows.
 *
 * ## All-day tasks
 *
 * The local fire time of day is a device preference this mapper cannot see, so
 * an all-day lead is whole days, written `-P<n>D` against `DUE`'s midnight. Read
 * back, any trigger on an all-day task rounds to the nearest whole day.
 */
object AlarmCodec {

    private const val DAY_MINUTES = 24 * 60

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
        val minutes = if (task.isAllDay) {
            // Rounded before the sign check: `PT9H` on a date-only DUE is
            // "09:00 on the day", which is this device's "on the day".
            roundToDays(before)
        } else {
            // A lead in seconds is not one the planner can reproduce.
            if (before % 60 != 0L) return null
            before / 60
        }
        return minutes.toIntOrNull()?.let { Lead(it, reference) }
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
     * ⚠️ A before-start row on a task that has lost its start is written
     * against `DUE`, which is what the planner fires it from. Leaving it out
     * would drop it from every other client, and the next download would then
     * take it off this device too.
     *
     * [now] marks an authored alarm `ACKNOWLEDGED` once it is past even the
     * planner's missed-reminder grace, so a client that honours RFC 9074 does
     * not greet it as a missed alarm. Not sooner: the stamp comes back on the
     * next download and would silence the late reminder this device still owes.
     */
    fun toVAlarm(row: TaskAlarmEntity, task: TaskEntity, now: Instant): ICalComponent? {
        val reference = if (row.reference == AlarmReference.START && task.dtstart == null) AlarmReference.DUE
        else row.reference
        val lead = Lead(row.minutesBefore, reference)
        row.raw?.let(::parse)?.let { original ->
            if (resolve(original, task) == lead) return original
        }

        val anchor = when (reference) {
            AlarmReference.DUE -> task.due
            AlarmReference.START -> task.dtstart
        } ?: return null
        val related = if (reference == AlarmReference.DUE) "END" else "START"
        val minutes = row.minutesBefore.coerceAtLeast(0).toLong()
        val seconds = if (task.isAllDay) {
            -((minutes + DAY_MINUTES / 2) / DAY_MINUTES) * 86_400
        } else {
            -minutes * 60
        }

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
     * always the row itself, because the wire is coarser: an all-day lead is
     * whole days, and a before-start row on a task without a start goes out
     * before-due. A download that brings back exactly this has not changed the
     * reminder, and the row's own lead must be kept rather than taken from it.
     */
    fun wireLead(row: TaskAlarmEntity, task: TaskEntity): Lead? =
        toVAlarm(row, task, Instant.DISTANT_PAST)?.let { resolve(it, task) }

    private fun parse(raw: String): ICalComponent? =
        runCatching { ICalParser.parse(raw) }.getOrNull()?.takeIf { it.name.equals("VALARM", ignoreCase = true) }
}
