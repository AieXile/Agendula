package de.jeanlucmakiola.agendula.data.reminders

import de.jeanlucmakiola.agendula.data.prefs.Settings
import de.jeanlucmakiola.agendula.domain.AllDayReminder
import de.jeanlucmakiola.agendula.domain.Task
import de.jeanlucmakiola.agendula.domain.TaskReminder
import de.jeanlucmakiola.agendula.domain.calendarDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.time.Instant

/** Which alarms the scheduler wants armed, from the tasks and settings alone. */
internal object ReminderPlanner {

    const val WINDOW_MS = 30L * 24 * 60 * 60 * 1000 // 30 days

    /** How long after its trigger a missed reminder is still worth firing. */
    const val MISSED_GRACE_MS = 6L * 60 * 60 * 1000 // 6 hours

    /**
     * Alarms this app will hold at once.
     *
     * Android 12+ throws at 500 per uid. Well under it, because the count is
     * per *uid* and this is not the only thing in the process that can arm
     * one — and because the alarms nearest in time are the ones that matter,
     * while the far edge of the window is re-armed by the next sync.
     */
    const val MAX_ALARMS = 400

    fun plan(
        tasks: List<Task>,
        perTask: Map<Long, List<TaskReminder>>,
        settings: Settings,
        now: Long,
        zone: ZoneId,
    ): Set<ScheduledReminder> = tasks
        // One reminder per *occurrence* and lead: a recurring series yields a row
        // per occurrence, all sharing a taskId, so this is a Set rather than a
        // taskId-keyed Map.
        .filter { !it.isClosed && (it.due != null || it.start != null) }
        .flatMap { task ->
            // Reminders set on the task itself win — every one of them;
            // otherwise the task's list may override the global lead, or opt
            // out entirely (override = null). All-day tasks have their own
            // day-scale defaults.
            //
            // ⚠️ Present-but-empty is "none", not "the default": the store's
            // answer for a task whose alarms another client turned off. A
            // default here would keep ringing for what the user switched off.
            // A default counts only from a date the task has; its own reminders
            // stand in each date for the other when the task lacks it.
            val own = perTask[task.taskId]
            val reminders = own ?: settings.defaultRemindersFor(task.listId, task.isAllDay)
                .filter { (if (it.fromStart) task.start else task.due) != null }
            val created = task.created?.toEpochMilliseconds()
            reminders.mapNotNull { reminder ->
                val anchor = if (reminder.fromStart) task.start ?: task.due!! else task.due ?: task.start!!
                val trigger = triggerAt(
                    anchor,
                    task.isAllDay,
                    reminder.minutesBefore,
                    settings.allDayReminderMinuteOfDay,
                    zone,
                )
                // A default never rings for a moment before the task existed:
                // that is a task saved after its default had passed, not a
                // reminder missed while the device was off.
                if (own == null && created != null && trigger < created) return@mapNotNull null
                // Dismissed on another client already: firing it here within
                // the missed-reminder grace would ring for something done with.
                //
                // ⚠️ An all-day reminder without a time of its own is compared
                // from the start of its day, not from this device's time of
                // day: other clients ring it at midnight, or at their own hour,
                // so a dismissal at 07:00 must still silence the 09:00 this
                // device would ring it at.
                val earliest = if (task.isAllDay && reminder.allDay.minuteOfDay == null) {
                    triggerAt(anchor, true, reminder.minutesBefore, 0, zone)
                } else {
                    trigger
                }
                val acknowledged = reminder.acknowledgedAt?.let { earliest <= it.toEpochMilliseconds() } == true
                if (acknowledged) return@mapNotNull null
                ScheduledReminder(
                    taskId = task.taskId,
                    triggerAt = trigger,
                    occurrenceStart = task.occurrenceStart?.toEpochMilliseconds() ?: ScheduledReminder.NO_OCCURRENCE,
                )
            }
        }
        // The lower bound trails `now` so a reminder missed while the device was
        // off still fires once on boot instead of being silently dropped.
        .filter { it.triggerAt in (now - MISSED_GRACE_MS)..(now + WINDOW_MS) }
        // ⚠️ Bounded, soonest first: Android 12+ throws at 500 concurrent exact
        // alarms per app. What falls off is the far edge of the window, which the
        // next sync arms as it comes closer.
        .sortedBy { it.triggerAt }
        .take(MAX_ALARMS)
        .toSet()

    /**
     * When a reminder [leadMinutes] before [anchor] fires.
     *
     * ⚠️ An all-day anchor is UTC midnight of its date (see `AllDayTime.kt`), a
     * storage convention and not a moment — firing off it rang at 02:00 in
     * Berlin and the evening before in New York. It is read as its calendar
     * date instead, and the lead as [TaskReminder.allDay]: whole days ring at
     * [allDayMinuteOfDay] local time, anything else at the time it names.
     */
    fun triggerAt(
        anchor: Instant,
        allDay: Boolean,
        leadMinutes: Int,
        allDayMinuteOfDay: Int,
        zone: ZoneId,
    ): Long {
        if (!allDay) return anchor.toEpochMilliseconds() - leadMinutes.coerceAtLeast(0) * 60_000L
        val (days, minuteOfDay) = AllDayReminder.of(leadMinutes)
        val minute = minuteOfDay ?: allDayMinuteOfDay
        val time = LocalTime.of(minute / 60 % 24, minute % 60)
        return anchor.calendarDate(allDay = true).minusDays(days.toLong())
            .atTime(time).atZone(zone).toInstant().toEpochMilli()
    }
}
