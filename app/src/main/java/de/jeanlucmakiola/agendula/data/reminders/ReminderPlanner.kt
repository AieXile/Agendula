package de.jeanlucmakiola.agendula.data.reminders

import de.jeanlucmakiola.agendula.data.prefs.Settings
import de.jeanlucmakiola.agendula.data.tasks.TaskReminder
import de.jeanlucmakiola.agendula.domain.Task
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
        .filter { !it.isClosed && it.due != null }
        .flatMap { task ->
            // Reminders set on the task itself win — every one of them, not just
            // the one the editor shows; otherwise the task's list may override the
            // global lead, or opt out entirely (override = null). All-day tasks
            // have their own day-scale defaults.
            val reminders = perTask[task.taskId].orEmpty().ifEmpty {
                val leads = if (task.isAllDay) settings.allDayReminderLeadsFor(task.listId)
                else settings.reminderLeadsFor(task.listId)
                leads.map { TaskReminder(it) }
            }
            reminders.map { reminder ->
                // A stored reminder says what it counts back from. Ours are always
                // before due, but an imported dmfs alarm or another client's can be
                // before *start*.
                val anchor = if (reminder.fromStart) task.start ?: task.due!! else task.due!!
                ScheduledReminder(
                    taskId = task.taskId,
                    triggerAt = triggerAt(
                        anchor,
                        task.isAllDay,
                        reminder.minutesBefore,
                        settings.allDayReminderMinuteOfDay,
                        zone,
                    ),
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
     * Berlin and the evening before in New York. It is read as its calendar date
     * at [allDayMinuteOfDay] local time instead, and the lead counts back from that.
     */
    fun triggerAt(
        anchor: Instant,
        allDay: Boolean,
        leadMinutes: Int,
        allDayMinuteOfDay: Int,
        zone: ZoneId,
    ): Long {
        val base = if (allDay) {
            val time = LocalTime.of(allDayMinuteOfDay / 60 % 24, allDayMinuteOfDay % 60)
            anchor.calendarDate(allDay = true).atTime(time).atZone(zone).toInstant().toEpochMilli()
        } else {
            anchor.toEpochMilliseconds()
        }
        return base - leadMinutes.coerceAtLeast(0) * 60_000L
    }
}
